package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R10 (fix-all 2.13.0, train T5; W-11; SPEC.md §3.9; skill app-texts): the relay swap is disclosed.
 *
 * From 2.13.0 the app replaces one of the two public relays that stops working with another public relay from a list
 * built into the app; that relay then sees the chat key, the IP address, online times and the chat keys we send to,
 * as the others do. More → Security (row 6, "Relays") and the Open mode note say which relays see what, so each says
 * this too, in English and Spanish, and the English Kotlin twins (PrivacyCopy.WHAT_ZCHAT_PROTECTS_ITEMS row 6 and
 * ConversationModeUi.OPEN_DESCRIPTION, which the resources equal byte for byte) carry the same sentence. The texts
 * still say "three Nostr relays" (three are active at any time), promise nothing ("always", "guarantee", "never
 * lost"), and the Spanish keeps the word "relay" (no "relé").
 */
class RelaySwapDisclosureTest {
    private val en by lazy { ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") } }
    private val es by lazy { ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") } }

    private companion object {
        const val SECURITY_EN =
            "If one of the two other relays stops working, ZChat replaces it with another public relay from a list " +
                "built into the app; that relay can then see the same things."
        const val SECURITY_ES =
            "Si uno de los otros dos relays deja de funcionar, ZChat lo reemplaza por otro relay público de una lista " +
                "incluida en la app; ese relay podrá ver lo mismo."
        const val OPEN_EN =
            "If one of those two stops working, ZChat switches to another public relay from a list built into the app."
        const val OPEN_ES =
            "Si uno de esos dos deja de funcionar, ZChat cambia a otro relay público de una lista incluida en la app."
        val PROMISES = listOf("always", "guarantee", "never lost", "siempre", "garantiza", "nunca se pierde")
    }

    private fun assertHonest(
        text: String,
        where: String,
    ) {
        PROMISES.forEach { assertFalse(it in text.lowercase(), "$where promises \"$it\": $text") }
        assertFalse("relé" in text.lowercase(), "$where says \"relé\": $text")
    }

    @Test
    fun `security row 6 says a relay that stops working is replaced, in English, Spanish and its Kotlin twin`() {
        val english = en.getValue("security_dialog_item_6_body")
        val spanish = es.getValue("security_dialog_item_6_body")
        val twin = WHAT_ZCHAT_PROTECTS_ITEMS[5].second
        assertTrue(SECURITY_EN in english, "EN security_dialog_item_6_body: $english")
        assertTrue(SECURITY_ES in spanish, "ES security_dialog_item_6_body: $spanish")
        assertTrue(SECURITY_EN in twin, "WHAT_ZCHAT_PROTECTS_ITEMS row 6: $twin")
        assertTrue(english.endsWith("Relays may keep logs. $SECURITY_EN"), "the sentence follows \"Relays may keep logs.\"")
        assertTrue("three Nostr relays" in english && "tres relays de Nostr" in spanish, "the three relays stay")
        listOf("EN" to english, "ES" to spanish, "twin" to twin).forEach { (where, text) -> assertHonest(text, where) }
    }

    @Test
    fun `the Open mode note says ZChat switches to another public relay, in English, Spanish and its Kotlin twin`() {
        val english = en.getValue("chat_mode_note_open_body")
        val spanish = es.getValue("chat_mode_note_open_body")
        assertTrue(OPEN_EN in english, "EN chat_mode_note_open_body: $english")
        assertTrue(OPEN_ES in spanish, "ES chat_mode_note_open_body: $spanish")
        assertTrue(OPEN_EN in OPEN_DESCRIPTION, "OPEN_DESCRIPTION: $OPEN_DESCRIPTION")
        assertTrue(
            "one run by ZChat and two by other people. $OPEN_EN" in english,
            "the sentence follows \"…one run by ZChat and two by other people.\"",
        )
        assertTrue("three Nostr relays" in english && "tres relays de Nostr" in spanish, "the three relays stay")
        listOf("EN" to english, "ES" to spanish, "twin" to OPEN_DESCRIPTION).forEach { (where, text) -> assertHonest(text, where) }
    }
}
