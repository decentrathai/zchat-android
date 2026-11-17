package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * ZC-1955 (Fable re-review of fix/2130-msg f58e91ef2, 16 Nov 2026; register medium; money-path rule 3) — Delete of a row
 * waiting for its funds (cancelKexOwedOnlyFor) read the stored row, judged it "never submitted" and removed it without
 * the claim lock that recordSubmitStarted holds while it writes the submit marker. A delete racing the row's submit could
 * remove a row whose submit had just started (the message then goes out with no row to reconcile it).
 *
 * Wanted: the read, the check and the removal run under the same lock (retryClaimLock): while another thread holds it
 * (as recordSubmitStarted does), a delete of the row waits and removes nothing; once the lock is free, it removes the
 * waiting row as before. Through the ViewModel's own step (reached by name); every address and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteOfAWaitingRowTakesTheClaimLockTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1955deletelock001"
        const val ROW_ID = "pending_zc1955"
        const val HELD_MS = 500L
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `a delete of a waiting row waits for the claim lock and removes nothing while it is held`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val account = syntheticZashiAccount()
            world.chat.prefs.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = ROW_ID,
                    text = "synthetic waiting first words",
                    timestampMillis = System.currentTimeMillis(),
                    peerAddress = PEER,
                    isPending = true,
                    status = MessageStatus.SENDING.name,
                    sendAmountZatoshi = 1_000L,
                    submitTracked = true,
                    acceptedPriceZatoshi = 51_000L,
                    acceptedAccountKey = GetDefaultUnifiedAddressUseCase.accountKeyOf(account),
                ),
            )
            val lock =
                ChatViewModel::class.java
                    .getDeclaredField("retryClaimLock")
                    .apply { isAccessible = true }
                    .get(null)!!
            val step =
                ChatViewModel::class.java.declaredMethods
                    .single { it.name == "cancelKexOwedOnlyFor" && it.parameterCount == 2 }
                    .apply { isAccessible = true }
            val deleter =
                Thread {
                    try {
                        step.invoke(vm, ROW_ID, true)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            val rowWhileHeld =
                synchronized(lock) {
                    deleter.start()
                    deleter.join(HELD_MS)
                    stored(world)
                }
            assertNotNull("the delete removed the row while the claim lock was held", rowWhileHeld)
            deleter.join(TimeUnit.SECONDS.toMillis(5))
            assertNull("control: once the lock is free the waiting row is removed", stored(world))
        }

    private fun stored(world: FundsConfirmingChat) =
        world.chat.prefs
            .getPendingMessages()
            .firstOrNull { it.id == ROW_ID }
}
