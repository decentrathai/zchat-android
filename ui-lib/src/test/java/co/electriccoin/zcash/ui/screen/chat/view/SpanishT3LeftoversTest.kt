package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T3b-2 item 5 (fix-all 2.13.0, train T3b; train T3 phone finding 9, es-ES on the Seeker and the HONOR): four places
 * still showed English (or an English number) on a Spanish phone:
 * - the note after switching a chat to Tunnel (ModeSecurityNoteDialog: "Tunnel mode: a paid handshake, then public
 *   relays", "Got it"); the same dialog shows the Open note, also English literals;
 * - the Quantum Shield turn-off dialog ("Turn off Extra Security? … Cancel / Turn off") and the lines of the banner
 *   it opens from ("Adds a post-quantum key…", "long-press to turn off");
 * - the Quantum Shield scan toasts ("Invalid QR code", "Extra Security (Post-Quantum) is on!");
 * - the transaction detail's fee row "< 0.001 ZEC" (the Spanish file writes a point; every other amount on that screen
 *   uses the phone's decimal comma).
 *
 * What it must be: each comes from a string resource with the old English text as its English value (so the honest
 * copy pinned by HonestCopyTest and UserFacingPrivacyClaimsTest is exactly what an English phone shows) and a Spanish
 * value (agent-drafted, for the native-speaker pass); the Spanish fee row writes "0,001". Resources are read from
 * res/ui and ids by name, so this file compiles before the fix.
 */
class SpanishT3LeftoversTest {
    // As getString returns them: "\\n" in the XML is a line break.
    private val en = ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") }
    private val es = ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") }

    private val modeNoteKeys =
        mapOf(
            ConversationMode.OPEN to ("chat_mode_note_open_title" to "chat_mode_note_open_body"),
            ConversationMode.TUNNEL to ("chat_mode_note_tunnel_title" to "chat_mode_note_tunnel_body"),
        )

    private val quantumShieldKeys =
        listOf(
            "quantum_shield_active_long_press_off",
            "quantum_shield_turn_off_title",
            "quantum_shield_turn_off_body",
            "quantum_shield_turn_off_confirm",
            "quantum_shield_turn_off_cancel",
            "quantum_shield_invalid_code",
            "quantum_shield_on_toast",
        )

    @Test
    fun the_mode_note_after_a_switch_is_a_resource_whose_english_is_the_pinned_copy() {
        for ((mode, keys) in modeNoteKeys) {
            val (title, body) = assertNotNull(modeSecurityNoteCopy(mode))
            assertEquals(title, en[keys.first], "${keys.first} must be the pinned English title")
            assertEquals(body, en[keys.second], "${keys.second} must be the pinned English body")
            for (key in listOf(keys.first, keys.second)) {
                val spanish = assertNotNull(es[key], "values-es has no $key")
                assertNotEquals(en[key], spanish, "$key is English in the Spanish file")
            }
        }
        assertNotNull(es["chat_mode_note_got_it"], "values-es has no chat_mode_note_got_it")
        assertNotEquals(en["chat_mode_note_got_it"], es["chat_mode_note_got_it"])
        // Mode names stay Shielded / Tunnel / Open in Spanish (the reviewers' choice).
        assertTrue("Tunnel" in es.getValue("chat_mode_note_tunnel_title"), es.getValue("chat_mode_note_tunnel_title"))
        assertTrue("Open" in es.getValue("chat_mode_note_open_title"), es.getValue("chat_mode_note_open_title"))
    }

    @Test
    fun the_mode_note_dialog_shows_the_resources_not_the_english_literals() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ConversationModeUi.kt")
        val dialog = src.substringAfter("fun ModeSecurityNoteDialog(").substringBefore("\n}\n")
        assertFalse("\"Got it\"" in dialog, "the mode note still shows the English literal \"Got it\"")
        assertTrue("R.string.chat_mode_note_got_it" in dialog, "the mode note's button is not the resource")
        assertTrue("stringResource(" in dialog, "the mode note does not read its texts from resources")
        assertFalse("modeSecurityNoteCopy(mode)" in dialog, "the mode note still shows the English copy")
    }

    @Test
    fun the_quantum_shield_turn_off_dialog_banner_and_toasts_are_resources_with_spanish() {
        for (key in quantumShieldKeys) {
            val english = assertNotNull(en[key], "values has no $key")
            val spanish = assertNotNull(es[key], "values-es has no $key")
            assertNotEquals(english, spanish, "$key is English in the Spanish file")
        }
        assertEquals("Turn off Extra Security?", en["quantum_shield_turn_off_title"])
        assertEquals("Invalid QR code", en["quantum_shield_invalid_code"])

        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        for (literal in listOf(
            "\"Turn off Extra Security?\"",
            "\"Turn off\"",
            "\"long-press to turn off\"",
            "\"Adds a post-quantum key on top of E2E encryption for this chat.\"",
            "\"This turns off the extra post-quantum key",
        )) {
            assertFalse(literal in view, "ChatDetailView still shows the English literal $literal")
        }
        for (key in listOf("quantum_shield_turn_off_title", "quantum_shield_turn_off_confirm", "quantum_shield_setup_intro")) {
            assertTrue("R.string.$key" in view, "ChatDetailView does not read R.string.$key")
        }

        val chat = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        assertFalse("\"Invalid QR code\"" in chat, "AndroidChat still toasts the English \"Invalid QR code\"")
        assertFalse("\"Extra Security (Post-Quantum) is on!\"" in chat, "AndroidChat still toasts English")
        assertTrue("R.string.quantum_shield_invalid_code" in chat)
        assertTrue("R.string.quantum_shield_on_toast" in chat)
    }

    /**
     * QA2 Q2-09 (W-137, ZC-1600/ZC-731) retires the "< 0.001" row: the detail shows the exact fee, or "fee not known
     * yet" (PendingSendAndFeeRowsAreHonestTest). While a Spanish copy of the old row exists it still writes the comma.
     */
    @Test
    fun the_spanish_minimal_fee_row_writes_the_decimal_comma() {
        val spanish = es["transaction_detail_fee_minimal"] ?: return
        assertTrue("0,001" in spanish, "the Spanish fee row must write 0,001: $spanish")
        assertFalse("0.001" in spanish, "the Spanish fee row writes a point: $spanish")
    }
}
