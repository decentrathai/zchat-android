package co.electriccoin.zcash.ui.common.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MED-017: the network fee is not a constant. These tests encode the two REAL failures that flat fee
 * reserves caused, so nobody reintroduces a magic number.
 */
class Zip317Test {
    @Test
    fun `fee never falls below the grace floor`() {
        val floor = Zip317.MARGINAL_FEE_ZATOSHI * Zip317.GRACE_ACTIONS // 10_000
        assertEquals(floor, Zip317.conventionalFeeZatoshi(0), "0 actions -> grace floor")
        assertEquals(floor, Zip317.conventionalFeeZatoshi(1), "1 action -> grace floor")
        assertEquals(floor, Zip317.conventionalFeeZatoshi(2), "2 actions -> grace floor")
    }

    @Test
    fun `fee scales past the floor — this is why the wallet Send All broke`() {
        // The wallet screen reserved a flat 10_000 (== the grace floor), which is only correct while
        // max(input_notes, outputs) <= 2. Send-all must spend EVERY note, and every received ZCHAT
        // message deposits a dust note, so real wallets have 3+. At 3 notes the true fee is 15_000, so
        // reserving 10_000 makes the SDK require (balance - 10_000) + 15_000 = balance + 5_000 -> the
        // send fails outright rather than "leaving a little dust" as the old comment claimed.
        assertEquals(15_000L, Zip317.conventionalFeeZatoshi(3), "3 notes -> 15_000")
        assertTrue(Zip317.conventionalFeeZatoshi(3) > 10_000L, "a 3-note wallet exceeds the old flat reserve")
        assertEquals(50_000L, Zip317.conventionalFeeZatoshi(10))
    }

    @Test
    fun `chat send-all outputs scale past the old flat 30k buffer`() {
        // The chat send is a multi-output tx: outputs = chunkCount + 1 platform fee + 1 change.
        // The old flat 30_000 buffer holds only while chunkCount + 2 <= 6, i.e. chunkCount <= 4.
        fun chatFee(chunkCount: Int) = Zip317.conventionalFeeZatoshi(chunkCount + 2)

        assertEquals(30_000L, chatFee(4), "chunkCount 4 -> exactly the old buffer (last safe value)")
        assertEquals(35_000L, chatFee(5), "chunkCount 5 -> 35_000 exceeds the old flat 30_000 buffer")
        assertTrue(chatFee(5) > 30_000L, "the old flat buffer under-reserves from chunkCount 5 onward")
        assertEquals(60_000L, chatFee(10), "a long memo scales linearly")
    }
}
