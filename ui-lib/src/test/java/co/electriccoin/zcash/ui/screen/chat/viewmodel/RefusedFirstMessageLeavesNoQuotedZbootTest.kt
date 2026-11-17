package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.copy
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T5b cross-review X-01 (M2; found by Grok and GPT, train/T5b 469416b07): spend without consent.
 *
 * Pay on the first-message question writes the quoted-ZBOOT allowance (R10B-1: the ZBOOT that goes on-chain after the
 * contact's KEXACK may be paid only against it) and then hands the message to the send. M2's deciding gate in the send
 * can still refuse it (the plan stopped being fundable while the question was open): nothing is paid, the text goes back
 * to the box and the notice says "Nothing was paid". At f0ae66e8a the allowance stays on disk. A KEXACK or an inbound
 * ZBOOT that arrives later then pays one on-chain ZBOOT (about 17,000 zatoshi) on a consent the user was told came to
 * nothing. The Keystone refusal next to the gate already drops the allowance; the gate does not.
 *
 * Wanted: a first message that is not sent (the gate refuses it after Pay, or its question is dismissed; ZC-1272: no
 * cost notice stands behind the question any more) leaves no allowance it wrote itself. An allowance an earlier confirmed send left is not this
 * send's to take away, and a first message that is sent keeps its allowance (controls). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusedFirstMessageLeavesNoQuotedZbootTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val TYPED = "synthetic first words to a new contact"
    }

    @Before
    fun setUp() {
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
    }

    @Test
    fun `a first message the gate refuses after Pay leaves no quoted ZBOOT behind`() =
        runBlocking {
            val peer = "usyntheticx01gaterefusal0001"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "NotFundable"))
            val allowance = ZbootAllowance(chat, vm)

            chat.typeAndSend(vm, TYPED)
            assertTrue(chat.asked(vm), "fixture sanity: a fundable first message is asked about")
            assertTrue(quotesTheZboot(vm), "fixture sanity: the question does not quote our ZBOOT")
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "fixture sanity: the refused first message paid something")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE"), error(vm), "fixture sanity: the gate did not refuse")
            assertFalse(
                allowance.quoted(peer),
                "the user was told nothing was paid, but the quoted-ZBOOT allowance is still on disk: a later KEXACK pays a ZBOOT on it",
            )
        }

    @Test
    fun `a first message whose question is dismissed with the cost notice unread leaves no quoted ZBOOT behind`() =
        runBlocking {
            val peer = "usyntheticx01costnotice00001"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            val allowance = ZbootAllowance(chat, vm, costAcknowledged = false)

            chat.typeAndSend(vm, TYPED)
            assertTrue(chat.asked(vm), "fixture sanity: a fundable first message is asked about")
            assertTrue(quotesTheZboot(vm), "fixture sanity: the question does not quote our ZBOOT")
            assertFalse(costNoticeShown(vm), "the cost notice stands on top of the first-message question")

            vm.dismissPaidAction()
            awaitIdle(vm)

            assertFalse(costNoticeShown(vm), "the cost notice followed a question the user dismissed")
            assertEquals(0, chat.proposals(), "a dismissed question paid something")
            assertFalse(allowance.quoted(peer), "the question was dismissed, nothing was sent, and a quoted-ZBOOT allowance stays")
        }

    /**
     * Whether the first-message question on screen counts our relay identity (the ZBOOT) as an on-chain transaction.
     * ZC-590: this is the fixture sanity of the two tests above since the allowance is written by the send once it has
     * taken the message, not by Pay; before, they checked that Pay had written it.
     */
    private fun quotesTheZboot(vm: ChatViewModel): Boolean {
        val state = HandshakeHarness.sendMessageState(vm)
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        return confirmation.javaClass.getMethod("getIncludesRelayIdentity").invoke(confirmation) as Boolean
    }

    /** Whether the one-time "messages cost ZEC" notice is on screen (the field behind showCostDisclaimer). */
    private fun costNoticeShown(vm: ChatViewModel): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("_showCostDisclaimer")
            .apply { isAccessible = true }
            .let { (it.get(vm) as MutableStateFlow<*>).value == true }

    /** Control, green before and after: the allowance of an earlier confirmed send is not this send's to take away. */
    @Test
    fun `control - a refused first message keeps an allowance an earlier confirmed send left`() =
        runBlocking {
            val peer = "usyntheticx01earlierquote001"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "NotFundable"))
            val allowance = ZbootAllowance(chat, vm)
            allowance.seed(peer)

            chat.typeAndSend(vm, TYPED)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "the refused first message paid something")
            assertTrue(allowance.quoted(peer), "an allowance an earlier confirmed send left was dropped by a later refusal")
        }

    /** Control, green before and after: a first message that is sent keeps the allowance its question quoted. */
    @Test
    fun `control - a first message that is sent keeps its quoted ZBOOT`() =
        runBlocking {
            val peer = "usyntheticx01sentkeepsit0001"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            val allowance = ZbootAllowance(chat, vm)

            chat.typeAndSend(vm, TYPED)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(2, chat.proposals(), "confirming pays the KEX and the message")
            assertTrue(allowance.quoted(peer), "the ZBOOT the question quoted lost its allowance although the message was sent")
        }

    /**
     * The quoted-ZBOOT allowance as the store keeps it, over [chat]'s preferences: every write is recorded.
     * [costAcknowledged] false: the one-time "messages cost ZEC" notice was never accepted.
     */
    private class ZbootAllowance(
        chat: FirstMessagePlanChat,
        vm: ChatViewModel,
        costAcknowledged: Boolean = true,
    ) {
        private val quoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val writes: MutableList<Pair<String, Boolean>> = CopyOnWriteArrayList()

        fun quoted(peer: String): Boolean = peer in quoted

        fun seed(peer: String) {
            quoted += peer
        }

        init {
            val prefs =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { p, method, args ->
                    when (method.name) {
                        "isZbootQuoted" -> args!![0] in quoted
                        "setZbootQuoted" -> {
                            val on = args!![1] as Boolean
                            writes += (args[0] as String) to on
                            if (on) quoted += args[0] as String else quoted -= args[0] as String
                            true
                        }
                        "hasAcknowledgedMessageCost" -> costAcknowledged
                        "toString" -> "ZbootAllowance"
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> p === args?.get(0)
                        else ->
                            try {
                                method.invoke(chat.prefs, *(args ?: emptyArray()))
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                    }
                } as ZchatPreferences
            PaymentHarness.setField(vm, "zchatPreferences", prefs)
        }
    }
}
