package co.electriccoin.zcash.ui.screen.more

import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 batch B6 (FIX_LIST rows Q2-88, 93, 94, 96, 99, 100, 101, 102; owner W-137: fixed in 2.13.0). English and
 * Spanish in step; the Spanish lines are drafts (app-texts rule 9).
 *
 * - Q2-88 (ZC-1662): the More row "Security" opens a dialog titled "What ZCHAT Protects"; the row says what it opens,
 *   and the "Known limitations" body is not the dimmest, smallest text of the dialog (14.sp, textPrimary).
 * - Q2-93 (ZC-1600): Tor says "(e.g. NEAR coming soon)" although NEAR is there; and its Enable/Disable rows carry
 *   decorative check/X pictures.
 * - Q2-94 (ZC-1666): the recovery phrase has three names ("Backup Seed Phrase", "ZCHAT Recovery Phrase", ...).
 * - Q2-96 (ZC-1668): Spanish More rows in Title Case ("Configuración Avanzada", "Libreta de Direcciones") beside
 *   sentence-case rows, and the row "Sobre" opens a screen titled "Acerca de".
 * - Q2-99 (ZC-1670): the server reads "zec.rocks: 443" because choose_server_full_server_name holds a line break,
 *   which Android's resource compiler turns into a space.
 * - Q2-100 (ZC-1600): About's tagline "True Privacy. Zero Compromise."; the feature bodies are textTertiary.
 *   (The emoji icons are batch B7.)
 * - Q2-101 (ZC-1600): Address Book opens the system prompt "Authentication for ZCHAT" / "... access Address Book
 *   feature."; Reset ZCHAT's prompt names the "Delete Wallet feature".
 * - Q2-102 (ZC-1671): Change Identity promises switching "between identities (masks)".
 */
class SettingsAboutAndTorTextsSayWhatTheyMeanTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun Map<String, String>.text(
        key: String,
        dir: String
    ): String = assertNotNull(this[key], "$dir: no $key")

    private fun assertBoth(
        key: String,
        english: String,
        spanish: String
    ) {
        assertEquals(english, en.text(key, "values"), "values: $key")
        assertEquals(spanish, es.text(key, "values-es"), "values-es: $key")
    }

    private fun mainSource(path: String): String =
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/$path")

    /** What aapt2 makes of an unquoted string: xliff tags dropped, every whitespace run one space, ends trimmed. */
    private fun compiled(raw: String): String =
        raw
            .replace(Regex("""</?xliff:g[^>]*>"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // ---- Q2-88 ----

    @Test
    fun q2_88_the_security_row_names_what_it_opens() {
        assertBoth("more_row_security", "Security: what ZCHAT protects", "Seguridad: qué protege ZCHAT")
    }

    @Test
    fun q2_88_the_known_limitations_body_is_readable() {
        val src = mainSource("screen/more/MoreView.kt")
        val at = src.indexOf("R.string.security_dialog_limitations)")
        assertTrue(at >= 0, "MoreView does not read security_dialog_limitations")
        val block = src.substring(at, src.indexOf(')', at + "R.string.security_dialog_limitations)".length))
        assertTrue("fontSize = 14.sp" in block, "limitations body is not 14.sp: $block")
        assertTrue("textPrimary" in block, "limitations body is not textPrimary: $block")
        assertFalse("textSecondary" in block, "limitations body is still textSecondary: $block")
    }

    // ---- Q2-93 ----

    @Test
    fun q2_93_tor_does_not_say_near_is_coming_soon() {
        val english = compiled(en.text("tor_settings_subtitle", "values"))
        val spanish = compiled(es.text("tor_settings_subtitle", "values-es"))
        assertFalse("coming soon" in english.lowercase(), english)
        assertTrue("(e.g. NEAR)" in english, english)
        assertFalse("pronto" in spanish.lowercase(), spanish)
        assertTrue("(p. ej., NEAR)" in spanish, spanish)
    }

    @Test
    fun q2_93_tor_enable_and_disable_rows_carry_no_decorative_pictures() {
        val src = mainSource("screen/tor/settings/TorSettingsView.kt")
        for (picture in listOf("R.drawable.ic_opt_in", "R.drawable.ic_opt_out")) {
            assertFalse(picture in src, "TorSettingsView still shows $picture beside Enable/Disable")
        }
    }

    // ---- Q2-94 ----

    @Test
    fun q2_94_the_recovery_phrase_has_one_name() {
        assertBoth("more_row_backup_seed", "Back up recovery phrase", "Guardar la frase de recuperación")
        assertBoth("advanced_settings_recovery", "Recovery phrase", "Frase de recuperación")
    }

    // ---- Q2-96 ----

    @Test
    fun q2_96_spanish_more_rows_are_sentence_case_and_about_matches_its_screen() {
        assertEquals("Configuración avanzada", es.text("settings_advanced_settings", "values-es"))
        assertEquals("Libreta de direcciones", es.text("settings_address_book", "values-es"))
        assertEquals(es.text("about_title", "values-es"), es.text("settings_about_us", "values-es"))
        assertEquals("Acerca de", es.text("settings_about_us", "values-es"))
    }

    @Test
    fun q2_96_no_spanish_text_points_to_the_old_title_case_menu_name() {
        val stale = es.filterValues { "Configuración Avanzada" in it }.keys
        assertTrue(stale.isEmpty(), "Spanish texts still name «Configuración Avanzada»: $stale")
    }

    // ---- Q2-99 ----

    @Test
    fun q2_99_the_server_name_has_no_space_after_the_colon() {
        for ((dir, strings) in listOf("values" to en, "values-es" to es)) {
            for (key in listOf("choose_server_full_server_name", "choose_server_full_server_name_text_field")) {
                val shown = String.format(Locale.ROOT, compiled(strings.text(key, dir)), "zec.rocks", 443)
                assertEquals("zec.rocks:443", shown, "$dir: $key")
            }
        }
    }

    // ---- Q2-100 ----

    @Test
    fun q2_100_about_tagline() {
        assertBoth("about_subtitle", "Private messaging on Zcash.", "Mensajería privada en Zcash.")
    }

    @Test
    fun q2_100_about_feature_bodies_are_not_dimmed() {
        val src = mainSource("screen/about/view/AboutView.kt")
        val start = src.indexOf("private fun PrivacyFeatureItem(")
        assertTrue(start >= 0, "AboutView has no PrivacyFeatureItem")
        val body = src.substring(start, src.indexOf("\n}\n", start).let { if (it < 0) src.length else it })
        assertFalse("textTertiary" in body, "About feature bodies are still textTertiary")
        assertTrue("textPrimary" in body, "About feature bodies are not textPrimary")
    }

    // ---- Q2-101 ----

    @Test
    fun q2_101_the_address_book_prompt_says_what_it_unlocks() {
        assertBoth(
            "authentication_address_book_title",
            "Unlock Address Book",
            "Desbloquear la libreta de direcciones",
        )
        assertBoth(
            "authentication_address_book_subtitle",
            "To open the Address Book, use your phone's unlock method.",
            "Para abrir la libreta de direcciones, usa el método de desbloqueo de tu teléfono.",
        )
        // Review follow-up (W-137 ZC-1600): the prompt describes the gate, not a storage property. The chat list
        // shows the same contact names without any prompt, so no text may say the names are kept behind the lock.
        val englishSubtitle = en.text("authentication_address_book_subtitle", "values").lowercase()
        val spanishSubtitle = es.text("authentication_address_book_subtitle", "values-es").lowercase()
        val subtitles = "$englishSubtitle $spanishSubtitle"
        assertFalse("kept" in subtitles || "behind" in subtitles, subtitles)
        assertFalse("nombres" in subtitles || "contactos" in subtitles, subtitles)
        val src = mainSource("common/usecase/NavigateToAddressBookUseCase.kt")
        assertTrue("R.string.authentication_address_book_title" in src, "the Address Book prompt has no own title")
        assertTrue("R.string.authentication_address_book_subtitle" in src, "the Address Book prompt has no own subtitle")
        assertFalse("authentication_use_case_adress_book" in src, "the Address Book prompt still says \"feature\"")
        // The prompt's title comes from the request, so the system prompt can say "Unlock Address Book".
        val fields = BiometricRequest::class.java.declaredFields.map { it.name }
        assertTrue("title" in fields, "BiometricRequest carries no title: $fields")
    }

    @Test
    fun q2_101_the_reset_prompt_names_reset_zchat() {
        assertBoth("authentication_use_case_delete_wallet", "Reset ZCHAT", "Restablecer ZCHAT")
        // The same words as the Advanced Settings button that opens it.
        assertEquals(en.text("advanced_settings_delete_button", "values"), en.text("authentication_use_case_delete_wallet", "values"))
        assertEquals(es.text("advanced_settings_delete_button", "values-es"), es.text("authentication_use_case_delete_wallet", "values-es"))
    }

    // ---- Q2-102 ----

    @Test
    fun q2_102_change_identity_speaks_of_addresses_not_masks() {
        val english = en.text("identity_diversified_desc", "values")
        val spanish = es.text("identity_diversified_desc", "values-es")
        // Review follow-up (W-137 ZC-1671): there is no screen to switch between addresses, and a wallet with a saved
        // chat identity refuses this mode, so the text promises neither a switch nor a later time.
        assertEquals(
            "Generate new address from same seed. Your ZEC balance is preserved.",
            english,
        )
        assertFalse("switch" in english.lowercase() || "later" in english.lowercase(), english)
        assertFalse("mask" in english.lowercase() || "identities" in english.lowercase(), english)
        assertEquals(
            "Genera una dirección nueva a partir de la misma frase de recuperación. Tu saldo en ZEC se conserva.",
            spanish,
        )
        assertFalse("cambiar" in spanish.lowercase() || "después" in spanish.lowercase(), spanish)
        assertFalse("máscara" in spanish.lowercase() || "identidades" in spanish.lowercase(), spanish)
    }

    // ---- Q2-96 review follow-up ----

    @Test
    fun q2_96_the_spanish_address_book_screen_uses_the_sentence_case_of_its_row() {
        // The More/Settings row is "Libreta de direcciones"; the screen it opens must not be Title Case.
        assertEquals("Libreta de direcciones", es.text("settings_address_book", "values-es"))
        assertEquals("Libreta de direcciones", es.text("address_book_title", "values-es"))
        assertEquals(
            "Contactos de la libreta de direcciones",
            es.text("address_book_multiple_wallets_contacts_title", "values-es"),
        )
    }
}
