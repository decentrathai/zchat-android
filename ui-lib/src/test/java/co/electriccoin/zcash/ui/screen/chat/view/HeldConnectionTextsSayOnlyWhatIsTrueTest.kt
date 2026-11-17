package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-634 part B (FIRST-CONTACT-SPEC revision 4a, 5.5 item 8, 6.4, 7.1): the three texts of a held connection and the moved
 * "Mark as verified" label, in English and Spanish, with the design's wording; the banner shows strings 1 and 2 only while
 * a mark stands and a ZBOOT was held; the dialog's button names the label the Spanish sentence names.
 *
 * Today none of the four keys exists, the waiting banner is a literal pair in ChatDetailView.kt, and the dialog's button
 * is the English literal "Mark as verified".
 */
class HeldConnectionTextsSayOnlyWhatIsTrueTest {
    private val english = ChatResourceText.strings("values")
    private val spanish = ChatResourceText.strings("values-es")

    @Test
    fun `the four keys exist in English and Spanish, in the chat surface file, with the design's words and no placeholder`() {
        val problems = mutableListOf<String>()
        for ((key, words) in EXPECTED) {
            val (en, es) = words
            if (english[key] != en) problems += "values/$key is ${english[key]?.take(40)}"
            if (spanish[key] != es) problems += "values-es/$key is ${spanish[key]?.take(40)}"
            for (dir in listOf("values", "values-es")) {
                val file = ChatResourceText.source("ui-lib/src/main/res/ui/common/$dir/chat_surface_strings.xml")
                if ("name=\"$key\"" !in file) problems += "$dir/chat_surface_strings.xml lacks $key"
            }
            if ((english[key] ?: "").contains('%') || (spanish[key] ?: "").contains('%')) problems += "$key has a placeholder"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the held banner's body promises nothing about Reconnect, payment or a free route`() {
        val body = english["chat_key_unconfirmed_held_body"] ?: ""
        assertTrue("fixture: the body exists", body.isNotEmpty())
        for (word in listOf("Reconnect", "nothing was paid", "free")) {
            assertFalse("the held banner's body says \"$word\"", body.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `the dialog's button and the held banner come from the resources, and the literal is gone`() {
        val view = ChatResourceText.source(CDV)
        assertFalse("the literal \"Mark as verified\" is still in ChatDetailView.kt", "\"Mark as verified\"" in view)
        assertTrue("the dialog does not read chat_safety_number_mark_verified", "R.string.chat_safety_number_mark_verified" in view)
        assertTrue("the banner does not read chat_key_unconfirmed_held_short", "R.string.chat_key_unconfirmed_held_short" in view)
        assertTrue("the banner does not read chat_key_unconfirmed_held_body", "R.string.chat_key_unconfirmed_held_body" in view)
        val vm = ChatResourceText.source(CVM)
        assertTrue(
            "Reconnect does not show chat_reconnect_not_sent_key_unconfirmed",
            "R.string.chat_reconnect_not_sent_key_unconfirmed" in vm,
        )
    }

    @Test
    fun `the banner shows the held texts only while a mark stands and a ZBOOT is held, the old pair otherwise`() {
        val view = ChatResourceText.source(CDV)
        val held = view.indexOf("R.string.chat_key_unconfirmed_held_short")
        val waiting = view.indexOf("shortLabel = \"Waiting for contact — calls locked\"")
        assertTrue("fixture: both banners are in the view", held >= 0 && waiting >= 0)
        val between = view.substring(minOf(held, waiting), maxOf(held, waiting))
        assertTrue("the choice between the two banners does not read the held state (keyClaimHeld)", "keyClaimHeld" in between)
        val screen = ChatResourceText.source(AC)
        assertTrue("the screen does not hand the held state to the view", "keyClaimHeld = viewModel.isKeyClaimHeld(peerAddress)" in screen)
    }

    private companion object {
        const val CDV = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val AC = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"

        /** Design 7.1, both languages, as the screen shows them. */
        val EXPECTED: Map<String, Pair<String, String>> =
            mapOf(
                "chat_key_unconfirmed_held_short" to
                    ("Connection held back — key not confirmed" to "Conexión retenida: clave sin confirmar"),
                "chat_key_unconfirmed_held_body" to
                    (
                        "A connection message for this chat arrived and ZChat held it back, because this chat's security key " +
                            "has not been confirmed. It may be from your contact, or from someone pretending to be them. " +
                            "Compare the safety number with your contact in person or on a channel you already trust: open ⋮ → " +
                            "Verify safety number. Only if the numbers match, tap “Mark as verified”; ZChat then uses the held " +
                            "message, and calls and messages over the relays can start. If they don't match, don't tap it. " +
                            "Holding the message back cost nothing." to
                            "Llegó un mensaje de conexión para este chat y ZChat lo retuvo, porque la clave de seguridad de este " +
                            "chat no está confirmada. Puede ser de tu contacto, o de alguien que se hace pasar por esa persona. " +
                            "Compara el número de seguridad con tu contacto en persona o por un canal en el que ya confíes: abre " +
                            "⋮ → Verificar el número de seguridad. Solo si los números coinciden, toca «Marcar como verificado»; " +
                            "ZChat usará entonces el mensaje retenido, y podrán empezar las llamadas y los mensajes por los " +
                            "relays. Si no coinciden, no lo toques. Retener el mensaje no costó nada."
                    ),
                "chat_reconnect_not_sent_key_unconfirmed" to
                    (
                        "Nothing was sent or charged. This chat's security key has not been confirmed, so sending your key " +
                            "again would not connect it. Compare the safety number with your contact first: ⋮ → Verify safety " +
                            "number." to
                            "No se envió ni se cobró nada. La clave de seguridad de este chat no está confirmada, así que volver " +
                            "a enviar tu clave no lo conectaría. Primero compara el número de seguridad con tu contacto: ⋮ → " +
                            "Verificar el número de seguridad."
                    ),
                "chat_safety_number_mark_verified" to ("Mark as verified" to "Marcar como verificado"),
            )
    }
}
