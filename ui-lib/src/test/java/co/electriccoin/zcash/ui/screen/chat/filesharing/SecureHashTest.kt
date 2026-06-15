package co.electriccoin.zcash.ui.screen.chat.filesharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for PBKDF2-based secure password hashing replacing plain SHA-256.
 */
class SecureHashTest {

    @Test
    fun `hash produces pbkdf2 prefixed output`() {
        val result = SecureHash.hash("test_pin")
        assertTrue(result.startsWith("pbkdf2:"), "Expected pbkdf2 prefix, got: ${result.take(20)}")
    }

    @Test
    fun `hash format is pbkdf2 iterations salt hash`() {
        val result = SecureHash.hash("mypassword")
        val parts = result.split(":")
        assertEquals(4, parts.size, "Expected 4 colon-separated parts")
        assertEquals("pbkdf2", parts[0])
        assertEquals("600000", parts[1])
        assertEquals(32, parts[2].length, "Salt should be 16 bytes = 32 hex chars")
        assertEquals(64, parts[3].length, "Hash should be 32 bytes = 64 hex chars")
    }

    @Test
    fun `verify returns true for correct input`() {
        val stored = SecureHash.hash("correct_phrase")
        assertTrue(SecureHash.verify("correct_phrase", stored))
    }

    @Test
    fun `verify returns false for wrong input`() {
        val stored = SecureHash.hash("correct_phrase")
        assertFalse(SecureHash.verify("wrong_phrase", stored))
    }

    @Test
    fun `same input produces different hashes due to random salt`() {
        val hash1 = SecureHash.hash("same_input")
        val hash2 = SecureHash.hash("same_input")
        assertNotEquals(hash1, hash2, "Random salt should produce different outputs")
        // But both should verify
        assertTrue(SecureHash.verify("same_input", hash1))
        assertTrue(SecureHash.verify("same_input", hash2))
    }

    @Test
    fun `verify handles legacy SHA-256 hex format`() {
        // Legacy format: plain 64-char hex string (no prefix)
        // SHA-256 of "***REMOVED***" computed manually
        val legacySha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest("***REMOVED***".toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(SecureHash.verify("***REMOVED***", legacySha256))
    }

    @Test
    fun `verify rejects wrong input against legacy hash`() {
        val legacySha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest("***REMOVED***".toByteArray()).joinToString("") { "%02x".format(it) }
        assertFalse(SecureHash.verify("000000", legacySha256))
    }

    @Test
    fun `verify returns false for empty stored hash`() {
        assertFalse(SecureHash.verify("anything", ""))
    }

    @Test
    fun `verify returns false for malformed stored hash`() {
        assertFalse(SecureHash.verify("anything", "pbkdf2:bad:data"))
        assertFalse(SecureHash.verify("anything", "pbkdf2:600000:short:short"))
    }

    @Test
    fun `verify rejects DoS iteration count`() {
        // A hostile stored hash with Int.MAX_VALUE iterations must NOT trigger compute.
        val mal = "pbkdf2:2147483647:00112233445566778899aabbccddeeff:" +
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        // verify must return false promptly, not freeze the test.
        val start = System.currentTimeMillis()
        assertFalse(SecureHash.verify("anything", mal))
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 200, "Verify should bail before compute; took ${elapsed}ms")
    }

    @Test
    fun `verify rejects too-low iteration count`() {
        val tooLow = "pbkdf2:1000:00112233445566778899aabbccddeeff:" +
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        assertFalse(SecureHash.verify("anything", tooLow))
    }

    @Test
    fun `verify returns false for non-hex salt instead of throwing`() {
        val mal = "pbkdf2:600000:ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ:" +
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        // Must not throw NumberFormatException.
        assertFalse(SecureHash.verify("anything", mal))
    }

    @Test
    fun `verifyAsync dispatches off main thread`() = kotlinx.coroutines.test.runTest {
        val stored = SecureHash.hash("test_pin")
        // Just verify it works through the suspending path; correctness already covered.
        assertTrue(SecureHash.verifyAsync("test_pin", stored))
        assertFalse(SecureHash.verifyAsync("wrong_pin", stored))
    }
}
