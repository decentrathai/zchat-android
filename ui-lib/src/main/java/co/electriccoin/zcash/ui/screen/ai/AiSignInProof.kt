package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption

/**
 * AI-KEY-D (owner W-52, W-53): the one message the AI sign-in key signs, and the one function that signs with it.
 *
 * Nine lines, UTF-8, each ended by one line feed:
 *
 *   ZCHAT-AI-AUTH-V1
 *   aud:<the service name>
 *   purpose:<register | signin | claim>
 *   nonce:<the server's one-time number: 43 base64url characters>
 *   exp:<the server's expiry, Unix seconds, exactly 10 digits>
 *   key:<the key id, 64 lower-case hex>
 *   account:<an account id, or ->
 *   wallet:<a 64-hex fingerprint, or -, or the word ticket>
 *   create:<1 or 0>
 *
 * The server never reads a message from a request: it builds the same nine lines from its own values and checks the
 * signature (SHA-256 with ECDSA, DER, Base64). A value outside its form is refused here, so that no field can add a
 * line or stand for another. The purpose rotate is reserved and refused by both sides. The same two fixed vectors
 * are pinned in AiSignInProofMessageTest and in the server's suite.
 *
 * Pure code: nothing is stored and no log line is written, and every refusal has a fixed text without the value.
 */
object AiSignInProof {
    private const val CONTEXT = "ZCHAT-AI-AUTH-V1"
    private const val NONE = "-"
    private const val EXP_FIRST_10_DIGITS = 1_000_000_000L
    private const val EXP_LAST_10_DIGITS = 9_999_999_999L
    private val PURPOSES = setOf("register", "signin", "claim")
    private val SERVICE_NAME_FORM = Regex("[A-Za-z0-9.:-]{1,253}")
    private val NONCE_FORM = Regex("[A-Za-z0-9_-]{43}")
    private val KEY_ID_FORM = Regex("[0-9a-f]{64}")
    private val ACCOUNT_FORM = Regex("[A-Za-z0-9_-]{1,64}")
    private val WALLET_FORM = Regex("[0-9a-f]{64}|-|ticket")

    /** The name of the service the proof is for: the second line of the message. */
    val serviceName: String
        get() = AiAuthEndpoint.serviceName

    /** The nine lines for these fields. Throws IllegalArgumentException, with a fixed text, for a value outside its form. */
    @Suppress("LongParameterList")
    fun message(
        purpose: String,
        nonce: String,
        expUnixSeconds: Long,
        keyId: String,
        account: String = NONE,
        wallet: String = NONE,
        create: Boolean = false,
    ): String {
        val service = serviceName
        require(SERVICE_NAME_FORM.matches(service)) { "the service name is not a host name" }
        require(purpose in PURPOSES) { "the purpose is not register, signin or claim" }
        require(NONCE_FORM.matches(nonce)) { "the one-time number is not 43 base64url characters" }
        require(expUnixSeconds in EXP_FIRST_10_DIGITS..EXP_LAST_10_DIGITS) { "the expiry is not 10 digits" }
        require(KEY_ID_FORM.matches(keyId)) { "the key id is not 64 lower-case hex characters" }
        require(ACCOUNT_FORM.matches(account)) { "the account id is outside its form" }
        require(WALLET_FORM.matches(wallet)) { "the wallet line is not a fingerprint, a dash or the word ticket" }
        return listOf(
            CONTEXT,
            "aud:" + service,
            "purpose:" + purpose,
            "nonce:" + nonce,
            "exp:" + expUnixSeconds.toString(),
            "key:" + keyId,
            "account:" + account,
            "wallet:" + wallet,
            "create:" + if (create) "1" else "0",
        ).joinToString(separator = "") { line -> line + "\n" }
    }

    /**
     * THE signing function of the AI sign-in key: it takes the fields, builds the message itself and signs it.
     * Nothing else may sign with this key, so it can never be made to sign a text somebody else chose.
     * Returns the signature as DER in Base64.
     */
    @Suppress("LongParameterList")
    fun sign(
        privateKeyBase64: String,
        purpose: String,
        nonce: String,
        expUnixSeconds: Long,
        keyId: String,
        account: String = NONE,
        wallet: String = NONE,
        create: Boolean = false,
    ): String =
        E2EEncryption.sign(
            privateKeyBase64,
            message(purpose, nonce, expUnixSeconds, keyId, account, wallet, create),
        )
}
