package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R4-4 (1), round-3 report section 4 item 6: a first message must never go out as a REPLY because a CONTROL
 * message created the conversation first.
 *
 * The first message to a new contact is a v4 INIT: it carries our address, which is how the recipient learns
 * who wrote. R2-6 (c) marks a conversation id created by a message send as "INIT not sent" until the INIT's
 * submit starts, and R3-2 (1) gave our KEX the same treatment. Every OTHER sender still asks
 * getOrCreateConversationId, which stores a new id with NO such mark: the disappearing-messages timer, the
 * mode change, the Tunnel ZBOOT handshake and the rotation announce. When one of those runs before the user's
 * first message, that message finds an id, counts the conversation as started and goes out as a REPLY carrying
 * only a hash of our address, which the recipient cannot place.
 *
 * Must hold: after a control created the id, the first message is still the INIT naming our address. A
 * conversation the peer began stays a REPLY, and a control after our INIT went out does not send it again.
 *
 * Drives the real ChatViewModel through the real use case and repository, over the real conversation-id store
 * ([RealConversationIds]). All addresses are synthetic; nothing here is paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlMessageKeepsFirstMessageInitTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticcontrolfirstmsg001"
        const val SELF = HandshakeHarness.SELF
        const val PEER_CONV_ID = "PEERC234"
        const val ONE_HOUR = 3600L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The disappearing-messages timer takes an id before anything was ever sent to this contact. */
    @Test
    fun `a disappearing timer that created the conversation leaves the first message a v4 INIT`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.setDisappearingTtl(PEER, ONE_HOUR)
            confirmIfAsked(vm)
            assertNotNull(chat.conversations.impl.getConversationId(PEER), "fixture sanity: the timer created the id")
            assertEquals(0, chat.messageMemos().size, "fixture sanity: no keys yet, so the timer control went nowhere")

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            confirmIfAsked(vm)

            val sent = chat.messageMemos().single()
            assertTrue(isInitFrom(sent, SELF), "the first message went out as a REPLY: ${sent.take(40)}…")
        }

    /**
     * A mode change out of Shielded takes an id the same way. The chat is Shielded again here (the peer never
     * got the change, so the user put it back) — the first on-chain message is still the one that names us.
     */
    @Test
    fun `a mode change that created the conversation leaves the first message a v4 INIT`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.changeConversationMode(PEER, ConversationMode.TUNNEL)
            confirmIfAsked(vm)
            assertNotNull(chat.conversations.impl.getConversationId(PEER), "fixture sanity: the mode change created the id")
            vm.changeConversationMode(PEER, ConversationMode.VAULT)
            awaitIdle(vm)
            assertEquals(0, chat.messageMemos().size, "fixture sanity: no keys yet, so the mode control went nowhere")

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            confirmIfAsked(vm)

            val sent = chat.messageMemos().single()
            assertTrue(isInitFrom(sent, SELF), "the first message went out as a REPLY: ${sent.take(40)}…")
        }

    /**
     * The peer began the chat (their id mapped to them). FC-ID (ZC-892; FIRST-CONTACT-SPEC 5.4.1 flow 19): our first
     * message pays our KEX first, so it goes out with that KEX under an id this phone made, as the INIT; the peer's id
     * still maps to the peer. Before FC-ID it was a REPLY under the peer's id.
     */
    @Test
    fun `a conversation the peer began, answered with our first message that pays our KEX, goes out as our INIT`() =
        runBlocking {
            val chat = Chat()
            chat.conversations.impl.setConversationMapping(PEER_CONV_ID, PEER)
            val vm = chat.viewModel()

            vm.setDisappearingTtl(PEER, ONE_HOUR)
            confirmIfAsked(vm)
            vm.sendMessage(PEER, "synthetic answer")
            awaitIdle(vm)
            confirmIfAsked(vm)

            val sent = chat.messageMemos().single()
            assertTrue(isInitFrom(sent, SELF), "our first message (it pays our KEX) did not go out as our INIT")
            assertFalse(sent.contains(PEER_CONV_ID), "our first message went out under the peer's conversation id")
            assertEquals(PEER, chat.conversations.impl.getPeerByConversationId(PEER_CONV_ID), "the peer's id moved")
        }

    /** Control, green before and after: once our INIT went out, a later control does not make the next one an INIT. */
    @Test
    fun `control - a control after our INIT went out does not send the INIT again`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            confirmIfAsked(vm)
            vm.setDisappearingTtl(PEER, ONE_HOUR)
            confirmIfAsked(vm)
            vm.sendMessage(PEER, "synthetic second message")
            awaitIdle(vm)
            confirmIfAsked(vm)

            val memos = chat.messageMemos()
            assertEquals(2, memos.size, "fixture sanity: two messages")
            assertTrue(isInitFrom(memos[0], SELF), "fixture sanity: the first was the INIT")
            assertFalse(memos[1].contains("|INIT|"), "the INIT was sent a second time")
        }

    // --------------------------------------------------------------- harness

    private fun confirmIfAsked(vm: ChatViewModel) {
        runBlocking { awaitIdle(vm) }
        if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation") {
            vm.confirmPaidAction()
        }
        runBlocking { awaitIdle(vm) }
    }

    /** A new contact in a Shielded chat, no keys yet, over the real conversation-id store and a recording seam. */
    private class Chat {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT, haveOurKey = false)
        val conversations = RealConversationIds()
        val seam = PaymentSeam(PEER)

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            PaymentHarness.setField(vm, "zchatPreferences", conversations.wrap(fixture.proxy))
            // ZC-1822: the timer's question is asked only of a wallet that can pay it.
            val accounts = fundedAccounts()
            PaymentHarness.setField(vm, "accountDataSource", accounts)
            PaymentHarness.setField(
                vm,
                "createChunkedMessageProposal",
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = accounts,
                            proposalDataSource = seam,
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also { SendHarnessScopes.current().own(it) },
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                ),
            )
            return vm
        }

        /** The first memo of every proposal that is a user message (not our KEX), in order. */
        fun messageMemos(): List<String> =
            seam.proposalUris
                .map { firstMemo(it) }
                .filterNot { ZMSGProtocol.isKEXMessage(it) || ZMSGProtocol.isKEXAckMessage(it) }
    }
}
