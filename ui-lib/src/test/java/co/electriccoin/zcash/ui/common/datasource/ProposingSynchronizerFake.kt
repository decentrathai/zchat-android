package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy

/**
 * A synchronizer that answers every proposal call (proposeTransfer, proposeFulfillingPaymentUri) with
 * [proposal] and validates every address as Unified. Records the names of the calls it answered. Any
 * other call is a fixture bug and throws.
 */
internal class ProposingSynchronizerProvider(
    private val proposal: Proposal,
) : SynchronizerProvider {
    val calls = mutableListOf<String>()

    override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

    private val proposing: Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "ProposingSynchronizer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "validateAddress" -> AddressType.Unified
                "proposeTransfer", "proposeFulfillingPaymentUri" -> {
                    calls += method.name
                    proposal
                }
                else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
            }
        } as Synchronizer

    override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(proposing)

    override suspend fun getSynchronizer(): Synchronizer = proposing

    override fun resetSynchronizer() = Unit
}

/**
 * Hand-encoded SDK 3.0.0 proposals (proposal.proto) whose steps carry a ZIP 321 transaction request
 * (ProposalStep field 1), for the scanned-request and money-gate tests. Every txid is synthetic.
 */
internal object RequestProposalBytes {
    const val SAPLING = 2
    const val ORCHARD = 3
    const val IRONWOOD = 4

    /**
     * One step: its transaction request ("" = no payment, left out as proto3 does), its input pool, and the
     * pool its payments land in (R2-2 c: a Sapling input paying the Ironwood pool crosses pools).
     */
    data class Step(
        val request: String,
        val payments: Int,
        val inputPool: Int = IRONWOOD,
        val inputValue: Long = 1_000_000,
        val paymentPool: Int = IRONWOOD,
    )

    fun proposal(
        vararg steps: Step,
        fee: Long = 10_000,
    ): Proposal = Proposal.fromByteArray(bytes(steps.toList(), fee))

    fun bytes(
        steps: List<Step>,
        fee: Long,
    ): ByteArray =
        SweepProposalBytes.message {
            varint(PROPOSAL_VERSION, 1)
            varint(PROPOSAL_FEE_RULE, FEE_RULE_ZIP317)
            steps.forEachIndexed { stepIndex, step ->
                bytes(
                    PROPOSAL_STEPS,
                    SweepProposalBytes.message {
                        if (step.request.isNotEmpty()) bytes(STEP_TRANSACTION_REQUEST, step.request.toByteArray())
                        repeat(step.payments) { index ->
                            bytes(
                                STEP_PAYMENT_POOLS,
                                SweepProposalBytes.message {
                                    varint(PAYMENT_INDEX, index.toLong())
                                    varint(PAYMENT_POOL, step.paymentPool.toLong())
                                },
                            )
                        }
                        bytes(
                            STEP_INPUTS,
                            SweepProposalBytes.message {
                                bytes(
                                    INPUT_RECEIVED,
                                    SweepProposalBytes.message {
                                        bytes(RECEIVED_TXID, ByteArray(TXID_BYTES) { (stepIndex + 7).toByte() })
                                        varint(RECEIVED_POOL, step.inputPool.toLong())
                                        varint(RECEIVED_INDEX, 0)
                                        varint(RECEIVED_VALUE, step.inputValue)
                                    },
                                )
                            },
                        )
                        bytes(STEP_BALANCE, SweepProposalBytes.message { varint(BALANCE_FEE, fee) })
                    },
                )
            }
        }

    private const val PROPOSAL_VERSION = 1
    private const val PROPOSAL_FEE_RULE = 2
    private const val PROPOSAL_STEPS = 4
    private const val STEP_TRANSACTION_REQUEST = 1
    private const val STEP_PAYMENT_POOLS = 2
    private const val STEP_INPUTS = 4
    private const val STEP_BALANCE = 5
    private const val PAYMENT_INDEX = 1
    private const val PAYMENT_POOL = 2
    private const val INPUT_RECEIVED = 1
    private const val RECEIVED_TXID = 1
    private const val RECEIVED_POOL = 2
    private const val RECEIVED_INDEX = 3
    private const val RECEIVED_VALUE = 4
    private const val BALANCE_FEE = 2
    private const val FEE_RULE_ZIP317 = 3L
    private const val TXID_BYTES = 32
}
