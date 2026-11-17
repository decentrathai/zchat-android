package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1235 follow-up — our own KEX, an owed KEX and a ZBOOT above the 17,000 zat every text quotes pay nothing and ask the true
 * figure on the next chat open. For each: the question, Pay at exactly the figure, Cancel at nothing; a question dropped once
 * our key changed (the store's half of a Reset) or the setup was paid; and the notice's Retry above its price. What a message
 * send says when its KEX is above the limit is in FirstMessageKexIsNotLostTest, which has the message path's fixture.
 *
 * [ABOVE] raises the 15,000 fee to 20,000 (22,000 with the output). `paidFigures` reads the fee from the very proposal handed
 * to submit. The send path above ProposalDataSource is the real one. No wire or stored format is involved. Every address, key
 * and id is synthetic.
 */
class OwnSetupTruePriceIsAskedAndPaidOnceTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private enum class Setup { KEX, OWED_KEX, ZBOOT }

    private fun world(
        setup: Setup,
        mode: ConversationMode = ConversationMode.TUNNEL,
    ) = LedgerWorld(
        mode = mode,
        exchangeComplete = setup == Setup.ZBOOT,
        consent = true,
        accountSource = OpenChatTunnelOfferPhone.funded(),
    ).also {
        if (setup == Setup.OWED_KEX) {
            it.prefs.kexOwed += it.peer
            it.prefs.kexOwedReason[it.peer] = ZchatPreferences.KEX_OWED_REASON_RESET
            // ZC-285: a paid Reset leaves the bootstrap marker set; its KEX is the owed one.
            it.base.ownBootSent[it.peer] = true
        }
    }

    /** The path that pays [setup] with nobody asked. */
    private suspend fun LedgerWorld.trigger(
        setup: Setup,
        vm: ChatViewModel,
    ) {
        when (setup) {
            Setup.KEX -> chatOpen(vm)
            Setup.OWED_KEX -> {
                vm.retryOwedKeyExchange(peer)
                HandshakeHarness.awaitIdle(vm)
            }
            Setup.ZBOOT -> sendZboot(vm)
        }
    }

    private suspend fun LedgerWorld.nextOpen(vm: ChatViewModel) {
        vm.retryKexAckIfResponder(peer)
        HandshakeHarness.awaitIdle(vm)
    }

    private fun held(
        setup: Setup,
        body: suspend LedgerWorld.(ChatViewModel) -> Unit,
    ) = runBlocking {
        val world = world(setup)
        val vm = world.openVm()
        world.seam.proposalFeeDelta = ABOVE
        world.trigger(setup, vm)
        assertEquals("fixture: $setup was not built", 1, world.seam.proposals.get())
        assertEquals("$setup paid above its quote", emptyList<Long>(), world.seam.paidFigures.toList())
        world.body(vm)
    }

    private fun assertAsked(
        vm: ChatViewModel,
        action: PaidAction = PaidAction.OWN_SETUP_TRUE_PRICE,
        figure: Long = TRUE_FIGURE,
    ) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("no question is on screen: $state", state is SendMessageState.NeedsPaidActionConfirmation)
        val asked = (state as SendMessageState.NeedsPaidActionConfirmation).confirmation
        assertEquals(action, asked.action)
        assertEquals("the question's figure", Zatoshi(figure), asked.totalCost)
        assertTrue("the true price is not asked as exact", asked.exact)
        assertEquals("the question's network fee", Zatoshi(figure - OUTPUT), asked.networkFee)
    }

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse("a question was raised: $state", state is SendMessageState.NeedsPaidActionConfirmation)
    }

    private suspend fun confirm(vm: ChatViewModel) {
        vm.confirmPaidAction()
        HandshakeHarness.awaitIdle(vm)
    }

    private fun asksOnTheNextOpen(setup: Setup) =
        held(setup) { vm ->
            assertNoQuestion(vm)
            nextOpen(vm)
            assertAsked(vm)
            assertEquals("the question built again", 1, seam.proposals.get())
            assertEquals("the question paid", 0, seam.submits.get())
        }

    private fun payPaysTheFigureOnce(setup: Setup) =
        held(setup) { vm ->
            nextOpen(vm)
            confirm(vm)
            assertEquals("what $setup paid", listOf(TRUE_FIGURE), seam.paidFigures.toList())

            repeat(2) { nextOpen(vm) }
            trigger(setup, vm)
            assertEquals("$setup paid again after its Pay", listOf(TRUE_FIGURE), seam.paidFigures.toList())
        }

    private fun cancelPaysNothing(setup: Setup) =
        held(setup) { vm ->
            nextOpen(vm)
            vm.dismissPaidAction()
            HandshakeHarness.awaitIdle(vm)
            assertEquals("Cancel paid", 0, seam.submits.get())
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(vm))

            repeat(2) {
                nextOpen(vm)
                assertNoQuestion(vm)
            }
            assertEquals("a later open paid after Cancel", 0, seam.submits.get())
            assertEquals("a later open built after Cancel", 1, seam.proposals.get())
        }

    private fun LedgerWorld.ourKeyIsReplaced() {
        base.ourKeys[peer] = E2EEncryption.generateKeyPair()
    }

    private fun droppedAfterAReset(setup: Setup) =
        held(setup) { vm ->
            ourKeyIsReplaced()
            nextOpen(vm)
            assertNoQuestion(vm)
            assertEquals("a question of the old key paid", 0, seam.submits.get())
        }

    private fun payAfterAResetPaysNothing(setup: Setup) =
        held(setup) { vm ->
            nextOpen(vm)
            assertAsked(vm)
            ourKeyIsReplaced()
            confirm(vm)
            assertEquals("Pay on the old key's question paid", 0, seam.submits.get())
        }

    private fun droppedOncePaid(setup: Setup) =
        held(setup) { vm ->
            seam.proposalFeeDelta = 0L
            trigger(setup, vm)
            assertEquals("fixture: the setup was not paid at the usual price", listOf(QUOTE), seam.paidFigures.toList())

            nextOpen(vm)

            assertNoQuestion(vm)
            assertEquals("a setup already paid was paid again", listOf(QUOTE), seam.paidFigures.toList())
        }

    @Test
    fun `KEX - the next open asks the true figure`() = asksOnTheNextOpen(Setup.KEX)

    @Test
    fun `owed KEX - the next open asks the true figure`() = asksOnTheNextOpen(Setup.OWED_KEX)

    @Test
    fun `ZBOOT - the next open asks the true figure`() = asksOnTheNextOpen(Setup.ZBOOT)

    @Test
    fun `KEX - Pay pays exactly the figure once`() = payPaysTheFigureOnce(Setup.KEX)

    @Test
    fun `owed KEX - Pay pays exactly the figure once`() = payPaysTheFigureOnce(Setup.OWED_KEX)

    @Test
    fun `ZBOOT - Pay pays exactly the figure once`() = payPaysTheFigureOnce(Setup.ZBOOT)

    @Test
    fun `KEX - Cancel pays nothing`() = cancelPaysNothing(Setup.KEX)

    @Test
    fun `owed KEX - Cancel pays nothing`() = cancelPaysNothing(Setup.OWED_KEX)

    @Test
    fun `ZBOOT - Cancel pays nothing`() = cancelPaysNothing(Setup.ZBOOT)

    @Test
    fun `KEX - the question is dropped after a Reset`() = droppedAfterAReset(Setup.KEX)

    @Test
    fun `owed KEX - the question is dropped after a Reset`() = droppedAfterAReset(Setup.OWED_KEX)

    @Test
    fun `ZBOOT - the question is dropped after a Reset`() = droppedAfterAReset(Setup.ZBOOT)

    @Test
    fun `KEX - Pay after a Reset pays nothing`() = payAfterAResetPaysNothing(Setup.KEX)

    @Test
    fun `owed KEX - Pay after a Reset pays nothing`() = payAfterAResetPaysNothing(Setup.OWED_KEX)

    @Test
    fun `ZBOOT - Pay after a Reset pays nothing`() = payAfterAResetPaysNothing(Setup.ZBOOT)

    @Test
    fun `KEX - the question is dropped once the setup is paid`() = droppedOncePaid(Setup.KEX)

    @Test
    fun `owed KEX - the question is dropped once the setup is paid`() = droppedOncePaid(Setup.OWED_KEX)

    @Test
    fun `ZBOOT - the question is dropped once the setup is paid`() = droppedOncePaid(Setup.ZBOOT)

    // ── A first message's owed KEX: no reason, and the bootstrap marker was never set ───────────────────────────────

    /** Held above the limit, then paid at the usual price by the next attempt (the block tick's path), with a price kept. */
    private fun firstMessageKexHeldThenPaid(body: suspend LedgerWorld.(ChatViewModel) -> Unit) =
        runBlocking {
            val world =
                LedgerWorld(mode = ConversationMode.TUNNEL, consent = true, accountSource = OpenChatTunnelOfferPhone.funded())
            world.prefs.kexOwed += world.peer
            val vm = world.openVm()
            world.seam.proposalFeeDelta = ABOVE
            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("fixture: the first message's KEX was not built", 1, world.seam.proposals.get())
            world.seam.proposalFeeDelta = 0L
            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("fixture: the KEX was not paid at the usual price", listOf(QUOTE), world.seam.paidFigures.toList())
            assertFalse("fixture: the KEX is still owed", world.peer in world.prefs.kexOwed)
            world.body(vm)
        }

    @Test
    fun `a first message's KEX paid at the usual price raises no question for it`() =
        firstMessageKexHeldThenPaid { vm ->
            nextOpen(vm)
            assertNoQuestion(vm)
        }

    @Test
    fun `a first message's KEX paid at the usual price is not paid again`() =
        firstMessageKexHeldThenPaid { vm ->
            seam.proposalFeeDelta = ABOVE
            nextOpen(vm)
            if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) confirm(vm)
            assertEquals("the KEX was paid twice", listOf(QUOTE), seam.paidFigures.toList())
        }

    // ── The notice's Retry above its price ──────────────────────────────────────────────────────────────────────────

    /** The notice's Retry confirmed at the usual price, with the build now above it. */
    private fun retryAbove(
        exchangeComplete: Boolean,
        proven: suspend LedgerWorld.(ChatViewModel) -> Long,
        action: PaidAction,
        body: suspend LedgerWorld.(ChatViewModel) -> Unit,
    ) = runBlocking {
        val world =
            LedgerWorld(exchangeComplete = exchangeComplete, consent = true, accountSource = OpenChatTunnelOfferPhone.funded())
        val vm = world.openVm()
        val marker = world.proven(vm)
        val submits = world.seam.submits.get()
        world.seam.proposalFeeDelta = ABOVE

        vm.retryHandshake(world.peer, marker)
        HandshakeHarness.awaitIdle(vm)
        confirm(vm)

        assertEquals("a Retry above its price paid", submits, world.seam.submits.get())
        assertAsked(vm, action)
        world.body(vm)
    }

    private val payThenTheFigure: suspend LedgerWorld.(ChatViewModel) -> Unit = { vm ->
        confirm(vm)
        assertEquals("what the Retry paid", listOf(TRUE_FIGURE), seam.paidFigures.toList())
    }

    private val cancelThenNothing: suspend LedgerWorld.(ChatViewModel) -> Unit = { vm ->
        vm.dismissPaidAction()
        HandshakeHarness.awaitIdle(vm)
        assertEquals("Cancel on the Retry paid", emptyList<Long>(), seam.paidFigures.toList())
    }

    private val kex: suspend LedgerWorld.(ChatViewModel) -> Long = { kexProvenNotSent(it) }
    private val zboot: suspend LedgerWorld.(ChatViewModel) -> Long = { zbootProvenNotSent(it) }
    private val kexAck: suspend LedgerWorld.(ChatViewModel) -> Long = { kexAckProvenNotSent(it) }

    @Test
    fun `KEX Retry above its price asks, and Pay pays the figure`() =
        retryAbove(false, kex, PaidAction.OWN_SETUP_TRUE_PRICE, payThenTheFigure)

    @Test
    fun `KEX Retry above its price asks, and Cancel pays nothing`() =
        retryAbove(false, kex, PaidAction.OWN_SETUP_TRUE_PRICE, cancelThenNothing)

    @Test
    fun `ZBOOT Retry above its price asks, and Pay pays the figure`() =
        retryAbove(true, zboot, PaidAction.OWN_SETUP_TRUE_PRICE, payThenTheFigure)

    @Test
    fun `ZBOOT Retry above its price asks, and Cancel pays nothing`() =
        retryAbove(true, zboot, PaidAction.OWN_SETUP_TRUE_PRICE, cancelThenNothing)

    @Test
    fun `KEXACK Retry above its price asks, and Pay pays the figure`() =
        retryAbove(false, kexAck, PaidAction.KEX_ANSWER_TRUE_PRICE, payThenTheFigure)

    @Test
    fun `KEXACK Retry above its price asks, and Cancel pays nothing`() =
        retryAbove(false, kexAck, PaidAction.KEX_ANSWER_TRUE_PRICE, cancelThenNothing)

    private companion object {
        const val OUTPUT = 2_000L
        const val ABOVE = 5_000L
        const val QUOTE = 17_000L
        const val TRUE_FIGURE = 22_000L
    }
}
