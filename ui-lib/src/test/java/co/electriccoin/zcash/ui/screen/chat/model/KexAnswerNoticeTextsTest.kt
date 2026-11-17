package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * ZC-1230, ZC-1233 follow-up — the two new texts in both languages, and the one notice with a figure in it.
 *
 * Limit: AndroidChat's localizedSendNotice is private and needs a Context, which these JVM tests have no double for. The
 * figure's path is run here from the real pieces (the notice, its parser, zecDisplay, the real Spanish line); that the
 * function joins them with the phone's locale is pinned on its source.
 */
class KexAnswerNoticeTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val spanish = Locale.forLanguageTag("es")

    private fun placeholders(text: String): List<String> {
        val found = Regex("""%\d+\$[sd]""").findAll(text)
        return found.map { it.value }.toList().sorted()
    }

    @Test
    fun `both texts exist in both languages, translated, with the same arguments`() {
        for (key in listOf("chat_send_kex_answer_shortfall", "paid_action_kex_answer_true_price")) {
            val english = en[key]
            val translated = es[key]
            assertTrue("$key has no English", !english.isNullOrBlank())
            assertTrue("$key has no Spanish", !translated.isNullOrBlank())
            assertNotEquals("$key is English in the Spanish file", english, translated)
            assertEquals("$key arguments differ", placeholders(english!!), placeholders(translated!!))
        }
        assertEquals(listOf("%1\$s"), placeholders(en.getValue("chat_send_kex_answer_shortfall")))
        assertEquals(emptyList<String>(), placeholders(en.getValue("paid_action_kex_answer_true_price")))
    }

    @Test
    fun `the question's text promises the same in both languages - nothing paid yet, nothing on Cancel`() {
        val english = en.getValue("paid_action_kex_answer_true_price")
        val translated = es.getValue("paid_action_kex_answer_true_price")
        assertTrue(english, english.contains("Nothing has been paid") && english.contains("If you cancel, nothing is paid"))
        assertTrue(translated, translated.contains("no se ha pagado nada") && translated.contains("Si cancelas, no se paga nada"))
    }

    @Test
    fun `the notice's figure is written the Spanish way on a Spanish phone`() {
        val notice = ChatSendCopy.kexAnswerShortfall(Zatoshi(22_000L))
        assertTrue("the stored notice is not in the neutral format: $notice", notice.contains("0.00022 ZEC"))

        val need = ChatSendCopy.kexAnswerShortfallNeed(notice)
        assertEquals(Zatoshi(22_000L), need)

        val shown = String.format(spanish, es.getValue("chat_send_kex_answer_shortfall"), zecDisplay(need!!, spanish))
        assertTrue("the Spanish notice reads: $shown", shown.contains("se necesitan 0,00022 ZEC"))
        assertFalse("the Spanish notice keeps the English separator: $shown", shown.contains("0.00022"))
    }

    @Test
    fun `the notice's figure is written the English way on an English phone`() {
        val need = ChatSendCopy.kexAnswerShortfallNeed(ChatSendCopy.kexAnswerShortfall(Zatoshi(22_000L)))!!
        val shown = String.format(Locale.ENGLISH, en.getValue("chat_send_kex_answer_shortfall"), zecDisplay(need, Locale.ENGLISH))
        assertTrue("the English notice reads: $shown", shown.contains("it needs 0.00022 ZEC"))
    }

    @Test
    fun `the figure survives the notice exactly, whatever the phone's default language`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ENGLISH, spanish, Locale.GERMANY)) {
                Locale.setDefault(locale)
                for (zatoshi in listOf(1L, 17_000L, 22_000L, 123_456L, 100_000_000L, 2_050_000_000L)) {
                    assertEquals(
                        "default $locale",
                        Zatoshi(zatoshi),
                        ChatSendCopy.kexAnswerShortfallNeed(ChatSendCopy.kexAnswerShortfall(Zatoshi(zatoshi))),
                    )
                }
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `any other text has no figure`() {
        assertNull(ChatSendCopy.kexAnswerShortfallNeed(ChatSendCopy.INSUFFICIENT_BALANCE_SHORT))
        assertNull(ChatSendCopy.kexAnswerShortfallNeed(""))
    }

    @Test
    fun `the screen fills the Spanish line with the phone's locale`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val start = source.indexOf("private fun localizedSendNotice(")
        assertTrue("fixture: localizedSendNotice", start >= 0)
        val body = source.substring(start, start + 700)
        assertTrue(body, body.contains("R.string.chat_send_kex_answer_shortfall"))
        assertTrue(body, body.contains("zecDisplay(need, context.resources.configuration.locales[0])"))
    }

    @Test
    fun `the own setup question exists in both languages, translated, with the same promise`() {
        val english = en.getValue("paid_action_own_setup_true_price")
        val translated = es.getValue("paid_action_own_setup_true_price")
        assertNotEquals("the Spanish line is the English one", english, translated)
        assertEquals(emptyList<String>(), placeholders(english))
        assertEquals(emptyList<String>(), placeholders(translated))
        assertTrue(english, english.contains("Nothing has been paid") && english.contains("If you cancel, nothing is paid"))
        assertTrue(translated, translated.contains("no se ha pagado nada") && translated.contains("Si cancelas, no se paga nada"))
        val source =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/PaidActionCopy.kt")
        assertTrue(source.contains("PaidAction.OWN_SETUP_TRUE_PRICE -> R.string.paid_action_own_setup_true_price"))
    }

    @Test
    fun `a message's above-price reason is mapped, and says the same in both languages`() {
        val key = "chat_send_failed_kex_above_price"
        assertEquals(ChatResourceText.stringId(key), ChatSendCopy.localizedResource(ChatSendCopy.FAILED_KEX_ABOVE_PRICE))
        val english = en.getValue(key)
        val translated = es.getValue(key)
        assertEquals("the stored reason is not the English line", ChatSendCopy.FAILED_KEX_ABOVE_PRICE, english)
        assertNotEquals("the Spanish line is the English one", english, translated)
        assertEquals(emptyList<String>(), placeholders(english))
        assertEquals(emptyList<String>(), placeholders(translated))
        assertTrue(english, english.contains("Nothing was paid") && english.contains("Tap Retry"))
        assertTrue(translated, translated.contains("No se pagó nada") && translated.contains("Toca Reintentar"))
    }

    @Test
    fun `the question uses the new text`() {
        val source =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/PaidActionCopy.kt")
        assertTrue(source.contains("PaidAction.KEX_ANSWER_TRUE_PRICE -> R.string.paid_action_kex_answer_true_price"))
    }
}
