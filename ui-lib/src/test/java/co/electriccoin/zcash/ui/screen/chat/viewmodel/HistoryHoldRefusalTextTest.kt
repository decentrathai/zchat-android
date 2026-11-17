package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Round 8 D8 — what a send held by the history settlement tells the user, and when the hold ends.
 *
 * F31 (smaller issue 5): the disappearing-timer and conversation-mode controls reuse RestoreKexPolicy.
 * STILL_READING_HISTORY ("…This finishes on its own; tap Retry then.") when the settlement holds them. A control is
 * not a message row: it shows only as a SendMessageState.Error toast, and there is no Retry button to tap. Wanted: a
 * control refusal never points to Retry (in English or Spanish). The timer can be set again once the reading
 * finishes; re-picking the mode that is already selected sends nothing, so the mode refusal must not tell the user to
 * set it again either.
 *
 * F32: settleLegacyFloorJumps drops a contact from peersAwaitingLegacySettlement only at the end of its loop body.
 * Its three early `continue`s (no conversation id, no keys, no processor) skip that, so after Reset encryption wipes
 * the contact's keys the contact stays marked for good, and every later refusal on the paths that ask
 * sendRefusedReason says "ZCHAT is still reading this chat's history" instead of their own reason. Wanted: a contact
 * the settlement has nothing left to settle for is no longer marked, so the refusal is the path's own ("otherwise")
 * text.
 *
 * All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryHoldRefusalTextTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ── F31: the timer and mode controls ─────────────────────────────────────────────────────────────

    /**
     * A Shielded chat whose ratchet the settlement holds: the contact is marked as waiting for it, and no processor
     * can be built for the send (the peer key is not readable), so the control is refused before anything is paid.
     */
    private suspend fun heldControlChat(): Pair<ChatViewModel, PaidChatFixture> {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT)
        val vm = fixture.viewModel()
        AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
        AutoSpendVm.setField(vm, "zchatPreferences", overriding(fixture.proxy, "getE2EPeerPublicKey" to null))
        markAwaitingSettlement(vm, PEER)
        return vm to fixture
    }

    private fun spanishOf(text: String): String? =
        ChatSendCopy.localizedResource(text)?.let { id ->
            ChatResourceText.stringName(id)?.let { ChatResourceText.strings("values-es")[it] }
        }

    @Test
    fun `a timer the history settlement holds is never told to tap Retry`() =
        runBlocking {
            val (vm, fixture) = heldControlChat()

            vm.setDisappearingTtl(PEER, 3_600L, costConfirmed = true)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("nothing may be paid while the settlement holds the ratchet", 0, fixture.seam.submits.get())
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("the timer refusal is shown: $state", state is SendMessageState.Error)
            val text = (state as SendMessageState.Error).message
            assertFalse("a timer toast has no Retry button, but it says to tap Retry: $text", text.contains("Retry"))
            val spanish = spanishOf(text)
            assertTrue("the refusal needs its Spanish text: $text", spanish != null)
            assertFalse("the Spanish toast points to Reintentar: $spanish", spanish!!.contains("Reintentar"))
            assertEquals(CONTROL_STILL_READING_HISTORY, text)
        }

    @Test
    fun `a mode change the history settlement holds is never told to tap Retry or to set it again`() =
        runBlocking {
            val (vm, fixture) = heldControlChat()

            vm.changeConversationMode(PEER, ConversationMode.TUNNEL, costConfirmed = true)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("nothing may be paid while the settlement holds the ratchet", 0, fixture.seam.submits.get())
            assertEquals("fixture: the mode changed on this device", ConversationMode.TUNNEL, fixture.mode)
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("the mode refusal is shown: $state", state is SendMessageState.Error)
            val text = (state as SendMessageState.Error).message
            assertFalse("a mode toast has no Retry button, but it says to tap Retry: $text", text.contains("Retry"))
            assertFalse(
                "re-picking the mode that is already selected sends nothing, so \"set it again\" cannot work: $text",
                text.contains("set it again", ignoreCase = true),
            )
            val spanish = spanishOf(text)
            assertTrue("the refusal needs its Spanish text: $text", spanish != null)
            assertFalse("the Spanish toast points to Reintentar: $spanish", spanish!!.contains("Reintentar"))
            assertTrue("the user is told the history is being read: $text", text.contains("history"))
            assertTrue("the user is told nothing was paid: $text", text.contains("nothing was paid"))
        }

    // ── F32: the settlement forgets a contact it has nothing left to settle for ──────────────────────

    @Test
    fun `after Reset encryption wiped the keys, a settlement pass stops holding the contact`() =
        runBlocking {
            // Reset encryption wiped both keys; the conversation id stays.
            val fixture = PaidChatFixture(PEER, ConversationMode.VAULT, haveOurKey = false)
            val vm = fixture.viewModel()
            AutoSpendVm.setField(vm, "zchatPreferences", overriding(fixture.proxy, "getConversationId" to CONV_ID))
            markAwaitingSettlement(vm, PEER)
            assertEquals(
                "fixture: a marked contact gets the history text",
                RestoreKexPolicy.STILL_READING_HISTORY,
                refusedReason(vm, PEER, OTHERWISE),
            )

            settlementPass(vm, setOf(PEER))

            assertEquals(
                "the contact has no keys left to settle, but every later refusal still says the history is being read",
                OTHERWISE,
                refusedReason(vm, PEER, OTHERWISE),
            )
        }

    @Test
    fun `a contact with no conversation id is not held by the settlement after its pass`() =
        runBlocking {
            val fixture = PaidChatFixture(PEER, ConversationMode.VAULT)
            val vm = fixture.viewModel()
            // No conversation id recorded for the contact (getConversationId is not answered: null).
            markAwaitingSettlement(vm, PEER)

            settlementPass(vm, setOf(PEER))

            assertEquals(
                "a contact with nothing to settle stays marked and every refusal names the history",
                OTHERWISE,
                refusedReason(vm, PEER, OTHERWISE),
            )
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private fun markAwaitingSettlement(
        vm: ChatViewModel,
        peer: String,
    ) {
        ChatViewModel::class.java
            .getDeclaredField("peersAwaitingLegacySettlement")
            .apply { isAccessible = true }
            .set(vm, setOf(peer))
    }

    private fun refusedReason(
        vm: ChatViewModel,
        peer: String,
        otherwise: String,
    ): String =
        ChatViewModel::class.java
            .getDeclaredMethod("sendRefusedReason", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, peer, otherwise) as String

    /** One pass-end settlement for [peers], with no history read yet (nothing can be settled or adopted). */
    private suspend fun settlementPass(
        vm: ChatViewModel,
        peers: Set<String>,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "settleLegacyFloorJumps" }
                .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                method.invoke(
                    vm,
                    peers,
                    false,
                    false,
                    emptyMap<String, List<Any>>(),
                    emptyMap<String, List<Any>>(),
                    emptyList<Any>(),
                    emptySet<String>(),
                    emptySet<String>(),
                    0,
                    continuation,
                )
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /** [base] with the calls named in [overrides] answered by their fixed value instead (null included). */
    private fun overriding(
        base: ZchatPreferences,
        vararg overrides: Pair<String, Any?>,
    ): ZchatPreferences {
        val answers = overrides.toMap()
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
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
        const val PEER = "usyntheticd8historyholdpeer001"
        const val CONV_ID = PaidChatFixture.CONV_ID
        const val OTHERWISE = "synthetic: this path's own refusal"

        /** The new ChatSendCopy.CONTROL_STILL_READING_HISTORY (the parity test pins the constant to it). */
        const val CONTROL_STILL_READING_HISTORY =
            "ZCHAT is still reading this chat's history from the blockchain. Nothing was sent and nothing was paid. " +
                "Once it finishes, set it again."
    }
}
