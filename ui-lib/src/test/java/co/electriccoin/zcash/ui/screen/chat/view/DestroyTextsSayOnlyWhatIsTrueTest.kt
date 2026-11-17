package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-692-T (register ZC-692, the texts part; ZC-725, ZC-720, the UK10 line of ZC-187, KI-29's wording).
 *
 * The Destroy texts promise what no app can do, in English and Spanish: "This will permanently delete", "Your
 * wallet: private keys and all funds", "your data is securely erased", "Make sure you have your backup ready",
 * "DESTROY EVERYTHING". Destroy All erases what ZCHAT keeps in its own storage on this phone. It does not erase the
 * pictures, voice notes and files the user saved to the phone, it cannot erase the blockchain, the contacts' phones,
 * the relays and file hosts, the records of the services ZCHAT talked to, the AI account or an older phone backup,
 * and whoever has the recovery phrase can restore the wallet and may be able to restore messages. None of that is
 * said anywhere. The screen behind "Emergency Data Wipe PIN" makes the same promises in English on every phone
 * (ZC-725), and the "Data Destroyed" screen can never be drawn, because the wipe ends the app's process (ZC-720).
 *
 * Wanted: one phrase for what is erased ("what ZCHAT keeps in its own storage on this phone"), the saved files named
 * first among what stays, the recovery phrase named as an ability, what no app can erase listed before the last tap,
 * the Destroy PIN screen's five promises replaced, and the screen that cannot appear removed.
 *
 * Texts only: nothing about what the wipe does, or when, changes. Reads the views' source and the string resources, as
 * the other copy tests do (HonestCopySecondPassTest, SpanishLeftoversAndPlaceholdersTest).
 */
class DestroyTextsSayOnlyWhatIsTrueTest {
    private val en: Map<String, String> by lazy { ChatResourceText.strings("values") }
    private val es: Map<String, String> by lazy { ChatResourceText.strings("values-es") }

    private val screens = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen"
    private val chatListView: String by lazy { ChatResourceText.source("$screens/chat/view/ChatListView.kt") }
    private val destroyView: String by lazy {
        ChatResourceText.source("$screens/enhanceddestroy/view/EnhancedDestroyView.kt")
    }
    private val destroyVm: String by lazy { ChatResourceText.source("$screens/enhanceddestroy/EnhancedDestroyVM.kt") }
    private val pinSetupView: String by lazy {
        ChatResourceText.source("$screens/onboarding/view/DestroyPinSetupView.kt")
    }
    private val advancedSettingsVm: String by lazy {
        ChatResourceText.source("$screens/advancedsettings/AdvancedSettingsVM.kt")
    }

    private fun isDestroyKey(key: String) = key.startsWith("destroy_") || key.startsWith("chat_destroy_")

    /** Every Destroy string of both languages; a Spanish one is named "es:key". */
    private val destroyStrings: Map<String, String> by lazy {
        en.filterKeys(::isDestroyKey) + es.filterKeys(::isDestroyKey).mapKeys { "es:${it.key}" }
    }

    private fun region(
        text: String,
        from: String,
        to: String,
    ): String {
        val start = text.indexOf(from)
        val end = text.indexOf(to, start + 1)
        assertTrue(start >= 0 && end > start, "fixture: \"$from\" .. \"$to\"")
        return text.substring(start, end)
    }

    private val finalDialog: String by lazy {
        region(chatListView, "// Final Destroy Confirmation Dialog", "// Group Chat Coming Soon Dialog")
    }
    private val confirmIntentStep: String by lazy {
        region(destroyView, "private fun ConfirmIntentStep(", "private fun DestructionItem(")
    }

    private fun english(key: String): String = en[key] ?: ""

    private fun spanish(key: String): String = es[key] ?: ""

    /** Where each of [keys] is first used in [source], in the order given; -1 for a key that is not used. */
    private fun uses(
        source: String,
        keys: List<String>,
    ): List<Int> = keys.map { Regex("""R\.string\.$it\b""").find(source)?.range?.first ?: -1 }

    private fun assertShownInOrder(
        source: String,
        keys: List<String>,
        where: String,
    ) {
        val at = uses(source, keys)
        val missing = keys.filterIndexed { i, _ -> at[i] < 0 }
        assertTrue(missing.isEmpty(), "$where does not show $missing")
        assertEquals(at.sorted(), at, "$where shows $keys in another order")
    }

    // ---- what the texts must no longer say ----

    @Test
    fun the_old_promises_are_gone() {
        val promises =
            listOf(
                "permanently",
                "securely erased",
                "all funds",
                "backup ready",
                "Only your recovery phrase",
                "all data",
                "all app data",
                "de forma permanente",
                "de forma segura",
                "todos los fondos",
                "todos los datos",
                "copia de seguridad!",
                "Solo tu frase",
            )
        val stillPromising =
            destroyStrings
                .filterValues { text -> promises.any { text.contains(it, ignoreCase = true) } }
                .keys
                .sorted()
        assertTrue(stillPromising.isEmpty(), "Destroy texts still promise what no app can do: $stillPromising")
    }

    @Test
    fun the_confirm_button_names_the_feature() {
        assertTrue("DESTROY ALL" in english("chat_destroy_final_confirm"), "EN: ${en["chat_destroy_final_confirm"]}")
        assertTrue("DESTRUIR TODO" in spanish("chat_destroy_final_confirm"), "ES: ${es["chat_destroy_final_confirm"]}")
        // Files the user saved to the phone stay, so no Destroy text says "EVERYTHING".
        val everything =
            destroyStrings.filterValues { "EVERYTHING" in it || "BORRARLO TODO" in it }.keys.sorted()
        assertTrue(everything.isEmpty(), "Destroy texts still say EVERYTHING: $everything")
    }

    // ---- what is erased ----

    @Test
    fun every_sentence_that_says_what_is_erased_names_zchats_own_storage() {
        val sentences =
            listOf(
                "destroy_will_delete",
                "destroy_after_countdown",
                "destroy_please_wait",
                "chat_destroy_verify_warning",
                "chat_destroy_final_list_intro",
                "destroy_pin_setup_intro",
                "destroy_pin_setup_what_body",
            )
        val wrong =
            sentences.filter { key ->
                val spanishText = spanish(key)
                "own storage" !in english(key) ||
                    !("propio almacenamiento" in spanishText || "almacenamiento propio" in spanishText)
            }
        assertTrue(wrong.isEmpty(), "these say what is erased without \"own storage\" / «propio almacenamiento»: $wrong")
    }

    // ---- what stays ----

    @Test
    fun saved_files_are_named_as_left_on_this_phone() {
        for (key in listOf("destroy_not_erased_saved_files", "chat_destroy_final_stays_note")) {
            val english = english(key)
            val spanish = spanish(key)
            assertTrue("saved" in english && "this phone" in english, "$key (EN) must say saved … this phone: $english")
            assertTrue(
                "guardaste" in spanish && "este teléfono" in spanish,
                "$key (ES) must say guardaste … este teléfono: $spanish",
            )
        }
        // The saved files are ON this phone: they come before the copies "outside this phone", never among them.
        val note = english("chat_destroy_final_stays_note")
        val saved = note.indexOf("saved")
        val outside = note.indexOf("outside this phone")
        assertTrue(saved in 0 until outside, "the stays-note must name the saved files before \"outside this phone\": $note")
        assertTrue(
            Regex("""outside this phone[^.]*\b(pictures|files you saved)""").find(note) == null,
            "the stays-note puts the saved files under \"outside this phone\": $note",
        )
        val notaEs = spanish("chat_destroy_final_stays_note")
        assertTrue(
            notaEs.indexOf("guardaste") in 0 until notaEs.indexOf("fuera de este teléfono"),
            "the Spanish stays-note must name the saved files before «fuera de este teléfono»: $notaEs",
        )
        assertTrue("full list" !in note.lowercase(), "the stays-note promises a full list: $note")
        assertTrue("lista completa" !in notaEs.lowercase(), "the Spanish stays-note promises a full list: $notaEs")
    }

    @Test
    fun the_recovery_phrase_is_named_as_an_ability() {
        for (key in listOf("destroy_phrase_note", "chat_destroy_final_wallet_note")) {
            val english = english(key)
            val spanish = spanish(key)
            assertTrue(
                "recovery phrase" in english && "may be able" in english && "messages" in english,
                "$key (EN) must say the recovery phrase may be able to restore messages: $english",
            )
            assertTrue(
                "frase de recuperación" in spanish && "quizá" in spanish && "mensajes" in spanish,
                "$key (ES) must say «quizá» for the messages: $spanish",
            )
        }
    }

    private val staysLines =
        listOf(
            "destroy_stays_blockchain",
            "destroy_stays_contacts_phones",
            "destroy_stays_servers",
            "destroy_stays_services",
            "destroy_stays_ai",
            "destroy_stays_backup",
            "destroy_stays_outside_app",
            "destroy_stays_android",
        )

    @Test
    fun what_no_app_can_erase_is_listed() {
        for (key in listOf("destroy_stays_title") + staysLines) {
            val english = en[key]
            val spanish = es[key]
            assertTrue(!english.isNullOrBlank() && !spanish.isNullOrBlank(), "$key must exist in English and Spanish")
            assertTrue(english != spanish, "$key is not translated")
        }
        val named =
            listOf(
                Triple("destroy_stays_blockchain", "blockchain", "cadena"),
                Triple("destroy_stays_blockchain", "public", "públicos"),
                Triple("destroy_stays_contacts_phones", "contacts' phones", "teléfonos de tus contactos"),
                Triple("destroy_stays_servers", "Relay", "relays"),
                Triple("destroy_stays_servers", "file hosts", "servidores de archivos"),
                Triple("destroy_stays_services", "sync server", "servidor de sincronización"),
                Triple("destroy_stays_services", "call servers", "servidores de llamadas"),
                Triple("destroy_stays_services", "Swap service", "servicio de Swap"),
                Triple("destroy_stays_services", "AI provider", "proveedor de IA"),
                Triple("destroy_stays_ai", "AI account", "cuenta de IA"),
                Triple("destroy_stays_backup", "backup", "copia de seguridad"),
                Triple("destroy_stays_android", "Android", "Android"),
            )
        val missing =
            named.filter { (key, english, spanish) -> english !in english(key) || spanish !in spanish(key) }
        assertTrue(missing.isEmpty(), "the card of what stays does not name (key, EN, ES): $missing")
    }

    @Test
    fun spanish_says_an_ability_where_english_does() {
        val asAFact = staysLines.filter { "los leen" in spanish(it) || "los abren" in spanish(it) }
        assertTrue(asAFact.isEmpty(), "Spanish states as a fact what English gives as an ability: $asAFact")
        assertTrue(staysLines.all { it in es }, "the card of what stays has no Spanish yet")
    }

    @Test
    fun the_backup_line_names_no_version() {
        assertTrue(
            english("destroy_stays_backup").startsWith("Older ZCHAT versions"),
            "EN: ${en["destroy_stays_backup"]}",
        )
        assertTrue(
            spanish("destroy_stays_backup").startsWith("Las versiones anteriores de ZCHAT"),
            "ES: ${es["destroy_stays_backup"]}",
        )
        val versioned = destroyStrings.filterValues { "2.12.4" in it }.keys.sorted()
        assertTrue(versioned.isEmpty(), "an earlier 2.13 test build made such copies too; no version is named: $versioned")
    }

    @Test
    fun the_uninstall_help_stays() {
        assertTrue("Settings → Apps" in english("chat_destroy_final_uninstall_note"))
        assertTrue("Ajustes → Aplicaciones" in spanish("chat_destroy_final_uninstall_note"))
    }

    // ---- they are shown ----

    @Test
    fun the_chat_lists_final_dialog_says_what_stays_and_scrolls() {
        assertShownInOrder(
            finalDialog,
            listOf(
                "chat_destroy_final_item_settings",
                "chat_destroy_final_stays_note",
                "chat_destroy_final_uninstall_note",
            ),
            "the final Destroy dialog",
        )
        assertTrue(
            "verticalScroll(" in finalDialog,
            "the final Destroy dialog does not scroll: on a small screen its last lines and buttons are cut",
        )
    }

    @Test
    fun emergency_destroys_first_step_says_what_is_erased_and_what_stays() {
        assertShownInOrder(
            confirmIntentStep,
            listOf(
                "destroy_item_wallet",
                "destroy_item_history",
                "destroy_item_ai_files",
                "destroy_item_settings",
                "destroy_not_erased_saved_files",
                "destroy_phrase_note",
                "destroy_stays_title",
            ) + staysLines,
            "Emergency Destroy's first step",
        )
    }

    // ---- the Destroy PIN screen (ZC-725) ----

    @Test
    fun the_destroy_pin_screen_stops_promising() {
        val promises = listOf("instantly wipe", "permanently deleted", "Unrecoverable", "backup ready", "Instant Wipe")
        val left = promises.filter { pinSetupView.contains(it, ignoreCase = true) }
        assertTrue(left.isEmpty(), "DestroyPinSetupView.kt still says $left")
        assertShownInOrder(
            pinSetupView,
            listOf(
                "destroy_pin_setup_intro",
                "destroy_pin_setup_what_title",
                "destroy_pin_setup_what_body",
                "destroy_pin_setup_undo_title",
                "destroy_pin_setup_undo_body",
                "destroy_not_erased_saved_files",
            ),
            "the Destroy PIN screen",
        )
    }

    // ---- no screen that cannot appear (ZC-720) ----

    @Test
    fun there_is_no_done_screen() {
        assertEquals(
            emptyList(),
            DestroyStep.entries.map { it.name }.filter { it == "COMPLETE" },
            "the wipe ends the app's process, so a step after it can never be drawn",
        )
        val doneStrings = (en.keys + es.keys).filter { it.startsWith("destroy_done_") }.distinct().sorted()
        assertTrue(doneStrings.isEmpty(), "texts of a screen that can never appear: $doneStrings")
        assertTrue("destroy_confirmed_cd" !in en && "destroy_confirmed_cd" !in es, "destroy_confirmed_cd is left over")

        val onFinalDestroy = region(destroyVm, "private fun onFinalDestroy()", "override fun onCleared()")
        val afterTheWipe = onFinalDestroy.substringAfter("destroyManager.destroyAll(", "")
        assertTrue(afterTheWipe.isNotEmpty(), "fixture: onFinalDestroy calls destroyManager.destroyAll")
        assertTrue(
            "currentStep" !in afterTheWipe,
            "EnhancedDestroyVM sets a step after destroyAll, which never comes back to draw it",
        )
    }

    // ---- the two Advanced Settings rows ----

    @Test
    fun the_two_advanced_settings_rows_are_resources() {
        for (literal in listOf("\"Emergency Destroy\"", "\"Emergency Data Wipe PIN\"")) {
            assertTrue(literal !in advancedSettingsVm, "AdvancedSettingsVM.kt still holds the literal $literal")
        }
        val rows =
            mapOf(
                "advanced_settings_emergency_destroy" to "Emergency Destroy",
                "advanced_settings_destroy_pin" to "Emergency Data Wipe PIN",
            )
        for ((key, oldEnglish) in rows) {
            assertEquals(oldEnglish, en[key], "$key keeps the old English")
            assertTrue(!es[key].isNullOrBlank() && es[key] != oldEnglish, "$key has no Spanish")
            assertTrue("R.string.$key" in advancedSettingsVm, "AdvancedSettingsVM.kt does not use $key")
        }
        // The dialog's pointer names the row as the row reads, in each language.
        assertTrue("Advanced Settings → Emergency Destroy" in english("chat_destroy_final_stays_note"))
        assertTrue(
            "Configuración avanzada → ${es["advanced_settings_emergency_destroy"]}" in
                spanish("chat_destroy_final_stays_note"),
            "the Spanish stays-note points at a row the screen does not show under that name",
        )
    }

    // ---- parity ----

    @Test
    fun every_new_text_is_in_both_languages_without_a_placeholder() {
        val newKeys =
            listOf(
                "destroy_item_history",
                "destroy_item_ai_files",
                "destroy_not_erased_saved_files",
                "destroy_phrase_note",
                "destroy_stays_title",
                "chat_destroy_final_stays_note",
                "destroy_pin_setup_intro",
                "destroy_pin_setup_what_title",
                "destroy_pin_setup_what_body",
                "destroy_pin_setup_undo_title",
                "destroy_pin_setup_undo_body",
                "advanced_settings_emergency_destroy",
                "advanced_settings_destroy_pin",
            ) + staysLines
        val missing = newKeys.filter { en[it].isNullOrBlank() || es[it].isNullOrBlank() }
        assertTrue(missing.isEmpty(), "missing in English or Spanish: $missing")
        val same = newKeys.filter { en[it] == es[it] }
        assertTrue(same.isEmpty(), "not translated: $same")
        val withPlaceholder = newKeys.filter { "%" in english(it) || "%" in spanish(it) }
        assertTrue(withPlaceholder.isEmpty(), "these texts take no value: $withPlaceholder")
    }
}
