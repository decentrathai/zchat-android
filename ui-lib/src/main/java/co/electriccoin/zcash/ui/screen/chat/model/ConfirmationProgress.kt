package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import java.util.Locale
import kotlin.math.ceil

/**
 * Display-only mirror of the SDK's ZIP 315 spendability defaults.
 *
 * The SDK (Rust JNI — not tunable from Kotlin) treats a shielded note as spendable once
 * `tip >= mined_height + N - 1`, where N is [TRUSTED_CONFIRMATIONS] for the wallet's own change
 * (Internal scope) or a trusted transaction and [UNTRUSTED_CONFIRMATIONS] for anything else. The SDK
 * decides spendability — `WalletBalance.available` is the truth the send gate reads. These constants
 * only let the UI say HOW FAR along a pending Ironwood note is, so "confirming" is a countdown rather
 * than a shrug. If the SDK's defaults ever move, this label drifts, not the gate.
 */
const val UNTRUSTED_CONFIRMATIONS = 10
const val TRUSTED_CONFIRMATIONS = 3

/**
 * Block interval used for the ETA before NU7. The target is 75 s but mainnet spacing drifts to ~100 s (the
 * 2026-09-07 refill measured 87.7 s/block over nine blocks), so 75 s under-promised by up to a third —
 * "~8 min" that was really ten. 90 s keeps the "~" honest; the label re-derives every block anyway.
 * ZC-114: the target spacing with ChainTiming's 6/5 allowance; from NU7 on [confirmationProgress] passes 30 s.
 */
const val SECONDS_PER_BLOCK =
    ChainTiming.PRE_NU7_BLOCK_SECONDS * ChainTiming.DISPLAY_DRIFT_NUMERATOR / ChainTiming.DISPLAY_DRIFT_DENOMINATOR

private const val SECONDS_PER_MINUTE = 60.0

/**
 * Where one note stands on its way to spendable. The mined block itself is confirmation 1, matching
 * the SDK rule above: `confirmations = tip - mined_height + 1`, complete at [required].
 *
 * [confirmations] is clamped to `[0, required]` in every derived value, so a stale tip (reorg) or an
 * old note can never render "-3/10" or "12/10".
 */
data class ConfirmationProgress(
    val confirmations: Int,
    val required: Int,
    /** ZC-114: the seconds each block still owed counts (90 before NU7). */
    val secondsPerBlock: Long = SECONDS_PER_BLOCK,
) {
    private val clamped: Int
        get() = confirmations.coerceIn(0, required)

    val remainingBlocks: Int
        get() = required - clamped

    val isComplete: Boolean
        get() = remainingBlocks == 0

    val etaSeconds: Long
        get() = remainingBlocks * secondsPerBlock

    /** Whole minutes, rounded UP: never "~0 min" while a block is still owed. */
    val etaMinutes: Long
        get() = ceil(etaSeconds / SECONDS_PER_MINUTE).toLong()

    /** The owner-approved wording, verbatim: "4/10 confirmations, ~9 min" / "10/10 confirmations, complete". */
    val label: String
        get() =
            if (isComplete) {
                "$required/$required confirmations, complete"
            } else {
                "$clamped/$required confirmations, ~$etaMinutes min"
            }
}

/**
 * Progress of a note mined at [minedHeight] as seen from chain tip [networkHeight], or null when
 * either is unknown (no tip yet / transaction still unmined) — there is nothing honest to count then.
 */
fun confirmationProgress(networkHeight: Long?, minedHeight: Long?, trusted: Boolean): ConfirmationProgress? {
    if (networkHeight == null || minedHeight == null) return null
    val required = if (trusted) TRUSTED_CONFIRMATIONS else UNTRUSTED_CONFIRMATIONS
    val confirmations = (networkHeight - minedHeight + 1).coerceIn(0L, required.toLong()).toInt()
    return ConfirmationProgress(
        confirmations = confirmations,
        required = required,
        secondsPerBlock = ChainTiming.displaySecondsPerBlock(networkHeight),
    )
}

/**
 * App-owned projection of one transaction that put value into the wallet. The ViewModel builds it
 * from `TransactionOverview` (minedHeight / has_change / is_shielding / trust_status) so the
 * selection below stays pure and unit-testable.
 *
 * [isOwnChange] — the note is the wallet's own change (a send's change, a shielding, a ZIP 318
 * crossing): Internal scope, so the SDK needs only [TRUSTED_CONFIRMATIONS].
 * [isTrusted] — the SDK's ZIP 315 trust status for the transaction; also [TRUSTED_CONFIRMATIONS].
 */
data class PendingReceipt(
    val minedHeight: Long?,
    val isOwnChange: Boolean,
    val isTrusted: Boolean,
) {
    val isTrustedForSpend: Boolean
        get() = isOwnChange || isTrusted

    /** How many confirmations the SDK will demand for this note. */
    val requiredConfirmations: Int
        get() = if (isTrustedForSpend) TRUSTED_CONFIRMATIONS else UNTRUSTED_CONFIRMATIONS

    fun progressAt(networkHeight: Long?): ConfirmationProgress? =
        confirmationProgress(networkHeight = networkHeight, minedHeight = minedHeight, trusted = isTrustedForSpend)
}

/**
 * The receipt that becomes spendable SOONEST (fewest blocks still owed) among those not yet complete,
 * or null when nothing is pending. Complete and unmined receipts are skipped: the first has already
 * unblocked the wallet, the second cannot be counted. Ties keep list order.
 */
fun soonestPendingReceipt(networkHeight: Long?, receipts: Collection<PendingReceipt>): PendingReceipt? =
    receipts
        .mapNotNull { receipt ->
            receipt.progressAt(networkHeight)?.takeUnless { it.isComplete }?.let { receipt to it }
        }.minByOrNull { (_, progress) -> progress.remainingBlocks }
        ?.first

/** [soonestPendingReceipt] resolved to its [ConfirmationProgress] — what the card and the dialog render. */
fun soonestConfirmationProgress(networkHeight: Long?, receipts: Collection<PendingReceipt>): ConfirmationProgress? =
    soonestPendingReceipt(networkHeight, receipts)?.progressAt(networkHeight)

/** ZC-1341: what one on-chain memo's output carries (CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT). */
private const val MEMO_DUST_ZAT = 1_000L

/**
 * Project one transaction, described by the SDK's own `TransactionOverview` flags, onto a
 * [PendingReceipt] — or null when it cannot be the note that unblocks messaging.
 *
 * Pure on purpose: this mapping decides whether a deposit is shown as needing 3 or 10 confirmations,
 * and it used to live untested inside the ViewModel, where a refactor could flip a third-party
 * deposit to "3" and have the card promise spendability the SDK would withhold for seven more blocks.
 *
 * - Own change (a send's change, a shielding) is Internal scope → [TRUSTED_CONFIRMATIONS].
 * - A receipt from a peer → [UNTRUSTED_CONFIRMATIONS] unless the SDK marks the tx trusted.
 * - A plain send with no change, or an unmined tx, puts nothing into the wallet: null.
 * - A receipt of memo dust only (ZC-1341) cannot pay a step: null.
 * - [landsInIronwood] is the pool of the transaction's outputs when the SDK reported them: only an
 *   Ironwood note can unblock an Ironwood-stranded wallet, so a Sapling/transparent receipt must not
 *   drive the countdown (it would show a countdown that then jumps BACK when the real note is
 *   younger). `null` means the outputs were not available — keep the pre-existing heuristic.
 */
@Suppress("ReturnCount") // guard clauses, one per reason a tx is not a pending receipt
fun pendingReceiptFrom(
    minedHeight: Long?,
    isSentTransaction: Boolean,
    isChange: Boolean,
    isShielding: Boolean,
    isTrusted: Boolean,
    receivedNoteCount: Int,
    totalReceivedZat: Long,
    landsInIronwood: Boolean?,
): PendingReceipt? {
    if (minedHeight == null) return null
    if (landsInIronwood == false) return null
    val ownChange = isShielding || (isSentTransaction && isChange)
    val incoming = !isSentTransaction && (receivedNoteCount > 0 || totalReceivedZat > 0L)
    if (!ownChange && !incoming) return null
    // ZC-1341: the 1,000 zat a memo delivers can never pay a 17,000 zat step, so its count is not the wait ("9 of 10" at 6).
    if (!ownChange && totalReceivedZat > 0L && totalReceivedZat <= receivedNoteCount * MEMO_DUST_ZAT) return null
    return PendingReceipt(minedHeight = minedHeight, isOwnChange = ownChange, isTrusted = isTrusted)
}

/**
 * Keep only receipts that could still be pending under the [UNTRUSTED_CONFIRMATIONS] rule: anything
 * more than that many blocks below the newest mined receipt is spendable already. A tip-free superset,
 * so it can be applied before the chain height is known.
 */
fun recentPendingReceipts(receipts: List<PendingReceipt>): List<PendingReceipt> {
    val newest = receipts.maxOfOrNull { it.minedHeight ?: 0L } ?: return emptyList()
    return receipts.filter { (it.minedHeight ?: 0L) > newest - UNTRUSTED_CONFIRMATIONS }
}

/**
 * Plain-language lines explaining WHY a send is being held back while funds confirm.
 *
 * The old copy said only "Your Ironwood funds are still confirming. Wait for the confirmation, then try
 * again." — which left three fair questions unanswered: what a confirmation is and how many are needed,
 * which transaction we are waiting on, and, sharpest, why the balance shows money that cannot be spent.
 * That last one is not a wording problem: the headline balance sums the sealed Orchard pool (which can
 * never pay another person) and value that is still confirming, so it is routinely larger than what can
 * actually be sent. The explanation says so out loud rather than leaving the user to guess.
 *
 * [zec] writes each figure; a screen that honours the hide-balances setting passes its placeholder. The default writes
 * it in English (Locale.US) because these lines are English-only copy: a figure follows the phone's language once the
 * sentence around it does (Round 8 P9 keeps this explanation's translation out of scope; see its REVIEW.md).
 */
fun confirmingExplanation(
    progress: ConfirmationProgress?,
    pending: Zatoshi,
    txAbbrev: String?,
    isOwnChange: Boolean,
    orchardStranded: Zatoshi,
    zec: (Zatoshi) -> String = { "${zecDisplay(it, Locale.US)} ZEC" },
): List<String> {
    val lines = mutableListOf<String>()
    lines +=
        "Zcash confirms a transaction before its funds can be spent again. Your own change needs " +
        "$TRUSTED_CONFIRMATIONS confirmations; funds someone else sends you need $UNTRUSTED_CONFIRMATIONS."
    val origin = if (isOwnChange) "change from your last send" else "received from someone else"
    val fromTx = txAbbrev?.takeIf { it.isNotBlank() }?.let { " in transaction $it…" } ?: ""
    lines += "Waiting on ${zec(pending)}$fromTx ($origin)."
    progress?.let { lines += "Progress: ${it.label}." }
    if (orchardStranded > Zatoshi(0)) {
        lines +=
            "Separately, ${zec(orchardStranded)} sits in the sealed Orchard pool, which cannot " +
            "pay another person at all."
    }
    lines += "Your balance counts all of it, which is why it looks larger than what you can send right now."
    return lines
}
