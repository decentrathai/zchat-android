package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote

/**
 * D-6 (fix-all 2.13.0, owner W-10) — what a payment confirm (the Send Payment dialog, the pay-request confirm, the Welcome
 * ZEC confirm) may show and pay, from ChatViewModel.quotePayment. Only [Exact] enables Pay; there is no fallback to a
 * floor ("at least").
 */
sealed interface PaymentQuote {
    /**
     * The SDK priced this payment: [amount] to the contact, the [platformFee], the [networkFee] and, for a memo of several
     * memo outputs, the dust each extra one carries ([memoOutputs]); [total] is all of it. Pay hands [quote] to the send,
     * which refuses (and pays nothing) when the proposal built after Pay is not this transaction.
     */
    data class Exact(
        val quote: ChatSendQuote,
        val amount: Zatoshi,
        val platformFee: Zatoshi,
        val networkFee: Zatoshi,
        val total: Zatoshi,
        val chunks: Int,
    ) : PaymentQuote {
        /** Everything on top of [amount]. */
        val fees: Zatoshi get() = Zatoshi(total.value - amount.value)

        /** The dust on the extra memo outputs of a long memo (0 for one memo). */
        val memoOutputs: Zatoshi get() = Zatoshi(total.value - amount.value - platformFee.value - networkFee.value)
    }

    /** The wallet cannot pay it: the payment needs at least [floor]; [spendable] can go to others now. */
    data class NotEnough(
        val floor: Zatoshi,
        val spendable: Zatoshi,
    ) : PaymentQuote

    /** Ironwood that is still confirming would pay it: wait. */
    data object StillConfirming : PaymentQuote

    /**
     * No exact price: the probe failed, needs Orchard inputs or more than one transaction, or the chat cannot pay at all.
     * [reason] is a [ChatSendCopy] text that says why (a Keystone account, our own address, sealed Orchard), or null.
     */
    data class Unavailable(
        val reason: String? = null,
    ) : PaymentQuote
}

/** The memo a payment-request fulfil carries (one raw memo): the fulfil sends it and its confirm prices it. */
fun fulfilMemoText(requestId: String): String = "ZREQ_FULFILL|$requestId"
