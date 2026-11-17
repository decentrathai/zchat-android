package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.zip317ChatPrice
import co.electriccoin.zcash.ui.screen.chat.usecase.zip321Amounts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * ZC-601 — the exact price question New Chat asks before it pays, reached by reflection so the tests compile before
 * the view model has it: `paidActionConfirmation` (the open question), `confirmPaidAction()` (Pay),
 * `dismissPaidAction()` (Cancel), and the form's `isAskingPrice` (the price is being worked out; not "Sending").
 */
internal object NewChatPriceQuestion {
    private const val WAIT_MS = 10_000L
    private const val POLL_MS = 5L

    private fun member(
        vm: ZchatComposeVM,
        name: String,
    ) = vm.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }

    /** Whether this build's New Chat can ask at all. */
    fun exists(vm: ZchatComposeVM): Boolean = member(vm, "getPaidActionConfirmation") != null

    /** The question that is open now, or null (also when this build has none). */
    fun open(vm: ZchatComposeVM): PaidActionConfirmation? =
        (member(vm, "getPaidActionConfirmation")?.invoke(vm) as? StateFlow<*>)?.value as? PaidActionConfirmation

    /** The open question, or a failure that says what the screen did instead. */
    fun require(vm: ZchatComposeVM): PaidActionConfirmation =
        open(vm) ?: throw AssertionError(
            if (exists(vm)) {
                "New Chat asked no price question; the screen is ${describe(vm.state.value)}"
            } else {
                "New Chat has no price question (ZchatComposeVM.paidActionConfirmation): the tap on Send is the payment"
            },
        )

    /** Pay: the user confirms the open question. */
    fun pay(vm: ZchatComposeVM) {
        val confirm = member(vm, "confirmPaidAction") ?: throw AssertionError("New Chat has no Pay (confirmPaidAction)")
        confirm.invoke(vm)
    }

    /** Cancel, Back or a tap outside: the user dismisses the open question. */
    fun cancel(vm: ZchatComposeVM) {
        val dismiss = member(vm, "dismissPaidAction") ?: throw AssertionError("New Chat has no Cancel (dismissPaidAction)")
        dismiss.invoke(vm)
    }

    /** Whether the form says the price is being worked out (false when this build has no such state). */
    fun askingPrice(state: ZchatComposeState): Boolean =
        state is ZchatComposeState.Ready &&
            state.javaClass.methods
                .firstOrNull { it.name == "isAskingPrice" && it.parameterCount == 0 }
                ?.invoke(state) == true

    /**
     * The step an existing New Chat harness adds after its tap on Send: waits while the price is worked out and
     * answers Pay when the question opens. A build that asks nothing (the base) is left as it is.
     */
    suspend fun payIfAsked(vm: ZchatComposeVM): Boolean {
        if (!exists(vm)) return false
        var paid = false
        withTimeout(WAIT_MS) {
            while (true) {
                // Read in this order: the question is set before the form stops saying the price is worked out.
                val working = askingPrice(vm.state.value)
                if (open(vm) != null) {
                    pay(vm)
                    paid = true
                    break
                }
                if (!working) break
                delay(POLL_MS)
            }
        }
        return paid
    }

    private fun describe(state: ZchatComposeState): String =
        when (state) {
            is ZchatComposeState.Ready -> "Ready(isSending=${state.isSending})"
            is ZchatComposeState.Error -> "Error(${state.message})"
            else -> state.javaClass.simpleName
        }
}

/**
 * A real ZIP 321 proposal for [zip321Uri] that carries the network fee its probe was priced at over [ironwoodNotes]
 * ([zip317ChatPrice]) plus [feeDelta], as one Ironwood-only transaction: the proposal a quoted send must match. A URI
 * the wallet cannot pay is answered with the fee-less synthetic proposal.
 */
internal suspend fun feeCarryingZip321Proposal(
    destination: String,
    zip321Uri: String,
    ironwoodNotes: List<Long>,
    feeDelta: Long = 0L,
): Zip321TransactionProposal {
    val amounts = zip321Amounts(zip321Uri)
    val synthetic = syntheticZip321Proposal(destination, amounts.first(), "")
    val priced = zip317ChatPrice(ironwoodNotes, amounts) as? FeeProbe.Built ?: return synthetic
    return synthetic.copy(
        proposal =
            SweepProposalBytes.proposal(
                inputs = ironwoodNotes.map { SweepProposalBytes.ironwood(it) },
                fee = priced.fee.value + feeDelta,
                paymentPools = amounts.map { SweepProposalBytes.IRONWOOD },
                change = priced.change.orEmpty(),
            ),
    )
}
