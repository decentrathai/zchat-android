package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
 * ZC-285 (fix-all 2.13.0, train T5b; r8-findings SPECS §1.3 (2)) — a confirmed "Reset encryption" wipes the keys whatever
 * the store can save. Once the Reset's KEX is owed durably, a store that cannot save the owed mark, its reason or the
 * ZBOOT allowance must stop the Reset before anything is wiped: otherwise a kill after the wipe leaves keys gone and
 * nothing that pays the KEX.
 *
 * Must hold: when any of those writes is not saved, nothing is wiped, nothing is built or sent, what was written is
 * undone, and the user sees the "couldn't save its records" notice (ChatSendCopy.STORAGE_PAUSED_PAID_STEP).
 *
 * Drives the real ViewModel ([ResetOwedChat]). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetMarksNotSavedWipesNothingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc285marksnotsaved"
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
    fun `an owed mark that is not saved stops the Reset before anything is wiped`() = refused("setKexOwed")

    @Test
    fun `an owed reason that is not saved stops the Reset before anything is wiped`() = refused("setKexOwedReason")

    @Test
    fun `a ZBOOT allowance that is not saved stops the Reset before anything is wiped`() = refused("setZbootQuoted")

    private fun refused(write: String) =
        runBlocking {
            val chat = ResetOwedChat(PEER).apply { refusedWrites += write }
            val vm = chat.viewModel()

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            chat.reset.assertNothingWiped("when $write was not saved")
            assertEquals("nothing may be built when $write was not saved", 0, chat.seam.proposals())
            assertEquals(0, chat.seam.submitted.size)
            assertEquals(
                "the user is not told why the Reset did not run",
                SendMessageState.Error(ChatSendCopy.STORAGE_PAUSED_PAID_STEP),
                HandshakeHarness.sendMessageState(vm),
            )
            assertFalse("the owed mark was left behind", PEER in chat.kexOwed)
            assertFalse("the owed reason was left behind", chat.kexOwedReason.containsKey(PEER))
            assertFalse("the owed account was left behind", chat.kexOwedAccount.containsKey(PEER))
            assertTrue("the ZBOOT allowance was left behind", chat.zbootQuoted[PEER] != true)
        }
}
