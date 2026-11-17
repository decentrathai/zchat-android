package co.electriccoin.zcash.ui.common.tex

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 fix-commit tests): the TEX checker's per-send rule table for plain sends,
 * and what a Wallet send's ids add up to ([plainSendsOutcome]).
 *
 * A plain send is a sent transaction of the ZCHAT account that is not a shielding and has no TEX status (neither a
 * paired leg nor an unmatched candidate). Mined: in `minedSends`. Unmined: waiting for its expiry, "checking" when the
 * expiry or the scanned tip is unknown (an expiry of 0 is never dead), NOT_SENT only after F4's proof. Every txid and
 * address is synthetic and used by this class only.
 */
class PlainSendVerdictTest {
    private companion object {
        const val E = 6_000L
        const val PAYEE = "u1fixtureplainpayeenotreal0004"
        val MINED: String = txid(0xF1)
        val WAITING: String = txid(0xF3)
        val NO_EXPIRY: String = txid(0xF5)
        val ZERO_EXPIRY: String = txid(0xF7)
        val SHIELDING: String = txid(0xF9)
        val RECEIVED: String = txid(0xFB)
        val DEAD_A: String = txid(0x31)
        val DEAD_B: String = txid(0x35)
    }

    private fun onTor() = FakeTexTor().apply { on = true }

    private fun facts(
        txId: String,
        mined: Long? = null,
        expiry: Long? = E,
        isSent: Boolean = true,
        isShielding: Boolean = false,
    ) = TexTxFacts(txId, txId.reversedHex(), isSent, isShielding, mined, expiry, listOf(TexOutput(PAYEE, false, TexAddressKind.OTHER)))

    private fun checkAfter(
        transactions: List<TexTxFacts>,
        vararg tips: Long?,
    ): TexCheck =
        runBlocking {
            val wallet = FakeTexWallet().apply { this.transactions = transactions }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })
            for (tip in tips) {
                wallet.tip = tip
                TexApi.pass(monitor)
            }
            TexSameBlockFixture.check(monitor) as TexCheck
        }

    @Test
    fun each_kind_of_send_gets_its_verdict() {
        val check =
            checkAfter(
                listOf(
                    facts(MINED, mined = E - 30),
                    facts(WAITING),
                    facts(NO_EXPIRY, expiry = null),
                    facts(ZERO_EXPIRY, expiry = 0),
                    facts(SHIELDING, isShielding = true),
                    facts(RECEIVED, isSent = false),
                ),
                E + 100,
                E + 101,
            )
        assertEquals(setOf(MINED), check.minedSends)
        assertEquals(PlainSendVerdict.NotSent, check.plainSends[WAITING])
        assertEquals(PlainSendVerdict.Waiting(null, E + 101), check.plainSends[NO_EXPIRY])
        assertEquals(PlainSendVerdict.Waiting(null, E + 101), check.plainSends[ZERO_EXPIRY], "an expiry of 0 is never dead")
        assertNull(check.plainSends[SHIELDING], "a shielding is judged as a plain send")
        assertNull(check.plainSends[RECEIVED], "a received transaction is judged as a plain send")
        assertFalse(texSendAgainAllowed(SHIELDING, check))
        assertFalse(texSendAgainAllowed(RECEIVED, check))
    }

    @Test
    fun before_the_expiry_it_waits_and_without_a_tip_nothing_is_known() {
        assertEquals(PlainSendVerdict.Waiting(E, E - 5), checkAfter(listOf(facts(WAITING)), E - 5).plainSends[WAITING])
        assertEquals(PlainSendVerdict.Waiting(null, null), checkAfter(listOf(facts(NO_EXPIRY, expiry = E)), null).plainSends[NO_EXPIRY])
    }

    @Test
    fun tex_legs_and_unmatched_candidates_are_not_plain_sends() {
        val paired = checkAfter(listOf(TexFixture.leg1(), TexFixture.leg2(mined = E - 5)).map { it as TexTxFacts }, E)
        assertTrue(paired.plainSends.isEmpty() && paired.minedSends.isEmpty(), "a TEX leg was judged as a plain send: $paired")

        val unmatched =
            checkAfter(
                listOf(
                    TexFixture.leg1(mined = null),
                    TexSameBlockFixture.leg1b(mined = null),
                    TexFixture.leg2(),
                ).map { it as TexTxFacts },
                TexFixture.EXPIRY - 20,
            )
        assertTrue(unmatched.plainSends.isEmpty(), "an unmatched candidate was judged as a plain send")
    }

    @Test
    fun a_wallet_sends_ids_add_up_to_one_outcome() {
        val check = checkAfter(listOf(facts(MINED, mined = E - 30), facts(DEAD_A), facts(DEAD_B)), E + 10, E + 11)
        assertEquals(PlainSendsOutcome.Sent, plainSendsOutcome(listOf(MINED), check))
        assertEquals(PlainSendsOutcome.NotSent, plainSendsOutcome(listOf(DEAD_A, DEAD_B), check))
        assertNull(plainSendsOutcome(listOf(MINED, DEAD_A), check), "part mined, part dead is not one outcome")
        assertNull(plainSendsOutcome(listOf(txid(0x7F)), check), "an id the pass did not judge")
        assertNull(plainSendsOutcome(emptyList(), check))
        assertNull(plainSendsOutcome(listOf(MINED), null))

        val waiting = checkAfter(listOf(facts(WAITING), facts(MINED, mined = E - 30)), E - 20)
        assertEquals(PlainSendsOutcome.Waiting(E, E - 20), plainSendsOutcome(listOf(MINED, WAITING), waiting))
        assertNull(plainSendsOutcome(listOf(NO_EXPIRY), checkAfter(listOf(facts(NO_EXPIRY, expiry = null)), E)))
    }
}
