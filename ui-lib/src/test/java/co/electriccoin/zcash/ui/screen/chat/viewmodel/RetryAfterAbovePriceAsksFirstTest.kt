package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1308 (money-path rule 1): an accepted first message stopped with FAILED_ABOVE_ACCEPTED_PRICE. Retry clears the accepted
 * price and pays whatever the current fee is. It must ask the new figure first: nothing is paid before Pay, the figure covers
 * what the legs now cost, Pay pays no more than it, and Cancel leaves the row stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryAfterAbovePriceAsksFirstTest {
    private val sendScopes = SendHarnessScopes()

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

    private class Stopped(
        val world: OneDecisionWorld,
        val vm: ChatViewModel,
        val id: String,
    )

    /** A first message accepted while its funds confirm, stopped because the build came back above the accepted total. */
    private suspend fun stoppedAbovePrice(): Stopped {
        val world = OneDecisionWorld()
        world.memory.consent = true
        val vm = world.chatScreen()
        val row = world.funds.acceptWhileConfirming(vm, OneDecisionWorld.FIRST_WORDS)
        world.fee(FEE_ABOVE_THE_TOTAL)
        world.funds.plan = "Fundable"
        world.funds.block(vm)
        assertTrue(
            "fixture sanity: the row did not stop above its price",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
                world.funds.row()?.failureReason == ChatSendCopy.FAILED_ABOVE_ACCEPTED_PRICE
            },
        )
        assertEquals("fixture sanity: nothing is paid above the price", 0, world.submits())
        return Stopped(world, vm, row.id)
    }

    private suspend fun retry(stopped: Stopped) {
        assertTrue("Retry was refused", stopped.vm.retryMessage(stopped.world.peer, stopped.id))
        HandshakeHarness.awaitIdle(stopped.vm)
    }

    @Test
    fun `Retry at a higher fee asks the new figure and pays nothing before Pay`() =
        runBlocking {
            val stopped = stoppedAbovePrice()
            val world = stopped.world
            world.fee(HIGHER_FEE)

            retry(stopped)

            world.funds.paymentsSettled()
            assertEquals("Retry paid at a new fee with no question", 0, world.submits())
            val asked = world.paidQuestion(stopped.vm)
            assertNotNull("Retry asked no new figure", asked)
            assertTrue(
                "the question ${asked!!.totalCost.value} does not cover both legs at the current fee",
                asked.totalCost.value >= 2 * (HIGHER_FEE + FundsConfirmingChat.LEG_OUTPUTS),
            )
        }

    @Test
    fun `Retry at a lower fee also asks first`() =
        runBlocking {
            val stopped = stoppedAbovePrice()
            val world = stopped.world
            world.fee(LOWER_FEE)

            retry(stopped)

            world.funds.paymentsSettled()
            assertEquals("Retry paid at a new fee with no question", 0, world.submits())
            assertNotNull("Retry asked no new figure", world.paidQuestion(stopped.vm))
        }

    @Test
    fun `Pay on the new figure pays both legs once, within it`() =
        runBlocking {
            val stopped = stoppedAbovePrice()
            val world = stopped.world
            world.fee(HIGHER_FEE)
            retry(stopped)
            val asked = world.paidQuestion(stopped.vm)
            assertNotNull("Retry asked no new figure", asked)

            stopped.vm.confirmPaidAction()

            assertTrue(
                "Pay did not send the row",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.submits() >= 2 },
            )
            world.funds.paymentsSettled()
            assertEquals("one message was paid more than once", 2, world.submits())
            assertTrue(
                "paid ${world.wallet.paidTo(world.peer)} against a question of ${asked!!.totalCost.value}",
                world.wallet.paidTo(world.peer) <= asked.totalCost.value,
            )
        }

    @Test
    fun `Cancel on the new figure leaves the row stopped, and no block pays it`() =
        runBlocking {
            val stopped = stoppedAbovePrice()
            val world = stopped.world
            world.fee(LOWER_FEE)
            retry(stopped)
            assertNotNull("Retry asked no new figure", world.paidQuestion(stopped.vm))

            stopped.vm.dismissPaidAction()
            world.funds.block(stopped.vm)

            assertTrue("a block paid a row whose question was cancelled", world.funds.staysAt(0))
            assertEquals("the row no longer reads as not sent", MessageStatus.FAILED.name, world.funds.row()?.status)
        }

    private companion object {
        const val FEE_ABOVE_THE_TOTAL = 100_000L
        const val HIGHER_FEE = 30_000L
        const val LOWER_FEE = 10_000L
    }
}
