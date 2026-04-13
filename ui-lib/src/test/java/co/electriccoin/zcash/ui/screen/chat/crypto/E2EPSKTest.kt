package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for E2E key derivation with optional Quantum Shield PSK.
 * Verifies backward compatibility: deriveKeyV2(secret, null) == deriveKeyV2(secret).
 */
class E2EPSKTest {

    private val testSharedSecret = ByteArray(32) { it.toByte() }
    private val testPSK = ByteArray(32) { (it + 100).toByte() }

    @Test
    fun deriveKeyV2_without_PSK_matches_existing_behavior() {
        // Call the NEW method with null PSK — must produce identical output
        // to the OLD method (backward compatibility guarantee)
        val withNullPSK = E2EEncryption.deriveKeyForTest(testSharedSecret, null)
        val withoutPSK = E2EEncryption.deriveKeyForTest(testSharedSecret)
        assertTrue(
            withNullPSK.contentEquals(withoutPSK),
            "deriveKey(secret, null) must equal deriveKey(secret) for backward compatibility"
        )
    }

    @Test
    fun deriveKeyV2_with_PSK_produces_different_key() {
        val withoutPSK = E2EEncryption.deriveKeyForTest(testSharedSecret, null)
        val withPSK = E2EEncryption.deriveKeyForTest(testSharedSecret, testPSK)
        assert(!withoutPSK.contentEquals(withPSK))
        assertEquals(32, withPSK.size)
    }

    @Test
    fun deriveKeyV2_with_PSK_is_deterministic() {
        val key1 = E2EEncryption.deriveKeyForTest(testSharedSecret, testPSK)
        val key2 = E2EEncryption.deriveKeyForTest(testSharedSecret, testPSK)
        assertTrue(key1.contentEquals(key2))
    }

    @Test
    fun different_PSKs_produce_different_keys() {
        val psk2 = ByteArray(32) { (it + 200).toByte() }
        val key1 = E2EEncryption.deriveKeyForTest(testSharedSecret, testPSK)
        val key2 = E2EEncryption.deriveKeyForTest(testSharedSecret, psk2)
        assert(!key1.contentEquals(key2))
    }
}
