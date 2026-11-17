package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * ZC-285 (fix-all 2.13.0, train T5b; r8-findings SPECS §1, scenario A) — a confirmed "Reset encryption" loses its KEX when
 * the user leaves the chat.
 *
 * ChatViewModel.resetSecureSession runs the confirmed reset on the chat screen's viewModelScope: it wipes our keys, the
 * ratchet and the Quantum Shield key, then the bootstrap (marked "sent" before the KEX goes) launches the paid KEX in that
 * same scope. With a single spendable note the KEX waits for a block; leaving the chat cancels the scope there. The keys
 * are gone, no KEX went, and the bootstrap marker says it did, so opening the chat again sends nothing: the chat cannot
 * encrypt until a Call tap or another paid Reset.
 *
 * Must hold: the confirmed Reset's KEX is owed durably (written before anything is wiped) and paid on the process-wide
 * send scope; leaving the chat while it waits for a block does not cancel it, and the next block pays it exactly once.
 *
 * Drives the real ViewModel through the real use case and repository ([ResetOwedChat]). Every address and key is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetEncryptionSurvivesLeavingTheChatTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc285leavechat0001"
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
    fun `a confirmed Reset's KEX waiting for a block is still paid once after the chat is left`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture sanity: the KEX met the still-confirming wait", 1, chat.seam.kexProposals.get())
            assertEquals("fixture sanity: no KEX went yet", 0, chat.seam.kexAnswered.get())
            chat.reset.assertWiped()

            // The user leaves the chat: its screen's ViewModel is cleared while the KEX waits for a block.
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            chat.heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the KEX of a confirmed Reset was lost when the chat screen closed: the keys are wiped and nothing was sent",
                1,
                chat.seam.kexAnswered.get(),
            )
            assertEquals("the KEX was submitted once", 1, chat.seam.submittedOf("KEX"))
            assertFalse("the paid KEX is still marked owed", PEER in chat.kexOwed)
        }

    @Test
    fun `the Reset's KEX is recorded as owed before anything is wiped`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }

            chat.reset.assertWiped()
            assertEquals("the Reset's KEX is owed while it waits for a block", true, PEER in chat.kexOwed)
            assertEquals("the owed KEX names the Reset as its reason", "reset", chat.kexOwedReason[PEER])
            assertEquals("the Reset quoted our ZBOOT: its allowance is written", true, chat.zbootQuoted[PEER])
            assertFalse("an owed mark was written after the wipe had started", chat.markWrittenAfterWipe)
        }

    /** Leaving the chat and opening it again (no block yet) pays the owed KEX once too; a later block pays nothing more. */
    @Test
    fun `opening the chat again after leaving it pays the Reset's KEX once`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, failFirstKexWithStillConfirming = true)
            val first = chat.viewModel()

            first.resetSecureSession(PEER)
            awaitIdle(first)
            first.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            first.viewModelScope.coroutineContext.job
                .cancelAndJoin()

            val reopened = chat.viewModel()
            chat.openChat(reopened)
            chat.tick(reopened)
            awaitIdle(reopened)
            chat.tick(reopened)
            awaitIdle(reopened)

            assertEquals("the Reset's KEX must go exactly once", 1, chat.seam.kexAnswered.get())
            assertEquals(1, chat.seam.submittedOf("KEX"))
            assertFalse("the paid KEX is still marked owed", PEER in chat.kexOwed)
        }
}
