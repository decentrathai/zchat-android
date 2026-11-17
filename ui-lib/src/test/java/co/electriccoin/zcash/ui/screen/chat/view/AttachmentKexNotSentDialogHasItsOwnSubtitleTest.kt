package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1681 (QA2 P3 polish; Fable wave-1 re-review; medium: "text may promise a free path that does not exist"): the
 * attachment notice for AttachmentBlockReason.KEX_NOT_SENT (action SEND_KEY_EXCHANGE, our key exchange was never sent)
 * adds the Reconnect menu's subtitle under its body: "Free over the relays when they reach your contact. Otherwise an
 * on-chain handshake transaction ...". That relay-first sentence describes a Reconnect of a chat that already has a
 * route; here nothing was sent, so no relay route is known and the sentence promises a free path that does not exist.
 *
 * Wanted: the SEND_KEY_EXCHANGE branch of AttachmentKeyExchangeDialog.kt has its own subtitle (EN + ES), not
 * `chat_menu_reconnect_subtitle`. It must not promise a free or relay path, must say it can cost ZEC (the on-chain
 * handshake transaction, with the figure as a placeholder or as text), and keeps its placeholders in both languages. The
 * menu's Reconnect keeps its own subtitle (NewChatAndModeTextsSayWhatPaysTest pins it). Nothing here pays: the action
 * stays `onSendKeyExchange` (the menu's Reconnect); money-path rule 1.
 *
 * ZC-1729 (Fable review of P3, finding 1, high): Reconnect does NOT always ask first. With the standing consent
 * ("Allow handshake transactions?" answered Allow) it pays the owed key exchange at once, with no question (ChatViewModel
 * startNostrBootstrap(force = true) -> launchKexReporting -> automaticHandshakeSpendAllowed). It asks only when the
 * consent was never given, was answered "Not now", or a failed attempt is on record. So the cost line must say what
 * happens in both cases and must not promise a price question ("before anything is paid").
 *
 * Source and resource checks (Compose dialog). The resource's name is not dictated.
 */
class AttachmentKexNotSentDialogHasItsOwnSubtitleTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val dialog by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/AttachmentKeyExchangeDialog.kt")
    }

    /** The code of the `SEND_KEY_EXCHANGE ->` branch of the body column, up to the ACCEPT branch. */
    private val branch by lazy {
        val start = dialog.indexOf("action == AttachmentBlockAction.SEND_KEY_EXCHANGE ->")
        assertTrue(start >= 0, "the dialog has no SEND_KEY_EXCHANGE branch in its body")
        val end = dialog.indexOf("action == AttachmentBlockAction.ACCEPT_KEY_EXCHANGE", start)
        dialog.substring(start, end)
    }

    private val keys by lazy { Regex("""R\.string\.(\w+)""").findAll(branch).map { it.groupValues[1] }.toList() }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    @Test
    fun `the not-sent key exchange notice does not reuse the Reconnect menu's relay-first subtitle`() {
        assertFalse(
            "chat_menu_reconnect_subtitle" in keys,
            "the SEND_KEY_EXCHANGE notice still shows chat_menu_reconnect_subtitle (\"Free over the relays ...\"), " +
                "a free path that does not exist when our key exchange was never sent",
        )
        assertTrue(keys.isNotEmpty(), "the SEND_KEY_EXCHANGE branch shows no subtitle at all")
    }

    @Test
    fun `its own subtitle exists in both languages, promises nothing free and says it can cost ZEC`() {
        val key = keys.firstOrNull { it != "chat_menu_reconnect_subtitle" }
        assertNotNull(key, "the SEND_KEY_EXCHANGE branch has no subtitle of its own")
        val english = assertNotNull(en[key], "values has no $key")
        val spanish = assertNotNull(es[key], "values-es has no $key")
        assertNotEquals(english, spanish, "$key: the Spanish text is the English one")
        assertEqualsPlaceholders(english, spanish, key)
        for (lie in listOf("free", "no cost", "no charge")) {
            assertFalse(lie in english.lowercase(), "$key (English) promises a free or relay path (\"$lie\"): $english")
        }
        for (lie in listOf("gratis", "sin costo", "sin coste")) {
            assertFalse(lie in spanish.lowercase(), "$key (Spanish) promises a free or relay path (\"$lie\"): $spanish")
        }
        assertTrue("ZEC" in english || placeholders(english).isNotEmpty(), "$key (English) does not say it can cost ZEC: $english")
        assertTrue("ZEC" in spanish || placeholders(spanish).isNotEmpty(), "$key (Spanish) does not say it can cost ZEC: $spanish")
        assertTrue(
            Regex("""(?i)\bcost""").containsMatchIn(english) || Regex("""(?i)\bpay|\bprice""").containsMatchIn(english),
            "$key (English) names no cost: $english",
        )
        assertTrue(
            Regex("""(?i)cuesta|costo|coste|pag|precio""").containsMatchIn(spanish),
            "$key (Spanish) names no cost: $spanish",
        )
    }

    @Test
    fun `the notice's action is still the menu's Reconnect (onSendKeyExchange)`() {
        assertTrue(
            Regex("""AttachmentBlockAction\.SEND_KEY_EXCHANGE\s*->\s*TextButton\(onClick = onSendKeyExchange\)""").containsMatchIn(dialog),
            "the SEND_KEY_EXCHANGE button no longer calls onSendKeyExchange",
        )
    }

    // ---- ZC-1729: the line says what happens, not that a question always comes first ----

    private val resDir = "ui-lib/src/main/res/ui/common"

    private fun commentAbove(
        dir: String,
        key: String,
    ): String {
        val xml = ChatResourceText.source("$resDir/$dir/chat_attachment_kex_strings.xml")
        return assertNotNull(
            Regex("""<!--((?:(?!-->).)*)-->\s*<string name="$key"""", RegexOption.DOT_MATCHES_ALL).find(xml),
            "$dir has no comment right above $key",
        ).groupValues[1]
    }

    @Test
    fun `the cost line does not promise a price question that standing consent skips`() {
        val key = "chat_attachment_kex_not_sent_cost"
        val english = ChatResourceText.strings("values").getValue(key)
        val spanish = ChatResourceText.strings("values-es").getValue(key)
        assertFalse("before anything is paid" in english, "$key (English) still says the price is shown before anything is paid: $english")
        assertFalse("antes de pagar nada" in spanish, "$key (Spanish) still says the price is shown before anything is paid: $spanish")
        // It says both outcomes: paid without another question under the allowed handshake transactions, otherwise asks first.
        assertTrue(
            Regex("""(?i)allowed handshake transactions""").containsMatchIn(english),
            "$key (English) does not name the consent: $english",
        )
        assertTrue(
            Regex("""(?i)without another question""").containsMatchIn(english),
            "$key (English) does not say it is paid without a question: $english",
        )
        // ZC-1756 (QA2 p3c, Fable review of P3b, finding 4): "otherwise ZChat asks first" promised a question that three
        // states skip (a second "Not now" to the same queued setup, a wallet that cannot pay one handshake, a Keystone
        // account). Nothing is paid in any of them, so the line says that.
        assertTrue(
            Regex("""(?i)otherwise nothing is paid until you agree""").containsMatchIn(english),
            "$key (English) does not say nothing is paid until the user agrees: $english",
        )
        assertFalse("asks first" in english, "$key (English) still promises a question: $english")
        assertTrue(
            Regex("""(?i)permitido las transacciones de enlace""").containsMatchIn(spanish),
            "$key (Spanish) does not name the consent: $spanish",
        )
        assertTrue(
            Regex("""(?i)sin (otra|ninguna) pregunta""").containsMatchIn(spanish),
            "$key (Spanish) does not say it is paid without a question: $spanish",
        )
        assertTrue(
            Regex("""(?i)si no, no se paga nada hasta que aceptes""").containsMatchIn(spanish),
            "$key (Spanish) does not say nothing is paid until the user agrees: $spanish",
        )
        assertFalse("te pregunta antes" in spanish, "$key (Spanish) still promises a question: $spanish")
        assertTrue("0.00017 ZEC" in english && "0,00017 ZEC" in spanish, "the floor price left the line")
    }

    @Test
    fun `the translator comments no longer say Reconnect always asks first`() {
        val key = "chat_attachment_kex_not_sent_cost"
        val english = commentAbove("values", key)
        val spanish = commentAbove("values-es", key)
        assertFalse("which asks first" in english, "the English comment still says Reconnect asks first: $english")
        assertFalse("que pregunta antes" in spanish, "the Spanish comment still says Reconnect asks first: $spanish")
        assertTrue("consent" in english.lowercase(), "the English comment does not mention the standing consent: $english")
        assertTrue("consentimiento" in spanish.lowercase(), "the Spanish comment does not mention the standing consent: $spanish")
    }

    private fun assertEqualsPlaceholders(
        english: String,
        spanish: String,
        key: String,
    ) = kotlin.test.assertEquals(placeholders(english), placeholders(spanish), "$key: the placeholders of the two languages differ")
}
