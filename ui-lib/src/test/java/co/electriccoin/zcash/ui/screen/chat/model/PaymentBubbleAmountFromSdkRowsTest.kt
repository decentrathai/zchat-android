package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * ZC-1376, phone matrix 1 Nov (TIMELINE3 lines 45, 51): a 0.00001 ZEC chat payment, mined, 5+ confirmations: the HONOR debug
 * receiver's bubble and the Fold sender's bubble both say "Payment: R3-D-pay" with no amount.
 *
 * The SDK 3.0.0 v_tx_outputs rows as the native library defines them: a received output (any pool, Ironwood is pool 4 in
 * v_received_outputs) has to_address NULL, to_account_uuid set, is_change 0; an output sent out has to_address from
 * sent_notes, which can be NULL, and carries the memo. Must hold: the receiver's row gives the amount; an outgoing row with
 * no address that carries the payment's memo is still the payee output. An outgoing row with no address and no memo stays
 * uncounted (PaymentAmountFromOutputsTest). Expected seam: PaymentOutput(address, toOwnAccount, valueZatoshi, isChange,
 * memo: String? = null), read by name so this file compiles before it exists.
 */
class PaymentBubbleAmountFromSdkRowsTest {
    private val fee = "fee-address"

    private fun amount(
        outgoing: Boolean,
        vararg outputs: PaymentOutput,
    ) = PaymentBubble.amountFromOutputs(outgoing, outputs.toList()) { it == fee }

    /** An output with its memo, through the 5-argument constructor the fix adds. */
    private fun withMemo(
        address: String?,
        own: Boolean,
        value: Long,
        change: Boolean,
        memo: String,
    ): PaymentOutput {
        val constructor =
            PaymentOutput::class.java.constructors.firstOrNull {
                it.parameterTypes.size == 5 && it.parameterTypes[4] == String::class.java
            } ?: fail("PaymentOutput carries no memo: an outgoing output whose to_address is NULL cannot be told from dust")
        return constructor.newInstance(address, own, value, change, memo) as PaymentOutput
    }

    @Test
    fun the_receivers_row_with_no_address_and_its_account_set_gives_the_amount() {
        // v_tx_outputs for the receiver: to_address NULL, to_account_uuid set (toOwnAccount), is_change 0; a dust memo output.
        val received = PaymentOutput(address = null, toOwnAccount = true, valueZatoshi = 1_000L, isChange = false)
        val dust = PaymentOutput(address = null, toOwnAccount = true, valueZatoshi = 1L, isChange = false)
        assertEquals(1_000L, amount(false, dust, received), "the receiver's payment row gives no amount")
    }

    @Test
    fun an_outgoing_row_with_no_address_that_carries_the_memo_is_the_payee_output() {
        val payee = withMemo(address = null, own = false, value = 1_000L, change = false, memo = "Payment: R3-D-pay")
        val platformFee = PaymentOutput(address = fee, toOwnAccount = false, valueZatoshi = 1_000L, isChange = false)
        val change = PaymentOutput(address = null, toOwnAccount = true, valueZatoshi = 54_604L, isChange = true)

        val figure = amount(true, platformFee, payee, change)

        assertNotNull(figure, "the sender's bubble shows no amount for a payee row whose to_address is NULL")
        assertEquals(1_000L, figure)
    }
}
