package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import java.math.BigDecimal

/**
 * ES-W — the chat's "Send Welcome ZEC" payment: the amount, the memo it carries and the least it takes from the
 * wallet, in one place, so the card, its confirm and the send cannot disagree.
 */
object WelcomeZec {
    /** The welcome amount: 0.005 ZEC. */
    const val AMOUNT_ZATOSHI = 500_000L

    /** The memo sent with it (as "Payment: Welcome to ZCHAT!", see [paymentMemoText]); unchanged on the wire. */
    const val MEMO = "Welcome to ZCHAT!"

    val amount = Zatoshi(AMOUNT_ZATOSHI)

    /**
     * [AMOUNT_ZATOSHI] as the Double ChatViewModel.sendPayment takes. sendPayment converts it back with
     * `BigDecimal.valueOf(amountZec).convertZecToZatoshi()`, which gives exactly [AMOUNT_ZATOSHI]
     * (WelcomeZecQuotesWhatLeavesTheWalletTest pins that).
     */
    val AMOUNT_ZEC: Double = BigDecimal.valueOf(AMOUNT_ZATOSHI).movePointLeft(8).toDouble()

    /**
     * The least the welcome takes from the wallet: the amount, the platform fee and the ZIP 317 fee, from the sizer the
     * Payment dialog uses. More input notes cost a larger network fee, so the confirm says "at least".
     */
    fun outlayFloor(): Zatoshi = paymentOutlay(amount, paymentMemoChunks(MEMO))
}
