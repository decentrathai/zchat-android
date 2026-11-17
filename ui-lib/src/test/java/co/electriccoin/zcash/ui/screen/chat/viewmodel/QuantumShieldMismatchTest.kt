package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * F11 (fix-all 2.13.0, train T3; qs-downgrade REVIEW breaks 5, 6 and 7, Grok G3 and G28), without the D3 claim.
 *
 * - G3: a shield that is on never compared a rescanned QR with the key it stored. When the other phone turned Extra
 *   Security off and set it up again, this phone rescanned the new QR and showed the old setup's code (or the
 *   "older ZChat" no-code text) as if all was well, while the two phones now held different keys and messages stopped
 *   opening. A rescan that matches neither key this phone could have agreed with its stored secret must say so,
 *   neutrally ("not this chat's code"; this phone cannot tell a third party's QR from a one-side reset), and must
 *   change nothing: the stored key, its bound record and our secret stay as they are.
 * - G28: a shield set up before the bound record existed wrote no record on a rescan, so it checked the key again at
 *   every rescan. A rescan whose BOUND key matches the stored one writes `true`; a 2.12.4-key match writes nothing
 *   (pinned by QuantumShieldConfirmationTest: "a repeat scan writes no record it cannot prove"), and a third party's QR
 *   is "not this chat's code", not the "older ZChat" text.
 * - Break 6: QuantumShieldAcrossVersionsTest finds "the code" by looking for 6 digits in the confirmation's text, so
 *   the confirmation may carry nothing else that holds digits: its fields are the code and the problem only.
 * - Controls: an unreadable rescan and a genuine rescan (padded or not) keep what they show today, with no problem.
 *
 * The problem is read by name through reflection, so this file compiles before the field exists. Only synthetic
 * secrets and public keys are used.
 */
class QuantumShieldMismatchTest {
    private companion object {
        const val PEER_OF_A = "usynthetic-qs-mismatch-peer-b"
        const val PEER_OF_B = "usynthetic-qs-mismatch-peer-a"
        const val NOT_THIS_CHATS_CODE = "NOT_THIS_CHATS_CODE"

        val PUB_A: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 3 + 1).toByte() })
        val PUB_B: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 2).toByte() })

        val SECRET_A = ByteArray(32) { it.toByte() }
        val SECRET_B = ByteArray(32) { (it + 100).toByte() }

        /** The secret the other phone makes when it sets Extra Security up again. */
        val SECRET_B_AGAIN = ByteArray(32) { (it * 7 + 11).toByte() }

        /** A secret of some other chat. */
        val SECRET_OTHER = ByteArray(32) { (it * 13 + 3).toByte() }

        const val BOUND_CODE_GOLDEN = "845693"

        fun paddedQr(secret: ByteArray): String = "ZCPSK:" + Base64.getEncoder().encodeToString(secret)

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }

    /** Map-backed ZchatPreferences for the Quantum Shield path; unexpected calls fail the test. */
    private class Prefs(
        ourPub: String,
        peerPub: String
    ) {
        val values = ConcurrentHashMap<String, String>()

        init {
            values["ourPub"] = ourPub
            values["peerPub"] = peerPub
        }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getE2EOurPublicKey" -> values["ourPub"]
                    "getE2EPeerPublicKey" -> values["peerPub"]
                    "getQuantumShieldPSK" -> values["psk"]
                    "setQuantumShieldPSK" -> {
                        values["psk"] = args!![1] as String
                        null
                    }
                    "clearQuantumShieldPSK" -> {
                        values.remove("psk")
                        values.remove("bound")
                        null
                    }
                    "getQuantumShieldBound" -> values["bound"]?.toBooleanStrict()
                    "setQuantumShieldBound" -> {
                        values["bound"] = (args!![1] as Boolean).toString()
                        null
                    }
                    "getQuantumShieldOurSecret" -> values["ourSecret"]
                    "setQuantumShieldOurSecret" -> {
                        values["ourSecret"] = args!![1] as String
                        null
                    }
                    "toString" -> "QuantumShieldMismatchPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call on the Quantum Shield path: ${method.name}",
                    )
                }
            } as ZchatPreferences
    }

    private class Phone(
        ourPub: String,
        peerPub: String,
        val peerAddress: String
    ) {
        val prefs = Prefs(ourPub, peerPub)
        val vm: ChatViewModel =
            allocateChatViewModel().also { vm ->
                setField(vm, "zchatPreferences", prefs.proxy)
                setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
                setField(vm, "_quantumShieldSas", MutableStateFlow<Any?>(null))
            }

        fun withOwnSecret(secret: ByteArray) = apply { prefs.values["ourSecret"] = QuantumShield.encodeSecret(secret) }

        fun initiate(): String = vm.initiateQuantumShield(peerAddress)

        fun complete(qr: String): Boolean = vm.completeQuantumShield(peerAddress, qr)

        fun dismiss() = vm.clearQuantumShieldSas()

        fun reset() = vm.resetQuantumShield(peerAddress)

        val pskB64: String? get() = prefs.values["psk"]

        val confirmation: Any?
            get() = (getField(vm, "_quantumShieldSas") as MutableStateFlow<*>).value

        val code: String?
            get() = confirmation?.let { Regex("\\d{6}").find(it.toString())?.value }

        /** The confirmation's problem by name, or null (also while the confirmation has no such field). */
        val problem: String?
            get() =
                confirmation?.let { c ->
                    runCatching {
                        c.javaClass
                            .getDeclaredField("problem")
                            .apply { isAccessible = true }
                            .get(c)
                            ?.toString()
                    }.getOrNull()
                }

        fun snapshot(): Map<String, String> = HashMap(prefs.values)

        private companion object {
            fun allocateChatViewModel(): ChatViewModel {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
                return allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
            }

            fun setField(vm: ChatViewModel, name: String, value: Any?) {
                ChatViewModel::class.java
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .set(vm, value)
            }

            fun getField(vm: ChatViewModel, name: String): Any? =
                ChatViewModel::class.java
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(vm)
        }
    }

    private fun newPhoneA() = Phone(ourPub = PUB_A, peerPub = PUB_B, peerAddress = PEER_OF_A).withOwnSecret(SECRET_A)

    private fun newPhoneB() = Phone(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B).withOwnSecret(SECRET_B)

    /** Asserts the rescan was answered with the neutral problem and wrote nothing. */
    private fun assertNotThisChatsCode(phone: Phone, qr: String, why: String) {
        val before = phone.snapshot()
        phone.dismiss()
        assertFalse("$why: a QR that matches neither key is not a completed setup", phone.complete(qr))
        assertNotNull("$why: the phone must say something, not stay silent", phone.confirmation)
        assertEquals("$why: the neutral problem, got ${phone.confirmation}", NOT_THIS_CHATS_CODE, phone.problem)
        assertNull("$why: no code may be shown for a key this phone does not hold", phone.code)
        assertEquals("$why: nothing is stored (key, bound record, our secret)", before, phone.snapshot())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // G3: a one-side reset is shown, never hidden behind the old setup's confirmation.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun a_one_side_reset_is_shown_as_not_this_chats_code_and_changes_nothing() {
        val a = newPhoneA()
        val b = newPhoneB()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertTrue(b.complete(QuantumShield.toQRPayload(SECRET_A)))
        assertEquals(BOUND_CODE_GOLDEN, a.code)
        val pskA = a.pskB64

        // B turns Extra Security off and sets it up again; A still shows its (reused) QR, B scans it.
        b.reset()
        val bAgain = b.initiate()
        assertTrue(b.complete(a.initiate()))
        assertNotEquals("the two phones now hold different keys (G3)", pskA, b.pskB64)
        assertEquals("A's key did not move", pskA, a.pskB64)

        assertNotThisChatsCode(a, bAgain, "A rescans B's new QR")
    }

    @Test
    fun a_one_side_reset_of_a_2_12_4_setup_is_shown_too() {
        // A set up with a 2.12.4 phone (padded QR): the 2.12.4 key and no code. That phone, now updated, set up again.
        val a = newPhoneA()
        assertTrue(a.complete(paddedQr(SECRET_B)))
        assertNull(a.code)
        assertEquals("false", a.prefs.values["bound"])

        assertNotThisChatsCode(a, QuantumShield.toQRPayload(SECRET_B_AGAIN), "unpadded QR of the new setup")
        assertNotThisChatsCode(a, paddedQr(SECRET_B_AGAIN), "padded QR of the new setup")
    }

    @Test
    fun a_qr_of_another_chat_is_not_this_chats_code() {
        val a = newPhoneA()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertNotThisChatsCode(a, QuantumShield.toQRPayload(SECRET_OTHER), "another chat's current QR")
        assertNotThisChatsCode(a, paddedQr(SECRET_OTHER), "another chat's 2.12.4 QR")
        assertNotThisChatsCode(a, QuantumShield.toQRPayload(SECRET_A), "this phone's own QR")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // G28: a shield set up before the bound record existed.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun a_genuine_rescan_of_a_bound_shield_without_the_record_shows_the_code_and_records_true() {
        for (qr in listOf(QuantumShield.toQRPayload(SECRET_B), paddedQr(SECRET_B))) {
            val a = newPhoneA()
            a.prefs.values["psk"] = b64(QuantumShield.derivePSK(SECRET_A, SECRET_B, PUB_A, PUB_B))
            val psk = a.pskB64
            assertTrue(a.complete(qr))
            assertEquals("the stored key is the bound one: its code ($qr)", BOUND_CODE_GOLDEN, a.code)
            assertNull(a.problem)
            assertEquals("a bound match proves the record: write true ($qr)", "true", a.prefs.values["bound"])
            assertEquals("the key itself never changes on a rescan", psk, a.pskB64)
        }
    }

    @Test
    fun a_third_party_qr_on_a_shield_without_the_record_is_not_this_chats_code_and_records_nothing() {
        val bound = newPhoneA()
        bound.prefs.values["psk"] = b64(QuantumShield.derivePSK(SECRET_A, SECRET_B, PUB_A, PUB_B))
        assertNotThisChatsCode(bound, QuantumShield.toQRPayload(SECRET_OTHER), "bound key, third-party QR")
        assertFalse(bound.prefs.values.containsKey("bound"))

        val unbound = newPhoneA()
        unbound.prefs.values["psk"] = b64(QuantumShield.derivePSK(SECRET_A, SECRET_B))
        assertNotThisChatsCode(unbound, paddedQr(SECRET_OTHER), "2.12.4 key, third-party QR")
        assertFalse(unbound.prefs.values.containsKey("bound"))
    }

    @Test
    fun a_genuine_rescan_of_a_2_12_4_key_without_the_record_shows_no_code_and_records_nothing() {
        val a = newPhoneA()
        a.prefs.values["psk"] = b64(QuantumShield.derivePSK(SECRET_A, SECRET_B))
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertNotNull(a.confirmation)
        assertNull(a.code)
        assertNull("a genuine rescan is no problem", a.problem)
        assertFalse("an unbound match writes no record (pinned elsewhere)", a.prefs.values.containsKey("bound"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Controls: what is shown today for readable, genuine and unreadable rescans stays.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun a_genuine_rescan_names_no_problem() {
        val a = newPhoneA()
        val b = newPhoneB()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertTrue(b.complete(QuantumShield.toQRPayload(SECRET_A)))
        for (qr in listOf(QuantumShield.toQRPayload(SECRET_B), paddedQr(SECRET_B))) {
            a.dismiss()
            assertTrue(a.complete(qr))
            assertNull(a.problem)
            assertEquals(BOUND_CODE_GOLDEN, a.code)
        }

        val withOld = newPhoneA()
        assertTrue(withOld.complete(paddedQr(SECRET_B)))
        for (qr in listOf(paddedQr(SECRET_B), QuantumShield.toQRPayload(SECRET_B))) {
            withOld.dismiss()
            assertTrue(withOld.complete(qr))
            assertNull(withOld.problem)
            assertNull(withOld.code)
        }
    }

    @Test
    fun an_unreadable_rescan_keeps_the_setup_confirmation_and_names_no_problem() {
        val a = newPhoneA()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        a.dismiss()
        assertTrue(a.complete("ZCPSK:%%%"))
        assertEquals("nothing to compare: the setup's code, as today", BOUND_CODE_GOLDEN, a.code)
        assertNull(a.problem)
    }

    @Test
    fun a_new_setup_is_unchanged_and_names_no_problem() {
        val a = newPhoneA()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertNull(a.problem)
        assertEquals("true", a.prefs.values["bound"])
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Break 6: the confirmation carries the code and the problem, nothing else.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun the_confirmation_holds_only_the_code_and_the_problem() {
        val cls = Class.forName("co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldConfirmation")
        val fields =
            cls.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSet()
        assertEquals(
            "no other field: the 6-digit code is found in the confirmation's text (QuantumShieldAcrossVersionsTest)",
            setOf("code", "problem"),
            fields,
        )
    }
}
