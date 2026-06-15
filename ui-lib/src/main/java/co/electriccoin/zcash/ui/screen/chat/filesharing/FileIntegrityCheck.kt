package co.electriccoin.zcash.ui.screen.chat.filesharing

import co.electriccoin.zcash.ui.nostr.FileUploadManager
import java.security.MessageDigest

/**
 * Verifies downloaded file integrity against the hash and size declared in the ZFILE message.
 *
 * The ZFILE message stores:
 *   hash = sha256Hex(ciphertext).take(EXPECTED_HASH_LEN)   (first 32 hex chars = 128 bits)
 *   size = ciphertext.size                                   (byte count of the encrypted blob)
 *
 * Both refer to the CIPHERTEXT — verification must happen BEFORE decryption.
 *
 * Note on security scope: matching ciphertext hash proves the relay served the bytes the sender
 * advertised. The plaintext authenticity comes from AES-GCM's authentication tag inside decrypt,
 * not from this check. We still verify ciphertext hash to fail fast on tampered/corrupt downloads
 * before paying the decrypt cost.
 */
object FileIntegrityCheck {

    const val EXPECTED_HASH_LEN: Int = 32

    fun verifySize(ciphertext: ByteArray, declaredSize: Long): Boolean =
        ciphertext.size.toLong() == declaredSize

    /**
     * Constant-time comparison of the first [EXPECTED_HASH_LEN] hex chars of the SHA-256 of
     * [ciphertext] against [declaredHash]. Rejects malformed declared hashes (wrong length).
     */
    fun verifyHash(ciphertext: ByteArray, declaredHash: String): Boolean {
        if (declaredHash.length != EXPECTED_HASH_LEN) return false
        val computedPrefix = FileUploadManager.sha256Hex(ciphertext).take(EXPECTED_HASH_LEN)
        return MessageDigest.isEqual(
            computedPrefix.toByteArray(Charsets.US_ASCII),
            declaredHash.toByteArray(Charsets.US_ASCII)
        )
    }

    fun verify(ciphertext: ByteArray, declaredHash: String, declaredSize: Long): Boolean =
        verifySize(ciphertext, declaredSize) && verifyHash(ciphertext, declaredHash)
}
