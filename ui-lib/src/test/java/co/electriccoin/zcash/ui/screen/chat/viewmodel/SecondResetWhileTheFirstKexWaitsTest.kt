package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-285 (fix-all 2.13.0, train T5b; found while writing the fix) — a second confirmed "Reset encryption" while the first
 * Reset's KEX still waits for a block. The second Reset records its own owed KEX and wipes the key the first KEX carries;
 * the first attempt then settles as "already delivered" (a Reset replaced its key) and, by clearing the owed mark, would
 * also clear the second Reset's: its attempt then finds nothing owed, and the chat is left with no keys and no KEX.
 *
 * Must hold: the KEX for the current key is paid exactly once (the first, stale one never), and nothing stays owed after
 * it. Written with the fix (not part of the RED commit); shown failing against the fix without its guard. Drives the
 * real ViewModel ([ResetOwedChat]). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondResetWhileTheFirstKexWaitsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc285secondreset01"
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
    fun `a second Reset while the first Reset's KEX waits for a block still pays the KEX for the current key once`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            val firstKey = chat.store.getE2EOurPublicKey(PEER)
            assertNotNull("fixture sanity: the first Reset's KEX made our new key", firstKey)

            // The user resets again before that KEX could go. (The first KEX still waits in the process send scope, so
            // these steps wait for their own result instead of for an idle scope.)
            vm.resetSecureSession(PEER)
            awaitTrue { HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation" }
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.store.getE2EOurPublicKey(PEER) == null }

            chat.heights.advance()
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)

            assertEquals("the KEX for our current key was never paid: the chat has no keys and no KEX", 1, chat.seam.kexAnswered.get())
            assertEquals(1, chat.seam.submittedOf("KEX"))
            assertFalse("the stale first key was the one sent", chat.store.getE2EOurPublicKey(PEER) == firstKey)
            assertFalse("the paid KEX is still marked owed", PEER in chat.kexOwed)
        }
}
