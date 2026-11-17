package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.HKDF
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * R6-5 — Quantum Shield between a 2.12.4 phone and a 2.13.0 phone must give both phones the SAME key.
 *
 * 2.12.4 (tag v2.12.4, commit a1fb4136f) derives the Quantum Shield key from the two exchanged secrets
 * alone: HKDF(sorted(secretA, secretB), info = "zchat-quantum-shield-psk"). 2.13.0 (B1l) mixes the two E2E
 * public keys into the HKDF info for every NEW setup. The QR text is the same ("ZCPSK:<base64>"), so a
 * 2.13.0 phone that pairs with a 2.12.4 phone stores a different key than its peer. That key is mixed into
 * the ratchet root (E2ERatchet.deriveRatchetRoot, unchanged since 2.12.4), so from then on neither phone
 * can read what the other sends: the chat becomes unreadable.
 *
 * The 2.12.4 side below is a verbatim copy of the 2.12.4 key-agreement code (QuantumShield.toQRPayload /
 * fromQRPayload / derivePSK and ChatViewModel.initiateQuantumShield / completeQuantumShield), pinned to
 * golden bytes computed independently (RFC 5869 HKDF-SHA256 in Python). 2.12.4 phones cannot be updated,
 * so nothing here may change what that side sends or accepts. The 2.13.0 side is the REAL ChatViewModel
 * (allocated without its constructor, the RouteBootPubkeyReuseTest idiom) over a map-backed preferences fake.
 *
 * A pairing whose key is not bound to the two identities has no security code the 2.12.4 phone could
 * show, so the 2.13.0 phone must not ask its user to compare a 6-digit code there: it must say plainly
 * that there is no code for this setup. The confirmation state is read through its backing field and its
 * text, so this test reads the same way before and after the confirmation carries more than a code.
 */
class QuantumShieldAcrossVersionsTest {
    private companion object {
        const val PEER_OF_A = "usynthetic-qs-peer-b-address"
        const val PEER_OF_B = "usynthetic-qs-peer-a-address"

        // Synthetic E2E public keys (base64 text, as ZchatPreferences stores them).
        val PUB_A: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 3 + 1).toByte() })
        val PUB_B: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 2).toByte() })

        val SIX_DIGITS = Regex("\\d{6}")
    }

    // ------------------------------------------------------------------------------------------------
    // 2.12.4 — verbatim copy of the shipped key agreement (a1fb4136f). Do not "fix" anything here.
    // ------------------------------------------------------------------------------------------------

    private object QuantumShield2124 {
        private const val QR_PREFIX = "ZCPSK:"
        private const val SECRET_LENGTH = 32
        private val INFO = "zchat-quantum-shield-psk".toByteArray()

        fun derivePSK(secretA: ByteArray, secretB: ByteArray): ByteArray {
            val (first, second) = orderSecrets(secretA, secretB)
            val ikm = first + second
            return HKDF.deriveKey(ikm = ikm, salt = null, info = INFO, length = SECRET_LENGTH)
        }

        fun toQRPayload(secret: ByteArray): String {
            val encoded = Base64.getEncoder().encodeToString(secret)
            return "$QR_PREFIX$encoded"
        }

        fun fromQRPayload(payload: String): ByteArray? {
            if (!payload.startsWith(QR_PREFIX)) return null
            val b64 = payload.removePrefix(QR_PREFIX)
            if (b64.isEmpty()) return null
            return try {
                val decoded = Base64.getDecoder().decode(b64)
                if (decoded.size == SECRET_LENGTH) decoded else null
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                null
            }
        }

        private fun orderSecrets(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
            val minLen = minOf(a.size, b.size)
            for (i in 0 until minLen) {
                val ua = a[i].toInt() and 0xFF
                val ub = b[i].toInt() and 0xFF
                if (ua < ub) return Pair(a, b)
                if (ua > ub) return Pair(b, a)
            }
            return if (a.size <= b.size) Pair(a, b) else Pair(b, a)
        }
    }

    /** A 2.12.4 phone: ChatViewModel.initiateQuantumShield / completeQuantumShield as shipped (one peer). */
    private class Phone2124 {
        var ourSecretB64: String? = null
        var pskB64: String? = null

        fun initiate(): String {
            val existing = ourSecretB64
            if (!existing.isNullOrEmpty()) {
                return QuantumShield2124.toQRPayload(Base64.getDecoder().decode(existing))
            }
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            ourSecretB64 = Base64.getEncoder().encodeToString(secret)
            return QuantumShield2124.toQRPayload(secret)
        }

        fun complete(qrPayload: String): Boolean {
            if (!pskB64.isNullOrEmpty()) return true
            val peerSecret = QuantumShield2124.fromQRPayload(qrPayload) ?: return false
            val ourSecret = Base64.getDecoder().decode(ourSecretB64 ?: return false)
            pskB64 = Base64.getEncoder().encodeToString(QuantumShield2124.derivePSK(ourSecret, peerSecret))
            return true
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 2.13.0 — the real ChatViewModel over a map-backed preferences fake.
    // ------------------------------------------------------------------------------------------------

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
                    // C7: which key the setup stored (bound or 2.12.4); the real store clears it with the key.
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
                    "toString" -> "QuantumShieldPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call on the Quantum Shield path: ${method.name}",
                    )
                }
            } as ZchatPreferences
    }

    private class Phone2130(
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

        fun initiate(): String = vm.initiateQuantumShield(peerAddress)

        fun complete(qr: String): Boolean = vm.completeQuantumShield(peerAddress, qr)

        fun dismiss() = vm.clearQuantumShieldSas()

        val pskB64: String? get() = prefs.values["psk"]

        /** What the confirmation dialog would show after the scan (null = no dialog). */
        val confirmation: Any?
            get() = (getField(vm, "_quantumShieldSas") as MutableStateFlow<*>).value

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

    private fun codeIn(confirmation: Any?): String? = confirmation?.let { SIX_DIGITS.find(it.toString())?.value }

    // ------------------------------------------------------------------------------------------------

    @Test
    fun `the 2_12_4 copy matches the bytes 2_12_4 produces`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 100).toByte() }
        assertEquals("ZCPSK:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", QuantumShield2124.toQRPayload(a))
        assertEquals("ZCPSK:ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+f4CBgoM=", QuantumShield2124.toQRPayload(b))
        val psk = QuantumShield2124.derivePSK(b, a).joinToString("") { "%02x".format(it) }
        assertEquals("af14c2e1bac457da14f581c41be17f1686aa0049b10463b3715c8a6c910eff88", psk)
    }

    @Test
    fun `a 2_12_4 phone and a 2_13_0 phone agree on one Quantum Shield key and no code is offered`() {
        val old = Phone2124()
        val new = Phone2130(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B)

        val oldQr = old.initiate()
        val newQr = new.initiate()

        assertTrue("the 2.12.4 phone must accept the QR a 2.13.0 phone shows", old.complete(newQr))
        assertTrue("the 2.13.0 phone must accept the QR a 2.12.4 phone shows", new.complete(oldQr))

        assertNotNull(old.pskB64)
        assertEquals(
            "both phones must hold the same Quantum Shield key, or the chat becomes unreadable",
            old.pskB64,
            new.pskB64,
        )

        // The 2.12.4 phone never shows a security code, so there is nothing to compare: the 2.13.0 phone
        // must say so instead of asking its user to confirm a code the other phone cannot show.
        val confirmation = new.confirmation
        assertNotNull("the 2.13.0 phone must tell its user the shield is set up", confirmation)
        assertNull(
            "a 2.12.4 pairing has no code the other phone can show; got: $confirmation",
            codeIn(confirmation),
        )
    }

    @Test
    fun `the same pairing agrees whichever phone scans first`() {
        val old = Phone2124()
        val new = Phone2130(ourPub = PUB_A, peerPub = PUB_B, peerAddress = PEER_OF_A)

        val newQr = new.initiate()
        val oldQr = old.initiate()

        assertTrue(new.complete(oldQr))
        assertTrue(old.complete(newQr))
        assertEquals(old.pskB64, new.pskB64)
    }

    @Test
    fun `two 2_13_0 phones keep the identity-bound key and show the same code`() {
        val a = Phone2130(ourPub = PUB_A, peerPub = PUB_B, peerAddress = PEER_OF_A)
        val b = Phone2130(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B)

        val qrA = a.initiate()
        val qrB = b.initiate()
        assertTrue(a.complete(qrB))
        assertTrue(b.complete(qrA))

        assertNotNull(a.pskB64)
        assertEquals(a.pskB64, b.pskB64)

        val secretA = Base64.getDecoder().decode(a.prefs.values["ourSecret"])
        val secretB = Base64.getDecoder().decode(b.prefs.values["ourSecret"])
        val bound = Base64.getEncoder().encodeToString(QuantumShield.derivePSK(secretA, secretB, PUB_A, PUB_B))
        assertEquals("two current phones must keep the B1l identity-bound key", bound, a.pskB64)

        val codeA = codeIn(a.confirmation)
        assertNotNull("two current phones must be asked to compare a code", codeA)
        assertEquals(codeA, codeIn(b.confirmation))
    }

    @Test
    fun `a phone updated from 2_12_4 in the middle of a setup still agrees with its peer`() {
        // The 2.12.4 phone showed its QR and the 2.13.0 peer scanned it; then the 2.12.4 phone was updated
        // to 2.13.0 before it scanned the peer's QR. Its stored secret is the one 2.12.4 wrote.
        val updating = Phone2124()
        val shownBeforeUpdate = updating.initiate()

        val peer = Phone2130(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B)
        val peerQr = peer.initiate()
        assertTrue(peer.complete(shownBeforeUpdate))

        val updated = Phone2130(ourPub = PUB_A, peerPub = PUB_B, peerAddress = PEER_OF_A)
        updated.prefs.values["ourSecret"] = updating.ourSecretB64!!
        assertTrue(updated.complete(peerQr))

        assertEquals(peer.pskB64, updated.pskB64)
    }

    @Test
    fun `an emptied secret is refused instead of deriving a key from zero bytes`() {
        // What resetQuantumShield leaves behind. Before R6-5 the scan derived a key from an empty secret.
        val old = Phone2124()
        val new = Phone2130(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B)
        new.prefs.values["ourSecret"] = ""

        assertFalse("no key may be derived from an empty secret", new.complete(old.initiate()))
        assertNull(new.pskB64)
        assertNull("a refused scan shows no confirmation", new.confirmation)
    }

    @Test
    fun `a second scan on a shield that is on keeps the confirmation of its setup`() {
        // With a 2.12.4 phone: no code at setup, and none after rescanning its QR or a garbled ZCPSK text.
        val old = Phone2124()
        val new = Phone2130(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B)
        val oldQr = old.initiate()
        new.initiate()
        assertTrue(new.complete(oldQr))
        val pskAtSetup = new.pskB64
        for (rescan in listOf(oldQr, "ZCPSK:%%%")) {
            new.dismiss()
            assertTrue(new.complete(rescan))
            assertEquals("a repeat scan never changes the key", pskAtSetup, new.pskB64)
            assertNotNull(new.confirmation)
            assertNull("no code after rescanning `$rescan` on a 2.12.4 setup", codeIn(new.confirmation))
        }

        // Two current phones: the code of the setup, also after rescanning the peer's secret as a padded QR.
        val a = Phone2130(ourPub = PUB_A, peerPub = PUB_B, peerAddress = PEER_OF_A)
        val b = Phone2130(ourPub = PUB_B, peerPub = PUB_A, peerAddress = PEER_OF_B)
        val qrA = a.initiate()
        val qrB = b.initiate()
        assertTrue(a.complete(qrB))
        assertTrue(b.complete(qrA))
        val setupCode = codeIn(a.confirmation)
        assertNotNull(setupCode)
        val paddedB = "ZCPSK:" + Base64.getEncoder().encodeToString(Base64.getDecoder().decode(b.prefs.values["ourSecret"]))
        a.dismiss()
        assertTrue(a.complete(paddedB))
        assertEquals("the stored key is still the bound one, so the code stays", setupCode, codeIn(a.confirmation))
    }
}
