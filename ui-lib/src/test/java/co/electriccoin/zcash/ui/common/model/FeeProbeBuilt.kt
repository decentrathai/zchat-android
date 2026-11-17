package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlin.test.fail

/**
 * F9 (fix-all 2.13.0, train T1): a [FeeProbe.Built] that also says which pools the proposal's inputs came from, as the
 * wire reader reads them from the proposal (`SweepShape.inputPools`).
 *
 * The `inputPools` field is added by the F9 fix. Until it exists this builds the four-field probe, so the fixtures
 * that use it compile and price exactly as before, and the F9 tests that need the pools fail for that reason. Reached
 * by name.
 */
internal object FeeProbeBuilt {
    /** The five-field constructor (fee, usesOrchardInputs, transactionCount, change, inputPools), when it exists. */
    private val withInputPools =
        FeeProbe.Built::class.java.constructors.firstOrNull { constructor ->
            constructor.parameterTypes.size == FIELDS_WITH_POOLS &&
                constructor.parameterTypes[FIELDS_WITH_POOLS - 1] == List::class.java
        }

    fun of(
        fee: Long,
        inputPools: List<Int>?,
        change: List<ProposalChange>? = emptyList(),
        transactionCount: Int = 1,
        usesOrchardInputs: Boolean = inputPools.orEmpty().contains(PROPOSAL_POOL_ORCHARD),
    ): FeeProbe.Built =
        withInputPools
            ?.newInstance(Zatoshi(fee), usesOrchardInputs, transactionCount, change, inputPools) as? FeeProbe.Built
            ?: FeeProbe.Built(Zatoshi(fee), usesOrchardInputs, transactionCount, change)

    /** The probe's input pools; fails when [FeeProbe.Built] cannot report them. */
    @Suppress("UNCHECKED_CAST")
    fun inputPoolsOf(probe: FeeProbe.Built): List<Int>? {
        val getter =
            probe.javaClass.methods.firstOrNull { it.name == "getInputPools" && it.parameterCount == 0 }
                ?: fail("FeeProbe.Built does not report the proposal's input pools (no inputPools)")
        return getter.invoke(probe) as List<Int>?
    }

    private const val FIELDS_WITH_POOLS = 5
}
