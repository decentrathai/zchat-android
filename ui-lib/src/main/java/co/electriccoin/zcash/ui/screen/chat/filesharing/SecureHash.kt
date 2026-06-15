package co.electriccoin.zcash.ui.screen.chat.filesharing

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secure password/PIN hashing using PBKDF2WithHmacSHA256.
 *
 * Replaces plain SHA-256 (which is brute-forceable in nanoseconds per guess on GPU)
 * with a key-stretching function that makes each guess take ~300ms on the device
 * and proportionally expensive on attack hardware.
 *
 * Format: "pbkdf2:<iterations>:<salt_hex>:<hash_hex>"
 * Legacy format (plain 64-char SHA-256 hex) is supported only during one-way migration.
 *
 * Threat-model notes:
 * - For a 6-digit PIN (10^6 search space), 600K-iteration PBKDF2 yields only modest
 *   attack-cost increase on commodity GPU. Application-level rate limiting is the
 *   primary defense; this primitive only raises the per-guess cost.
 * - The `verify` second argument MUST come from trusted storage (EncryptedSharedPreferences
 *   or the device app sandbox). User-controllable input as `storedHash` is a DoS vector.
 *
 * Zero external dependencies — uses javax.crypto built into Android JCA.
 */
object SecureHash {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS: Int = 600_000  // OWASP 2023 recommendation
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val PREFIX = "pbkdf2"

    // DoS guards on parsed iteration count. Upper bound conservatively chosen so a single
    // verify completes in <2 s on a slow ARM core even with MAX_ITERATIONS.
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 1_200_000

    /**
     * Suspending hash. Dispatches PBKDF2 work to [Dispatchers.Default] so callers from a
     * UI scope cannot accidentally block the main thread.
     */
    suspend fun hashAsync(input: String): String = withContext(Dispatchers.Default) { hash(input) }

    /**
     * Suspending verify. Same off-main-thread dispatch as [hashAsync].
     */
    suspend fun verifyAsync(input: String, storedHash: String): Boolean =
        withContext(Dispatchers.Default) { verify(input, storedHash) }

    /**
     * Synchronous hash. Production callers should prefer [hashAsync]. Kept for tests and
     * legacy call sites that already run off the main thread.
     */
    fun hash(input: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hashHex = computePbkdf2(input, salt, ITERATIONS)
        val saltHex = salt.toHex()
        return "$PREFIX:$ITERATIONS:$saltHex:$hashHex"
    }

    /**
     * Synchronous verify. Returns false on any malformed input (no exceptions propagate).
     * Production callers should prefer [verifyAsync].
     */
    fun verify(input: String, storedHash: String): Boolean {
        if (storedHash.isEmpty()) return false

        // Catch Throwable (not just Exception) — guards against OOM / NoClassDefFoundError
        // from pathological JCA provider behavior on malformed input.
        return try {
            if (storedHash.startsWith("$PREFIX:")) {
                verifyPbkdf2(input, storedHash)
            } else {
                verifyLegacySha256(input, storedHash)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Check if a stored hash is in the legacy SHA-256 format (needs upgrade). */
    fun isLegacyFormat(storedHash: String): Boolean =
        storedHash.isNotEmpty() && !storedHash.startsWith("$PREFIX:")

    private fun verifyPbkdf2(input: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false  // DoS bound
        val saltHex = parts[2]
        val expectedHashHex = parts[3]
        if (saltHex.length != SALT_LENGTH_BYTES * 2) return false
        if (expectedHashHex.length != KEY_LENGTH_BITS / 4) return false

        val salt = saltHex.hexToBytes() ?: return false
        val expectedBytes = expectedHashHex.hexToBytes() ?: return false
        val computedHashHex = computePbkdf2(input, salt, iterations)
        val computedBytes = computedHashHex.hexToBytes() ?: return false
        return MessageDigest.isEqual(computedBytes, expectedBytes)
    }

    private fun verifyLegacySha256(input: String, storedHex: String): Boolean {
        if (storedHex.length != 64) return false
        val expectedBytes = storedHex.hexToBytes() ?: return false
        val computedBytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(computedBytes, expectedBytes)
    }

    private fun computePbkdf2(input: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(input.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            val hex = keyBytes.toHex()
            keyBytes.fill(0)
            hex
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i ->
                ((this[i * 2].digitToInt(16) shl 4) + this[i * 2 + 1].digitToInt(16)).toByte()
            }
        }.getOrNull()
    }
}
