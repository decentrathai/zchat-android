package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * ZC-1417, ZC-1909 — the one rule for "can this money pay a transaction of a given cost", used by the pre-question
 * checks (spendableCovers), the first-message plan gate (FirstMessagePlanFunding), the send-time classifier
 * (ShortfallReason.classify) and, through the classifier's "…confirm on-chain…" text, the note wait after a paid key
 * exchange. Two rules used to answer it: the gate accepted a KEX change of cost + 5,000 .. 9,999 that the classifier
 * then called "not enough" (it asked cost + 10,000), so the message failed after its KEX was paid.
 */
object SpendableCover {
    /**
     * The SDK refuses a non-zero change below [Zip317.MARGINAL_FEE_ZATOSHI]: [spendableZat] pays [costZat] only when it
     * is exactly the cost (no change) or at least the cost plus the marginal fee.
     */
    fun covers(
        spendableZat: Long,
        costZat: Long,
    ): Boolean = costZat > 0 && (spendableZat == costZat || spendableZat >= costZat + Zip317.MARGINAL_FEE_ZATOSHI)

    /**
     * Whether [need] will be payable once the [pending] Ironwood confirms: something is still confirming, and what is
     * spendable now plus it covers [need] by [covers]. Accepted limit: notes beyond the transaction's output count each
     * add an action fee the SDK's [need] did not count yet; a send that meets it is refused again after the notes
     * confirm, and is then judged with nothing pending ("not enough"), never waited on forever.
     */
    fun coversOnceConfirmed(
        have: Zatoshi,
        pending: Zatoshi,
        need: Zatoshi,
    ): Boolean = pending.value > 0 && covers(have.value + pending.value, need.value)
}
