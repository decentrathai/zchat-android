package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.IRONWOOD
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.SAPLING
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.orchard
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The check every sweep proposal passes before it is shown and again before it is signed (sweep spec
 * §2.2): one step and one transaction, Orchard inputs only, exactly one Ironwood payment, no change, the
 * proposal's own fee, and more than 5,000 zatoshi left to arrive. Anything unreadable is refused.
 */
class OrchardSweepShapeRefusalTest {
    @Test
    fun an_unreadable_or_empty_proposal_is_refused() {
        val refusals =
            listOf(
                orchardSweepShapeRefusal(null, true, 1, Zatoshi(20_000)),
                orchardSweepShapeRefusal(readSweepShape(ByteArray(0)), true, 1, Zatoshi(20_000)),
            )

        refusals.forEach { refusal ->
            assertTrue(refusal is TransactionProposalNotCreatedException, "$refusal")
            assertTrue(refusal.cause is OrchardSweepRefusedException, "${refusal.cause}")
        }
    }

    @Test
    fun only_the_exact_sweep_passes_the_shape_check() {
        val sweep = SweepProposalBytes.proposal(listOf(orchard(139_000)), fee = 20_000)
        assertNull(sweep.refusal())

        val refused =
            mapOf(
                "fee disagrees" to (sweep to Zatoshi(25_000)),
                "a Sapling input" to
                    (
                        SweepProposalBytes.proposal(
                            listOf(orchard(100_000), SweepProposalBytes.Input(SAPLING, 39_000)),
                            25_000
                        ) to null
                    ),
                "two payments" to
                    (SweepProposalBytes.proposal(listOf(orchard(139_000)), 25_000, listOf(IRONWOOD, IRONWOOD)) to null),
                "no payment" to (SweepProposalBytes.proposal(listOf(orchard(139_000)), 20_000, emptyList()) to null),
            )
        refused.forEach { (case, pair) ->
            val (proposal, fee) = pair
            assertTrue(proposal.refusal(fee) is TransactionProposalNotCreatedException, case)
        }
    }

    @Test
    fun a_prior_step_input_or_a_change_output_is_refused() {
        val priorStepInput = SweepProposalBytes.Input(0, 0, isPriorStep = true)
        val priorStep = SweepProposalBytes.proposal(listOf(orchard(139_000), priorStepInput), 20_000)
        val changeOutput = listOf(ProposalChange(10_000, IRONWOOD))
        val change = SweepProposalBytes.proposal(listOf(orchard(139_000)), 20_000, change = changeOutput)

        assertTrue(priorStep.refusal() is TransactionProposalNotCreatedException)
        assertTrue(change.refusal() is TransactionProposalNotCreatedException)
    }

    /** 5,000 arriving would be an uneconomic note: too small, with the proposal's own figures. */
    @Test
    fun at_most_the_marginal_fee_arriving_is_too_small() {
        val refusal = SweepProposalBytes.proposal(List(3) { orchard(10_000) }, fee = 25_000).refusal()

        assertTrue(refusal is OrchardSweepTooSmallException, "$refusal")
        assertEquals(Zatoshi(30_000), refusal.orchard)
        assertEquals(Zatoshi(25_000), refusal.fee)
        assertNull(SweepProposalBytes.proposal(List(3) { orchard(10_001) }, fee = 25_000).refusal())
    }

    private fun Proposal.refusal(fee: Zatoshi? = null): Exception? =
        orchardSweepShapeRefusal(
            shape = readSweepShape(toByteArray()),
            usesOrchardInputs = usesOrchardInputs(),
            transactionCount = transactionCount(),
            totalFeeRequired = fee ?: totalFeeRequired(),
        )
}
