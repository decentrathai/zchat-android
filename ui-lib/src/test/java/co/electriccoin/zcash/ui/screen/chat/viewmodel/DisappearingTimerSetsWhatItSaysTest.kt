package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 D10 (DECISIONS_NIGHT §5, p4-2b8456cab054 / M-CHT-07) — the disappearing-messages timer sets what its text says.
 *
 * - Open / Tunnel with no relay ack: nothing re-sends a timer, so the notice must say the timer is set on this phone only
 *   and must never promise "it'll sync when you're both online". Round 8 D8 (d1e1356ab) already replaced that text with
 *   ChatSendCopy.TIMER_NOT_DELIVERED; the first case pins it (it passes at the base).
 * - Shielded (Vault) with no ratchet: setDisappearingTtl wrote the timer and its "⏳ Messages now disappear…" note on this
 *   phone first, then refused with "Finish the encrypted key exchange first, then set the timer." The timer then stayed
 *   on for this phone only while the refusal said it was not set. Wanted: nothing is set locally, no note is written,
 *   nothing is paid, and the refusal is shown.
 * - Control: with a ratchet, the timer is set once and the one control message is paid.
 *
 * All addresses and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisappearingTimerSetsWhatItSaysTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a free-chat timer no relay took says this phone only and never promises a sync`() =
        runBlocking {
            for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.OPEN)) {
                val fixture = PaidChatFixture(PEER_FREE, mode)
                val vm = fixture.viewModel()

                vm.setDisappearingTtl(PEER_FREE, 3_600L)
                HandshakeHarness.awaitIdle(vm)

                val state = HandshakeHarness.sendMessageState(vm)
                assertTrue("$mode: the user is told the timer did not reach the contact: $state", state is SendMessageState.Error)
                val text = (state as SendMessageState.Error).message
                assertFalse("$mode: nothing re-sends a timer, but the text promises a sync: $text", "it'll sync" in text)
                assertFalse("$mode: nothing re-sends a timer: $text", "sync automatically" in text)
                assertEquals(ChatSendCopy.TIMER_NOT_DELIVERED, text)
                assertTrue("$mode: the text says where the timer is set: $text", "on this device only" in text)
                assertTrue("$mode: the text says what to do: $text", "Set it again" in text)
                val spanish = spanishOf(text)
                assertTrue("$mode: the notice needs its Spanish text", spanish != null)
                assertFalse("$mode: the Spanish text promises a sync: $spanish", "se sincronizará" in spanish!!)
                assertEquals("$mode: a free chat pays nothing", 0, fixture.seam.proposalRequests.get())
            }
        }

    @Test
    fun `a Shielded timer with no ratchet sets nothing on this phone and shows the refusal`() =
        runBlocking {
            val fixture = PaidChatFixture(PEER_NO_RATCHET, ConversationMode.VAULT)
            val vm = fixture.viewModel()
            val writes = Collections.synchronizedList(mutableListOf<String>())
            // No ratchet: no cached processor, and none can be built (the peer's key is not there).
            AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
            AutoSpendVm.setField(vm, "zchatPreferences", recording(fixture.proxy, writes, "getE2EPeerPublicKey" to null))

            vm.setDisappearingTtl(PEER_NO_RATCHET, 3_600L, costConfirmed = true)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("nothing may be paid without a ratchet", 0, fixture.seam.proposalRequests.get())
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("the refusal is shown: $state", state is SendMessageState.Error)
            assertEquals(ChatSendCopy.TIMER_NEEDS_KEY_EXCHANGE, (state as SendMessageState.Error).message)
            assertEquals(
                "the refusal says the timer was not set, but it was set on this phone (it then stays on for this phone only)",
                emptyList<String>(),
                writes.filter { it == "setDisappearingTtl" },
            )
            assertEquals(
                "no \"Messages now disappear…\" note may be written for a timer that was not set",
                emptyList<String>(),
                writes.filter { it == "addPendingMessage" || it == "commitPendingMessage" },
            )
        }

    /** Control (passes before and after): with a ratchet the timer is set once and its one control message is paid. */
    @Test
    fun `control - a Shielded timer with a ratchet is set and its control message is paid`() =
        runBlocking {
            val fixture = PaidChatFixture(PEER_RATCHET, ConversationMode.VAULT)
            val vm = fixture.viewModel()
            val writes = Collections.synchronizedList(mutableListOf<String>())
            AutoSpendVm.setField(vm, "zchatPreferences", recording(fixture.proxy, writes))

            vm.setDisappearingTtl(PEER_RATCHET, 3_600L, costConfirmed = true)
            HandshakeHarness.awaitIdle(vm)

            assertEquals(1, writes.count { it == "setDisappearingTtl" })
            assertEquals("the one control message is paid", 1, fixture.seam.proposalRequests.get())
        }

    private fun spanishOf(text: String): String? =
        ChatSendCopy.localizedResource(text)?.let { id ->
            ChatResourceText.stringName(id)?.let { ChatResourceText.strings("values-es")[it] }
        }

    /** [base], recording every call's name in [calls]; the calls named in [overrides] answer their fixed value. */
    private fun recording(
        base: ZchatPreferences,
        calls: MutableList<String>,
        vararg overrides: Pair<String, Any?>,
    ): ZchatPreferences {
        val answers = overrides.toMap()
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            calls += method.name
            if (answers.containsKey(method.name)) {
                answers[method.name]
            } else {
                try {
                    method.invoke(base, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences
    }

    private companion object {
        const val PEER_FREE = "usyntheticd10timerfreepeer01"
        const val PEER_NO_RATCHET = "usyntheticd10timernoratchet1"
        const val PEER_RATCHET = "usyntheticd10timerratchet01"
    }
}
