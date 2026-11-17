package co.electriccoin.zcash.ui.common.model

/**
 * ZIP-317 conventional fee — the single source of truth for every fee reserve/estimate in the app.
 *
 * ```
 * fee = marginal_fee * max(grace_actions, logical_actions)
 * ```
 * For a fully-shielded (Orchard) send, `logical_actions = max(input_notes, outputs)`.
 *
 * WHY THIS EXISTS: the fee reserves were flat constants (10,000 for the wallet send-all, 30,000 for the
 * chat multi-output send). 10,000 is exactly the ZIP-317 floor — `5,000 * 2` grace actions — which is
 * correct ONLY while `max(input_notes, outputs) <= 2`. But a real fee SCALES with note and output count,
 * and a flat reserve cannot track it:
 *
 *  - Send-all must spend EVERY note. A 3-note wallet is 3 logical actions => fee 15,000, so reserving
 *    only 10,000 makes the SDK require `(balance - 10,000) + 15,000 = balance + 5,000` and the send fails
 *    outright. Every received ZCHAT message deposits a dust note, so multi-note wallets are the norm.
 *  - The chat send-all reserves a flat 30,000 while outputs = chunkCount + 2 drives fee
 *    `5,000 * (chunkCount + 2)`, which overruns at chunkCount >= 5.
 *
 * NOTE these are fee MODELS, not interchangeable magic numbers: the wallet send (single output) and the
 * chat send (N memo outputs + platform fee + change) are different transaction SHAPES and legitimately
 * reserve different amounts. Unifying the constants would be wrong; unifying the MODEL is the fix. Where
 * the true note count is unknown, prefer the SDK's own `proposal.totalFeeRequired()` over any estimate.
 */
object Zip317 {
    /** ZIP-317 marginal fee, in zatoshi, per logical action. */
    const val MARGINAL_FEE_ZATOSHI = 5_000L

    /** ZIP-317 grace actions — the fee never falls below `MARGINAL_FEE_ZATOSHI * GRACE_ACTIONS`. */
    const val GRACE_ACTIONS = 2

    /** The conventional fee for a transaction with [logicalActions] logical actions. */
    fun conventionalFeeZatoshi(logicalActions: Int): Long =
        MARGINAL_FEE_ZATOSHI * maxOf(GRACE_ACTIONS, logicalActions).toLong()
}
