package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.EPHEMERAL
import co.electriccoin.zcash.ui.common.tex.TexFixture.EXPIRY
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG1
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture.EPHEMERAL_B
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture.LEG1B
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture.LEG2B
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture.PLAIN_T
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T3-fixup (fix-all 2.13.0, train T3; cross-family review G-F3-2): two payments proposed in the same block share an
 * expiry height, so pairing a TEX send's legs by expiry alone drops both as "ambiguous": a quick TEX retry after "Send
 * Failed", a second TEX send, a NEAR swap deposit or any payment to a plain transparent address in that block. The
 * dropped send got no status, no lookup and no shield: its ZEC stayed at the one-time address with nothing said.
 * Leg 2 spends leg 1's one-time output, and its stored raw transaction names that outpoint: the legs are told apart
 * by it (tx formats 4, 5 and 6). Where that still fails (no raw transaction, or one that cannot be read), every
 * transaction that may be a leg of the unmatched send gets a status that says so and withholds "Send again", until
 * all of them are mined. Nothing is looked up, shielded or handed to the server for an unmatched send.
 * Fails at 7e8800885: pairTexSends pairs by expiry only and runPass says nothing about a send it could not pair.
 */
class TexSameBlockSendsTest {
    private fun legs(pair: Any): Pair<Any?, Any?> =
        TexApi.prop(TexApi.prop(pair, "leg1")!!, "txId") to TexApi.prop(TexApi.prop(pair, "leg2")!!, "txId")

    private fun onTor() = FakeTexTor().apply { on = true }

    private val leg1Raw get() = LEG1.reversedHex()
    private val leg1bRaw get() = LEG1B.reversedHex()

    @Test
    fun two_tex_sends_of_one_block_are_told_apart_by_the_outpoint_leg_two_spends() {
        val transactions =
            listOf(
                TexFixture.leg1(),
                TexSameBlockFixture.leg1b(),
                TexSameBlockFixture.leg2WithRaw(LEG2, spends = leg1bRaw),
                TexSameBlockFixture.leg2WithRaw(LEG2B, spends = leg1Raw),
            )

        val pairs = TexApi.pairs(transactions)

        // Not by order or by id: LEG2's raw transaction spends LEG1B's output.
        assertEquals(setOf(LEG1B to LEG2, LEG1 to LEG2B), pairs.map(::legs).toSet())
        assertEquals(
            setOf(LEG1B to EPHEMERAL_B, LEG1 to EPHEMERAL),
            pairs.map { TexApi.prop(TexApi.prop(it, "leg1")!!, "txId") to TexApi.prop(it, "ephemeralAddress") }.toSet(),
        )
    }

    @Test
    fun a_plain_transparent_payment_of_the_same_block_is_no_rival_once_its_inputs_are_read() {
        val transactions =
            listOf(
                TexFixture.leg1(),
                TexSameBlockFixture.leg2WithRaw(LEG2, spends = leg1Raw),
                TexSameBlockFixture.plainTransparentWithRaw(),
            )

        assertEquals(listOf<Pair<Any?, Any?>>(LEG1 to LEG2), TexApi.pairs(transactions).map(::legs))
    }

    @Test
    fun leg_two_is_read_in_tx_formats_four_five_and_six() {
        for (version in listOf(4, 5, 6)) {
            val transactions =
                listOf(
                    TexFixture.leg1(),
                    TexSameBlockFixture.leg1b(),
                    TexSameBlockFixture.leg2WithRaw(LEG2, spends = leg1Raw, version = version),
                    TexSameBlockFixture.leg2WithRaw(LEG2B, spends = leg1bRaw, version = version),
                )

            assertEquals(setOf(LEG1 to LEG2, LEG1B to LEG2B), TexApi.pairs(transactions).map(::legs).toSet(), "v$version")
        }
    }

    @Test
    fun two_stuck_sends_of_one_block_are_both_looked_up() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY + TexRulesView.CHECK_MARGIN
                    transactions =
                        listOf(
                            TexFixture.leg1(),
                            TexSameBlockFixture.leg1b(),
                            TexSameBlockFixture.leg2WithRaw(LEG2, spends = leg1Raw),
                            TexSameBlockFixture.leg2WithRaw(LEG2B, spends = leg1bRaw),
                        )
                }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            val statuses = TexApi.pass(monitor)

            assertEquals(setOf(EPHEMERAL, EPHEMERAL_B), wallet.lookups.toSet())
            assertEquals("CHECKING", TexApi.state(TexApi.statusOf(statuses, LEG2)))
            assertEquals("CHECKING", TexApi.state(TexApi.statusOf(statuses, LEG2B)))
            val check = TexSameBlockFixture.check(monitor)
            for (leg in listOf(LEG1, LEG2, LEG1B, LEG2B)) {
                assertFalse(TexSameBlockFixture.sendAgainAllowed(leg, check), "Send again on $leg of a stuck send")
            }
        }

    @Test
    fun a_send_that_still_cannot_be_matched_is_said_and_never_touched() =
        runBlocking {
            // No raw transaction to read: LEG2 may belong to LEG1 or LEG1B, and PLAIN_T may be either leg 2.
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY - 20
                    transactions =
                        listOf(
                            TexFixture.leg1(mined = null),
                            TexSameBlockFixture.leg1b(mined = null),
                            TexFixture.leg2(),
                            TexSameBlockFixture.plainTransparent(),
                        )
                }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            val statuses = TexApi.pass(monitor)

            val check = TexSameBlockFixture.check(monitor)
            for (tx in listOf(LEG1, LEG1B, LEG2, PLAIN_T)) {
                val status = assertNotNull(TexSameBlockFixture.statusFor(statuses, tx), "no status for $tx")
                assertEquals("NEEDS_SUPPORT", TexApi.state(status), tx)
                assertEquals(true, TexApi.prop(status, "unmatched"), tx)
                assertFalse(TexSameBlockFixture.sendAgainAllowed(tx, check), "Send again on $tx")
            }
            assertEquals(emptyList(), wallet.lookups)
            assertEquals(emptyList(), wallet.resubmitted)
            assertEquals(0, wallet.proposeCount)
        }

    @Test
    fun a_raw_transaction_that_cannot_be_read_leaves_the_send_unmatched_and_the_pass_runs() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY - 20
                    transactions =
                        listOf(
                            TexFixture.leg1(mined = null),
                            TexSameBlockFixture.leg1b(mined = null),
                            TexSameBlockFixture.factsWithRaw(
                                txId = LEG2,
                                mined = null,
                                outputs = listOf(TexApi.output(TexFixture.EXCHANGE_TEX, isOwn = false, kind = "TEX")),
                                rawTxHex = "0500008",
                            ),
                            TexSameBlockFixture.factsWithRaw(
                                txId = LEG2B,
                                mined = null,
                                outputs = listOf(TexApi.output(TexFixture.EXCHANGE_TEX, isOwn = false, kind = "TEX")),
                                rawTxHex = "070000800a27a726",
                            ),
                        )
                }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            val statuses = TexApi.pass(monitor)

            for (tx in listOf(LEG1, LEG1B, LEG2, LEG2B)) {
                assertEquals("NEEDS_SUPPORT", TexApi.state(assertNotNull(TexSameBlockFixture.statusFor(statuses, tx), tx)))
            }
        }

    @Test
    fun an_unmatched_group_that_is_all_mined_says_nothing() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY + 50
                    transactions =
                        listOf(
                            TexFixture.leg1(),
                            TexSameBlockFixture.leg1b(),
                            TexFixture.leg2(mined = EXPIRY - 5),
                            TexSameBlockFixture.leg2b(mined = EXPIRY - 5),
                        )
                }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            assertEquals(emptyList(), TexApi.pass(monitor))
            assertTrue(TexSameBlockFixture.sendAgainAllowed(LEG2, TexSameBlockFixture.check(monitor)))
        }

    @Test
    fun the_unmatched_status_says_what_is_known_in_english_and_spanish() =
        runBlocking {
            val wallet =
                FakeTexWallet().apply {
                    tip = EXPIRY - 20
                    transactions = listOf(TexFixture.leg1(mined = null), TexSameBlockFixture.leg1b(mined = null), TexFixture.leg2())
                }
            val status = TexApi.pass(TexApi.monitor(wallet, onTor(), idle = { true })).first()

            val texts = TexApi.static("TexTextsKt", "texTextsOf", status, Locale.US)!!
            assertEquals(ChatResourceText.stringId("tex_status_unmatched_title"), TexApi.prop(TexApi.prop(texts, "title")!!, "resId"))
            assertEquals(ChatResourceText.stringId("tex_status_unmatched_body"), TexApi.prop(TexApi.prop(texts, "body")!!, "resId"))
            val home = TexApi.static("TexTextsKt", "texHomeTextsOf", status, Locale.US)!!
            assertEquals(ChatResourceText.stringId("tex_home_unmatched_subtitle"), TexApi.prop(TexApi.prop(home, "body")!!, "resId"))

            val en = ChatResourceText.strings("values")
            val es = ChatResourceText.strings("values-es")
            val body = en.getValue("tex_status_unmatched_body")
            assertTrue("Do not send this payment again" in body, body)
            assertFalse("was not paid" in body, "nothing proved the exchange was not paid: $body")
            for (name in listOf("tex_status_unmatched_title", "tex_status_unmatched_body", "tex_home_unmatched_subtitle")) {
                assertNotNull(es[name], "no Spanish $name")
            }
            assertTrue("No vuelvas a enviar este pago" in es.getValue("tex_status_unmatched_body"))
        }

    @Test
    fun the_pairing_of_a_single_send_is_unchanged() {
        assertNull(TexSameBlockFixture.statusFor(emptyList(), LEG2))
        assertEquals(
            listOf<Pair<Any?, Any?>>(LEG1 to LEG2),
            TexApi.pairs(listOf(TexFixture.leg1(), TexFixture.leg2())).map(::legs),
        )
    }
}
