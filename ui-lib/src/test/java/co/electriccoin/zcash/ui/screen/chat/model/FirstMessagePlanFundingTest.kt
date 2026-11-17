package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.built
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.decide
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.nameOf
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.outpoint
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.short
import org.junit.Test
import kotlin.test.assertEquals

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.1-2.3, t5b SPECS §2 C-M2-4, C-M2-5): the first-message question
 * accepted a plan the wallet could not fund. On the Seeker (Ironwood 69,376 in 43 notes: 14,376 + 10,000 + 5,000 and
 * forty 1,000-zat notes) "2 on-chain transactions: at least 0.00034 ZEC" was asked, Pay sent the KEX (17,000), and the
 * message then failed for funds: no selection of those notes pays both legs.
 *
 * The rule (pure, over two never-stored SDK probes of the current wallet, one per leg): Fundable only when both legs
 * were built and either (1) their inputs are disjoint (two different note sets pay them now), or (2) one change note
 * of the KEX alone pays the message once it confirms (>= the message's minimum, 17,000 for one memo at 1,000 zat).
 * Anything else is NotFundable: a Short leg, unreadable inputs (fail closed), or an input whose output index could not
 * be read (two notes of one transaction then look alike, so they count as the same note).
 *
 * At HEAD none of PlanProbe, FirstMessagePlan or FirstMessagePlanFunding exists, and ProposalInputOutpoint has no
 * output index (reached by name: [FirstMessagePlanApi]). All txids are synthetic.
 */
class FirstMessagePlanFundingTest {
    private companion object {
        const val MESSAGE_ALONE = 17_000L
        const val TX_A = "aa00000000000000000000000000000000000000000000000000000000000001"
        const val TX_B = "bb00000000000000000000000000000000000000000000000000000000000002"
        const val TX_C = "cc00000000000000000000000000000000000000000000000000000000000003"
    }

    private val note14376 = outpoint(TX_A, 0, 14_376)
    private val note10000 = outpoint(TX_B, 0, 10_000)
    private val note5000 = outpoint(TX_C, 0, 5_000)

    @Test
    fun `the Seeker's notes cannot pay both legs - refused before anything is paid`() {
        // KEX: 14,376 + 5,000 - 2,000 outputs - 15,000 fee = change 2,376. Message: 14,376 + 10,000 (overlap).
        val kex = built(inputs = listOf(note14376, note5000), change = listOf(2_376))
        val message = built(inputs = listOf(note14376, note10000), change = listOf(7_376))

        assertEquals("NotFundable", nameOf(decide(kex, message, MESSAGE_ALONE)))
    }

    @Test
    fun `the Seeker's notes, the other reconstruction (a 3-input KEX), are refused too`() {
        val kex = built(inputs = listOf(note14376, note10000, note5000), change = listOf(12_376))
        val message = built(inputs = listOf(note14376, note10000), change = listOf(7_376))

        assertEquals("NotFundable", nameOf(decide(kex, message, MESSAGE_ALONE)))
    }

    @Test
    fun `disjoint inputs are fundable - two different notes pay the two legs now`() {
        val kex = built(inputs = listOf(note14376, note5000), change = listOf(2_376))
        val message = built(inputs = listOf(outpoint(TX_B, 1, 20_000)), change = listOf(3_000))

        assertEquals("Fundable", nameOf(decide(kex, message, MESSAGE_ALONE)))
    }

    @Test
    fun `one 40,000 note is fundable - the KEX's change (23,000) pays the message once it confirms`() {
        val note = outpoint(TX_A, 0, 40_000)
        val kex = built(inputs = listOf(note), change = listOf(23_000))
        val message = built(inputs = listOf(note), change = listOf(23_000))

        assertEquals("Fundable", nameOf(decide(kex, message, MESSAGE_ALONE)))
    }

    @Test
    fun `one 30,000 note is not fundable - the KEX's change (13,000) is below the message's 17,000`() {
        val note = outpoint(TX_A, 0, 30_000)
        val kex = built(inputs = listOf(note), change = listOf(13_000))
        val message = built(inputs = listOf(note), change = listOf(13_000))

        assertEquals("NotFundable", nameOf(decide(kex, message, MESSAGE_ALONE)))
    }

    @Test
    fun `a leg the wallet cannot build is not fundable`() {
        val note = outpoint(TX_A, 0, 40_000)
        val leg = built(inputs = listOf(note), change = listOf(23_000))

        assertEquals("NotFundable", nameOf(decide(short(17_000), leg, MESSAGE_ALONE)), "the KEX leg was short")
        assertEquals("NotFundable", nameOf(decide(leg, short(17_000), MESSAGE_ALONE)), "the message leg was short")
        assertEquals("NotFundable", nameOf(decide(short(), short(), MESSAGE_ALONE)), "both legs were short")
    }

    @Test
    fun `unreadable inputs are not fundable (fail closed)`() {
        val note = outpoint(TX_A, 0, 40_000)
        val leg = built(inputs = listOf(note), change = listOf(23_000))

        assertEquals("NotFundable", nameOf(decide(built(inputs = null, change = listOf(23_000)), leg, MESSAGE_ALONE)))
        assertEquals("NotFundable", nameOf(decide(leg, built(inputs = null, change = listOf(23_000)), MESSAGE_ALONE)))
        assertEquals(
            "NotFundable",
            nameOf(decide(built(inputs = emptyList(), change = listOf(23_000)), leg, MESSAGE_ALONE)),
            "a proposal that spends nothing was read wrong",
        )
    }

    @Test
    fun `unreadable change on overlapping inputs is not fundable`() {
        val note = outpoint(TX_A, 0, 40_000)

        assertEquals(
            "NotFundable",
            nameOf(decide(built(inputs = listOf(note), change = null), built(inputs = listOf(note), change = null), MESSAGE_ALONE)),
        )
    }

    @Test
    fun `two notes of one transaction are told apart by their output index`() {
        val kex = built(inputs = listOf(outpoint(TX_A, 0, 20_000)), change = listOf(3_000))
        val message = built(inputs = listOf(outpoint(TX_A, 1, 20_000)), change = listOf(3_000))

        assertEquals("Fundable", nameOf(decide(kex, message, MESSAGE_ALONE)), "outputs 0 and 1 of one transaction are two notes")
    }

    @Test
    fun `an input whose output index or txid could not be read counts as the same note (fail closed)`() {
        val kex = built(inputs = listOf(outpoint(TX_A, null, 20_000)), change = listOf(3_000))
        val message = built(inputs = listOf(outpoint(TX_A, null, 20_000)), change = listOf(3_000))
        assertEquals("NotFundable", nameOf(decide(kex, message, MESSAGE_ALONE)), "the index was unknown")

        val noTx = built(inputs = listOf(outpoint(null, 0, 20_000)), change = listOf(3_000))
        val other = built(inputs = listOf(outpoint(TX_B, 0, 20_000)), change = listOf(3_000))
        assertEquals("NotFundable", nameOf(decide(noTx, other, MESSAGE_ALONE)), "a prior-step input has no txid")
    }

    @Test
    fun `a change note exactly at the message's minimum is fundable, one zatoshi less is not`() {
        val note = outpoint(TX_A, 0, 34_000)
        val atMinimum = built(inputs = listOf(note), change = listOf(MESSAGE_ALONE))
        val below = built(inputs = listOf(note), change = listOf(MESSAGE_ALONE - 1))
        val message = built(inputs = listOf(note), change = listOf(MESSAGE_ALONE))

        assertEquals("Fundable", nameOf(decide(atMinimum, message, MESSAGE_ALONE)))
        assertEquals("NotFundable", nameOf(decide(below, message, MESSAGE_ALONE)))
    }
}
