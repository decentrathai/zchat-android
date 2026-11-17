package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlan
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlanCheck
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1327 (money-path rule 2): a wait row's block work waits on the plan check. The row's total limit can pass during that
 * wait and settle the row as "waited too long", with Retry on it. The check then answers Fundable. Must hold: the row
 * stays not sent and nothing is paid until a Retry; a block afterwards pays nothing either.
 *
 * Two places the wait can fall: the check of resumeFundsWaitRowClaimed, before LiveSenders.running, and the same check in
 * the send run it starts (doSendMessage's gate). Real ChatViewModel over [FundsConfirmingChat]; every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ARowSettledAsWaitedTooLongNeverPaysAfterwardsTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usyntheticzc1327settledgap01"
        const val TYPED = "synthetic first words settled while the plan was checked"
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS
        const val ONE_HOUR_AND_A_MINUTE_MS = 61L * 60_000L
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAcceptScope")
            .apply { isAccessible = true }
            .set(null, sendScopes.heldAcceptScope)
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
    fun `a row settled while the block's plan check waits pays nothing when the check answers`() =
        runBlocking { settledDuringTheCheck(parkAtCheck = 1) }

    @Test
    fun `a row settled while the started send's plan check waits pays nothing when the check answers`() =
        runBlocking { settledDuringTheCheck(parkAtCheck = 2) }

    private suspend fun settledDuringTheCheck(parkAtCheck: Int) {
        val world = FundsConfirmingChat(PEER)
        val vm = world.screen()
        val row = world.acceptWhileConfirming(vm, TYPED)

        val checks = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        PaymentHarness.setField(
            vm,
            "firstMessagePlanCheck",
            FirstMessagePlanCheck { _, _, _ ->
                if (checks.incrementAndGet() == parkAtCheck) {
                    entered.complete(Unit)
                    answer.await()
                }
                FirstMessagePlan.Fundable
            },
        )
        try {
            world.plan = "Fundable"
            world.block(vm)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { entered.await() }
            assertEquals("fixture sanity: nothing paid before the limit", 0, world.seam.submits.get())

            scheduler.advanceTimeBy(ONE_HOUR_AND_A_MINUTE_MS)
            scheduler.runCurrent()
            assertTrue(
                "fixture sanity: the row was not settled on disk during the check: ${world.row()?.status}",
                PaymentHarness.eventually(SETTLE_MS) { world.row()?.status == MessageStatus.FAILED.name },
            )
            assertEquals("fixture sanity: the settled reason", ChatSendCopy.FAILED_WAITED_TOO_LONG, world.row()?.failureReason)

            answer.complete(Unit)
            assertTrue("a row shown as not sent paid after the plan check answered: ${world.seam.submits.get()}", world.staysAt(0))
            world.block(vm)
            assertTrue("a later block paid a row shown as not sent: ${world.seam.submits.get()}", world.staysAt(0))
            assertEquals("the row no longer reads as not sent", MessageStatus.FAILED.name, world.row()?.status)
            assertEquals("the row lost its reason", ChatSendCopy.FAILED_WAITED_TOO_LONG, world.row()?.failureReason)
            assertEquals("fixture sanity: the row is still this one", row.id, world.row()?.id)
        } finally {
            answer.complete(Unit)
        }
    }
}
