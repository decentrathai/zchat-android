package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-718 (register ZC-718, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 5.1 and 6.1, amendment items 3 to 5):
 * what the Reset screen and Change Identity's Full Wallet Reset say about the AI chats, the AI pictures and the key to
 * AI credit, in English and Spanish. With "keep" ticked the AI data is kept with the chats; unticked, the AI chats and
 * pictures are deleted and a key to AI credit is KEPT, for this recovery phrase only (rule K-0, WAIVERS.md W-52), with
 * no word of support. The Destroy card says that Destroy All deletes such a kept key.
 *
 * Not here (batch ZC-090-R): the sentences about old wallet data and about what stays (delete_wallet_stays_unchecked);
 * this class neither requires nor forbids them. The words HonestCopyTest and HonestCopySecondPassTest pin stay.
 */
class ResetSaysWhatHappensToAiDataTest {
    @Test
    fun `the box names AI chats, AI pictures and the key`() {
        val en = text(DW, "values", "delete_wallet_checkbox_title")
        for (word in listOf("AI chats", "AI pictures", "key to your AI credit", "chat", "key", "different recovery phrase")) {
            assertTrue("EN title lacks \"$word\": $en", word in en)
        }
        val es = text(DW, "values-es", "delete_wallet_checkbox_title")
        for (word in listOf("chats de IA", "imágenes de IA", "clave", "frase de recuperación distinta")) {
            assertTrue("ES title lacks «$word»: $es", word in es)
        }
    }

    @Test
    fun `unticked - the AI chats and pictures are deleted, a key to AI credit is kept for this phrase only`() {
        val en = text(DW, "values", "delete_wallet_checkbox_warning_unchecked_ai_kept")
        for (word in listOf("deleted", "AI chats", "AI pictures", "kept", "for this recovery phrase only") + EN_ADDITIONS) {
            assertTrue("EN warning lacks \"$word\": $en", word in en)
        }
        val es = text(DW, "values-es", "delete_wallet_checkbox_warning_unchecked_ai_kept")
        val esWords = listOf("eliminar", "chats de IA", "imágenes de IA", "conserva", "solo para esta frase de recuperación")
        for (word in esWords + ES_ADDITIONS) {
            assertTrue("ES warning lacks «$word»: $es", word in es)
        }
        assertKeyNeverDeleted(en, "key", listOf("delete"))
        assertKeyNeverDeleted(es, "clave", listOf("elimin", "borra"))
    }

    @Test
    fun `Full Wallet Reset - identity_reset_ai_note in both languages, shown only in the full-reset branch`() {
        val en = text(STR, "values", "identity_reset_ai_note_ai_kept")
        val es = text(STR, "values-es", "identity_reset_ai_note_ai_kept")
        assertNotEquals("the Spanish note is the English one", en, es)
        for (word in listOf("AI chats", "AI pictures", "deleted", "kept", "for this recovery phrase only") + EN_ADDITIONS) {
            assertTrue("EN note lacks \"$word\": $en", word in en)
        }
        for (word in listOf("chats de IA", "imágenes de IA", "elimin", "conserva", "solo para esta frase de recuperación") + ES_ADDITIONS) {
            assertTrue("ES note lacks «$word»: $es", word in es)
        }
        assertKeyNeverDeleted(en, "key", listOf("delete"))
        assertKeyNeverDeleted(es, "clave", listOf("elimin", "borra"))

        val view = source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityView.kt")
        assertEquals("the note is not drawn exactly once", 1, Regex("""R\.string\.identity_reset_ai_note\b""").findAll(view).count())
        // The full-reset branch of the confirm dialog: the `if (isFullReset) {` that holds the contacts line (the file
        // has other `if (isFullReset)` blocks before it, for the icon's tint and the title).
        val contactsLine = view.indexOf("stringResource(R.string.change_identity_full_reset_contacts)")
        val branch = view.lastIndexOf("if (isFullReset) {", contactsLine)
        assertTrue("the confirm dialog has no full-reset branch that holds the contacts line", branch >= 0 && contactsLine >= 0)
        val fullReset = view.substring(branch).substringBefore("} else {")
        assertTrue("the note is not in the full-reset branch of the confirm dialog", "R.string.identity_reset_ai_note" in fullReset)
        val contacts = fullReset.indexOf("R.string.change_identity_full_reset_contacts")
        assertTrue("the note is not under the contacts line", contacts in 0 until fullReset.indexOf("R.string.identity_reset_ai_note"))
    }

    @Test
    fun `the Destroy card says that Destroy All deletes a kept key`() {
        val en = text(STR, "values", "destroy_kept_ai_key_note")
        val es = text(STR, "values-es", "destroy_kept_ai_key_note")
        assertNotEquals(en, es)
        assertTrue(en, "Destroy All deletes that key too" in en)
        assertTrue(es, "Destruir todo también elimina esa clave" in es)
        val card = source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/view/EnhancedDestroyView.kt")
        val ai = card.indexOf("R.string.destroy_stays_ai)")
        val kept = card.indexOf("R.string.destroy_kept_ai_key_note)")
        assertTrue("the card does not draw destroy_kept_ai_key_note", kept >= 0)
        assertTrue("destroy_kept_ai_key_note is not directly after destroy_stays_ai", ai in 0 until kept)
        val between = card.substring(ai, kept)
        assertEquals("another line stands between them", 1, Regex("""R\.string\.""").findAll(between).count())
    }

    @Test
    fun `nothing they must not say`() {
        val texts =
            listOf(DW to "delete_wallet_checkbox_title", DW to "delete_wallet_checkbox_warning_unchecked", STR to "identity_reset_ai_note")
                .flatMap { (file, key) -> listOf(text(file, "values", key), text(file, "values-es", key)) } +
                listOf(text(STR, "values", "destroy_kept_ai_key_note"), text(STR, "values-es", "destroy_kept_ai_key_note"))
        for (text in texts) {
            val lower = text.lowercase()
            for (word in listOf("lost", "gone for good", "se pierde", "automatically", "automáticamente", "support", "soporte")) {
                assertFalse("says \"$word\": $text", word in lower)
            }
            assertFalse("names a number of hours: $text", Regex("""\d+\s*(hours?|horas?|h\b)""").containsMatchIn(lower))
            for (sentence in sentences(text)) {
                if ("AI pictures" in sentence || "imágenes de IA" in sentence) {
                    assertFalse("the sentence about AI pictures says encrypted: $sentence", "encrypted" in sentence.lowercase())
                    assertFalse("the sentence about AI pictures says cifrad: $sentence", "cifrad" in sentence.lowercase())
                }
            }
        }
    }

    @Test
    fun `parity - every key in both languages, no placeholder`() {
        for ((file, key) in KEYS) {
            val en = text(file, "values", key)
            val es = text(file, "values-es", key)
            assertTrue("$key is empty", en.isNotBlank() && es.isNotBlank())
            for (value in listOf(en, es)) {
                assertFalse("$key has a placeholder: $value", Regex("""%\d*\$?[sd]|\{\w*}""").containsMatchIn(value))
            }
        }
    }

    private fun assertKeyNeverDeleted(
        text: String,
        key: String,
        deleted: List<String>,
    ) {
        for (sentence in sentences(text).filter { key in it.lowercase() }) {
            for (word in deleted) {
                assertFalse("a sentence about the key says it is deleted: $sentence", word in sentence.lowercase())
            }
        }
    }

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.;:])\s+""")).filter { it.isNotBlank() }

    private fun text(
        file: String,
        dir: String,
        key: String,
    ): String {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/$file".replace("{dir}", dir))
        assertTrue("missing ${xml.path}", xml.isFile)
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(xml)
                .getElementsByTagName("string")
        val value =
            (0 until nodes.length)
                .map { nodes.item(it) }
                .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == key }
                ?.textContent
        assertNotNull("$dir: no $key in $file", value)
        return value!!.replace("\\'", "'").replace("\\\"", "\"")
    }

    private fun source(relative: String): String = File(repoRoot, relative).readText()

    private val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "gradle.properties").isFile && File(it, "ui-lib").isDirectory }
            ?: error("repo root not found above the test working dir")
    }

    private companion object {
        const val DW = "delete_wallet/{dir}/strings.xml"
        const val STR = "common/{dir}/destroy_identity_strings.xml"
        val EN_ADDITIONS = listOf("that copy stays", "until Destroy All or an uninstall", "To hand this phone on")
        val ES_ADDITIONS = listOf("esa copia se queda", "hasta que Destruir todo o una desinstalación", "Para entregar este teléfono")
        val KEYS =
            listOf(
                DW to "delete_wallet_checkbox_title",
                DW to "delete_wallet_checkbox_warning_unchecked",
                STR to "identity_reset_ai_note",
                STR to "destroy_kept_ai_key_note",
            )
    }
}
