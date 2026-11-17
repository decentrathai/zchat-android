package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.EPHEMERAL
import co.electriccoin.zcash.ui.common.tex.TexFixture.EXCHANGE_TEX
import co.electriccoin.zcash.ui.common.tex.TexFixture.EXPIRY
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG1
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F3 (PLAN.md §3 F3, TexPairingTest): which transactions of the SDK database are a TEX send. The checker acts only on
 * a send it can name for certain; a transaction without a TEX partner is never touched (correction 3).
 * Fails at 15e2b1c39: there is no TEX checker (no pairTexSends).
 */
class TexPairingTest {
    @Test
    fun a_leg_paying_a_tex_address_and_the_leg_paying_our_ephemeral_address_are_one_send() {
        val pairs = TexApi.pairs(listOf(TexFixture.leg1(), TexFixture.leg2()))

        assertEquals(1, pairs.size)
        val pair = pairs.single()
        assertEquals(LEG1, TexApi.prop(TexApi.prop(pair, "leg1")!!, "txId"))
        assertEquals(LEG2, TexApi.prop(TexApi.prop(pair, "leg2")!!, "txId"))
        assertEquals(EPHEMERAL, TexApi.prop(pair, "ephemeralAddress"))
    }

    @Test
    fun the_record_key_is_a_hash_and_never_the_address() {
        val pair = TexApi.pairs(listOf(TexFixture.leg1(), TexFixture.leg2())).single()
        val key = TexApi.prop(pair, "key") as String

        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, key)
        assertFalse(EPHEMERAL.lowercase() in key)
        assertEquals(key, TexApi.recordKey(EPHEMERAL))
    }

    @Test
    fun a_leg_two_listed_as_a_plain_transparent_payment_is_still_paired() {
        val leg2 =
            TexApi.facts(
                txId = LEG2,
                expiry = EXPIRY,
                outputs = listOf(TexApi.output("t1FixtureExchangeUnderlyingNotReal01", isOwn = false, kind = "TRANSPARENT")),
            )

        assertEquals(1, TexApi.pairs(listOf(TexFixture.leg1(), leg2)).size)
    }

    @Test
    fun a_send_without_a_tex_partner_is_never_a_tex_send() {
        val plain =
            TexApi.facts(
                txId = txid(0x33),
                expiry = EXPIRY,
                outputs = listOf(TexApi.output("u1FixturePeerNotReal", isOwn = false, kind = "OTHER")),
            )
        val loneTex =
            TexApi.facts(txId = txid(0x44), expiry = EXPIRY + 5, outputs = listOf(TexApi.output(EXCHANGE_TEX, false, "TEX")))
        val loneLeg1 = TexFixture.leg1()

        assertEquals(emptyList(), TexApi.pairs(listOf(plain)))
        assertEquals(emptyList(), TexApi.pairs(listOf(loneTex)))
        assertEquals(emptyList(), TexApi.pairs(listOf(loneLeg1, plain)))
    }

    @Test
    fun legs_with_different_expiry_heights_are_not_one_send() {
        val leg2 = TexApi.facts(txId = LEG2, expiry = EXPIRY + 1, outputs = listOf(TexApi.output(EXCHANGE_TEX, false, "TEX")))

        assertEquals(emptyList(), TexApi.pairs(listOf(TexFixture.leg1(), leg2)))
    }

    @Test
    fun two_candidates_for_one_leg_are_ambiguous_and_left_out() {
        val otherLeg1 =
            TexApi.facts(
                txId = txid(0x55),
                expiry = EXPIRY,
                outputs = listOf(TexApi.output("t1FixtureOtherEphemeralNotReal01", isOwn = true, kind = "TRANSPARENT")),
            )

        assertEquals(emptyList(), TexApi.pairs(listOf(TexFixture.leg1(), otherLeg1, TexFixture.leg2())))
    }

    @Test
    fun a_received_or_shielding_transaction_is_never_a_leg() {
        val received =
            TexApi.facts(
                txId = LEG1,
                isSent = false,
                expiry = EXPIRY,
                outputs = listOf(TexApi.output(EPHEMERAL, isOwn = true, kind = "TRANSPARENT")),
            )
        val shielding =
            TexApi.facts(
                txId = LEG1,
                isShielding = true,
                expiry = EXPIRY,
                outputs = listOf(TexApi.output(EPHEMERAL, isOwn = true, kind = "TRANSPARENT")),
            )

        assertEquals(emptyList(), TexApi.pairs(listOf(received, TexFixture.leg2())))
        assertEquals(emptyList(), TexApi.pairs(listOf(shielding, TexFixture.leg2())))
    }
}
