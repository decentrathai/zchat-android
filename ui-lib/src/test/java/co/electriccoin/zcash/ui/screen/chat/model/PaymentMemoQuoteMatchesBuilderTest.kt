package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R5-2 (round 4 report, section 5B, third bullet) — the payment dialog's memo-output count must be the
 * count of the memos the payment send actually builds.
 *
 * `sendPayment` hands the memo to `createChunkedMessageProposal` with `isFirstMessage = false`, no
 * conversation id and no last-received txid, so the builder packs it with the v3 reply chunker; the dialog
 * sizes the same text with the v3 reply counter. Every extra memo output is another dust output and
 * another ZIP 317 action, so a quote sized by a different chunker than the builder uses is a quote the
 * wallet does not honour — the mistake round-3 item 10 fixed for the first-message prompt.
 *
 * This test pins the two together, including across the first-chunk boundary, so a later switch of the
 * payment send to another chunk format cannot silently leave the quote behind.
 */
class PaymentMemoQuoteMatchesBuilderTest {
    private val senderAddress = "usyntheticpaymentquotesender0000000000000000000000000000000000"

    @Test
    fun `the quoted memo outputs are the memo outputs the payment send builds`() {
        val memos =
            listOf("", "rent", "a".repeat(100)) +
                (455..475).map { "b".repeat(it) } +
                listOf("c".repeat(900), "d".repeat(1_400), "é".repeat(300))

        for (memo in memos) {
            val text = paymentMemoText(memo)
            val built = ZMSGProtocol.createChunkedReplyMessages(senderAddress, text).size
            assertEquals(
                "the Payment dialog quoted a different number of memo outputs than the send builds for a " +
                    "${memo.length}-character memo; each extra output is another dust output and another " +
                    "ZIP 317 action the user was not quoted",
                built,
                paymentMemoChunks(memo),
            )
        }
    }
}
