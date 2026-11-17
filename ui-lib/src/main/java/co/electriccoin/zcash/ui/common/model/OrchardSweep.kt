package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException

/*
 * The one way out of the sealed Orchard pool (sweep spec v2, owner decisions of 2026-09-10).
 *
 * After NU6.3 an Orchard note can pay nobody but the wallet itself. SDK 3.0.0 builds that crossing as ONE
 * transaction that spends every Orchard note to the wallet's own Ironwood address with no change. The
 * amount becomes public on chain, so the app only ever builds it after the user agreed, and only through
 * the dedicated funnel (ProposalDataSource.createOrchardSweepProposal). Everything here is pure: the
 * decision the Wallet-tab card and the chat dialog show, the check of what the SDK built (read with
 * ProposalWireReader.kt), and the mapping of a refusal to the sheet state that explains it.
 */

/**
 * The fewest logical actions a sweep is charged for: the Orchard bundle and the Ironwood bundle are each
 * padded to at least two actions, so n notes cost `5,000 * (max(n, 2) + 2)` (sweep spec §2.3).
 */
private const val MIN_ORCHARD_SWEEP_ACTIONS = 2 * Zip317.GRACE_ACTIONS

/** The smallest fee a sweep can cost: 20,000 zatoshi. */
const val MIN_ORCHARD_SWEEP_FEE = Zip317.MARGINAL_FEE_ZATOSHI * MIN_ORCHARD_SWEEP_ACTIONS

/**
 * An Orchard balance at or below this cannot be moved: after the smallest fee, at most the marginal fee
 * would arrive, and the SDK books a note that small as uneconomic, so it could never be spent.
 */
const val MIN_SWEEPABLE_ORCHARD = MIN_ORCHARD_SWEEP_FEE + Zip317.MARGINAL_FEE_ZATOSHI

/** What the Wallet-tab card, the chat dialog and the sheet say about the sealed Orchard balance. */
sealed interface OrchardSweepDecision {
    /** Nothing is in Orchard. */
    data object None : OrchardSweepDecision

    /** Moving is not available for Keystone accounts in 2.13.0 (owner decision 6). */
    data class Keystone(
        val orchard: Zatoshi
    ) : OrchardSweepDecision

    /** Some Orchard value is not spendable yet; the SDK would refuse or leave it behind. */
    data class WaitForSync(
        val orchard: Zatoshi,
        val pending: Zatoshi
    ) : OrchardSweepDecision

    /** At most [MIN_SWEEPABLE_ORCHARD]: nothing spendable would arrive after the fee. */
    data class TooSmall(
        val orchard: Zatoshi
    ) : OrchardSweepDecision

    /** Worth asking the SDK for the sweep. */
    data class Available(
        val orchard: Zatoshi
    ) : OrchardSweepDecision
}

/** Pure. Checked in this order: nothing, Keystone, still pending, too small, available. */
fun decideOrchardSweep(account: WalletAccount): OrchardSweepDecision {
    val orchard = account.unified.balance
    val available = orchard.available
    val pending = orchard.changePending + orchard.valuePending
    return when {
        available == Zatoshi(0) && pending == Zatoshi(0) -> OrchardSweepDecision.None
        account is KeystoneAccount -> OrchardSweepDecision.Keystone(available + pending)
        pending > Zatoshi(0) -> OrchardSweepDecision.WaitForSync(orchard = available + pending, pending = pending)
        available.value <= MIN_SWEEPABLE_ORCHARD -> OrchardSweepDecision.TooSmall(available)
        else -> OrchardSweepDecision.Available(available)
    }
}

/** What the chat's sealed-Orchard dialog offers. */
enum class ChatSweepPrompt {
    /** A "Move old balance" button. */
    MOVE,

    /** A note that the balance is too small to move, and no button. */
    TOO_SMALL,

    /** Nothing: Ironwood is confirming (waiting is the answer), or there is no Orchard. */
    NONE
}

/** Pure. [orchard] is the stranded Orchard balance; [ironwoodPending] the Ironwood still confirming. */
fun chatSweepPrompt(
    orchard: Zatoshi,
    ironwoodPending: Zatoshi
): ChatSweepPrompt =
    when {
        ironwoodPending > Zatoshi(0) -> ChatSweepPrompt.NONE
        orchard.value > MIN_SWEEPABLE_ORCHARD -> ChatSweepPrompt.MOVE
        orchard > Zatoshi(0) -> ChatSweepPrompt.TOO_SMALL
        else -> ChatSweepPrompt.NONE
    }

// --------------------------------------------------------------------------------- refusals

/** The SDK built something that is not a one-transaction, Orchard-only sweep to the wallet's own Ironwood. */
class OrchardSweepRefusedException(
    reason: String
) : Exception(reason)

/** A sweep is already being submitted; a second one must wait for it (spec §2.4 "No double sweep"). */
class OrchardSweepPendingException : Exception("An Orchard sweep is already in flight")

/**
 * What would arrive in Ironwood is at most the marginal fee: a note too small to ever spend. [fee] is the
 * proposal's own fee when one was built, else null.
 */
class OrchardSweepTooSmallException(
    val orchard: Zatoshi,
    val fee: Zatoshi?,
) : Exception("The Orchard balance is too small to move after the network fee")

/**
 * Null when [shape] is exactly the sweep: one step and one transaction, Orchard inputs only, one Ironwood
 * payment, no change, the step fee equal to [totalFeeRequired], and more than the marginal fee left to
 * arrive. Otherwise the exception the funnel throws: [OrchardSweepTooSmallException] when only the
 * arriving amount is wrong, else [TransactionProposalNotCreatedException].
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal fun orchardSweepShapeRefusal(
    shape: SweepShape?,
    usesOrchardInputs: Boolean,
    transactionCount: Int,
    totalFeeRequired: Zatoshi,
): Exception? {
    fun refused(reason: String) = TransactionProposalNotCreatedException(OrchardSweepRefusedException(reason))

    if (shape == null) return refused("the proposal could not be read")
    if (shape.steps != 1 || transactionCount != 1) return refused("not one transaction: $shape")
    if (!usesOrchardInputs) return refused("no Orchard input: $shape")
    if (shape.inputPools.isEmpty() || shape.inputPools.any { it != PROPOSAL_POOL_ORCHARD }) {
        return refused("an input outside Orchard: $shape")
    }
    if (shape.paymentPools != listOf(PROPOSAL_POOL_IRONWOOD)) return refused("not one Ironwood payment: $shape")
    if (shape.changeOutputs != 0) return refused("a change output: $shape")
    if (shape.fee != totalFeeRequired.value) return refused("fee $totalFeeRequired disagrees: $shape")
    if (shape.inputTotal - shape.fee <= Zip317.MARGINAL_FEE_ZATOSHI) {
        return OrchardSweepTooSmallException(orchard = Zatoshi(shape.inputTotal), fee = Zatoshi(shape.fee))
    }
    return null
}

// --------------------------------------------------------------------------------- failure mapping

/** Why a sweep could not be prepared, as the sheet explains it. */
sealed interface OrchardSweepFailure {
    /** [orchard] is too small to move after [fee]; null figures are unknown. */
    data class TooSmall(
        val orchard: Zatoshi?,
        val fee: Zatoshi?
    ) : OrchardSweepFailure

    data object Pending : OrchardSweepFailure

    data object WaitForSync : OrchardSweepFailure

    data object Error : OrchardSweepFailure
}

private const val MAX_SWEEP_CAUSE_DEPTH = 20

/** The rust backend's shortfall figures: "Insufficient balance (have 24000, need 25001 including fee)". */
private val SWEEP_SHORTFALL_FIGURES = Regex("""have (\d+), need (\d+)""")

private val WAIT_FOR_SYNC_PHRASES =
    listOf("ineligible", "sync required", "Must scan blocks first", "not yet scanned any blocks")

/**
 * Walk [error]'s cause chain. A too-small sweep and an SDK shortfall are TooSmall (a shortfall's fee is
 * `need - 1`: the sweep needs the fee plus at least one zatoshi to arrive); a sweep in flight is Pending;
 * the scanner's "not ready" phrases are WaitForSync; anything else is Error.
 */
@Suppress("ReturnCount")
fun classifyOrchardSweepFailure(error: Throwable): OrchardSweepFailure {
    val chain = generateSequence(error) { it.cause }.take(MAX_SWEEP_CAUSE_DEPTH).toList()
    chain.filterIsInstance<OrchardSweepTooSmallException>().firstOrNull()?.let {
        return OrchardSweepFailure.TooSmall(orchard = it.orchard, fee = it.fee)
    }
    chain.filterIsInstance<InsufficientFundsException>().firstOrNull()?.let {
        return OrchardSweepFailure.TooSmall(orchard = it.have, fee = it.need?.let { need -> feeFromNeed(need.value) })
    }
    if (chain.any { it is OrchardSweepPendingException }) return OrchardSweepFailure.Pending
    chain.firstNotNullOfOrNull { SWEEP_SHORTFALL_FIGURES.find(it.message.orEmpty()) }?.let { figures ->
        val have = figures.groupValues[1].toLongOrNull()
        val need = figures.groupValues[2].toLongOrNull()
        return OrchardSweepFailure.TooSmall(
            orchard = have?.let(::Zatoshi),
            fee = need?.let(::feeFromNeed),
        )
    }
    val text = chain.joinToString(" ") { it.message.orEmpty() }
    if (WAIT_FOR_SYNC_PHRASES.any { text.contains(it, ignoreCase = true) }) return OrchardSweepFailure.WaitForSync
    return OrchardSweepFailure.Error
}

/** The sweep's fee from an SDK shortfall's "need": the fee plus the one zatoshi that must arrive. */
private fun feeFromNeed(need: Long): Zatoshi = Zatoshi((need - 1).coerceAtLeast(0))
