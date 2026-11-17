package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 2 report 4 item 16 ("English-only text on Spanish phones"), the user-facing money and retry text:
 *
 * - every reason the chat error mapper can show (toZchatUserMessage) has a Spanish translation, looked up the
 *   same way as the other send notices (ChatSendCopy.localizedResource), so a Spanish phone never shows
 *   an English refusal after a failed send;
 * - the "Unknown cause ..." error screen shows a money refusal in the phone's language;
 * - the "Shield Your Funds" dialog (SendMessageState.NeedsOrchardShielding) is translated;
 * - the Retry button under a failed message is translated, and the Spanish notices name it as it is shown.
 */
class SpanishMoneyAndRetryTextTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()

    /** Every reason the mapper returns, reached the way a failed send reaches it (its error text). */
    private val mapperReasons: List<String> by lazy {
        listOf(
            "AmountTooSmall",
            "MemoTooLong",
            "Grpc failure",
            "Funds are in the sealed Orchard pool",
            "Your Ironwood funds are still confirming",
            "This send would be funded from the sealed Orchard pool",
            "would be paid from your Sapling balance into another pool",
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MARKER,
            "Insufficient balance",
            "still pending",
        ).map { RuntimeException(it).toZchatUserMessage(fallback = "FALLBACK") }
    }

    @Test
    fun the_mapper_reasons_are_the_ones_this_test_expects() {
        assertFalse("FALLBACK" in mapperReasons, "a probe no longer reaches its reason: $mapperReasons")
        assertEquals(mapperReasons.size, mapperReasons.toSet().size, "two probes reached the same reason")
    }

    @Test
    fun every_error_mapper_reason_has_a_spanish_translation() {
        assertTranslated(mapperReasons)
    }

    @Test
    fun the_shield_needed_message_has_a_spanish_translation() {
        assertTranslated(listOf(SendMessageState.NeedsOrchardShielding(Zatoshi(0), Zatoshi(0)).message))
    }

    @Test
    fun the_error_screen_shows_a_money_refusal_in_the_phones_language() {
        val vm = src("screen/error/ErrorVM.kt")
        assertTrue("ChatSendCopy" in vm && "localizedResource(" in vm, "ErrorVM wraps the refusal in English")
    }

    @Test
    fun the_shield_your_funds_dialog_is_translated() {
        val chat = src("screen/chat/AndroidChat.kt")
        for (english in listOf(
            "Text(\"Shield Your Funds\")",
            "\"Your funds are currently in:\"",
            "\"Use ZCHAT to shield your funds into Ironwood, then try again.\"",
        )) {
            assertFalse(english in chat, "still hard-coded English: $english")
        }
        val en = allStrings("values")
        val es = allStrings("values-es")
        for (name in listOf(
            "chat_shield_dialog_title",
            "chat_shield_dialog_funds_in",
            "chat_shield_dialog_sapling",
            "chat_shield_dialog_transparent",
            "chat_shield_dialog_action",
        )) {
            val english = assertNotNull(en[name], "values has no $name")
            val spanish = assertNotNull(es[name], "values-es has no $name")
            assertTrue(spanish.isNotBlank() && spanish != english, "$name is not translated")
        }
    }

    @Test
    fun the_retry_button_is_translated_and_the_spanish_notices_name_it_as_shown() {
        val detail = src("screen/chat/view/ChatDetailView.kt")
        assertFalse("text = \"Retry\"," in detail, "the Retry label is hard-coded English")
        assertTrue("R.string.chat_retry" in detail)
        assertEquals("Retry", allStrings("values")["chat_retry"])
        val spanishLabel = assertNotNull(allStrings("values-es")["chat_retry"])
        assertEquals("Reintentar", spanishLabel)
        val stale = allStrings("values-es").filterValues { Regex("\\bRetry\\b").containsMatchIn(it) }
        assertTrue(stale.isEmpty(), "Spanish text still names the button \"Retry\": ${stale.keys}")
    }

    // ---- helpers ----

    private fun assertTranslated(texts: List<String>) {
        val en = allStrings("values")
        val es = allStrings("values-es")
        for (text in texts) {
            val id = assertNotNull(ChatSendCopy.localizedResource(text), "no translation for: $text")
            val name = resourceName(id)
            assertEquals(text, en[name], "English resource $name must be the exact text the app reports")
            val spanish = assertNotNull(es[name], "Spanish resource $name missing")
            assertTrue(spanish.isNotBlank() && spanish != text, "Spanish resource $name is not translated")
        }
    }

    private fun resourceName(id: Int): String =
        assertNotNull(
            R.string::class.java.fields
                .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
                ?.name,
            "no string resource with id $id",
        )

    private fun allStrings(dir: String): Map<String, String> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList()
            }.fold(emptyMap()) { acc, f -> acc + parse(f.readText()) }

    private fun parse(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
            }
}
