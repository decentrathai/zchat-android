package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 B-5 (Q2-67, ZC-1653; owner W-137; design FABLE_BEHAVIOUR_DESIGN.md "B-5"): Send Image sent the moment the picture
 * was picked, with no preview, so a wrong pick went out at once and Delete does not unsend on the other phone.
 *
 * The picker, the camera and the share-in no longer call the view model. They park the choice in a PendingImageSend; an
 * ImageSendPreviewDialog (ChatDialogs.kt) shows the picture with Cancel and Send, and only its Send calls
 * handlePickedImage / handlePickedImageViewOnce. Rules:
 * - no caption (a caption needs a new field in the ZFILE memo: a wire change a 2.12.4 phone would misread, compat-2124);
 * - the preview's Send is not a payment confirmation and shows no price: the upload slot, the key-exchange gate and the
 *   on-chain "Pay for this?" sheet of a Shielded chat stay downstream, unchanged;
 * - a view-once pick says so.
 */
class PickedImageIsPreviewedBeforeItIsSentTest {
    private val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
    private val androidChat by lazy { ChatResourceText.source("$chat/AndroidChat.kt") }
    private val dialogs by lazy { ChatResourceText.source("$chat/view/ChatDialogs.kt") }
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun between(
        source: String,
        from: String,
        to: String,
    ): String {
        val start = source.indexOf(from)
        assertTrue(start >= 0, "marker not found: $from")
        val end = source.indexOf(to, start + from.length)
        assertTrue(end > start, "marker not found after \"$from\": $to")
        return source.substring(start, end)
    }

    @Test
    fun a_picked_image_is_parked_in_a_pending_image_send() {
        val model = File(ChatResourceText.repoRoot, "$chat/model/PendingImageSend.kt")
        assertTrue(model.isFile, "no PendingImageSend model: the pick has nowhere to wait for the preview")
        val text = model.readText()
        assertTrue("data class PendingImageSend" in text)
        assertTrue(Regex("""val\s+uri\s*:""").containsMatchIn(text) && Regex("""val\s+viewOnce\s*:\s*Boolean""").containsMatchIn(text))
    }

    @Test
    fun the_picker_does_not_send() {
        val picker = between(androidChat, "val imagePickerLauncher =", "val pendingCameraUri")
        assertFalse("handlePickedImage" in picker, "the gallery pick still sends at once (handlePickedImage is called in its callback)")
        assertTrue("PendingImageSend" in picker, "the gallery pick does not wait for the preview")
    }

    @Test
    fun the_camera_does_not_send() {
        val camera = between(androidChat, "val cameraLauncher =", "// Audio recording state")
        assertFalse("handlePickedImage" in camera, "the camera still sends at once")
        assertTrue("PendingImageSend" in camera, "the camera picture does not wait for the preview")
    }

    @Test
    fun the_share_in_is_previewed_the_same_way() {
        val share = between(androidChat, "consumeArmedFor(peerAddress)", "Best-effort cleanup of the copied share-inbox file")
        assertTrue("PendingImageSend" in share, "a picture shared into ZChat still goes out without the preview")
    }

    @Test
    fun only_the_previews_send_reaches_the_view_model() {
        assertTrue("ImageSendPreviewDialog(" in androidChat, "AndroidChat never shows the preview")
        val dialogCall = androidChat.indexOf("ImageSendPreviewDialog(")
        val sends = Regex("""viewModel\.handlePickedImage(ViewOnce)?\(""").findAll(androidChat).map { it.range.first }.toList()
        assertTrue(sends.isNotEmpty(), "nothing sends the picture any more")
        // Every remaining send sits in the preview's confirm action, after the dialog is opened.
        assertTrue(sends.all { it > dialogCall }, "a send path still bypasses the preview")
    }

    private val dialogBody by lazy {
        val start = dialogs.indexOf("fun ImageSendPreviewDialog(")
        assertTrue(start >= 0, "ChatDialogs.kt has no ImageSendPreviewDialog")
        val next = Regex("""\n(@Composable|private fun|fun |internal fun)""").find(dialogs, start + 20)
        dialogs.substring(start, next?.range?.first ?: dialogs.length)
    }

    @Test
    fun the_preview_has_cancel_send_and_a_view_once_line_and_no_caption_and_no_price() {
        assertTrue("R.string.chat_image_preview_title" in dialogBody)
        assertTrue("R.string.chat_image_preview_view_once" in dialogBody, "a view-once pick must say so in the preview")
        assertFalse("TextField(" in dialogBody, "a caption needs a new ZFILE field (wire change)")
        assertFalse("PaidActionCopy" in dialogBody, "the preview's Send is not a payment confirmation: it carries no price")
        val keys = Regex("""R\.string\.(\w+)""").findAll(dialogBody).map { it.groupValues[1] }.toSet()
        val shown = keys.map { assertNotNull(en[it], "values has no $it") }
        assertTrue(shown.any { it == "Send" }, "no Send button in the preview: $shown")
        assertTrue(shown.any { it == "Cancel" }, "no Cancel button in the preview: $shown")
        assertTrue(shown.none { "ZEC" in it }, "the preview shows a ZEC figure although nothing is quoted yet: $shown")
    }

    @Test
    fun the_preview_texts_exist_in_english_and_spanish() {
        assertEquals("Send this picture?", en["chat_image_preview_title"])
        assertEquals("¿Enviar esta imagen?", es["chat_image_preview_title"])
        assertEquals("View-once: they see it once, then it's gone", en["chat_image_preview_view_once"])
        assertEquals("Ver una vez: la verá una vez y luego desaparece", es["chat_image_preview_view_once"])
        assertNotEquals(en["chat_image_preview_title"], es["chat_image_preview_title"])
    }

    @Test
    fun the_wire_format_of_an_attachment_is_unchanged() {
        // No caption: the ZFILE memo keeps its fields (compat-2124 rule 2). The model file is not touched by this item.
        val zfile = ChatResourceText.source("$chat/model/ZFILEMessage.kt")
        assertFalse("caption" in zfile.lowercase(), "a caption field in ZFILE is a wire change a 2.12.4 phone would misread")
    }
}
