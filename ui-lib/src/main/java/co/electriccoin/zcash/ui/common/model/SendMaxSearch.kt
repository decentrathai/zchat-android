package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType

/** The kinds of recipient Send all prices differently. */
enum class SendMaxRecipient {
    /** Unified or Sapling: one shielded output. */
    SHIELDED,

    /** A transparent output costs one more logical action (ZIP 317). */
    TRANSPARENT,

    /**
     * Two transactions (ZIP 320). Send all stays off until it is confirmed that proposing one does not
     * reserve an ephemeral address.
     */
    TEX;

    companion object {
        /** Null while there is no valid recipient; Send all never probes without one. */
        fun of(type: AddressType?): SendMaxRecipient? =
            when (type) {
                AddressType.Unified, AddressType.Shielded -> SHIELDED
                AddressType.Transparent -> TRANSPARENT
                AddressType.Tex -> TEX
                is AddressType.Invalid, null -> null
            }
    }
}

/** What one priced, never-stored, never-submitted proposal said. */
sealed interface FeeProbe {
    /**
     * [change] is every change output the proposal creates, read from the proposal itself
     * ([readSweepShape]); null when it could not be read.
     *
     * [inputPools] is the pool of every input the proposal spends, read the same way (F9); null when it could not be
     * read. Send all accepts only a proposal whose inputs are all Ironwood.
     */
    data class Built(
        val fee: Zatoshi,
        val usesOrchardInputs: Boolean,
        val transactionCount: Int,
        val change: List<ProposalChange>? = null,
        val inputPools: List<Int>? = null,
    ) : FeeProbe

    /** The SDK refused with "Insufficient balance": this amount plus its fee cannot be covered. */
    data object Short : FeeProbe
}

/** Why Send all cannot fill anything. Each carries the number the user is shown. */
sealed interface SendMaxUnavailable {
    /** Nothing can go to another person until this Ironwood value confirms. */
    data class StillConfirming(
        val pending: Zatoshi
    ) : SendMaxUnavailable

    /** The value is in the sealed Orchard pool, which can only pay this wallet. */
    data class OrchardSealed(
        val orchard: Zatoshi
    ) : SendMaxUnavailable

    /** What can go to another person does not even cover the smallest network fee. */
    data class BelowFee(
        val have: Zatoshi,
        val fee: Zatoshi
    ) : SendMaxUnavailable

    /**
     * Ironwood cannot cover even the smallest sweep, and [sapling] sits in the Sapling pool, which Send all never
     * spends (F9: a proposal with a Sapling input is never accepted). Shown only after the Ironwood check, so a little
     * Sapling next to enough Ironwood no longer turns Send all off.
     */
    data class SaplingPresent(
        val sapling: Zatoshi
    ) : SendMaxUnavailable

    data object TexUnsupported : SendMaxUnavailable

    /** No proposal swept Ironwood exactly; the user must enter an amount. Never an estimate. */
    data object CouldNotPrice : SendMaxUnavailable
}

/**
 * The transaction a sweep builds, beyond its amount and fee.
 *
 * The wallet's Send all is the plain case: one output, nothing else paid, nothing kept. A chat send
 * also pays [alsoPays] on outputs of its own (the platform fee and one dust output per extra memo
 * chunk), and a chat Send all to a contact whose handshake has not run yet [keeps] the handshake's cost
 * back as change, so the swept amount is `V - alsoPays - keeps - fee`.
 *
 * [startingActions] is the smallest logical-action count the shape can be charged for; the scan starts
 * there and only moves up. [minimumAmount] is the smallest amount worth proposing.
 */
data class SendMaxShape(
    val startingActions: Int,
    val alsoPays: Zatoshi = Zatoshi(0),
    val keeps: Zatoshi = Zatoshi(0),
    val minimumAmount: Zatoshi = Zatoshi(1),
) {
    companion object {
        /** The wallet Send screen: one output to [recipient], nothing else paid or kept. */
        fun wallet(recipient: SendMaxRecipient): SendMaxShape =
            SendMaxShape(startingActions = SendMaxSearch.startingActions(recipient))
    }
}

sealed interface SendMaxResult {
    /**
     * [amount] + [fee] is exactly the Ironwood that was available when it was priced, less whatever the
     * [SendMaxShape] also pays or keeps.
     */
    data class Max(
        val amount: Zatoshi,
        val fee: Zatoshi
    ) : SendMaxResult

    data class Unavailable(
        val reason: SendMaxUnavailable
    ) : SendMaxResult
}

/**
 * The exact "Send all" amount, asked of the SDK itself: the SDK has no peer send-max, and the fee of a
 * sweep depends on how many notes it spends, which only the SDK's input selector knows.
 *
 * A monotone upward scan over k logical actions. For each k it prices `A = V - 5,000 * k`, where V is
 * the Ironwood available at that moment (re-read before every probe), and accepts only a proposal that
 * spends no Orchard, is one transaction and charges exactly `5,000 * k`. Anything that draws on Orchard,
 * comes back short or charges more means k is too small, so k grows. An Ironwood-only proposal that
 * charges LESS means the notes cannot be swept exactly (it would leave change, and change of exactly
 * 5,000 is stranded as dust), so the answer is [SendMaxUnavailable.CouldNotPrice], never a step down.
 *
 * A proposal is accepted only when every input it spends is Ironwood, read from the proposal itself
 * ([FeeProbe.Built.inputPools], F9). An input from any other pool (Orchard, Sapling, or a pool the reader
 * cannot name) means the SDK could not fund `A + fee` from Ironwood alone, so k is too small and grows, exactly
 * as for Orchard; a proposal whose inputs cannot be read is [SendMaxUnavailable.CouldNotPrice]. So Sapling value
 * no longer turns Send all off by itself: it only never pays for it.
 *
 * Why an accepted result has no change: its inputs are Ironwood notes summing to at most V, and they must cover
 * `A + fee = V`. That proof rests on V as read before the probe, so it is also checked on the proposal: a
 * sweep that keeps nothing must report a readable change with no value in it ([keepsWhole], F9). The only
 * assumption is that the SDK can select exactly the notes counted in `ironwood.available`; if it cannot, no k
 * is accepted.
 *
 * The scan starts at the ZIP 317 grace count (2), or 3 for a transparent recipient, and gives up after
 * [MAX_EXTRA_ACTIONS] more steps.
 *
 * A [SendMaxShape] that keeps value back changes one thing: the accepted proposal leaves the kept value
 * behind instead of nothing. The acceptance rule is the same, `fee == 5,000 * k`, so what leaves the
 * wallet is still exactly `V - keeps`, and what stays behind totals exactly `keeps`. On its own that does
 * not make it ONE note: when the selector leaves some Ironwood notes unselected, the kept value is split
 * between the change and those notes, and a change of exactly 5,000 in such a split is uneconomic to
 * spend (Ironwood notes 431,002 + 12,000 with a 17,000 reserve: 5,000 change + the 12,000 note, and
 * neither pays the handshake the reserve was kept for). So such a proposal is accepted only when its own
 * change, read from the proposal, is exactly one Ironwood output of `keeps` (owner decision C-8,
 * 2026-09-10). Anything else, including a change that cannot be read, is
 * [SendMaxUnavailable.CouldNotPrice]: the user enters an amount instead.
 */
object SendMaxSearch {
    /** How far past the starting action count the scan may go before it gives up. */
    const val MAX_EXTRA_ACTIONS = 15

    /** The logical-action count the scan starts from, and so the smallest fee it can quote. */
    fun startingActions(recipient: SendMaxRecipient): Int =
        when (recipient) {
            SendMaxRecipient.TRANSPARENT -> Zip317.GRACE_ACTIONS + 1
            SendMaxRecipient.SHIELDED, SendMaxRecipient.TEX -> Zip317.GRACE_ACTIONS
        }

    /**
     * The reason Send all cannot fill anything for [account] and [recipient], or null when it is worth
     * asking the SDK. Cheap and pure: the Send screen shows it before any tap.
     */
    fun precheck(
        account: WalletAccount,
        recipient: SendMaxRecipient,
        shape: SendMaxShape = SendMaxShape.wallet(recipient),
    ): SendMaxUnavailable? {
        val ironwood = account.unified.ironwood
        val sapling = account.sapling?.balance?.available ?: Zatoshi(0)
        // For a shape that also pays or keeps value, "the fee" the user is quoted is everything the
        // smallest sweep needs besides its amount.
        val minimumFee =
            Zatoshi(Zip317.conventionalFeeZatoshi(shape.startingActions)) + shape.alsoPays + shape.keeps
        val smallestSweep = minimumFee + shape.minimumAmount
        val pending = ironwood.changePending + ironwood.valuePending
        val orchard = account.unified.balance.available
        return when {
            recipient == SendMaxRecipient.TEX -> SendMaxUnavailable.TexUnsupported
            // F9: Sapling no longer turns Send all off by itself; the scan never accepts a Sapling input.
            ironwood.available >= smallestSweep -> null
            // "Wait" is promised only when the confirming Ironwood would really cover the smallest sweep,
            // with the same allowance for the notes it adds as ShortfallReason. A 1,000-zatoshi chat note
            // confirming next to sealed Orchard would otherwise say "wait" and then never deliver.
            pending > Zatoshi(0) &&
                ironwood.available + pending >= smallestSweep + ShortfallReason.CONFIRMING_NOTES_FEE ->
                SendMaxUnavailable.StillConfirming(pending)
            orchard > Zatoshi(0) -> SendMaxUnavailable.OrchardSealed(orchard)
            // After Orchard, which the Old balance card can move: Sapling value Send all never uses.
            sapling > Zatoshi(0) -> SendMaxUnavailable.SaplingPresent(sapling)
            else -> SendMaxUnavailable.BelowFee(have = ironwood.available, fee = minimumFee)
        }
    }

    /**
     * Run the scan. [readAccount] is called before every probe so a balance that moves mid-search is
     * priced as it is now; [probe] must price the send and keep nothing.
     */
    @Suppress("ReturnCount") // each exit is a distinct, named outcome of the scan
    suspend fun search(
        recipient: SendMaxRecipient,
        readAccount: suspend () -> WalletAccount,
        probe: suspend (account: WalletAccount, amount: Zatoshi) -> FeeProbe,
        shape: SendMaxShape = SendMaxShape.wallet(recipient),
    ): SendMaxResult {
        val firstActions = shape.startingActions
        for (actions in firstActions..firstActions + MAX_EXTRA_ACTIONS) {
            val account = readAccount()
            precheck(account, recipient, shape)?.let { return SendMaxResult.Unavailable(it) }

            val fee = Zatoshi(Zip317.conventionalFeeZatoshi(actions))
            val left = account.peerSpendableBalance.value - shape.alsoPays.value - shape.keeps.value - fee.value
            if (left < shape.minimumAmount.value) {
                return SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
            }
            val amount = Zatoshi(left)

            when (val priced = probe(account, amount)) {
                FeeProbe.Short -> Unit
                is FeeProbe.Built ->
                    when {
                        // F9: which pools the inputs came from can't be read, so no sweep can be proved.
                        priced.inputPools.isNullOrEmpty() ->
                            return SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
                        priced.usesOrchardInputs ||
                            priced.inputPools.orEmpty().any { it != PROPOSAL_POOL_IRONWOOD } ||
                            priced.fee > fee -> Unit
                        priced.fee == fee && priced.transactionCount == 1 && keepsWhole(priced, shape) ->
                            return SendMaxResult.Max(amount = amount, fee = fee)
                        else -> return SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
                    }
            }
        }
        return SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
    }

    /**
     * Whether [priced] leaves exactly what [shape] keeps. When something is kept: exactly one change output, of
     * exactly `keeps`, in Ironwood (C-8). When nothing is kept (F9): no change value at all, so no change output or
     * only zero-value ones (the SDK may add one); a sweep that leaves change is not "all". An unreadable change fails
     * in both cases.
     */
    fun keepsWhole(
        priced: FeeProbe.Built,
        shape: SendMaxShape
    ): Boolean {
        val change = priced.change ?: return false
        return if (shape.keeps == Zatoshi(0)) {
            change.all { it.value == 0L }
        } else {
            change == listOf(ProposalChange(value = shape.keeps.value, pool = PROPOSAL_POOL_IRONWOOD))
        }
    }
}
