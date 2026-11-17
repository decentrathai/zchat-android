package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1272 (c), money-path rules 1 and 2: the one question's Pay is consent for what it quoted and nothing else. A leg above
 * its price is refused and the question comes back with the new figure; a leg outside the quote is not paid; the standing
 * consent is not implied for later automatic spends; a contact's key change still asks.
 *
 * The controls are green before and after the fix: they keep the fix from answering ZC-1272 by turning the standing consent
 * on behind the user's back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EveryLegHeldToItsQuoteTest {
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

    private suspend fun paidTunnelQuestion(world: OneDecisionWorld) {
        val form = world.newChatIn(ConversationMode.TUNNEL)
        world.tap(form)
        world.pay(form)
    }

    @Test
    fun `a key exchange above its leg pays nothing and asks again with the new figure, not the standing consent`() =
        runBlocking {
            val world = OneDecisionWorld()
            paidTunnelQuestion(world)
            val paidByTheMessage = world.submits()

            world.fee(FEE_ABOVE_THE_LEG)
            val detail = world.chatScreen()
            world.openChat(detail)

            world.funds.paymentsSettled()
            assertEquals("a key exchange above its leg was paid", paidByTheMessage, world.submits())
            assertFalse("a changed price fell back to the standing consent question", world.askedHandshakeConsent(detail))
            val again = world.paidQuestion(detail)
            assertNotNull("a changed plan did not ask again", again)
            assertTrue(
                "the new question ${again!!.totalCost.value} does not state the new price of the leg",
                again.totalCost.value >= FEE_ABOVE_THE_LEG + FundsConfirmingChat.LEG_OUTPUTS,
            )
        }

    @Test
    fun `control - a Pay never writes the standing consent`() =
        runBlocking {
            val world = OneDecisionWorld()
            paidTunnelQuestion(world)
            world.openChat(world.chatScreen())

            assertEquals(
                "Pay turned on the standing consent for every later automatic spend",
                0,
                world.memory.consentWrites.size,
            )
            assertFalse(world.memory.consent)
        }

    @Test
    fun `control - a setup towards another contact is outside the quote and still asks`() =
        runBlocking {
            val world = OneDecisionWorld()
            paidTunnelQuestion(world)
            val paidBefore = world.wallet.paidTo(OneDecisionWorld.OTHER_PEER)

            val detail = world.chatScreen()
            detail.sendKEXMessage(OneDecisionWorld.OTHER_PEER, HandshakeHarness.SELF)
            HandshakeHarness.awaitIdle(detail)

            world.funds.paymentsSettled()
            assertEquals("a contact outside the quote was paid under it", paidBefore, world.wallet.paidTo(OneDecisionWorld.OTHER_PEER))
            assertTrue("a contact outside the quote was not asked about", world.askedHandshakeConsent(detail))
        }

    @Test
    fun `control - a second key exchange forced later is outside the quote and is not paid under it`() =
        runBlocking {
            val world = OneDecisionWorld()
            paidTunnelQuestion(world)
            val detail = world.chatScreen()
            world.openChat(detail)
            world.funds.paymentsSettled()
            val before = world.submits()

            detail.ensureNostrBootstrapSent(world.peer, force = true)
            HandshakeHarness.awaitIdle(detail)

            world.funds.paymentsSettled()
            assertEquals("a key exchange the question did not count was paid", before, world.submits())
        }

    @Test
    fun `control - while the contact's new key waits nothing is paid, and the reconnect asks its own price`() =
        runBlocking {
            val world = OneDecisionWorld()
            paidTunnelQuestion(world)
            val paidByTheMessage = world.submits()
            world.chat.fixture.keyChanged = true

            val detail = world.chatScreen()
            world.openChat(detail)
            world.funds.paymentsSettled()
            assertEquals("a key exchange was paid while the contact's new key waits", paidByTheMessage, world.submits())

            detail.dismissE2EKeyChanged(world.peer)
            HandshakeHarness.awaitIdle(detail)
            assertNotNull("a contact's key change no longer asks", world.paidQuestion(detail))
            assertEquals("the reconnect paid before its question", paidByTheMessage, world.submits())
        }

    private companion object {
        const val FEE_ABOVE_THE_LEG = 100_000L
    }
}
