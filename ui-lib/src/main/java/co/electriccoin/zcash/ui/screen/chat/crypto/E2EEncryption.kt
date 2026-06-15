package co.electriccoin.zcash.ui.screen.chat.crypto

import java.util.Base64
import androidx.annotation.VisibleForTesting
import co.electriccoin.zcash.ui.common.result.CryptoResult
import co.electriccoin.zcash.ui.common.result.ZchatError
import co.electriccoin.zcash.ui.common.result.ZchatResult
import co.electriccoin.zcash.ui.common.util.redactAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key derivation version for E2E encryption.
 * V1: Legacy SHA-256 based derivation (insecure, for backwards compatibility)
 * V2: Proper HKDF (RFC 5869) derivation
 */
enum class E2EKeyVersion(val value: Int) {
    V1(1),  // Legacy: SHA-256 only (weak)
    V2(2);  // HKDF: Extract + Expand (secure)

    companion object {
        fun fromValue(value: Int): E2EKeyVersion = entries.find { it.value == value } ?: V1
    }
}

/**
 * HKDF (RFC 5869) implementation for secure key derivation.
 * Uses HMAC-SHA256 for both extract and expand phases.
 */
object HKDF {
    private const val HASH_LENGTH = 32 // SHA-256 output size

    /**
     * HKDF-Extract: PRK = HMAC-Hash(salt, IKM)
     * @param salt Optional salt value (defaults to zeros)
     * @param ikm Input keying material
     * @return Pseudorandom key (PRK)
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = salt ?: ByteArray(HASH_LENGTH)
        return hmacSha256(actualSalt, ikm)
    }

    /**
     * HKDF-Expand: OKM = T(1) || T(2) || ... where T(i) = HMAC-Hash(PRK, T(i-1) || info || i)
     * @param prk Pseudorandom key from extract phase
     * @param info Context/application specific info
     * @param length Desired output length in bytes (max 255 * HASH_LENGTH)
     * @return Output keying material
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LENGTH) { "Output length too large" }

        val n = (length + HASH_LENGTH - 1) / HASH_LENGTH
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0

        for (i in 1..n) {
            val data = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(prk, data)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }

        return okm
    }

    /**
     * Full HKDF: Extract then Expand
     * @param ikm Input keying material (shared secret from ECDH)
     * @param salt Optional salt (recommended but optional)
     * @param info Context-specific info string
     * @param length Desired output length
     * @return Derived key material
     */
    fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        length: Int = 32
    ): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/**
 * E2E Encryption Layer for ZCHAT
 *
 * Provides an optional additional encryption layer on top of Zcash's native encryption.
 * Uses secp256r1 (P-256) for key exchange and AES-256-GCM for message encryption.
 *
 * Key Derivation (V2):
 * - ECDH produces raw shared secret
 * - HKDF-Extract with salt "ZCHAT_E2E_SALT_V2"
 * - HKDF-Expand with info "ZCHAT_E2E_KEY"
 * - Output: 256-bit AES key
 *
 * Message Format:
 * - E2E:<nonce_base64>:<ciphertext_base64>
 */
object E2EEncryption {

    private const val TAG = "E2E_ENCRYPTION"
    private const val KEY_ALGORITHM = "EC"
    private const val KEY_CURVE = "secp256r1" // NIST P-256, widely supported
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val NONCE_SIZE = 12
    private const val E2E_PREFIX = "E2E:"

    // HKDF parameters for V2 key derivation
    private val HKDF_SALT_V2 = "ZCHAT_E2E_SALT_V2".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "ZCHAT_E2E_KEY".toByteArray(Charsets.UTF_8)
    private const val DERIVED_KEY_LENGTH = 32 // 256 bits

    /**
     * Generate a new key pair for E2E encryption.
     * Returns the public key as Base64 encoded string.
     */
    fun generateKeyPair(): E2EKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        val spec = java.security.spec.ECGenParameterSpec(KEY_CURVE)
        keyPairGenerator.initialize(spec, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)

        return E2EKeyPair(publicKeyBase64, privateKeyBase64)
    }

    /**
     * Derive a shared secret from our private key and peer's public key.
     * Uses ECDH key agreement with version-aware key derivation.
     *
     * @param ourPrivateKeyBase64 Our private key (Base64)
     * @param peerPublicKeyBase64 Peer's public key (Base64)
     * @param version Key derivation version (V1 for legacy, V2 for HKDF)
     * @param psk Optional Quantum Shield pre-shared key to mix into derivation (V2 only)
     * @return Derived encryption key (256-bit)
     */
    fun deriveSharedSecret(
        ourPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        version: E2EKeyVersion = E2EKeyVersion.V2,
        psk: ByteArray? = null
    ): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)

        // Decode our private key
        val privateKeyBytes = Base64.getDecoder().decode(ourPrivateKeyBase64)
        val privateKeySpec = java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes)
        val privateKey = keyFactory.generatePrivate(privateKeySpec)

        // Decode peer's public key
        val publicKeyBytes = Base64.getDecoder().decode(peerPublicKeyBase64)
        val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
        val publicKey = keyFactory.generatePublic(publicKeySpec)

        // Perform key agreement
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)

        // Derive 256-bit key using version-specific derivation
        val rawSharedSecret = keyAgreement.generateSecret()
        return deriveKey(rawSharedSecret, version, psk)
    }

    /**
     * Derive a 256-bit AES key from shared secret.
     * Uses version-aware derivation:
     * - V1: Legacy SHA-256 (weak, for backwards compatibility)
     * - V2: HKDF (RFC 5869) with proper salt and info, optional PSK mixing
     *
     * @param psk Optional Quantum Shield pre-shared key. When provided (V2 only),
     *            the PSK is concatenated with the shared secret before HKDF extraction,
     *            strengthening the key against future quantum attacks on ECDH.
     */
    private fun deriveKey(sharedSecret: ByteArray, version: E2EKeyVersion, psk: ByteArray? = null): ByteArray {
        return when (version) {
            E2EKeyVersion.V1 -> deriveKeyV1(sharedSecret)
            E2EKeyVersion.V2 -> deriveKeyV2(sharedSecret, psk)
        }
    }

    /**
     * V1 (Legacy): Simple SHA-256 derivation.
     * DEPRECATED: Only use for backwards compatibility with existing keys.
     */
    private fun deriveKeyV1(sharedSecret: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("ZCHAT_E2E_KEY_V1".toByteArray())
        return digest.digest(sharedSecret)
    }

    /**
     * V2 (Current): Proper HKDF (RFC 5869) derivation.
     * - Extract: HMAC-SHA256(salt="ZCHAT_E2E_SALT_V2", IKM=sharedSecret [+ psk])
     * - Expand: HMAC-SHA256(PRK, info="ZCHAT_E2E_KEY" || 0x01)
     *
     * When [psk] is non-null, it is concatenated to the IKM before HKDF extraction.
     * This mixes the Quantum Shield pre-shared key into the derivation so that
     * the resulting key depends on BOTH the ECDH shared secret AND the PSK.
     *
     * Backward compatibility: when [psk] is null the IKM is unchanged, producing
     * an identical key to the original (pre-PSK) implementation.
     */
    private fun deriveKeyV2(sharedSecret: ByteArray, psk: ByteArray? = null): ByteArray {
        val ikm = if (psk != null) sharedSecret + psk else sharedSecret
        return HKDF.deriveKey(
            ikm = ikm,
            salt = HKDF_SALT_V2,
            info = HKDF_INFO,
            length = DERIVED_KEY_LENGTH
        )
    }

    /**
     * Get the current/default key version for new key exchanges.
     */
    fun getCurrentKeyVersion(): E2EKeyVersion = E2EKeyVersion.V2

    /**
     * Test-visible wrapper for V2 key derivation with optional PSK.
     * Allows instrumentation tests to verify backward compatibility
     * and PSK mixing without going through the full ECDH flow.
     *
     * @param sharedSecret Raw shared secret bytes
     * @param psk Optional Quantum Shield PSK (null = no PSK, matches legacy behavior)
     */
    @VisibleForTesting
    fun deriveKeyForTest(sharedSecret: ByteArray, psk: ByteArray? = null): ByteArray {
        return deriveKeyV2(sharedSecret, psk)
    }

    /**
     * Encrypt a message using the shared key.
     * Returns the encrypted message in format: E2E:<nonce>:<ciphertext>
     */
    fun encrypt(plaintext: String, sharedKey: ByteArray): String {
        val secretKey: SecretKey = SecretKeySpec(sharedKey, "AES")

        // Generate random nonce
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        // Encrypt
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Encode and format
        val nonceBase64 = Base64.getEncoder().encodeToString(nonce)
        val ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)

        return "$E2E_PREFIX$nonceBase64:$ciphertextBase64"
    }

    /**
     * Decrypt a message using the shared key.
     * Expects format: E2E:<nonce>:<ciphertext>
     */
    fun decrypt(encryptedMessage: String, sharedKey: ByteArray): String? {
        if (!isE2EEncrypted(encryptedMessage)) {
            android.util.Log.d("ZCHAT_E2E", "decrypt: not E2E message, prefix=${encryptedMessage.take(10)}")
            return null
        }

        // Validate key length before use
        if (sharedKey.size != DERIVED_KEY_LENGTH) {
            android.util.Log.e("ZCHAT_E2E", "decrypt: invalid key size ${sharedKey.size}, expected $DERIVED_KEY_LENGTH")
            return null
        }

        return try {
            val parts = encryptedMessage.removePrefix(E2E_PREFIX).split(":")
            if (parts.size != 2) {
                android.util.Log.w("ZCHAT_E2E", "decrypt: invalid format, parts=${parts.size}")
                return null
            }

            val nonce = Base64.getDecoder().decode(parts[0])
            val ciphertext = Base64.getDecoder().decode(parts[1])

            // Validate nonce length
            if (nonce.size != NONCE_SIZE) {
                android.util.Log.e("ZCHAT_E2E", "decrypt: invalid nonce size ${nonce.size}, expected $NONCE_SIZE")
                return null
            }

            val secretKey: SecretKey = SecretKeySpec(sharedKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ZCHAT_E2E", "decrypt FAILED: msgLen=${encryptedMessage.length} err=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Decrypt a message using the shared key, returning a Result type.
     * Expects format: E2E:<nonce>:<ciphertext>
     *
     * This is the preferred version - uses explicit error handling via CryptoResult.
     * Callers should use fold() to handle both success and failure cases.
     *
     * Example:
     * ```kotlin
     * decryptWithResult(encrypted, sharedKey).fold(
     *     onSuccess = { plaintext -> displayMessage(plaintext) },
     *     onFailure = { error -> logError(error.message) }
     * )
     * ```
     */
    fun decryptWithResult(encryptedMessage: String, sharedKey: ByteArray): CryptoResult<String> {
        if (!isE2EEncrypted(encryptedMessage)) {
            return ZchatResult.failure(ZchatError.Crypto.DecryptionFailed("Not an E2E message"))
        }

        if (sharedKey.size != DERIVED_KEY_LENGTH) {
            return ZchatResult.failure(ZchatError.Crypto.DecryptionFailed("Invalid key size"))
        }

        return try {
            val parts = encryptedMessage.removePrefix(E2E_PREFIX).split(":")
            if (parts.size != 2) {
                return ZchatResult.failure(ZchatError.Crypto.DecryptionFailed("Invalid E2E format"))
            }

            val nonce = Base64.getDecoder().decode(parts[0])
            val ciphertext = Base64.getDecoder().decode(parts[1])

            if (nonce.size != NONCE_SIZE) {
                return ZchatResult.failure(ZchatError.Crypto.DecryptionFailed("Invalid nonce size"))
            }

            val secretKey: SecretKey = SecretKeySpec(sharedKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plaintext = cipher.doFinal(ciphertext)
            ZchatResult.success(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Decryption failed", e)
            ZchatResult.failure(ZchatError.Crypto.DecryptionFailed(e.message))
        }
    }

    /**
     * Check if a message is E2E encrypted.
     */
    fun isE2EEncrypted(message: String): Boolean {
        return message.startsWith(E2E_PREFIX)
    }

    /**
     * Create an E2E INIT message that includes our public key.
     * Format: E2E_INIT:<public_key>
     * @deprecated Use createKEXPayload for authenticated key exchange
     */
    fun createE2EInitPayload(publicKey: String): String {
        return "E2E_INIT:$publicKey"
    }

    /**
     * Parse an E2E INIT payload to extract the public key.
     * @deprecated Use parseKEXPayload for authenticated key exchange
     */
    fun parseE2EInitPayload(payload: String): String? {
        return if (payload.startsWith("E2E_INIT:")) {
            payload.removePrefix("E2E_INIT:")
        } else {
            null
        }
    }

    // ==========================================
    // AUTHENTICATED KEY EXCHANGE (KEX)
    // ==========================================

    /**
     * Sign a message using ECDSA with SHA-256.
     * Used for KEX to prove ownership of the E2E private key.
     *
     * @param privateKeyBase64 Our private key (Base64 encoded)
     * @param message The message to sign (typically: address + pubkey)
     * @return Base64 encoded signature
     */
    fun sign(privateKeyBase64: String, message: String): String {
        val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)
        val privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64)
        val privateKeySpec = java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes)
        val privateKey = keyFactory.generatePrivate(privateKeySpec)

        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(message.toByteArray(Charsets.UTF_8))
        val signatureBytes = signature.sign()

        return Base64.getEncoder().encodeToString(signatureBytes)
    }

    /**
     * Verify an ECDSA signature.
     * Used for KEX to verify the sender controls the E2E private key.
     *
     * @param publicKeyBase64 Sender's public key (Base64 encoded)
     * @param message The original signed message
     * @param signatureBase64 The signature to verify (Base64 encoded)
     * @return true if signature is valid
     */
    fun verify(publicKeyBase64: String, message: String, signatureBase64: String): Boolean {
        return try {
            val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)
            val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
            val publicKey = keyFactory.generatePublic(publicKeySpec)

            val signature = java.security.Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(message.toByteArray(Charsets.UTF_8))

            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    /**
     * Result of parsing/verifying a KEX or KEXACK payload.
     *
     * @param publicKey The verified peer E2E public key (Base64). Always present on success.
     * @param nostrPubkeyHex Optional 64-hex-char NOSTR pubkey carried in the KEX (BUG-4 one-tap
     *        calling). Null when the sender is a legacy client that did not append it — in that
     *        case the caller falls back to the existing ZBOOT flow to learn the NOSTR identity.
     * @param relayUrl Optional relay URL the sender prefers for NOSTR DMs/calls. Null when absent.
     */
    data class ParsedKEX(
        val publicKey: String,
        val nostrPubkeyHex: String? = null,
        val relayUrl: String? = null,
        // The sender's address that the signature was verified against. For a first-contact KEX this is
        // recovered FROM the payload (the only way a recipient with no prior convId mapping can learn it).
        val senderAddress: String? = null,
    )

    /**
     * Create a KEX (Key Exchange) payload with signature.
     *
     * Format (legacy / no NOSTR):   KEX:<pubkey_b64>:<sig_b64>
     * Format (with NOSTR, BUG-4):   KEX:<pubkey_b64>:<sig_b64>:<nostrPubkeyHex>:<relay_b64>
     *
     * The signature is over (senderAddress + pubkey) to bind the key to the address. The signed
     * bytes are UNCHANGED by the optional NOSTR fields, so a legacy peer verifying a new KEX (and a
     * new peer verifying a legacy KEX) sign/verify the exact same canonical message — this is what
     * keeps old and new clients interoperable without a protocol-version bump.
     *
     * DELIMITER SAFETY: segments are joined with ':' which is NOT in the standard Base64 alphabet
     * (A–Z a–z 0–9 + / =) and not in lowercase hex (0–9 a–f). publicKey, signature and the relay are
     * Base64; nostrPubkeyHex is hex — none can contain ':', so splitting on ':' is unambiguous. The
     * relay URL contains ':' and '/', so it MUST be Base64-encoded before appending (hence relay_b64).
     *
     * @param senderAddress The sender's Zcash address
     * @param publicKey Our public key (Base64)
     * @param privateKey Our private key (Base64) - used for signing
     * @param nostrPubkeyHex Optional: our 64-hex-char NOSTR pubkey, to enable one-tap calling
     * @param relayUrl Optional: our preferred relay URL (encoded to Base64 in the wire format)
     * @return KEX payload string
     */
    fun createKEXPayload(
        senderAddress: String,
        publicKey: String,
        privateKey: String,
        nostrPubkeyHex: String? = null,
        relayUrl: String? = null,
    ): String {
        // Sign: SHA256(address || pubkey)
        val messageToSign = senderAddress + publicKey
        val signature = sign(privateKey, messageToSign)
        return buildKEXWire("KEX:", publicKey, signature, nostrPubkeyHex, relayUrl, senderAddress)
    }

    /**
     * Parse a KEX payload and verify the signature.
     *
     * @param payload The KEX payload string
     * @param senderAddress The sender's Zcash address (from transaction)
     * @return The verified public key, or null if verification fails
     */
    fun parseKEXPayload(payload: String, senderAddress: String? = null): String? {
        return parseKEXPayloadFull(payload, senderAddress)?.publicKey
    }

    /**
     * Parse + verify a KEX payload, returning the verified public key AND any optional NOSTR fields.
     *
     * Backward-compat: a payload with only <pubkey>:<sig> (legacy) parses fine with
     * nostrPubkeyHex == null. Extra trailing segments that are malformed are treated as ABSENT
     * (we still return the verified key) rather than failing the whole KEX.
     *
     * @return [ParsedKEX] on success, or null if the signature does not verify.
     */
    fun parseKEXPayloadFull(payload: String, senderAddress: String? = null): ParsedKEX? {
        if (!payload.startsWith("KEX:")) {
            return null
        }
        return parseKEXWire(payload.removePrefix("KEX:"), senderAddress, "KEX")
    }

    /**
     * Create a KEX acknowledgment payload.
     * Sent after receiving and verifying a KEX message.
     *
     * Format (legacy / no NOSTR):   KEXACK:<our_pubkey_b64>:<sig_b64>
     * Format (with NOSTR, BUG-4):   KEXACK:<our_pubkey_b64>:<sig_b64>:<nostrPubkeyHex>:<relay_b64>
     *
     * Same signed-bytes and delimiter-safety guarantees as [createKEXPayload].
     */
    fun createKEXAckPayload(
        senderAddress: String,
        publicKey: String,
        privateKey: String,
        nostrPubkeyHex: String? = null,
        relayUrl: String? = null,
    ): String {
        val messageToSign = senderAddress + publicKey
        val signature = sign(privateKey, messageToSign)
        return buildKEXWire("KEXACK:", publicKey, signature, nostrPubkeyHex, relayUrl, senderAddress)
    }

    /**
     * Parse a KEX acknowledgment payload.
     */
    fun parseKEXAckPayload(payload: String, senderAddress: String? = null): String? {
        return parseKEXAckPayloadFull(payload, senderAddress)?.publicKey
    }

    /**
     * Parse + verify a KEXACK payload, returning the verified public key AND any optional NOSTR
     * fields. See [parseKEXPayloadFull] for the backward-compat contract.
     */
    fun parseKEXAckPayloadFull(payload: String, senderAddress: String? = null): ParsedKEX? {
        if (!payload.startsWith("KEXACK:")) {
            return null
        }
        return parseKEXWire(payload.removePrefix("KEXACK:"), senderAddress, "KEXACK")
    }

    /**
     * Build the on-wire body shared by KEX and KEXACK. Appends the optional NOSTR segments only
     * when BOTH a pubkey and a relay are present (a partial pair would be ambiguous on the wire).
     */
    private fun buildKEXWire(
        prefix: String,
        publicKey: String,
        signature: String,
        nostrPubkeyHex: String?,
        relayUrl: String?,
        senderAddress: String? = null,
    ): String {
        var wire = "$prefix$publicKey:$signature"
        // SIZE BUDGET (512-byte Zcash memo): a u1 address is ~178 chars and the NOSTR fields add ~100
        // more. Including BOTH (plus pubkey + signature + ZMSG framing) overflows the memo → MemoTooLong
        // → the whole KEX send FAILS, which silently breaks the first-contact handshake (found via a
        // fresh-wallet 2-device retest). So PRIORITIZE: the address is ESSENTIAL — it lets a recipient
        // with no prior convId mapping recover + verify the sender (the signature binds
        // senderAddress||pubkey) so they can reply. The NOSTR fields (one-tap calling) are an
        // optimization that is ALSO delivered via the ZBOOT path, so drop them when the address is
        // present. A u1 address is bech32m (no ':'), so it stays delimiter-safe. Established-peer
        // re-KEX (no address needed — convId mapping exists) still carries the NOSTR fields.
        if (!senderAddress.isNullOrBlank()) {
            wire = "$wire:$senderAddress"
        } else if (!nostrPubkeyHex.isNullOrBlank() && !relayUrl.isNullOrBlank()) {
            val relayB64 = Base64.getEncoder().encodeToString(relayUrl.toByteArray(Charsets.UTF_8))
            wire = "$wire:$nostrPubkeyHex:$relayB64"
        }
        return wire
    }

    private val NOSTR_HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * Parse the body (after the "KEX:"/"KEXACK:" prefix) and verify the signature.
     * Returns null only when the signature fails; malformed trailing NOSTR fields are ignored.
     */
    private fun parseKEXWire(body: String, senderAddress: String?, tag: String): ParsedKEX? {
        val parts = body.split(":")
        if (parts.size < 2) {
            android.util.Log.e(TAG, "Invalid $tag payload format")
            return null
        }

        val publicKey = parts[0]
        val signature = parts[1]

        // Recover the appended sender address (raw unified address: starts with "u1", long, no ':').
        // This is distinguishable from the 64-hex NOSTR pubkey and the Base64 relay field.
        val payloadAddress = parts.drop(2).firstOrNull { it.length > 60 && it.startsWith("u1") }

        // Verify against the caller's address (established conversation) when known, else against the
        // address carried in the payload (first contact, TOFU). The signature binds (address || pubkey),
        // so a self-consistent payload proves the holder of the private key claims that address.
        val verifyAddress = senderAddress ?: payloadAddress
        if (verifyAddress == null) {
            android.util.Log.e(TAG, "$tag has no address to verify against (no convId mapping, no payload address)")
            return null
        }
        val messageToVerify = verifyAddress + publicKey
        if (!verify(publicKey, messageToVerify, signature)) {
            android.util.Log.e(TAG, "$tag signature verification failed for ${verifyAddress.redactAddress()}")
            return null
        }

        // Optional NOSTR segments (BUG-4 one-tap calling). Absent or malformed → treat as absent
        // (legacy fallback to ZBOOT), never fail the verified key exchange over them.
        var nostrPubkeyHex: String? = null
        var relayUrl: String? = null
        if (parts.size >= 4) {
            val candidatePubkey = parts[2].lowercase()
            val relayB64 = parts[3]
            if (NOSTR_HEX_64.matches(candidatePubkey)) {
                val decodedRelay = runCatching {
                    String(Base64.getDecoder().decode(relayB64), Charsets.UTF_8)
                }.getOrNull()
                if (decodedRelay != null && decodedRelay.startsWith("wss://") && decodedRelay.length <= 80) {
                    nostrPubkeyHex = candidatePubkey
                    relayUrl = decodedRelay
                }
            }
            if (nostrPubkeyHex == null) {
                android.util.Log.w(TAG, "$tag carried malformed NOSTR fields — ignoring, will fall back to ZBOOT")
            }
        }

        return ParsedKEX(
            publicKey = publicKey,
            nostrPubkeyHex = nostrPubkeyHex,
            relayUrl = relayUrl,
            senderAddress = verifyAddress,
        )
    }

    /**
     * Check if a payload is a KEX message.
     */
    fun isKEXPayload(payload: String): Boolean {
        return payload.startsWith("KEX:") || payload.startsWith("KEXACK:")
    }

    // ==========================================
    // ECIES (Elliptic Curve Integrated Encryption Scheme)
    // Used for encrypting group keys for individual members
    // ==========================================

    // TODO: ECIES V2 should add a proper salt. Current V1 uses null salt for backward compat.
    private val ECIES_INFO = "ZCHAT_ECIES_V1".toByteArray(Charsets.UTF_8)

    /**
     * Encrypt data using ECIES for a specific recipient.
     *
     * ECIES Flow:
     * 1. Generate ephemeral keypair
     * 2. ECDH: shared_secret = ephemeral_private * recipient_public
     * 3. Derive key from shared_secret using HKDF
     * 4. Encrypt data with AES-GCM
     *
     * @param recipientPublicKeyBase64 Recipient's public key (Base64)
     * @param plaintext The data to encrypt
     * @return ECIES encrypted blob: "ECIES:<ephemeral_pubkey>:<nonce>:<ciphertext>"
     */
    fun encryptECIES(recipientPublicKeyBase64: String, plaintext: ByteArray): String {
        // Generate ephemeral keypair
        val ephemeralKeyPair = generateKeyPair()

        // Compute shared secret using ECDH
        val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)

        val ephemeralPrivateKeyBytes = Base64.getDecoder().decode(ephemeralKeyPair.privateKey)
        val ephemeralPrivateKeySpec = java.security.spec.PKCS8EncodedKeySpec(ephemeralPrivateKeyBytes)
        val ephemeralPrivateKey = keyFactory.generatePrivate(ephemeralPrivateKeySpec)

        val recipientPublicKeyBytes = Base64.getDecoder().decode(recipientPublicKeyBase64)
        val recipientPublicKeySpec = java.security.spec.X509EncodedKeySpec(recipientPublicKeyBytes)
        val recipientPublicKey = keyFactory.generatePublic(recipientPublicKeySpec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(ephemeralPrivateKey)
        keyAgreement.doPhase(recipientPublicKey, true)
        val rawSharedSecret = keyAgreement.generateSecret()

        // Derive encryption key using HKDF
        val encryptionKey = HKDF.deriveKey(
            ikm = rawSharedSecret,
            salt = null,
            info = ECIES_INFO,
            length = 32
        )

        // Generate random nonce
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        // Encrypt with AES-GCM
        val secretKey: SecretKey = SecretKeySpec(encryptionKey, "AES")
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext)

        // Encode components
        val ephemeralPubB64 = ephemeralKeyPair.publicKey
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)
        val ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext)

        return "ECIES:$ephemeralPubB64:$nonceB64:$ciphertextB64"
    }

    /**
     * Decrypt ECIES encrypted data.
     *
     * @param ourPrivateKeyBase64 Our private key (Base64)
     * @param eciesBlob The ECIES encrypted blob
     * @return Decrypted plaintext, or null on failure
     */
    fun decryptECIES(ourPrivateKeyBase64: String, eciesBlob: String): ByteArray? {
        if (!eciesBlob.startsWith("ECIES:")) {
            android.util.Log.e(TAG, "Invalid ECIES blob format")
            return null
        }

        return try {
            val parts = eciesBlob.removePrefix("ECIES:").split(":")
            if (parts.size != 3) {
                android.util.Log.e(TAG, "Invalid ECIES blob: expected 3 parts, got ${parts.size}")
                return null
            }

            val ephemeralPubKeyB64 = parts[0]
            val nonceB64 = parts[1]
            val ciphertextB64 = parts[2]

            // Compute shared secret using ECDH
            val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)

            val ourPrivateKeyBytes = Base64.getDecoder().decode(ourPrivateKeyBase64)
            val ourPrivateKeySpec = java.security.spec.PKCS8EncodedKeySpec(ourPrivateKeyBytes)
            val ourPrivateKey = keyFactory.generatePrivate(ourPrivateKeySpec)

            val ephemeralPubKeyBytes = Base64.getDecoder().decode(ephemeralPubKeyB64)
            val ephemeralPubKeySpec = java.security.spec.X509EncodedKeySpec(ephemeralPubKeyBytes)
            val ephemeralPubKey = keyFactory.generatePublic(ephemeralPubKeySpec)

            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(ourPrivateKey)
            keyAgreement.doPhase(ephemeralPubKey, true)
            val rawSharedSecret = keyAgreement.generateSecret()

            // Derive encryption key using HKDF
            val encryptionKey = HKDF.deriveKey(
                ikm = rawSharedSecret,
                salt = null,
                info = ECIES_INFO,
                length = 32
            )

            // Decrypt with AES-GCM
            val nonce = Base64.getDecoder().decode(nonceB64)
            val ciphertext = Base64.getDecoder().decode(ciphertextB64)

            val secretKey: SecretKey = SecretKeySpec(encryptionKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            android.util.Log.e("ZCHAT_E2E", "ECIES decrypt FAILED: err=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Decrypt ECIES encrypted data, returning a Result type.
     *
     * This is the preferred version - uses explicit error handling via CryptoResult.
     *
     * @param ourPrivateKeyBase64 Our private key (Base64)
     * @param eciesBlob The ECIES encrypted blob
     * @return CryptoResult with decrypted bytes on success, or EciesFailed error on failure
     */
    fun decryptECIESWithResult(ourPrivateKeyBase64: String, eciesBlob: String): CryptoResult<ByteArray> {
        if (!eciesBlob.startsWith("ECIES:")) {
            return ZchatResult.failure(ZchatError.Crypto.EciesFailed("Invalid ECIES blob format"))
        }

        return try {
            val parts = eciesBlob.removePrefix("ECIES:").split(":")
            if (parts.size != 3) {
                return ZchatResult.failure(ZchatError.Crypto.EciesFailed("Invalid ECIES blob: expected 3 parts, got ${parts.size}"))
            }

            val ephemeralPubKeyB64 = parts[0]
            val nonceB64 = parts[1]
            val ciphertextB64 = parts[2]

            // Compute shared secret using ECDH
            val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)

            val ourPrivateKeyBytes = Base64.getDecoder().decode(ourPrivateKeyBase64)
            val ourPrivateKeySpec = java.security.spec.PKCS8EncodedKeySpec(ourPrivateKeyBytes)
            val ourPrivateKey = keyFactory.generatePrivate(ourPrivateKeySpec)

            val ephemeralPubKeyBytes = Base64.getDecoder().decode(ephemeralPubKeyB64)
            val ephemeralPubKeySpec = java.security.spec.X509EncodedKeySpec(ephemeralPubKeyBytes)
            val ephemeralPubKey = keyFactory.generatePublic(ephemeralPubKeySpec)

            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(ourPrivateKey)
            keyAgreement.doPhase(ephemeralPubKey, true)
            val rawSharedSecret = keyAgreement.generateSecret()

            // Derive encryption key using HKDF
            val encryptionKey = HKDF.deriveKey(
                ikm = rawSharedSecret,
                salt = null,
                info = ECIES_INFO,
                length = 32
            )

            // Decrypt with AES-GCM
            val nonce = Base64.getDecoder().decode(nonceB64)
            val ciphertext = Base64.getDecoder().decode(ciphertextB64)

            val secretKey: SecretKey = SecretKeySpec(encryptionKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            ZchatResult.success(cipher.doFinal(ciphertext))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ECIES decryption failed", e)
            ZchatResult.failure(ZchatError.Crypto.EciesFailed(e.message ?: "Unknown error"))
        }
    }

    /**
     * Encrypt a group key for a specific member using ECIES.
     *
     * @param memberPublicKey Member's E2E public key (Base64)
     * @param groupKey The group AES key (raw bytes)
     * @return ECIES encrypted blob
     */
    fun encryptGroupKeyForMember(memberPublicKey: String, groupKey: ByteArray): String {
        return encryptECIES(memberPublicKey, groupKey)
    }

    /**
     * Decrypt a group key from an invite using ECIES.
     *
     * @param ourPrivateKey Our E2E private key (Base64)
     * @param encryptedGroupKey The ECIES encrypted blob from invite
     * @return Decrypted group key (raw bytes), or null on failure
     */
    fun decryptGroupKeyFromInvite(ourPrivateKey: String, encryptedGroupKey: String): ByteArray? {
        return decryptECIES(ourPrivateKey, encryptedGroupKey)
    }

    // ==========================================
    // FILE ENCRYPTION (AES-256-GCM)
    // Used for encrypting file attachments
    // ==========================================

    // HKDF parameters for file key wrapping.
    // Domain separation here comes from the SALT ("ZCHAT_FILE_KEY_WRAP", globally unique); the INFO
    // value is secondary. Both are FROZEN WIRE CONSTANTS: the sender wraps and the receiver unwraps
    // with the identical bytes, so changing either value (e.g. "renaming" INFO to a longer string for
    // naming consistency) silently breaks file decryption between mismatched app versions for ZERO
    // security gain. Do NOT change without a versioned migration that accepts both old and new values.
    private val FILE_KEY_WRAP_SALT = "ZCHAT_FILE_KEY_WRAP".toByteArray(Charsets.UTF_8)
    private val FILE_KEY_WRAP_INFO = "WRAP".toByteArray(Charsets.UTF_8)

    /**
     * Generate a random 256-bit AES key for file encryption.
     *
     * @return 32-byte AES key
     */
    fun generateFileKey(): ByteArray {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_SIZE, SecureRandom())
        return generator.generateKey().encoded
    }

    /**
     * Encrypt file data using AES-256-GCM.
     * Output format: [12-byte IV][ciphertext+tag]
     *
     * @param plaintext Raw file bytes to encrypt
     * @param key 32-byte AES key (from [generateFileKey])
     * @return IV prepended to ciphertext
     */
    fun encryptFile(plaintext: ByteArray, key: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Decrypt file data encrypted with [encryptFile].
     * Expects format: [12-byte IV][ciphertext+tag]
     *
     * @param ciphertext IV-prepended ciphertext from [encryptFile]
     * @param key 32-byte AES key used during encryption
     * @return Decrypted file bytes
     */
    fun decryptFile(ciphertext: ByteArray, key: ByteArray, aad: ByteArray? = null): ByteArray {
        // Reject a too-short (truncated / maliciously tiny) ciphertext explicitly instead of letting
        // copyOfRange(0, NONCE_SIZE) throw an opaque IndexOutOfBoundsException. Mirrors the nonce-size
        // guard in the message-level decrypt(). A GCM tag also follows the nonce, so anything without
        // room for nonce + tag can't be valid.
        require(ciphertext.size >= NONCE_SIZE + (GCM_TAG_LENGTH / 8)) {
            "Encrypted file too small (${ciphertext.size} bytes) — truncated or corrupt"
        }
        val iv = ciphertext.copyOfRange(0, NONCE_SIZE)
        val data = ciphertext.copyOfRange(NONCE_SIZE, ciphertext.size)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    /**
     * Wrap (encrypt) a file key using a key derived from the shared secret.
     * Uses HKDF to derive a wrapping key, then AES-GCM to encrypt the file key.
     *
     * @param fileKey The file encryption key to wrap (from [generateFileKey])
     * @param sharedSecret The ECDH shared secret between sender and recipient
     * @param psk Optional pre-shared key for additional entropy (post-quantum layer)
     * @return Wrapped (encrypted) file key
     */
    fun wrapFileKey(
        fileKey: ByteArray,
        sharedSecret: ByteArray,
        psk: ByteArray? = null,
        aad: ByteArray? = null,
    ): ByteArray {
        val ikm = if (psk != null) sharedSecret + psk else sharedSecret
        val wrapKey = HKDF.deriveKey(
            ikm = ikm,
            salt = FILE_KEY_WRAP_SALT,
            info = FILE_KEY_WRAP_INFO,
            length = DERIVED_KEY_LENGTH
        )
        return encryptFile(fileKey, wrapKey, aad)
    }

    /**
     * Unwrap (decrypt) a file key using a key derived from the shared secret.
     * Inverse of [wrapFileKey].
     *
     * @param wrapped The wrapped file key from [wrapFileKey]
     * @param sharedSecret The ECDH shared secret between sender and recipient
     * @param psk Optional pre-shared key (must match the one used during wrapping)
     * @return Unwrapped file encryption key
     */
    fun unwrapFileKey(
        wrapped: ByteArray,
        sharedSecret: ByteArray,
        psk: ByteArray? = null,
        aad: ByteArray? = null,
    ): ByteArray {
        val ikm = if (psk != null) sharedSecret + psk else sharedSecret
        val wrapKey = HKDF.deriveKey(
            ikm = ikm,
            salt = FILE_KEY_WRAP_SALT,
            info = FILE_KEY_WRAP_INFO,
            length = DERIVED_KEY_LENGTH
        )
        return decryptFile(wrapped, wrapKey, aad)
    }
}

/**
 * Data class to hold a key pair.
 */
data class E2EKeyPair(
    val publicKey: String,  // Base64 encoded
    val privateKey: String  // Base64 encoded
)
