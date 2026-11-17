package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.restored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1271 (owner's hands-on test of 2.13.0): a first message sent with freshly received ZEC was refused with "Please wait
 * for your ZEC to confirm on-chain, then try again", and the real wait is 10 confirmations, about 13 minutes.
 *
 * Must hold (money-path rules 1 and 2):
 * - the price is asked first, the row is then saved with the accepted total, and nothing is paid yet;
 * - once the funds can be spent the row sends by itself, exactly once: with its chat left, with two screens hearing the
 *   block, and after a process restart from the row on disk, at the next block;
 * - it never pays above the accepted total; a plan that changed stops the row with a decision, and no later block pays it.
 *
 * Real ChatViewModel send, loader and block tick over [FundsConfirmingChat]. The controls are green before and after the
 * fix: they prove the fixture's restart and tick reach the wallet. FirstMessageWaitsWhenFundsConfirmTest pins the refusal
 * this replaces; changing it is the implementer's, with the orchestrator's test-change approval.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmingFirstMessageSendsItselfTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1271confirming01"
        const val TYPED = "synthetic first words with fresh funds"

        /** The real wait: a deposit needs 10 confirmations. */
        const val CONFIRMATIONS = 10

        /** The key exchange and the message. */
        const val BOTH_LEGS = 2
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS

        /** Each leg of this fee alone is above the whole accepted total. */
        const val FEE_ABOVE_THE_TOTAL = 100_000L
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

    // ── accepted and saved ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a first message with funds still confirming is asked its price and saved with it, and pays nothing`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()

            val row = world.acceptWhileConfirming(vm, TYPED)

            val stored = world.row()!!
            assertEquals(
                "the saved row does not hold the total the user accepted",
                row.acceptedTotal,
                FundsConfirmingChat.acceptedPrice(stored),
            )
            assertTrue("the saved row is not waiting (status ${stored.status}, pending ${stored.isPending})", world.isWaiting(row.id))
            assertEquals("the row was saved under another text", TYPED, stored.text)
            val shown = world.shown(vm, row.id)
            assertTrue(
                "the row is not on screen as waiting: $shown",
                shown != null && shown.isPending && shown.status != MessageStatus.FAILED,
            )
            assertNotEquals(
                "the send still answers 'try again'",
                CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
                error(vm),
            )
            assertNull("an accepted message was handed back to the input", restored(vm))
            assertEquals("something was paid while the funds confirm", 0, world.seam.submits.get())
        }

    // ── sends by itself, once ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the whole wait for ten confirmations pays nothing and the first spendable block sends it once`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)

            repeat(CONFIRMATIONS) {
                world.block(vm)
                assertTrue("the row gave up during the normal wait, at block ${it + 1}", world.isWaiting(row.id))
            }
            assertEquals("something was paid before the funds could be spent", 0, world.seam.submits.get())

            world.plan = "Fundable"
            world.block(vm)
            assertSentOnce(world)
            world.block(vm)
            world.block(vm)
            assertEquals("a later block paid again", BOTH_LEGS, world.seam.submits.get())
            assertTrue("a sent row reads as not sent", world.row()?.status != MessageStatus.FAILED.name)
        }

    @Test
    fun `with the chat left the row still sends once at the next block another screen hears`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val list = world.screen()
            val detail = world.screen()
            world.acceptWhileConfirming(detail, TYPED)

            world.leave(detail)
            world.plan = "Fundable"
            world.block(list)

            assertSentOnce(world)
        }

    @Test
    fun `two live screens hearing the same block send the row once`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val list = world.screen()
            val detail = world.screen()
            world.acceptWhileConfirming(detail, TYPED)

            world.plan = "Fundable"
            world.block(list, detail)

            assertSentOnce(world)
        }

    @Test
    fun `after a restart the row is still waiting and resumes from disk at the next block, once`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val before = world.screen()
            val row = world.acceptWhileConfirming(before, TYPED)

            val after = world.restart(before)
            assertTrue("the loader failed a row that waits on purpose", world.isWaiting(row.id))
            assertEquals("the row is not on screen as waiting", true, world.shown(after, row.id)?.isPending)
            world.block(after)
            assertEquals("a restarted phone paid while the funds still confirm", 0, world.seam.submits.get())

            world.plan = "Fundable"
            world.block(after)
            assertSentOnce(world)
        }

    // ── never above the accepted price ───────────────────────────────────────────────────────────────────

    @Test
    fun `a build above the accepted price pays nothing, stops for a decision, and no later block pays it`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)

            priceRisesThenFalls(world, vm, row.id)
        }

    @Test
    fun `after a restart the accepted price from disk still holds`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val before = world.screen()
            val row = world.acceptWhileConfirming(before, TYPED)

            priceRisesThenFalls(world, world.restart(before), row.id)
        }

    @Test
    fun `a message leg dearer than what is left of the accepted total pays nothing after its key exchange`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)

            // The key exchange goes at its price; the note that is left costs the message more than the total leaves.
            world.funds.feeFor = { if (world.seam.submits.get() == 0) FundsConfirmingChat.NORMAL_FEE else FEE_ABOVE_THE_TOTAL }
            world.plan = "Fundable"
            world.block(vm)

            assertTrue("the row did not stop for a decision", PaymentHarness.eventually(SETTLE_MS) { world.needsDecision(vm, row.id) })
            world.paymentsSettled()
            assertTrue(
                "paid ${world.funds.paidTo(PEER)} against an accepted total of ${row.acceptedTotal}",
                world.funds.paidTo(PEER) <= row.acceptedTotal,
            )
        }

    @Test
    fun `a plan that can no longer be funded stops for a decision and pays nothing, then or later`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)

            world.plan = "NotFundable"
            world.block(vm)
            assertTrue("the row did not stop for a decision", PaymentHarness.eventually(SETTLE_MS) { world.needsDecision(vm, row.id) })
            assertEquals("a changed plan paid", 0, world.seam.submits.get())

            world.plan = "Fundable"
            world.block(vm)
            assertTrue("a block paid a row that was stopped for the user's decision", world.staysAt(0))
        }

    // ── controls: green before and after ─────────────────────────────────────────────────────────────────

    @Test
    fun `control - a first message the wallet can pay now still asks and pays both legs at once`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            world.plan = "Fundable"

            world.chat.typeAndSend(vm, TYPED)
            assertEquals("fixture sanity: the fixture's question", 3, world.question(vm).first)
            vm.confirmPaidAction()

            assertSentOnce(world)
            assertEquals("fixture sanity: the wallet saw what each submit paid", BOTH_LEGS, world.funds.paid.count { PEER in it.first })
        }

    @Test
    fun `control - the fixture's restart and block tick reach the wallet`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            world.plan = "Fundable"
            val before = world.screen()
            world.chat.kexOwed += PEER
            world.chat.seedFailedFirstMessage(before, "pending_${System.nanoTime()}", TYPED)

            world.block(world.restart(before))

            assertTrue(
                "the owed key exchange was not paid at the tick after a restart",
                PaymentHarness.eventually(SETTLE_MS) { world.seam.submits.get() == 1 },
            )
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun priceRisesThenFalls(
        world: FundsConfirmingChat,
        vm: ChatViewModel,
        id: String,
    ) {
        world.funds.feeFor = { FEE_ABOVE_THE_TOTAL }
        world.plan = "Fundable"
        world.block(vm)
        assertTrue("the row did not stop for a decision", PaymentHarness.eventually(SETTLE_MS) { world.needsDecision(vm, id) })
        assertEquals("paid above the accepted price", 0, world.seam.submits.get())

        // The price comes back down: the stop stands until the user answers.
        world.funds.feeFor = { FundsConfirmingChat.NORMAL_FEE }
        world.block(vm)
        assertTrue("a block paid a row that was stopped for the user's decision", world.staysAt(0))
    }

    private suspend fun assertSentOnce(world: FundsConfirmingChat) {
        assertTrue(
            "the row did not send by itself: ${world.seam.submits.get()} of $BOTH_LEGS legs paid",
            PaymentHarness.eventually(SETTLE_MS) { world.seam.submits.get() >= BOTH_LEGS },
        )
        assertTrue("one message was paid more than once: ${world.seam.submits.get()} submits", world.staysAt(BOTH_LEGS))
        assertFalse("nothing should have been paid to an unknown request", world.funds.paid.any { it.first == "unknown" })
    }
}
