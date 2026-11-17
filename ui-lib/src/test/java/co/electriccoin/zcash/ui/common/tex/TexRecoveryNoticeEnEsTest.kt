package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F3 (PLAN_V2 §2 F3 "The notice afterwards"; owner 25 Sep: "an EN+ES notice afterwards states the amount, the fee and
 * that Tor stays on; 'Send again' only once the money is provably back"): every TEX text exists in English and
 * Spanish with the same figures; the notice names what was moved, the fee and what arrived, and, when ZCHAT turned
 * Tor on, that Tor stays on and where it is turned off (the real menu names); no text says the exchange was not paid
 * before the lookup proved it, none says "failed", every text before the ZEC is back says not to send again, and
 * amounts are written by F1's formatter (never grouped).
 * Fails at 15e2b1c39: there are no TEX texts.
 */
class TexRecoveryNoticeEnEsTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")
    private val texNames = en.keys.filter { it.startsWith("tex_status_") || it.startsWith("tex_home_") || it == "tex_notification_channel" }

    @Test
    fun every_tex_text_exists_in_english_and_spanish_with_the_same_figures() {
        assertTrue(texNames.size >= 20, "expected the F3 texts, found $texNames")
        for (name in texNames) {
            val english = assertNotNull(en[name], name)
            val spanish = assertNotNull(es[name], "no Spanish $name")
            assertEquals(placeholders(english), placeholders(spanish), "$name: figures differ between EN and ES")
            assertFalse(english == spanish, "$name is not translated")
        }
    }

    @Test
    fun the_notice_names_the_amount_the_fee_what_arrived_and_that_tor_stays_on() {
        for (name in listOf("tex_status_shielded_body", "tex_status_shielded_body_tor")) {
            assertEquals(listOf("%1\$s", "%2\$s", "%3\$s"), placeholders(en.getValue(name)), name)
        }
        val torEn = en.getValue("tex_status_shielded_body_tor")
        val torEs = es.getValue("tex_status_shielded_body_tor")
        assertTrue("turned on Tor" in torEn && "stays on" in torEn, torEn)
        assertTrue("activó Tor" in torEs && "seguirá activado" in torEs, torEs)
        assertTrue("was not paid" in torEn)
        assertTrue("no recibió el pago" in torEs)
        // The way back names the menus as the app names them.
        for ((strings, text) in listOf(en to torEn, es to torEs)) {
            for (menu in listOf("settings_title", "settings_advanced_settings", "advanced_settings_privacy")) {
                val label = assertNotNull(strings[menu], menu)
                assertTrue(label in text, "the Tor notice does not name \"$label\": $text")
            }
        }
        assertFalse("Tor" in en.getValue("tex_status_shielded_body"), "the plain notice says nothing about Tor")
    }

    @Test
    fun nothing_is_called_unpaid_before_the_lookup_proved_it_and_nothing_failed() {
        for (name in listOf(
            "tex_status_checking_body",
            "tex_status_needs_tor_body",
            "tex_status_tor_failing_body",
            "tex_status_tor_off_body"
        )) {
            val text = en.getValue(name)
            assertFalse("so the exchange was not paid" in text, "$name claims the exchange was not paid: $text")
            assertTrue("has not been confirmed" in text, name)
        }
        for (name in texNames) {
            assertFalse("failed" in en.getValue(name).lowercase(), "$name says failed")
            assertFalse("error" in es.getValue(name).lowercase(), "$name (ES) says error")
        }
    }

    @Test
    fun only_texts_after_the_money_is_provably_back_or_never_left_allow_sending_again() {
        val mayResend = setOf("tex_status_not_sent_body", "tex_status_shielded_body", "tex_status_shielded_body_tor")
        val bodies = texNames.filter { it.startsWith("tex_status_") && "_body" in it }
        assertTrue(bodies.containsAll(mayResend) && bodies.size >= BODY_TEXTS, "expected the F3 state texts, found $bodies")
        for (name in bodies) {
            val text = en.getValue(name)
            if (name in mayResend) {
                assertTrue("send the payment again" in text, "$name: $text")
            } else {
                assertTrue("Do not send this payment again" in text || "do not send this payment again" in text, "$name: $text")
            }
        }
    }

    @Test
    fun amounts_are_written_by_the_field_formatter_never_grouped() {
        assertEquals("12345.6789", TexApi.static("TexTextsKt", "texZec", 1_234_567_890_000L, Locale.US))
        assertEquals("12345,6789", TexApi.static("TexTextsKt", "texZec", 1_234_567_890_000L, Locale.forLanguageTag("es-ES")))
        assertEquals("0.0001", TexApi.static("TexTextsKt", "texZec", 10_000L, Locale.US))
        assertEquals("0.00000001", TexApi.static("TexTextsKt", "texZec", 1L, Locale.US))
    }

    @Test
    fun the_notice_is_formatted_with_found_fee_and_what_arrived() {
        val status =
            TexApi.construct(
                "TexStatus",
                "key",
                TexFixture.LEG1,
                TexFixture.LEG2,
                TexApi.enumValue("TexState", "SHIELDED"),
                150_000L,
                10_000L,
                true,
                false,
                false,
                false,
                false,
            )
        val texts = TexApi.static("TexTextsKt", "texTextsOf", status, Locale.US)!!
        val body = TexApi.prop(texts, "body")!!
        assertEquals(listOf("0.0015", "0.0001", "0.0014"), TexApi.prop(body, "args"))
        assertEquals(ChatResourceText.stringId("tex_status_shielded_body_tor"), TexApi.prop(body, "resId"))
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%\d\$[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private companion object {
        /** sending ×2, not sent, checking, needs Tor, Tor failing, Tor off, returned, waiting, shielding, shielded ×2, support. */
        const val BODY_TEXTS = 13
    }
}
