package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi

/*
 * NU6.3 pool-spend gating shared by the wallet proposal funnel (common/datasource) and the messaging
 * chokepoint + UI error mapping under screen/. Lives in common/model so that common/ never depends on
 * screen/. Declarations moved verbatim from screen/chat/usecase/CreateChunkedMessageProposalUseCase.kt.
 */

/**
 * The selected account holds value only in Orchard, which NU6.3 sealed: an Orchard Action may only
 * pay its own address (ZIP 258, enableCrossAddress = 0), so a peer-addressed memo cannot be built
 * from it, and the SDK's input selector — which draws on Orchard last but does draw on it — would
 * otherwise fund the send as a turnstile crossing whose amount is public (ZIP 318).
 *
 * Deliberately NOT an [InsufficientFundsException]: the handshake retry loop treats a transient
 * insufficient-funds error as "wait for the next block", and this condition does not resolve with
 * time. Automatic senders (KEX/KEXACK/ZBOOT) catch it and skip; user sends surface it.
 */
class OrchardSealedException(
    val orchardAvailable: Zatoshi,
    /** Ironwood value that has landed but not yet confirmed. Non-zero means "wait", not "move". */
    val ironwoodPending: Zatoshi = Zatoshi(0),
) : Exception(if (ironwoodPending > Zatoshi(0)) IRONWOOD_CONFIRMING_MESSAGE else ORCHARD_SEALED_MESSAGE) {
    companion object {
        const val ORCHARD_SEALED_MESSAGE =
            "Funds are in the sealed Orchard pool and cannot be sent to a peer. The “Old balance” " +
                "card on the Wallet tab shows how to move them to Ironwood, if they can be moved."
        const val IRONWOOD_CONFIRMING_MESSAGE =
            "Your Ironwood funds are still confirming. Sending now would draw on the sealed Orchard pool " +
                "and publish the amount — wait for the confirmation, then try again."
    }
}

/**
 * The proposal the SDK actually built spends Orchard notes, so settling it would move value across the
 * NU6.3 turnstile — and ZIP 318 publishes that crossing's amount on chain.
 *
 * This is the leak the balance-based [orchardSealedRefusal] cannot see. SDK 3.0.0 prefers
 * [Ironwood, Orchard, Sapling] and spends the first pool whose notes ALONE cover amount + fee, so a
 * wallet with a little spendable Ironwood and a lot of Orchard is funded from Orchard; and for a
 * canonical ZIP 318 denomination the SDK deliberately tries an Orchard-only proposal FIRST even when
 * Ironwood could pay. No arithmetic predicts either case, so the built proposal is the only honest
 * oracle.
 */
class PublicCrossingException(
    val orchardAvailable: Zatoshi,
    val ironwoodAvailable: Zatoshi,
) : Exception(PUBLIC_CROSSING_MESSAGE) {
    companion object {
        const val PUBLIC_CROSSING_MESSAGE =
            "This send would be funded from the sealed Orchard pool, which makes the amount public on " +
                "chain. Try an amount your Ironwood balance can cover, or move your old balance to " +
                "Ironwood once from the “Old balance” card on the Wallet tab (that move makes its " +
                "amount public)."
    }
}

/**
 * R2-2 (c) — the proposal the SDK built pays from Sapling into another pool. SDK 3.0.0 funds a send from the
 * first pool of [Ironwood, Orchard, Sapling] whose notes alone cover it, so a wallet whose Ironwood cannot
 * cover the amount is funded from Sapling. Value leaving the Sapling pool shows in the transaction's Sapling
 * value balance, so the amount becomes public, as with the Orchard crossing. The send is refused and said why.
 */
class SaplingCrossingException(
    val saplingAvailable: Zatoshi,
) : Exception(SAPLING_CROSSING_MESSAGE) {
    companion object {
        const val SAPLING_CROSSING_MESSAGE =
            "This send would be paid from your Sapling balance into another pool, which makes the amount " +
                "public on chain. Nothing was sent. Send an amount your Ironwood balance can cover."
    }
}

/**
 * R3-1 — the reason a proposal the wire reader could not read is refused with (fail closed: neither the
 * Orchard nor the Sapling crossing check can run on a shape nobody could read, and the send may be exactly
 * the pool crossing those checks exist to stop). One function so the words the user ends up reading are
 * decided in one place.
 */
internal fun unreadableProposalRefusal(): Exception = UnreadableProposalException()

/**
 * R4-6 (round 3 report 4 item 11) — ZChat stopped a send because it could not check which Zcash pools the
 * proposal the SDK built would use. Its own type and its own sentence: the generic "couldn't be sent, please
 * try again" copy was wrong twice, because no retry can fix it and because it hid that the send was stopped
 * deliberately, before anything went out, to avoid publishing an amount.
 */
class UnreadableProposalException : Exception(UNREADABLE_PROPOSAL_MESSAGE) {
    companion object {
        const val UNREADABLE_PROPOSAL_MESSAGE =
            "ZChat could not check which Zcash pools this send would use, so it stopped rather than risk " +
                "one that makes the amount public. Nothing was sent and nothing was spent. Close and " +
                "reopen ZChat and try again; if it keeps happening, update ZChat."
    }
}

/**
 * R2-2 (c) — refuse a proposal that spends Sapling notes and pays or returns change to any other pool.
 * [shape] is the built proposal read by [readSweepShape]. A proposal that cannot be read is not passed here:
 * the caller refuses it (R3-1, fail closed), because this check cannot tell whether it crosses pools.
 */
internal fun saplingCrossingRefusal(
    shape: SweepShape,
    saplingAvailable: Zatoshi,
): SaplingCrossingException? {
    if (PROPOSAL_POOL_SAPLING !in shape.inputPools) return null
    val leavesSapling =
        shape.paymentPools.any { it != PROPOSAL_POOL_SAPLING } || shape.change.any { it.pool != PROPOSAL_POOL_SAPLING }
    return if (leavesSapling) SaplingCrossingException(saplingAvailable) else null
}

/**
 * Refuse a proposal that would publish its amount. [proposalUsesOrchardInputs] comes from
 * `Proposal.usesOrchardInputs()` on the proposal the SDK just built.
 */
fun publicCrossingRefusal(
    proposalUsesOrchardInputs: Boolean,
    unified: UnifiedInfo,
): PublicCrossingException? =
    if (proposalUsesOrchardInputs) {
        PublicCrossingException(unified.balance.available, unified.ironwood.available)
    } else {
        null
    }

/**
 * THE definition of "stranded", shared by the messaging chokepoint, the wallet's proposal funnel and
 * the ViewModel pre-check. Keyed on SPENDABLE value on purpose: a wallet whose first Ironwood transfer
 * has landed but is still confirming has nothing the SDK can select from Ironwood, so the input
 * selector would fall back to Orchard exactly as if the crossing had never happened.
 *
 * Returns null when a peer-addressed spend can be funded from Ironwood; otherwise the refusal to throw.
 */
fun orchardSealedRefusal(unified: UnifiedInfo): OrchardSealedException? =
    if (unified.ironwood.available == Zatoshi(0) && unified.balance.available > Zatoshi(0)) {
        OrchardSealedException(
            orchardAvailable = unified.balance.available,
            ironwoodPending = unified.ironwood.changePending + unified.ironwood.valuePending,
        )
    } else {
        null
    }

/** How far down a cause chain to look; matches the depth ZchatErrorMessage walks. */
private const val MAX_CAUSE_DEPTH = 20

/**
 * The hand-written explanation for a typed money/privacy refusal, or null for anything else.
 *
 * The generic error screen renders `stackTraceToString()`, so a sealed-pool or public-crossing refusal
 * reaching the scanned-ZIP321 / swap / Flexa funnels was shown to the user as a Java stack trace whose
 * first line happened to be the real sentence. These two exceptions are the only ones on those paths
 * whose message is written FOR the user, so they are named here and rendered as themselves.
 */
fun moneyRefusalMessage(error: Throwable): String? =
    generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .firstOrNull {
            it is NetworkUpgradeRequiredException ||
                it is OrchardSealedException ||
                it is PublicCrossingException ||
                it is SaplingCrossingException ||
                // R4-6 (11): the fail-closed refusal is written for the user too, so the error screen
                // shows the sentence instead of a stack trace whose first line happens to be it.
                it is UnreadableProposalException
        }?.message
