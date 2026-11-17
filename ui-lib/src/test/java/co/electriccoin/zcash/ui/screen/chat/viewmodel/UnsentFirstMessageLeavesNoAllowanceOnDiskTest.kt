package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.copy
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-590 (medium; the narrowed rest of ZC-575 / T5b X-01, closed in train T6a batch ZC-536) — the quoted-ZBOOT allowance
 * of a confirmed first message that never went out is left on disk with no owner.
 *
 * T5b's fix clears the allowance when a confirmed first message is then not sent, but "this Pay wrote the allowance and
 * no message has gone out for it yet" lived only in memory. After the end of the process between Pay and the send's gate
 * (the message waited behind the one-time cost notice, or behind a running send) the allowance stays on disk. The next
 * Pay does not take it over (it is already set), so a later cancel or refusal no longer clears it, and a later key reply
 * or inbound ZBOOT can pay one setup message (about 17,000 zatoshi) for a first message that never went out. A clear whose
 * write fails was only logged, with the same result.
 *
 * Must hold: an allowance is on disk only for a first message the send has taken (it passed its gate). So nothing is
 * left by a process that ended before the gate, by a later question that is cancelled, by a later confirmed message the
 * gate refuses, or by a store that cannot save a clear. Controls: a first message that is sent has its allowance on disk
 * before anything is paid, and the allowance an earlier sent message left survives a later refusal.
 *
 * Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsentFirstMessageLeavesNoAllowanceOnDiskTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val TYPED = "synthetic first words to a new contact"

        /** The end of the process: what ChatViewModel keeps process-wide about quoted ZBOOTs and owed KEXes is lost. */
        fun forgetProcessState() {
            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            FirstMessagePlanChat.resetOwedKexState()
            for (name in listOf("unsentZbootQuotes", "unsavedZbootWithdrawals", "nostrBootInFlight")) {
                val field =
                    ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
                        ?: continue
                field.isAccessible = true
                (field.get(null) as? MutableCollection<*>)?.clear()
            }
        }
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        forgetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        forgetProcessState()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `after the process ends behind the open question a later first message the gate refuses leaves no allowance`() =
        runBlocking {
            val peer = "usyntheticzc590restartgate01"
            val chat = FirstMessagePlanChat(peer)
            val disk = Disk(chat)
            askUntilTheQuestionThenEndTheProcess(chat, disk)

            // The process came back. The same first message is typed again, confirmed, and refused by the gate:
            // "Nothing was paid".
            val next = disk.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "NotFundable"))
            chat.typeAndSend(next, TYPED)
            assertTrue(chat.asked(next), "fixture sanity: a fundable first message is asked about")
            next.confirmPaidAction()
            awaitIdle(next)

            assertEquals(0, chat.proposals(), "fixture sanity: something was paid")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE"), error(next), "fixture sanity: the gate did not refuse")
            assertFalse(
                disk.quoted(peer),
                "no first message ever went out, but a quoted-ZBOOT allowance is on disk: a later key reply pays a ZBOOT on it",
            )
        }

    @Test
    fun `after the process ends behind the open question a later first-message question that is cancelled leaves no allowance`() =
        runBlocking {
            val peer = "usyntheticzc590restartcancel"
            val chat = FirstMessagePlanChat(peer)
            val disk = Disk(chat)
            askUntilTheQuestionThenEndTheProcess(chat, disk)

            val next = disk.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            chat.typeAndSend(next, TYPED)
            assertTrue(chat.asked(next), "fixture sanity: a fundable first message is asked about")
            next.dismissPaidAction()
            awaitIdle(next)

            assertEquals(0, chat.proposals(), "a cancelled question paid something")
            assertFalse(
                disk.quoted(peer),
                "the question was cancelled and no first message ever went out, but a quoted-ZBOOT allowance is on disk",
            )
        }

    /** The failed-write half: a clear the store does not save must not leave an allowance that can pay. */
    @Test
    fun `a first message the gate refuses leaves no allowance even when the store cannot save a clear`() =
        runBlocking {
            val peer = "usyntheticzc590clearnotsaved"
            val chat = FirstMessagePlanChat(peer)
            val disk = Disk(chat, clearsSave = false)
            val vm = disk.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "NotFundable"))

            chat.typeAndSend(vm, TYPED)
            assertTrue(chat.asked(vm), "fixture sanity: a fundable first message is asked about")
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "fixture sanity: the refused first message paid something")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE"), error(vm), "fixture sanity: the gate did not refuse")
            assertFalse(
                disk.quoted(peer),
                "the user was told nothing was paid, the store could not save the clear, and the quoted-ZBOOT allowance stays on disk",
            )
        }

    /** Control, green before and after (R10B-1, R5-2): the allowance of a message that is sent is on disk before any payment. */
    @Test
    fun `control - a first message that is sent has its allowance on disk before anything is paid`() =
        runBlocking {
            val peer = "usyntheticzc590sentkeepsit01"
            val chat = FirstMessagePlanChat(peer)
            val disk = Disk(chat)
            val vm = disk.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            // The first payment the send asks for is the KEX: note whether the allowance was on disk at that moment.
            disk.noteWhenKexIsOwed = true

            chat.typeAndSend(vm, TYPED)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(2, chat.proposals(), "confirming pays the KEX and the message")
            assertTrue(disk.quoted(peer), "the ZBOOT the question quoted has no allowance although the message was sent")
            assertEquals(
                true,
                disk.quotedWhenKexWasOwed,
                "the allowance was not on disk when the send recorded its KEX as owed (it must be there before anything is paid)",
            )
        }

    /** Control, green before and after: an allowance an earlier sent message left is not a later refusal's to take. */
    @Test
    fun `control - a refused first message keeps the allowance an earlier sent message left`() =
        runBlocking {
            val peer = "usyntheticzc590earlierquote1"
            val chat = FirstMessagePlanChat(peer)
            val disk = Disk(chat)
            disk.seed(peer)
            val vm = disk.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "NotFundable"))

            chat.typeAndSend(vm, TYPED)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "the refused first message paid something")
            assertTrue(disk.quoted(peer), "an allowance an earlier sent message left was dropped by a later refusal")
        }

    /** The first-message question is open, unanswered, when the process ends (ZC-1272: no notice waits behind its Pay). */
    private suspend fun askUntilTheQuestionThenEndTheProcess(
        chat: FirstMessagePlanChat,
        disk: Disk,
    ) {
        val first = disk.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
        chat.typeAndSend(first, TYPED)
        assertTrue(chat.asked(first), "fixture sanity: a fundable first message is asked about")
        assertTrue(quotesTheZboot(first), "fixture sanity: the question does not quote our ZBOOT")
        assertEquals(0, chat.proposals(), "fixture sanity: something was paid behind the open question")

        first.viewModelScope.coroutineContext.job
            .cancelAndJoin()
        forgetProcessState()
    }

    /** Whether the first-message question on screen counts our relay identity (the ZBOOT) as an on-chain transaction. */
    private fun quotesTheZboot(vm: ChatViewModel): Boolean {
        val state = HandshakeHarness.sendMessageState(vm)
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        return confirmation.javaClass.getMethod("getIncludesRelayIdentity").invoke(confirmation) as Boolean
    }

    /**
     * What stays on disk between processes, over [chat]'s preferences: the quoted-ZBOOT allowance. [clearsSave] false: a write that clears the allowance reports "not saved" and changes
     * nothing. Every ViewModel made by [viewModel] reads this one store.
     */
    private class Disk(
        private val chat: FirstMessagePlanChat,
        private val clearsSave: Boolean = true,
    ) {
        private val quoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val writes: MutableList<Pair<String, Boolean>> = CopyOnWriteArrayList()

        @Volatile var noteWhenKexIsOwed = false

        @Volatile var quotedWhenKexWasOwed: Boolean? = null

        fun quoted(peer: String): Boolean = peer in quoted

        fun seed(peer: String) {
            quoted += peer
        }

        private val prefs: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "isZbootQuoted" -> args!![0] in quoted
                    "setZbootQuoted" -> {
                        val on = args!![1] as Boolean
                        writes += (args[0] as String) to on
                        when {
                            on -> {
                                quoted += args[0] as String
                                true
                            }
                            clearsSave -> {
                                quoted -= args[0] as String
                                true
                            }
                            else -> false
                        }
                    }
                    "setKexOwed" -> {
                        if (noteWhenKexIsOwed && args!![1] as Boolean && quotedWhenKexWasOwed == null) {
                            quotedWhenKexWasOwed = args[0] in quoted
                        }
                        chat.prefs.setKexOwed(args!![0] as String, args[1] as Boolean)
                    }
                    "hasAcknowledgedMessageCost" -> true
                    "toString" -> "Zc590Disk"
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

        suspend fun viewModel(check: FirstMessagePlanApi.ScriptedCheck): ChatViewModel =
            chat.viewModel(check).also { PaymentHarness.setField(it, "zchatPreferences", prefs) }
    }
}
