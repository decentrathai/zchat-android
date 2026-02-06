package co.electriccoin.zcash.ui.screen.chat.crypto

import android.util.Base64
import co.electriccoin.zcash.ui.common.result.CryptoResult
import co.electriccoin.zcash.ui.common.result.ZchatError
import co.electriccoin.zcash.ui.common.result.ZchatResult
import co.electriccoin.zcash.ui.common.util.redactAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
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

        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privateKeyBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

        return E2EKeyPair(publicKeyBase64, privateKeyBase64)
    }

    /**
     * Derive a shared secret from our private key and peer's public key.
     * Uses ECDH key agreement with version-aware key derivation.
     *
     * @param ourPrivateKeyBase64 Our private key (Base64)
     * @param peerPublicKeyBase64 Peer's public key (Base64)
     * @param version Key derivation version (V1 for legacy, V2 for HKDF)
     * @return Derived encryption key (256-bit)
     */
    fun deriveSharedSecret(
        ourPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        version: E2EKeyVersion = E2EKeyVersion.V2
    ): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)

        // Decode our private key
        val privateKeyBytes = Base64.decode(ourPrivateKeyBase64, Base64.NO_WRAP)
        val privateKeySpec = java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes)
        val privateKey = keyFactory.generatePrivate(privateKeySpec)

        // Decode peer's public key
        val publicKeyBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
        val publicKey = keyFactory.generatePublic(publicKeySpec)

        // Perform key agreement
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)

        // Derive 256-bit key using version-specific derivation
        val rawSharedSecret = keyAgreement.generateSecret()
        return deriveKey(rawSharedSecret, version)
    }

    /**
     * Derive a 256-bit AES key from shared secret.
     * Uses version-aware derivation:
     * - V1: Legacy SHA-256 (weak, for backwards compatibility)
     * - V2: HKDF (RFC 5869) with proper salt and info
     */
    private fun deriveKey(sharedSecret: ByteArray, version: E2EKeyVersion): ByteArray {
        return when (version) {
            E2EKeyVersion.V1 -> deriveKeyV1(sharedSecret)
            E2EKeyVersion.V2 -> deriveKeyV2(sharedSecret)
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
     * - Extract: HMAC-SHA256(salt="ZCHAT_E2E_SALT_V2", IKM=sharedSecret)
     * - Expand: HMAC-SHA256(PRK, info="ZCHAT_E2E_KEY" || 0x01)
     */
    private fun deriveKeyV2(sharedSecret: ByteArray): ByteArray {
        return HKDF.deriveKey(
            ikm = sharedSecret,
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
        val nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

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

        return try {
            val parts = encryptedMessage.removePrefix(E2E_PREFIX).split(":")
            if (parts.size != 2) {
                android.util.Log.w("ZCHAT_E2E", "decrypt: invalid format, parts=${parts.size}")
                return null
            }

            val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val secretKey: SecretKey = SecretKeySpec(sharedKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ZCHAT_E2E", "decrypt FAILED: keyLen=${sharedKey.size} msgLen=${encryptedMessage.length} err=${e.message}")
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

        return try {
            val parts = encryptedMessage.removePrefix(E2E_PREFIX).split(":")
            if (parts.size != 2) {
                return ZchatResult.failure(ZchatError.Crypto.DecryptionFailed("Invalid E2E format"))
            }

            val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

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
        val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
        val privateKeySpec = java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes)
        val privateKey = keyFactory.generatePrivate(privateKeySpec)

        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(message.toByteArray(Charsets.UTF_8))
        val signatureBytes = signature.sign()

        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
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
            val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
            val publicKey = keyFactory.generatePublic(publicKeySpec)

            val signature = java.security.Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(message.toByteArray(Charsets.UTF_8))

            val signatureBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    /**
     * Create a KEX (Key Exchange) payload with signature.
     * Format: KEX:<pubkey_b64>:<sig_b64>
     *
     * The signature is over (senderAddress + pubkey) to bind the key to the address.
     *
     * @param senderAddress The sender's Zcash address
     * @param publicKey Our public key (Base64)
     * @param privateKey Our private key (Base64) - used for signing
     * @return KEX payload string
     */
    fun createKEXPayload(senderAddress: String, publicKey: String, privateKey: String): String {
        // Sign: SHA256(address || pubkey)
        val messageToSign = senderAddress + publicKey
        val signature = sign(privateKey, messageToSign)
        return "KEX:$publicKey:$signature"
    }

    /**
     * Parse a KEX payload and verify the signature.
     *
     * @param payload The KEX payload string
     * @param senderAddress The sender's Zcash address (from transaction)
     * @return The verified public key, or null if verification fails
     */
    fun parseKEXPayload(payload: String, senderAddress: String): String? {
        if (!payload.startsWith("KEX:")) {
            return null
        }

        val parts = payload.removePrefix("KEX:").split(":", limit = 2)
        if (parts.size != 2) {
            android.util.Log.e(TAG, "Invalid KEX payload format")
            return null
        }

        val publicKey = parts[0]
        val signature = parts[1]

        // Verify: signature over (address || pubkey)
        val messageToVerify = senderAddress + publicKey
        if (!verify(publicKey, messageToVerify, signature)) {
            android.util.Log.e(TAG, "KEX signature verification failed for ${senderAddress.redactAddress()}")
            return null
        }

        return publicKey
    }

    /**
     * Create a KEX acknowledgment payload.
     * Sent after receiving and verifying a KEX message.
     * Format: KEXACK:<our_pubkey_b64>:<sig_b64>
     */
    fun createKEXAckPayload(senderAddress: String, publicKey: String, privateKey: String): String {
        val messageToSign = senderAddress + publicKey
        val signature = sign(privateKey, messageToSign)
        return "KEXACK:$publicKey:$signature"
    }

    /**
     * Parse a KEX acknowledgment payload.
     */
    fun parseKEXAckPayload(payload: String, senderAddress: String): String? {
        if (!payload.startsWith("KEXACK:")) {
            return null
        }

        val parts = payload.removePrefix("KEXACK:").split(":", limit = 2)
        if (parts.size != 2) {
            android.util.Log.e(TAG, "Invalid KEXACK payload format")
            return null
        }

        val publicKey = parts[0]
        val signature = parts[1]

        val messageToVerify = senderAddress + publicKey
        if (!verify(publicKey, messageToVerify, signature)) {
            android.util.Log.e(TAG, "KEXACK signature verification failed for ${senderAddress.redactAddress()}")
            return null
        }

        return publicKey
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

        val ephemeralPrivateKeyBytes = Base64.decode(ephemeralKeyPair.privateKey, Base64.NO_WRAP)
        val ephemeralPrivateKeySpec = java.security.spec.PKCS8EncodedKeySpec(ephemeralPrivateKeyBytes)
        val ephemeralPrivateKey = keyFactory.generatePrivate(ephemeralPrivateKeySpec)

        val recipientPublicKeyBytes = Base64.decode(recipientPublicKeyBase64, Base64.NO_WRAP)
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
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

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

            val ourPrivateKeyBytes = Base64.decode(ourPrivateKeyBase64, Base64.NO_WRAP)
            val ourPrivateKeySpec = java.security.spec.PKCS8EncodedKeySpec(ourPrivateKeyBytes)
            val ourPrivateKey = keyFactory.generatePrivate(ourPrivateKeySpec)

            val ephemeralPubKeyBytes = Base64.decode(ephemeralPubKeyB64, Base64.NO_WRAP)
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
            val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)

            val secretKey: SecretKey = SecretKeySpec(encryptionKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            android.util.Log.e("ZCHAT_E2E", "ECIES decrypt FAILED: blobLen=${eciesBlob.length} err=${e.message}")
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

            val ourPrivateKeyBytes = Base64.decode(ourPrivateKeyBase64, Base64.NO_WRAP)
            val ourPrivateKeySpec = java.security.spec.PKCS8EncodedKeySpec(ourPrivateKeyBytes)
            val ourPrivateKey = keyFactory.generatePrivate(ourPrivateKeySpec)

            val ephemeralPubKeyBytes = Base64.decode(ephemeralPubKeyB64, Base64.NO_WRAP)
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
            val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)

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
}

/**
 * Data class to hold a key pair.
 */
data class E2EKeyPair(
    val publicKey: String,  // Base64 encoded
    val privateKey: String  // Base64 encoded
)
