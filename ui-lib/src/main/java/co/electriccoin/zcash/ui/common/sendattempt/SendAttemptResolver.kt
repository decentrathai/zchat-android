package co.electriccoin.zcash.ui.common.sendattempt

import co.electriccoin.zcash.ui.common.tex.TexRules

/**
 * F4 (fix-all 2.13.0, train T4): the numbers a send attempt of a 1:1 chat row is judged by (maybe-sent design, SPECS
 * §1.2).
 */
object SendAttemptRules {
    /**
     * A transaction counts as dead only once the scanned tip is this far past its expiry height (TEX's margin), in blocks
     * of 75 s; [reorgMargin] keeps its time from NU7 on (ZC-688 H1: 30 blocks of 25 s).
     */
    const val REORG_MARGIN: Long = TexRules.REORG_MARGIN

    /** ZC-1554 / ZC-688 H1: [REORG_MARGIN] at the spacing of [expiry], the height it is counted from. */
    fun reorgMargin(
        expiry: Long,
        upgrades: NetworkUpgradeHeights = ChainTiming.current,
    ): Long = ChainTiming.timeKeptBlocks(REORG_MARGIN, expiry, upgrades)

    /**
     * ZC-114: the expiry delta of a transaction ZChat 2.12.4 made. 2.12.4 built every transaction before NU6.3, so before
     * NU7, at the pre-NU7 delta: a 2.12.4 row keeps it at any height. Every other row reads
     * [ChainTiming.maxExpiryDelta] at its height.
     */
    const val LEGACY_2124_EXPIRY_DELTA = 40L

    /** Room for the SDK's tip to lead the network height the app read, in blocks of 75 s ([tipLead] from NU7 on). */
    const val TIP_LEAD = 2L

    /** ZC-1554 / ZC-688 H3: [TIP_LEAD] at the spacing of [height] (6 blocks of 25 s from NU7 on). */
    fun tipLead(
        height: Long,
        upgrades: NetworkUpgradeHeights = ChainTiming.current,
    ): Long = ChainTiming.timeKeptBlocks(TIP_LEAD, height, upgrades)

    /** A candidate of a row with no id counts only when it was not mined this far before the attempt's height. */
    const val TIP_LAG = 100L

    /** A mined candidate of a 2.12.4 row counts only up to this many blocks after the cutover. */
    const val LEGACY_MINED_WINDOW = LEGACY_2124_EXPIRY_DELTA + TIP_LEAD

    /** How much earlier than a row's time a candidate's block time may read (phone clock against block time). */
    const val CLOCK_ALLOWANCE_MS = 10L * 60L * 1000L

    /**
     * Past this many blocks after a row's release anchor [anchor], nothing the attempt made can still be mined: the
     * largest expiry delta the shipped SDK can use at the attempt's target height (at most [TIP_LEAD] past the anchor;
     * a 2.12.4 row: [LEGACY_2124_EXPIRY_DELTA]), plus the tip lead and the reorg margin, each kept in time at its
     * height. 52 before NU7 (ZC-114), 156 from NU7 on (ZC-1554): 65 minutes either way.
     */
    fun window(
        anchor: Long,
        legacy: Boolean,
        upgrades: NetworkUpgradeHeights = ChainTiming.current,
    ): Long {
        val lead = tipLead(anchor, upgrades)
        val delta = if (legacy) LEGACY_2124_EXPIRY_DELTA else ChainTiming.maxExpiryDelta(anchor + lead, upgrades)
        return delta + lead + reorgMargin(anchor + lead + delta, upgrades)
    }

    /** The first block at which an unmined transaction with [expiry] can count as dead, by the process's table. */
    fun deadBlock(expiry: Long): Long = deadBlock(expiry, ChainTiming.current)

    /** [deadBlock] by the height table [upgrades]. */
    fun deadBlock(
        expiry: Long,
        upgrades: NetworkUpgradeHeights,
    ): Long = expiry + reorgMargin(expiry, upgrades)

    /**
     * About how many minutes until a transaction with expiry height [expiry] counts as dead at scanned tip [tip]: every
     * block still owed (at least one) at its own spacing (ZC-114; 75 s a block before NU7, as before).
     */
    fun minutesUntilDead(
        expiry: Long,
        tip: Long,
        upgrades: NetworkUpgradeHeights = ChainTiming.current,
    ): Int = ChainTiming.minutesBetween(tip, maxOf(deadBlock(expiry, upgrades), tip + 1), upgrades)

    /** The unmined transaction with expiry height [expiry] can no longer be mined at scanned tip [tip]. */
    fun isDead(
        expiry: Long?,
        tip: Long,
    ): Boolean = expiry != null && expiry >= 1 && tip >= deadBlock(expiry)
}

/**
 * One of the ZCHAT account's own transactions as a fresh read of the wallet shows it. [recipients] is null when they
 * could not be read; [readableText] is the message the chat read from it, or null when it read none it can trust.
 */
data class OwnTransaction(
    val txId: String,
    val isSent: Boolean,
    val minedHeight: Long?,
    val expiryHeight: Long?,
    val valueZatoshi: Long,
    val recipients: List<String>?,
    val readableText: String?,
    val blockTimeMillis: Long?,
)

/**
 * The wallet as one pass read it: [scannedTip] is the lower of the fully scanned heights read before and after
 * [transactions] (null when either read failed); [claimedElsewhere] are the ids another row holds as its own or as
 * superseded, which are never a candidate of this row.
 */
data class ChainView(
    val scannedTip: Long?,
    val transactions: List<OwnTransaction>,
    val claimedElsewhere: Set<String> = emptySet(),
    /** ZC-114: the network upgrade heights the read is judged by (this process's table unless a test gives one). */
    val upgrades: NetworkUpgradeHeights = ChainTiming.current,
) {
    val byId: Map<String, OwnTransaction> = transactions.associateBy { it.txId }
}

/**
 * One send attempt of a row. [txIds] and [expiries] are the transactions the attempt recorded (an expiry is known only
 * when it was recorded before the submit). [anchorHeight] is the row's release anchor: the first network height a pass
 * read after the row left the submit path (for a 2.12.4 row, or a row an older 2.13.0 build wrote, the first height a
 * pass of this build read: the cutover). [otherProcessSameAccount]: the attempt was made by another, ended process of
 * this same ZCHAT account (the K1 fast path).
 */
data class SendAttempt(
    val peerAddress: String,
    val text: String,
    val amountZatoshi: Long,
    val rowTimeMillis: Long,
    val markerMillis: Long?,
    val attemptHeight: Long?,
    val txIds: List<String>,
    val expiries: Map<String, Long>,
    val legacy: Boolean,
    val anchorHeight: Long?,
    val otherProcessSameAccount: Boolean,
)

/** What the chain says about one attempt. */
sealed interface SendAttemptVerdict {
    /** Every transaction of the attempt is mined. */
    data object Sent : SendAttemptVerdict

    /** Some are mined and the rest can no longer be mined. Never a Retry. */
    data object Partial : SendAttemptVerdict

    /**
     * Something of the attempt can still be mined; if it is not, it is dead at SendAttemptRules.deadBlock(expiryHeight)
     * (null: unknown).
     */
    data class Waiting(
        val expiryHeight: Long?,
        val tip: Long?,
    ) : SendAttemptVerdict

    /** Nothing of the attempt can reach the chain any more. */
    data object NotSent : SendAttemptVerdict

    /** A mined payment of ours to this contact may be this attempt; it is never sent again. */
    data object MinedUnattributed : SendAttemptVerdict

    /** Nothing can be decided now (no scanned tip, an incomplete pass, or the attempt is still in flight here). */
    data object Unknown : SendAttemptVerdict
}

/**
 * F4: the pure rules of SPECS §1.2. A row with ids is judged by its own transactions; a row with no id by the
 * candidates that could be it, then the K1 fast path, then its release anchor. Any doubt answers WAITING.
 */
object SendAttemptResolver {
    fun resolve(
        attempt: SendAttempt,
        chain: ChainView,
    ): SendAttemptVerdict {
        val tip = chain.scannedTip ?: return SendAttemptVerdict.Unknown
        return if (attempt.txIds.isNotEmpty()) byIds(attempt, chain, tip) else byCandidates(attempt, chain, tip)
    }

    private enum class Leg { MINED, DEAD, LIVE }

    private fun byIds(
        attempt: SendAttempt,
        chain: ChainView,
        tip: Long,
    ): SendAttemptVerdict {
        val liveExpiries = mutableListOf<Long?>()
        val legs =
            attempt.txIds.distinct().map { id ->
                val tx = chain.byId[id]
                val recorded = attempt.expiries[id]
                when {
                    tx?.minedHeight != null -> Leg.MINED
                    tx != null -> {
                        // The wallet's own expiry wins; 0 means "no expiry" and is never dead.
                        val expiry = tx.expiryHeight ?: recorded
                        if (SendAttemptRules.isDead(expiry, tip)) {
                            Leg.DEAD
                        } else {
                            liveExpiries += expiry?.takeIf { it >= 1 }
                            Leg.LIVE
                        }
                    }
                    // Missing from the wallet: judged only when the wallet holds other transactions of this account.
                    chain.transactions.isEmpty() -> {
                        liveExpiries += recorded
                        Leg.LIVE
                    }
                    recorded != null ->
                        if (SendAttemptRules.isDead(recorded, tip)) {
                            Leg.DEAD
                        } else {
                            liveExpiries += recorded.takeIf { it >= 1 }
                            Leg.LIVE
                        }
                    else ->
                        if (anchorPassed(attempt, tip, chain.upgrades)) {
                            Leg.DEAD
                        } else {
                            liveExpiries += null
                            Leg.LIVE
                        }
                }
            }
        return when {
            legs.all { it == Leg.MINED } -> SendAttemptVerdict.Sent
            legs.any { it == Leg.LIVE } -> SendAttemptVerdict.Waiting(liveExpiries.maxOrNullIfAnyUnknown(), tip)
            legs.all { it == Leg.DEAD } -> SendAttemptVerdict.NotSent
            else -> SendAttemptVerdict.Partial
        }
    }

    private fun byCandidates(
        attempt: SendAttempt,
        chain: ChainView,
        tip: Long,
    ): SendAttemptVerdict {
        val candidates = chain.transactions.filter { mayBe(attempt, it, chain) }
        if (candidates.any { it.minedHeight != null }) return SendAttemptVerdict.MinedUnattributed
        val live = candidates.filterNot { SendAttemptRules.isDead(it.expiryHeight, tip) }
        if (live.isNotEmpty()) {
            return SendAttemptVerdict.Waiting(live.map { it.expiryHeight?.takeIf { e -> e >= 1 } }.maxOrNullIfAnyUnknown(), tip)
        }
        // K1 (review point 3): the attempt's process ended, it was this same account, and the wallet holds this
        // account's transactions but none that could be it: the attempt stored nothing.
        if (!attempt.legacy && attempt.otherProcessSameAccount && chain.transactions.isNotEmpty()) {
            return SendAttemptVerdict.NotSent
        }
        return if (anchorPassed(attempt, tip, chain.upgrades)) SendAttemptVerdict.NotSent else SendAttemptVerdict.Waiting(null, tip)
    }

    private fun anchorPassed(
        attempt: SendAttempt,
        tip: Long,
        upgrades: NetworkUpgradeHeights,
    ): Boolean {
        val anchor = attempt.anchorHeight ?: return false
        return tip >= anchor + SendAttemptRules.window(anchor, attempt.legacy, upgrades)
    }

    /** Whether [tx] could be the transaction of [attempt], a row with no id. Any doubt answers yes. */
    @Suppress("ReturnCount")
    private fun mayBe(
        attempt: SendAttempt,
        tx: OwnTransaction,
        chain: ChainView,
    ): Boolean {
        if (!tx.isSent || tx.txId in chain.claimedElsewhere) return false
        val recipients = tx.recipients
        if (!recipients.isNullOrEmpty() && attempt.peerAddress !in recipients) return false
        if (tx.valueZatoshi < attempt.amountZatoshi) return false
        val mined = tx.minedHeight
        if (mined != null && !minedInWindow(attempt, tx, mined)) return false
        val text = tx.readableText ?: return true
        return !readablyDifferent(text, attempt.text)
    }

    private fun minedInWindow(
        attempt: SendAttempt,
        tx: OwnTransaction,
        mined: Long,
    ): Boolean {
        val time = tx.blockTimeMillis
        if (attempt.legacy) {
            val afterRow = time == null || time >= attempt.rowTimeMillis - SendAttemptRules.CLOCK_ALLOWANCE_MS
            val beforeCutoverWindow =
                attempt.anchorHeight == null || mined <= attempt.anchorHeight + SendAttemptRules.LEGACY_MINED_WINDOW
            return afterRow && beforeCutoverWindow
        }
        attempt.attemptHeight?.let { return mined >= it - SendAttemptRules.TIP_LAG }
        val marker = attempt.markerMillis ?: return true
        return time == null || time >= marker - SendAttemptRules.CLOCK_ALLOWANCE_MS
    }

    /** Readably a different message: the texts differ beyond spacing, and neither is the start of the other. */
    fun readablyDifferent(
        chainText: String,
        rowText: String,
    ): Boolean {
        val a = chainText.normalized()
        val b = rowText.normalized()
        if (a.isEmpty() || b.isEmpty()) return false
        return a != b && !a.startsWith(b) && !b.startsWith(a)
    }

    private fun String.normalized(): String = trim().replace(Regex("\\s+"), " ")

    private fun List<Long?>.maxOrNullIfAnyUnknown(): Long? = if (isEmpty() || any { it == null }) null else filterNotNull().maxOrNull()
}
