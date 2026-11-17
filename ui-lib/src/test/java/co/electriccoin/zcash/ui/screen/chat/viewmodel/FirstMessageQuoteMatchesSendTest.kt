package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B2 / Grok checklist H1 (FIRST-MSG-QUOTE-PREDICATE): the first-message cost prompt and the send it guards
 * ask different questions, so the send can carry one memo output more than the user was shown.
 *
 * The prompt sizes the message with `isFirstMessageTo(peer)`, which asks "does a conversation id exist?"
 * (`getConversationId(peer) == null`). The send asks `getOrCreateConversationIdForMessage(peer).second`, which
 * stays true until an INIT's submit has started (R2-6 c): the `initpending:<peer>` mark. A control that ran
 * before the first message (disappearing timer, mode change, ZBOOT, rotation announce) creates the id and leaves
 * that mark set. The prompt then prices a REPLY, and the confirmed send builds the longer INIT, which carries our
 * whole address in its first chunk. Near a chunk boundary: quoted 2 memo outputs, charged 3.
 *
 * Wanted: the quote asks the question the send asks (`needsConversationInit`), so the total on the prompt is the
 * KEX plus the memo outputs the send really packs.
 *
 * Drives the real ChatViewModel through the real use case and repository over the real conversation-id store
 * ([RealConversationIds]), the ControlMessageKeepsFirstMessageInitTest harness. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageQuoteMatchesSendTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticquotematchessend01"

        /** A real mainnet unified address is 178-213 bytes; the INIT's first chunk carries all of it. */
        val SELF = "u1" + "q".repeat(211)
        const val ONE_HOUR = 3600L
    }

    /** The shortest message a REPLY packs into 2 memos and the INIT into 3, for our address. */
    private val boundaryMessage: String =
        (300..2000)
            .asSequence()
            .map { "z".repeat(it) }
            .first {
                ZMSGProtocol.calculateV4ChunkCount(it, false, SELF) == 2 &&
                    ZMSGProtocol.calculateV4ChunkCount(it, true, SELF) == 3
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
    fun `an id whose INIT was never sent is quoted as the INIT the send builds`() =
        runBlocking {
            val chat = Chat()
            // The id exists, its INIT is not submitted: what a control leaves behind.
            chat.conversations.impl.getOrCreateConversationIdForMessage(PEER)
            assertNotNull(chat.conversations.impl.getConversationId(PEER), "fixture sanity: the id exists")
            assertTrue(chat.conversations.impl.needsConversationInit(PEER), "fixture sanity: the INIT is still owed")
            val vm = chat.viewModel()

            vm.sendMessage(PEER, boundaryMessage)
            awaitIdle(vm)

            assertEquals(
                expectedTotal(chunks = 3),
                quotedTotal(vm),
                "the prompt priced a 2-memo REPLY, but the send is the 3-memo INIT",
            )
        }

    /** The reported path: the disappearing timer took the id before the first message. */
    @Test
    fun `after a disappearing timer created the id, the quote equals what the confirmed send packs`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            vm.setDisappearingTtl(PEER, ONE_HOUR)
            confirmIfAsked(vm)
            assertNotNull(chat.conversations.impl.getConversationId(PEER), "fixture sanity: the timer created the id")
            assertEquals(0, chat.messageUris().size, "fixture sanity: no keys yet, so the timer sent nothing")

            vm.sendMessage(PEER, boundaryMessage)
            awaitIdle(vm)
            val quoted = quotedTotal(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            val sent = chat.messageUris().single()
            assertTrue(isInitFrom(firstMemo(sent), SELF), "fixture sanity: the first message went out as the INIT")
            assertEquals(3, memoCount(sent), "fixture sanity: the INIT packs 3 memos")
            assertEquals(
                expectedTotal(chunks = memoCount(sent)),
                quoted,
                "the user was shown the price of a shorter message than the one that was paid for",
            )
        }

    /** Control, green before and after: a contact never written to is quoted as the INIT. */
    @Test
    fun `control - a new contact with no id is quoted as the INIT`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, boundaryMessage)
            awaitIdle(vm)

            assertEquals(expectedTotal(chunks = 3), quotedTotal(vm))
        }

    // --------------------------------------------------------------- harness

    /**
     * Our KEX, the message's memo outputs and (Round 10b R10B-1) our ZBOOT: the contact's relay key is unknown here, so the
     * ZBOOT after their KEXACK goes on-chain and the question counts it.
     */
    private fun expectedTotal(chunks: Int): Long =
        (
            onChainSendMinimumCost() +
                onChainSendMinimumCost(chunks, CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT) +
                onChainSendMinimumCost()
        ).value

    private fun quotedTotal(vm: ChatViewModel): Long {
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
            "the user must be asked before a first message is paid for, was $state",
        )
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        return (confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation) as Zatoshi).value
    }

    private fun confirmIfAsked(vm: ChatViewModel) {
        runBlocking { awaitIdle(vm) }
        if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation") {
            vm.confirmPaidAction()
        }
        runBlocking { awaitIdle(vm) }
    }

    /** A new contact in a Shielded chat, no keys yet, over the real conversation-id store and a recording seam. */
    private inner class Chat {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT, haveOurKey = false)
        val conversations = RealConversationIds()
        val seam = PaymentSeam(PEER)

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            PaymentHarness.setField(vm, "zchatPreferences", conversations.wrap(fixture.proxy))
            PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(SELF))
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

        /** Every proposal that is a user message (not our KEX), in order. */
        fun messageUris(): List<String> =
            seam.proposalUris.filterNot {
                val memo = firstMemo(it)
                ZMSGProtocol.isKEXMessage(memo) || ZMSGProtocol.isKEXAckMessage(memo)
            }
    }
}
