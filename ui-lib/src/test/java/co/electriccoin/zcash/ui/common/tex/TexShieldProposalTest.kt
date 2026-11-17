package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG1_RAW
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F3 (PLAN.md §3 F3 TexShieldProposalTest; PLAN_V2 §2 F3 "only the funds found are spent"): the automatic shield is
 * accepted only when it spends nothing but transparent outputs of the ONE leg 1 whose ZEC the lookup found, pays no
 * one, agrees with the SDK on its fee and leaves something after it. The SDK's shielding proposal with no receiver
 * picks the account's other transparent ZEC first, so this check is what keeps the recovery from moving anything else.
 * Fails at 15e2b1c39: there is no TEX recovery.
 */
class TexShieldProposalTest {
    private fun input(
        rawId: String? = LEG1_RAW,
        pool: Int = TRANSPARENT_POOL,
        value: Long = 150_000,
    ) = TexApi.shieldInput(rawId, pool, value)

    @Test
    fun a_shield_of_only_leg_one_s_output_is_accepted_in_either_byte_order() {
        val shape = TexApi.shieldShape(listOf(input()), fee = 10_000)
        assertEquals(LEG1_RAW, TexApi.matchShield(shape, listOf(LEG1_RAW)))

        val reversed = TexApi.shieldShape(listOf(input(rawId = LEG1_RAW.reversedHex())), fee = 10_000)
        assertEquals(LEG1_RAW, TexApi.matchShield(reversed, listOf(LEG1_RAW)))

        val twoOutputs = TexApi.shieldShape(listOf(input(value = 60_000), input(value = 70_000)), fee = 15_000)
        assertEquals(LEG1_RAW, TexApi.matchShield(twoOutputs, listOf(LEG1_RAW, txid(0x90).reversedHex())))
    }

    @Test
    fun anything_else_is_refused() {
        val other = txid(0x77)
        val refused =
            mapOf(
                "another transaction's output" to TexApi.shieldShape(listOf(input(rawId = other)), fee = 10_000),
                "the found output plus another" to
                    TexApi.shieldShape(listOf(input(), input(rawId = other)), fee = 10_000),
                "a shielded input" to TexApi.shieldShape(listOf(input(pool = 4)), fee = 10_000),
                "a prior-step input" to TexApi.shieldShape(listOf(input(rawId = null)), fee = 10_000),
                "a payment" to TexApi.shieldShape(listOf(input()), fee = 10_000, paymentCount = 1),
                "two steps" to TexApi.shieldShape(listOf(input()), fee = 10_000, steps = 2),
                "two transactions" to TexApi.shieldShape(listOf(input()), fee = 10_000, transactionCount = 2),
                "a fee the SDK disagrees with" to TexApi.shieldShape(listOf(input()), fee = 10_000, totalFeeRequired = 15_000),
                "nothing left after the fee" to TexApi.shieldShape(listOf(input(value = 10_000)), fee = 10_000),
                "no input" to TexApi.shieldShape(emptyList(), fee = 10_000),
                "no fee" to TexApi.shieldShape(listOf(input()), fee = 0),
            )
        refused.forEach { (case, shape) -> assertNull(TexApi.matchShield(shape, listOf(LEG1_RAW)), case) }

        val found = listOf(LEG1_RAW, txid(0x90).reversedHex())
        val fromTwoSends =
            TexApi.shieldShape(listOf(input(), input(rawId = txid(0x90).reversedHex())), fee = 10_000)
        assertNull(TexApi.matchShield(fromTwoSends, found), "one shield moves the ZEC of one TEX send")
    }

    @Test
    fun the_shape_is_read_from_the_proposal_bytes_as_the_sdk_reads_them() {
        val txIdBytes = hexBytes(LEG1_RAW)
        val bytes = shieldProposalBytes(txIdBytes = txIdBytes, value = 150_000, fee = 10_000)

        val shape = assertNotNull(TexApi.readShieldShape(bytes, transactionCount = 1, totalFeeRequired = 10_000))

        assertEquals(1, TexApi.prop(shape, "steps"))
        assertEquals(0, TexApi.prop(shape, "paymentCount"))
        assertEquals(10_000L, TexApi.prop(shape, "fee"))
        val inputs = TexApi.prop(shape, "inputs") as List<*>
        assertEquals(1, inputs.size)
        assertEquals(LEG1_RAW, TexApi.prop(inputs.single()!!, "txIdHex"))
        assertEquals(TRANSPARENT_POOL, TexApi.prop(inputs.single()!!, "pool"))
        assertEquals(150_000L, TexApi.prop(inputs.single()!!, "value"))
        assertEquals(LEG1_RAW, TexApi.matchShield(shape, listOf(LEG1_RAW)))

        // The SDK's own generated parser (runtime classpath only, hence reflection) reads the same txid and value.
        val proposal =
            Class
                .forName("cash.z.wallet.sdk.internal.ffi.ProposalOuterClass\$Proposal")
                .getMethod("parseFrom", ByteArray::class.java)
                .invoke(null, bytes)
        val step = (proposal.javaClass.getMethod("getStepsList").invoke(proposal) as List<*>).single()!!
        val received =
            (step.javaClass.getMethod("getInputsList").invoke(step) as List<*>).single()!!.let {
                it.javaClass.getMethod("getReceivedOutput").invoke(it)
            }
        val sdkTxId = received.javaClass.getMethod("getTxid").invoke(received)
        val sdkBytes = sdkTxId.javaClass.getMethod("toByteArray").invoke(sdkTxId) as ByteArray
        assertEquals(LEG1_RAW, sdkBytes.joinToString("") { "%02x".format(it) })
        assertEquals(150_000L, received.javaClass.getMethod("getValue").invoke(received))
    }

    @Test
    fun unreadable_bytes_are_no_shape() {
        val bytes = shieldProposalBytes(txIdBytes = hexBytes(LEG1_RAW), value = 150_000, fee = 10_000)

        assertNull(TexApi.readShieldShape(bytes.copyOf(bytes.size - 3), 1, 10_000))
        assertNull(TexApi.readShieldShape(byteArrayOf(0x22, 0x7F), 1, 10_000))
    }

    private fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** proposal.proto (SDK 3.0.0): Proposal{1 version, 4 steps{4 inputs{1 receivedOutput{1 txid,2 pool,3 index,4 value}}, 5 balance{2 fee}}}. */
    private fun shieldProposalBytes(
        txIdBytes: ByteArray,
        value: Long,
        fee: Long,
    ): ByteArray {
        val received =
            message {
                bytes(1, txIdBytes)
                varint(2, TRANSPARENT_POOL.toLong())
                varint(3, 0)
                varint(4, value)
            }
        val input = message { bytes(1, received) }
        val balance = message { varint(2, fee) }
        val step =
            message {
                bytes(4, input)
                bytes(5, balance)
            }
        return message {
            varint(1, 1)
            bytes(4, step)
        }
    }

    private class Writer {
        val out = ByteArrayOutputStream()

        fun varint(
            field: Int,
            value: Long,
        ) {
            raw(field.toLong() shl 3)
            raw(value)
        }

        fun bytes(
            field: Int,
            value: ByteArray,
        ) {
            raw((field.toLong() shl 3) or 2)
            raw(value.size.toLong())
            out.write(value)
        }

        private fun raw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
    }

    private fun message(block: Writer.() -> Unit): ByteArray = Writer().apply(block).out.toByteArray()
}
