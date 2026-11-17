package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.memoDisplayText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 p3b nits from the Fable review of P3 (`P3_FABLE_REVIEW.md`), source and resource checks:
 *
 * - ZC-1731: the image viewer's Save and Share guard fails closed. It read `viewerMessage?.fileViewOnce != true`, so a message
 *   that is not found showed both buttons.
 * - ZC-1732: the chooser titles of Share and Forward are resources, EN + ES (they were the English literals "Share via…" and
 *   "Forward via…", now reachable from the viewer's Share button).
 * - ZC-1733: the transaction detail's label of a readable file memo is a resource (it was built from the English `displayText`).
 * - ZC-1734: the shield success title and the ordinary send title are pinned exactly: "Sent!" / "¡Enviado!".
 * - ZC-1705: the chat header at a large font: the nickname hint is not squeezed into one ellipsized line beside the chips, and
 *   the app bar is as tall as the larger text needs, so the chip row does not run over the messages. The layout itself needs a
 *   phone check at 1.3x; these pins hold the structure.
 */
class P3bNitsQa2Test {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val detail by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
    }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    // ---- ZC-1731 ----

    @Test
    fun `the viewer shows Save and Share only for a message it found that is not view-once`() {
        assertFalse(
            "viewerMessage?.fileViewOnce != true" in detail,
            "the viewer guard still fails open: a message that is not found shows Save and Share",
        )
        assertTrue(
            Regex("""viewerMessage\s*!=\s*null\s*&&\s*!viewerMessage\.fileViewOnce""").containsMatchIn(detail),
            "the viewer guard is not `viewerMessage != null && !viewerMessage.fileViewOnce`",
        )
    }

    // ---- ZC-1732 ----

    @Test
    fun `the Share and Forward chooser titles are resources in both languages`() {
        assertFalse("\"Share via…\"" in detail, "the Share chooser title is still an English literal")
        assertFalse("\"Forward via…\"" in detail, "the Forward chooser title is still an English literal")
        val start = detail.indexOf("private fun shareFile(")
        assertTrue(start >= 0, "shareFile is gone")
        val body = detail.substring(start, detail.indexOf("private fun openFileExternally(", start))
        assertTrue("R.string.chat_share_chooser_title" in body, "shareFile does not read chat_share_chooser_title")
        assertTrue("R.string.chat_forward_chooser_title" in body, "shareFile does not read chat_forward_chooser_title")
        assertEquals("Share via…", en["chat_share_chooser_title"])
        assertEquals("Forward via…", en["chat_forward_chooser_title"])
        assertEquals("Compartir con…", es["chat_share_chooser_title"])
        assertEquals("Reenviar con…", es["chat_forward_chooser_title"])
    }

    // ---- ZC-1733 (p3c, ZC-1756: the labels are ZFILEMessage.typeLabelRes, the ones the chat rows already use) ----

    private val rawFile = "ZFILE|${"0123456789abcdef".repeat(2)}|p|2048|https://blossom.primal.net/x.png|key|blur"

    @Test
    fun `a readable file memo is labelled by a resource with the file's size, in both languages`() {
        val file = assertNotNull(ZFILEMessage.parse(rawFile), "fixture: the file memo does not parse")
        val label = assertIs<StringResource.ByResource>(memoDisplayText(rawFile), "the readable file label is not a string resource")
        assertEquals(file.typeLabelRes, label.resource, "the label is not ZFILEMessage.typeLabelRes")
        val key = assertNotNull(ChatResourceText.stringName(label.resource), "no string name for the label's resource")
        assertEquals(listOf<Any>(file.sizeText), label.args, "the label takes the file's size as its only argument")
        val english = assertNotNull(en[key], "values has no $key")
        val spanish = assertNotNull(es[key], "values-es has no $key")
        assertEquals("Image (%1\$s)", english, "the English label changed")
        assertEquals(setOf("%1\$s"), placeholders(spanish), "$key: the Spanish label lost its size placeholder")
        assertNotEquals(english, spanish, "$key: the Spanish label is the English text")
        assertTrue("Imagen" in spanish, "$key: the Spanish label does not say Imagen: $spanish")
    }

    @Test
    fun `the voice and document file memos have their own labels in both languages`() {
        val hash = "0123456789abcdef".repeat(2)
        val cases =
            listOf(
                Triple("ZFILE|$hash|a|2048|https://blossom.primal.net/x|key|blur", "Voice message (%1\$s)", "Mensaje de voz"),
                Triple("ZFILE|$hash|d|2048|https://blossom.primal.net/x|key|blur", "Document (%1\$s)", "Documento"),
            )
        for ((memo, english, spanish) in cases) {
            val file = assertNotNull(ZFILEMessage.parse(memo), "fixture: ${memo.take(40)} does not parse")
            val label = assertIs<StringResource.ByResource>(memoDisplayText(memo), "the label of ${memo.take(40)} is not a resource")
            assertEquals(file.typeLabelRes, label.resource, "the label of ${memo.take(40)} is not ZFILEMessage.typeLabelRes")
            val key = assertNotNull(ChatResourceText.stringName(label.resource), "no string name for the label's resource")
            assertEquals(english, en[key], "$key: the English label changed")
            assertTrue(spanish in es.getValue(key), "$key: the Spanish label does not say $spanish")
        }
    }

    @Test
    fun `the transaction detail has no second copy of the file kind labels`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/MemoDisplayText.kt")
        assertTrue("typeLabelRes" in source, "MemoDisplayText does not take the label from ZFILEMessage.typeLabelRes")
        assertFalse("isImage" in source || "isAudio" in source, "MemoDisplayText still repeats the file kind `when`")
        for (name in listOf("memo_display_file_image", "memo_display_file_voice", "memo_display_file_document")) {
            assertFalse(name in en, "values still holds the duplicate string $name")
            assertFalse(name in es, "values-es still holds the duplicate string $name")
        }
    }

    // ---- ZC-1734 ----

    @Test
    fun `the send and shield success titles are exactly Sent and Enviado`() {
        for (key in listOf("send_confirmation_success_title", "send_confirmation_success_title_transparent")) {
            assertEquals("Sent!", en[key], "$key (English)")
            assertEquals("¡Enviado!", es[key], "$key (Spanish)")
        }
    }

    // ---- ZC-1705 ----

    @Test
    fun `the nickname hint is not squeezed into the one-line address text beside the chips`() {
        assertFalse(
            Regex(
                """val subText\s*=\s*if \(conversation\.hasContactName\)[^}]*\}\s*else\s*\{[^}]*chat_nickname_hint""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(detail),
            "the nickname hint is still the else-branch of the one-line `· address` text (weight(1f), one ellipsized line)",
        )
        val at = detail.indexOf("text = stringResource(R.string.chat_nickname_hint)")
        assertTrue(at >= 0, "the nickname hint has no Text of its own")
        assertTrue(
            Regex("""maxLines\s*=\s*2""").containsMatchIn(detail.substring(at, minOf(detail.length, at + 400))),
            "the nickname hint may not wrap to two lines",
        )
    }

    @Test
    fun `the chat app bar is as tall as the larger font needs`() {
        val top = detail.indexOf("topBar = {")
        assertTrue(top >= 0, "no topBar in ChatDetailView")
        val end = detail.indexOf("colors = TopAppBarDefaults.topAppBarColors(", top)
        val region = detail.substring(top, end)
        assertTrue(Regex("""\bTopAppBar\(\s*expandedHeight\s*=""").containsMatchIn(region), "the chat app bar keeps the fixed 64 dp height")
        assertTrue("fontScale" in region, "the chat app bar height does not follow the font scale")
    }
}
