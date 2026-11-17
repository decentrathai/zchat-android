package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESTROY-ADV-C (register ZC-722; owner W-41, W-47, W-52, W-57; Destroy Advanced spec revision 2c, 9.1 to 9.3 and
 * 10.2) — the texts of the choice of what Destroy All erases, and of the Destroy flows when the choice keeps names,
 * say only what is true.
 *
 *  - every key of 9.1 (the three Destroy-options rows), 9.2 and 9.3 exists in English and Spanish, differs, has no
 *    placeholder; the English is the spec's, word for word;
 *  - every kept-branch sentence of 9.3 promises the list only as an attempt ("try" / «intent», or a condition); none
 *    says the contacts are kept;
 *  - the tick's two texts say what it costs: "copies", "erases everything else", "can see", "only a name", "it
 *    destroys everything" (ES «copia», «borra todo lo demás», «puede ver», «solo un nombre», «lo destruye todo»);
 *  - W-52 to the user: destroy_options_asked_now says "does not ask again" / «no vuelve a preguntarlo»;
 *  - B1 (W-57): "Destroy everything" is true: destroy_options_saved_all and destroy_options_needs_pin say "destroys
 *    everything" / «lo destruye todo», never "keeps nothing"; the bracket names what stays while ZCHAT is installed,
 *    and every thing DestroyWipe may leave is named there; that list holds no notification channel;
 *  - no text of 9.2 names the screen lock; no kept sentence or row text is joined from pieces in Kotlin.
 */
class DestroyKeepTextsSayOnlyWhatIsTrueTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `every key in English and Spanish, different, no placeholder, the English word for word`() {
        for ((key, text) in ENGLISH) {
            val english = en[key] ?: throw AssertionError("no English $key")
            val spanish = es[key] ?: throw AssertionError("no Spanish $key")
            assertEquals("English $key (spec 9.1 to 9.3)", text, english)
            assertNotEquals("$key is not translated", english, spanish)
            assertFalse("$key has a placeholder", '%' in english || '%' in spanish)
        }
    }

    @Test
    fun `the kept sentences promise the list only as an attempt, and never say the contacts are kept`() {
        for (key in KEPT) {
            val english = en[key] ?: throw AssertionError("no English $key")
            val spanish = es[key] ?: throw AssertionError("no Spanish $key")
            assertTrue("$key promises the list", Regex("(?i)\\btry\\b|\\btries\\b|\\btrying\\b|\\bif\\b").containsMatchIn(english))
            assertTrue("$key promises the list (ES)", Regex("(?i)intent|\\bsi\\b").containsMatchIn(spanish))
            for (wrong in listOf("contacts are kept", "keeps your contacts", "your contacts stay")) {
                assertFalse("$key says $wrong", wrong in english.lowercase())
            }
        }
    }

    @Test
    fun `the tick's two texts say what it costs`() {
        val english = en.getValue("destroy_keep_contacts_body") + " " + en.getValue("destroy_keep_contacts_after")
        val spanish = es.getValue("destroy_keep_contacts_body") + " " + es.getValue("destroy_keep_contacts_after")
        for (words in listOf("copies", "erases everything else", "can see", "only a name", "it destroys everything")) {
            assertTrue("the tick does not say \"$words\"", words in english)
        }
        for (words in listOf("copia", "borra todo lo demás", "puede ver", "solo un nombre", "lo destruye todo")) {
            assertTrue("the tick does not say «$words»", words in spanish)
        }
        assertTrue("does not ask again", "does not ask again" in en.getValue("destroy_options_asked_now"))
        assertTrue("no vuelve a preguntarlo", "no vuelve a preguntarlo" in es.getValue("destroy_options_asked_now"))
    }

    @Test
    fun `Destroy everything is true - destroys everything, never keeps nothing, and the bracket names what stays`() {
        for (key in listOf("destroy_options_saved_all", "destroy_options_needs_pin")) {
            assertTrue("$key says destroys everything", "destroys everything" in en.getValue(key))
            assertTrue("$key says lo destruye todo", "lo destruye todo" in es.getValue(key))
            assertFalse("$key says keeps nothing", "keeps nothing" in en.getValue(key))
            assertFalse("$key says no conserva nada", "no conserva nada" in es.getValue(key))
        }
        val bracket = en.getValue("destroy_options_everything_list")
        val bracketEs = es.getValue("destroy_options_everything_list")
        assertTrue("What stays while ZCHAT is installed" in bracket)
        assertTrue("Lo que queda mientras ZCHAT siga instalado" in bracketEs)
        for (words in listOf("notification categories", "job table")) assertTrue(words in bracket)
        for (words in listOf("categorías de notificaciones", "tabla de tareas")) assertTrue(words in bracketEs)

        val mayBeLeft = FakePhone().use { phone -> phone.wipeField("MAY_BE_LEFT") as List<*> }.map { it.toString() }
        assertFalse(
            "DestroyWipe may leave a notification channel or a job: T6b's amendment is missing (stop and report)",
            mayBeLeft.any { "channel" in it || "job" in it.lowercase() }
        )
        // Every thing the wipe may leave is named in the bracket's second half.
        val stays = bracket.substringAfter("What stays while ZCHAT is installed")
        for (entry in mayBeLeft) {
            val named =
                when {
                    entry == "lib" || entry == "code_cache" -> "the app itself"
                    entry.contains("androidx.work.workdb") -> "job table"
                    entry.endsWith("zchat_destroy_pending") -> "small record"
                    else -> throw AssertionError("DestroyWipe may leave $entry, which the bracket does not name")
                }
            assertTrue("the bracket does not name what stays for $entry", named in stays)
        }
    }

    @Test
    fun `no text of the options screen names the screen lock`() {
        for (key in ENGLISH.keys.filter { it.startsWith("destroy_options_") || it.startsWith("destroy_keep_") }) {
            for (lock in listOf("screen lock", "fingerprint", "pattern")) {
                assertFalse("$key names $lock", lock in en.getValue(key).lowercase())
            }
            assertFalse("$key names the screen lock (ES)", "bloqueo de pantalla" in es.getValue(key).lowercase())
        }
    }

    @Test
    fun `no kept sentence or row text is joined from pieces in Kotlin`() {
        val ui = File(ChatResourceText.repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui")
        val files = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in files) {
            val lines = SourceScan.stripComments(file.readText()).lines()
            for (line in lines) {
                if (ENGLISH.keys.none { "R.string.$it" in line }) continue
                assertFalse(
                    "${file.name} joins a Destroy text: ${line.trim()}",
                    Regex("\\)\\s*\\+|\\+\\s*(stringResource|getString|stringRes)\\(").containsMatchIn(line)
                )
            }
        }
    }

    private companion object {
        val KEPT =
            listOf(
                "chat_destroy_verify_warning_keep_contacts",
                "chat_destroy_final_kept_contacts",
                "chat_destroy_final_stays_installed_note",
                "destroy_will_delete_keep_contacts",
                "destroy_kept_contacts",
                "destroy_after_countdown_keep_contacts",
                "destroy_please_wait_keep_contacts",
                "destroy_pin_setup_what_body_keep_contacts",
            )

        /** Spec 9.1 (the Destroy-options rows), 9.2 and 9.3, English, word for word. */
        val ENGLISH =
            linkedMapOf(
                "advanced_settings_destroy_options" to
                    "Destroy All: what it erases",
                "advanced_settings_destroy_options_all" to
                    "Everything (default)",
                "advanced_settings_destroy_options_keep_contacts" to
                    "Everything except a list of your chat contacts' names",
                "destroy_options_title" to
                    "What Destroy All erases",
                "destroy_options_default_body" to
                    "Destroy All erases what ZCHAT keeps in its own storage on this phone (your wallet and its recovery phrase, your payment history, your messages, your contacts, AI chats and pictures, the files ZCHAT fetched for your chats and ZCHAT's own settings) and then asks your phone to uninstall ZCHAT. It does not erase pictures and files you saved from ZCHAT to this phone.",
                "destroy_options_advanced" to
                    "Advanced",
                "destroy_options_everything" to
                    "Destroy everything",
                "destroy_options_everything_list" to
                    "(wallet and recovery phrase, payment history, messages, contacts, AI chats and pictures, fetched files, ZCHAT's own settings, its notification categories and its background jobs. What stays while ZCHAT is installed: the app itself and your phone's own settings for it, with the phone's note that notification categories were deleted; an empty job table of Android's background-work system; and a small record that ZCHAT removes the next time it starts.)",
                "destroy_options_keep" to
                    "Keep some things",
                "destroy_keep_contacts_title" to
                    "Names of your chat contacts",
                "destroy_keep_contacts_body" to
                    "When you destroy, ZCHAT first erases the recovery phrase, then copies the names and addresses of your chat contacts into one list that stays on this phone, and then erases everything else, your contacts and nicknames themselves included. Copying takes up to two seconds. If the list is kept, ZCHAT is not uninstalled. Whoever opens ZCHAT on this phone afterwards can see who your contacts were.",
                "destroy_keep_contacts_after" to
                    "You find the list in Advanced Settings → Names you kept. A kept name is only a name: ZCHAT treats its address as if the name were not on the list, until you save it as a contact there. The wallet's Contacts list for payments is not kept. If ZCHAT cannot copy the list at that moment, it destroys everything.",
                "destroy_keep_note" to
                    "Everything you do not tick is destroyed. If ZCHAT cannot read this choice when you destroy, it destroys everything.",
                "destroy_keep_messages_hint" to
                    "To remove the wallet from this phone and keep your chats for the same recovery phrase, use Reset ZCHAT in Advanced Settings instead.",
                "destroy_options_existing_list" to
                    "A list of names you kept earlier is on this phone. With “Destroy everything” it is destroyed too.",
                "destroy_options_save" to
                    "Save",
                "destroy_options_saved_all" to
                    "Saved. Destroy All destroys everything.",
                "destroy_options_saved_keep_contacts" to
                    "Saved. Destroy All will try to keep a list of your chat contacts' names and erase the rest.",
                "destroy_options_key_failed" to
                    "ZCHAT could not prepare the phone-security key for the list. Nothing was changed; try again.",
                "destroy_options_auth_title" to
                    "Change what Destroy All erases",
                "destroy_options_asked_now" to
                    "You choose this here, before you need it. When you run Destroy All, ZCHAT does not ask again.",
                "destroy_options_needs_pin" to
                    "This choice is saved together with a Destroy PIN. Without a Destroy PIN, Destroy All destroys everything.",
                "destroy_options_set_pin" to
                    "Set a Destroy PIN",
                "chat_destroy_verify_warning_keep_contacts" to
                    "Warning: this erases what ZCHAT keeps in its own storage on this phone: messages, the wallet and your contacts. ZCHAT will try to keep one list of your chat contacts' names (your choice in Advanced Settings).",
                "chat_destroy_final_kept_contacts" to
                    "• Your contacts. ZCHAT will try to keep one list of their names and addresses (your choice in Advanced Settings)",
                "chat_destroy_final_stays_installed_note" to
                    "If the list is kept, ZCHAT is not uninstalled and closes when it has finished. If ZCHAT cannot copy the list, it destroys everything and asks your phone to uninstall it.",
                "destroy_will_delete_keep_contacts" to
                    "This erases what ZCHAT keeps in its own storage on this phone. ZCHAT will try to keep one list of your chat contacts' names (your choice in Advanced Settings). Erased:",
                "destroy_kept_contacts" to
                    "Your contacts and nicknames (one list of their names and addresses is kept if ZCHAT can copy it)",
                "destroy_after_countdown_keep_contacts" to
                    "After the countdown, ZCHAT erases what it keeps in its own storage on this phone and tries to keep one list of your chat contacts' names. If the list is kept, ZCHAT is not uninstalled.",
                "destroy_please_wait_keep_contacts" to
                    "ZCHAT is erasing what it keeps in its own storage on this phone and trying to keep the list of names.",
                "destroy_erases_earlier_list" to
                    "A list of names kept by an earlier Destroy All is on this phone. This Destroy All erases it too.",
                "destroy_pin_setup_what_body_keep_contacts" to
                    "Messages, the wallet and your contacts are erased from ZCHAT's own storage on this phone. ZCHAT will try to keep one list of your chat contacts' names (your choice in Advanced Settings).",
            )
    }
}
