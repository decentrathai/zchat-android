package co.electriccoin.zcash.ui.common.model

/*
 * A pure-Kotlin reader for the protobuf bytes of an SDK proposal (`Proposal.toByteArray()`, SDK 3.0.0
 * proposal.proto). The SDK's Proposal answers only fee, transaction count and "uses Orchard inputs"; the
 * Orchard sweep must also know every input pool, the payment pools and the change, and the chat Send All
 * must know its change (C-8). Nothing here is a guess: a malformed or truncated proposal reads as null,
 * and every caller refuses on null.
 */

/** One change output a proposal creates: its value and the pool it lands in. */
data class ProposalChange(
    val value: Long,
    val pool: Int
)

/**
 * What a serialized proposal says about its own shape, summed over every step. A prior-step input counts
 * as a non-Orchard input of unknown value.
 */
internal data class SweepShape(
    val steps: Int,
    val inputPools: List<Int>,
    val inputTotal: Long,
    val paymentPools: List<Int>,
    val change: List<ProposalChange>,
    val fee: Long,
    /**
     * Each step's ZIP 321 transaction request (the payments the SDK will build), in step order. A step
     * whose request is empty ("zcash:" with no payment, which proto3 does not serialize) has no entry.
     * Carries addresses, so it is left out of [toString], which is logged.
     */
    val transactionRequests: List<String> = emptyList(),
) {
    val changeOutputs: Int
        get() = change.size

    override fun toString(): String =
        "steps=$steps inputs=${inputPools.size} inputPools=${inputPools.distinct()} inputTotal=$inputTotal " +
            "payments=$paymentPools change=$changeOutputs fee=$fee"
}

/** The Sapling value pool in proposal.proto. */
const val PROPOSAL_POOL_SAPLING = 2

/** The Orchard value pool in proposal.proto. */
const val PROPOSAL_POOL_ORCHARD = 3

/** The Ironwood value pool in proposal.proto. */
const val PROPOSAL_POOL_IRONWOOD = 4

// proposal.proto (SDK 3.0.0) field numbers.
private const val PROPOSAL_STEPS = 4
private const val STEP_TRANSACTION_REQUEST = 1
private const val STEP_PAYMENT_POOLS = 2
private const val STEP_INPUTS = 4
private const val STEP_BALANCE = 5
private const val PAYMENT_VALUE_POOL = 2
private const val INPUT_RECEIVED_OUTPUT = 1
private const val INPUT_PRIOR_STEP_OUTPUT = 2
private const val INPUT_PRIOR_STEP_CHANGE = 3
private const val RECEIVED_TXID = 1
private const val RECEIVED_VALUE_POOL = 2
private const val RECEIVED_INDEX = 3
private const val RECEIVED_VALUE = 4
private const val BALANCE_CHANGE = 1
private const val BALANCE_FEE = 2
private const val CHANGE_VALUE = 1
private const val CHANGE_VALUE_POOL = 2

/** An input or change whose pool the proposal does not name (a prior-step output, or an unset pool). */
private const val POOL_UNKNOWN = 0

// Protobuf wire format.
private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
private const val WIRE_TYPE_BITS = 3
private const val WIRE_TYPE_MASK = 0x7L
private const val BYTE_MASK = 0xFF
private const val VARINT_PAYLOAD = 0x7F
private const val VARINT_MORE = 0x80
private const val VARINT_SHIFT = 7
private const val VARINT_LAST_SHIFT = 63
private const val FIXED64_BYTES = 8
private const val FIXED32_BYTES = 4

/**
 * Read the shape of a serialized proposal. Null when the bytes are malformed, truncated or out of range:
 * every caller treats that as a refusal (fail closed). Unknown fields are skipped by wire type.
 */
internal fun readSweepShape(bytes: ByteArray): SweepShape? =
    try {
        readProposal(WireReader(bytes, 0, bytes.size))
    } catch (_: MalformedProposalException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

private class MalformedProposalException : Exception()

/**
 * F3 (fix-all 2.13.0): one input of a proposal as the TEX recovery reads it: the id of the transaction whose output
 * it spends ([txIdHex], the proposal's own byte order, hex; null for a prior-step output or change), its pool and
 * its value.
 *
 * M2 (fix-all 2.13.0): [index] is the output index of the spent note within that transaction (proposal.proto
 * ReceivedOutput field 3), so two notes of one transaction are two notes; null when it was not read (a prior-step
 * input, or a proposal that did not carry it). The first-message plan check treats an input without txid or index as
 * possibly the same note as any other (fail closed).
 */
data class ProposalInputOutpoint(
    val txIdHex: String?,
    val pool: Int,
    val value: Long,
    val index: Int? = null,
)

/** F3: the inputs, payments and fee of a serialized proposal, summed over every step. */
internal data class ProposalInputsShape(
    val steps: Int,
    val inputs: List<ProposalInputOutpoint>,
    val paymentPools: List<Int>,
    val fee: Long,
)

/**
 * F3: read which outputs a serialized proposal spends. Null when the bytes are malformed, truncated or out of range;
 * the TEX recovery refuses on null (fail closed), as every other reader here does.
 */
internal fun readProposalInputs(bytes: ByteArray): ProposalInputsShape? =
    try {
        readInputsProposal(WireReader(bytes, 0, bytes.size))
    } catch (_: MalformedProposalException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

private fun readInputsProposal(reader: WireReader): ProposalInputsShape {
    var steps = 0
    val inputs = mutableListOf<ProposalInputOutpoint>()
    val paymentPools = mutableListOf<Int>()
    var fee = 0L
    reader.forEachField { field, wireType ->
        if (field == PROPOSAL_STEPS && wireType == WIRE_LENGTH_DELIMITED) {
            steps++
            val step = reader.message()
            step.forEachField { stepField, stepWire ->
                when {
                    stepField == STEP_PAYMENT_POOLS && stepWire == WIRE_LENGTH_DELIMITED ->
                        paymentPools += step.message().lastVarint(PAYMENT_VALUE_POOL).toPool()
                    stepField == STEP_INPUTS && stepWire == WIRE_LENGTH_DELIMITED ->
                        inputs += readInputOutpoint(step.message())
                    stepField == STEP_BALANCE && stepWire == WIRE_LENGTH_DELIMITED -> {
                        val balance = StepShape()
                        readBalance(step.message(), balance)
                        fee = Math.addExact(fee, balance.fee)
                    }
                    else -> step.skip(stepWire)
                }
            }
        } else {
            reader.skip(wireType)
        }
    }
    return ProposalInputsShape(steps = steps, inputs = inputs, paymentPools = paymentPools, fee = fee)
}

/** A ProposedInput as an outpoint. A prior-step output or change has no txid, pool or value. */
private fun readInputOutpoint(reader: WireReader): ProposalInputOutpoint {
    var input = ProposalInputOutpoint(txIdHex = null, pool = POOL_UNKNOWN, value = 0L)
    reader.forEachField { field, wireType ->
        input =
            when {
                field == INPUT_RECEIVED_OUTPUT && wireType == WIRE_LENGTH_DELIMITED -> {
                    val received = reader.message()
                    var txId: String? = null
                    var pool = POOL_UNKNOWN
                    var value = 0L
                    // M2: proto3 leaves a zero index out, so a received output with no index field is output 0.
                    var index = 0
                    received.forEachField { receivedField, receivedWire ->
                        when {
                            receivedField == RECEIVED_TXID && receivedWire == WIRE_LENGTH_DELIMITED ->
                                txId = received.lengthDelimitedBytes().joinToString("") { "%02x".format(it) }
                            receivedField == RECEIVED_VALUE_POOL && receivedWire == WIRE_VARINT ->
                                pool = received.varint().toPool()
                            receivedField == RECEIVED_INDEX && receivedWire == WIRE_VARINT ->
                                index = Math.toIntExact(received.varint())
                            receivedField == RECEIVED_VALUE && receivedWire == WIRE_VARINT -> value = received.varint()
                            else -> received.skip(receivedWire)
                        }
                    }
                    ProposalInputOutpoint(txIdHex = txId, pool = pool, value = value, index = index)
                }
                (field == INPUT_PRIOR_STEP_OUTPUT || field == INPUT_PRIOR_STEP_CHANGE) &&
                    wireType == WIRE_LENGTH_DELIMITED -> {
                    reader.skip(wireType)
                    ProposalInputOutpoint(txIdHex = null, pool = POOL_UNKNOWN, value = 0L)
                }
                else -> {
                    reader.skip(wireType)
                    input
                }
            }
    }
    return input
}

private class StepShape {
    val inputPools = mutableListOf<Int>()
    var inputTotal = 0L
    val paymentPools = mutableListOf<Int>()
    val change = mutableListOf<ProposalChange>()
    var fee = 0L
    var transactionRequest: String? = null
}

private fun readProposal(reader: WireReader): SweepShape {
    val steps = mutableListOf<StepShape>()
    reader.forEachField { field, wireType ->
        if (field == PROPOSAL_STEPS && wireType == WIRE_LENGTH_DELIMITED) {
            steps += readStep(reader.message())
        } else {
            reader.skip(wireType)
        }
    }
    return SweepShape(
        steps = steps.size,
        inputPools = steps.flatMap { it.inputPools },
        inputTotal = steps.fold(0L) { total, step -> Math.addExact(total, step.inputTotal) },
        paymentPools = steps.flatMap { it.paymentPools },
        change = steps.flatMap { it.change },
        fee = steps.fold(0L) { total, step -> Math.addExact(total, step.fee) },
        transactionRequests = steps.mapNotNull { it.transactionRequest },
    )
}

private fun readStep(reader: WireReader): StepShape {
    val step = StepShape()
    reader.forEachField { field, wireType ->
        when {
            field == STEP_TRANSACTION_REQUEST && wireType == WIRE_LENGTH_DELIMITED ->
                step.transactionRequest = reader.text()
            field == STEP_PAYMENT_POOLS && wireType == WIRE_LENGTH_DELIMITED ->
                step.paymentPools += reader.message().lastVarint(PAYMENT_VALUE_POOL).toPool()
            field == STEP_INPUTS && wireType == WIRE_LENGTH_DELIMITED -> {
                val (pool, value) = readInput(reader.message())
                step.inputPools += pool
                step.inputTotal = Math.addExact(step.inputTotal, value)
            }
            field == STEP_BALANCE && wireType == WIRE_LENGTH_DELIMITED -> readBalance(reader.message(), step)
            else -> reader.skip(wireType)
        }
    }
    return step
}

/** A ProposedInput: its pool and value. A prior-step output or change is [POOL_UNKNOWN] with no value. */
private fun readInput(reader: WireReader): Pair<Int, Long> {
    var input = POOL_UNKNOWN to 0L
    reader.forEachField { field, wireType ->
        input =
            when {
                field == INPUT_RECEIVED_OUTPUT && wireType == WIRE_LENGTH_DELIMITED -> {
                    val received = reader.message()
                    received.poolAndValue(poolField = RECEIVED_VALUE_POOL, valueField = RECEIVED_VALUE)
                }
                (field == INPUT_PRIOR_STEP_OUTPUT || field == INPUT_PRIOR_STEP_CHANGE) &&
                    wireType == WIRE_LENGTH_DELIMITED -> {
                    reader.skip(wireType)
                    POOL_UNKNOWN to 0L
                }
                else -> {
                    reader.skip(wireType)
                    input
                }
            }
    }
    return input
}

private fun readBalance(
    reader: WireReader,
    step: StepShape
) {
    reader.forEachField { field, wireType ->
        when {
            field == BALANCE_CHANGE && wireType == WIRE_LENGTH_DELIMITED -> {
                val (pool, value) =
                    reader.message().poolAndValue(poolField = CHANGE_VALUE_POOL, valueField = CHANGE_VALUE)
                step.change += ProposalChange(value = value, pool = pool)
            }
            field == BALANCE_FEE && wireType == WIRE_VARINT -> step.fee = reader.varint()
            else -> reader.skip(wireType)
        }
    }
}

private fun Long.toPool(): Int = if (this in 0..Int.MAX_VALUE) toInt() else throw MalformedProposalException()

/** A bounded view of protobuf bytes. Every read past [end] or out of shape throws [MalformedProposalException]. */
private class WireReader(
    private val bytes: ByteArray,
    private var position: Int,
    private val end: Int,
) {
    fun forEachField(block: (field: Int, wireType: Int) -> Unit) {
        while (position < end) {
            val tag = varint()
            val field = tag ushr WIRE_TYPE_BITS
            if (field < 1 || field > Int.MAX_VALUE) throw MalformedProposalException()
            block(field.toInt(), (tag and WIRE_TYPE_MASK).toInt())
        }
    }

    /** The last [field] varint in this message, 0 when absent (the proto3 default). */
    fun lastVarint(field: Int): Long {
        var value = 0L
        forEachField { current, wireType ->
            if (current == field && wireType == WIRE_VARINT) value = varint() else skip(wireType)
        }
        return value
    }

    /** A message holding a pool varint and a value varint (a ReceivedOutput or a ChangeValue). */
    fun poolAndValue(
        poolField: Int,
        valueField: Int
    ): Pair<Int, Long> {
        var pool = POOL_UNKNOWN
        var value = 0L
        forEachField { field, wireType ->
            when {
                field == poolField && wireType == WIRE_VARINT -> pool = varint().toPool()
                field == valueField && wireType == WIRE_VARINT -> value = varint()
                else -> skip(wireType)
            }
        }
        return pool to value
    }

    /** A non-negative varint. A uint64 past [Long.MAX_VALUE] is malformed here: no zatoshi figure is. */
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= end || shift > VARINT_LAST_SHIFT) throw MalformedProposalException()
            val byte = bytes[position++].toInt() and BYTE_MASK
            val payload = (byte and VARINT_PAYLOAD).toLong()
            if (shift == VARINT_LAST_SHIFT && payload != 0L) throw MalformedProposalException()
            result = result or (payload shl shift)
            if (byte and VARINT_MORE == 0) return result
            shift += VARINT_SHIFT
        }
    }

    /** The length-delimited field that starts here, as its own reader. */
    fun message(): WireReader {
        val length = varint()
        if (length > end - position) throw MalformedProposalException()
        val start = position
        position += length.toInt()
        return WireReader(bytes, start, position)
    }

    /** The length-delimited bytes that start here (F3: a received output's txid). */
    fun lengthDelimitedBytes(): ByteArray {
        val length = varint()
        if (length > end - position) throw MalformedProposalException()
        val start = position
        position += length.toInt()
        return bytes.copyOfRange(start, position)
    }

    /** The length-delimited UTF-8 string that starts here. Malformed UTF-8 is malformed. */
    fun text(): String {
        val length = varint()
        if (length > end - position) throw MalformedProposalException()
        val start = position
        position += length.toInt()
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, start, length.toInt())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw MalformedProposalException()
        }
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> advance(FIXED64_BYTES)
            WIRE_LENGTH_DELIMITED -> message()
            WIRE_FIXED32 -> advance(FIXED32_BYTES)
            // Groups (wire types 3 and 4) are not used by proposal.proto; anything else is not protobuf.
            else -> throw MalformedProposalException()
        }
    }

    private fun advance(count: Int) {
        if (count > end - position) throw MalformedProposalException()
        position += count
    }
}
