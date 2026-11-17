package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.OrchardSealedException

/** How far down a cause chain to look. */
private const val MAX_CAUSE_DEPTH = 20

/**
 * C1 (B1 review item 1) — whether a failed memo send proves that no transaction exists which the SDK could
 * still broadcast. The one rule for the group helper (GroupViewModel.sendGroupMemoWithRetry) and the automatic /
 * time-lock helper (ChatViewModel.sendMemoWithBlockRetry).
 *
 * [submitStarted] is whether [CreateChunkedMessageProposalUseCase]'s `onSubmitStarting` ran for this attempt; it
 * runs right before the SDK is asked to store and broadcast, and nothing before it stores anything.
 *
 * - Before the submit, every failure sent nothing: the chokepoint's own refusals (sealed Orchard, the ZC-102
 *   Keystone refusal, a public or Sapling crossing, an unreadable proposal, a memo too long, a Send All that
 *   changed, a cancelled spend auth) and a shortfall found while the proposal was built. Only a
 *   [SubmitRejectedException] naming a stored transaction would contradict that, and none can come from there.
 * - Once the submit started, only [SubmitRejectedException] with `nothingStored` proves it, and only when every
 *   rejection in the chain says so (a chain that also names a stored transaction proves nothing). An
 *   InsufficientFundsException proves nothing by its type. D1 (REVIEW_7C F1): the use case words a shortfall found
 *   after the submit started as an InsufficientFundsException whose cause is the nothing-stored rejection, so the
 *   proof reaches the caller through that cause; a stored-then-rejected submit is never rewrapped (C4).
 */
internal fun memoFailureProvesNothingSent(
    error: Throwable,
    submitStarted: Boolean,
): Boolean {
    val rejections = submitRejections(error)
    return if (submitStarted) {
        rejections.isNotEmpty() && rejections.all { it.nothingStored }
    } else {
        rejections.none { !it.nothingStored }
    }
}

/** C1 (B1 review item 2) — the ids of the transactions the SDK stored before the rejection in [error]'s chain. */
internal fun storedTxIdsOf(error: Throwable): List<String> =
    submitRejections(error)
        .flatMap { it.txIds }
        .distinct()

private fun submitRejections(error: Throwable): List<SubmitRejectedException> =
    generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<SubmitRejectedException>()
        .toList()

/**
 * Round 8 D7 (sweep #8, #12, #13) — whether a failed send was refused only because the wallet's funds are still
 * confirming, which the next blocks resolve. The one rule for the block-aware retries (the group helper
 * GroupViewModel.sendGroupMemoWithRetry, ChatViewModel.sendMemoWithBlockRetry and the 1:1 message queue).
 *
 * The first [InsufficientFundsException] or [OrchardSealedException] in [error]'s cause chain decides:
 * - the shortfall the send chokepoint words "…confirm on-chain…" (ShortfallReason.StillConfirming: what is still
 *   confirming covers the send);
 * - the sealed-Orchard refusal with Ironwood still confirming (`ironwoodPending > 0`): a wallet that still holds
 *   Orchard meets it whenever its only Ironwood note is locked by its own pending change.
 *
 * Both are refused before the submit starts, so nothing was stored. A caller must still only retry a failure whose
 * submit did not start (or that [memoFailureProvesNothingSent] proves stored nothing): this rule reads the reason,
 * not the proof. A genuine shortfall and the "move your old balance" refusal (`ironwoodPending == 0`) are not waits.
 */
internal fun isTransientWait(error: Throwable): Boolean =
    when (
        val decisive =
            generateSequence(error) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .firstOrNull { it is InsufficientFundsException || it is OrchardSealedException }
    ) {
        is InsufficientFundsException -> decisive.message?.contains("confirm on-chain", ignoreCase = true) == true
        is OrchardSealedException -> decisive.ironwoodPending > Zatoshi(0)
        else -> false
    }
