package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1335 (phone matrix 1 Nov, 00:35 to 00:42): Seeker force-stopped while its accepted first message waited for funds;
 * reopened 3 minutes later the row read "Failed, Not sent. The reason was not recorded".
 *
 * Why ConfirmingFirstMessageSendsItselfTest stayed green: its restart sets the process's "history read" flag to true, and
 * its key provider has no such flag at all. On a real cold start the flag is false until the first complete history pass,
 * and the first tip comes before it. While it is
 * false, messageOwesKexFirst answers false for a contact with no key, so resumeFundsWaitRowClaimed skips the plan check and
 * calls doSendMessage, whose waitsForRestoredHistory branch fails the row with no reason.
 *
 * Must hold: a tip before the history pass leaves the row waiting, and the row then goes once at a later block.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitingFirstMessageSurvivesColdStartTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1335coldstart01"
        const val TYPED = "synthetic first words before a force stop"
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
    fun `a tip before the first history pass leaves the waiting row waiting, and it goes once after that pass`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val before = world.screen()
            val row = world.acceptWhileConfirming(before, TYPED)

            val after = world.restart(before)
            // The fixture's key provider answers "read" for good; the real one (SeedDerivedE2EKeyProvider.create) reads this flag.
            var historyRead = false
            PaymentHarness.setField(
                after,
                "seedKeyProvider",
                SeedDerivedE2EKeyProvider(store = world.chat.prefs, seedProvider = { null }, ownHistoryRead = { historyRead }),
            )
            AutoSpendVm.setHistoryRead(false)
            world.block(after)
            world.paymentsSettled()

            val stored = world.row()
            assertTrue(
                "a cold start failed the waiting row: status ${stored?.status}, reason ${stored?.failureReason}",
                world.isWaiting(row.id) && stored?.status != MessageStatus.FAILED.name,
            )
            assertEquals("a cold start paid before the history was read", 0, world.seam.submits.get())

            historyRead = true
            AutoSpendVm.setHistoryRead(true)
            world.plan = "Fundable"
            world.block(after)
            assertTrue("the row did not resume", PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() == 2 })
        }
}
