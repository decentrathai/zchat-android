package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R6-3 (round 5 report §5 item 6) — what a phone set to Spanish still showed in English after round 5:
 *
 * - the whole chat overflow menu (⋮), including "Conversation mode: Shielded" and the disappearing-messages
 *   timer it names;
 * - the whole conversation-mode picker: its title, the three mode descriptions, the greyed-out Open hint and
 *   Cancel / Apply;
 * - the group screens end to end: the group chat, New Group and Group Settings, the send/invite banners and
 *   the member count under the group's name;
 * - the chat-list section headers ("Groups" / "Chats") and the group row;
 * - the composer: the cost line, the placeholder, the recording bar and the + menu;
 * - the date separators ("TODAY", "YESTERDAY", "Sep 23, 2026", "BLOCK").
 *
 * The mode names Shielded / Tunnel / Open stay as they are (product names, as every other Spanish string
 * already writes them). The English picker descriptions are the wording UserFacingPrivacyClaimsTest and
 * HonestCopySecondPassTest pin; their resources must stay byte-for-byte equal to it.
 *
 * Presentation only. Nothing about what is sent, stored or spent changes.
 */
class SpanishChatMenusGroupsAndComposerTextTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()

    /** The body of the top-level function [name] in [file]: from its declaration to the next top-level one. */
    private fun function(
        file: String,
        name: String
    ): String {
        val start = file.indexOf("fun $name(")
        assertTrue(start >= 0, "no function $name")
        val next =
            Regex("""\n(?:@Composable\s*\n)?(?:private |internal )?fun [A-Za-z]""")
                .find(file, start + 1)
                ?.range
                ?.first ?: file.length
        return file.substring(start, next)
    }

    /** Names whose Spanish is legitimately the same text as the English. */
    private val sameInBothLanguages =
        setOf(
            "chat_list_section_chats",
            "group_error_title",
            "group_admin_badge",
        )

    private fun assertNoEnglish(
        where: String,
        body: String,
        vararg literals: String
    ) {
        val left = literals.filter { it in body }
        assertTrue(left.isEmpty(), "$where still shows hard-coded English: $left")
    }

    // ---- the chat overflow menu ----

    @Test
    fun the_chat_overflow_menu_has_no_hard_coded_english() {
        val detail = src("screen/chat/view/ChatDetailView.kt")
        assertNoEnglish(
            "the chat ⋮ menu",
            detail,
            "contentDescription = \"More actions\"",
            "Text(\"End-to-end encrypted\")",
            "\"Every message in this chat is encrypted — always on.\"",
            "\"Every message in this chat is end-to-end encrypted.\"",
            "Text(\"Search messages\")",
            "\"Unmute\" else \"Mute\"",
            "\"Verify safety number\"",
            "\"Enable Extra Security (Post-Quantum)\"",
            "\"Conversation mode: ",
            "\"Reconnect (re-send my key)\"",
            "\"May cost a small ZEC fee.",
            "\"Disappearing messages: ",
            "\"Reset encryption…\"",
        )
        assertNoEnglish(
            "the disappearing-messages timer the ⋮ menu names",
            function(detail, "ttlLabel"),
            "\"1 minute\"",
            "\"1 hour\"",
            "\"1 day\"",
            "\"1 week\"",
            " days\"",
            " hours\"",
            " minutes\"",
        )
    }

    @Test
    fun the_chat_overflow_menu_is_translated() {
        assertTranslated(
            "chat_menu_more_actions",
            "chat_menu_e2e_title",
            "chat_menu_e2e_subtitle",
            "chat_menu_e2e_toast",
            "chat_menu_search",
            "chat_menu_mute",
            "chat_menu_unmute",
            "chat_menu_verify_safety_number",
            "chat_menu_extra_security",
            "chat_menu_conversation_mode",
            "chat_menu_reconnect_title",
            "chat_menu_reconnect_subtitle",
            "chat_menu_disappearing",
            "chat_menu_disappearing_off",
            "chat_menu_reset_encryption",
        )
        assertPluralsTranslated("chat_ttl_minutes", "chat_ttl_hours", "chat_ttl_days", "chat_ttl_weeks")
        assertKeepsPlaceholder("chat_menu_conversation_mode", "%1\$s")
        assertKeepsPlaceholder("chat_menu_disappearing", "%1\$s")
    }

    /**
     * Once the ⋮ menu is translated, a Spanish sentence that tells the user to tap "⋮ → Conversation mode"
     * points at an entry a Spanish phone no longer shows. Every such pointer must name the Spanish entry.
     */
    @Test
    fun spanish_directions_to_the_mode_menu_name_the_spanish_entry() {
        val es = allStrings("values-es")
        val menuEs = assertNotNull(es["chat_menu_conversation_mode"], "values-es has no chat_menu_conversation_mode")
        val entry = menuEs.substringBefore(":").trim()
        assertTrue(entry.isNotEmpty() && entry != "Conversation mode", "the Spanish ⋮ entry is not translated: $menuEs")
        val stale = es.filterValues { "Conversation mode" in it }.keys
        assertTrue(stale.isEmpty(), "Spanish text still sends the user to \"Conversation mode\": $stale")
        assertTrue(
            es.values.any { "⋮ → $entry" in it },
            "no Spanish direction names the translated entry \"$entry\""
        )
    }

    // ---- the conversation-mode picker ----

    @Test
    fun the_mode_picker_has_no_hard_coded_english() {
        val picker = function(src("screen/chat/view/ConversationModeUi.kt"), "ConversationModePickerDialog")
        assertNoEnglish(
            "the conversation-mode picker",
            picker,
            "Text(\"Apply\")",
            "Text(\"Cancel\")",
            "Text(\"Conversation mode\")",
            "OPEN_NEEDS_KEY_HINT else oneLiner",
        )
    }

    @Test
    fun the_mode_picker_is_translated() {
        assertTranslated(
            "chat_mode_picker_title",
            "chat_mode_picker_apply",
            "chat_mode_picker_cancel",
            "chat_mode_picker_desc_vault",
            "chat_mode_picker_desc_tunnel",
            "chat_mode_picker_desc_open",
            "chat_mode_picker_open_needs_key",
        )
    }

    /** The English descriptions are pinned elsewhere as constants; the resources must not drift from them. */
    @Test
    fun the_mode_picker_english_is_the_pinned_wording() {
        val en = allStrings("values")
        assertEquals(modeBlurb(ConversationMode.VAULT).first, en["chat_mode_picker_desc_vault"])
        assertEquals(modeBlurb(ConversationMode.TUNNEL).first, en["chat_mode_picker_desc_tunnel"])
        assertEquals(modeBlurb(ConversationMode.OPEN).first, en["chat_mode_picker_desc_open"])
        assertEquals(OPEN_NEEDS_KEY_HINT, en["chat_mode_picker_open_needs_key"])
    }

    @Test
    fun the_spanish_mode_descriptions_keep_the_cost_and_the_mode_names() {
        val es = allStrings("values-es")
        assertTrue("0,00017 ZEC" in es.getValue("chat_mode_picker_desc_vault"), "Shielded lost its cost figure")
        val hint = es.getValue("chat_mode_picker_open_needs_key")
        assertTrue("Tunnel" in hint && "Open" in hint, "the greyed-out Open hint must name Tunnel and Open: $hint")
        assertTrue("ZEC" in hint, "the greyed-out Open hint must say Tunnel costs ZEC: $hint")
    }

    // ---- the composer ----

    @Test
    fun the_composer_has_no_hard_coded_english() {
        val input = function(src("screen/chat/view/ChatDetailView.kt"), "MessageInput")
        assertNoEnglish(
            "the chat composer",
            input,
            "\"Message cost: ",
            "\" (tap to change)\"",
            "\"Free — sent over NOSTR\"",
            "\"View-once  %d:%02d\"",
            "\"Recording  %d:%02d\"",
            "\"Cancel recording\"",
            "\"Send recording\"",
            "\"Special features\"",
            "\"Send Payment\"",
            "\"Send ZEC to this contact\"",
            "\"Time-Locked Message\"",
            "\"Message unlocked by time",
            "\"Shielded chats only — switch mode",
            "\"Quick Pay Templates\"",
            "\"Pre-saved payment shortcuts\"",
            "\"Take Photo\"",
            "\"Capture with camera (encrypted)\"",
            "\"Send Image\"",
            "\"Share a photo from gallery (encrypted)\"",
            "\"Send view-once photo\"",
            "\"Recipient sees it once",
            "\"Send File\"",
            "\"PDF, ZIP, TXT, image",
            "\"Request Payment\"",
            "\"Ask contact to pay you\"",
            "\"Message...\"",
            "\"Replies not available\"",
            "\"Message trimmed to ",
            "contentDescription = \"Send\"",
        )
    }

    @Test
    fun the_composer_is_translated() {
        assertTranslated(
            "chat_composer_cost",
            "chat_composer_tap_to_change",
            "chat_composer_free_nostr",
            "chat_composer_recording",
            "chat_composer_recording_view_once",
            "chat_composer_cancel_recording",
            "chat_composer_send_recording",
            "chat_composer_features",
            "chat_feature_send_payment",
            "chat_feature_send_payment_desc",
            "chat_feature_time_lock",
            "chat_feature_time_lock_desc",
            "chat_feature_time_lock_shielded_only",
            "chat_feature_templates",
            "chat_feature_templates_desc",
            "chat_feature_take_photo",
            "chat_feature_take_photo_desc",
            "chat_feature_send_image",
            "chat_feature_send_image_desc",
            "chat_feature_view_once",
            "chat_feature_view_once_desc",
            "chat_feature_send_file",
            "chat_feature_send_file_desc",
            "chat_feature_request_payment",
            "chat_feature_request_payment_desc",
            "chat_composer_placeholder",
            "chat_composer_placeholder_disabled",
            "chat_composer_trimmed",
            "chat_composer_send",
        )
        assertKeepsPlaceholder("chat_composer_cost", "%1\$s")
        assertKeepsPlaceholder("chat_composer_trimmed", "%1\$d")
        assertKeepsPlaceholder("chat_composer_recording", "%1\$d", "%2\$02d")
        assertKeepsPlaceholder("chat_composer_recording_view_once", "%1\$d", "%2\$02d")
    }

    /** The cost line is a money figure: it follows the phone's language like R5-4's cost dialogs. */
    @Test
    fun the_composer_cost_does_not_format_with_the_process_default_locale() {
        val input = function(src("screen/chat/view/ChatDetailView.kt"), "MessageInput")
        assertFalse(
            "String.format(\"%.5f\", zecAmount)" in input,
            "the composer's message cost is formatted with Locale.getDefault(), so its separators keep the old " +
                "language until the app is restarted"
        )
        assertTrue("rememberDesiredFormatLocale()" in input, "the composer reads no Compose-observed locale")
    }

    // ---- the date separators ----

    @Test
    fun the_date_separators_have_no_hard_coded_english() {
        val separator = function(src("screen/chat/view/ChatDetailView.kt"), "DateSeparator")
        assertNoEnglish(
            "the date separator",
            separator,
            "\"TODAY\"",
            "\"YESTERDAY\"",
            "BLOCK \${",
            "ofPattern(\"MMM d, yyyy\")",
        )
        assertTrue(
            "rememberDesiredFormatLocale()" in separator,
            "the date separator formats dates with no Compose-observed locale, so month names stay English"
        )
    }

    @Test
    fun the_date_separators_are_translated() {
        assertTranslated("chat_date_today", "chat_date_yesterday", "chat_date_today_block")
        assertKeepsPlaceholder("chat_date_today_block", "%1\$s", "%2\$s")
    }

    // ---- the chat list ----

    @Test
    fun the_chat_list_section_headers_and_group_row_have_no_hard_coded_english() {
        val list = src("screen/chat/view/ChatListView.kt")
        assertNoEnglish(
            "the chat list",
            list,
            "SectionHeader(title = \"Groups\")",
            "SectionHeader(title = \"Chats\")",
        )
        assertNoEnglish(
            "the group row on the chat list",
            function(list, "GroupItem"),
            "\"Group chat\"",
            "Text(\"Leave Group\")",
        )
    }

    @Test
    fun the_chat_list_section_headers_and_group_row_are_translated() {
        assertTranslated(
            "chat_list_section_groups",
            "chat_list_section_chats",
            "chat_list_group_chat",
            "group_leave",
        )
    }

    // ---- the group screens ----

    @Test
    fun the_group_chat_screen_has_no_hard_coded_english() {
        val detail = src("screen/chat/view/GroupDetailView.kt")
        assertNoEnglish(
            "the group chat screen",
            detail,
            "text = \"Error\"",
            "\"Message couldn't be delivered to anyone",
            "\"Delivered to ",
            "\"Couldn't invite ",
            "member(s) haven't received",
            "contentDescription = \"Back\"",
            "contentDescription = \"Group Settings\"",
            "\"No messages yet\"",
            "\"Send the first message!\"",
            "Text(\"Message...\")",
            "contentDescription = \"Send\"",
            "\"Tap to sync group keys\"",
            // The model's English "2 members · 1 active" label; the screen must count in the phone's language.
            "conversation.memberCountLabel",
        )
    }

    @Test
    fun the_new_group_screen_has_no_hard_coded_english() {
        assertNoEnglish(
            "the New Group screen",
            src("screen/chat/view/CreateGroupView.kt"),
            "\"New Group\"",
            "contentDescription = \"Back\"",
            "Text(\"Group Name\")",
            "\"Enter group name...\"",
            "\"Add Members\"",
            "/9 selected\"",
            "\"No contacts available\"",
            "\"Start chatting with someone first\"",
            "\"Creating group\"",
            "\"Creating…\"",
            "\"Create Group (",
        )
    }

    @Test
    fun the_group_settings_screen_has_no_hard_coded_english() {
        assertNoEnglish(
            "the Group Settings screen",
            src("screen/chat/view/GroupSettingsView.kt"),
            "text = \"Error\"",
            "\"Couldn't load that image\"",
            "Text(\"Group Settings\")",
            "contentDescription = \"Back\"",
            "\"Edit group photo\"",
            "} members\"",
            "\"Created \${",
            "\"Copy Group ID\"",
            "text = \"Members\"",
            "text = \"Add\"",
            "\"No members\"",
            "Text(\"Rotate Group Key\")",
            "Text(\"Leave Group\")",
            "title = \"Group photo\"",
            "Text(\"Leave Group?\")",
            "Are you sure you want to leave",
            "Text(\"Leave\")",
            "Text(\"Cancel\")",
            "Text(\"Rotate Group Key?\")",
            "Generate a fresh group key",
            "Text(\"Rotate\")",
            "Text(\"Add Member\")",
            "No one to add yet",
            "Text(\"Done\")",
            "\"(you)\"",
            "\"Invite failed\"",
            "\"Inviting…\"",
            "-> \"Invited\"",
            "text = \"Resend\"",
            "\"Admin badge\"",
            "text = \"Admin\"",
            "\"Remove member\"",
            "Text(\"Remove member?\")",
            "from the group? The group key will be rotated",
            "Text(\"Remove\")",
            "ofPattern(\"MMM d, yyyy\")",
        )
    }

    @Test
    fun the_group_screens_are_translated() {
        assertTranslated(
            "group_error_title",
            "group_send_all_failed",
            "group_back",
            "group_settings_title",
            "group_no_messages",
            "group_send_first_message",
            "group_tap_to_sync_keys",
            "group_new_title",
            "group_name_label",
            "group_name_placeholder",
            "group_add_members",
            "group_no_contacts",
            "group_no_contacts_hint",
            "group_creating_description",
            "group_creating",
            "group_photo_load_failed",
            "group_edit_photo",
            "group_created_on",
            "group_copy_id",
            "group_members_header",
            "group_add",
            "group_no_members",
            "group_rotate_key",
            "group_leave",
            "group_photo_title",
            "group_leave_confirm_title",
            "group_leave_confirm_body",
            "group_leave_confirm_button",
            "chat_action_cancel",
            "group_rotate_confirm_title",
            "group_rotate_confirm_body",
            "group_rotate_confirm_button",
            "group_add_member_title",
            "group_add_member_empty",
            "group_done",
            "group_member_you",
            "group_invite_failed_status",
            "group_inviting_status",
            "group_invited_status",
            "group_resend",
            "group_admin_badge_description",
            "group_admin_badge",
            "group_remove_member_description",
            "group_remove_member_title",
            "group_remove_member_body",
            "group_remove_member_button",
            "group_create_selected",
        )
        assertPluralsTranslated(
            "group_send_partial",
            "group_invite_failed",
            "group_removal_incomplete",
            "group_member_count",
            "group_member_active",
            "group_create_button",
        )
        assertKeepsPlaceholder("group_created_on", "%1\$s")
        assertKeepsPlaceholder("group_leave_confirm_body", "%1\$s")
        assertKeepsPlaceholder("group_remove_member_body", "%1\$s")
        assertKeepsPlaceholder("group_create_selected", "%1\$d", "%2\$d")
    }

    // ---- helpers ----

    private fun assertTranslated(vararg names: String) {
        val en = allStrings("values")
        val es = allStrings("values-es")
        for (name in names) {
            val english = assertNotNull(en[name], "values has no $name")
            val spanish = assertNotNull(es[name], "values-es has no $name")
            assertTrue(english.isNotBlank(), "$name has no English text")
            assertTrue(spanish.isNotBlank(), "$name has no Spanish text")
            if (name !in sameInBothLanguages) {
                assertTrue(spanish != english, "$name is not translated")
            }
        }
    }

    private fun assertPluralsTranslated(vararg names: String) {
        val en = allPlurals("values")
        val es = allPlurals("values-es")
        for (name in names) {
            val english = assertNotNull(en[name], "values has no <plurals name=\"$name\">")
            val spanish = assertNotNull(es[name], "values-es has no <plurals name=\"$name\">")
            assertTrue("other" in english && "other" in spanish, "$name has no quantity=\"other\"")
            assertTrue(english["other"] != spanish["other"], "$name is not translated")
            val placeholders = Regex("""%\d\$[ds]""").findAll(english.getValue("other")).map { it.value }.toSet()
            for ((quantity, text) in spanish) {
                for (p in placeholders) assertTrue(p in text, "values-es/$name[$quantity] lost $p: $text")
            }
        }
    }

    private fun assertKeepsPlaceholder(
        name: String,
        vararg placeholders: String
    ) {
        for (dir in listOf("values", "values-es")) {
            val text = assertNotNull(allStrings(dir)[name], "$dir has no $name")
            for (p in placeholders) assertTrue(p in text, "$dir/$name lost $p: $text")
        }
    }

    private fun resFiles(dir: String): List<File> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module -> File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }

    private fun allStrings(dir: String): Map<String, String> =
        resFiles(dir).fold(emptyMap()) { acc, f ->
            acc +
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(f.readText())
                    .associate { m -> m.groupValues[1] to unescape(m.groupValues[2]) }
        }

    private fun allPlurals(dir: String): Map<String, Map<String, String>> =
        resFiles(dir).fold(emptyMap()) { acc, f ->
            acc +
                Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(f.readText())
                    .associate { m ->
                        m.groupValues[1] to
                            Regex("""<item quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                                .findAll(m.groupValues[2])
                                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
                    }
        }

    private fun unescape(raw: String) =
        raw
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
