package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KI-3 (fix-all 2.13.0, train T4) — splitting a bill moves from Send Payment (which paid the whole total while it
 * showed a share) to Request Payment: "Split a bill" takes the total and the number of people, including the user,
 * and asks this contact for their share only, floor(total / people) in zatoshi. No money leaves the user's wallet for
 * the split, and the request goes out as today's ZREQ (no wire change).
 *
 * Wanted, in PaymentRequestComposerDialog (ChatDialogs.kt):
 * - it takes the contact's name (recipientName), and ChatDetailView passes the conversation's display name;
 * - a "Split a bill" switch, a people counter bounded by SplitShare.MIN_PEOPLE..MAX_PEOPLE (the decrease/increase
 *   content descriptions of the old card), the total labels, the share line, the rounded-down line and the "less than
 *   1 zatoshi" line, all string resources;
 * - the share is SplitShare.of(amountZatoshi, splitPeople); with the split on, the figure passed to onSendRequest is
 *   the share, never the total; the button names that same figure and is disabled when it is not above 0;
 * - an empty reason is replaced by the prefilled split reason (a resource, in the user's language).
 *
 * Reads the view's source, as PaymentRequestDialogLocalizedTest does. SplitShare's arithmetic is SplitShareTest.
 */
class RequestComposerOffersSplitTest {
    private val block: String by lazy {
        val source = ChatResourceText.source(CHAT_DIALOGS)
        val start = source.indexOf("internal fun PaymentRequestComposerDialog(")
        check(start >= 0) { "no PaymentRequestComposerDialog in ChatDialogs" }
        val end = source.indexOf("// PAYMENT REQUEST CONTENT", start)
        check(end > start) { "no end marker after PaymentRequestComposerDialog" }
        source.substring(start, end)
    }

    private val signature: String by lazy { block.substring(0, block.indexOf(") {")) }

    @Test
    fun the_composer_takes_the_contacts_name_and_the_chat_passes_it() {
        assertTrue("recipientName: String" in signature, "PaymentRequestComposerDialog takes no recipientName")
        val detail = ChatResourceText.source(CHAT_DETAIL)
        val call = detail.substring(detail.indexOf("PaymentRequestComposerDialog("))
        val args = call.substring(0, call.indexOf("onSendRequest"))
        assertTrue(
            "recipientName = conversation.displayName" in args,
            "ChatDetailView does not hand the request dialog the contact's name",
        )
    }

    @Test
    fun the_composer_offers_split_a_bill_with_its_texts() {
        val keys =
            listOf(
                "request_payment_split_title",
                "request_payment_split_desc",
                "request_payment_split_total_label_zec",
                "request_payment_split_total_label_usd",
                "request_payment_split_people",
                "request_payment_split_share",
                "request_payment_split_rounded",
                "request_payment_split_reason",
                "request_payment_split_too_small",
                "send_payment_dialog_decrease",
                "send_payment_dialog_increase",
            )
        val missing = keys.filterNot { "R.string.$it" in block }
        assertTrue(missing.isEmpty(), "the request dialog does not show: $missing")
        assertTrue(
            "SplitShare.MIN_PEOPLE" in block && "SplitShare.MAX_PEOPLE" in block,
            "the people counter is not bounded by SplitShare's limits",
        )
    }

    @Test
    fun with_the_split_on_the_request_asks_for_the_share_not_the_total() {
        assertTrue("SplitShare.of(amountZatoshi, splitPeople)" in block, "the share is not SplitShare.of(total, people)")
        assertTrue(
            Regex("""val requestedZatoshi\s*=\s*if \(splitOn\) split\?\.share \?: 0L else amountZatoshi""").containsMatchIn(block),
            "with the split on, the requested figure is not the share (0 when there is none)",
        )
        val calls = Regex("""\bonSendRequest\(([^)]*)\)""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(listOf("requestedZatoshi, reasonToSend"), calls, "onSendRequest must be called once, with the requested figure")
        assertTrue("enabled = requestedZatoshi > 0" in block, "Request is not disabled when the requested figure is 0")
        assertTrue(
            "val requestedZecText = PaidActionCopy.zec(Zatoshi(requestedZatoshi), formatLocale)" in block &&
                "stringResource(R.string.request_payment_button, requestedZecText)" in block,
            "the button does not name the figure the request asks for",
        )
    }

    @Test
    fun an_empty_reason_is_the_prefilled_split_reason() {
        assertTrue(
            Regex("""val reasonToSend\s*=\s*if \(splitOn && reason\.isBlank\(\)\) splitReason else reason""").containsMatchIn(block),
            "an empty reason is not replaced by the split reason",
        )
    }

    private companion object {
        const val CHAT_DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
        const val CHAT_DETAIL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
    }
}
