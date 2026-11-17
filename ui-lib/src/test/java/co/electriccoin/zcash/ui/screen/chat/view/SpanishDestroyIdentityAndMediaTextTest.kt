package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 3 report 4 item 12 — what is still English on a Spanish phone after the money texts were translated
 * (round 2 report 4 item 16, commit cffb4c396):
 *
 * - the Destroy screens (Emergency Destroy: confirm, PIN, biometric, last step, countdown, done) and the PIN
 *   and biometric errors the view model produces;
 * - the Change Identity screen (mode picker, warning card, confirmation dialog, success dialog, errors);
 * - the Stranded funds dialog in the chat (title, the two balance lines, the closing paragraph);
 * - the recorder, camera and file toasts;
 * - the "Retry sending" screen-reader label on the Retry row.
 *
 * Each must be a string resource with an English and a Spanish value, and no hard-coded English may be left
 * at the call site. Nothing about what is sent or spent changes: this is presentation only.
 */
class SpanishDestroyIdentityAndMediaTextTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()

    /** Names whose Spanish is legitimately the same word as the English ("PIN", "OK", "Error"). */
    private val sameInBothLanguages =
        setOf(
            "destroy_pin_label",
            "identity_error_title",
            "chat_dialog_ok",
        )

    // ---- Destroy ----

    @Test
    fun the_destroy_screen_has_no_hard_coded_english() {
        val view = src("screen/enhanceddestroy/view/EnhancedDestroyView.kt")
        for (english in listOf(
            "\"Emergency Destroy\"",
            "\"Destroy All Data\"",
            "\"This will permanently delete:\"",
            "\"All chat messages and history\"",
            "\"Your wallet and private keys\"",
            "\"This action CANNOT be undone.",
            "\"I Understand, Continue\"",
            "\"Enter Destroy PIN\"",
            "\"Verify PIN\"",
            "\"Biometric Verification\"",
            "\"Authenticate\"",
            "\"Last Step\"",
            "\"ZCHAT does not tell your contacts that you have left.\"",
            "\"Start 5-Second Countdown\"",
            "\"Go Back\"",
            "\"DESTROYING IN...\"",
            "\"Tap cancel to abort\"",
            "\"Destroying All Data...\"",
            "\"Data Destroyed\"",
        )) {
            assertFalse(english in view, "still hard-coded English on the Destroy screen: $english")
        }
    }

    @Test
    fun the_destroy_errors_have_no_hard_coded_english() {
        val vm = src("screen/enhanceddestroy/EnhancedDestroyVM.kt")
        for (english in listOf(
            "\"PIN must be at least 4 digits\"",
            "\"Incorrect PIN.\"",
            "\"Set a Destroy PIN in Settings",
            "\"Too many failed attempts.",
        )) {
            assertFalse(english in vm, "still hard-coded English in the Destroy view model: $english")
        }
    }

    @Test
    fun the_destroy_strings_are_translated() {
        assertTranslated(
            "destroy_title",
            "destroy_heading",
            "destroy_will_delete",
            "destroy_item_messages",
            "destroy_item_wallet",
            "destroy_item_contacts",
            "destroy_item_settings",
            "destroy_cannot_be_undone",
            "destroy_cancel",
            "destroy_enter_pin_title",
            "destroy_enter_pin_body",
            "destroy_pin_label",
            "destroy_verify_pin",
            "destroy_biometric_title",
            "destroy_biometric_body",
            "destroy_authenticate",
            "destroy_no_notice",
            "destroy_after_countdown",
            "destroy_start_countdown",
            "destroy_destroying_in",
            "destroy_tap_cancel",
            "destroy_cancel_upper",
            "destroy_in_progress",
            "destroy_please_wait",
            "destroy_pin_too_short",
            "destroy_pin_incorrect",
            "destroy_locked_minutes",
            "destroy_locked_seconds",
        )
    }

    /** A count in a refusal is money-adjacent copy: the number must survive translation. */
    @Test
    fun the_destroy_lockout_texts_keep_their_numbers() {
        for (dir in listOf("values", "values-es")) {
            val strings = allStrings(dir)
            for (name in listOf("destroy_locked_minutes", "destroy_locked_seconds")) {
                assertTrue("%1\$d" in assertNotNull(strings[name], "$dir has no $name"), "$dir: $name lost its number")
            }
        }
    }

    // ---- Change Identity ----

    @Test
    fun the_change_identity_screen_has_no_hard_coded_english() {
        val view = src("screen/changeidentity/ChangeIdentityView.kt")
        for (english in listOf(
            "\"Change Identity\"",
            "\"Processing...\"",
            "\"Choose Regeneration Mode\"",
            "\"Diversified Address\"",
            "\"Full Wallet Reset\"",
            "\"Reset Wallet\"",
            "\"Identity Change\"",
            "\"Current Address\"",
            "\"Recommended\"",
            "\"Caution\"",
            "\"Confirm Wallet Reset\"",
            "\"Confirm Identity Change\"",
            "\"You cannot undo this action!\"",
            "\"You will generate a new messaging identity.\"",
            "\"You can switch back to your old identity later.\"",
            "\"Success!\"",
        )) {
            assertFalse(english in view, "still hard-coded English on the Change Identity screen: $english")
        }
    }

    @Test
    fun the_change_identity_strings_are_translated() {
        assertTranslated(
            "identity_title",
            "identity_processing",
            "identity_choose_mode",
            "identity_diversified_title",
            "identity_diversified_desc",
            "identity_full_reset_title",
            "identity_full_reset_desc",
            "identity_reset_wallet",
            "identity_change_heading",
            "identity_change_body",
            "identity_current_address",
            "identity_recommended",
            "identity_caution",
            "identity_confirm_reset_title",
            "identity_confirm_change_title",
            "identity_reset_warning",
            "identity_reset_step_seed",
            "identity_reset_step_transfer",
            "identity_cannot_undo",
            "identity_new_messaging",
            "identity_can_switch_back",
            "identity_confirm",
            "identity_cancel",
            "identity_success",
            "identity_change_done",
            "identity_error_loading_address",
            "identity_error_failed",
            "identity_error_change_failed",
        )
    }

    // ---- Stranded funds dialog ----

    @Test
    fun the_stranded_funds_dialog_has_no_hard_coded_english() {
        val chat = src("screen/chat/AndroidChat.kt")
        for (english in listOf(
            "\"Ironwood funds confirming\"",
            "\"• Orchard pool: ",
            "\"• Ironwood, confirming: ",
            "\"Existing Open and Tunnel chats over NOSTR keep working.",
        )) {
            assertFalse(english in chat, "still hard-coded English in the Stranded funds dialog: $english")
        }
    }

    @Test
    fun the_stranded_funds_strings_are_translated() {
        assertTranslated(
            "chat_stranded_ironwood_confirming_title",
            "chat_stranded_orchard_line",
            "chat_stranded_ironwood_line",
            "chat_stranded_ironwood_line_progress",
            "chat_stranded_relays_keep_working",
        )
    }

    /** The balance lines carry ZEC amounts; a translation that drops the placeholder would hide money. */
    @Test
    fun the_stranded_balance_lines_keep_their_amounts() {
        for (dir in listOf("values", "values-es")) {
            val strings = allStrings(dir)
            assertTrue("%1\$s" in assertNotNull(strings["chat_stranded_orchard_line"], "$dir"), "$dir: no amount")
            assertTrue("%1\$s" in assertNotNull(strings["chat_stranded_ironwood_line"], "$dir"), "$dir: no amount")
            val withProgress = assertNotNull(strings["chat_stranded_ironwood_line_progress"], "$dir")
            assertTrue("%1\$s" in withProgress && "%2\$s" in withProgress, "$dir: no amount or no progress")
        }
    }

    // ---- Recorder, camera and file toasts, and the Retry label ----

    @Test
    fun the_media_toasts_have_no_hard_coded_english() {
        val chat = src("screen/chat/AndroidChat.kt")
        for (english in listOf(
            "\"Microphone permission denied\"",
            "\"Microphone permission is required to place a call\"",
            "\"View-once recording — plays once then deletes\"",
            "\"Recording too short\"",
            "\"Couldn't start the recorder. Please try again.\"",
            "\"Voice message failed — please try again.\"",
            "\"Camera unavailable. Please try again.\"",
            "\"File picker unavailable. Please try again.\"",
            "\"Couldn’t send the photo. Please try again.\"",
        )) {
            assertFalse(english in chat, "still hard-coded English toast: $english")
        }
        val detail = src("screen/chat/view/ChatDetailView.kt")
        for (english in listOf(
            "\"File not available\"",
            "\"Saving to device requires Android 10 or newer\"",
            "\"Could not create destination\"",
            "\"Couldn't load that image\"",
            "\"Couldn’t save the file. Please try again.\"",
        )) {
            assertFalse(english in detail, "still hard-coded English toast: $english")
        }
    }

    @Test
    fun the_media_strings_are_translated() {
        assertTranslated(
            "chat_mic_permission_denied",
            "chat_mic_permission_needed_for_call",
            "chat_view_once_recording",
            "chat_recording_too_short",
            "chat_recorder_unavailable",
            "chat_voice_message_failed",
            "chat_camera_unavailable",
            "chat_file_picker_unavailable",
            "chat_photo_send_failed",
            "chat_image_load_failed",
            "chat_file_not_available",
            "chat_save_needs_android_10",
            "chat_save_no_destination",
            "chat_file_saved_to",
            "chat_file_save_failed",
            "chat_retry_sending",
        )
    }

    @Test
    fun the_retry_sending_label_is_a_resource() {
        val detail = src("screen/chat/view/ChatDetailView.kt")
        assertFalse("contentDescription = \"Retry sending\"" in detail, "the Retry row's label is hard-coded English")
        assertTrue("R.string.chat_retry_sending" in detail, "the Retry row has no screen-reader label resource")
    }

    /** Round 2's rule, kept over the strings this batch adds: none of them may be left in English. */
    @Test
    fun the_new_spanish_strings_are_really_spanish() {
        val es = allStrings("values-es")
        val stale =
            es
                .filterKeys { it.startsWith("destroy_") || it.startsWith("identity_") || it.startsWith("chat_stranded_") }
                .filterValues { Regex("\\b(Retry|Cancel|Confirm|Continue|Wallet|Warning)\\b").containsMatchIn(it) }
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
