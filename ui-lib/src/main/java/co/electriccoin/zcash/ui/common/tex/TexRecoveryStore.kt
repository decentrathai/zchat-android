package co.electriccoin.zcash.ui.common.tex

import android.content.Context
import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules

/**
 * F3: the durable record of one TEX send's recovery, keyed by [TexPair.key] (a hash of the ephemeral address; the
 * address is never stored). It is what makes the automatic shield pay at most once:
 *  - [attempt] and [createStartTip] are written BEFORE a shield is created, so a process killed between the create
 *    and the record still knows a shield may exist (and until which height it could be mined);
 *  - [shieldTxIds] and [shieldExpiry] are written through F2's onStored, after the create and before the submit.
 * A second shield is built only when the one on record is proven expired (two passes, [TexRules.REORG_MARGIN]) and
 * a fresh lookup finds the ZEC still unspent.
 */
data class TexRecoveryRecord(
    val key: String,
    val attempt: Int = 0,
    val createStartTip: Long? = null,
    val shieldTxIds: List<String> = emptyList(),
    val shieldExpiry: Long? = null,
    val foundZat: Long? = null,
    val feeZat: Long? = null,
    val noticeShown: Boolean = false,
    /** The system notification about the finished recovery was posted (at most once). */
    val notified: Boolean = false,
    /** Found, but too little to move after the fee (said in two passes): support must look; no shield is built. */
    val needsSupport: Boolean = false,
) {
    /** A shield of this attempt may exist: its create started. */
    val shieldStarted: Boolean
        get() = createStartTip != null || shieldTxIds.isNotEmpty()

    /**
     * The last height the shield on record could be mined at: its recorded expiry, or else (ZC-114) the tip read before
     * its create, plus 1 (its target), plus the largest expiry delta the shipped SDK can use there, plus
     * [TexRules.UNKNOWN_EXPIRY_ROOM]: 50 blocks before NU7. The delta is read [SendAttemptRules.TIP_LEAD] blocks past
     * the target, as the SDK's tip may lead, so a create just before NU7 counts the larger delta (never a bound too short).
     */
    val shieldBound: Long?
        get() =
            shieldExpiry ?: createStartTip?.let { tip ->
                val target = tip + 1
                target + ChainTiming.maxExpiryDelta(target + SendAttemptRules.TIP_LEAD) + TexRules.UNKNOWN_EXPIRY_ROOM
            }
}

/** Where [TexRecoveryRecord]s live. Every write is a checked commit: a write that fails throws. */
interface TexRecoveryStore {
    fun get(key: String): TexRecoveryRecord?

    /** Throws when the record did not reach the disk. */
    fun put(record: TexRecoveryRecord)

    /** ZCHAT turned Tor on for a TEX check (the notice says so). */
    fun torTurnedOnByZchat(): Boolean

    /** Throws when the mark did not reach the disk. */
    fun markTorTurnedOnByZchat()

    /** F5: [sendAgainUsedHash] of every plain send whose "Send again" was used (hashes only, never a txid). */
    fun sendAgainUsedHashes(): Set<String> = emptySet()

    /** F5: records that "Send again" was used on the send whose [sendAgainUsedHash] is [hash]; throws when not written. */
    fun markSendAgainUsed(hash: String) {}
}

/** A store that keeps nothing across a restart (JVM tests). */
class InMemoryTexRecoveryStore : TexRecoveryStore {
    private val records = mutableMapOf<String, TexRecoveryRecord>()
    private var tor = false
    private val sendAgainUsed = mutableSetOf<String>()

    /** Every record written, in order (tests read the history of writes). */
    val writes: MutableList<TexRecoveryRecord> = mutableListOf()

    @Synchronized
    override fun get(key: String): TexRecoveryRecord? = records[key]

    @Synchronized
    override fun put(record: TexRecoveryRecord) {
        records[record.key] = record
        writes += record
    }

    @Synchronized
    override fun torTurnedOnByZchat(): Boolean = tor

    @Synchronized
    override fun markTorTurnedOnByZchat() {
        tor = true
    }

    @Synchronized
    override fun sendAgainUsedHashes(): Set<String> = sendAgainUsed.toSet()

    @Synchronized
    override fun markSendAgainUsed(hash: String) {
        sendAgainUsed += hash
    }
}

/** The phone's store: one private SharedPreferences file, written with commit() and checked. */
class SharedPrefsTexRecoveryStore(
    context: Context,
) : TexRecoveryStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): TexRecoveryRecord? = prefs.getString(RECORD_PREFIX + key, null)?.let { decodeTexRecord(key, it) }

    @Synchronized
    override fun put(record: TexRecoveryRecord) {
        check(prefs.edit().putString(RECORD_PREFIX + record.key, encodeTexRecord(record)).commit()) {
            "The TEX recovery record could not be written"
        }
    }

    @Synchronized
    override fun torTurnedOnByZchat(): Boolean = prefs.getBoolean(KEY_TOR_BY_ZCHAT, false)

    @Synchronized
    override fun markTorTurnedOnByZchat() {
        check(prefs.edit().putBoolean(KEY_TOR_BY_ZCHAT, true).commit()) { "The Tor mark could not be written" }
    }

    @Synchronized
    override fun sendAgainUsedHashes(): Set<String> = prefs.getStringSet(KEY_SEND_AGAIN_USED, null)?.toSet().orEmpty()

    @Synchronized
    override fun markSendAgainUsed(hash: String) {
        val used = sendAgainUsedHashes() + hash
        check(prefs.edit().putStringSet(KEY_SEND_AGAIN_USED, used).commit()) { "The Send again mark could not be written" }
    }

    private companion object {
        const val PREFS_NAME = "zchat_tex_recovery"
        const val RECORD_PREFIX = "rec:"
        const val KEY_TOR_BY_ZCHAT = "tor_on_by_zchat"

        // F5: SHA-256 hex of each plain send's txid whose "Send again" was used.
        const val KEY_SEND_AGAIN_USED = "send_again_used_v1"
    }
}

/** One line per field, `name=value`; txids joined by ','. No org.json, so the JVM tests read the same code. */
fun encodeTexRecord(record: TexRecoveryRecord): String =
    listOf(
        "v=1",
        "attempt=${record.attempt}",
        "createStartTip=${record.createStartTip ?: ""}",
        "shieldTxIds=${record.shieldTxIds.joinToString(",")}",
        "shieldExpiry=${record.shieldExpiry ?: ""}",
        "foundZat=${record.foundZat ?: ""}",
        "feeZat=${record.feeZat ?: ""}",
        "noticeShown=${record.noticeShown}",
        "notified=${record.notified}",
        "needsSupport=${record.needsSupport}",
    ).joinToString("\n")

/**
 * Reads [encodeTexRecord]'s text. A value that cannot be read keeps its safe default, except that a record whose
 * text cannot be read at all is still a record: [TexRecoveryRecord.createStartTip] is set to 0 so the monitor treats
 * it as "a shield may exist" rather than "nothing was ever sent".
 */
fun decodeTexRecord(
    key: String,
    text: String,
): TexRecoveryRecord {
    val fields =
        text
            .lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
    if (fields["v"] != "1") return TexRecoveryRecord(key = key, attempt = TexRules.MAX_SHIELD_ATTEMPTS, createStartTip = 0)
    return TexRecoveryRecord(
        key = key,
        attempt = fields["attempt"]?.toIntOrNull() ?: TexRules.MAX_SHIELD_ATTEMPTS,
        createStartTip = fields["createStartTip"]?.toLongOrNull(),
        shieldTxIds = fields["shieldTxIds"].orEmpty().split(',').filter { it.isNotBlank() },
        shieldExpiry = fields["shieldExpiry"]?.toLongOrNull(),
        foundZat = fields["foundZat"]?.toLongOrNull(),
        feeZat = fields["feeZat"]?.toLongOrNull(),
        noticeShown = fields["noticeShown"] == "true",
        notified = fields["notified"] == "true",
        needsSupport = fields["needsSupport"] == "true",
    )
}
