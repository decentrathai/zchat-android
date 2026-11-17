package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.IRONWOOD
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.ORCHARD
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.SAPLING
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes.orchard
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure-Kotlin proposal reader (sweep spec §2.2) that the C-8 Send All check and the Orchard sweep
 * rely on. It must read exactly what the SDK's own protobuf parser reads, skip what it does not know, and
 * read malformed bytes as null (every caller refuses on null).
 */
class SweepShapeReaderTest {
    @Test
    fun a_sweep_reads_back_as_written() {
        val bytes = SweepProposalBytes.bytes(listOf(orchard(100_000), orchard(39_000)), fee = 20_000)

        assertEquals(
            SweepShape(
                steps = 1,
                inputPools = listOf(ORCHARD, ORCHARD),
                inputTotal = 139_000,
                paymentPools = listOf(IRONWOOD),
                change = emptyList(),
                fee = 20_000,
            ),
            readSweepShape(bytes),
        )
    }

    @Test
    fun change_outputs_are_read_with_their_value_and_pool() {
        val change = listOf(ProposalChange(17_000, IRONWOOD), ProposalChange(5_000, SAPLING))
        val bytes =
            SweepProposalBytes.bytes(
                inputs = listOf(SweepProposalBytes.ironwood(443_002)),
                fee = 15_000,
                paymentPools = listOf(IRONWOOD, IRONWOOD),
                change = change,
            )

        val shape = assertNotNull(readSweepShape(bytes))

        assertEquals(change, shape.change)
        assertEquals(2, shape.changeOutputs)
        assertEquals(listOf(IRONWOOD, IRONWOOD), shape.paymentPools)
    }

    @Test
    fun every_step_is_summed_and_a_prior_step_input_is_not_orchard() {
        val bytes =
            SweepProposalBytes.bytes(
                inputs = listOf(orchard(50_000), SweepProposalBytes.Input(0, 0, isPriorStep = true)),
                fee = 10_000,
                steps = 2,
            )

        val shape = assertNotNull(readSweepShape(bytes))

        assertEquals(2, shape.steps)
        assertEquals(20_000, shape.fee)
        assertEquals(100_000, shape.inputTotal)
        assertEquals(listOf(ORCHARD, 0, ORCHARD, 0), shape.inputPools)
    }

    @Test
    fun unknown_fields_of_every_wire_type_are_skipped() {
        val plain = SweepProposalBytes.bytes(listOf(orchard(139_000)), fee = 20_000)
        val padded = SweepProposalBytes.bytes(listOf(orchard(139_000)), fee = 20_000, unknownFields = true)

        assertTrue(padded.size > plain.size)
        assertEquals(readSweepShape(plain), readSweepShape(padded))
    }

    /** The field numbers are the SDK's: its own parser reads the same figures from the same bytes. */
    @Test
    fun the_reader_agrees_with_the_sdk_parser() {
        val bytes =
            SweepProposalBytes.bytes(
                inputs = listOf(orchard(129_000), SweepProposalBytes.ironwood(10_000)),
                fee = 25_000,
                paymentPools = listOf(IRONWOOD, ORCHARD),
                change = listOf(ProposalChange(17_000, IRONWOOD)),
                unknownFields = true,
            )
        val shape = assertNotNull(readSweepShape(bytes))
        val sdk = SdkProposalView.parse(bytes)

        assertEquals(sdk.stepCount, shape.steps)
        assertEquals(sdk.inputPools, shape.inputPools)
        assertEquals(sdk.inputValues.sum(), shape.inputTotal)
        assertEquals(sdk.paymentPools, shape.paymentPools)
        assertEquals(sdk.change, shape.change)
        assertEquals(sdk.fee, shape.fee)

        val proposal = Proposal.fromByteArray(bytes)
        assertEquals(proposal.transactionCount(), shape.steps)
        assertEquals(proposal.totalFeeRequired(), Zatoshi(shape.fee))
    }

    @Test
    fun malformed_bytes_read_as_null() {
        val good = SweepProposalBytes.bytes(listOf(orchard(139_000)), fee = 20_000)
        val malformed =
            mapOf(
                "truncated" to good.copyOf(good.size - 1),
                "a length past the end" to byteArrayOf(0x22, 0x7F, 0x01),
                "a group wire type" to byteArrayOf(0x0B),
                "an unfinished varint" to byteArrayOf(0x08, 0x80.toByte()),
                "field number zero" to byteArrayOf(0x00, 0x01),
                "a varint past 64 bits" to
                    byteArrayOf(0x08) + ByteArray(10) { 0xFF.toByte() } + byteArrayOf(0x01),
                "a value past Long.MAX_VALUE" to
                    byteArrayOf(0x10) + ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x01),
            )

        malformed.forEach { (case, bytes) -> assertNull(readSweepShape(bytes), case) }
    }

    /**
     * The SDK's generated protobuf classes read the same bytes. They are only on the runtime classpath
     * (the SDK depends on its backend for runtime), so they are reached by reflection.
     */
    private class SdkProposalView(
        val stepCount: Int,
        val inputPools: List<Int>,
        val inputValues: List<Long>,
        val paymentPools: List<Int>,
        val change: List<ProposalChange>,
        val fee: Long,
    ) {
        companion object {
            private const val OUTER = "cash.z.wallet.sdk.internal.ffi.ProposalOuterClass\$Proposal"

            fun parse(bytes: ByteArray): SdkProposalView {
                val proposal =
                    checkNotNull(Class.forName(OUTER).getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes))
                val steps = proposal.list("getStepsList")
                val inputs = steps.flatMap { it.list("getInputsList") }
                val received =
                    inputs.map { input ->
                        if (input.call("getValueCase").toString() == "RECEIVEDOUTPUT") {
                            input.call("getReceivedOutput")
                        } else {
                            null
                        }
                    }
                return SdkProposalView(
                    stepCount = steps.size,
                    inputPools = received.map { it?.int("getValuePoolValue") ?: 0 },
                    inputValues = received.map { it?.long("getValue") ?: 0L },
                    paymentPools =
                        steps
                            .flatMap { it.list("getPaymentOutputPoolsList") }
                            .map { it.int("getValuePoolValue") },
                    change =
                        steps
                            .flatMap { it.call("getBalance").list("getProposedChangeList") }
                            .map { ProposalChange(it.long("getValue"), it.int("getValuePoolValue")) },
                    fee = steps.sumOf { it.call("getBalance").long("getFeeRequired") },
                )
            }

            private fun Any.call(name: String): Any = checkNotNull(javaClass.getMethod(name).invoke(this))

            private fun Any.list(name: String): List<Any> = (call(name) as List<*>).filterNotNull()

            private fun Any.int(name: String): Int = call(name) as Int

            private fun Any.long(name: String): Long = call(name) as Long
        }
    }
}
