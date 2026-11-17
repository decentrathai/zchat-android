package co.electriccoin.zcash.ui.screen.chat.viewmodel

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The 2.12.4 phone as a sender (compat-2124 rule 1: frozen code, not HEAD). Copied from `git show v2.12.4:` (a1fb4136f):
 * - `ChatViewModel.kt` getOrCreateMessageProcessor, root material: the sorted txids joined with `|`
 *   (`E2ERatchet.canonicalTxidMaterial`) and `E2ERatchet.deriveRatchetRoot`: HKDF-SHA256(ikm = ECDH secret, salt =
 *   "ZCHAT_RATCHET_ROOT_V1", info = sha256(kexMaterial || kexAckMaterial));
 * - `E2ERatchet.kt` deriveMessageKey / deriveChainKey0 / counterNonce / aadFor / aesGcmEncrypt: chain key 0 =
 *   HKDF(ikm = root, no salt, info = "ZCHAT_CHAIN_A2B_V1" or "..._B2A_V1"), one HMAC step per counter, message key =
 *   HMAC(chain, 0x01), nonce = direction || 000 || counter (u64 BE), AAD = direction || counter (u64 BE) || convId;
 * - `CiphertextWireFormat.kt` serialize: `E2E1:<dir hex 2>:<counter hex 16>:<base64>`.
 * HKDF is RFC 5869 written out here (HMAC-SHA256 extract and expand), not HEAD's HKDF object, so a drift in HEAD's cannot
 * hide. The direction of a sender is 0x00 when its public key sorts lower, else 0x01.
 */
internal object Frozen2124Sealer {
    private val ROOT_SALT = "ZCHAT_RATCHET_ROOT_V1".toByteArray(Charsets.UTF_8)

    /** The 2.12.4 root: material = sorted txids joined by `|`, no scalar (a single-exchange chat's scalar is the same txid). */
    fun root(
        ecdhSharedSecret: ByteArray,
        kexTxids: List<String>,
        kexAckTxids: List<String>,
    ): ByteArray {
        val kex = kexTxids.toSortedSet().joinToString("|").toByteArray(Charsets.UTF_8)
        val ack = kexAckTxids.toSortedSet().joinToString("|").toByteArray(Charsets.UTF_8)
        return hkdf(ikm = ecdhSharedSecret, salt = ROOT_SALT, info = sha256(kex + ack))
    }

    /** The `E2E1:` wire of [text] at [counter] in the direction of a sender whose key is the lower one iff [senderIsLower]. */
    fun seal(
        root: ByteArray,
        convId: String,
        senderIsLower: Boolean,
        text: String,
        counter: Long = 0L,
    ): String {
        val direction: Byte = if (senderIsLower) 0x00 else 0x01
        val chainInfo = if (direction == 0x00.toByte()) "ZCHAT_CHAIN_A2B_V1" else "ZCHAT_CHAIN_B2A_V1"
        var chainKey = hkdf(ikm = root, salt = null, info = chainInfo.toByteArray())
        var step = 0L
        while (step < counter) {
            chainKey = hmac(chainKey, byteArrayOf(0x02))
            step++
        }
        val messageKey = hmac(chainKey, byteArrayOf(0x01))
        val nonce = ByteArray(12)
        nonce[0] = direction
        System.arraycopy(ByteBuffer.allocate(8).putLong(counter).array(), 0, nonce, 4, 8)
        val aad = byteArrayOf(direction) + ByteBuffer.allocate(8).putLong(counter).array() + convId.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return "E2E1:%02x:%016x:%s".format(direction, counter, Base64.getEncoder().encodeToString(body))
    }

    private fun hkdf(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
    ): ByteArray {
        val prk = hmac(salt ?: ByteArray(32), ikm)
        return hmac(prk, info + byteArrayOf(0x01)) // one block: the 32 bytes the app asks for
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
