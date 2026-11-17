package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuantumShieldTest {

    @Test
    fun generateRandom_produces_32_bytes() {
        val random = QuantumShield.generateRandom()
        assertEquals(32, random.size)
    }

    @Test
    fun derivePSK_is_deterministic() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 50).toByte() }
        val psk1 = QuantumShield.derivePSK(a, b)
        val psk2 = QuantumShield.derivePSK(a, b)
        assertTrue(psk1.contentEquals(psk2))
        assertEquals(32, psk1.size)
    }

    @Test
    fun derivePSK_is_order_independent() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 100).toByte() }
        val psk_ab = QuantumShield.derivePSK(a, b)
        val psk_ba = QuantumShield.derivePSK(b, a)
        assertTrue(psk_ab.contentEquals(psk_ba), "PSK(a,b) must equal PSK(b,a)")
    }

    @Test
    fun derivePSK_different_inputs_produce_different_PSK() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 50).toByte() }
        val c = ByteArray(32) { (it + 100).toByte() }
        val psk1 = QuantumShield.derivePSK(a, b)
        val psk2 = QuantumShield.derivePSK(a, c)
        assert(!psk1.contentEquals(psk2))
    }

    // ==========================================
    // B1l (Grok MED-016): peer-bound derivation + SAS
    // ==========================================

    @Test
    fun derivePSK_bound_is_order_independent_across_secret_and_binding_order() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 100).toByte() }
        val alice = "u1alice_address"
        val bob = "u1bob_address"
        val reference = QuantumShield.derivePSK(a, b, alice, bob)
        // Full swap (Bob's view: his secret first, his binding first)
        assertTrue(reference.contentEquals(QuantumShield.derivePSK(b, a, bob, alice)))
        // Binding-only swap
        assertTrue(reference.contentEquals(QuantumShield.derivePSK(a, b, bob, alice)))
        // Secret-only swap
        assertTrue(reference.contentEquals(QuantumShield.derivePSK(b, a, alice, bob)))
        assertEquals(32, reference.size)
    }

    @Test
    fun derivePSK_bound_different_peer_binding_produces_different_PSK() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 100).toByte() }
        val pskAliceBob = QuantumShield.derivePSK(a, b, "u1alice_address", "u1bob_address")
        val pskAliceCarol = QuantumShield.derivePSK(a, b, "u1alice_address", "u1carol_address")
        assertTrue(
            !pskAliceBob.contentEquals(pskAliceCarol),
            "Same secrets bound to a different peer pair must yield a different PSK"
        )
    }

    @Test
    fun derivePSK_bound_differs_from_unbound_derivation() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 100).toByte() }
        val bound = QuantumShield.derivePSK(a, b, "u1alice_address", "u1bob_address")
        val unbound = QuantumShield.derivePSK(a, b)
        assertTrue(
            !bound.contentEquals(unbound),
            "Bound derivation must actually mix the bindings into the key"
        )
    }

    @Test
    fun computeSafetyString_is_deterministic_and_order_independent() {
        val psk = ByteArray(32) { (it * 7).toByte() }
        val alice = "u1alice_address"
        val bob = "u1bob_address"
        val sas1 = QuantumShield.computeSafetyString(psk, alice, bob)
        val sas2 = QuantumShield.computeSafetyString(psk, alice, bob)
        val sasSwapped = QuantumShield.computeSafetyString(psk, bob, alice)
        assertEquals(sas1, sas2, "SAS must be deterministic")
        assertEquals(sas1, sasSwapped, "SAS must be binding-order independent")
        assertTrue(sas1.matches(Regex("\\d{6}")), "SAS must be a 6-digit code, got '$sas1'")
    }

    @Test
    fun computeSafetyString_changes_when_secret_or_binding_changes() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 100).toByte() }
        val c = ByteArray(32) { (it + 200).toByte() }
        val alice = "u1alice_address"
        val bob = "u1bob_address"

        val pskAB = QuantumShield.derivePSK(a, b, alice, bob)
        val reference = QuantumShield.computeSafetyString(pskAB, alice, bob)

        // Different secret => different PSK => different SAS
        val pskAC = QuantumShield.derivePSK(a, c, alice, bob)
        assertTrue(
            reference != QuantumShield.computeSafetyString(pskAC, alice, bob),
            "SAS must change when a secret changes"
        )
        // Different binding (same PSK bytes) => different SAS
        assertTrue(
            reference != QuantumShield.computeSafetyString(pskAB, alice, "u1carol_address"),
            "SAS must change when a binding changes"
        )
    }

    @Test
    fun toQRPayload_and_fromQRPayload_roundtrip() {
        val random = QuantumShield.generateRandom()
        val payload = QuantumShield.toQRPayload(random)
        assertTrue(payload.startsWith("ZCPSK:"))
        val parsed = QuantumShield.fromQRPayload(payload)
        assertNotNull(parsed)
        assertTrue(random.contentEquals(parsed))
    }

    @Test
    fun fromQRPayload_rejects_invalid_input() {
        assertNull(QuantumShield.fromQRPayload("not a valid payload"))
        assertNull(QuantumShield.fromQRPayload("ZCPSK:!!!invalid-base64!!!"))
        assertNull(QuantumShield.fromQRPayload(""))
    }
}
