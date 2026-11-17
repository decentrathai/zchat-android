package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D-6 (fix-all 2.13.0, train T5b; t5b SPECS §5, owner W-10; skill app-texts) — the texts of the three payment confirms
 * name a floor: `pay_request_confirm_body` and `welcome_zec_confirm_body` say "at least %3$s ZEC leaves your wallet" /
 * "al menos", and the Send Payment dialog says "+ about %1$s ZEC in fees" / "unos". There are no texts for the exact
 * fees, for a price that is still being worked out or cannot be, for a wallet that cannot pay it, or for a payment whose
 * price changed before it went out.
 *
 * Wanted, English and Spanish with the same placeholders:
 * - `pay_request_confirm_body` and `welcome_zec_confirm_body` say "exactly %3$s" / "exactamente %3$s" and name the
 *   platform fee (%4$s) and the network fee (%5$s);
 * - `send_payment_dialog_fees_exact` (total fees, platform, network) and `send_payment_dialog_fees_exact_memos` (the same
 *   and the extra memo outputs a long memo adds), with no "about" / "unos";
 * - `payment_quote_working`, `payment_quote_unavailable` (nothing can be paid yet), `payment_quote_not_enough` (the
 *   least the payment needs, %1$s, and what can go to others now, %2$s);
 * - `chat_payment_price_changed`, held byte for byte by `ChatSendCopy.PAYMENT_PRICE_CHANGED` and mapped to its key; it
 *   says nothing was paid.
 * The constant is reached by name (it does not exist at HEAD).
 */
class ExactPaymentTextsHaveSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val keys =
        listOf(
            "pay_request_confirm_body",
            "welcome_zec_confirm_body",
            "send_payment_dialog_fees_exact",
            "send_payment_dialog_fees_exact_memos",
            "payment_quote_working",
            "payment_quote_unavailable",
            "payment_quote_not_enough",
            "chat_payment_price_changed",
        )

    private val placeholder = Regex("""%\d+\$[sd]""")

    @Test
    fun every_key_is_in_english_and_spanish_with_the_same_placeholders() {
        for (key in keys) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                placeholder.findAll(english).map { it.value }.toSet(),
                placeholder.findAll(spanish).map { it.value }.toSet(),
                "$key: English and Spanish use different placeholders: \"$english\" / \"$spanish\"",
            )
        }
    }

    @Test
    fun the_two_confirm_bodies_name_one_exact_figure_and_both_fees() {
        for (key in listOf("pay_request_confirm_body", "welcome_zec_confirm_body")) {
            val english = en.getValue(key)
            val spanish = es.getValue(key)
            assertTrue("exactly %3\$s ZEC leaves your wallet" in english, "$key: $english")
            assertTrue("exactamente %3\$s ZEC" in spanish, "$key: $spanish")
            assertTrue("platform fee (%4\$s ZEC)" in english && "network fee (%5\$s ZEC)" in english, "$key: $english")
            assertTrue("plataforma (%4\$s ZEC)" in spanish && "red (%5\$s ZEC)" in spanish, "$key: $spanish")
            assertTrue("can't be undone" in english, "$key: $english")
            assertTrue("No se puede deshacer" in spanish, "$key: $spanish")
        }
    }

    @Test
    fun no_payment_figure_says_at_least_or_about() {
        val exactTexts =
            listOf(
                "pay_request_confirm_body",
                "welcome_zec_confirm_body",
                "send_payment_dialog_fees_exact",
                "send_payment_dialog_fees_exact_memos",
            )
        for (key in exactTexts) {
            val english = en.getValue(key).lowercase()
            val spanish = es.getValue(key).lowercase()
            for (floor in listOf("at least", "about", "around", "approximately")) {
                assertTrue(floor !in english, "$key still says \"$floor\": $english")
            }
            for (floor in listOf("al menos", "unos ", "aproximadamente", "alrededor")) {
                assertTrue(floor !in spanish, "$key still says \"$floor\": $spanish")
            }
        }
        assertEquals(
            listOf("%1\$s", "%2\$s", "%3\$s"),
            placeholder.findAll(en.getValue("send_payment_dialog_fees_exact")).map { it.value }.toList(),
        )
        assertEquals(
            listOf("%1\$s", "%2\$s", "%3\$s", "%4\$s"),
            placeholder.findAll(en.getValue("send_payment_dialog_fees_exact_memos")).map { it.value }.toList(),
        )
    }

    @Test
    fun the_quote_states_say_what_happens() {
        assertTrue("exact price" in en.getValue("payment_quote_working"), en.getValue("payment_quote_working"))
        assertTrue("precio exacto" in es.getValue("payment_quote_working"), es.getValue("payment_quote_working"))
        assertTrue("nothing can be paid yet" in en.getValue("payment_quote_unavailable"), en.getValue("payment_quote_unavailable"))
        assertTrue("no se puede pagar nada" in es.getValue("payment_quote_unavailable"), es.getValue("payment_quote_unavailable"))
        val notEnough = en.getValue("payment_quote_not_enough")
        assertTrue("%1\$s" in notEnough && "%2\$s" in notEnough, "both figures: $notEnough")
    }

    @Test
    fun the_price_changed_notice_says_nothing_was_paid_and_is_chat_send_copy() {
        val english = en.getValue("chat_payment_price_changed")
        val spanish = es.getValue("chat_payment_price_changed")
        assertTrue(english.startsWith("Not sent") && "Nothing was paid" in english, english)
        assertTrue(spanish.startsWith("No enviado") && "No se pagó nada" in spanish, spanish)
        assertTrue('%' !in english && '%' !in spanish, "no placeholder")
        val constant =
            runCatching { ChatSendCopy::class.java.getField("PAYMENT_PRICE_CHANGED").get(null) as String }
                .getOrElse { throw AssertionError("ChatSendCopy.PAYMENT_PRICE_CHANGED does not exist (D-6)") }
        assertEquals(english, constant, "ChatSendCopy.PAYMENT_PRICE_CHANGED is not the English text")
        val id = assertNotNull(ChatSendCopy.localizedResource(constant), "ChatSendCopy's map does not know PAYMENT_PRICE_CHANGED")
        assertEquals("chat_payment_price_changed", ChatResourceText.stringName(id))
    }
}
