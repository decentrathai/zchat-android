package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SR-C1 / KI-37 (fix-all 2.13.0, train T4c; photos-privacy spec §4, strings S-C1): the warning before a photo, file or
 * voice note goes to a contact whose latest relay message came from ZChat 2.12 or older.
 *
 * - The warning's title, body and buttons and the cancelled row's reason are resources in English and Spanish, with the
 *   same placeholders, translated, and none of them says "try again".
 * - The row reason is ChatSendCopy.ATTACHMENT_LEGACY_CONTACT_CANCELLED and is shown through its resource.
 * - The chat screen shows the warning (SendMessageState.LegacyContactAttachmentWarning) with "Send anyway" and "Cancel"
 *   wired to the view model's answers.
 */
class Ki37TextsAreResourcesTest {
    private val keys =
        listOf(
            "chat_attachment_legacy_title",
            "chat_attachment_legacy_body",
            "chat_attachment_legacy_send_anyway",
            "chat_attachment_legacy_cancel",
            "chat_attachment_legacy_row",
        )

    private fun placeholders(text: String): List<String> =
        Regex("%(\\d+\\$)?[sd]")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    @Test
    fun `the KI-37 warning texts exist in English and Spanish with the same placeholders`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in keys) {
            val english = en[key] ?: fail("no English string $key")
            val spanish = es[key] ?: fail("no Spanish string $key")
            assertTrue(english != spanish, "$key is not translated")
            assertEquals(placeholders(english), placeholders(spanish), "$key: the Spanish placeholders differ")
            for (text in listOf(english, spanish)) {
                for (phrase in listOf("try again", "otra vez", "inténtalo", "reintenta")) {
                    assertFalse(text.contains(phrase, ignoreCase = true), "$key promises '$phrase': $text")
                }
            }
        }
        // The title and the body name the contact (%1$s = the name the chat header shows).
        for (key in listOf("chat_attachment_legacy_title", "chat_attachment_legacy_body")) {
            assertEquals(listOf("%1\$s"), placeholders(en.getValue(key)).distinct(), "$key does not name the contact")
        }
        // Honest about the version: the body names the version to update to, and the row says nothing was uploaded.
        assertTrue(en.getValue("chat_attachment_legacy_body").contains("2.13"), "the warning does not say which version to update to")
        assertTrue(en.getValue("chat_attachment_legacy_row").contains("Nothing was uploaded"), "the row does not say nothing went out")
    }

    @Test
    fun `the cancelled row's reason is a ChatSendCopy constant shown through its resource`() {
        val text =
            try {
                ChatSendCopy::class.java.getField("ATTACHMENT_LEGACY_CONTACT_CANCELLED").get(null) as String
            } catch (e: NoSuchFieldException) {
                throw AssertionError("ChatSendCopy has no ATTACHMENT_LEGACY_CONTACT_CANCELLED", e)
            }
        val en = ChatResourceText.strings("values")
        assertEquals(en["chat_attachment_legacy_row"], text, "the constant differs from its English resource")
        assertEquals(ChatResourceText.stringId("chat_attachment_legacy_row"), ChatSendCopy.localizedResource(text))
    }

    @Test
    fun `the chat screen shows the warning and wires both answers`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        assertTrue(source.contains("LegacyContactAttachmentWarning"), "AndroidChat never shows the KI-37 warning")
        assertTrue(source.contains("confirmLegacyContactAttachment()"), "\"Send anyway\" is not wired")
        assertTrue(source.contains("cancelLegacyContactAttachment()"), "\"Cancel\" is not wired")
    }
}
