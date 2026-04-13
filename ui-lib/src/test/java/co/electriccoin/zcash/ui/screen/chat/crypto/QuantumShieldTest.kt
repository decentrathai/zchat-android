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
