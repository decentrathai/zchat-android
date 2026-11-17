package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import java.util.Base64

/**
 * D3: a phone's signed statement about its Quantum Shield for one chat, sent to the other phone as the content of one
 * relay DM (never on chain, never paid for):
 *
 * ```
 * ZRCPT|zqs1|<state>|<commit>|<kc>|<seq>|<sig>        exactly 7 '|'-separated parts
 * ```
 *
 * - [state]: [STATE_NONE] "n" (no secret here), [STATE_UNPADDED] "u" (our secret is unpadded: made by a binding
 *   build) or [STATE_PADDED] "p" (our secret is padded: made by 2.12.4 before this phone was updated);
 * - [commit]: [QuantumShield.commitment] of our secret, "" exactly when the state is "n";
 * - [kc]: [QuantumShield.keyCheck] of our PSK, "" unless our shield is on;
 * - [seq]: per peer, strictly increasing ([nextSeq]); a replayed older claim cannot roll the state back;
 * - [sig]: [E2EEncryption.sign] with this chat's E2E private key over [signedString], standard base64.
 *
 * The `ZRCPT|` prefix is [ZMSGConstants.Prefixes.RECEIPT] on purpose: every ZChat build drops it on the relay path
 * without a row or an alert (2.12.4 included, v2.12.4 ChatViewModel.kt:692).
 *
 * The line carries only one-way values (a 128-bit commitment and a 128-bit key check of 256-bit values): never the
 * secret, the PSK or the security code. Honest limit (qs-downgrade REVIEW break 1): it is signed with the per-chat E2E
 * key, so whoever already replaced that key can forge or suppress it.
 *
 * Pure: this file sends, stores and shows nothing (D3-1). D3-2 carries it; D3-3 acts on it.
 */
data class QsClaim(
    val state: String,
    val commit: String,
    val kc: String,
    val seq: Long,
    val sig: String
) {
    /**
     * The exact text the signature covers. The signer's key comes first, so a phone's own claim reflected back to it
     * does not verify (it would be checked with the signer and the other key swapped). Unambiguous:
     * the keys are base64 (no '|') and every other field is checked by [isWellFormed].
     */
    fun signedString(
        signerPub: String,
        otherPub: String
    ): String = "$SIGNED_PREFIX|$signerPub|$otherPub|$state|$commit|$kc|$seq"

    /** This claim with the signature of [privateKey] (this chat's E2E private key, whose public key is [signerPub]). */
    fun sign(
        privateKey: String,
        signerPub: String,
        otherPub: String
    ): QsClaim {
        require(isWellFormed(requireSig = false)) { "a malformed Quantum Shield claim is never signed" }
        require(isKey(signerPub) && isKey(otherPub)) { "a claim key must be non-empty and hold no '|'" }
        return copy(sig = E2EEncryption.sign(privateKey, signedString(signerPub, otherPub)))
    }

    /**
     * True only when this claim is well formed and its signature is [signerPub]'s over [signedString] for this exact
     * pair: another signer, the two keys swapped (a reflected claim), another addressee or any changed field fails.
     */
    fun verify(
        signerPub: String,
        otherPub: String
    ): Boolean {
        if (!isWellFormed(requireSig = true) || !isKey(signerPub) || !isKey(otherPub)) return false
        return E2EEncryption.verify(signerPub, signedString(signerPub, otherPub), sig)
    }

    /** The wire line. Only a well-formed, signed claim has one. */
    fun encode(): String {
        check(isWellFormed(requireSig = true)) { "a malformed or unsigned Quantum Shield claim is never sent" }
        return "$PREFIX$state|$commit|$kc|$seq|$sig"
    }

    private fun isWellFormed(requireSig: Boolean): Boolean {
        if (state != STATE_NONE && state != STATE_UNPADDED && state != STATE_PADDED) return false
        if (!isValue(commit) || !isValue(kc)) return false
        if ((state == STATE_NONE) != commit.isEmpty()) return false
        if (seq < 0) return false
        return if (requireSig) isSignature(sig) else sig.isEmpty() || isSignature(sig)
    }

    companion object {
        const val STATE_NONE = "n"
        const val STATE_UNPADDED = "u"
        const val STATE_PADDED = "p"

        /** The version tag after the receipt prefix. */
        private const val VERSION = "zqs1"
        const val PREFIX = "${ZMSGConstants.Prefixes.RECEIPT}$VERSION|"
        private const val SIGNED_PREFIX = "zchat-qs-claim-v1"
        private const val PARTS = 7
        private const val MAX_SEQ_DIGITS = 19
        private const val MAX_SIG_CHARS = 128
        private val VALUE = Regex("^[0-9a-f]{32}$")
        private val SEQ = Regex("^(0|[1-9][0-9]*)$")
        private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

        /**
         * The claim in [line], or null unless the line is exactly a well-formed claim: 7 parts, `ZRCPT|zqs1|`, a known
         * state, commit and kc each "" or 32 lowercase hex with the commit empty exactly for state "n", a canonical
         * decimal seq of at most 19 digits that fits a Long (no sign, no leading zero), and a standard base64 sig of at
         * most 128 characters. Parsing checks the shape only; [verify] checks who signed it.
         */
        fun parse(line: String): QsClaim? {
            if (!line.startsWith(PREFIX)) return null
            val parts = line.split('|')
            if (parts.size != PARTS) return null
            val seqText = parts[5]
            if (seqText.length > MAX_SEQ_DIGITS || !SEQ.matches(seqText)) return null
            val seq = seqText.toLongOrNull() ?: return null
            val claim = QsClaim(state = parts[2], commit = parts[3], kc = parts[4], seq = seq, sig = parts[6])
            return if (claim.isWellFormed(requireSig = true)) claim else null
        }

        /** The seq of our next claim to one peer: above the last one we signed, and never behind the clock. */
        fun nextSeq(
            last: Long,
            now: Long
        ): Long {
            val afterLast = if (last == Long.MAX_VALUE) last else last + 1
            return maxOf(afterLast, now, 0L)
        }

        private fun isValue(text: String): Boolean = text.isEmpty() || VALUE.matches(text)

        private fun isKey(text: String): Boolean = text.isNotEmpty() && '|' !in text

        @Suppress("SwallowedException")
        private fun isSignature(text: String): Boolean {
            if (text.isEmpty() || text.length > MAX_SIG_CHARS || text.length % 4 != 0) return false
            if (!BASE64.matches(text)) return false
            return try {
                Base64.getDecoder().decode(text).isNotEmpty()
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
