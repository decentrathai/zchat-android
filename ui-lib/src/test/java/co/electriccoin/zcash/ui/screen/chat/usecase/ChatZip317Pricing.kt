package co.electriccoin.zcash.ui.screen.chat.usecase

import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.FeeProbeBuilt
import co.electriccoin.zcash.ui.common.model.PROPOSAL_POOL_IRONWOOD
import co.electriccoin.zcash.ui.common.model.ProposalChange
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import java.math.BigDecimal

/**
 * A ZIP 317 stand-in for how SDK 3.0.0 prices a chat send over Ironwood notes (never Orchard):
 * oldest-first note selection until the inputs cover the outputs plus the fee, and
 * `fee = 5,000 * max(2, inputs, outputs)`, where outputs include a change output whenever there is
 * change. [zeroChangeCounts] says whether a zero-value change output is still counted (the SDK's exact
 * behaviour is not pinned; the Send All scan must find the sweep either way). Change below 5,000 is dust:
 * the selector takes another note, or comes back short.
 */
@Suppress("ReturnCount") // exact sweep, sweep with change, or short
internal fun zip317ChatPrice(
    ironwoodNotes: List<Long>,
    outputs: List<Long>,
    zeroChangeCounts: Boolean = true,
): FeeProbe {
    val paid = outputs.sum()
    // The change the proposal itself reports (C-8 reads it): a counted zero-value change, or one output.
    val zeroChange = if (zeroChangeCounts) listOf(ironwood(0)) else emptyList()
    var inputs = 0
    var sum = 0L
    for (note in ironwoodNotes) {
        inputs++
        sum += note
        val feeWithoutChange =
            Zip317.conventionalFeeZatoshi(maxOf(inputs, outputs.size + if (zeroChangeCounts) 1 else 0))
        // F9: the probe also reports its input pools, read from the proposal: every input here is an Ironwood note.
        val pools = List(inputs) { PROPOSAL_POOL_IRONWOOD }
        if (sum == paid + feeWithoutChange) return FeeProbeBuilt.of(feeWithoutChange, pools, zeroChange)
        val feeWithChange = Zip317.conventionalFeeZatoshi(maxOf(inputs, outputs.size + 1))
        val change = sum - paid - feeWithChange
        val withChange = FeeProbeBuilt.of(feeWithChange, pools, listOf(ironwood(change)))
        if (change >= Zip317.MARGINAL_FEE_ZATOSHI) return withChange
    }
    return FeeProbe.Short
}

private fun ironwood(change: Long) = ProposalChange(value = change, pool = PROPOSAL_POOL_IRONWOOD)

/** Every `amount` / `amount.N` in a ZIP 321 URI, in zatoshi, in payment order. */
internal fun zip321Amounts(uri: String): List<Long> =
    Regex("""(?:^|[?&])amount(?:\.\d+)?=([0-9.]+)""")
        .findAll(uri)
        .map { BigDecimal(it.groupValues[1]).movePointRight(8).toLong() }
        .toList()

/**
 * A [ProposalDataSource] whose ZIP 321 probes are priced by [zip317ChatPrice] over [ironwoodNotes], and
 * whose real ZIP 321 proposals are recorded and answered with a synthetic proposal for output 0. The
 * synthetic proposal carries no fee, so a send-time check against a priced Send All never matches it:
 * that is the "the wallet moved between the quote and the tap" case. Everything else delegates to
 * [recording], which also records any submission.
 */
internal class ChatZip317ProposalDataSource(
    var ironwoodNotes: List<Long>,
    private val zeroChangeCounts: Boolean = true,
    val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    val probedUris = mutableListOf<String>()
    val proposedUris = mutableListOf<String>()

    override suspend fun probeZip321Fee(
        account: WalletAccount,
        zip321Uri: String,
    ): FeeProbe {
        probedUris += zip321Uri
        return zip317ChatPrice(ironwoodNotes, zip321Amounts(zip321Uri), zeroChangeCounts)
    }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal {
        proposedUris += zip321Uri
        return syntheticZip321Proposal(
            destination = "usynthetic-send-all-peer",
            amount = zip321Amounts(zip321Uri).first(),
            memo = "",
        )
    }
}
