package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
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
 * R3-2 (1), round-2 report section 4 item 3: a new contact's first message must never go out as a REPLY
 * because our background key exchange created the conversation first.
 *
 * The first message to a new contact is a v4 INIT: it carries our address, which is how the recipient learns
 * who wrote. R2-6 (c) marks a conversation id created by a message send as "INIT not sent" until the INIT's
 * submit starts. But our KEX (sent on its own when E2E is switched on, on a Tunnel chat's open, and in the
 * background just before the first message) asks getOrCreateConversationId, which stores a new id with no
 * such mark. The message that follows finds an id, counts the conversation as started and sends a REPLY
 * carrying only a hash of our address, which the recipient cannot place until the key exchange goes through.
 *
 * Must hold: after our KEX created the id, the first message is still the INIT naming our address; a
 * conversation the peer began (their id mapped to them) and one whose INIT already went out stay REPLYs.
 *
 * Drives the real ChatViewModel KEX and message sends through the real use case and repository, with the
 * real conversation-id store ([RealConversationIds]). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundKexKeepsFirstMessageInitTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticbackgroundkexpeer1"
        const val SELF = HandshakeHarness.SELF
        const val PEER_CONV_ID = "PEERB234"
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

    @Test
    fun `a KEX that created the conversation before the first message leaves that message a v4 INIT`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            // E2E switched on (or a Tunnel chat opened) before anything was written: our KEX goes out first.
            // FC-ID (ZC-892): through its real trigger, which chooses the KEX's own-made id first; sendKEXMessage alone is
            // the fail-closed pin of OwnKexGoesOutUnderAnOwnIdTest (l).
            vm.setE2EEnabled(PEER, true)
            awaitIdle(vm)
            assertEquals(1, chat.seam.proposalUris.size, "fixture sanity: the KEX was proposed")
            assertTrue(ZMSGProtocol.isKEXMessage(firstMemo(chat.seam.proposalUris[0])), "fixture sanity: a KEX")
            assertNotNull(chat.conversations.impl.getConversationId(PEER), "fixture sanity: the KEX created the id")

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            confirmIfAsked(vm)

            val sent = chat.messageMemos().single()
            assertTrue(isInitFrom(sent, SELF), "the first message went out as a REPLY: ${sent.take(40)}…")
        }

    /**
     * Control, green before and after in this harness: the first message's own KEX is launched just before the
     * message takes its id, and here the message wins. On a phone the KEX can win; the test above is that order.
     */
    @Test
    fun `control - the first message to a new contact that triggers its own KEX is a v4 INIT`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            confirmIfAsked(vm)

            assertTrue(chat.seam.proposalUris.any { ZMSGProtocol.isKEXMessage(firstMemo(it)) }, "fixture sanity: our KEX")
            val sent = chat.messageMemos().single()
            assertTrue(isInitFrom(sent, SELF), "the first message went out as a REPLY: ${sent.take(40)}…")
        }

    /** Control, green before and after: the peer began the chat (their INIT mapped their id), so we REPLY. */
    @Test
    fun `control - a conversation the peer began is answered with a REPLY after our KEX`() =
        runBlocking {
            val chat = Chat()
            chat.conversations.impl.setConversationMapping(PEER_CONV_ID, PEER)
            val vm = chat.viewModel()

            vm.sendKEXMessage(PEER, SELF)
            awaitIdle(vm)
            vm.sendMessage(PEER, "synthetic answer")
            awaitIdle(vm)
            confirmIfAsked(vm)

            val sent = chat.messageMemos().single()
            assertFalse(sent.contains("|INIT|"), "an answer to the peer's own conversation went out as an INIT")
            assertTrue(sent.contains(PEER_CONV_ID), "the answer is not on the peer's conversation id")
        }

    /** Control, green before and after: once our INIT went out, a later KEX does not make the next one an INIT. */
    @Test
    fun `control - a KEX after our INIT went out does not send the INIT again`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            confirmIfAsked(vm)
            vm.sendKEXMessage(PEER, SELF)
            awaitIdle(vm)
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
            val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
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
