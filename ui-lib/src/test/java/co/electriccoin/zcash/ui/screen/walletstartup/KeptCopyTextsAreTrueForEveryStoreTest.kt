package co.electriccoin.zcash.ui.screen.walletstartup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-733-C (register ZC-733, medium; store-opener design, revision 2a, rule S10, sections 4.3 and 4.4; class C): the
 * kept-copy screens are true for every store that can be set aside: a chat store, the nicknames, the contact book, the
 * picture index or the AI store. They say "chat data" and "the kept copy", never "your chats were set aside"; the first
 * offer names the kinds once; the gone screen no longer says the new chats "are not affected" (a restore can keep them
 * as a copy); the Settings row speaks of the kept copy. Button labels and the chat stores' own screen do not change.
 *
 * Reads the string files as the text pins of this folder do. Today the texts speak of "your chats" for a copy that may
 * hold only contact names, pictures or AI chats.
 */
class KeptCopyTextsAreTrueForEveryStoreTest {
    private val offerAndScreens =
        listOf(
            "wallet_startup_chat_copy_title",
            "wallet_startup_chat_copy_message",
            "wallet_startup_chat_copy_reoffer_after_use_message",
            "wallet_startup_chat_copy_gone_title",
            "wallet_startup_chat_copy_gone_message",
            "wallet_startup_chat_copy_paused_title",
            "wallet_startup_chat_copy_paused_message",
            "wallet_startup_chat_copy_parked_title",
            "wallet_startup_chat_copy_parked_message",
        )
    private val settings = listOf("advanced_settings_restore_old_chats_subtitle", "advanced_settings_restore_old_chats_confirm_text")

    // (1) FAILS today.
    @Test
    fun `1 - no 'your chats' for what was set aside`() {
        val forbidden =
            listOf(
                "your chats were",
                "your old chats",
                "kept chats",
                "your kept chats",
                "tus chats se apartaron",
                "tus chats antiguos",
                "chats guardados",
            )
        for (qualifier in listOf("values", "values-es")) {
            val texts = strings("error", qualifier)
            offerAndScreens.forEach { key ->
                val text = texts.getValue(key).lowercase()
                forbidden.forEach { phrase -> assertFalse("$qualifier/$key says '$phrase': $text", phrase in text) }
            }
        }
    }

    // (2) FAILS today.
    @Test
    fun `2 - the first offer names the kinds once`() {
        val en = strings("error", "values").getValue("wallet_startup_chat_copy_message")
        val es = strings("error", "values-es").getValue("wallet_startup_chat_copy_message")
        assertTrue(en, "Chats, chat keys, contact names, pictures or AI chats" in en)
        assertTrue(es, "chats, claves de chat, nombres de contactos, fotos o chats de IA" in es)
    }

    // (3) FAILS today.
    @Test
    fun `3 - the gone screen keeps the new chats on this phone and is not 'not affected'`() {
        val en = strings("error", "values").getValue("wallet_startup_chat_copy_gone_message")
        val es = strings("error", "values-es").getValue("wallet_startup_chat_copy_gone_message")
        assertFalse(en, "are not affected" in en)
        assertFalse(es, "no se ven afectados" in es)
        assertTrue(en, "kept on this phone" in en)
        assertTrue(es, "se quedan en este teléfono" in es)
    }

    // (4) FAILS today.
    @Test
    fun `4 - the Settings texts speak of the kept copy, the row title is unchanged`() {
        for ((qualifier, phrase) in listOf("values" to "of your chats", "values-es" to "de tus chats")) {
            val texts = strings("advanced_settings", qualifier)
            settings.forEach { key -> assertFalse("$qualifier/$key says '$phrase'", phrase in texts.getValue(key)) }
        }
    }

    // (5) Guard: byte for byte as at this commit's parent.
    @Test
    fun `5 - the buttons, Try again, the row title and the chat stores' own screen do not change`() {
        val pinned =
            listOf(
                Triple("values-es", "advanced_settings_restore_old_chats", "Restaurar chats antiguos"),
                Triple("values-es", "wallet_startup_chat_copy_continue", "Continuar sin ellos"),
                Triple("values-es", "wallet_startup_chat_copy_gone_button", "Continuar"),
                Triple("values-es", "wallet_startup_chat_copy_parked_button", "Continuar"),
                Triple("values-es", "wallet_startup_chat_copy_paused_button", "Continuar"),
                Triple("values-es", "wallet_startup_chat_copy_restore", "Restaurar mis chats"),
                Triple("values-es", "wallet_startup_chats_unavailable_message", UNAVAILABLE_ES),
                Triple("values-es", "wallet_startup_chats_unavailable_title", "ZCHAT no puede abrir tus chats ahora"),
                Triple("values-es", "wallet_startup_failed_try_again", "Reintentar"),
                Triple("values", "advanced_settings_restore_old_chats", "Restore old chats"),
                Triple("values", "wallet_startup_chat_copy_continue", "Continue without them"),
                Triple("values", "wallet_startup_chat_copy_gone_button", "Continue"),
                Triple("values", "wallet_startup_chat_copy_parked_button", "Continue"),
                Triple("values", "wallet_startup_chat_copy_paused_button", "Continue"),
                Triple("values", "wallet_startup_chat_copy_restore", "Restore my chats"),
                Triple("values", "wallet_startup_chats_unavailable_message", UNAVAILABLE_EN),
                Triple("values", "wallet_startup_chats_unavailable_title", "ZCHAT can't open your chats right now"),
                Triple("values", "wallet_startup_failed_try_again", "Try again"),
            )
        pinned.forEach { (qualifier, key, value) ->
            val folder = if (key.startsWith("advanced_settings")) "advanced_settings" else "error"
            assertEquals("$qualifier/$key changed", value, strings(folder, qualifier).getValue(key))
        }
    }

    // (6)
    @Test
    fun `6 - parity - the eleven keys exist in both languages, differ, hold no placeholder, and the offers name the wallet`() {
        for ((folder, keys) in listOf("error" to offerAndScreens, "advanced_settings" to settings)) {
            val en = strings(folder, "values")
            val es = strings(folder, "values-es")
            keys.forEach { key ->
                val english = en[key] ?: throw AssertionError("no English $key")
                val spanish = es[key] ?: throw AssertionError("no Spanish $key")
                assertNotEquals("$key: the Spanish is the English", english, spanish)
                assertFalse("$key holds a placeholder", "%" in english || "%" in spanish)
            }
        }
        for (key in listOf("wallet_startup_chat_copy_message", "wallet_startup_chat_copy_reoffer_after_use_message")) {
            val english = strings("error", "values").getValue(key)
            assertTrue(key, english.endsWith("ask for it again from More → Advanced Settings → Restore old chats."))
            assertTrue(
                key,
                strings("error", "values-es")
                    .getValue(key)
                    .endsWith("puedes volver a pedirla desde Más → Configuración avanzada → Restaurar chats antiguos."),
            )
        }
    }

    /** The text of every string of res/ui/<folder>/<qualifier>/strings.xml, with the files' \' read as an apostrophe. */
    private fun strings(
        folder: String,
        qualifier: String,
    ): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val file = File(dir ?: error("repo root not found"), "ui-lib/src/main/res/ui/$folder/$qualifier/strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private companion object {
        private const val UNAVAILABLE_EN =
            "Your phone's security system (Android Keystore) did not answer, so ZCHAT cannot open the encrypted " +
                "storage of your chats and chat keys. Nothing was deleted: your chats and your wallet are still on " +
                "this phone. Tap Try again. If it keeps happening, restart your phone and open ZCHAT again."

        private const val UNAVAILABLE_ES =
            "El sistema de seguridad de tu teléfono (Android Keystore) no respondió, así que ZCHAT no puede abrir " +
                "el almacenamiento cifrado de tus chats y sus claves. No se borró nada: tus chats y tu billetera " +
                "siguen en este teléfono. Toca Reintentar. Si sigue pasando, reinicia el teléfono y vuelve a abrir " +
                "ZCHAT."
    }
}
