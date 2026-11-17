package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1575 + ZC-1592 (QA2 Phase 0 UI/UX read, row 2; skills app-texts rules 1-2, money-path rule 1): the "keys out of
 * sync" banner ChatDetailView shows when a message cannot be decrypted is hard-coded English ("Can't read messages —
 * keys out of sync", its body, "Re-establish") with a hard-coded amber `Color(0xFFFFA726)`, the two bubble
 * placeholders ChatViewModel returns for such a message are English literals, and the banner and the Reset dialog
 * (chat_reset_encryption_body) tell both people to do it ("both of you should do this", "BOTH of you should tap this
 * once", "los DOS deben tocarlo").
 *
 * Since the ZC-1571/1583/1586 fixes one Re-establish by either side converges, and doing it twice pays twice (a key
 * exchange each side, ~17,000 zat each). Re-establish costs money and ZChat quotes before paying, so the banner says
 * the price is shown first.
 *
 * Wanted: banner title, body and button and both placeholders come from string resources in English and Spanish; no
 * text tells both people to do it; the banner body says the price is shown first; the banner colours are no literal.
 */
class KeysOutOfSyncBannerSaysOneSideAndPriceTest {
    private val en: Map<String, String> by lazy { ChatResourceText.strings("values") }
    private val es: Map<String, String> by lazy { ChatResourceText.strings("values-es") }
    private val chatDetail: String by lazy { SourceScan.stripComments(ChatResourceText.source("$CHAT/view/ChatDetailView.kt")) }
    private val chatViewModel: String by lazy {
        SourceScan.stripComments(ChatResourceText.source("$CHAT/viewmodel/ChatViewModel.kt"))
    }
    private val banner: String by lazy { SourceScan.braceBlock(chatDetail, "if (showDecryptRecovery") }

    @Test
    fun the_banner_shows_resources_and_no_english_literal() {
        for (key in BANNER_KEYS) {
            assertTrue("R.string.$key" in banner, "the keys-out-of-sync banner does not show R.string.$key:\n$banner")
        }
        val literals = Regex(""""([^"\\]|\\.)*"""").findAll(banner).map { it.value }.filter { Regex("[A-Za-z]").containsMatchIn(it) }
        assertEquals(emptyList(), literals.toList(), "the keys-out-of-sync banner still shows hard-coded text")
    }

    @Test
    fun the_banner_colours_are_theme_tokens_not_literals() {
        val colours = Regex("""Color\(0x[0-9A-Fa-f]+\)""").findAll(banner).map { it.value }.toList()
        assertEquals(emptyList(), colours, "the keys-out-of-sync banner hard-codes its colour:\n$banner")
    }

    @Test
    fun the_two_decrypt_placeholders_come_from_resources() {
        for (old in OLD_PLACEHOLDER_LITERALS) {
            assertFalse(old in chatViewModel, "ChatViewModel still returns the English placeholder \"$old…\"")
        }
        for (key in PLACEHOLDER_KEYS) {
            assertTrue("R.string.$key" in chatViewModel, "ChatViewModel does not show R.string.$key")
        }
    }

    @Test
    fun every_text_is_in_english_and_spanish_with_the_same_placeholders() {
        for (key in BANNER_KEYS + PLACEHOLDER_KEYS + RESET_BODY) {
            val e = en[key]
            val s = es[key]
            assertNotNull(e, "no English $key")
            assertNotNull(s, "no Spanish $key")
            assertNotEquals(e, s, "$key: the Spanish file holds the English text")
            assertEquals(placeholders(e), placeholders(s), "$key: the Spanish placeholders differ from the English")
        }
    }

    @Test
    fun no_text_tells_both_people_to_do_it() {
        for (key in BANNER_KEYS + PLACEHOLDER_KEYS + RESET_BODY) {
            en[key]?.let { text ->
                assertFalse(
                    BOTH_EN.containsMatchIn(text) || "BOTH" in text,
                    "English $key tells both people to re-establish, but one side is enough and each pays: \"$text\"",
                )
            }
            es[key]?.let { text ->
                assertFalse(
                    BOTH_ES.containsMatchIn(text),
                    "Spanish $key tells both people to re-establish, but one side is enough and each pays: \"$text\"",
                )
            }
        }
    }

    @Test
    fun the_banner_body_says_the_price_is_shown_first() {
        val e = en[BANNER_BODY]
        val s = es[BANNER_BODY]
        assertNotNull(e, "no English $BANNER_BODY")
        assertNotNull(s, "no Spanish $BANNER_BODY")
        assertTrue(Regex("""(?i)\bprice\b""").containsMatchIn(e), "the banner body must say ZChat shows the price first: \"$e\"")
        assertTrue(Regex("""(?i)\bprecio\b""").containsMatchIn(s), "the Spanish banner body must say the price is shown first: \"$s\"")
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    private companion object {
        const val CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"

        const val BANNER_TITLE = "chat_decrypt_recovery_title"
        const val BANNER_BODY = "chat_decrypt_recovery_body"
        const val BANNER_BUTTON = "chat_decrypt_recovery_reestablish"
        val BANNER_KEYS = listOf(BANNER_TITLE, BANNER_BODY, BANNER_BUTTON)

        val PLACEHOLDER_KEYS = listOf("chat_decrypt_placeholder_reestablishing", "chat_decrypt_placeholder_key_missing")
        val RESET_BODY = listOf("chat_reset_encryption_body")

        val OLD_PLACEHOLDER_LITERALS =
            listOf("Encrypted message — secure session re-establishing", "Can't read this message \\u2014 its decryption key")

        val BOTH_EN = Regex("""(?i)\b(both of you|you both|each of you)\b""")
        val BOTH_ES = Regex("""(?i)\b(los dos|las dos|ambos|ambas|cada uno de ustedes)\b""")
    }
}
