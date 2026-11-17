package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 4 report §3 "Spanish still missing" — what a phone set to Spanish still showed in English after
 * round 4 translated the money, Destroy and Change-Identity surfaces:
 *
 * - the unverified-contact banner (its short label, its explanation and its button);
 * - the privacy chip under the chat header, in all six of its states;
 * - every "You switched this chat to …" system note, including the three a peer's switch produces and
 *   the "You kept this chat on …" one;
 * - the "Tap to set nickname" hint under the contact's name;
 * - the recovery-phrase backup banner on the chat list;
 * - the "You:" prefix in the chat-list preview;
 * - More → Theme / Notifications / Backup Seed Phrase / Security / Change Identity, the theme and
 *   notification names those two rows show, and the two dialogs plus the Notifications screen they open.
 *
 * And the phone check that came with them: after the phone's language is switched back, the *numbers*
 * must switch back too, without restarting the app. The activity declares `locale` in `configChanges`
 * (ui-lib/src/main/AndroidManifest.xml), so a language change does NOT recreate it: Compose updates
 * `LocalConfiguration`, every `stringResource` reader recomposes, and text returns to the new language —
 * but a composable that formats with `Locale.getDefault()`, or holds a `DecimalFormat` in a keyless
 * `remember`, reads no Compose state and is never invalidated, so its numbers keep the old language
 * until the process restarts. Money display only: no amount, fee or parse changes.
 *
 * Presentation only. Nothing about what is sent, stored or spent changes.
 */
class SpanishChatSurfacesAndMoreTextTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()

    /** Names whose Spanish is legitimately the same text as the English (proper names, "PIN", "OK"). */
    private val sameInBothLanguages =
        setOf(
            "theme_name_nightwire_dark",
            "theme_name_nightwire_light",
        )

    // ---- the chat screen ----

    @Test
    fun the_unverified_contact_banner_has_no_hard_coded_english() {
        val detail = src("screen/chat/view/ChatDetailView.kt")
        for (english in listOf(
            "\"Unverified contact\"",
            "“Unverified” just means",
            "\"Check safety number\"",
        )) {
            assertFalse(english in detail, "still hard-coded English on the unverified-contact banner: $english")
        }
    }

    @Test
    fun the_privacy_chip_has_no_hard_coded_english() {
        val detail = src("screen/chat/view/ChatDetailView.kt")
        for (english in listOf(
            "\"End-to-end encrypted over NOSTR — not on-chain\"",
            "\"Ironwood funds are confirming — messaging resumes once they confirm\"",
            "\"Funds are in the sealed Orchard pool",
            "\"Messages are shielded on-chain via Zcash\"",
            "\"Funds need shielding for full privacy\"",
        )) {
            assertFalse(english in detail, "still hard-coded English on the privacy chip: $english")
        }
    }

    @Test
    fun the_nickname_hint_has_no_hard_coded_english() {
        assertFalse(
            "\"Tap to set nickname\"" in src("screen/chat/view/ChatDetailView.kt"),
            "the set-nickname hint under the contact's name is hard-coded English"
        )
    }

    @Test
    fun the_chat_surface_strings_are_translated() {
        assertTranslated(
            "chat_unverified_contact_label",
            "chat_unverified_contact_body",
            "chat_unverified_check_safety_number",
            "chat_nickname_hint",
            "chat_privacy_chip_nostr",
            "chat_privacy_chip_ironwood_confirming",
            "chat_privacy_chip_ironwood_confirming_progress",
            "chat_privacy_chip_stranded",
            "chat_privacy_chip_shielded",
            "chat_privacy_chip_needs_shielding",
        )
    }

    @Test
    fun the_confirming_chip_keeps_its_progress_placeholder() {
        for (dir in listOf("values", "values-es")) {
            val text = assertNotNull(allStrings(dir)["chat_privacy_chip_ironwood_confirming_progress"], dir)
            assertTrue("%1\$s" in text, "$dir: the confirming chip lost the confirmation-progress placeholder")
        }
    }

    // ---- the mode system notes ----

    @Test
    fun the_mode_notes_have_no_hard_coded_english() {
        val vm = src("screen/chat/viewmodel/ChatViewModel.kt")
        for (english in listOf(
            "\"🔀 You switched this chat to",
            "switched this chat to \${modeLabel",
            "asked to switch this chat to",
            "\"You kept this chat on",
            "Your side is unchanged",
        )) {
            assertFalse(english in vm, "still hard-coded English in a mode system note: $english")
        }
    }

    @Test
    fun the_mode_notes_are_translated() {
        assertTranslated(
            "chat_mode_label_vault",
            "chat_mode_label_tunnel",
            "chat_mode_label_open",
            "chat_note_you_switched",
            "chat_note_you_switched_to_match",
            "chat_note_peer_switched_followed",
            "chat_note_peer_asked_to_switch",
            "chat_note_peer_switched_unchanged",
            "chat_note_you_kept_mode",
        )
    }

    /**
     * The English mode labels are pinned by UserFacingPrivacyClaimsTest (Open goes through three relays,
     * not one). Moving them into resources must not quietly reword them, so the English resource has to
     * stay byte-for-byte what [modeNoteLabel] returns.
     */
    @Test
    fun the_english_mode_labels_still_say_exactly_what_they_said() {
        val en = allStrings("values")
        assertTrue(
            en["chat_mode_label_vault"] == modeNoteLabel(ConversationMode.VAULT),
            "the English Shielded label changed when it became a resource"
        )
        assertTrue(
            en["chat_mode_label_tunnel"] == modeNoteLabel(ConversationMode.TUNNEL),
            "the English Tunnel label changed when it became a resource"
        )
        assertTrue(
            en["chat_mode_label_open"] == modeNoteLabel(ConversationMode.OPEN),
            "the English Open label changed when it became a resource"
        )
    }

    @Test
    fun the_peer_mode_notes_keep_both_placeholders() {
        for (dir in listOf("values", "values-es")) {
            val strings = allStrings(dir)
            for (name in listOf(
                "chat_note_peer_switched_followed",
                "chat_note_peer_asked_to_switch",
                "chat_note_peer_switched_unchanged",
                "chat_note_you_switched_to_match",
            )) {
                val text = assertNotNull(strings[name], "$dir has no $name")
                assertTrue("%1\$s" in text && "%2\$s" in text, "$dir: $name lost the name or the mode placeholder")
            }
        }
    }

    // ---- the chat list ----

    @Test
    fun the_chat_list_has_no_hard_coded_english() {
        val list = src("screen/chat/view/ChatListView.kt")
        for (english in listOf(
            "\"You: \$previewText\"",
            "\"Back up your recovery phrase\"",
            "\"It's the only way to recover your funds if this device is lost or reset.\"",
            "\"Back Up\"",
        )) {
            assertFalse(english in list, "still hard-coded English on the chat list: $english")
        }
    }

    @Test
    fun the_chat_list_strings_are_translated() {
        assertTranslated(
            "chat_list_you_prefix",
            "chat_backup_banner_title",
            "chat_backup_banner_body",
            "chat_backup_banner_button",
        )
    }

    @Test
    fun the_you_prefix_keeps_the_message_placeholder() {
        for (dir in listOf("values", "values-es")) {
            val text = assertNotNull(allStrings(dir)["chat_list_you_prefix"], dir)
            assertTrue("%1\$s" in text, "$dir: the chat-list \"You:\" prefix lost the message placeholder")
        }
    }

    // ---- More, and what its rows open ----

    @Test
    fun the_more_rows_have_no_hard_coded_english() {
        val vm = src("screen/more/MoreVM.kt")
        for (english in listOf(
            "stringRes(\"Theme:",
            "stringRes(\"Notifications:",
            "stringRes(\"Backup Seed Phrase\")",
            "stringRes(\"Security\")",
            "stringRes(\"Change Identity\")",
            "\"Full Preview\"",
            "\"Sender Only\"",
            "\"New Message\"",
            "\"Silent\"",
        )) {
            assertFalse(english in vm, "still hard-coded English in the More menu: $english")
        }
    }

    @Test
    fun the_theme_names_have_no_hard_coded_english() {
        val theme = src("screen/settings/model/ThemePreference.kt")
        for (english in listOf(
            "SYSTEM -> \"System\"",
            "LIGHT -> \"Light\"",
            "DARK -> \"Dark\"",
            "ZYPHERPUNK -> \"Nightwire Dark\"",
            "NIGHTWIRE_LIGHT -> \"Nightwire Light\"",
        )) {
            assertFalse(english in theme, "still hard-coded English theme name: $english")
        }
    }

    @Test
    fun the_theme_and_notification_dialogs_have_no_hard_coded_english() {
        val dialogs = src("screen/settings/view/ThemeSelectorView.kt")
        for (english in listOf(
            "\"Choose Theme\"",
            "\"Notification Privacy\"",
            "\"Follow device settings\"",
            "\"Light/White background\"",
            "\"Dark background\"",
            "\"Cypherpunk dark: cyan/magenta/green, neon glow\"",
            "\"Cypherpunk daylight: bone paper, teal, garnet\"",
            "\"Shows sender and message content\"",
            "\"Shows who messaged, hides content\"",
            "\"No notifications, check app manually\"",
            "contentDescription = \"Selected\"",
        )) {
            assertFalse(english in dialogs, "still hard-coded English in the Theme/Notifications dialog: $english")
        }
    }

    @Test
    fun the_notifications_screen_has_no_hard_coded_english() {
        val screen = src("screen/notificationsettings/NotificationSettingsView.kt")
        for (english in listOf(
            "title = \"Notifications\"",
            "\"Privacy Level\"",
            "\"Notification Content\"",
            "\"Sound & Vibration\"",
            "\"Play notification sound for new messages\"",
            "\"Vibrate for new messages\"",
            "\"Permissions\"",
            "\"Notification Permission\"",
            "\"Required to show message notifications\"",
            "\"Notification permission granted\"",
            "\"Battery Optimization\"",
            "\"Exempt ZCHAT for reliable background sync\"",
            "\"Battery optimization exempted\"",
            "\"Muted Conversations\"",
            "\"No muted conversations\"",
            "contentDescription = \"Unmute\"",
        )) {
            assertFalse(english in screen, "still hard-coded English on the Notifications screen: $english")
        }
    }

    @Test
    fun the_more_strings_are_translated() {
        assertTranslated(
            "more_row_theme",
            "more_row_notifications",
            "more_row_backup_seed",
            "more_row_security",
            "theme_dialog_title",
            "theme_name_system",
            "theme_name_light",
            "theme_name_dark",
            "theme_name_nightwire_dark",
            "theme_name_nightwire_light",
            "theme_desc_system",
            "theme_desc_light",
            "theme_desc_dark",
            "theme_desc_nightwire_dark",
            "theme_desc_nightwire_light",
            "theme_option_selected",
            "notif_privacy_dialog_title",
            "notif_privacy_full_preview",
            "notif_privacy_sender_only",
            "notif_privacy_new_message",
            "notif_privacy_silent",
            "notif_privacy_desc_full_preview",
            "notif_privacy_desc_sender_only",
            "notif_privacy_desc_new_message",
            "notif_privacy_desc_silent",
            "notif_title",
            "notif_section_privacy_level",
            "notif_row_content",
            "notif_section_sound",
            "notif_sound_title",
            "notif_sound_subtitle",
            "notif_vibration_title",
            "notif_vibration_subtitle",
            "notif_section_permissions",
            "notif_permission_title",
            "notif_permission_subtitle",
            "notif_permission_button",
            "notif_permission_granted",
            "notif_battery_title",
            "notif_battery_subtitle",
            "notif_battery_button",
            "notif_battery_exempt",
            "notif_section_muted",
            "notif_no_muted",
            "notif_unmute",
        )
    }

    @Test
    fun the_more_rows_keep_their_value_placeholder() {
        for (dir in listOf("values", "values-es")) {
            val strings = allStrings(dir)
            assertTrue("%1\$s" in assertNotNull(strings["more_row_theme"], dir), "$dir: the Theme row lost its value")
            assertTrue(
                "%1\$s" in assertNotNull(strings["more_row_notifications"], dir),
                "$dir: the Notifications row lost its value"
            )
        }
    }

    /** The Change Identity row reuses round 4's identity_title rather than adding a second copy of it. */
    @Test
    fun the_change_identity_row_reuses_the_translated_title() {
        assertTrue(
            "R.string.identity_title" in src("screen/more/MoreVM.kt"),
            "the Change Identity row does not use the translated identity_title"
        )
    }

    // ---- numbers follow the phone's language without a restart ----

    /**
     * The activity keeps `locale` in configChanges, so this rule is what makes numbers follow a language
     * change at all. If that ever changes the rule can be relaxed — until then it must hold.
     */
    @Test
    fun the_activity_still_handles_the_locale_change_itself() {
        val manifest = File(repoRoot, "ui-lib/src/main/AndroidManifest.xml").readText()
        assertTrue(
            Regex("""android:configChanges="[^"]*locale""").containsMatchIn(manifest),
            "the activity no longer handles locale changes itself"
        )
    }

    @Test
    fun the_chat_balance_badge_does_not_format_with_the_process_default_locale() {
        val list = src("screen/chat/view/ChatListView.kt")
        assertFalse(
            "toZecString(java.util.Locale.getDefault())" in list,
            "the chat-list balance formats with Locale.getDefault(), so its separators keep the old " +
                "language until the app is restarted"
        )
        assertTrue(
            "rememberDesiredFormatLocale()" in list,
            "the chat-list balance reads no Compose-observed locale, so a language change never re-formats it"
        )
    }

    @Test
    fun the_cost_dialogs_do_not_hold_a_locale_less_number_format() {
        val dialogs = src("screen/chat/view/ChatDialogs.kt")
        assertFalse(
            "remember { DecimalFormat(" in dialogs,
            "a cost dialog holds a DecimalFormat in a keyless remember, so its amounts keep the old " +
                "language's separators until the app is restarted"
        )
        assertTrue(
            "rememberDesiredFormatLocale()" in dialogs,
            "the cost dialogs read no Compose-observed locale, so a language change never re-formats them"
        )
    }

    /** Round 2's rule, kept over the strings this batch adds: none of them may be left in English. */
    @Test
    fun the_new_spanish_strings_are_really_spanish() {
        val es = allStrings("values-es")
        val prefixes =
            listOf(
                "chat_privacy_chip_",
                "chat_note_",
                "chat_backup_banner_",
                "more_row_",
                "theme_desc_",
                "notif_",
            )
        val english = Regex("""\b(Theme|Notifications|Sound|Vibration|Permissions|Muted|switched|Back up)\b""")
        val stale =
            es
                .filterKeys { name -> prefixes.any { name.startsWith(it) } }
                .filterValues { english.containsMatchIn(it) }
        assertTrue(stale.isEmpty(), "Spanish text left in English: ${stale.keys}")
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
