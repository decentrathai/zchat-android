package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Proposal
import java.io.ByteArrayOutputStream

/**
 * Hand-encoded SDK 3.0.0 proposals (proposal.proto) for the sweep and C-8 tests. Field numbers and pool
 * values are the ones the SDK's generated classes declare (SweepShapeReaderTest checks the bytes against
 * the SDK's own parser). Every txid is a synthetic byte pattern.
 */
internal object SweepProposalBytes {
    const val TRANSPARENT = 1
    const val SAPLING = 2
    const val ORCHARD = 3
    const val IRONWOOD = 4

    data class Input(
        val pool: Int,
        val value: Long,
        /** A prior-step output instead of a received note. */
        val isPriorStep: Boolean = false,
    )

    fun orchard(value: Long) = Input(ORCHARD, value)

    fun ironwood(value: Long) = Input(IRONWOOD, value)

    /** [steps] identical steps, each spending [inputs], paying [paymentPools] and making [change]. */
    @Suppress("LongParameterList")
    fun bytes(
        inputs: List<Input>,
        fee: Long,
        paymentPools: List<Int> = listOf(IRONWOOD),
        change: List<ProposalChange> = emptyList(),
        steps: Int = 1,
        unknownFields: Boolean = false,
    ): ByteArray {
        val step =
            message {
                if (unknownFields) unknowns()
                paymentPools.forEachIndexed { index, pool ->
                    val payment =
                        message {
                            varint(PAYMENT_INDEX, index.toLong())
                            varint(PAYMENT_POOL, pool.toLong())
                        }
                    bytes(STEP_PAYMENT_POOLS, payment)
                }
                inputs.forEachIndexed { index, input ->
                    val proposedInput =
                        if (input.isPriorStep) {
                            val priorStep =
                                message {
                                    varint(PRIOR_STEP_INDEX, 0)
                                    varint(PRIOR_STEP_PAYMENT_INDEX, 0)
                                }
                            message { bytes(INPUT_PRIOR_STEP_OUTPUT, priorStep) }
                        } else {
                            message {
                                bytes(
                                    INPUT_RECEIVED,
                                    message {
                                        bytes(RECEIVED_TXID, ByteArray(TXID_BYTES) { (index + 1).toByte() })
                                        varint(RECEIVED_POOL, input.pool.toLong())
                                        varint(RECEIVED_INDEX, index.toLong())
                                        if (unknownFields) unknowns()
                                        varint(RECEIVED_VALUE, input.value)
                                    },
                                )
                            }
                        }
                    bytes(STEP_INPUTS, proposedInput)
                }
                bytes(
                    STEP_BALANCE,
                    message {
                        change.forEach { output ->
                            bytes(
                                BALANCE_CHANGE,
                                message {
                                    varint(CHANGE_VALUE, output.value)
                                    varint(CHANGE_POOL, output.pool.toLong())
                                },
                            )
                        }
                        varint(BALANCE_FEE, fee)
                    },
                )
            }
        return message {
            varint(PROPOSAL_VERSION, 1)
            varint(PROPOSAL_FEE_RULE, FEE_RULE_ZIP317)
            if (unknownFields) unknowns()
            repeat(steps) { bytes(PROPOSAL_STEPS, step) }
        }
    }

    /** [bytes] as the SDK's own [Proposal]. */
    @Suppress("LongParameterList")
    fun proposal(
        inputs: List<Input>,
        fee: Long,
        paymentPools: List<Int> = listOf(IRONWOOD),
        change: List<ProposalChange> = emptyList(),
        steps: Int = 1,
    ): Proposal = Proposal.fromByteArray(bytes(inputs, fee, paymentPools, change, steps))

    /** A tiny protobuf writer: enough for the fields above. */
    class Wire {
        val out = ByteArrayOutputStream()

        fun varint(
            field: Int,
            value: Long
        ) {
            raw(encodeVarint((field.toLong() shl TYPE_BITS) or WIRE_VARINT))
            raw(encodeVarint(value))
        }

        fun bytes(
            field: Int,
            value: ByteArray
        ) {
            raw(encodeVarint((field.toLong() shl TYPE_BITS) or WIRE_LENGTH_DELIMITED))
            raw(encodeVarint(value.size.toLong()))
            raw(value)
        }

        /** One unknown field of every wire type the reader must skip. */
        fun unknowns() {
            varint(UNKNOWN_FIELD, 7)
            raw(encodeVarint(((UNKNOWN_FIELD + 1).toLong() shl TYPE_BITS) or WIRE_FIXED64))
            raw(ByteArray(8) { 1 })
            bytes(UNKNOWN_FIELD + 2, byteArrayOf(1, 2, 3))
            raw(encodeVarint(((UNKNOWN_FIELD + 3).toLong() shl TYPE_BITS) or WIRE_FIXED32))
            raw(ByteArray(4) { 2 })
        }

        fun raw(value: ByteArray) = out.write(value, 0, value.size)
    }

    fun message(build: Wire.() -> Unit): ByteArray = Wire().apply(build).out.toByteArray()

    fun encodeVarint(value: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            bytes.write(((rest and 0x7FL) or 0x80L).toInt())
            rest = rest ushr 7
        }
        bytes.write(rest.toInt())
        return bytes.toByteArray()
    }

    private const val PROPOSAL_VERSION = 1
    private const val PROPOSAL_FEE_RULE = 2
    private const val PROPOSAL_STEPS = 4
    private const val STEP_PAYMENT_POOLS = 2
    private const val STEP_INPUTS = 4
    private const val STEP_BALANCE = 5
    private const val PAYMENT_INDEX = 1
    private const val PAYMENT_POOL = 2
    private const val INPUT_RECEIVED = 1
    private const val INPUT_PRIOR_STEP_OUTPUT = 2
    private const val PRIOR_STEP_INDEX = 1
    private const val PRIOR_STEP_PAYMENT_INDEX = 2
    private const val RECEIVED_TXID = 1
    private const val RECEIVED_POOL = 2
    private const val RECEIVED_INDEX = 3
    private const val RECEIVED_VALUE = 4
    private const val BALANCE_CHANGE = 1
    private const val BALANCE_FEE = 2
    private const val CHANGE_VALUE = 1
    private const val CHANGE_POOL = 2
    private const val FEE_RULE_ZIP317 = 3L
    private const val UNKNOWN_FIELD = 40
    private const val TXID_BYTES = 32
    private const val TYPE_BITS = 3
    private const val WIRE_VARINT = 0L
    private const val WIRE_FIXED64 = 1L
    private const val WIRE_LENGTH_DELIMITED = 2L
    private const val WIRE_FIXED32 = 5L
}
