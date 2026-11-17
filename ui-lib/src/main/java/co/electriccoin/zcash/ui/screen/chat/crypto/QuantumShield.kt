package co.electriccoin.zcash.ui.screen.chat.crypto

import java.util.Base64
import java.security.MessageDigest
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
    private const val SAS_BYTES = 4
    private const val SAS_SPACE = 1_000_000L
    private val INFO = "zchat-quantum-shield-psk".toByteArray()
    private val SAS_INFO = "zchat-quantum-shield-sas".toByteArray()
    private val COMMIT_INFO = "zchat-qs-commit-v1".toByteArray(Charsets.UTF_8)
    private val KEYCHECK_INFO = "zchat-qs-keycheck-v1".toByteArray(Charsets.UTF_8)
    private const val CLAIM_VALUE_BYTES = 16
    private const val HEX_DIGITS = "0123456789abcdef"

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
     *
     * LEGACY (unbound) derivation — the one 2.12.4 uses. Kept for already-active shields whose stored
     * PSK was derived this way, and for every new setup with a 2.12.4 phone (R6-5, [deriveAgreedPSK]).
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
     * Derive a mutual PSK from two 32-byte secrets, BOUND to a canonical peer pair (B1l /
     * Grok MED-016). [bindingA]/[bindingB] are the two peers' stable identities (their Zcash
     * addresses); mixing them into the HKDF `info` scopes an observed secret to this exact
     * pair — a photographed QR can't be replayed into a different conversation.
     *
     * Order-independent across BOTH dimensions: secrets and bindings are each sorted
     * canonically, so Alice and Bob compute the identical PSK. Bindings are length-prefixed
     * to make the encoding unambiguous. The wire format ("ZCPSK:") is unchanged — the
     * binding uses identities both sides already hold locally. Already-active shields keep
     * their stored PSK (derived unbound); only NEW setups between two binding versions use
     * this bound derivation ([deriveAgreedPSK]).
     */
    fun derivePSK(
        secretA: ByteArray,
        secretB: ByteArray,
        bindingA: String,
        bindingB: String
    ): ByteArray {
        val (first, second) = orderSecrets(secretA, secretB)
        return HKDF.deriveKey(
            ikm = first + second,
            salt = null,
            info = INFO + encodeBindings(bindingA, bindingB),
            length = SECRET_LENGTH
        )
    }

    /**
     * Short human-verifiable Safety String (SAS) for an established shield: a 6-digit code
     * both devices display after the QR exchange. Users compare it out-of-band; a mismatch
     * reveals a tampered or swapped QR. Deterministic and binding-order independent.
     */
    fun computeSafetyString(psk: ByteArray, bindingA: String, bindingB: String): String {
        val okm = HKDF.deriveKey(
            ikm = psk,
            salt = null,
            info = SAS_INFO + encodeBindings(bindingA, bindingB),
            length = SAS_BYTES
        )
        var value = 0L
        for (b in okm) value = (value shl 8) or (b.toLong() and 0xFF)
        return String.format(java.util.Locale.ROOT, "%06d", value % SAS_SPACE)
    }

    /**
     * D3: a one-way commitment to a shield secret, sent in a [QsClaim] so the other phone can tell which QR code this
     * phone's shield was set up with, without seeing the secret: the first 16 bytes of
     * SHA-256("zchat-qs-commit-v1" || secret), as 32 lowercase hex characters. It is taken over the secret's bytes,
     * so the padded and unpadded base64 forms of one secret give the same commitment.
     */
    fun commitment(secret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(COMMIT_INFO)
        digest.update(secret)
        return toHex(digest.digest().copyOf(CLAIM_VALUE_BYTES))
    }

    /**
     * D3: a key check both phones of one shield compute the same way, sent in a [QsClaim] so they can learn whether
     * they hold the same key without revealing it: HKDF-SHA256(ikm = psk, salt = null (32 zero bytes),
     * info = "zchat-qs-keycheck-v1" || the same canonical bindings as the PSK and the security code, 16 bytes), as
     * 32 lowercase hex characters. Binding-order independent; its own info string keeps it apart from the PSK and the
     * code.
     */
    fun keyCheck(
        psk: ByteArray,
        bindingA: String,
        bindingB: String
    ): String =
        toHex(
            HKDF.deriveKey(
                ikm = psk,
                salt = null,
                info = KEYCHECK_INFO + encodeBindings(bindingA, bindingB),
                length = CLAIM_VALUE_BYTES
            )
        )

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * Canonical, unambiguous encoding of the two peer identities: UTF-8 bytes, sorted with
     * the same unsigned-lexicographic rule as the secrets, each length-prefixed (4 bytes,
     * big-endian) so ("ab","c") can never collide with ("a","bc").
     */
    private fun encodeBindings(bindingA: String, bindingB: String): ByteArray {
        val a = bindingA.toByteArray(Charsets.UTF_8)
        val b = bindingB.toByteArray(Charsets.UTF_8)
        val (first, second) = orderSecrets(a, b)
        return lengthPrefixed(first) + lengthPrefixed(second)
    }

    private fun lengthPrefixed(bytes: ByteArray): ByteArray =
        byteArrayOf(
            ((bytes.size ushr 24) and 0xFF).toByte(),
            ((bytes.size ushr 16) and 0xFF).toByte(),
            ((bytes.size ushr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte()
        ) + bytes

    /**
     * Encode a secret as a QR-scannable payload string.
     * Format: "ZCPSK:<base64-no-wrap>"
     *
     * R6-5: the base64 is written WITHOUT '=' padding when [boundCapable] (every secret 2.13.0 makes) and
     * WITH it otherwise. That padding is the only thing that tells the scanning phone which derivation its
     * peer will use, see [deriveAgreedPSK]. 2.12.4 always writes padding and decodes with
     * java.util.Base64.getDecoder(), which takes the unpadded form as the same 32 bytes, so a 2.12.4 phone
     * still reads a 2.13.0 QR.
     */
    fun toQRPayload(
        secret: ByteArray,
        boundCapable: Boolean = true
    ): String = "$QR_PREFIX${encodeSecret(secret, boundCapable)}"

    /**
     * The base64 form of our secret as it is stored and shown in our QR. Unpadded = made by a version that
     * binds the key to the two identities (2.13.0 and later); padded = made by 2.12.4 or earlier, whose QR a
     * peer may already have scanned as a 2.12.4 QR. Storing the secret in this form keeps that fact for a
     * phone updated in the middle of a setup.
     */
    fun encodeSecret(secret: ByteArray, boundCapable: Boolean = true): String =
        if (boundCapable) {
            Base64.getEncoder().withoutPadding().encodeToString(secret)
        } else {
            Base64.getEncoder().encodeToString(secret)
        }

    /** Whether a stored secret or a QR's base64 comes from a version that binds the key (no '=' padding). */
    fun isBoundCapable(encoded: String): Boolean = !encoded.endsWith("=")

    /**
     * D3-3 scan rule (t5-specs SPECS §2.2): whether a new setup may use [peer], given the other phone's newest verified
     * claim [claim] (null when there is none). Only a padded QR can be refused, and only against a claim: a padded QR
     * means "2.12.4 made this" and makes this phone derive the key with no security code, so a padded copy of a current
     * phone's QR would silently remove the code. Accepted: no claim; an unpadded QR; a padded QR whose secret is the one
     * a `p` claim commits to (the other phone's own pre-update secret). Refused: a padded QR against a `u` or `n` claim,
     * or against a `p` claim for another secret. A D3 build never makes a padded secret, and 2.12.4 sends no claim, so a
     * genuine QR is never refused.
     */
    fun acceptsScan(
        claim: QsClaim?,
        peer: PeerQr,
    ): Boolean =
        claim == null ||
            peer.boundCapable ||
            (claim.state == QsClaim.STATE_PADDED && claim.commit == commitment(peer.secret))

    /** What this phone knows about its own shield when it reads the other phone's claim ([alertFor]). */
    class OwnShield(
        /** Our key check ([keyCheck] of our stored key), or null when our shield is not on. */
        val keyCheck: String?,
        /** Our bound record: true bound key, false the 2.12.4 key, null not recorded (set up before the record). */
        val bound: Boolean?,
        /** Whether our own secret is unpadded (a `u` claim). */
        val secretUnpadded: Boolean,
        /** The local time our shield was set up (from the setup record; 0 without one). */
        val activeAt: Long,
        /** The commitment of the QR we scanned at setup, or null without a setup record. */
        val setupPeerCommit: String?,
    )

    /** Rules (b) and (c) ignore a claim the peer made more than this long before our setup (it arrived late). */
    const val CLAIM_BEFORE_SETUP_SLACK_MS: Long = 10 * 60 * 1000L

    /**
     * D3-3 alerts (t5-specs SPECS §2.2 "Alerts"): what an active shield shows about the other phone's newest verified
     * claim [claim], received at [rxAt]. NONE unless our shield is on and there is a claim. KEYS_DIFFER when (a) the
     * claim has a key check and it differs from ours, (b) the claim says `n` and arrived after our setup, or (c) it has
     * no key check, says `u`/`p`, commits to another secret than the one we scanned and arrived after our setup; for (b)
     * and (c) the claim must be made no earlier than [CLAIM_BEFORE_SETUP_SLACK_MS] before our setup, and (c) needs the
     * setup record. DOWNGRADED when the key checks are equal, our key is the 2.12.4 one and both secrets are unpadded
     * (both phones could have had a code). CONFIRMED when the key checks are equal otherwise. Pure; never sticky.
     */
    @Suppress("ReturnCount")
    fun alertFor(
        own: OwnShield,
        claim: QsClaim?,
        rxAt: Long,
    ): QsAlert {
        val ourKc = own.keyCheck ?: return QsAlert.NONE
        if (claim == null) return QsAlert.NONE
        if (claim.kc.isNotEmpty() && claim.kc != ourKc) return QsAlert.KEYS_DIFFER
        val madeForThisSetup = claim.seq >= own.activeAt - CLAIM_BEFORE_SETUP_SLACK_MS && rxAt > own.activeAt
        if (claim.state == QsClaim.STATE_NONE && madeForThisSetup) return QsAlert.KEYS_DIFFER
        if (claim.kc.isEmpty() &&
            claim.state != QsClaim.STATE_NONE &&
            own.setupPeerCommit != null &&
            claim.commit != own.setupPeerCommit &&
            madeForThisSetup
        ) {
            return QsAlert.KEYS_DIFFER
        }
        if (claim.kc != ourKc) return QsAlert.NONE
        val downgraded = own.bound == false && own.secretUnpadded && claim.state == QsClaim.STATE_UNPADDED
        return if (downgraded) QsAlert.DOWNGRADED else QsAlert.CONFIRMED
    }

    /** A scanned peer QR: the peer's secret, and whether the peer's version binds the key. */
    class PeerQr(
        val secret: ByteArray,
        val boundCapable: Boolean
    )

    /** [fromQRPayload] plus which derivation the peer will use. Null when the payload is not a valid QR. */
    fun parsePeerQr(payload: String): PeerQr? {
        val secret = fromQRPayload(payload) ?: return null
        return PeerQr(secret, isBoundCapable(payload.removePrefix(QR_PREFIX)))
    }

    /**
     * The key BOTH phones of one setup derive (R6-5). 2.12.4 always uses the unbound [derivePSK] and cannot
     * be updated; a 2.13.0 phone uses the identity-bound one (B1l) only when its own secret AND the peer's
     * QR both come from a binding version. Each phone knows its own secret's form and sees the other's in
     * the QR it scans, so both phones reach the same answer:
     *  - 2.13.0 with 2.13.0: bound on both;
     *  - 2.13.0 with 2.12.4: the 2.12.4 QR is padded, so the 2.13.0 phone uses the unbound key 2.12.4 uses;
     *  - a phone updated in the middle of a setup keeps its padded secret, so both phones use the unbound key.
     */
    fun deriveAgreedPSK(
        ourSecret: ByteArray,
        ourSecretBoundCapable: Boolean,
        peer: PeerQr,
        ourBinding: String,
        peerBinding: String,
    ): ByteArray =
        if (usesBoundKey(ourSecretBoundCapable, peer.boundCapable)) {
            derivePSK(ourSecret, peer.secret, ourBinding, peerBinding)
        } else {
            derivePSK(ourSecret, peer.secret)
        }

    /** True when both sides of a setup bind the key, so both can show and compare a security code. */
    fun usesBoundKey(ourSecretBoundCapable: Boolean, peerBoundCapable: Boolean): Boolean =
        ourSecretBoundCapable && peerBoundCapable

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

/**
 * What a phone shows after a Quantum Shield scan (R6-5): the 6-digit [code] both phones show when the key is
 * bound to the two identities, or null when this setup has no code to compare (the other phone runs 2.12.4,
 * or one phone started the setup before it was updated). The shield is on either way.
 *
 * F11: [problem] is set when the scan was not accepted for a reason the user must be told (then [code] is null).
 * Nothing else may be added here: tests find the code by its six digits in this object's text (qs-downgrade
 * REVIEW break 6), so any later state (a D3 claim) lives in its own holder.
 */
data class QuantumShieldConfirmation(
    val code: String?,
    val problem: QsProblem? = null
)

/** F11: why a Quantum Shield scan was not accepted. */
enum class QsProblem {
    /**
     * The shield is already on, and the scanned QR is readable but matches neither key this phone could have agreed
     * with its stored secret: another chat's code, or the other phone set Extra Security up again, so the two phones
     * now hold different keys. Without a signed statement from the other phone this phone cannot tell which, so the
     * text names both and blames no one (qs-downgrade REVIEW break 7). Nothing is stored.
     */
    NOT_THIS_CHATS_CODE,

    /**
     * D3-3: a new setup scanned a QR in the older (padded, 2.12.4) format, but the other phone said in a verified claim
     * that its own code is not one ([QuantumShield.acceptsScan]): an old picture, another chat's code, or a code changed
     * on the way. Using it would silently leave the setup without a security code, so nothing is set up.
     */
    QR_OLD_FORMAT,

    /**
     * D3-3: the shield is on, and the rescanned QR matches neither key, but it is exactly the secret the other phone's
     * newest verified claim commits to: the other phone set Extra Security up again, and the two phones now hold
     * different keys. Nothing is stored. (No screen offers a rescan of an active shield in 2.13.0; kept for when one does.)
     */
    OTHER_PHONE_SET_UP_AGAIN,
}

/**
 * D3-3: what an active shield shows about the other phone's newest verified claim ([QuantumShield.alertFor]). Kept in
 * its own holder (ChatViewModel.quantumShieldAlerts), never in [QuantumShieldConfirmation].
 */
enum class QsAlert {
    /** Nothing to show: no active shield, no verified claim, or a claim that says nothing about this setup yet. */
    NONE,

    /** The other phone's claim shows a different key (or none): messages may not open until both set it up again. */
    KEYS_DIFFER,

    /** Both phones could show a code, but each read the other's QR in the older format: the setup has no code. */
    DOWNGRADED,

    /** The other phone's key check equals ours: both phones hold the same key (as strong as the chat's E2E key). */
    CONFIRMED,
}
