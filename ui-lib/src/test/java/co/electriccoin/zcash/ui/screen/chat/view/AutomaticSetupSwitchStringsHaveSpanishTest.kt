package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KI-23 (ZC-020; SPEC D7, §7) — the texts of the Advanced Settings row "Automatic setup payments" are string resources
 * in English and Spanish (an agent draft for the native-speaker pass), with the same placeholders (none).
 *
 * The texts claim only what the code does. Off means "ZChat pays no setup transaction unless you allow it": never
 * "free" (a setup transaction costs ZEC once allowed) and never "asks again" (a question answered "Not now" is not
 * raised again for the same waiting setups). ZC-1013: the row also turns the payments back on, behind a confirm whose
 * text is the standing consent (what it allows, the price per setup message, that it can be turned off again).
 */
class AutomaticSetupSwitchStringsHaveSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val expectedEnglish =
        mapOf(
            "advanced_settings_auto_setup_payments" to "Automatic setup payments",
            "advanced_settings_auto_setup_payments_subtitle_on" to
                "On: ZChat pays handshake transactions and group key answers without asking each time",
            "advanced_settings_auto_setup_payments_subtitle_off" to "Off: ZChat pays no setup transaction unless you allow it",
            "advanced_settings_auto_setup_payments_off_title" to "Turn off automatic setup payments?",
            "advanced_settings_auto_setup_payments_off_text" to
                "ZChat will not pay for a chat setup transaction unless you allow it. A chat or group that needs one waits " +
                "until you allow it. Messages you send yourself and group joins are not affected.",
            "advanced_settings_auto_setup_payments_off_button" to "Turn off",
            "advanced_settings_auto_setup_payments_cancel_button" to "Cancel",
            "advanced_settings_auto_setup_payments_on_title" to "Turn on automatic setup payments?",
            "advanced_settings_auto_setup_payments_on_text" to
                "Setting up secure chats sends small on-chain handshakes. They are not messages you wrote, and they cost " +
                "ZEC: at least %1\$s ZEC each, including fees (more if your balance is spread over many small notes). " +
                "With this on, ZChat pays chat handshakes and group key answers by itself when it sends them, without " +
                "asking, including handshakes already waiting. Turning this on pays nothing by itself. Joining a group " +
                "still asks first. You can turn it off again here.",
            "advanced_settings_auto_setup_payments_on_button" to "Turn on",
            "advanced_settings_auto_setup_payments_ok_button" to "OK",
            "advanced_settings_auto_setup_payments_not_saved" to
                "Could not save the change. Automatic setup payments are unchanged.",
        )

    @Test
    fun every_key_is_english_and_spanish_with_the_same_placeholders() {
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
    fun no_text_says_free_or_asks_again() {
        val keys = expectedEnglish.keys
        for (key in keys) {
            val english = en[key].orEmpty().lowercase()
            for (claim in listOf("free", "ask again", "asks again", "ask you again", "every time", "always ask")) {
                assertFalse(claim in english, "$key promises \"$claim\": $english")
            }
            val spanish = es[key].orEmpty().lowercase()
            val spanishClaims =
                listOf("gratis", "gratuit", "vuelve a preguntar", "volverá a preguntar", "preguntará de nuevo", "siempre pregunta")
            for (claim in spanishClaims) {
                assertFalse(claim in spanish, "$key promises \"$claim\": $spanish")
            }
        }
    }

    @Test
    fun the_on_text_says_what_it_allows_the_price_and_the_way_back() {
        val english = en["advanced_settings_auto_setup_payments_on_text"].orEmpty()
        val spanish = es["advanced_settings_auto_setup_payments_on_text"].orEmpty()
        for (words in listOf("handshakes", "cost", "%1\$s ZEC each", "without asking", "pays nothing by itself", "turn it off again")) {
            assertTrue(words in english, "the turn-on text does not say \"$words\"")
        }
        for (words in listOf("transacciones de enlace", "%1\$s ZEC", "sin preguntar", "desactivarlo")) {
            assertTrue(words in spanish, "the Spanish turn-on text does not say «$words»")
        }
    }
}
