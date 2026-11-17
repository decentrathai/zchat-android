package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 8 D5 (DECISIONS_NIGHT §2 / M-NOS-21) — the Reset encryption text exists in English and Spanish.
 *
 * - The paid-action question names what a reset pays for (paid_action_reset_encryption).
 * - The confirm dialog before it (ChatDetailView) was English only, hard-coded: its title, body and buttons move to
 *   resources, the English body byte for byte, with a Spanish translation.
 * - The body quotes the text a chat shows for a message it cannot decrypt. Since ZC-1575/ZC-1592 that text is a resource
 *   in both languages, so each body quotes the text a user of that language sees, and the quote must stay the real wording.
 *
 * Resources are read from the XML files, so this file compiles before they exist.
 */
class ResetEncryptionCopyTest {
    @Test
    fun `the Reset encryption strings exist in English and Spanish with the reviewed wording`() {
        val en = strings("values", MONEY_FILE) + strings("values", MENU_FILE)
        val es = strings("values-es", MONEY_FILE) + strings("values-es", MENU_FILE)
        for ((key, expected) in ENGLISH) {
            assertEquals(expected, en[key] ?: fail("values has no $key"), "English $key")
        }
        for ((key, expected) in SPANISH) {
            val spanish = es[key] ?: fail("values-es has no $key")
            assertEquals(expected, spanish, "Spanish $key")
            assertNotEquals(en[key], spanish, "$key is the English text in the Spanish file")
        }
    }

    @Test
    fun `English and Spanish use the same placeholders and the same paragraph breaks`() {
        val en = strings("values", MONEY_FILE) + strings("values", MENU_FILE)
        val es = strings("values-es", MONEY_FILE) + strings("values-es", MENU_FILE)
        for (key in ENGLISH.keys) {
            val enText = en[key] ?: fail("values has no $key")
            val esText = es[key] ?: fail("values-es has no $key")
            assertEquals(placeholders(enText), placeholders(esText), "placeholders of $key")
            assertEquals(enText.split("\\n").size, esText.split("\\n").size, "line breaks of $key")
        }
    }

    @Test
    fun `the body quotes the text a chat really shows for a message it cannot decrypt`() {
        val viewModel = source("screen/chat/viewmodel/ChatViewModel.kt")
        assertTrue("R.string.$KEY_MISSING" in viewModel, "the chat no longer shows R.string.$KEY_MISSING")
        val enShown = strings("values", MENU_FILE)[KEY_MISSING] ?: fail("values has no $KEY_MISSING")
        val esShown = strings("values-es", MENU_FILE)[KEY_MISSING] ?: fail("values-es has no $KEY_MISSING")
        assertTrue(DECRYPT_FAILURE_EN in enShown, "the chat's own text no longer says \"$DECRYPT_FAILURE_EN\": $enShown")
        assertTrue(DECRYPT_FAILURE_ES in esShown, "the Spanish chat text no longer says \"$DECRYPT_FAILURE_ES\": $esShown")
        val es = strings("values-es", MENU_FILE)[BODY] ?: fail("values-es has no $BODY")
        assertTrue("«$DECRYPT_FAILURE_ES»" in es, "the Spanish body must quote the Spanish text the chat shows: $es")
    }

    @Test
    fun `the confirm dialog shows the resources, not hard-coded English`() {
        val view = source("screen/chat/view/ChatDetailView.kt")
        for (key in listOf(TITLE, BODY, CONFIRM, CANCEL)) {
            assertTrue("R.string.$key" in view, "ChatDetailView must show R.string.$key")
        }
        assertFalse("Text(\"Reset encryption?\")" in view, "the title is still hard-coded")
        assertFalse("\"Use this only if messages here show" in view, "the body is still hard-coded")
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    private fun strings(
        dir: String,
        file: String,
    ): Map<String, String> {
        val f = File(resRoot, "$dir/$file")
        if (!f.isFile) fail("missing ${f.path}")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace(Regex("\\s+"), " ")
                        .trim()
            }
    }

    private fun source(relative: String): String {
        val f = resRoot.resolve("../../../java/co/electriccoin/zcash/ui/$relative").normalize()
        if (!f.isFile) fail("missing ${f.path}")
        return f.readText()
    }

    private companion object {
        const val MONEY_FILE = "chat_money_strings.xml"
        const val MENU_FILE = "chat_menu_composer_strings.xml"
        const val PAID = "paid_action_reset_encryption"
        const val TITLE = "chat_reset_encryption_title"
        const val BODY = "chat_reset_encryption_body"
        const val CONFIRM = "chat_reset_encryption_confirm"
        const val CANCEL = "chat_reset_encryption_cancel"
        const val DECRYPT_FAILURE_EN = "its decryption key isn't on this device"
        const val KEY_MISSING = "chat_decrypt_placeholder_key_missing"
        const val DECRYPT_FAILURE_ES = "su clave de descifrado no está en este dispositivo"

        val ENGLISH =
            mapOf(
                PAID to "Resetting encryption with this contact: your key exchange, then your relay identity, both on-chain.",
                TITLE to "Reset encryption?",
                BODY to
                    "Use this only if messages here show “its decryption key isn’t on this device.” That happens when " +
                    "one of you restored from seed or reset their phone, leaving your encryption keys out of sync." +
                    "\\n\\nThis re-establishes a fresh secure session with this contact. Your chat history stays, but " +
                    "messages sent before the reset can’t be recovered. Only one of you needs to do this; ZChat shows " +
                    "the price before anything is paid.",
                CONFIRM to "Reset",
                CANCEL to "Cancel",
            )

        val SPANISH =
            mapOf(
                PAID to
                    "Restablecer el cifrado con este contacto: tu intercambio de claves y luego tu identidad de relay, " +
                    "ambos en la cadena.",
                TITLE to "¿Restablecer el cifrado?",
                BODY to
                    "Úsalo solo si los mensajes aquí muestran «su clave de descifrado no está en este dispositivo». Eso " +
                    "pasa cuando uno de ustedes restauró desde la frase de recuperación o reinició su teléfono y sus " +
                    "claves de cifrado quedaron desincronizadas.\\n\\nEsto crea una sesión segura nueva con este " +
                    "contacto. Tu historial se conserva, pero los mensajes enviados antes del restablecimiento no se " +
                    "pueden recuperar. Basta con que lo haga uno de ustedes; ZChat muestra el precio antes de pagar nada.",
                CONFIRM to "Restablecer",
                CANCEL to "Cancelar",
            )

        val resRoot: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/res/ui/common") }
                .firstOrNull { it.resolve("values").isDirectory }
                ?: fail("could not locate ui-lib/src/main/res/ui/common from ${start.path}")
        }
    }
}
