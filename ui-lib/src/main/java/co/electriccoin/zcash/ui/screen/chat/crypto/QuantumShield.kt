package co.electriccoin.zcash.ui.screen.chat.crypto

import java.util.Base64
import java.security.SecureRandom

/**
 * QuantumShield — Pre-Shared Key (PSK) generation and derivation for quantum-resistant
 * file sharing. Each party generates a random 32-byte secret, exchanges it via QR code,
 * and both derive an identical PSK from the two secrets using HKDF.
 *
 * The derivation is order-independent: derivePSK(a, b) == derivePSK(b, a).
 */
object QuantumShield {

    private const val QR_PREFIX = "ZCPSK:"
    private const val SECRET_LENGTH = 32
    private val INFO = "zchat-quantum-shield-psk".toByteArray()

    /**
     * Generate a cryptographically secure 32-byte random secret.
     */
    fun generateRandom(): ByteArray {
        val bytes = ByteArray(SECRET_LENGTH)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Derive a mutual PSK from two 32-byte secrets.
     * Order-independent: derivePSK(a, b) == derivePSK(b, a).
     *
     * The two inputs are sorted lexicographically (unsigned byte comparison),
     * concatenated, and fed through HKDF to produce a 32-byte key.
     */
    fun derivePSK(secretA: ByteArray, secretB: ByteArray): ByteArray {
        val (first, second) = orderSecrets(secretA, secretB)
        val ikm = first + second
        return HKDF.deriveKey(
            ikm = ikm,
            salt = null,
            info = INFO,
            length = SECRET_LENGTH
        )
    }

    /**
     * Encode a secret as a QR-scannable payload string.
     * Format: "ZCPSK:<base64-no-wrap>"
     */
    fun toQRPayload(secret: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(secret)
        return "$QR_PREFIX$encoded"
    }

    /**
     * Parse a QR payload back to a secret byte array.
     * Returns null if the payload is invalid or malformed.
     */
    fun fromQRPayload(payload: String): ByteArray? {
        if (!payload.startsWith(QR_PREFIX)) return null
        val b64 = payload.removePrefix(QR_PREFIX)
        if (b64.isEmpty()) return null
        return try {
            val decoded = Base64.getDecoder().decode(b64)
            if (decoded.size == SECRET_LENGTH) decoded else null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /**
     * Sort two byte arrays lexicographically using unsigned comparison
     * so that the order of inputs does not affect the result.
     */
    private fun orderSecrets(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val ua = a[i].toInt() and 0xFF
            val ub = b[i].toInt() and 0xFF
            if (ua < ub) return Pair(a, b)
            if (ua > ub) return Pair(b, a)
        }
        // If equal up to minLen, shorter array comes first
        return if (a.size <= b.size) Pair(a, b) else Pair(b, a)
    }
}
