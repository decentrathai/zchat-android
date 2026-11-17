package co.electriccoin.zcash.ui.screen.walletstartup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F10 (fix-all 2.13.0, chat copy #16): the texts of the chat-copy back-off. Fails at 7d8799d29: there is no parked
 * screen text and no Advanced Settings "Restore old chats" row, and the offer texts promise that ZCHAT "keeps the copy
 * and asks again", which stops being true once a copy is parked.
 *
 * Required: the parked screen says, in English and in Spanish, that the copy stays on the phone and nothing was
 * deleted, never that the key is gone or why the phone could not unlock it (review break 8: nothing shows the key
 * is lost, and an unknown error class is treated the same way), and names the way back (More → Advanced Settings →
 * Restore old chats). The offer texts name that way too, the paused text says "later", and the Settings confirm says
 * that ZCHAT closes and that a send in progress should finish first (review break 7).
 */
class ChatCopyParkedCopyTest {
    private val english = ChatCopyBackoffApi.strings("error", "values")
    private val spanish = ChatCopyBackoffApi.strings("error", "values-es")
    private val settingsEnglish = ChatCopyBackoffApi.strings("advanced_settings", "values")
    private val settingsSpanish = ChatCopyBackoffApi.strings("advanced_settings", "values-es")

    private fun String.normalize() = replace(Regex("\\s+"), " ").trim()

    @Test
    fun the_parked_screen_is_written_in_english_and_in_spanish() {
        for (key in PARKED_KEYS) {
            val en = english[key] ?: throw AssertionError("error/values has no $key")
            val es = spanish[key] ?: throw AssertionError("error/values-es has no $key")
            assertTrue("$key is blank in English", en.isNotBlank())
            assertTrue("$key is blank in Spanish", es.isNotBlank())
        }
        assertNotEquals("the Spanish text is the English text", english[PARKED_MESSAGE]?.normalize(), spanish[PARKED_MESSAGE]?.normalize())
    }

    @Test
    fun the_parked_screen_never_calls_the_key_gone_and_names_the_way_back() {
        val en = english[PARKED_MESSAGE] ?: throw AssertionError("no $PARKED_MESSAGE in English")
        val es = spanish[PARKED_MESSAGE] ?: throw AssertionError("no $PARKED_MESSAGE in Spanish")
        for (gone in listOf("gone for good", "lost for good", "can't be read", "cannot be read")) {
            assertFalse("English calls the key gone ($gone): $en", en.contains(gone, ignoreCase = true))
        }
        for (gone in listOf("definitivamente", "se perdió", "no se pueden leer")) {
            assertFalse("Spanish calls the key gone ($gone): $es", es.contains(gone, ignoreCase = true))
        }
        assertFalse("English asserts the cause: $en", en.contains("did not answer", ignoreCase = true))
        assertFalse("Spanish asserts the cause: $es", es.contains("no respondió", ignoreCase = true))
        assertTrue(en, en.contains("could not unlock", ignoreCase = true))
        assertTrue(en, en.contains("nothing shows that the key is lost", ignoreCase = true))
        assertTrue(en, en.contains("nothing was deleted", ignoreCase = true))
        assertTrue(en, en.contains("More → Advanced Settings → Restore old chats"))
        assertTrue(es, es.contains("no pudo desbloquear", ignoreCase = true))
        assertTrue(es, es.contains("nada indica que la clave se haya perdido", ignoreCase = true))
        assertTrue(es, es.contains("no se borró nada", ignoreCase = true))
        assertTrue(es, es.contains("Más → Configuración avanzada → Restaurar chats antiguos"))
    }

    @Test
    fun the_offer_texts_no_longer_promise_to_ask_again_for_ever() {
        for (key in listOf(FIRST_OFFER_MESSAGE, REOFFER_MESSAGE)) {
            val en = english[key] ?: throw AssertionError("no $key in English")
            val es = spanish[key] ?: throw AssertionError("no $key in Spanish")
            assertTrue("$key (English) promises to ask again without the way back: $en", en.contains("Restore old chats"))
            assertTrue("$key (Spanish) promises to ask again without the way back: $es", es.contains("Restaurar chats antiguos"))
            assertTrue("$key (English): $en", en.contains("stops asking by itself", ignoreCase = true))
            assertTrue("$key (Spanish): $es", es.contains("deja de preguntar", ignoreCase = true))
        }
        val paused = english[PAUSED_MESSAGE].orEmpty()
        val pausedEs = spanish[PAUSED_MESSAGE].orEmpty()
        assertTrue(
            "the paused text still says the copy comes back as soon as the Keystore answers: $paused",
            paused.contains("again later", ignoreCase = true)
        )
        assertTrue(pausedEs, pausedEs.contains("más tarde", ignoreCase = true))
    }

    @Test
    fun the_settings_row_and_its_confirm_are_written_in_english_and_in_spanish() {
        for (key in SETTINGS_KEYS) {
            val en = settingsEnglish[key] ?: throw AssertionError("advanced_settings/values has no $key")
            val es = settingsSpanish[key] ?: throw AssertionError("advanced_settings/values-es has no $key")
            assertTrue("$key is blank in English", en.isNotBlank())
            assertTrue("$key is blank in Spanish", es.isNotBlank())
        }
        assertEquals("Restore old chats", settingsEnglish["advanced_settings_restore_old_chats"])
        assertEquals("Restaurar chats antiguos", settingsSpanish["advanced_settings_restore_old_chats"])
        val confirm = settingsEnglish["advanced_settings_restore_old_chats_confirm_text"].orEmpty()
        val confirmEs = settingsSpanish["advanced_settings_restore_old_chats_confirm_text"].orEmpty()
        assertTrue("the confirm does not say ZCHAT closes: $confirm", confirm.contains("closes", ignoreCase = true))
        assertTrue("the confirm does not ask to let a send finish first: $confirm", confirm.contains("finished", ignoreCase = true))
        assertTrue(confirmEs, confirmEs.contains("se cierra", ignoreCase = true))
        assertTrue(confirmEs, confirmEs.contains("termine", ignoreCase = true))
        assertTrue(
            "the settings path names a title the menu does not show",
            english["wallet_startup_chat_copy_parked_message"].orEmpty().contains(
                settingsEnglish["advanced_settings_restore_old_chats"].orEmpty()
            )
        )
    }

    private companion object {
        const val PARKED_MESSAGE = "wallet_startup_chat_copy_parked_message"
        const val FIRST_OFFER_MESSAGE = "wallet_startup_chat_copy_message"
        const val REOFFER_MESSAGE = "wallet_startup_chat_copy_reoffer_after_use_message"
        const val PAUSED_MESSAGE = "wallet_startup_chat_copy_paused_message"

        val PARKED_KEYS =
            listOf(
                "wallet_startup_chat_copy_parked_title",
                PARKED_MESSAGE,
                "wallet_startup_chat_copy_parked_button",
            )

        val SETTINGS_KEYS =
            listOf(
                "advanced_settings_restore_old_chats",
                "advanced_settings_restore_old_chats_subtitle",
                "advanced_settings_restore_old_chats_confirm_title",
                "advanced_settings_restore_old_chats_confirm_text",
                "advanced_settings_restore_old_chats_confirm_button",
                "advanced_settings_restore_old_chats_cancel_button",
            )
    }
}
