package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-545 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3A.5 row 12, §3A.7; owner decision W-32; skill app-texts) —
 * the key-changed banner's × is labelled "Accept the new key" and the Verify dialog says "re-compare this new number",
 * but the number is the OLD pair's and the × adopts nothing.
 *
 * Wanted: while a refused key is remembered, Verify and the × open ONE dialog that shows THAT key's safety number,
 * when it first arrived, how many other keys arrived later, what accepting costs in this chat's mode, and the buttons
 * Accept this key, Not their key and Close; the dialog keeps the snapshot of both keys it opened with and Accept
 * passes both; with no old key left (an interrupted accept) Not their key is hidden and the interrupted text shows;
 * the old dialog's key-changed line is a resource that says the number is still the old key's. English and Spanish
 * for every new text, with the same numbered placeholders; the ×'s label and the refusal notice's pinned words stay.
 */
class KeyChangedBannerAndAcceptDialogSayWhatAcceptDoesTest {
    private val en: Map<String, String> by lazy { ChatResourceText.strings("values") }
    private val es: Map<String, String> by lazy { ChatResourceText.strings("values-es") }
    private val chatDetailRaw: String by lazy { ChatResourceText.source("$CHAT/view/ChatDetailView.kt") }
    private val chatDetail: String by lazy { SourceScan.stripComments(chatDetailRaw) }
    private val androidChat: String by lazy { SourceScan.stripComments(ChatResourceText.source("$CHAT/AndroidChat.kt")) }

    @Test
    fun every_new_text_is_in_english_and_spanish_with_the_same_numbered_placeholders() {
        for (key in NEW_KEYS) {
            val e = en[key]
            val s = es[key]
            assertNotNull(e, "no English $key")
            assertNotNull(s, "no Spanish $key")
            assertEquals(placeholders(e), placeholders(s), "$key: the Spanish placeholders differ from the English")
            assertFalse(Regex("""%[sd]""").containsMatchIn(e), "$key: an unnumbered placeholder in English")
            assertFalse("Vault" in e || "Vault" in s, "$key: a text for a 2.13 user names the modes as the picker shows them")
        }
        assertEquals(listOf("%1\$s"), placeholders(en.getValue("chat_key_changed_banner_body_pending")))
        assertEquals(listOf("%1\$s"), placeholders(en.getValue("chat_key_changed_offer_received")))
        assertEquals(listOf("%1\$d"), placeholders(en.getValue("chat_key_changed_other_keys")))
    }

    @Test
    fun the_buttons_and_the_unchanged_label_read_as_decided() {
        assertEquals("Accept this key", en["chat_key_changed_accept_this_key"])
        assertEquals("Aceptar esta clave", es["chat_key_changed_accept_this_key"])
        assertEquals("Not their key", en["chat_key_changed_dismiss_key"])
        assertEquals("No es su clave", es["chat_key_changed_dismiss_key"])
        assertEquals("Accept this contact's new key?", en["chat_key_changed_review_title"])
        assertEquals("Accept the new key", en["chat_key_changed_accept_key"], "the ×'s label must stay")
        assertEquals("Aceptar la nueva clave", es["chat_key_changed_accept_key"], "the ×'s Spanish label must stay")
    }

    @Test
    fun the_texts_say_only_what_the_accept_does() {
        val body = en.getValue("chat_key_changed_review_body")
        assertTrue("matches exactly" in body, "the dialog must say to accept only when the number matches: $body")
        assertTrue("unverified" in body, "the dialog must say the chat shows as unverified afterwards: $body")
        assertTrue("Close changes nothing" in body, body)
        val shielded = en.getValue("chat_key_changed_review_cost_shielded")
        assertTrue("one on-chain message" in shielded && "asks before paying" in shielded, shielded)
        val paid = en.getValue("chat_key_changed_review_cost_paid")
        assertTrue("shows the price" in paid && "asks before paying" in paid, paid)
        val question = en.getValue("paid_action_key_change_accept")
        assertTrue("key exchange" in question && "last hour" in question, question)
        val older = en.getValue("paid_action_key_change_accept_older")
        assertTrue(older.startsWith(question) && "2.12.4 or older" in older && "Nothing is lost" in older, older)
        assertTrue("2.12.4 o anterior" in es.getValue("chat_key_changed_review_older"), es.getValue("chat_key_changed_review_older"))
        val oldNumber = en.getValue("chat_key_changed_safety_dialog_old_number")
        assertTrue("OLD key" in oldNumber, "the old dialog must say whose number it shows: $oldNumber")
        val refused = en.getValue("chat_key_changed_send_refused")
        assertTrue("NEW key" in refused, "the refusal notice must say Verify shows the new key's number: $refused")
        assertTrue("one on-chain message" in refused && "(up to 2 on-chain messages)" in refused, refused)
        val refusedEs = es.getValue("chat_key_changed_send_refused")
        assertTrue("clave NUEVA" in refusedEs && "(hasta 2 mensajes en la cadena)" in refusedEs, refusedEs)
    }

    @Test
    fun the_notices_the_view_model_shows_are_these_texts() {
        assertEquals(en["chat_key_changed_changed_again"], constant("KEY_CHANGE_CHANGED_AGAIN"))
        assertEquals(en["chat_key_accept_not_saved"], constant("KEY_ACCEPT_NOT_SAVED"))
        assertEquals(en["chat_key_changed_interrupted"], constant("KEY_ACCEPT_INTERRUPTED"))
        val again = en.getValue("chat_key_changed_changed_again")
        assertTrue("nothing was changed" in again && "our key changed" in again, again)
    }

    @Test
    fun verify_and_the_x_open_the_one_dialog_while_a_key_is_remembered() {
        val start = chatDetailRaw.indexOf("Key-Changed Warning Banner")
        assertTrue(start >= 0, "fixture: the key-changed banner's comment in ChatDetailView")
        val banner = SourceScan.stripComments(SourceScan.braceBlock(chatDetailRaw.substring(start), "if (isKeyChanged)"))
        assertTrue("R.string.chat_key_changed_banner_body_pending" in banner, "the banner does not say a new key waits:\n$banner")
        assertTrue("refusedKeyOffer" in banner, "the banner does not follow the remembered key:\n$banner")
        assertEquals(
            2,
            Regex("""showKeyReviewDialog = true""").findAll(banner).count(),
            "Verify and the × must both open the one dialog:\n$banner",
        )
        assertFalse("onAcceptRefusedKey" in banner, "the banner must not accept a key directly:\n$banner")
        assertTrue("onDismissKeyChanged" in banner, "with no remembered key the × is the accept of ZC-535:\n$banner")
        assertTrue("R.string.chat_key_changed_accept_key" in banner && "keyChangeRefused" in banner, banner)
        assertTrue("onAcknowledgeKeyChangeRefusal" in banner, banner)
    }

    @Test
    fun the_dialog_shows_the_remembered_keys_number_and_passes_both_keys() {
        assertTrue("if (showKeyReviewDialog" in chatDetail, "the review dialog does not exist")
        val dialog = SourceScan.braceBlock(chatDetail, "if (showKeyReviewDialog")
        for (key in DIALOG_KEYS) {
            assertTrue("R.string.$key" in dialog, "the dialog does not use $key")
        }
        assertTrue("safetyNumber.chunked(4)" in dialog, "the dialog does not show the number in groups of four")
        assertTrue("keyReviewSnapshot" in dialog, "the dialog does not keep the snapshot it opened with")
        assertTrue(
            Regex("""onAcceptRefusedKey\(\s*\w+\.refusedKey,\s*\w+\.ourKey\s*\)""").containsMatchIn(dialog),
            "Accept must pass both keys of the snapshot:\n$dialog",
        )
        assertTrue(
            Regex("""onDismissRefusedKey\(\s*\w+\.refusedKey\s*\)""").containsMatchIn(dialog),
            "Not their key is not wired",
        )
        assertTrue(Regex("""laterOffers\s*>\s*0""").containsMatchIn(dialog), "the later-keys count must show only when above 0")
        assertTrue("oldKeyStored" in dialog, "the dialog does not tell an interrupted accept")
        assertTrue("contactIsOlder" in dialog, "the dialog does not name an older contact")
        assertTrue("stringResByDateTime(" in dialog, "the first-received time must go through the app's formatter")
    }

    @Test
    fun the_old_dialog_no_longer_calls_the_old_number_new() {
        assertFalse("re-compare this new number" in chatDetail, "the old dialog still calls the old pair's number new")
        assertTrue(
            "R.string.chat_key_changed_safety_dialog_old_number" in chatDetail,
            "the old dialog's key-changed line is not a resource",
        )
    }

    @Test
    fun the_screen_wires_the_dialog_to_the_view_model() {
        assertTrue("viewModel.refusedKeyOffer(peerAddress)" in androidChat, "AndroidChat does not read the remembered key")
        assertTrue(
            Regex("""viewModel\.acceptRefusedKey\(peerAddress,\s*refusedKey,\s*ourKey\)""").containsMatchIn(androidChat),
            "AndroidChat does not pass both keys to the accept",
        )
        assertTrue(
            Regex("""viewModel\.dismissRefusedKeyOffer\(peerAddress,\s*refusedKey\)""").containsMatchIn(androidChat),
            "AndroidChat does not wire Not their key",
        )
    }

    private fun constant(name: String): String? =
        ChatSendCopy::class.java.declaredFields
            .firstOrNull { it.name == name }
            ?.apply { isAccessible = true }
            ?.get(null) as String?

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private companion object {
        const val CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
        val DIALOG_KEYS =
            listOf(
                "chat_key_changed_review_title",
                "chat_key_changed_review_body",
                "chat_key_changed_offer_received",
                "chat_key_changed_other_keys",
                "chat_key_changed_interrupted",
                "chat_key_changed_accept_this_key",
                "chat_key_changed_dismiss_key",
                "chat_key_changed_review_cost_shielded",
                "chat_key_changed_review_cost_paid",
                "chat_key_changed_review_older",
            )
        val NEW_KEYS =
            DIALOG_KEYS +
                listOf(
                    "chat_key_changed_banner_body_pending",
                    "chat_key_changed_safety_dialog_old_number",
                    "chat_key_changed_changed_again",
                    "chat_key_accept_not_saved",
                    "paid_action_key_change_accept",
                    "paid_action_key_change_accept_older",
                )
    }
}
