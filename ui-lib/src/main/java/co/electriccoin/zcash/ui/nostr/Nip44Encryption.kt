package co.electriccoin.zcash.ui.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * NIP-44 v2 conversational encryption.
 *
 * Spec: https://github.com/nostr-protocol/nips/blob/master/44.md
 *
 * Pipeline:
 *   1. ECDH(sender_priv, recipient_pub) → 32-byte x-coordinate shared secret.
 *   2. HKDF-Extract(salt="nip44-v2", IKM=shared) → 32-byte conversation key.
 *   3. HKDF-Expand(conversation_key, info=nonce, len=76) → 32B ChaCha key | 12B nonce
 *      | 32B HMAC key.
 *   4. Pad plaintext to a power-of-two-ish bucket (see [pad]).
 *   5. ChaCha20-encrypt the padded plaintext.
 *   6. HMAC-SHA256(nonce || ciphertext) using HMAC key.
 *   7. Wire: base64( 0x02 || nonce(32) || ciphertext || hmac(32) ).
 *
 * Threat model: confidentiality + integrity per message. NOT forward-secret — long-term
 * key compromise reveals past messages. ZCHAT mitigates by rotating the NOSTR identity
 * when a conversation moves from Tunnel to a fresh chat.
 */
object Nip44Encryption {

    private const val VERSION_V2: Byte = 0x02
    private const val NONCE_LEN = 32
    private const val MAC_LEN = 32
    private const val CHACHA_KEY_LEN = 32
    private const val CHACHA_NONCE_LEN = 12
    private const val HMAC_KEY_LEN = 32
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65535
    private const val MIN_PADDED = 32

    /** Encrypt UTF-8 [plaintext] from [senderPriv] (32B) to [recipientXOnlyPub] (32B). */
    fun encrypt(plaintext: String, senderPriv: ByteArray, recipientXOnlyPub: ByteArray): String {
        require(plaintext.toByteArray(Charsets.UTF_8).size in MIN_PLAINTEXT..MAX_PLAINTEXT) {
            "plaintext length must be in 1..65535 bytes"
        }
        val conversation = conversationKey(senderPriv, recipientXOnlyPub)
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val (chachaKey, chachaNonce, hmacKey) = derivePerMessageKeys(conversation, nonce)
        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = chacha20(chachaKey, chachaNonce, padded)
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        val payload = ByteArray(1 + NONCE_LEN + ciphertext.size + MAC_LEN).apply {
            this[0] = VERSION_V2
            System.arraycopy(nonce, 0, this, 1, NONCE_LEN)
            System.arraycopy(ciphertext, 0, this, 1 + NONCE_LEN, ciphertext.size)
            System.arraycopy(mac, 0, this, 1 + NONCE_LEN + ciphertext.size, MAC_LEN)
        }
        return Base64.getEncoder().encodeToString(payload)
    }

    /** Decrypt [b64Payload] from [senderXOnlyPub] to [recipientPriv] (32B). */
    fun decrypt(b64Payload: String, recipientPriv: ByteArray, senderXOnlyPub: ByteArray): String {
        val raw = Base64.getDecoder().decode(b64Payload)
        require(raw.size >= 1 + NONCE_LEN + MIN_PADDED + MAC_LEN) { "payload too short" }
        require(raw[0] == VERSION_V2) { "unsupported NIP-44 version ${raw[0]}" }

        val nonce = raw.copyOfRange(1, 1 + NONCE_LEN)
        val ciphertext = raw.copyOfRange(1 + NONCE_LEN, raw.size - MAC_LEN)
        val mac = raw.copyOfRange(raw.size - MAC_LEN, raw.size)

        val conversation = conversationKey(recipientPriv, senderXOnlyPub)
        val (chachaKey, chachaNonce, hmacKey) = derivePerMessageKeys(conversation, nonce)

        val expectedMac = hmacSha256(hmacKey, nonce + ciphertext)
        require(MessageDigest.isEqual(expectedMac, mac)) { "HMAC mismatch" }

        val padded = chacha20(chachaKey, chachaNonce, ciphertext)
        return unpad(padded).toString(Charsets.UTF_8)
    }

    /**
     * ECDH conversation key. NIP-44 v2 takes the raw X coordinate of the shared point
     * (NOT the secp256k1 library default of SHA256 of the compressed point) and runs it
     * through HKDF-Extract.
     *
     * We use pubKeyTweakMul to multiply the peer's pubkey by our private key, then
     * extract the X coordinate from the resulting uncompressed point. This sidesteps
     * acinq's default ecdh hash function, which would break NIP-44 wire compatibility.
     */
    fun conversationKey(privKey: ByteArray, peerXOnlyPub: ByteArray): ByteArray {
        val pub33 = ByteArray(33).also {
            it[0] = 0x02
            System.arraycopy(peerXOnlyPub, 0, it, 1, 32)
        }
        val sharedPoint = Secp256k1.pubKeyTweakMul(pub33, privKey)
        // sharedPoint is 33 bytes compressed OR 65 bytes uncompressed depending on impl;
        // acinq returns 65 bytes. X is bytes 1..32.
        val sharedX = when (sharedPoint.size) {
            65 -> sharedPoint.copyOfRange(1, 33)
            33 -> sharedPoint.copyOfRange(1, 33)
            else -> error("Unexpected shared-point length ${sharedPoint.size}")
        }
        return hkdfExtract(salt = SALT_NIP44_V2, ikm = sharedX)
    }

    private fun derivePerMessageKeys(
        conversationKey: ByteArray,
        nonce: ByteArray,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val output = hkdfExpand(prk = conversationKey, info = nonce, length = CHACHA_KEY_LEN + CHACHA_NONCE_LEN + HMAC_KEY_LEN)
        val chachaKey = output.copyOfRange(0, CHACHA_KEY_LEN)
        val chachaNonce = output.copyOfRange(CHACHA_KEY_LEN, CHACHA_KEY_LEN + CHACHA_NONCE_LEN)
        val hmacKey = output.copyOfRange(CHACHA_KEY_LEN + CHACHA_NONCE_LEN, output.size)
        return Triple(chachaKey, chachaNonce, hmacKey)
    }

    /**
     * NIP-44 padding scheme: prepend a 2-byte big-endian length and pad with zeros up to
     * a "bucket" length that obscures the true message size. Bucket function from the spec.
     */
    private fun pad(plain: ByteArray): ByteArray {
        val padded = calcPaddedLength(plain.size)
        val buf = ByteArray(2 + padded)
        buf[0] = (plain.size ushr 8).toByte()
        buf[1] = plain.size.toByte()
        System.arraycopy(plain, 0, buf, 2, plain.size)
        // remaining bytes are already zero
        return buf
    }

    private fun unpad(padded: ByteArray): ByteArray {
        require(padded.size >= 2) { "unpad: too short" }
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(len in MIN_PLAINTEXT..MAX_PLAINTEXT && len + 2 <= padded.size) { "unpad: length oob" }
        return padded.copyOfRange(2, 2 + len)
    }

    /** Bucket function per NIP-44 v2 §6 — message lengths leak only the bucket. */
    @Suppress("MagicNumber")
    private fun calcPaddedLength(unpaddedLen: Int): Int {
        if (unpaddedLen <= MIN_PADDED) return MIN_PADDED
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    // --- HKDF (RFC 5869) ---

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val input = ByteArray(t.size + info.size + 1).apply {
                System.arraycopy(t, 0, this, 0, t.size)
                System.arraycopy(info, 0, this, t.size, info.size)
                this[size - 1] = counter.toByte()
            }
            t = hmacSha256(prk, input)
            val toCopy = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, toCopy)
            pos += toCopy
            counter++
        }
        return out
    }

    // --- HMAC-SHA256 ---

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // --- ChaCha20 ---
    // Standalone implementation per RFC 8439. Bouncy Castle would also work but adds a
    // ~3 MB dependency we don't need elsewhere; ChaCha20 is ~80 lines of straight-line code.
    //
    // `internal` (not private) so Nip44ChaCha20Test can pin it against the RFC 8439
    // Appendix A.1 keystream vectors — that test is what guards the counter origin below.

    @Suppress("MagicNumber")
    internal fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        val out = ByteArray(data.size)
        val state = IntArray(16)
        // Constants "expand 32-byte k"
        state[0] = 0x61707865; state[1] = 0x3320646e
        state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0..7) state[4 + i] = leBytesToInt(key, i * 4)
        // counter at state[12]
        state[13] = leBytesToInt(nonce, 0)
        state[14] = leBytesToInt(nonce, 4)
        state[15] = leBytesToInt(nonce, 8)

        // NIP-44 v2 (§2) + the reference @noble/ciphers implementation start the ChaCha20
        // block counter at 0. (RFC 8439's AEAD construction reserves block 0 for the
        // Poly1305 key and starts payload encryption at 1, but NIP-44 does NOT use that
        // AEAD construction — it uses raw ChaCha20 + a separate HMAC, so the counter
        // starts at 0.) Starting at 1 round-trips with ourselves but is wire-incompatible
        // with every spec-compliant NOSTR client; see Nip44ChaCha20Test.
        var offset = 0
        var counter = 0
        while (offset < data.size) {
            state[12] = counter
            val block = chaChaBlock(state)
            val toCopy = minOf(64, data.size - offset)
            for (i in 0 until toCopy) {
                out[offset + i] = (data[offset + i] xor block[i])
            }
            offset += toCopy
            counter++
        }
        return out
    }

    @Suppress("MagicNumber")
    private fun chaChaBlock(state: IntArray): ByteArray {
        val working = state.copyOf()
        repeat(10) {
            // Column rounds
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }
        for (i in 0..15) working[i] += state[i]
        val out = ByteArray(64)
        for (i in 0..15) {
            out[i * 4] = working[i].toByte()
            out[i * 4 + 1] = (working[i] ushr 8).toByte()
            out[i * 4 + 2] = (working[i] ushr 16).toByte()
            out[i * 4 + 3] = (working[i] ushr 24).toByte()
        }
        return out
    }

    @Suppress("MagicNumber")
    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun leBytesToInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xff) or
            ((buf[off + 1].toInt() and 0xff) shl 8) or
            ((buf[off + 2].toInt() and 0xff) shl 16) or
            ((buf[off + 3].toInt() and 0xff) shl 24)

    private val SALT_NIP44_V2 = "nip44-v2".toByteArray(Charsets.US_ASCII)
}
