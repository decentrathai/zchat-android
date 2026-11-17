package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-285 (fix-all 2.13.0, train T5b; r8-findings SPECS §1, scenario A after a kill) — a confirmed "Reset encryption" whose
 * KEX is waiting for a block when the process ends is never paid: nothing records it as owed, and the bootstrap marker
 * says our KEX went, so neither a block nor a chat open in the next process sends it.
 *
 * Must hold: after the kill (every scope cancelled, the process memory gone), a new ViewModel over the same store pays
 * the Reset's KEX on the next block, exactly once, without a question; a second block pays nothing more. M2's
 * first-message plan gate does not hold a Reset's lone KEX (its quote has no message), while a first message's owed KEX in
 * the same wallet still waits for its message to be fundable (control).
 *
 * Drives the real ViewModel through the real use case and repository ([ResetOwedChat]). Every address and key is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetOwedKexIsPaidAfterAKillTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc285killedproc001"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `a Reset's KEX interrupted by the end of the process is paid once by the next process`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, failFirstKexWithStillConfirming = true)
            val first = chat.viewModel()
            confirmResetAndWaitForABlock(chat, first)

            ResetOwedChat.kill(first)

            val next = chat.viewModel()
            chat.tick(next)
            awaitIdle(next)

            assertEquals("the KEX of a confirmed Reset was never sent after the process ended", 1, chat.seam.kexAnswered.get())
            assertNoQuestion(next)
            assertFalse("the paid KEX is still marked owed", PEER in chat.kexOwed)

            chat.tick(next)
            awaitIdle(next)
            chat.openChat(next)
            awaitIdle(next)
            assertEquals("the Reset's KEX was paid a second time", 1, chat.seam.kexAnswered.get())
            assertEquals(1, chat.seam.submittedOf("KEX"))
        }

    @Test
    fun `a Reset's owed KEX is paid after a kill even when a first message could not follow it`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, failFirstKexWithStillConfirming = true)
            val notFundable = FirstMessagePlanApi.ScriptedCheck("NotFundable")
            val first = chat.viewModel(planCheck = notFundable)
            confirmResetAndWaitForABlock(chat, first)

            ResetOwedChat.kill(first)

            val next = chat.viewModel(planCheck = notFundable)
            chat.tick(next)
            awaitIdle(next)

            assertEquals(
                "M2's first-message plan gate held the Reset's lone KEX (its quote has no message)",
                1,
                chat.seam.kexAnswered.get(),
            )
            assertFalse("the paid KEX is still marked owed", PEER in chat.kexOwed)
        }

    /** Control, green before and after (M2): a first message's owed KEX is not paid alone when its message cannot follow. */
    @Test
    fun `control - a first message's owed KEX in the same wallet is not paid when its message cannot follow`() =
        runBlocking {
            val chat = ResetOwedChat(PEER)
            // A first message to this contact confirmed its KEX and the process ended before it went: no keys, the mark
            // with no reason (a first message's).
            chat.store.clearE2EKeys(PEER)
            chat.kexOwed += PEER
            val check = FirstMessagePlanApi.ScriptedCheck("NotFundable")
            val vm = chat.viewModel(planCheck = check)

            chat.tick(vm)
            awaitIdle(vm)

            assertEquals("a first message's KEX was paid alone though its message cannot follow", 0, chat.seam.proposals())
            assertTrue("the first message's KEX is no longer owed", PEER in chat.kexOwed)
            assertTrue("fixture sanity: the tick asked the plan check", check.calls.isNotEmpty())
        }

    private suspend fun confirmResetAndWaitForABlock(
        chat: ResetOwedChat,
        vm: ChatViewModel,
    ) {
        vm.resetSecureSession(PEER)
        awaitIdle(vm)
        chat.reset.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitTrue { chat.heights.isWaiting() }
        assertEquals("fixture sanity: the KEX met the still-confirming wait", 1, chat.seam.kexProposals.get())
        assertEquals("fixture sanity: no KEX went yet", 0, chat.seam.kexAnswered.get())
        chat.reset.assertWiped()
    }

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse(
            "the user was asked again to pay for the KEX the Reset already quoted: $state",
            state.javaClass.simpleName == "NeedsPaidActionConfirmation" || state is SendMessageState.NeedsHandshakeSpendConsent,
        )
    }
}
