package co.electriccoin.zcash.ui.screen.chat.filesharing

import co.electriccoin.zcash.ui.nostr.FileUploadManager

/**
 * Verifies downloaded file integrity against the hash and size declared in the ZFILE message.
 *
 * The ZFILE message stores:
 *   hash = sha256Hex(ciphertext).take(32)   (first 32 hex chars = 128 bits)
 *   size = ciphertext.size                   (byte count of the encrypted blob)
 *
 * Both refer to the CIPHERTEXT — verification must happen BEFORE decryption.
 */
object FileIntegrityCheck {

    fun verifySize(ciphertext: ByteArray, declaredSize: Long): Boolean =
        ciphertext.size.toLong() == declaredSize

    fun verifyHash(ciphertext: ByteArray, declaredHash: String): Boolean {
        if (declaredHash.isEmpty()) return false
        val computedHash = FileUploadManager.sha256Hex(ciphertext)
        return computedHash.startsWith(declaredHash)
    }

    fun verify(ciphertext: ByteArray, declaredHash: String, declaredSize: Long): Boolean =
        verifySize(ciphertext, declaredSize) && verifyHash(ciphertext, declaredHash)
}
