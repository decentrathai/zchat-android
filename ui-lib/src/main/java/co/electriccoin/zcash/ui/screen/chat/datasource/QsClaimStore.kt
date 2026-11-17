package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.crypto.QsClaim

/**
 * D3-2: the Quantum Shield claim records of one chat (t5-specs SPECS §2.2 "Store").
 *
 * - the peer's newest verified claim ([peerClaim]): the raw line, the E2E keys it was verified with and our local
 *   receive time;
 * - our own current claim ([ownClaim]): its seq and body (`state|commit|kc`), committed BEFORE it is published, so a
 *   seq is never reused for another body and never goes down;
 * - [sentSeq]: the seq of our newest claim a relay acknowledged (0 when none).
 *
 * The records live in the encrypted E2E store ([ZchatPreferences] `qs_claim_*`), so the wallet scope, clearAll and the
 * wallet copies carry them; Reset Encryption (`clearE2EKeys`) removes all of them. Nothing here is a secret: the
 * claim holds only one-way values.
 */
interface QsClaimStore {
    fun peerClaim(peerAddress: String): QsPeerClaim?

    /** Stores [record] as the peer's newest claim. The caller has verified it (ChatViewModel.recordQsPeerClaim). */
    fun storePeerClaim(
        peerAddress: String,
        record: QsPeerClaim
    ): Boolean

    fun ownClaim(peerAddress: String): QsOwnClaim?

    /** Commits our claim [body] under [seq]; refused (false) unless [seq] is above the stored one. */
    fun commitOwnClaim(
        peerAddress: String,
        seq: Long,
        body: String
    ): Boolean

    fun sentSeq(peerAddress: String): Long

    /** Records that a relay acknowledged our claim [seq]; never lowers the stored value. */
    fun markSent(
        peerAddress: String,
        seq: Long
    ): Boolean

    /** D3-3: the record of this chat's current setup, or null (none yet, or a shield set up before D3-3). */
    fun setupRecord(peerAddress: String): QsSetupRecord?

    /** D3-3: written by a completed new setup between its bound record and its key; removed with the key. */
    fun storeSetupRecord(
        peerAddress: String,
        record: QsSetupRecord
    ): Boolean
}

/**
 * D3-3: when this phone's shield was set up ([activeAt], local time) and the commitment of the QR it scanned
 * ([peerCommit]), stored as `<activeAt>|<peerCommit>` in `qs_claim_setup_`. The alerts compare the other phone's later
 * claims with it (a claim for another secret after our setup: the other phone set it up again).
 */
data class QsSetupRecord(
    val activeAt: Long,
    val peerCommit: String
) {
    fun encode(): String = "$activeAt|$peerCommit"

    companion object {
        fun decode(raw: String?): QsSetupRecord? {
            val at = raw?.indexOf('|') ?: return null
            if (at <= 0) return null
            val activeAt = raw.substring(0, at).toLongOrNull() ?: return null
            val commit = raw.substring(at + 1)
            return if (commit.isEmpty()) null else QsSetupRecord(activeAt, commit)
        }
    }
}

/** The peer's claim [line] as received, verified with [signerPub] (the peer's E2E key) for [otherPub] (ours). */
data class QsPeerClaim(
    val line: String,
    val signerPub: String,
    val otherPub: String,
    val rxAt: Long
) {
    /** The parsed claim, or null for a record that no longer parses. */
    val claim: QsClaim?
        get() = QsClaim.parse(line)

    /** The stored form: the line comes last, since it holds '|' itself (the base64 keys never do). */
    fun encode(): String = "$rxAt|$signerPub|$otherPub|$line"

    companion object {
        private const val PARTS = 4

        fun decode(raw: String?): QsPeerClaim? {
            val parts = raw?.split('|', limit = PARTS) ?: return null
            if (parts.size != PARTS) return null
            val rxAt = parts[0].toLongOrNull() ?: return null
            if (parts[1].isEmpty() || parts[2].isEmpty() || parts[3].isEmpty()) return null
            return QsPeerClaim(line = parts[3], signerPub = parts[1], otherPub = parts[2], rxAt = rxAt)
        }
    }
}

/** Our own current claim to one peer: its [seq] and [body] `state|commit|kc`. */
data class QsOwnClaim(
    val seq: Long,
    val body: String
) {
    fun encode(): String = "$seq|$body"

    companion object {
        fun decode(raw: String?): QsOwnClaim? {
            val at = raw?.indexOf('|') ?: return null
            if (at <= 0) return null
            val seq = raw.substring(0, at).toLongOrNull() ?: return null
            return QsOwnClaim(seq = seq, body = raw.substring(at + 1))
        }
    }
}

/**
 * [QsClaimStore] over the [ZchatPreferences] claim methods (the encrypted E2E store, one commit per write). Built by
 * ChatViewModel's init without touching the preferences: it only wraps the instance.
 */
class PrefsQsClaimStore(
    private val prefs: ZchatPreferences
) : QsClaimStore {
    override fun peerClaim(peerAddress: String): QsPeerClaim? = QsPeerClaim.decode(prefs.getQsClaimPeer(peerAddress))

    override fun storePeerClaim(
        peerAddress: String,
        record: QsPeerClaim
    ): Boolean = prefs.setQsClaimPeer(peerAddress, record.encode())

    override fun ownClaim(peerAddress: String): QsOwnClaim? = QsOwnClaim.decode(prefs.getQsClaimOurSeq(peerAddress))

    override fun commitOwnClaim(
        peerAddress: String,
        seq: Long,
        body: String
    ): Boolean =
        synchronized(LOCK) {
            val stored = ownClaim(peerAddress)
            if (stored != null && seq <= stored.seq) return false
            prefs.setQsClaimOurSeq(peerAddress, QsOwnClaim(seq, body).encode())
        }

    override fun sentSeq(peerAddress: String): Long = prefs.getQsClaimSentSeq(peerAddress)

    override fun markSent(
        peerAddress: String,
        seq: Long
    ): Boolean =
        synchronized(LOCK) {
            if (seq <= prefs.getQsClaimSentSeq(peerAddress)) return true
            prefs.setQsClaimSentSeq(peerAddress, seq)
        }

    override fun setupRecord(peerAddress: String): QsSetupRecord? = QsSetupRecord.decode(prefs.getQsClaimSetup(peerAddress))

    override fun storeSetupRecord(
        peerAddress: String,
        record: QsSetupRecord
    ): Boolean = prefs.setQsClaimSetup(peerAddress, record.encode())

    private companion object {
        /** One lock for every instance: the list and the detail screen each build their own store. */
        val LOCK = Any()
    }
}
