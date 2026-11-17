package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OneDecisionWorld.Companion.LONG_WORDS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OneDecisionWorld.Companion.NORMAL_LEG
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
 * ZC-1272 (owner's hands-on test of 2.13.0): choosing Tunnel with its cost, then "Allow handshake transactions?" on the
 * chat; on a first reply a price question and then the cost notice "Shielded messages travel inside Zcash…".
 *
 * Must hold: one decision per chat. One question states every leg (key exchange, ZBOOT, message, fees) and what it costs,
 * and its Pay is the answer for those legs: no further question for them, and no notice on top of a question. Real
 * ZchatComposeVM and ChatViewModel over one store and wallet ([OneDecisionWorld]). The standing handshake consent is off
 * here, so only the question can authorise a leg.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneDecisionPerChatTest {
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

    // ── (a) the question states every leg, and its Pay answers for them ──────────────────────────────────

    @Test
    fun `the Tunnel question of New Chat states the key exchange, the ZBOOT and the message with their total`() =
        runBlocking {
            val world = OneDecisionWorld()
            val form = world.newChatIn(ConversationMode.TUNNEL)

            world.tap(form)

            val asked = NewChatPriceQuestion.require(form)
            assertEquals("the question does not count every leg of a Tunnel chat", TUNNEL_LEGS, asked.onChainMessages)
            assertEquals("the question's total is not the sum of the legs", TUNNEL_LEGS * NORMAL_LEG, asked.totalCost.value)
            assertTrue("the question does not name the chat's setup", asked.includesChatSetup)
            assertTrue("the question does not name the relay identity", asked.includesRelayIdentity)
        }

    @Test
    fun `after Pay on the Tunnel question, opening the chat asks nothing more for the key exchange`() =
        runBlocking {
            val world = OneDecisionWorld()
            val form = world.newChatIn(ConversationMode.TUNNEL)
            world.tap(form)
            val asked = world.pay(form)

            val detail = world.chatScreen()
            world.openChat(detail)

            assertFalse("a key exchange the question covered asked 'Allow handshake transactions?'", world.askedHandshakeConsent(detail))
            assertEquals("the chat asked a second price for a leg the question covered", null, world.paidQuestion(detail))
            assertTrue(
                "the key exchange the question covered was not paid",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.submits() == MESSAGE_AND_KEX },
            )
            world.funds.paymentsSettled()
            assertTrue(
                "paid ${world.wallet.paidTo(world.peer)} against a question of ${asked.totalCost.value}",
                world.wallet.paidTo(world.peer) <= asked.totalCost.value,
            )
        }

    @Test
    fun `after Pay on the Tunnel question, the contact's answer pays the ZBOOT with no question`() =
        runBlocking {
            val world = OneDecisionWorld()
            val form = world.newChatIn(ConversationMode.TUNNEL)
            world.tap(form)
            val asked = world.pay(form)
            val detail = world.chatScreen()
            world.openChat(detail)
            val before = world.submits()

            world.contactAnswered()
            AutoSpendVm.sendNostrBoot(detail, world.peer)
            HandshakeHarness.awaitIdle(detail)

            assertFalse("a ZBOOT the question covered asked 'Allow handshake transactions?'", world.askedHandshakeConsent(detail))
            assertTrue(
                "the ZBOOT the question covered was not paid",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.submits() == before + 1 },
            )
            assertTrue(
                "paid ${world.wallet.paidTo(world.peer)} against a question of ${asked.totalCost.value}",
                world.wallet.paidTo(world.peer) <= asked.totalCost.value,
            )
        }

    // ── (b) a confirmed price question is not followed by, or behind, the cost notice ────────────────────

    @Test
    fun `a confirmed first-message question shows no cost notice and sends`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.funds.plan = "Fundable"
            world.memory.costAcknowledged = false
            val vm = world.chatScreen()

            world.chat.typeAndSend(vm, OneDecisionWorld.FIRST_WORDS)
            assertNotNull("fixture sanity: the first message asks its price", world.paidQuestion(vm))
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertFalse("the cost notice followed a price question the user confirmed", costNoticeShown(vm))
            assertTrue(
                "the confirmed first message was not sent",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.submits() == MESSAGE_AND_KEX },
            )
        }

    @Test
    fun `a confirmed long-message question shows no cost notice`() =
        runBlocking {
            val chat = FirstMessagePlanChat(OneDecisionWorld.PEER, haveOurKey = true)
            val store = OneDecisionWorld.RememberingStore(chat.prefs).apply { costAcknowledged = false }
            val vm = chat.viewModel(null, leaveCheckNull = true)
            PaymentHarness.setField(vm, "zchatPreferences", store.proxy)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.sendMessage(chat.peer, LONG_WORDS)
            HandshakeHarness.awaitIdle(vm)
            assertEquals(
                "fixture sanity: a long message asks its price",
                "NeedsPaidActionConfirmation",
                HandshakeHarness.sendMessageState(vm).javaClass.simpleName,
            )
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertFalse("the cost notice followed a price question the user confirmed", costNoticeShown(vm))
        }

    @Test
    fun `a long reply asks its price alone, with no cost notice before it`() =
        runBlocking {
            val chat = FirstMessagePlanChat(OneDecisionWorld.PEER, haveOurKey = true)
            val store = OneDecisionWorld.RememberingStore(chat.prefs).apply { costAcknowledged = false }
            val vm = chat.viewModel(null, leaveCheckNull = true)
            PaymentHarness.setField(vm, "zchatPreferences", store.proxy)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.sendReply(chat.peer, LONG_WORDS, "synthetic-reply-target", "synthetic quote", 1_000L)
            HandshakeHarness.awaitIdle(vm)

            assertFalse("a first reply met the cost notice as well as its price question", costNoticeShown(vm))
            assertEquals(
                "a long reply did not ask its price",
                "NeedsPaidActionConfirmation",
                HandshakeHarness.sendMessageState(vm).javaClass.simpleName,
            )
        }

    @Test
    fun `New Chat asks its price with no cost notice before or after`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.memory.costAcknowledged = false
            val form = world.newChatIn(ConversationMode.VAULT)

            world.tap(form)

            assertFalse("New Chat showed the cost notice on top of its price question", form.showCostDisclaimer.value)
            world.pay(form)
            assertFalse("the cost notice followed a price question the user confirmed", form.showCostDisclaimer.value)
            assertEquals("Pay did not send", 1, world.submits())
        }

    /** Control, green before and after: a one-memo message that asks no price still gets the one-time notice. */
    @Test
    fun `control - a one-memo message with no price question still shows the notice once`() =
        runBlocking {
            val chat = FirstMessagePlanChat(OneDecisionWorld.PEER, haveOurKey = true)
            val store = OneDecisionWorld.RememberingStore(chat.prefs).apply { costAcknowledged = false }
            val vm = chat.viewModel(null, leaveCheckNull = true)
            PaymentHarness.setField(vm, "zchatPreferences", store.proxy)

            vm.sendMessage(chat.peer, OneDecisionWorld.FIRST_WORDS)
            HandshakeHarness.awaitIdle(vm)

            assertTrue("a message that asks no price lost its one-time notice", costNoticeShown(vm))
            val seam = chat.fixture.seam
            assertEquals("the notice paid", 0, seam.submits.get())
        }

    private companion object {
        /** The key exchange, the ZBOOT and the message. */
        const val TUNNEL_LEGS = 3
        const val MESSAGE_AND_KEX = 2
    }
}
