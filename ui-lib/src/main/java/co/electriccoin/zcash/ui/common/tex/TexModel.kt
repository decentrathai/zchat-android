package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import java.security.MessageDigest

/*
 * F3 (fix-all 2.13.0, train T3): the TEX checker and its fully automatic recovery.
 *
 * A payment to an exchange's TEX address (ZIP 320) is two transactions the SDK builds together: leg 1 moves the ZEC
 * from the shielded balance to a one-time ("ephemeral") transparent address of this wallet, leg 2 pays it from there
 * to the exchange. Both carry the same expiry height. If leg 1 is mined and leg 2 never is, the exchange was not paid
 * and the ZEC sits at the ephemeral address, where SDK 3.0.0 does not count it until the network has confirmed that it
 * is still unspent after leg 2's expiry (a Tor-only lookup, fetchUtxosByAddress). This package derives each TEX
 * send's state from the SDK's own database, and moves stuck ZEC back by itself.
 */

/** Where a TEX send stands. The first five are read from the chain; the rest are the recovery's own. */
enum class TexState {
    /** Leg 1 is not mined and could still be (the scanned tip is not 10 blocks past its expiry, twice). */
    SENDING_STEP1,

    /** Leg 1 is mined; leg 2 is not, and the scanned tip is not yet 2 blocks past its expiry. */
    SENDING_STEP2,

    /** Leg 2 is mined: the exchange was paid. */
    DONE,

    /** Leg 1 was never mined and can no longer be: nothing left the wallet. */
    NOT_SENT,

    /** Leg 1 is mined, leg 2 expired unmined: the ephemeral address is being looked up (over Tor). */
    CHECKING,

    /** As [CHECKING], but Tor is off; it is turned on as soon as nothing else is in progress. */
    NEEDS_TOR,

    /** The lookup found the ZEC unspent at the ephemeral address; it is shielded back as soon as possible. */
    RETURNED,

    /** The shield that moves the ZEC back is on durable record and not yet mined (nor proven expired). */
    SHIELDING,

    /** The shield is mined: the ZEC is back in the shielded balance. */
    SHIELDED,

    /** Found but too little to move after the fee, or the shield failed too often: a person must look. */
    NEEDS_SUPPORT,
}

/** The kind of address an output of a transaction pays. */
enum class TexAddressKind {
    TEX,
    TRANSPARENT,
    OTHER,
}

/**
 * One output of a transaction: its address, whether it pays the ZCHAT account itself ([isOwn]; a payment to another
 * account of the same wallet is not own, T3b-3), and its kind.
 */
data class TexOutput(
    val address: String,
    val isOwn: Boolean,
    val kind: TexAddressKind,
)

/**
 * What the checker reads of one transaction from the SDK database.
 *
 * @param txId the id as `txIdString()` prints it (the order every SubmitResult and StoredTransaction uses).
 * @param rawIdHex the id bytes as the wallet database holds them (`TransactionId.value`), in hex; a proposal names
 *   its inputs in this order, and so does a transaction's input (its outpoint).
 * @param rawTxHex the transaction as the wallet database stores it, in hex, when it is read (a sent transaction that
 *   may be a TEX leg); leg 2 names the outpoint of leg 1 it spends, which tells two sends of one block apart.
 */
data class TexTxFacts
    @JvmOverloads
    constructor(
        val txId: String,
        val rawIdHex: String,
        val isSent: Boolean,
        val isShielding: Boolean,
        val minedHeight: Long?,
        val expiryHeight: Long?,
        val outputs: List<TexOutput>,
        val rawTxHex: String? = null,
    ) {
        override fun toString(): String =
            "TexTxFacts(txId=${txId.take(TXID_LOG_CHARS)}…, sent=$isSent, shielding=$isShielding, " +
                "mined=$minedHeight, expiry=$expiryHeight, outputs=${outputs.size}, raw=${rawTxHex != null})"
    }

/**
 * A TEX send: [leg1] pays the wallet's own [ephemeralAddress], [leg2] pays the exchange. [key] names the send on
 * durable record without the address: a SHA-256 of it, in hex.
 */
data class TexPair(
    val leg1: TexTxFacts,
    val leg2: TexTxFacts,
    val ephemeralAddress: String,
) {
    val key: String
        get() = texRecordKey(ephemeralAddress)

    val expiryHeight: Long
        get() = checkNotNull(leg2.expiryHeight ?: leg1.expiryHeight)

    override fun toString(): String = "TexPair(leg1=$leg1, leg2=$leg2, key=${key.take(TXID_LOG_CHARS)}…)"
}

/** The numbers the checker and the recovery live by. */
object TexRules {
    /**
     * NOT_SENT needs the scanned tip this far past the expiry, in two non-decreasing passes (correction 2), in pre-NU7
     * blocks; [reorgMargin] keeps its time from NU7 on (ZC-688 H1, ZC-1554: three times as many blocks).
     */
    const val REORG_MARGIN = 10L

    /** ZC-1554: [REORG_MARGIN] at the spacing of [expiry], the height it is counted from. */
    fun reorgMargin(expiry: Long): Long = ChainTiming.timeKeptBlocks(REORG_MARGIN, expiry)

    /** The lookup is due this far past leg 2's expiry: there the lookup, not the height, is the proof. */
    const val CHECK_MARGIN = 2L

    /** Only ZEC found at the ephemeral address worth at least this much is shielded back (zatoshi). */
    const val SHIELD_THRESHOLD_ZAT = 20_000L

    /** Lookups of ephemeral addresses per process. */
    const val MAX_LOOKUPS_PER_PROCESS = 20

    /** Shields of one TEX send's ZEC, each after the one before was proven expired. */
    const val MAX_SHIELD_ATTEMPTS = 3

    /**
     * When a shield's expiry is not on record (the process ended between its create and its record), it cannot be
     * mined later than the tip read before the create, plus 1 (its target), plus the largest expiry delta the SDK can
     * use there ([co.electriccoin.zcash.ui.common.sendattempt.ChainTiming.maxExpiryDelta]), plus this room
     * ([TexRecoveryRecord.shieldBound]; 50 blocks in all before NU7, ZC-114).
     */
    const val UNKNOWN_EXPIRY_ROOM = 9L

    /** First wait after a lookup that found nothing, or a failed Tor start; doubled each time. */
    const val FIRST_BACKOFF_MS = 2L * 60 * 1000

    /** Longest wait between two lookups of one address, or two Tor starts. */
    const val MAX_BACKOFF_MS = 60L * 60 * 1000

    /** How often the monitor looks at the wallet. */
    const val PASS_INTERVAL_MS = 60L * 1000
}

/**
 * How one TEX send is shown: on Home, in the transaction detail, and in the notice. For a send whose legs could not be
 * told apart ([unmatched]), there is one status per transaction that may be one of its legs, with that transaction
 * as both [leg1TxId] and [leg2TxId].
 */
data class TexStatus
    @JvmOverloads
    constructor(
        val key: String,
        val leg1TxId: String,
        val leg2TxId: String,
        val state: TexState,
        /** The ZEC the shield moves back (its inputs), when a shield was built. */
        val foundZat: Long? = null,
        /** The network fee of that shield, taken from [foundZat]. */
        val feeZat: Long? = null,
        /** ZCHAT turned Tor on for this check (the notice says so). */
        val torTurnedOnByZchat: Boolean = false,
        /** Tor could not be started yet: still checking, with backoff. */
        val torFailing: Boolean = false,
        /** Tor is needed, but the user turned it off after ZCHAT had turned it on: ZCHAT does not turn it on again. */
        val torOffByUser: Boolean = false,
        /** Found, but the wallet's other transparent ZEC is shielded first (the SDK picks it before this). */
        val waitingForOtherFunds: Boolean = false,
        /** The notice about a finished recovery was read. */
        val noticeShown: Boolean = false,
        /**
         * T3-fixup: this transaction may be a leg of a TEX send whose legs cannot be told apart (another payment of the
         * same block, and no raw transaction to read). Shown as [TexState.NEEDS_SUPPORT] with its own text; nothing is
         * looked up, shielded or sent for it, and "Send again" is withheld until all of them are mined.
         */
        val unmatched: Boolean = false,
    )

/**
 * T3-fixup (G-F3-1): what the TEX checker's last completed pass knew: the [statuses] it derived and the id of every
 * transaction it read ([seenTxIds]). Null (no pass completed yet) means nothing is classified.
 */
data class TexCheck(
    val statuses: List<TexStatus>,
    val seenTxIds: Set<String>,
    /** F5: the plain sends of the ZCHAT account (in no TEX send) that pass saw mined. */
    val minedSends: Set<String> = emptySet(),
    /** F5: the verdict of every unmined plain send that pass could judge (F4's rules; see [PlainSendVerdict]). */
    val plainSends: Map<String, PlainSendVerdict> = emptyMap(),
    /** F5: [sendAgainUsedHash] of every send whose "Send again" was used (never the txid). */
    val sendAgainUsed: Set<String> = emptySet(),
) {
    override fun toString(): String =
        "TexCheck(statuses=${statuses.size}, seen=${seenTxIds.size}, mined=${minedSends.size}, " +
            "plain=${plainSends.size}, used=${sendAgainUsed.size})"
}

/**
 * F5 (fix-all 2.13.0, train T5): where an unmined plain send of the ZCHAT account stands, as the TEX checker's pass
 * judged it with F4's rules (SendAttemptResolver, SendAttemptProof). A mined one is in [TexCheck.minedSends].
 */
sealed interface PlainSendVerdict {
    /**
     * It can still be mined, or it is not proven dead yet. [expiryHeight] is its expiry height (null: none known, or
     * the pass had no scanned tip); [tip] the scanned tip of the pass (null: unknown).
     */
    data class Waiting(
        val expiryHeight: Long?,
        val tip: Long?,
    ) : PlainSendVerdict

    /** Unmined, and the scanned tip was at least 10 blocks past its expiry on two passes at a strictly rising tip. */
    data object NotSent : PlainSendVerdict
}

/** F5: what a Wallet send's transactions ([plainSendsOutcome]) add up to. */
sealed interface PlainSendsOutcome {
    /** Every one is mined. */
    data object Sent : PlainSendsOutcome

    /** None is mined and every one is proven never mined. */
    data object NotSent : PlainSendsOutcome

    /** One can still be mined: if none is, the last is dead at [expiryHeight] + 10, seen at scanned tip [tip]. */
    data class Waiting(
        val expiryHeight: Long,
        val tip: Long,
    ) : PlainSendsOutcome
}

/**
 * F5: the outcome of a Wallet send whose transactions are [txIds], by the checker's last pass [check]. Null (say what
 * the submit said, as before) when there is no check, no id, an id is a TEX leg, an id the pass did not judge, a part
 * mined and a part dead, or a wait with no known expiry or tip.
 */
@Suppress("ReturnCount")
fun plainSendsOutcome(
    txIds: List<String>,
    check: TexCheck?,
): PlainSendsOutcome? {
    if (check == null || txIds.isEmpty()) return null
    if (txIds.any { check.statuses.forTransaction(it) != null }) return null
    if (txIds.all { it in check.minedSends }) return PlainSendsOutcome.Sent
    val unmined = txIds.filterNot { it in check.minedSends }
    val verdicts = unmined.map { check.plainSends[it] ?: return null }
    if (verdicts.all { it == PlainSendVerdict.NotSent }) {
        return if (unmined.size == txIds.size) PlainSendsOutcome.NotSent else null
    }
    val waiting = verdicts.filterIsInstance<PlainSendVerdict.Waiting>()
    val expiry = waiting.map { it.expiryHeight ?: return null }.maxOrNull() ?: return null
    val tip = waiting.map { it.tip ?: return null }.minOrNull() ?: return null
    return PlainSendsOutcome.Waiting(expiry, tip)
}

/** F5: the hash "Send again was used" is kept under: a SHA-256 of the txid, hex. The txid itself is never stored. */
fun sendAgainUsedHash(txId: String): String = texRecordKey(txId)

/**
 * Whether "Send again" may be offered for a transaction whose TEX state is [state] (null: not part of a TEX send).
 * Only when the money is provably where the user thinks it is: nothing left ([TexState.NOT_SENT]), the exchange
 * was paid ([TexState.DONE]), or the ZEC is back and mined ([TexState.SHIELDED]). Never while it may still arrive,
 * is being checked or moved, or needs support: sending again then can pay twice.
 */
fun texSendAgainAllowed(state: TexState?): Boolean =
    when (state) {
        null, TexState.NOT_SENT, TexState.DONE, TexState.SHIELDED -> true
        else -> false
    }

/**
 * T3-fixup (G-F3-1): whether the transaction detail of [txId] may offer "Send again", given the checker's last
 * completed pass [check]. "No status" only means "no TEX send" for a transaction that pass read: before a pass
 * completed, while every pass throws, and for a transaction made after the last pass, nothing is offered again (the
 * transaction may be leg 2 of a TEX send that can still be mined, and sending it again pays the exchange twice).
 *
 * F5: a transaction in no TEX send is offered "Send again" only when it is a plain send the pass saw mined, or one it
 * proved never mined ([PlainSendVerdict.NotSent]) whose "Send again" was not used yet; never while it can still be mined.
 */
fun texSendAgainAllowed(
    txId: String,
    check: TexCheck?,
): Boolean {
    if (check == null) return false
    val status = check.statuses.forTransaction(txId)
    if (status != null) return texSendAgainAllowed(status.state)
    // F5: a plain send. Mined: Zashi's repeat payment. Proven never mined: once. Anything else may still be mined.
    if (txId in check.minedSends) return true
    return check.plainSends[txId] == PlainSendVerdict.NotSent && sendAgainUsedHash(txId) !in check.sendAgainUsed
}

/** The durable-record key of an ephemeral address: a SHA-256 of it, hex. The address itself is never stored. */
fun texRecordKey(ephemeralAddress: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(ephemeralAddress.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * The TEX sends among the transactions ([pairs]), and the sent transactions that may be a leg of a TEX send that
 * could not be told apart from another payment of the same block ([unmatched]; only while one of them is unmined).
 */
data class TexSends(
    val pairs: List<TexPair>,
    val unmatched: List<TexTxFacts>,
)

/** The TEX sends among [transactions] (see [findTexSends]). */
fun pairTexSends(transactions: List<TexTxFacts>): List<TexPair> = findTexSends(transactions).pairs

/**
 * Finds the TEX sends among [transactions].
 *
 * Leg 2 is a sent transaction that pays a TEX address, whoever holds it: someone outside the wallet, another account
 * of the same wallet, or the ZCHAT account itself (T3b-3; only leg 2 of a ZIP 320 send pays a TEX address). Leg 1 is
 * a sent transaction with the same expiry height that pays a transparent address of the ZCHAT account (its ephemeral
 * address; SDK 3.0.0 lists every output, with the account set on the ones that pay an account of the wallet, and
 * [TexOutput.isOwn] is set only for the ZCHAT account). If leg 2's output is listed as a plain transparent address, a
 * sent transaction that pays only external transparent addresses and has such a leg 1 at the same expiry is leg 2.
 *
 * Two payments proposed in the same block share an expiry height. T3-fixup (G-F3-2): leg 2 spends leg 1's ephemeral
 * output, and its stored raw transaction names that outpoint ([texSpentRawIds]), so a leg 2 whose inputs are read may
 * be the partner only of a leg 1 it spends, and a transaction whose inputs are read and spend no leg 1 is no rival.
 * A leg 2 whose inputs cannot be read may be the partner of any leg 1 of its expiry.
 *
 * A leg 1 and a leg 2 are one send only when each is the other's one possible partner: the checker acts only on a
 * send it can name for certain, and a transaction without a TEX partner is never touched (correction 3). The
 * transactions that may be a leg of a send that is still not unique are [TexSends.unmatched], as long as one of
 * those of their expiry height is unmined (when all are mined, every one of them went through).
 */
@Suppress("CyclomaticComplexMethod")
fun findTexSends(transactions: List<TexTxFacts>): TexSends {
    val sent = transactions.filter { it.isSent && !it.isShielding && it.expiryHeight != null }
    val leg1Candidates = sent.filter { tx -> tx.ownTransparentOutputs().isNotEmpty() }
    val leg2Candidates =
        sent.filter { tx ->
            tx.paysTex() ||
                (tx.externalOutputs().isNotEmpty() && tx.externalOutputs().all { it.kind == TexAddressKind.TRANSPARENT })
        }
    val spends = leg2Candidates.associate { it.txId to texSpentRawIds(it.rawTxHex) }

    // Every leg 1 / leg 2 that may be one send.
    val edges =
        leg2Candidates.flatMap { leg2 ->
            leg1Candidates
                .filter { leg1 ->
                    leg1.txId != leg2.txId &&
                        leg1.expiryHeight == leg2.expiryHeight &&
                        // The fallback (leg 2 listed as a plain transparent payment) needs leg 1 to pay nothing else.
                        (leg2.paysTex() || leg1.externalOutputs().isEmpty()) &&
                        spends[leg2.txId].let { spent -> spent == null || leg1.rawIdHex in spent }
                }.map { leg1 -> leg1 to leg2 }
        }
    val leg1Degree = edges.groupingBy { it.first.txId }.eachCount()
    val leg2Degree = edges.groupingBy { it.second.txId }.eachCount()

    val pairs = mutableListOf<TexPair>()
    val used = mutableSetOf<String>()
    for ((leg1, leg2) in edges) {
        if (leg1Degree[leg1.txId] != 1 || leg2Degree[leg2.txId] != 1) continue
        val ephemeral = leg1.ownTransparentOutputs().map { it.address }.distinct()
        if (ephemeral.size != 1) continue
        if (leg1.txId in used || leg2.txId in used) continue
        used += leg1.txId
        used += leg2.txId
        pairs += TexPair(leg1 = leg1, leg2 = leg2, ephemeralAddress = ephemeral.single())
    }

    val unmatched =
        edges
            .flatMap { (leg1, leg2) -> listOf(leg1, leg2) }
            .filter { it.txId !in used }
            .distinctBy { it.txId }
            .groupBy { it.expiryHeight }
            .values
            .filter { group -> group.any { it.minedHeight == null } }
            .flatten()
    return TexSends(pairs = pairs, unmatched = unmatched)
}

/**
 * The outpoints a raw transaction spends, as the id bytes of the transactions they belong to (the order of
 * [TexTxFacts.rawIdHex]), read from its transparent inputs; an empty set for a transaction without any. Null when
 * [rawTxHex] is absent or is not a v4, v5 or v6 (NU6.3) transaction that can be read: then nothing is proven.
 */
@Suppress("ReturnCount", "MagicNumber")
fun texSpentRawIds(rawTxHex: String?): Set<String>? {
    if (rawTxHex == null || rawTxHex.length % 2 != 0) return null
    val bytes =
        try {
            ByteArray(rawTxHex.length / 2) { rawTxHex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
        } catch (_: NumberFormatException) {
            return null
        }
    val reader = TexRawReader(bytes)
    return try {
        val header = reader.u32()
        if (header and OVERWINTERED_FLAG == 0L) return null
        val version = header and OVERWINTERED_FLAG.inv()
        val versionGroup = reader.u32()
        when {
            version == 4L && versionGroup == V4_VERSION_GROUP_ID -> Unit
            // Branch id, lock time and expiry height come before the inputs from v5 on.
            (version == 5L && versionGroup == V5_VERSION_GROUP_ID) || (version == 6L && versionGroup == V6_VERSION_GROUP_ID) ->
                reader.skip(12)
            else -> return null
        }
        val inputs = reader.compactSize()
        if (inputs > MAX_READ_INPUTS) return null
        buildSet {
            repeat(inputs.toInt()) {
                add(reader.hex(32))
                reader.skip(4) // the output index
                reader.skip(reader.compactSize()) // the script
                reader.skip(4) // the sequence
            }
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

/** Reads a raw transaction front to back; throws IndexOutOfBoundsException past its end. */
private class TexRawReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    private fun byte(): Long {
        if (position >= bytes.size) throw IndexOutOfBoundsException("past the end of the transaction")
        return (bytes[position++].toLong() and BYTE_MASK)
    }

    fun u32(): Long = (0 until 4).fold(0L) { value, index -> value or (byte() shl (8 * index)) }

    fun skip(count: Long) {
        if (count < 0 || position + count > bytes.size) throw IndexOutOfBoundsException("past the end of the transaction")
        position += count.toInt()
    }

    fun skip(count: Int) = skip(count.toLong())

    fun hex(count: Int): String {
        if (position + count > bytes.size) throw IndexOutOfBoundsException("past the end of the transaction")
        val text = (position until position + count).joinToString("") { "%02x".format(bytes[it]) }
        position += count
        return text
    }

    @Suppress("MagicNumber")
    fun compactSize(): Long =
        when (val first = byte()) {
            0xFDL -> byte() or (byte() shl 8)
            0xFEL -> u32()
            0xFFL -> throw IndexOutOfBoundsException("a count this large is not a transaction the wallet made")
            else -> first
        }
}

/** T3b-3: a TEX output is leg 2's whoever holds the address, the ZCHAT account included (see [findTexSends]). */
private fun TexTxFacts.paysTex(): Boolean = outputs.any { it.kind == TexAddressKind.TEX }

private fun TexTxFacts.ownTransparentOutputs(): List<TexOutput> =
    outputs.filter { it.isOwn && it.kind == TexAddressKind.TRANSPARENT }

private fun TexTxFacts.externalOutputs(): List<TexOutput> = outputs.filter { !it.isOwn }

private const val TXID_LOG_CHARS = 8
private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFFL
private const val OVERWINTERED_FLAG = 0x80000000L
private const val V4_VERSION_GROUP_ID = 0x892F2085L
private const val V5_VERSION_GROUP_ID = 0x26A7270AL
private const val V6_VERSION_GROUP_ID = 0xD884B698L

/** More inputs than any leg 2 has (it spends one output); a larger count is not read. */
private const val MAX_READ_INPUTS = 10_000L
