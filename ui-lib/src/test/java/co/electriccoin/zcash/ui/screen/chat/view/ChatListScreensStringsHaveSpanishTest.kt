package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ES-A (fix-all 2.13.0, train T4c; Spanish fold spec §2, §3.2): the texts the chat list, its dialogs, the Invite
 * Friend screen and the update dialogs move into resources. Each has English (the text an English phone saw at HEAD,
 * byte for byte, except the intended changes listed apart) and a Spanish draft for the native-speaker pass (F14)
 * that differs from it and keeps its placeholders.
 */
class ChatListScreensStringsHaveSpanishTest {
    // As getString returns them: "\\n" in the XML is a line break.
    private val en by lazy { ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") } }
    private val es by lazy { ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") } }
    private val enPlurals by lazy { ChatResourceText.plurals("values") }
    private val esPlurals by lazy { ChatResourceText.plurals("values-es") }

    /** Every moved text: key to the English an English phone saw at HEAD. */
    private val movedEnglish =
        mapOf(
            // chat list: search bar, menu, photo
            "chat_list_search_placeholder" to "Search conversations...",
            "chat_list_cd_close_search" to "Close search",
            "chat_list_cd_clear_search" to "Clear search",
            "chat_list_change_my_photo" to "Change my photo",
            "chat_list_image_load_failed" to "Couldn't load that image",
            "chat_list_my_photo" to "My photo",
            "chat_list_menu_check_updates" to "Check for Updates",
            "chat_list_menu_settings" to "Settings",
            "chat_list_no_results" to "No conversations found",
            // request banner
            // QA2 Q2-32 (W-137, ZC-1600): "Nostr relays", never NOSTR in capitals.
            "chat_list_message_requests_hint" to "Tap Review to accept or decline — free, encrypted over Nostr relays",
            "chat_list_message_requests_review" to "Review",
            // sync banner
            "chat_list_sync_setting_up" to "Setting Up Wallet",
            "chat_list_sync_restoring" to "Restoring Wallet",
            "chat_list_sync_syncing" to "Syncing",
            "chat_list_sync_keep_open_setup" to "Keep app open while wallet is being set up",
            "chat_list_sync_keep_open_older" to "Keep app open • Older wallets may take longer",
            // empty list and its privacy dialog
            "chat_list_empty_title" to "No conversations yet",
            "chat_list_empty_body" to "Send a %1\$s message to get started",
            "chat_list_empty_body_link" to "private",
            "chat_list_empty_start" to "Start a Chat",
            "chat_list_empty_cd_privacy" to "Privacy",
            "chat_list_privacy_title" to "What makes it private?",
            "chat_list_privacy_body" to emptyChatPrivacyBody(),
            "chat_list_privacy_got_it" to "Got it",
            // rows
            "chat_list_row_cd_delete" to "Delete",
            "chat_list_row_swipe_delete" to "DELETE",
            "chat_list_row_cd_muted" to "Muted",
            "chat_list_row_draft_prefix" to "Draft:",
            "chat_list_address_copied" to "Address copied to clipboard",
            "chat_list_leave_group_left" to "Left group",
            "chat_list_dialog_cancel" to "Cancel",
            // unreachable dialogs kept (the status dialog is a status feature; the group dialog waits for D-1)
            "chat_list_status_title" to "Set Your Status",
            "chat_list_status_hint" to "Your status will be visible to contacts",
            "chat_list_status_label" to "Status",
            "chat_list_status_placeholder" to "What's your status?",
            "chat_list_status_quick" to "Quick status:",
            "chat_list_status_save" to "Save",
            "chat_list_status_clear" to "Clear",
            "chat_list_group_soon_title" to "Group Chats",
            "chat_list_group_soon_badge" to "COMING SOON",
            "chat_list_group_soon_body" to "Private group messaging is being built using the ZMSG-GROUP protocol.",
            "chat_list_group_soon_expect" to "What to expect:",
            "chat_list_group_soon_e2e" to "End-to-end encrypted group chats",
            "chat_list_group_soon_members" to "Up to 20 members per group",
            "chat_list_group_soon_rotation" to "Secure key rotation on member changes",
            "chat_list_group_soon_onchain" to "Fully on-chain, no servers",
            "chat_list_group_soon_note" to
                "Note: Group messages cost ~0.0001 ZEC per member (e.g., 10 members = 0.001 ZEC per message)",
            "chat_list_group_soon_got_it" to "Got it!",
            // message requests
            "message_requests_title" to "Message requests",
            "message_requests_body" to
                "These people messaged you for free over Nostr relays. The Zcash address is what the sender claims — ZChat " +
                "has not verified it. Read the message and accept only if you know who wrote it.",
            "message_requests_no_text" to "(no message text)",
            "message_requests_reject" to "Reject",
            "message_requests_accept" to "Accept",
            "message_requests_close" to "Close",
            "message_requests_error_title" to "Couldn't accept request",
            "message_requests_error_key_changed" to
                "This address is already linked to a DIFFERENT security key. That can mean the contact rotated keys — " +
                "or an impersonation attempt. We did not link it. Verify the contact out-of-band before accepting.",
            "message_requests_error_pubkey_reused" to
                "This sender's key is already linked to another contact. Accepting could let one identity impersonate " +
                "several — so we did not link it.",
            "message_requests_error_self" to "That request claims your OWN address, so it was discarded.",
            // contact name dialog and photos
            "contact_nickname_title" to "Contact name",
            "contact_nickname_prompt" to "Nickname (kept only on this phone)",
            "contact_nickname_placeholder" to "Enter nickname",
            "contact_nickname_save" to "Save",
            "contact_nickname_remove" to "Remove nickname",
            "photo_change" to "Change photo",
            "photo_choose" to "Choose photo",
            "photo_remove" to "Remove photo",
            "photo_setup_title" to "Set up photo",
            "photo_stored_locally" to "The photo is stored only on this device.",
            "photo_add_hint" to "Add a photo — it's stored only on this device.",
            "photo_cd_avatar" to "Avatar",
            // tabs
            "tab_chats" to "Chats",
            "tab_ai" to "AI",
            // invite
            "invite_scan_free_chat" to "Scan this to start a free chat",
            "invite_share_text" to "Join me on ZCHAT — private messaging that no one can read.\nDownload: %1\$s\nMy address: %2\$s",
            // update dialogs
            "update_dialog_checking" to "Checking for updates…",
            "update_dialog_up_to_date_title" to "Up to Date",
            "update_dialog_up_to_date_body" to "You're running the latest version of ZCHAT (v%1\$s).",
            "update_dialog_ok" to "OK",
            "update_dialog_available_title" to "Update Available",
            "update_dialog_available_body" to "v%1\$s is available. You have v%2\$s.",
            "update_dialog_show_details" to "View Details",
            "update_dialog_hide_details" to "Hide Details",
            "update_dialog_update_now" to "Update Now",
            "update_dialog_not_now" to "Not Now",
            "update_dialog_downloading" to "Downloading Update...",
            "update_dialog_ready_title" to "Ready to Install",
            "update_dialog_ready_body" to "If the installer didn't open, tap Install below.",
            "update_dialog_install" to "Install",
            "update_dialog_dismiss" to "Dismiss",
            "update_dialog_failed_title" to "Update Failed",
            "update_dialog_failed_unreachable" to "Could not reach update server. Check your connection.",
            "update_dialog_failed_download" to "Download failed. Check your connection and try again.",
            "update_dialog_failed_not_newer" to
                "The downloaded file is not a newer build of this app, so it was not installed. Please try again later.",
        )

    /** The English changes an English phone does see, each with its reason. */
    private val intendedEnglishChanges =
        mapOf(
            // ES-L: the chat-list Leave Group dialog reuses the group screen's texts: "Leave Group?" and the group's name.
            "group_leave_confirm_title" to "Leave Group?",
            "group_leave_confirm_body" to "Are you sure you want to leave \"%1\$s\"? You will no longer receive messages from this group.",
            "group_leave_confirm_button" to "Leave",
            // "Yesterday" is unchanged; the date of an older row follows the phone's order (en-US "Sep 3", en-GB "3 Sept").
            "chat_list_yesterday" to "Yesterday",
        )

    // The same word in both languages (a name, or a word Spanish shares).
    private val sameInBoth = setOf("tab_chats", "update_dialog_ok", "photo_cd_avatar")

    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    @Test
    fun every_new_key_has_english_and_a_different_spanish() {
        val missing = mutableListOf<String>()
        for (key in movedEnglish.keys + intendedEnglishChanges.keys) {
            val english = en[key]
            val spanish = es[key]
            if (english == null) missing += "$key: no English"
            if (spanish == null) missing += "$key: no Spanish"
            if (english != null && spanish != null && key !in sameInBoth && english == spanish) {
                missing += "$key: the Spanish is the English"
            }
        }
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }

    @Test
    fun placeholders_match_between_english_and_spanish() {
        for (key in movedEnglish.keys + intendedEnglishChanges.keys) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertEquals(
                placeholder
                    .findAll(english)
                    .map { it.value }
                    .sorted()
                    .toList(),
                placeholder
                    .findAll(spanish)
                    .map { it.value }
                    .sorted()
                    .toList(),
                "$key: the placeholders differ",
            )
        }
    }

    @Test
    fun english_phones_see_exactly_what_they_saw() {
        for ((key, text) in movedEnglish + intendedEnglishChanges) {
            assertEquals(text, en[key], "$key: the English changed")
        }
        // The privacy body is shown from the resource now; the JVM privacy tests read the function, so they must agree.
        assertEquals(emptyChatPrivacyBody(), en["chat_list_privacy_body"])
    }

    @Test
    fun the_request_count_is_a_plural_in_both_languages() {
        val english = assertNotNull(enPlurals["chat_list_message_requests"], "no English plurals chat_list_message_requests")
        val spanish = assertNotNull(esPlurals["chat_list_message_requests"], "no Spanish plurals chat_list_message_requests")
        assertEquals("1 message request", String.format(Locale.ROOT, english.getValue("one"), 1))
        assertEquals("2 message requests", String.format(Locale.ROOT, english.getValue("other"), 2))
        assertEquals("1 solicitud de mensaje", String.format(Locale.ROOT, spanish.getValue("one"), 1))
        assertEquals("2 solicitudes de mensaje", String.format(Locale.ROOT, spanish.getValue("other"), 2))
        assertTrue("many" in spanish, "Spanish needs a 'many' form (CLDR es: one / many / other)")
    }

    @Test
    fun the_banner_hint_names_the_review_button_in_its_own_language() {
        val enHint = assertNotNull(en["chat_list_message_requests_hint"])
        val esHint = assertNotNull(es["chat_list_message_requests_hint"])
        assertTrue(en.getValue("chat_list_message_requests_review") in enHint, enHint)
        assertTrue(es.getValue("chat_list_message_requests_review") in esHint, esHint)
    }

    @Test
    fun the_settings_word_is_the_one_the_app_already_uses() {
        // The app says "Configuración" for Settings everywhere; it never says "Ajustes".
        assertEquals("Configuración", es["chat_list_menu_settings"])
    }

    @Test
    fun the_empty_state_sentence_has_one_link_slot() {
        for ((lang, map) in listOf("en" to en, "es" to es)) {
            val body = assertNotNull(map["chat_list_empty_body"], "$lang: no chat_list_empty_body")
            assertEquals(1, Regex(Regex.escape("%1\$s")).findAll(body).count(), "$lang: the sentence needs exactly one %1\$s")
            assertTrue(map["chat_list_empty_body_link"].orEmpty().isNotBlank(), "$lang: the link word is empty")
        }
    }

    @Test
    fun the_spanish_privacy_body_keeps_every_fact() {
        val body = assertNotNull(es["chat_list_privacy_body"], "no Spanish chat_list_privacy_body")
        for (fact in listOf("tres relays de Nostr", "ZChat", "clave de chat", "dirección IP", "en línea", "claves de chat", "registros")) {
            assertTrue(fact in body, "the Spanish privacy body lost \"$fact\": $body")
        }
        assertFalse("no pueden ver" in body, "the Spanish privacy body says the relays cannot see something: $body")
    }

    @Test
    fun the_invite_screen_has_a_spanish_file() {
        val dir = File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui/invite")
        val englishFile = File(dir, "values/strings.xml")
        val spanishFile = File(dir, "values-es/strings.xml")
        assertTrue(spanishFile.isFile, "ui/invite has no values-es: every invite_* text is English on a Spanish phone")
        val keyRe = Regex("""<string name="([^"]+)"""")
        val englishKeys = keyRe.findAll(englishFile.readText()).map { it.groupValues[1] }.toSet()
        val spanishKeys = keyRe.findAll(spanishFile.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(englishKeys, spanishKeys, "ui/invite values-es must have every English key")
        for (key in englishKeys) assertNotEquals(en[key], es[key], "$key is English in the Spanish file")
    }

    @Test
    fun the_new_spanish_files_are_marked_as_drafts() {
        val res = File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui")
        for (path in listOf(
            "common/values-es/chat_list_screen_strings.xml",
            "invite/values-es/strings.xml",
            "update/values-es/update_dialog_strings.xml",
        )) {
            val file = File(res, path)
            assertTrue(file.isFile, "$path is missing")
            val firstComment = Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL).find(file.readText())?.groupValues?.get(1)
            assertTrue(
                firstComment?.contains("revisión nativa") == true,
                "$path: the first comment must mark it as a draft for the native pass"
            )
        }
    }
}
