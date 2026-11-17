package co.electriccoin.zcash.ui.common.tex

import cash.z.ecc.android.sdk.model.Proposal
import co.electriccoin.zcash.ui.common.model.readProposalInputs

/** The transparent value pool in proposal.proto. */
const val TEX_PROPOSAL_POOL_TRANSPARENT = 1

/** One input of the recovery shield: the transaction whose output it spends (proposal byte order, hex). */
data class TexShieldInput(
    val txIdHex: String?,
    val pool: Int,
    val value: Long,
)

/** What the recovery checks of a shielding proposal before anything is stored or sent. */
data class TexShieldShape(
    val steps: Int,
    val transactionCount: Int,
    val inputs: List<TexShieldInput>,
    val paymentCount: Int,
    val fee: Long,
    val totalFeeRequired: Long,
) {
    val inputTotal: Long
        get() = inputs.fold(0L) { total, input -> Math.addExact(total, input.value) }
}

/** A built recovery shield: its checked [shape] and the SDK proposal (null only in JVM tests that never submit). */
class TexShieldPlan(
    val shape: TexShieldShape,
    val proposal: Proposal?,
)

/** Reads a shielding proposal's shape; null when its bytes cannot be read (the recovery then builds nothing). */
fun readTexShieldShape(
    proposalBytes: ByteArray,
    transactionCount: Int,
    totalFeeRequired: Long,
): TexShieldShape? {
    val read = readProposalInputs(proposalBytes) ?: return null
    return TexShieldShape(
        steps = read.steps,
        transactionCount = transactionCount,
        inputs = read.inputs.map { TexShieldInput(txIdHex = it.txIdHex, pool = it.pool, value = it.value) },
        paymentCount = read.paymentPools.size,
        fee = read.fee,
        totalFeeRequired = totalFeeRequired,
    )
}

/**
 * "Only the funds found are spent": the shield is accepted only when it is one transaction of one step that pays no
 * one, spends only transparent outputs of ONE leg 1 among [leg1RawIdHexes] (the TEX sends whose ZEC the lookup found
 * in this pass), agrees with the SDK on its fee, and leaves more than nothing after it. Returns that leg 1's id as
 * given, or null (refused: nothing is stored or sent).
 *
 * A leg 1's id is accepted in either byte order: a hash cannot name another transaction by being reversed, and the
 * byte order of the SDK's two id types is a phone check (PLAN.md §6).
 */
@Suppress("ReturnCount")
fun matchTexShield(
    shape: TexShieldShape,
    leg1RawIdHexes: Collection<String>,
): String? {
    if (shape.steps != 1 || shape.transactionCount != 1) return null
    if (shape.paymentCount != 0) return null
    if (shape.inputs.isEmpty()) return null
    if (shape.fee != shape.totalFeeRequired || shape.fee <= 0) return null
    if (shape.inputs.any { it.pool != TEX_PROPOSAL_POOL_TRANSPARENT || it.txIdHex == null || it.value <= 0 }) {
        return null
    }
    val total =
        try {
            shape.inputTotal
        } catch (_: ArithmeticException) {
            return null
        }
    if (total <= shape.fee) return null
    val matched =
        shape.inputs
            .map { input -> leg1RawIdHexes.firstOrNull { sameTxId(it, checkNotNull(input.txIdHex)) } }
            .distinct()
    return matched.singleOrNull()
}

private fun sameTxId(
    a: String,
    b: String,
): Boolean {
    val left = a.lowercase()
    val right = b.lowercase()
    return left == right || left == reverseHexBytes(right)
}

private fun reverseHexBytes(hex: String): String = hex.chunked(2).reversed().joinToString("")
