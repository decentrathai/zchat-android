package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * QA2 U7 second follow-up (Fable review #13, finding 1): the value assertions of [memoDisplayText] that the first
 * follow-up replaced with source-text checks. The function is called and the resolved value is compared, so a branch
 * reordered above ZREQ_FULFILL, or mapped to the wrong resource, fails here. A wallet screen (the transaction detail)
 * never shows a raw protocol payload; a plain memo comes back unchanged.
 */
class MemoDisplayTextValuesTest {
    @Test
    fun a_paid_request_memo_is_the_translated_paid_request_resource() {
        assertEquals(
            stringRes(R.string.memo_display_paid_request),
            memoDisplayText("ZREQ_FULFILL|nmsg-0123456789abcdef"),
        )
    }

    @Test
    fun a_payment_request_memo_is_named_never_shown_raw() {
        assertEquals(stringRes(R.string.memo_display_payment_request), memoDisplayText("ZREQ|0.01|abc"))
    }

    @Test
    fun a_fulfilled_request_is_not_taken_for_the_request_itself() {
        // Order guard: the fulfilment must not fall into the "Payment request" label, nor the request into "Paid".
        assertNotEquals(memoDisplayText("ZREQ_FULFILL|abc"), memoDisplayText("ZREQ|0.01|abc"))
    }

    @Test
    fun a_plain_memo_passes_through_unchanged() {
        assertEquals(stringRes("thanks for lunch"), memoDisplayText("thanks for lunch"))
        // the original, untrimmed text of a plain memo is what comes back
        assertEquals(stringRes("  hello  "), memoDisplayText("  hello  "))
    }

    @Test
    fun the_other_protocol_memos_have_their_labels() {
        assertEquals(stringRes(R.string.memo_display_secure_connection_request), memoDisplayText("ZBOOT|v1|abc"))
        assertEquals(stringRes(R.string.memo_display_reaction), memoDisplayText("ZREACT|abc|👍"))
        assertEquals(stringRes(R.string.memo_display_read_receipt), memoDisplayText("ZRCPT|abc"))
        assertEquals(stringRes(R.string.memo_display_status_update), memoDisplayText("ZSTAT|abc"))
        assertEquals(stringRes(R.string.memo_display_time_locked), memoDisplayText("ZTL|abc"))
        assertEquals(stringRes(R.string.memo_display_time_locked), memoDisplayText("ZUNLOCK|abc"))
        assertEquals(stringRes(R.string.memo_display_control_message), memoDisplayText("ZEXP|abc"))
        assertEquals(stringRes(R.string.memo_display_control_message), memoDisplayText("ZMODE|abc"))
        assertEquals(stringRes(R.string.memo_display_zchat_message), memoDisplayText("ZMSG|v4|abc"))
        assertEquals(stringRes(R.string.memo_display_zchat_message), memoDisplayText("ZMSG:abc"))
    }

    @Test
    fun a_file_memo_is_named_with_its_size_and_an_unreadable_one_is_an_attachment() {
        assertEquals(stringRes(R.string.memo_display_attachment), memoDisplayText("ZFILE|not-a-file"))
        val raw = "ZFILE|${"0123456789abcdef".repeat(2)}|p|2048|https://blossom.primal.net/x.png|key|blur"
        val file = ZFILEMessage.parse(raw)
        // ZC-1733: the label is a resource taking the file's size (its texts are pinned by P3bNitsQa2Test), not an English literal.
        val label = memoDisplayText(raw)
        assertTrue(
            label is StringResource.ByResource && label.args == listOf<Any>(file!!.sizeText),
            "a readable file memo is not a resource taking the file's size: $label",
        )
    }
}
