package co.electriccoin.zcash.ui.screen.chat.model

/** One output of a transaction as the wallet's note tables hold it; [memo] is its text memo, when it has one. */
data class PaymentOutput(
    val address: String?,
    val toOwnAccount: Boolean,
    val valueZatoshi: Long,
    val isChange: Boolean,
    val memo: String? = null,
)

/** ZC-1273: what the bubble of a payment says instead of its raw memo. [memo] is what the user wrote, if anything. */
data class PaymentBubbleLine(
    val isSent: Boolean,
    val amountZatoshi: Long,
    val memo: String?,
)

/**
 * ZC-1273: the display line of a payment row, drawn at render time. The row's text and the memo on the wire stay as they are:
 * Retry and the retire-only match read the text, and 2.12.4 reads the memo.
 */
object PaymentBubble {
    private const val NO_MEMO_TEXT = "Payment from ZCHAT"
    private const val MEMO_PREFIX = "Payment: "

    /** Whether [text] has the form [paymentMemoText] writes. */
    fun isPaymentText(text: String): Boolean = text == NO_MEMO_TEXT || text.startsWith(MEMO_PREFIX)

    /** Null: not a payment, or nothing true to say (no amount, or it did not go out), and the bubble keeps the row's text. */
    fun of(
        message: ChatMessage,
        amountZatoshi: Long?,
    ): PaymentBubbleLine? {
        val wentOut = !message.isPending && message.status != MessageStatus.FAILED && message.status != MessageStatus.SENDING
        return if (amountZatoshi == null || amountZatoshi <= 0L || !wentOut || !isPaymentText(message.text)) {
            null
        } else {
            PaymentBubbleLine(
                isSent = message.isOutgoing,
                amountZatoshi = amountZatoshi,
                memo =
                    message.text
                        .removePrefix(NO_MEMO_TEXT)
                        .removePrefix(MEMO_PREFIX)
                        .trim()
                        .ifEmpty { null },
            )
        }
    }

    /**
     * The amount of a payment from its outputs. A chat payment puts the amount on one output and at most that much (dust) on
     * each further memo output, in an order the chain does not keep, so the amount is the largest output: for the sender among
     * those that leave the wallet and are not a platform fee, for the receiver among those that reach it and are not change.
     * Both sides read the same figure, and the fee is in neither. ZC-1376: an output that leaves the wallet with no address
     * (sent_notes can hold none) counts when it carries a memo, which a platform fee does not. Null when no such output can be
     * read.
     */
    fun amountFromOutputs(
        isOutgoing: Boolean,
        outputs: List<PaymentOutput>,
        isPlatformFeeAddress: (String) -> Boolean,
    ): Long? =
        outputs
            .filter { output ->
                if (isOutgoing) {
                    !output.toOwnAccount &&
                        (output.address?.let(isPlatformFeeAddress) == false || (output.address == null && output.memo != null))
                } else {
                    output.toOwnAccount && !output.isChange
                }
            }.maxOfOrNull { it.valueZatoshi }
            ?.takeIf { it > 0L }
}
