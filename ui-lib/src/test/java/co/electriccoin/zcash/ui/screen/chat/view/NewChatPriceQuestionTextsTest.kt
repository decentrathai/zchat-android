package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ZC-601 — the words of New Chat's exact price question, in English and Spanish.
 *
 * At the base New Chat asks nothing, so there is no text, no action to choose it by, no dialog on the screen, and no
 * text for a send whose exact price could not be worked out.
 *
 * Wanted: two argument-free action texts in `ui/common/values{,-es}/chat_money_strings.xml`, one for a Shielded first
 * message and one for every other mode (the Tunnel handshake comes after it and is not part of the price), chosen by
 * two new [PaidAction] values; the New Chat screen shows the existing `PaidActionConfirmDialog` with the view model's
 * confirm and dismiss; a ChatSendCopy constant shows the existing `payment_quote_unavailable`. The texts never say
 * "free", and never describe the chat's own first-message plan ("the key exchange goes first"): New Chat sends one
 * transaction and no key exchange.
 */
class NewChatPriceQuestionTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun text(
        strings: Map<String, String>,
        locale: String,
        key: String,
    ): String = strings[key] ?: throw AssertionError("$locale has no $key: New Chat has no words for its price question")

    @Test
    fun `both action texts exist in English and Spanish, differ and take no argument`() {
        for (key in listOf(SHIELDED, TUNNEL)) {
            val english = text(en, "values", key)
            val spanish = text(es, "values-es", key)
            assertNotEquals(english, spanish, "$key is not translated")
            assertFalse('%' in english, "$key takes an argument: $english")
            assertFalse('%' in spanish, "$key takes an argument in Spanish: $spanish")
        }
        assertNotEquals(text(en, "values", SHIELDED), text(en, "values", TUNNEL), "Tunnel needs its own sentence")
        assertNotEquals(text(es, "values-es", SHIELDED), text(es, "values-es", TUNNEL))
    }

    @Test
    fun `the English says one Zcash transaction and never free or the chat's key-exchange plan`() {
        for (key in listOf(SHIELDED, TUNNEL)) {
            val english = text(en, "values", key)
            assertTrue("one Zcash transaction" in english, "$key does not say it is one transaction: $english")
            assertFalse(english.contains("free", ignoreCase = true), "$key says free: $english")
            assertFalse(
                english.contains("key exchange goes first", ignoreCase = true),
                "$key describes the chat's first-message plan, which New Chat does not send: $english",
            )
        }
    }

    @Test
    fun `the Tunnel sentence says the handshake comes after, at its floor, outside this price`() {
        val floorEn = PaidActionCopy.zec(onChainSendMinimumCost(), Locale.US)
        val floorEs = PaidActionCopy.zec(onChainSendMinimumCost(), Locale.forLanguageTag("es"))
        assertEquals("0.00017", floorEn, "fixture sanity: the least one handshake transaction costs")

        val english = text(en, "values", TUNNEL)
        assertTrue("not part of this price" in english, english)
        assertTrue("at least $floorEn ZEC" in english, "the handshake's floor comes from the code ($floorEn): $english")

        val spanish = text(es, "values-es", TUNNEL)
        assertTrue("al menos $floorEs ZEC" in spanish, "the Spanish floor uses the decimal comma ($floorEs): $spanish")
        assertTrue("no forma parte de este precio" in spanish, spanish)
        assertTrue("intercambio de claves" in spanish, "a handshake is an «intercambio de claves»: $spanish")
    }

    @Test
    fun `the Spanish keeps the app's own words`() {
        for (key in listOf(SHIELDED, TUNNEL)) {
            val spanish = text(es, "values-es", key)
            assertTrue("una sola transacción de Zcash" in spanish, "$key: $spanish")
            listOf("on-chain", "gratis", "saludo", "cartera", "monedero").forEach { word ->
                assertFalse(spanish.contains(word, ignoreCase = true), "$key says «$word»: $spanish")
            }
        }
    }

    @Test
    fun `each of the two new actions chooses its own text`() {
        for ((name, key) in listOf("NEW_CHAT_FIRST_MESSAGE" to SHIELDED, "NEW_CHAT_FIRST_MESSAGE_TUNNEL" to TUNNEL)) {
            val action =
                PaidAction.entries.firstOrNull { it.name == name }
                    ?: throw AssertionError("PaidAction has no $name: New Chat cannot ask its price question")
            assertEquals(key, ChatResourceText.stringName(PaidActionCopy.actionText(action)), "$name shows another text")
        }
    }

    @Test
    fun `the New Chat screen shows the price question with the view model's Pay and Cancel`() {
        val source = SourceScan.stripComments(ChatResourceText.source(ANDROID_CHAT))
        val start = source.indexOf("fun AndroidZchatCompose()")
        assertTrue(start >= 0, "fixture: AndroidZchatCompose in AndroidChat.kt")
        val body = source.substring(start, source.indexOf("\n}\n", start))
        assertTrue("PaidActionConfirmDialog(" in body, "New Chat shows no PaidActionConfirmDialog: nothing can ask")
        assertTrue("viewModel.paidActionConfirmation" in body, "the dialog is not driven by the view model's question")
        assertTrue(Regex("""onConfirm\s*=\s*\{\s*viewModel\.confirmPaidAction\(\)\s*}""").containsMatchIn(body), "Pay")
        assertTrue(Regex("""onDismiss\s*=\s*\{\s*viewModel\.dismissPaidAction\(\)\s*}""").containsMatchIn(body), "Cancel")
    }

    @Test
    fun `a send with no exact price has a text that shows the existing sentence`() {
        val constants =
            ChatSendCopy::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .mapNotNull { field -> runCatching { field.apply { isAccessible = true }.get(null) as String }.getOrNull() }
        assertTrue(
            constants.any { ChatSendCopy.localizedResource(it) == R.string.payment_quote_unavailable },
            "no ChatSendCopy text shows payment_quote_unavailable: New Chat cannot say the price could not be worked out",
        )
    }

    private companion object {
        const val SHIELDED = "paid_action_new_chat_first_message"
        const val TUNNEL = "paid_action_new_chat_first_message_tunnel"
        const val ANDROID_CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
    }
}
