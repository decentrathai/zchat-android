package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.crypto.E2ESeedDerivation
import co.electriccoin.zcash.ui.screen.chat.crypto.HKDF
import java.security.MessageDigest
import java.util.Base64

/**
 * AI-KEY-D (owner W-52, W-53): the AI sign-in key, made from the seed of the wallet's recovery phrase, so that an
 * AI account and its paid credit come back with the phrase.
 *
 * FROZEN CONSTRUCTION, pinned by AiSignInKeyDerivationVectorsTest and by the server's suite:
 *
 *   okm = HKDF-SHA256(ikm = seed, salt = the salt (below), info = the label (below), length = 48)
 *   d   = (OS2IP(okm) mod (n - 1)) + 1 on P-256, Q = d * G
 *   public half = Base64 of the 91-byte X.509 SubjectPublicKeyInfo (named curve, uncompressed point)
 *   key id      = lower-case hex of the SHA-256 of those 91 bytes
 *
 * It is a key of its own and never a spending key: its salt and its label are used nowhere else, so it is none of
 * the wallet's, the chats' or the relays' keys, and from its public half nobody gets to the seed or to another key.
 * It signs one thing only, the message of [AiSignInProof].
 *
 * Pure code: it stores nothing, writes no log line and does not change the seed bytes it is given; their owner
 * wipes them. The private half is returned as an unchangeable string that cannot be wiped: it lives until the
 * runtime frees it, as the chat keys' private halves do. The arithmetic is the chat keys' and is not constant-time;
 * the trade-off accepted there (E2ESeedDerivation) holds here as well.
 */
object AiSignInKey {
    private const val OKM_LENGTH = 48
    private const val PUBLIC_KEY_BYTES = 91

    // FROZEN. A changed byte gives every user another key, and their AI account and paid credit are not found.
    private val SALT = "ZCHAT_AI_SIGNIN_SEED".toByteArray(Charsets.UTF_8)

    // FROZEN from the first release on, and for this service only.
    // - Another service needs another label: with this one it would see the same public key for the same recovery
    //   phrase and could recognise the user by it.
    // - A later version of this label never replaces it: the app must keep deriving and comparing every earlier
    //   one, because an account that was linked under an earlier label is found by that label's key alone.
    private val LABEL = "zchat-ai-signin-v1:zsend.xyz".toByteArray(Charsets.UTF_8)

    /**
     * The sign-in key of [seed] (the 64-byte BIP-39 seed of the saved recovery phrase). The same seed always gives
     * the same key. [seed] is read only; the caller wipes it.
     */
    fun derive(seed: ByteArray): AiSignInKeyPair {
        require(seed.isNotEmpty()) { "the seed must not be empty" }
        val okm = HKDF.deriveKey(ikm = seed, salt = SALT, info = LABEL, length = OKM_LENGTH)
        try {
            val halves = E2ESeedDerivation.keyPairForScalar(E2ESeedDerivation.reduceToScalar(okm))
            return AiSignInKeyPair(publicKey = halves.publicKey, privateKey = halves.privateKey)
        } finally {
            okm.fill(0)
        }
    }

    /** The key id the server stores: lower-case hex of the SHA-256 of the public half's 91 DER bytes. */
    fun keyId(publicKeyBase64: String): String {
        val der = Base64.getDecoder().decode(publicKeyBase64)
        require(der.size == PUBLIC_KEY_BYTES) { "the public key is not a 91-byte P-256 key" }
        return MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * ZC-956: the two halves of the AI sign-in key, both Base64 (the public half as in [AiSignInKey], the private half
 * as PKCS#8), in a type of its own that never shows them.
 *
 * It is no `data class` on purpose. Its text is one fixed word for every key, so a line that writes the pair
 * somewhere, a string built with it or a failed comparison of two pairs cannot carry a half. It has no `copy` and
 * no parts to take it apart by, and two pairs are equal only when they are the same object. Only
 * [AiSignInKey.derive] makes one. AiSignInKeyNeverPrintsItsPrivateHalfTest holds all of this, and that no file of
 * the app puts a pair or its private half into a text.
 */
class AiSignInKeyPair internal constructor(
    val publicKey: String,
    val privateKey: String,
) {
    override fun toString(): String = "AiSignInKeyPair(redacted)"
}
