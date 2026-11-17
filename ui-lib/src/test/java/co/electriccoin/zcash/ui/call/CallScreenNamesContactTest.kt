package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Round 9 R9-6 (REVIEW_R8 F13 = device D-F13) — the call screen shows a key, not the contact, and only in English.
 *
 * At 8ecb482aa CallOverlay writes "Calling… ${peerPubkeyHex.take(12)}…" (device: "Calling… 93c9d47100f8…"), the
 * ringing screen shows 24 hex characters of the caller's key under a hard-coded "Incoming video call" /
 * "Incoming voice call", and "Connecting…" / "On call with" are hard-coded English with the key too. The service
 * already maps a key to the contact's name for the incoming-call notification (callerDisplayName).
 *
 * Wanted, in CallOverlay.kt (reached by name, so this file compiles before they exist):
 *  - callPeerLabel(prefs, pubkeyHex): the contact's name (findPeerByNostrPubkey → getDisplayName) for a mapped key,
 *    otherwise a short key (8 hex characters and "…"), never 12 or more;
 *  - callScreenText(state, peerLabel): the resource id and arguments of each line the overlay shows: call_dialling
 *    ("Calling %1$s…"), call_incoming_voice / call_incoming_video with the caller's label under it, call_connecting
 *    and call_in_call with the label;
 *  - EN and ES texts for every one of those strings;
 *  - no hard-coded English line or raw key left in CallOverlay.
 * All keys and names are synthetic.
 */
class CallScreenNamesContactTest {
    private companion object {
        const val PEER_KEY = "93c9d47100f8aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233"
        const val STRANGER_KEY = "5e6f7a8b9c0d1e2f3a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9012"
        const val PEER = "usyntheticr96callpeer000000001"
        const val NAME = "Synthetic Alice"
        const val CALL_ID = "0123456789abcdef0123456789abcdef"

        val EXPECTED_EN =
            mapOf(
                "call_dialling" to "Calling %1\$s…",
                "call_incoming_voice" to "Incoming voice call",
                "call_incoming_video" to "Incoming video call",
            )
        val EXPECTED_ES =
            mapOf(
                "call_dialling" to "Llamando a %1\$s…",
                "call_incoming_voice" to "Llamada de voz entrante",
                "call_incoming_video" to "Videollamada entrante",
            )
    }

    @Test
    fun `dialling a contact shows the contact's name, not a key`() {
        val lines = english(VoiceCallManager.CallState.Dialling(PEER_KEY, CALL_ID, isVideo = false))

        assertEquals(listOf("Calling $NAME…"), lines)
        assertNoKey(lines)
    }

    @Test
    fun `an incoming voice call says so and names the caller`() {
        val lines = english(VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID, isVideo = false))

        assertEquals(listOf("Incoming voice call", NAME), lines)
        assertNoKey(lines)
    }

    @Test
    fun `an incoming video call says so and names the caller`() {
        val lines = english(VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID, isVideo = true))

        assertEquals(listOf("Incoming video call", NAME), lines)
        assertNoKey(lines)
    }

    @Test
    fun `connecting and in-call name the contact too`() {
        val connecting = english(VoiceCallManager.CallState.Connecting(PEER_KEY, CALL_ID))
        val inCall = english(VoiceCallManager.CallState.InCall(PEER_KEY, CALL_ID))

        assertTrue("connecting does not name the contact: $connecting", connecting.any { NAME in it })
        assertTrue("in call does not name the contact: $inCall", inCall.any { NAME in it })
        assertNoKey(connecting + inCall)
    }

    @Test
    fun `an unknown caller shows a short key, never twelve hex characters`() {
        val label = peerLabel(STRANGER_KEY)

        assertEquals("${STRANGER_KEY.take(8)}…", label)
    }

    @Test
    fun `the call strings are English and Spanish resources`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for ((name, text) in EXPECTED_EN) assertEquals("English $name", text, en[name])
        for ((name, text) in EXPECTED_ES) assertEquals("Spanish $name", text, es[name])
        for (name in listOf("call_connecting", "call_in_call")) {
            assertTrue("English $name must take the contact's label", en[name]?.contains("%1\$s") == true)
            assertTrue("Spanish $name must take the contact's label", es[name]?.contains("%1\$s") == true)
        }
    }

    @Test
    fun `the overlay has no hard-coded English line and no raw key`() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallOverlay.kt")
        for (literal in listOf("\"Calling…", "\"Connecting…", "\"On call with", "\"Incoming video call\"", "\"Incoming voice call\"")) {
            assertFalse("CallOverlay still writes $literal", literal in src)
        }
        assertFalse("CallOverlay still shows a raw key", Regex("""peerPubkeyHex\.take\(""").containsMatchIn(src))
    }

    // ---------------------------------------------------------------- fixture

    private val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "findPeerByNostrPubkey" -> if (a[0] == PEER_KEY) PEER else null
                "getDisplayName" -> if (a[0] == PEER) NAME else "u1synthetic…"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "CallPrefs"
                else -> error("unexpected ZchatPreferences.${method.name}")
            }
        } as ZchatPreferences

    private fun overlayFunction(
        name: String,
        params: Int,
    ): Method {
        val cls = Class.forName("co.electriccoin.zcash.ui.call.CallOverlayKt")
        return cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == params }?.apply { isAccessible = true }
            ?: throw AssertionError("CallOverlay.kt has no $name: the call screen cannot name the contact")
    }

    private fun peerLabel(key: String): String = call(overlayFunction("callPeerLabel", 2), prefs, key) as String

    /** Every line the overlay shows for [state], in English, as getString(id, args) would give it. */
    private fun english(state: VoiceCallManager.CallState): List<String> {
        val label =
            peerLabel(
                when (state) {
                    is VoiceCallManager.CallState.Ringing -> state.peerPubkeyHex
                    is VoiceCallManager.CallState.Dialling -> state.peerPubkeyHex
                    is VoiceCallManager.CallState.Connecting -> state.peerPubkeyHex
                    is VoiceCallManager.CallState.InCall -> state.peerPubkeyHex
                    else -> error("no peer in $state")
                }
            )
        val lines = call(overlayFunction("callScreenText", 2), state, label) as List<*>
        return lines.map { line ->
            assertNotNull(line)
            val cls = line!!.javaClass
            val resId = cls.getMethod("getResId").invoke(line) as Int?
            val args = (cls.getMethod("getArgs").invoke(line) as List<*>).map { it as Any }
            val literal = cls.getMethod("getLiteral").invoke(line) as String?
            when {
                resId != null -> String.format(Locale.ROOT, ChatResourceText.english(resId), *args.toTypedArray())
                literal != null -> literal
                else -> throw AssertionError("a call screen line has neither a resource nor a label")
            }
        }
    }

    private fun call(
        method: Method,
        vararg args: Any,
    ): Any? =
        try {
            method.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun assertNoKey(lines: List<String>) {
        for (line in lines) {
            assertFalse("a line shows 12 hex characters of the key: $line", PEER_KEY.take(12) in line)
            assertFalse("a line shows 8 hex characters of the key: $line", PEER_KEY.take(8) in line)
        }
    }
}
