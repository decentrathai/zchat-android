package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1306: New Chat's first message with funds still confirming is refused with "Please wait for your ZEC to confirm",
 * while the chat path (ZC-1271) asks, saves the row with its price and sends it itself. New Chat must behave the same:
 * asked, row saved with the accepted price and the form closed on the chat, nothing paid, no refusal; the row then goes out
 * once, within that price, at the first block that can pay it.
 *
 * NewChatAsksTheExactPriceBeforeItPaysTest's shortfall test holds a wallet with nothing confirming, which stays refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatWaitsWhenFundsConfirmTest {
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

    @Test
    fun `with funds confirming the tap asks a price and is not refused`() =
        runBlocking {
            val world = OneDecisionWorld().apply { fundsStillConfirming() }
            val form = world.newChatIn(ConversationMode.VAULT)

            world.tap(form)

            val state = form.state.value
            assertTrue(
                "New Chat refused a first message whose funds are only confirming: $state",
                state !is ZchatComposeState.Error,
            )
            val asked = NewChatPriceQuestion.require(form)
            assertTrue("the question names no real cost: ${asked.totalCost.value}", asked.totalCost.value >= OneDecisionWorld.NORMAL_LEG)
            assertEquals("the tap paid", 0, world.submits())
        }

    @Test
    fun `Pay saves the row with the accepted price, opens the chat and pays nothing`() =
        runBlocking {
            val world = OneDecisionWorld().apply { fundsStillConfirming() }
            val form = world.newChatIn(ConversationMode.VAULT)
            world.tap(form)

            val asked = world.pay(form)

            val row = world.funds.row()
            assertNotNull("Pay saved no row for the first message", row)
            assertEquals(
                "the row does not hold the total the user accepted",
                asked.totalCost.value,
                FundsConfirmingChat.acceptedPrice(row!!),
            )
            assertEquals("the row is under another text", OneDecisionWorld.FIRST_WORDS, row.text)
            assertTrue("the row is not waiting (status ${row.status}, pending ${row.isPending})", world.funds.isWaiting(row.id))
            assertEquals("the chat did not open on the waiting row", listOf("replace"), world.navigation.commands.toList())
            assertEquals("something was paid while the funds confirm", 0, world.submits())
            assertNotEquals(
                "the form still answers 'try again'",
                CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
                (form.state.value as? ZchatComposeState.Error)?.message,
            )
        }

    @Test
    fun `Cancel saves and pays nothing and keeps the form`() =
        runBlocking {
            val world = OneDecisionWorld().apply { fundsStillConfirming() }
            val form = world.newChatIn(ConversationMode.VAULT)
            world.tap(form)
            NewChatPriceQuestion.require(form)

            NewChatPriceQuestion.cancel(form)
            world.settle(form)

            assertNull("a cancelled question saved a row", world.funds.row())
            assertEquals(OneDecisionWorld.FIRST_WORDS, world.ready(form).message)
            assertEquals(0, world.submits())
        }

    @Test
    fun `a Shielded row sends itself once at the first block that can pay, within its price`() =
        runBlocking {
            assertSendsItself(ConversationMode.VAULT)
        }

    /** New Chat's default mode; the block's resume refuses a row in any mode but Shielded today (RETRY_MODE_CHANGED). */
    @Test
    fun `a Tunnel row is not stopped by the next block and sends itself once, within its price`() =
        runBlocking {
            assertSendsItself(ConversationMode.TUNNEL)
        }

    /** Control, green before and after: a wallet with nothing confirming is told so, as the shortfall test pins. */
    @Test
    fun `control - a wallet with nothing confirming is still refused`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.accounts.account.value = walletWith(orchard = 0, ironwood = 0)
            world.funds.seam.feeProbe = { co.electriccoin.zcash.ui.common.model.FeeProbe.Short }
            val form = world.newChatIn(ConversationMode.VAULT)

            world.tap(form)

            assertNull("a wallet that cannot pay was asked to pay", NewChatPriceQuestion.open(form))
            assertTrue("no refusal: ${form.state.value}", form.state.value is ZchatComposeState.Error)
            assertNull(world.funds.row())
        }

    private suspend fun assertSendsItself(mode: ConversationMode) {
        val world = OneDecisionWorld().apply { fundsStillConfirming() }
        val form = world.newChatIn(mode)
        world.tap(form)
        val asked = world.pay(form)
        val row = world.funds.row()
        assertNotNull("Pay saved no row for the first message", row)
        val detail = world.chatScreen()

        world.funds.block(detail)
        assertTrue("the row gave up during the normal wait", world.funds.isWaiting(row!!.id))
        assertEquals("paid while the funds confirm", 0, world.submits())

        world.fundsConfirmed()
        world.funds.block(detail)
        assertTrue(
            "the row did not send by itself",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.submits() >= 1 },
        )
        world.funds.paymentsSettled()
        val settled = world.submits()
        world.funds.block(detail)
        assertTrue("a later block paid again", world.funds.staysAt(settled))
        assertNotEquals("the row reads as not sent", MessageStatus.FAILED.name, world.funds.row()?.status)
        assertTrue(
            "paid ${world.wallet.paidTo(world.peer)} against an accepted total of ${asked.totalCost.value}",
            world.wallet.paidTo(world.peer) <= asked.totalCost.value,
        )
    }
}
