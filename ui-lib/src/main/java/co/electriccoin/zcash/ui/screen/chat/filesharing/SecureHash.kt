package co.electriccoin.zcash.ui.screen.chat.filesharing

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secure password/PIN hashing using PBKDF2WithHmacSHA256.
 *
 * Replaces plain SHA-256 (which is brute-forceable in nanoseconds per guess on GPU)
 * with a key-stretching function that makes each guess take ~300ms on the device
 * and proportionally expensive on attack hardware.
 *
 * Format: "pbkdf2:<iterations>:<salt_hex>:<hash_hex>"
 * Legacy format (plain 64-char hex) is supported for backward compatibility.
 *
 * Zero external dependencies — uses javax.crypto built into Android JCA.
 */
object SecureHash {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 600_000  // OWASP 2023 recommendation
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val PREFIX = "pbkdf2"

    /**
     * Hash a password/PIN with a random salt. Returns "pbkdf2:<iter>:<salt>:<hash>".
     */
    fun hash(input: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hashHex = computePbkdf2(input, salt, ITERATIONS)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        return "$PREFIX:$ITERATIONS:$saltHex:$hashHex"
    }

    /**
     * Verify an input against a stored hash. Supports both:
     * - New format: "pbkdf2:<iter>:<salt>:<hash>"
     * - Legacy format: plain 64-char hex (SHA-256, no salt)
     */
    fun verify(input: String, storedHash: String): Boolean {
        if (storedHash.isEmpty()) return false

        return if (storedHash.startsWith("$PREFIX:")) {
            verifyPbkdf2(input, storedHash)
        } else {
            verifyLegacySha256(input, storedHash)
        }
    }

    /**
     * Check if a stored hash is in the legacy SHA-256 format (needs upgrade).
     */
    fun isLegacyFormat(storedHash: String): Boolean =
        storedHash.isNotEmpty() && !storedHash.startsWith("$PREFIX:")

    private fun verifyPbkdf2(input: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val saltHex = parts[2]
        val expectedHash = parts[3]
        if (saltHex.length != SALT_LENGTH_BYTES * 2) return false
        if (expectedHash.length != KEY_LENGTH_BITS / 4) return false

        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val computedHash = computePbkdf2(input, salt, iterations)
        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
            computedHash.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun verifyLegacySha256(input: String, storedHex: String): Boolean {
        if (storedHex.length != 64) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val computedHex = hashBytes.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            computedHex.toByteArray(Charsets.UTF_8),
            storedHex.toByteArray(Charsets.UTF_8)
        )
    }

    private fun computePbkdf2(input: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(input.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return keyBytes.joinToString("") { "%02x".format(it) }
    }
}
