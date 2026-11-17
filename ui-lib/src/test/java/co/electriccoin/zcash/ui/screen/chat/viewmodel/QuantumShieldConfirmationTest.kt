package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * C7 (R6-4 review findings 8, 12 and the unit part of 11) — what the Quantum Shield confirmation says must follow
 * the key this phone STORED at setup, never the QR that happens to be scanned later.
 *
 * - When the shield is already on, a second scan does not change the key (completeQuantumShield keeps the stored
 *   one), so it must not change the confirmation either: a bound shield keeps its code when a padded (2.12.4-looking)
 *   QR is rescanned, and a shield set up with a 2.12.4 phone shows no code when a garbled `ZCPSK:` text is rescanned.
 *   Which key was stored is recorded at setup next to the key, and written before it, so a shield that is on always
 *   has that record.
 * - A shield set up before that record existed shows a code only when the stored key is provably the bound key of
 *   our secret and the rescanned QR; an unreadable rescan shows no code (never "bound" by default).
 * - The record is removed with the key (shield off, per-peer reset) and lives in the encrypted E2E store that
 *   clearAll wipes.
 * - An emptied secret (what a reset leaves) is refused instead of deriving a key from zero bytes.
 * - The dialog text no longer claims "Both phones use the same key": this phone cannot know what the other one
 *   stored (it may not have scanned yet, or one QR may have been changed on the way).
 * - Round 8 D11 (Grok CLI review, G29): nor "Once both phones have scanned each other's code, they use the same
 *   key". Two phones that have both scanned can still hold different keys (one side reset and set up again, or a QR
 *   changed on the way), and a setup with no code has nothing that would tell. The text says this phone cannot check it.
 * - Compatibility (R6-5) is unchanged: the key bytes are pinned to goldens computed independently (RFC 5869
 *   HKDF-SHA256 in Python, round7c-20260923/C7/golden.py) for both the 2.12.4 key and the bound key.
 *
 * Only synthetic secrets and public keys are used.
 */
class QuantumShieldConfirmationTest {
    private companion object {
        const val PEER_OF_A = "usynthetic-qs-confirm-peer-b"
        const val PEER_OF_B = "usynthetic-qs-confirm-peer-a"

        val PUB_A: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 3 + 1).toByte() })
        val PUB_B: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 2).toByte() })

        val SECRET_A = ByteArray(32) { it.toByte() }
        val SECRET_B = ByteArray(32) { (it + 100).toByte() }

        // Independent goldens (golden.py): HKDF-SHA256, null salt, info = "zchat-quantum-shield-psk" [+ bindings].
        const val UNBOUND_GOLDEN = "af14c2e1bac457da14f581c41be17f1686aa0049b10463b3715c8a6c910eff88"
        const val BOUND_GOLDEN = "01e36d2f93263d86e83a9d96ffd9787f3f59fdf8c4c0fe6f93cc375f9d09825a"
        const val BOUND_CODE_GOLDEN = "845693"

        fun hex(b64: String?): String? = b64?.let { Base64.getDecoder().decode(it).joinToString("") { b -> "%02x".format(b) } }

        /** The QR a 2.12.4 phone shows: padded base64. */
        fun paddedQr(secret: ByteArray): String = "ZCPSK:" + Base64.getEncoder().encodeToString(secret)
    }

    /** Map-backed ZchatPreferences for the Quantum Shield path; unexpected calls fail the test. */
    private class Prefs(
        ourPub: String,
        peerPub: String
    ) {
        val values = ConcurrentHashMap<String, String>()

        /** Whether the bound record existed at the moment each PSK was written. */
        val boundPresentAtPskWrite = mutableListOf<Boolean>()

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
                        boundPresentAtPskWrite += values.containsKey("bound")
                        values["psk"] = args!![1] as String
                        null
                    }
                    // Contract of the real store: the key and its bound record are removed together.
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
                    "toString" -> "QuantumShieldConfirmationPrefs"
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

        /** A 2.13.0 phone that made [secret] (stored unpadded, as initiateQuantumShield stores it). */
        fun withOwnSecret(secret: ByteArray) = apply { prefs.values["ourSecret"] = QuantumShield.encodeSecret(secret) }

        fun complete(qr: String): Boolean = vm.completeQuantumShield(peerAddress, qr)

        fun dismiss() = vm.clearQuantumShieldSas()

        fun reset() = vm.resetQuantumShield(peerAddress)

        val pskB64: String? get() = prefs.values["psk"]

        /** The confirmation state (null = no dialog). */
        val confirmation: Any?
            get() = (getField(vm, "_quantumShieldSas") as MutableStateFlow<*>).value

        /** The 6-digit code the dialog asks to compare, or null when it shows the "no code" text. */
        val code: String?
            get() = confirmation?.let { Regex("\\d{6}").find(it.toString())?.value }

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

    // ---------------------------------------------------------------------------------------------------------------
    // (i) ACTIVE bound shield: a rescanned padded QR does not hide the code.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun an_active_bound_shield_keeps_its_code_when_a_padded_qr_is_rescanned() {
        val a = newPhoneA()
        val b = newPhoneB()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertTrue(b.complete(QuantumShield.toQRPayload(SECRET_A)))
        val setupCode = a.code
        assertEquals("two current phones compare a code at setup", BOUND_CODE_GOLDEN, setupCode)
        assertEquals(setupCode, b.code)
        val pskAtSetup = a.pskB64
        a.dismiss()

        // The same peer's secret, but padded as a 2.12.4 QR would be: the key stays the bound one, so must the code.
        assertTrue(a.complete(paddedQr(SECRET_B)))
        assertEquals("a repeat scan never changes the stored key", pskAtSetup, a.pskB64)
        assertNotNull("a repeat scan re-shows the confirmation", a.confirmation)
        assertEquals(
            "the stored key is the bound one, so the rescan must show the setup code, not the no-code text",
            setupCode,
            a.code,
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // (ii) ACTIVE unbound shield (2.12.4 peer): a garbled rescan does not invent a code (finding 12).
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun an_active_shield_with_a_2_12_4_phone_shows_no_code_when_a_garbled_qr_is_rescanned() {
        val a = newPhoneA()
        assertTrue("a 2.13.0 phone accepts a 2.12.4 QR", a.complete(paddedQr(SECRET_B)))
        assertNull("a 2.12.4 pairing has no code at setup", a.code)
        a.dismiss()

        assertTrue(a.complete("ZCPSK:%%%"))
        assertNotNull("a repeat scan re-shows the confirmation", a.confirmation)
        assertNull(
            "the stored key is the 2.12.4 key; the 2.12.4 phone cannot show a code, so none may be shown",
            a.code,
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // (iii) A shield set up before the bound record existed.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun a_shield_set_up_before_the_record_shows_no_code_for_an_unreadable_rescan() {
        val a = newPhoneA()
        a.prefs.values["psk"] = Base64.getEncoder().encodeToString(QuantumShield.derivePSK(SECRET_A, SECRET_B, PUB_A, PUB_B))
        assertFalse(a.prefs.values.containsKey("bound"))

        assertTrue(a.complete("ZCPSK:%%%"))
        assertNotNull(a.confirmation)
        assertNull("without the record and without a readable QR there is nothing to base a code on", a.code)
    }

    @Test
    fun a_shield_set_up_before_the_record_shows_the_code_only_when_the_stored_key_is_the_bound_one() {
        // Bound key stored by an earlier build; the rescanned QR is the peer's genuine secret, padded or not.
        val bound = newPhoneA()
        bound.prefs.values["psk"] = Base64.getEncoder().encodeToString(QuantumShield.derivePSK(SECRET_A, SECRET_B, PUB_A, PUB_B))
        assertTrue(bound.complete(paddedQr(SECRET_B)))
        assertEquals(
            "the stored key is the bound key of our secret and this QR, whatever its padding",
            BOUND_CODE_GOLDEN,
            bound.code,
        )

        // 2.12.4 key stored (the other phone is 2.12.4): no code, even though our own secret is unpadded.
        val unbound = newPhoneA()
        unbound.prefs.values["psk"] = Base64.getEncoder().encodeToString(QuantumShield.derivePSK(SECRET_A, SECRET_B))
        assertTrue(unbound.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertNotNull(unbound.confirmation)
        assertNull("the stored key is the 2.12.4 key, which has no code on the other phone", unbound.code)
        assertFalse("a repeat scan writes no record it cannot prove", unbound.prefs.values.containsKey("bound"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The record is written with (before) the key and removed with it.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun setup_records_which_key_it_stored_before_the_key_itself() {
        val a = newPhoneA()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertEquals("true", a.prefs.values["bound"])
        assertEquals("the record must exist when the key is written", listOf(true), a.prefs.boundPresentAtPskWrite)

        val withOld = newPhoneA()
        assertTrue(withOld.complete(paddedQr(SECRET_B)))
        assertEquals("false", withOld.prefs.values["bound"])
        assertEquals(listOf(true), withOld.prefs.boundPresentAtPskWrite)
    }

    @Test
    fun turning_the_shield_off_removes_the_record_with_the_key() {
        val a = newPhoneA()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertTrue("setup stores the record", a.prefs.values.containsKey("bound"))
        a.reset()
        assertNull(a.pskB64)
        assertFalse("shield off must remove the record with the key", a.prefs.values.containsKey("bound"))
    }

    @Test
    fun the_store_keeps_the_record_in_the_encrypted_e2e_store_and_clears_it_with_the_key() {
        val src = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt").readText()
        assertTrue("ZchatPreferences declares getQuantumShieldBound", "fun getQuantumShieldBound(peerAddress: String): Boolean?" in src)
        assertTrue(
            "ZchatPreferences declares setQuantumShieldBound",
            "fun setQuantumShieldBound(peerAddress: String, bound: Boolean)" in src,
        )
        val setBody = overrideBody(src, "setQuantumShieldBound")
        assertTrue("the record lives in the encrypted E2E store (wiped by clearAll): $setBody", "e2ePrefs.edit()" in setBody)
        assertTrue("the record is written synchronously, like the key: $setBody", ".commit()" in setBody)
        val clearBody = overrideBody(src, "clearQuantumShieldPSK")
        assertTrue("clearing the key clears its record in the same edit: $clearBody", "qs_bound_" in clearBody && "qs_psk_" in clearBody)
        assertEquals("one edit, one commit: $clearBody", 1, Regex("\\.commit\\(\\)").findAll(clearBody).count())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // (iv) An emptied secret is refused (unit part of finding 11).
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun an_emptied_secret_is_refused_and_stores_nothing() {
        val a = Phone(ourPub = PUB_A, peerPub = PUB_B, peerAddress = PEER_OF_A)
        a.prefs.values["ourSecret"] = ""
        assertFalse("no key may be derived from an empty secret", a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertNull(a.pskB64)
        assertFalse(a.prefs.values.containsKey("bound"))
        assertNull("no confirmation for a refused scan", a.confirmation)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Compatibility preserved: the stored key bytes are the independent goldens.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun the_stored_keys_are_the_golden_bytes() {
        val withOld = newPhoneA()
        assertTrue(withOld.complete(paddedQr(SECRET_B)))
        assertEquals("the key agreed with a 2.12.4 phone is unchanged", UNBOUND_GOLDEN, hex(withOld.pskB64))

        val a = newPhoneA()
        val b = newPhoneB()
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
        assertTrue(b.complete(QuantumShield.toQRPayload(SECRET_A)))
        assertEquals("the bound key of two current phones is unchanged", BOUND_GOLDEN, hex(a.pskB64))
        assertEquals(BOUND_GOLDEN, hex(b.pskB64))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // (v) The dialog text does not promise what the phone cannot know.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun the_no_code_text_does_not_claim_both_phones_use_the_same_key() {
        val en = noCodeBody("values")
        val es = noCodeBody("values-es")
        assertFalse("EN must not claim what this phone cannot know: $en", "Both phones use the same key" in en)
        assertFalse("ES must not claim what this phone cannot know: $es", "Ambos teléfonos usan la misma clave" in es)
        // D11 (G29): scanning each other's code does not prove the keys match either.
        assertFalse("EN must not say the keys match once both have scanned: $en", "they use the same key" in en)
        assertFalse("ES must not say the keys match once both have scanned: $es", "usarán la misma clave" in es)
        assertTrue(
            "EN says this phone cannot check the key and what to do if messages stop opening: $en",
            "This phone cannot check whether both phones ended up with the same key." in en &&
                "If messages stop opening, turn Extra Security off on both phones and set it up again." in en,
        )
        assertTrue(
            "ES says the same: $es",
            "Este teléfono no puede comprobar si los dos teléfonos tienen la misma clave." in es &&
                "Si los mensajes dejan de abrirse, desactiva la seguridad extra en ambos y vuelve a configurarla." in es,
        )
    }

    @Test
    fun the_quantum_shield_texts_have_the_same_placeholders_in_both_languages_and_none() {
        val en = strings("values")
        val es = strings("values-es")
        assertEquals(en.keys, es.keys)
        val placeholder = Regex("%(\\d+\\$)?[sdf]")
        for ((name, value) in en) {
            val enSet = placeholder.findAll(value).map { it.value }.toSet()
            val esSet = placeholder.findAll(es.getValue(name)).map { it.value }.toSet()
            assertEquals("placeholders of $name", enSet, esSet)
            assertTrue("$name has no placeholders", enSet.isEmpty())
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        return dir ?: error("repo root not found above the test working dir")
    }

    private fun strings(folder: String): Map<String, String> {
        val xml = File(repoRoot(), "ui-lib/src/main/res/ui/common/$folder/quantum_shield_strings.xml").readText()
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun noCodeBody(folder: String): String =
        strings(folder)["quantum_shield_no_code_body"]?.replace("\\'", "'")
            ?: error("quantum_shield_no_code_body missing in $folder")

    /** The body of `override fun <name>(` up to the next `override fun`, or "" when absent. */
    private fun overrideBody(src: String, name: String): String {
        val start = src.indexOf("override fun $name(")
        if (start < 0) return ""
        val end = src.indexOf("override fun ", start + 1).let { if (it < 0) src.length else it }
        return src.substring(start, end)
    }
}
