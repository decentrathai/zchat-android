package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KI-3 (fix-all 2.13.0, train T4) — the "Split a bill" texts of Request Payment are string resources in English and
 * Spanish (an agent draft for the native-speaker pass), with the same placeholders in the same order, and the Send
 * Payment split texts that promised a per-person payment are gone in both languages. The two counter content
 * descriptions stay: the request dialog's counter uses them.
 */
class SplitStringsHaveSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    /** The English each new key carries: it says only what the code does (a request for one share, rounded down). */
    private val expectedEnglish =
        mapOf(
            "request_payment_split_title" to "Split a bill",
            "request_payment_split_desc" to "Ask this contact for their share only",
            "request_payment_split_total_label_zec" to "Total (ZEC)",
            "request_payment_split_total_label_usd" to "Total (USD)",
            "request_payment_split_people" to "people, including you",
            "request_payment_split_share" to "%1\$s's share: %2\$s ZEC",
            "request_payment_split_rounded" to "Rounded down by %1\$d zatoshi",
            "request_payment_split_reason" to "Share of %1\$s ZEC, split %2\$d ways",
            "request_payment_split_too_small" to "The share would be less than 1 zatoshi",
        )

    @Test
    fun every_new_key_is_english_and_spanish_with_the_same_placeholders() {
        expectedEnglish.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(english),
                SpanishSendPaymentDialogTest.placeholders(spanish),
                "$key: the Spanish placeholders differ",
            )
        }
    }

    @Test
    fun the_send_payment_split_texts_are_gone() {
        for (key in listOf(
            "send_payment_dialog_split_title",
            "send_payment_dialog_split_desc",
            "send_payment_dialog_each_pays",
            "send_payment_dialog_people",
        )) {
            assertFalse(key in en, "English $key is still defined")
            assertFalse(key in es, "Spanish $key is still defined")
        }
        for (key in listOf("send_payment_dialog_decrease", "send_payment_dialog_increase")) {
            assertTrue(key in en && key in es, "$key is used by the request dialog's counter")
        }
    }
}
