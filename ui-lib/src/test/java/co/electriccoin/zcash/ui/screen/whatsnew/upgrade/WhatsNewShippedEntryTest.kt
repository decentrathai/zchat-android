package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import co.electriccoin.zcash.build.releaseNotesEn
import co.electriccoin.zcash.build.releaseNotesEs
import co.electriccoin.zcash.ui.common.model.Changelog
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bridge 2 (FINAL_PLAN 2.2, COPY_DECK WN1-WN7): What's New shows the real `2.13.0 (21300)` entry of
 * `docs/whatsNew/WHATS_NEW_EN.md` / `WHATS_NEW_ES.md`, in English and Spanish.
 *
 * The notes reach the app through build-info-lib, which embeds them as JSON inside a Kotlin string, so this
 * test reads what the app actually decodes ([releaseNotesEn] / [releaseNotesEs] through [Changelog.new]).
 *
 * Gates: "and ZEC" in WN4.1 ships only after XV-05 passes on a phone; until then the entry says "Shielded
 * messages you send ...".
 */
class WhatsNewShippedEntryTest {
    private val en by lazy { Changelog.new(releaseNotesEn) }
    private val es by lazy { Changelog.new(releaseNotesEs) }

    private fun Changelog.text() =
        listOfNotNull(added, changed, fixed, removed).joinToString("\n") { it.content }

    @Test
    fun the_app_shows_the_2_13_0_entry_in_both_languages() {
        assertEquals("2.13.0", en.version)
        assertEquals("2.13.0", es.version)
        assertEquals(en.date, es.date)
    }

    @Test
    fun the_entry_is_the_first_after_unreleased_and_matches_the_build_version() {
        val props = File(repoRoot, "gradle.properties").readLines()
        assertTrue("ZCASH_VERSION_CODE=21300" in props)
        assertTrue("ZCASH_VERSION_NAME=2.13.0" in props)
        for (lang in listOf("EN", "ES")) {
            val lines = File(repoRoot, "docs/whatsNew/WHATS_NEW_$lang.md").readLines()
            val unreleased = lines.indexOf("## [Unreleased]")
            val next = lines.drop(unreleased + 1).first { it.startsWith("## [") }
            assertTrue(next.startsWith("## [2.13.0 (21300)] - "), "$lang: $next")
        }
    }

    @Test
    fun the_english_entry_has_the_copy_deck_sentences() {
        val text = en.text()
        for (sentence in listOf(
            // QA2 Q2-85 (ZC-1526): both 2026 network upgrades are named.
            "Why you had to update. Zcash upgraded its network twice this year (28 July and 6 November 2026).",
            "Older ZChat versions can't send on it or see what arrives on it.",
            // QA2 review follow-up (Q2-85, nit 7): with two upgrades named, "from the upgrade onward" is ambiguous.
            "ZChat is re-reading the blockchain to catch up with both upgrades.",
            "Until it finishes, your balance and some messages may look incomplete.",
            "Nothing sent to you on the blockchain is lost, except in chats with disappearing messages on.",
            // QA2 Q2-85 (ZC-097): no promise of progress on the wallet screen.
            "Progress shows at the top of your chat list.",
            "The first time you spend it, ZChat may ask you to move it once.",
            "That move shows the amount publicly, and ZChat explains this before you confirm.",
            "Shielded messages you send to a friend on 2.12.4 or older won't show until they update.",
            "Open messages go through relays, and the app explains what relays can see.",
            "The wallet Receive screen shows a new address each time.",
            "Your ZChat address (My Address) hasn't changed.",
            "That contact is now named “ZChat Team (old address, not monitored)”.",
            "Don't uninstall ZChat and don't create a new wallet.",
            "Uninstalling deletes your chats, and your ZEC comes back only with your recovery phrase.",
            "Use More → Send Us Feedback, or go to zsend.xyz/update.",
        )) {
            assertTrue(sentence in text, "EN is missing: $sentence\n---\n$text")
        }
    }

    @Test
    fun the_spanish_entry_has_the_copy_deck_sentences() {
        val text = es.text()
        for (sentence in listOf(
            "Por qué tenías que actualizar. Zcash actualizó su red dos veces este año (28 de julio y 6 de noviembre de 2026).",
            "Las versiones anteriores de ZChat no pueden enviar en ella ni ver lo que llega.",
            "Esta versión sí.",
            "ZChat está volviendo a leer la blockchain para ponerse al día con las dos actualizaciones.",
            "No se pierde nada de lo que te enviaron por la blockchain, salvo en los chats con mensajes temporales activados.",
            "El progreso se muestra arriba en tu lista de chats.",
            "Ese movimiento hace público el monto, y ZChat te lo explica antes de que confirmes.",
            "Los mensajes Shielded que envíes a un amigo con la versión 2.12.4 o anterior no le aparecerán hasta que actualice.",
            "Tu dirección de ZChat (Mi dirección) no ha cambiado.",
            "No desinstales ZChat ni crees una billetera nueva.",
            "Al desinstalar se borran tus chats, y tu ZEC solo se recupera con tu frase de recuperación.",
            "Usa Más → Envíanos tus Comentarios o visita zsend.xyz/update.",
        )) {
            assertTrue(sentence in text, "ES is missing: $sentence\n---\n$text")
        }
    }

    @Test
    fun the_entry_names_both_upgrades_and_no_wallet_screen_progress() {
        // QA2 Q2-85 (ZC-1526, ZC-097): the first bullet named only the 28 July upgrade, and the progress sentence
        // promised the wallet screen. The publish date is the release lane's, so it is not pinned here.
        val english = en.text()
        val spanish = es.text()
        assertFalse("On 28 July 2026 Zcash upgraded its network" in english, english)
        assertFalse("El 28 de julio de 2026, Zcash actualizó su red" in spanish, spanish)
        assertFalse("from the upgrade onward" in english, english)
        assertFalse("desde la actualización de la red" in spanish, spanish)
        assertFalse("wallet screen" in english, english)
        assertFalse("pantalla de la billetera" in spanish, spanish)
    }

    @Test
    fun the_xv_05_gate_keeps_and_zec_out_until_it_passes() {
        assertFalse("Shielded messages and ZEC you send" in en.text())
        assertFalse("Los mensajes Shielded y el ZEC" in es.text())
    }

    @Test
    fun the_entry_never_promises_chats_back_from_the_recovery_phrase() {
        for (text in listOf(en.text(), es.text())) {
            assertFalse(Regex("(?i)chats? (come|comes) back").containsMatchIn(text), text)
            assertFalse(Regex("(?i)recuperan? (tus )?chats").containsMatchIn(text), text)
        }
    }

    @Test
    fun the_entry_has_no_character_that_breaks_the_build_time_embedding() {
        // build-info-lib writes the notes as JSON into a Kotlin string: an ASCII double quote ends that string
        // and a dollar sign starts a template.
        for (lang in listOf("EN", "ES")) {
            val lines = File(repoRoot, "docs/whatsNew/WHATS_NEW_$lang.md").readLines()
            val start = lines.indexOfFirst { it.startsWith("## [2.13.0 (21300)]") }
            assertTrue(start >= 0, "$lang: no 2.13.0 (21300) entry")
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## [") }.let { if (it < 0) lines.size else start + 1 + it }
            val entry = lines.subList(start, end).joinToString("\n")
            assertFalse('"' in entry, "$lang: ASCII double quote in the entry")
            assertFalse('$' in entry, "$lang: dollar sign in the entry")
            assertFalse("**" in entry, "$lang: What's New shows plain text, so no Markdown bold")
        }
    }
}
