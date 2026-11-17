package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * M2 (fix-all 2.13.0) — what one priced, never-stored, never-submitted proposal says about the notes it would spend,
 * for the first-message plan check (screen.chat.model.FirstMessagePlanFunding). [FeeProbe] stays as it is (Send all
 * and a test helper build it with exactly its five fields); this is its sibling with the inputs.
 */
sealed interface PlanProbe {
    /**
     * The SDK built the proposal. [fee] is its whole network fee (SR-A quotes each leg with it). [inputs] is every note
     * it spends with its outpoint, null when the proposal could not be read; [change] is the value of each Ironwood
     * change note it makes, null when it could not be read.
     */
    data class Built(
        val fee: Zatoshi,
        val transactionCount: Int,
        val usesOrchardInputs: Boolean,
        val inputs: List<ProposalInputOutpoint>?,
        val change: List<Long>?,
    ) : PlanProbe

    /**
     * The SDK refused with "Insufficient balance". [need] is its "need R including fee", or null when it gave none;
     * the caller classifies the shortfall with it (ShortfallReason).
     */
    data class Short(
        val need: Zatoshi?,
    ) : PlanProbe
}
