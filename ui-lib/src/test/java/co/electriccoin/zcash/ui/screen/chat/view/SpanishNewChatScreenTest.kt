package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P10 (PHONE_TRIAGE ES-4) — the New Chat screen (ZchatComposeView.kt) is English on a Spanish phone: the
 * recipient field, the CONTACTS list, the MODE picker and its dialogs, the message card, the amount dialog and the
 * Add-to-Contacts dialog are all string literals.
 *
 * Wanted:
 * - every word the screen shows is a string resource in ui/common/values{,-es}/new_chat_strings.xml; the English text
 *   is the old literal byte for byte, the Spanish text is a translation (it differs) with the same placeholders;
 * - the mode names Shielded / Tunnel / Open stay as they are (they come from ConversationMode.label(), not from here);
 * - the Open and Tunnel captions are resources whose English equals composeBlurb() (which the honesty tests pin), and
 *   the screen no longer shows composeBlurb() itself;
 * - the amount options' names come from resources whose English equals MessageAmount's label / description.
 */
class SpanishNewChatScreenTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val viewPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt"

    private val source: String by lazy { ChatResourceText.source(viewPath) }

    /**
     * The view without composeBlurb(): its English captions stay as the reference the honesty tests read, so they are
     * the one place the English text may remain in the file.
     */
    private val screen: String by lazy {
        val start = source.indexOf("internal fun ConversationMode.composeBlurb()")
        assertTrue(start >= 0, "fixture: composeBlurb() in ZchatComposeView.kt")
        val end = source.indexOf("\n}\n", start)
        assertTrue(end > start, "fixture: the end of composeBlurb()")
        source.substring(0, start) + source.substring(end + 3)
    }

    /** Literals that are not words a person reads in a language: the code prefix, its example, animation labels. */
    private val technical = setOf("\"zchat:\"", "\"zchat:c1?z=…\"", "\"icon_scale\"", "\"content_alpha\"")

    /** The English text each key must carry: the screen's literals before the move. */
    private val expectedEnglish =
        mapOf(
            "new_chat_back" to "Back",
            "new_chat_retry" to "Retry",
            "new_chat_recipient_label" to "Recipient Address",
            "new_chat_recipient_placeholder" to "Zcash address or zchat: contact code...",
            "new_chat_invalid_address" to "Invalid Zcash address",
            "new_chat_selected_contact" to "Contact: %1\$s",
            "new_chat_add_to_contacts" to "Add to contacts",
            "new_chat_contacts_header" to "CONTACTS",
            "new_chat_no_contacts" to "No contacts yet",
            "new_chat_no_contacts_hint" to "Paste an address or zchat: code, or scan a QR",
            "new_chat_message_label" to "Message",
            "new_chat_message_placeholder" to "Type your message...",
            "new_chat_char_count" to "%1\$d / %2\$d chars",
            "new_chat_chunk_count" to "%1\$d chunks",
            "new_chat_available" to "Available: %1\$s",
            // QA2 Q2-32 (W-137, ZC-1600): "Nostr relays", never NOSTR in capitals.
            "new_chat_free_title" to "Free — sent over Nostr relays",
            "new_chat_fee" to "Fee: %1\$s",
            "new_chat_send_all_not_enough" to "Not enough spendable balance to Send All.",
            "new_chat_adjust" to "Adjust",
            "new_chat_sending" to "Sending...",
            "new_chat_send_message" to "Send Message",
            "new_chat_paste_code_title" to "Paste contact code",
            "new_chat_paste_code_body" to
                "Paste your contact's ZCHAT code (starts with \"zchat:\"). It carries their address AND messaging key, " +
                "so the chat starts free over Nostr relays from the very first message.",
            "new_chat_paste_code_label" to "zchat: code",
            "new_chat_paste_code_not_code" to "Not a zchat: code — paste plain addresses in the recipient field.",
            "new_chat_use_code" to "Use code",
            "new_chat_cancel" to "Cancel",
            "new_chat_mode_header" to "MODE",
            // QA2 Q2-90 (ZC-1597): short enough for the chip in Spanish.
            "new_chat_mode_needs_code" to "Needs code",
            "new_chat_mode_tag_vault" to "On-chain",
            "new_chat_mode_tag_open" to "Free / Nostr",
            "new_chat_mode_tag_tunnel" to "2–3 payments, then free",
            "new_chat_open_needs_code_title" to "Open needs your contact's code",
            "new_chat_scan_code" to "Scan code",
            "new_chat_paste_code" to "Paste code",
            "new_chat_open_warning_title" to "Open mode: free over public relays",
            "new_chat_use_open" to "Use Open",
            "new_chat_enter_recipient" to "Enter a recipient to choose how this chat is delivered.",
            "new_chat_scan_contact_code" to "Scan contact code",
            "new_chat_add_contact_title" to "Add to Contacts",
            "new_chat_add_contact_address" to "Address: %1\$s...%2\$s",
            "new_chat_contact_name_label" to "Contact Name",
            "new_chat_contact_name_placeholder" to "Enter name...",
            "new_chat_add" to "Add",
            "new_chat_amount_dialog_title" to "Message Amount",
            "new_chat_amount_dialog_desc" to "Amount of ZEC to send with each message chunk",
            "new_chat_custom_amount_label" to "Custom Amount (ZEC)",
            "new_chat_done" to "Done",
            "new_chat_sent_icon" to "Message Sent",
            "new_chat_sent_title" to "MESSAGE SENT",
            // QA2 Q2-104 (ZC-1595): sent, not yet confirmed.
            "new_chat_sent_subtitle" to "Encrypted and sent to the Zcash network — not confirmed yet",
            "new_chat_sent_add_to_contacts" to "ADD TO CONTACTS",
            "new_chat_sent_done" to "DONE",
        )

    /**
     * QA2 (W-137): keys whose new English NewChatAndModeTextsSayWhatPaysTest holds: Q2-51 (ZC-1593) the texts whose old
     * English promised "no ZEC, ever" / "no ZEC needed" / "no relay involved" (held to what they may not promise), and
     * Q2-91 (ZC-1664) the amount card, whose ZChat fee figure comes from the code's constant. Here they stay resources
     * the screen shows, translated, with the same placeholders.
     */
    private val honestFormKeys =
        listOf(
            "new_chat_amount",
            "new_chat_free_desc",
            "new_chat_open_needs_code_body",
            "new_chat_open_warning_body",
            "new_chat_code_free_hint",
        )

    /** The amount options' resources and the enum text each English one must equal. */
    private val amountKeys =
        mapOf(
            "new_chat_amount_minimal_desc" to MessageAmount.MINIMAL.description,
            "new_chat_amount_small_desc" to MessageAmount.SMALL.description,
            "new_chat_amount_send_all" to MessageAmount.SEND_ALL.label,
            "new_chat_amount_send_all_desc" to MessageAmount.SEND_ALL.description,
            "new_chat_amount_custom" to MessageAmount.CUSTOM.label,
            "new_chat_amount_custom_desc" to MessageAmount.CUSTOM.description,
        )

    /** The Open and Tunnel captions (Shielded already has new_chat_mode_blurb_vault from P4). */
    private val blurbKeys =
        mapOf(
            "new_chat_mode_blurb_open" to ConversationMode.OPEN,
            "new_chat_mode_blurb_tunnel" to ConversationMode.TUNNEL,
        )

    @Test
    fun the_screen_shows_no_english_literal() {
        val found =
            SpanishSendPaymentDialogTest
                .englishLiterals(screen)
                .filter { line -> technical.none { line.endsWith(it) } }
        assertTrue(found.isEmpty(), "the New Chat screen still shows English literals:\n" + found.joinToString("\n"))
    }

    @Test
    fun every_key_is_the_old_english_and_a_spanish_translation() {
        expectedEnglish.forEach { (key, english) -> assertKey(key, english) }
        honestFormKeys.forEach { key -> assertKey(key, assertNotNull(en[key], "no English $key").replace("\\n", "\n")) }
    }

    @Test
    fun the_open_and_tunnel_captions_are_resources_equal_to_compose_blurb() {
        blurbKeys.forEach { (key, mode) -> assertKey(key, mode.composeBlurb()) }
        val calls = Regex("""composeBlurb\(\)""").findAll(source).count()
        assertEquals(1, calls, "the screen still shows composeBlurb() instead of its resource")
    }

    @Test
    fun the_amount_options_are_resources_equal_to_message_amount() {
        amountKeys.forEach { (key, english) -> assertKey(key, english) }
        val enumText = "amount.label" in source || "amount.description" in source
        assertTrue(!enumText, "the amount dialog still shows MessageAmount's English text")
    }

    @Test
    fun the_mode_names_stay_as_they_are() {
        assertTrue("text = mode.label()," in source, "the chips no longer show the mode names Shielded / Tunnel / Open")
    }

    private fun assertKey(
        key: String,
        english: String
    ) {
        assertEquals(english, en[key]?.replace("\\n", "\n"), "English $key")
        val spanish = assertNotNull(es[key], "no Spanish $key")
        assertNotEquals(english, spanish.replace("\\n", "\n"), "$key is not translated")
        assertEquals(placeholders(english), placeholders(spanish), "$key: the Spanish placeholders differ")
        assertTrue("R.string.$key" in source, "ZchatComposeView does not show $key")
    }

    private fun placeholders(text: String): List<String> = SpanishSendPaymentDialogTest.placeholders(text)
}
