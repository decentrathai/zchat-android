package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-250 (fix-all 2.13.0, train T5b; t5b SPECS §6, texts) — the key-changed texts do not say what accepting the key
 * costs, and the banner is English on every phone.
 *
 * At 3ff26bc9a a refused send says "…then close the warning banner (×) to continue." (chat_send_key_changed_verify).
 * In Tunnel that × (dismissE2EKeyChanged) asks KEY_CHANGE_RECONNECT for 2 on-chain messages before it accepts
 * the key; Open accepts without an on-chain payment. The text names no Tunnel cost and suggests × simply continues.
 * The text is a ~4 s toast, and the banner it points
 * to ("Security key changed — verify", its explanation, "Verify", the × described as "Dismiss") is Kotlin literals in
 * ChatDetailView.
 *
 * Wanted (app-texts: say only what the code does):
 * - chat_key_changed_send_refused, shown in the expanded banner after a refused send: nothing was sent or paid, compare
 *   the safety number (Verify), × accepts the new key: free in a Shielded or Open chat, and in a Tunnel chat ZChat first
 *   asks to pay for a reconnect (2 on-chain messages) and shows the price; EN + ES;
 * - the short cue chat_send_key_changed_verify points to the red warning and no longer says "to continue"; its Kotlin
 *   twin ChatSendCopy.KEY_CHANGED_VERIFY is the English text;
 * - the banner's texts are resources (EN + ES): short label, explanation, Verify, the × description; ChatDetailView holds
 *   none of the old literals, and its key-changed banner shows the refusal notice.
 */
class KeyChangedTextsNameTheReconnectCostTest {
    private val en: Map<String, String> by lazy { ChatResourceText.strings("values") }
    private val es: Map<String, String> by lazy { ChatResourceText.strings("values-es") }
    private val chatDetailRaw: String by lazy { ChatResourceText.source("$VIEW/ChatDetailView.kt") }
    private val chatDetail: String by lazy { SourceScan.stripComments(chatDetailRaw) }
    private val androidChat: String by lazy {
        SourceScan.stripComments(ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"))
    }

    @Test
    fun every_key_changed_text_is_in_english_and_spanish_with_the_same_placeholders() {
        for (key in KEYS) {
            val e = en[key]
            val s = es[key]
            assertNotNull(e, "no English $key")
            assertNotNull(s, "no Spanish $key")
            assertEquals(placeholders(e), placeholders(s), "$key: the Spanish placeholders differ from the English")
        }
    }

    @Test
    fun the_refusal_notice_says_nothing_was_sent_and_names_the_reconnect_cost() {
        val e = en.getValue("chat_key_changed_send_refused")
        for (words in EN_NOTICE_WORDS) {
            assertTrue(words in e, "the English notice does not say \"$words\": $e")
        }
        assertFalse("Tunnel or Open" in e, "the English notice still says an Open reconnect pays: $e")
        val s = es.getValue("chat_key_changed_send_refused")
        for (words in ES_NOTICE_WORDS) {
            assertTrue(words in s, "the Spanish notice does not say \"$words\": $s")
        }
        assertFalse("Tunnel u Open" in s, "the Spanish notice still says an Open reconnect pays: $s")
    }

    @Test
    fun the_short_cue_points_to_the_warning_and_no_longer_says_continue() {
        val e = en.getValue("chat_send_key_changed_verify")
        val s = es.getValue("chat_send_key_changed_verify")
        assertEquals("Not sent: this contact's security key changed. See the red warning at the top of the chat.", e)
        assertFalse("to continue" in e, e)
        assertFalse("para continuar" in s, s)
        assertTrue("No enviado" in s && "aviso rojo" in s, s)
        assertEquals(e, ChatSendCopy.KEY_CHANGED_VERIFY, "the Kotlin twin of the short cue must be its English text")
    }

    @Test
    fun the_banner_texts_are_resources() {
        for (literal in OLD_LITERALS) {
            assertFalse(literal in chatDetail, "ChatDetailView still holds the English literal $literal")
        }
        for (key in BANNER_KEYS) {
            assertTrue("R.string.$key" in chatDetail, "ChatDetailView does not use $key")
        }
        assertEquals("Security key changed — verify", en["chat_key_changed_banner_short"])
        assertEquals(
            "This contact's security key changed. It could be a new device — or someone trying to impersonate them. " +
                "Compare your safety number before trusting this chat.",
            en["chat_key_changed_banner_body"],
        )
    }

    @Test
    fun the_key_changed_banner_shows_the_refusal_until_it_is_collapsed() {
        val start = chatDetailRaw.indexOf("Key-Changed Warning Banner")
        assertTrue(start >= 0, "fixture: the key-changed banner's comment in ChatDetailView")
        val banner = SourceScan.stripComments(SourceScan.braceBlock(chatDetailRaw.substring(start), "if (isKeyChanged)"))
        assertTrue("R.string.chat_key_changed_send_refused" in banner, "the banner never shows the refusal notice:\n$banner")
        assertTrue("keyChangeRefused" in banner, "the banner does not follow the refusal:\n$banner")
        // Its × accepts the new key (free in Shielded, a paid reconnect question first elsewhere): a screen reader says so.
        assertTrue("R.string.chat_key_changed_accept_key" in banner, "the banner's × is described as a plain Dismiss:\n$banner")
        assertEquals("Accept the new key", en["chat_key_changed_accept_key"])
        assertTrue("onAcknowledgeKeyChangeRefusal" in banner, "collapsing the banner does not acknowledge the refusal:\n$banner")
        assertTrue(
            Regex("""keyChangeRefused\s*=\s*keyChangeRefusal\s*==\s*peerAddress""").containsMatchIn(androidChat),
            "AndroidChat does not pass whether this chat's send was refused",
        )
        assertTrue("viewModel.keyChangeRefusal.collectAsStateWithLifecycle()" in androidChat, "AndroidChat does not follow the refusal")
        assertTrue("viewModel.acknowledgeKeyChangeRefusal()" in androidChat, "AndroidChat never acknowledges the refusal")
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private companion object {
        const val VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view"
        val EN_NOTICE_WORDS =
            listOf(
                "Not sent",
                "Nothing was sent or paid",
                "Verify",
                "Accept the new key",
                "Shielded",
                "free",
                "Tunnel",
                "2 on-chain messages",
                "price",
            )
        val ES_NOTICE_WORDS =
            listOf(
                "No enviado",
                "No se envió ni se pagó nada",
                "Verificar",
                "Aceptar la nueva clave",
                "Shielded",
                "gratis",
                "Tunnel",
                "2 mensajes",
                "precio",
            )
        val OLD_LITERALS =
            listOf("\"Security key changed — verify\"", "trying to impersonate them", "\"Verify\"", "contentDescription = \"Dismiss\"")
        val BANNER_KEYS =
            listOf("chat_key_changed_banner_short", "chat_key_changed_banner_body", "chat_key_changed_verify", "chat_a11y_dismiss")
        val KEYS =
            listOf(
                "chat_key_changed_send_refused",
                "chat_send_key_changed_verify",
                "chat_key_changed_banner_short",
                "chat_key_changed_banner_body",
                "chat_key_changed_verify",
                "chat_key_changed_accept_key",
                "chat_a11y_dismiss",
            )
    }
}
