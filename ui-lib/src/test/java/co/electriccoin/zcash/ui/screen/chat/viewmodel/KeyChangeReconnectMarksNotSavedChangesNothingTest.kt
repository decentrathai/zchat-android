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
import org.junit.Before
import org.junit.Test

/**
 * ZC-300 (fix-all 2.13.0, train T5b; r8-findings SPECS §16.1, NEW-KCR) — a confirmed key-change reconnect clears the
 * flag and drops the contact's relay key whatever the store can save. Once the reconnect's KEX is owed durably, a store
 * that cannot save the owed mark, its account, its reason or the ZBOOT allowance must stop the reconnect before anything
 * changes: otherwise a kill after the relay key is gone leaves nothing that pays the KEX.
 *
 * Must hold: when any of those writes is not saved, the key-change flag and the relay key stay, nothing is built or sent,
 * what was written is undone, and the user sees the "couldn't save its records" notice
 * (ChatSendCopy.STORAGE_PAUSED_PAID_STEP).
 *
 * Drives the real ViewModel ([KeyChangeReconnectChat]). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChangeReconnectMarksNotSavedChangesNothingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc300marksnotsave"
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
    fun `an owed mark that is not saved stops the reconnect before anything changes`() = refused("setKexOwed")

    @Test
    fun `an owed account that is not saved stops the reconnect before anything changes`() = refused("setKexOwedAccount")

    @Test
    fun `an owed reason that is not saved stops the reconnect before anything changes`() = refused("setKexOwedReason")

    @Test
    fun `a ZBOOT allowance that is not saved stops the reconnect before anything changes`() = refused("setZbootQuoted")

    private fun refused(write: String) =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER).apply { owed.refusedWrites += write }
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            chat.assertNothingChanged("when $write was not saved")
            assertEquals(
                "the user is not told why the reconnect did not run",
                SendMessageState.Error(ChatSendCopy.STORAGE_PAUSED_PAID_STEP),
                HandshakeHarness.sendMessageState(vm),
            )
        }
}
