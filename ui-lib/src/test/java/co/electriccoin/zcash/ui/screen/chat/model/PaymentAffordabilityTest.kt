package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C-5: the in-chat Payment dialog offered Send for any `amount <= totalBalance` (Doubles, no fees). On the
 * owner's phone "Available" read 0.00582002 while only 443,002 zatoshi of Ironwood could pay a peer, so
 * typing the shown balance always failed after the tap. Affordability is now zatoshi, against what can
 * reach a peer, with the platform fee and the network fee on top.
 */
class PaymentAffordabilityTest {
    @Test
    fun the_whole_ironwood_is_not_affordable_the_ironwood_minus_fees_is() {
        val chunks = paymentMemoChunks("")

        assertEquals(1, chunks)
        assertTrue(canAffordPayment(Zatoshi(427_002), chunks, Zatoshi(IRONWOOD)))
        assertFalse(canAffordPayment(Zatoshi(427_003), chunks, Zatoshi(IRONWOOD)))
        assertFalse(canAffordPayment(Zatoshi(IRONWOOD), chunks, Zatoshi(IRONWOOD)))
    }

    @Test
    fun the_old_available_figure_is_not_affordable() {
        assertFalse(canAffordPayment(Zatoshi(582_002), paymentMemoChunks("thanks"), Zatoshi(IRONWOOD)))
    }

    @Test
    fun zero_is_never_affordable() {
        assertFalse(canAffordPayment(Zatoshi(0), 1, Zatoshi(IRONWOOD)))
    }

    /** A long memo adds dust outputs and actions; both come out of the budget. */
    @Test
    fun a_longer_memo_costs_more() {
        val longMemo = "x".repeat(900)
        val chunks = paymentMemoChunks(longMemo)

        assertTrue(chunks > 1, "the fixture memo must span several chunks")
        assertEquals(
            100_000L + (chunks - 1) * 1_000L + 1_000L + 5_000L * (chunks + 2),
            paymentOutlay(Zatoshi(100_000), chunks).value,
        )
        assertEquals(
            paymentOutlay(Zatoshi(100_000), chunks).value - 100_000L,
            paymentFees(Zatoshi(100_000), chunks).value,
        )
    }

    @Test
    fun the_payment_memo_is_the_one_send_payment_sends() {
        assertEquals("Payment from ZCHAT", paymentMemoText(""))
        assertEquals("Payment: rent", paymentMemoText("rent"))
    }

    private companion object {
        const val IRONWOOD = 443_002L
    }
}
