package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1273: the figure a payment bubble shows comes from the transaction's outputs, never its total. The read of the SDK's
 * view itself needs a real wallet database, so it is a phone check; the choice among the outputs is proven here.
 */
class PaymentAmountFromOutputsTest {
    private val fee = "fee-address"

    private fun out(
        value: Long,
        address: String? = "peer",
        own: Boolean = false,
        change: Boolean = false,
    ) = PaymentOutput(address, own, value, change)

    private fun amount(
        outgoing: Boolean,
        vararg outputs: PaymentOutput,
    ) = PaymentBubble.amountFromOutputs(outgoing, outputs.toList()) { it == fee }

    @Test
    fun the_sender_sees_the_payee_output_without_platform_fee_or_change() {
        assertEquals(
            156_000L,
            amount(true, out(156_000L), out(1_000L, fee), out(9_000_000L, "own", own = true, change = true)),
        )
    }

    @Test
    fun a_second_memo_output_does_not_add_to_the_amount_in_any_order() {
        assertEquals(156_000L, amount(true, out(1_000L), out(156_000L), out(1_000L, fee)))
        assertEquals(156_000L, amount(false, out(1_000L, own = true), out(156_000L, own = true)))
    }

    @Test
    fun the_receiver_sees_its_own_output_and_never_change() {
        assertEquals(156_000L, amount(false, out(156_000L, own = true), out(1_000L, fee)))
        assertNull(amount(false, out(5_000L, own = true, change = true)))
    }

    @Test
    fun outputs_that_say_nothing_give_no_amount() {
        assertNull(amount(true))
        assertNull(amount(false))
        assertNull(amount(true, out(1_000L, fee)), "only a platform fee left the wallet")
        assertNull(amount(true, out(1_000L, address = null)), "an output with no address names no payee")
        assertNull(amount(true, out(5_000L, "own", own = true)), "a transfer to this wallet pays no contact")
        assertNull(amount(true, out(0L)))
    }

    @Test
    fun the_figure_is_exact_to_the_last_zatoshi() {
        assertEquals("0.00000001", zecDisplay(Zatoshi(1L), Locale.US))
        assertEquals("0.00156", zecDisplay(Zatoshi(156_000L), Locale.US))
        assertEquals("0.12345678", zecDisplay(Zatoshi(12_345_678L), Locale.US))
    }

    /** Source read: the Navigator needs a real NavController. */
    @Test
    fun a_chat_gone_from_the_stack_falls_back_to_the_root() {
        val navigator = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/Navigator.kt")
        assertTrue("if (!popped && command.route == ChatDetail::class) backToRoot()" in navigator)
    }
}
