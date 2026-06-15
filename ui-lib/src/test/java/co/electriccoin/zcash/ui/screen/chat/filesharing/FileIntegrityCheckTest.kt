package co.electriccoin.zcash.ui.screen.chat.filesharing

import co.electriccoin.zcash.ui.nostr.FileUploadManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for file download integrity verification.
 * The ZFILE message stores:
 *   hash = sha256Hex(ciphertext).take(32)   (first 32 hex chars = 128 bits)
 *   size = ciphertext.size
 *
 * Verification must check CIPHERTEXT (before decryption), not plaintext.
 */
class FileIntegrityCheckTest {

    @Test
    fun `size check passes for matching ciphertext`() {
        val ciphertext = "hello encrypted world".toByteArray()
        val declaredSize = ciphertext.size.toLong()
        assertTrue(FileIntegrityCheck.verifySize(ciphertext, declaredSize))
    }

    @Test
    fun `size check fails for truncated download`() {
        val ciphertext = "hello encrypted world".toByteArray()
        val declaredSize = ciphertext.size.toLong() + 100
        assertFalse(FileIntegrityCheck.verifySize(ciphertext, declaredSize))
    }

    @Test
    fun `hash check passes for matching ciphertext`() {
        val ciphertext = "test ciphertext bytes".toByteArray()
        val fullHash = FileUploadManager.sha256Hex(ciphertext)
        val truncatedHash = fullHash.take(32)  // same as upload flow
        assertTrue(FileIntegrityCheck.verifyHash(ciphertext, truncatedHash))
    }

    @Test
    fun `hash check fails for tampered ciphertext`() {
        val original = "original ciphertext".toByteArray()
        val tampered = "tampered ciphertext".toByteArray()
        val originalHash = FileUploadManager.sha256Hex(original).take(32)
        assertFalse(FileIntegrityCheck.verifyHash(tampered, originalHash))
    }

    @Test
    fun `hash check handles empty hash gracefully`() {
        val ciphertext = "something".toByteArray()
        assertFalse(FileIntegrityCheck.verifyHash(ciphertext, ""))
    }

    @Test
    fun `hash check rejects single-char attacker hash`() {
        // Without the length guard, a single 'a' would match ~1/16 of inputs by accident.
        val ciphertext = "something".toByteArray()
        assertFalse(FileIntegrityCheck.verifyHash(ciphertext, "a"))
    }

    @Test
    fun `hash check rejects truncated declared hash`() {
        val ciphertext = "test".toByteArray()
        val fullHash = FileUploadManager.sha256Hex(ciphertext)
        // Anything shorter than EXPECTED_HASH_LEN must be rejected outright.
        assertFalse(FileIntegrityCheck.verifyHash(ciphertext, fullHash.take(16)))
        assertFalse(FileIntegrityCheck.verifyHash(ciphertext, fullHash.take(31)))
    }

    @Test
    fun `hash check rejects oversized declared hash`() {
        val ciphertext = "test".toByteArray()
        val fullHash = FileUploadManager.sha256Hex(ciphertext)
        // Full 64-char hex hash is also rejected — sender truncates to EXPECTED_HASH_LEN.
        assertFalse(FileIntegrityCheck.verifyHash(ciphertext, fullHash))
    }

    @Test
    fun `full verify checks size then hash`() {
        val ciphertext = "full pipeline test".toByteArray()
        val hash = FileUploadManager.sha256Hex(ciphertext).take(32)
        val size = ciphertext.size.toLong()
        assertTrue(FileIntegrityCheck.verify(ciphertext, hash, size))
    }

    @Test
    fun `full verify fails on wrong size without computing hash`() {
        val ciphertext = "short".toByteArray()
        val hash = FileUploadManager.sha256Hex(ciphertext).take(32)
        // Wrong size — should fail before hash
        assertFalse(FileIntegrityCheck.verify(ciphertext, hash, 99999L))
    }
}
