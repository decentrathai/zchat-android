package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * ZC-822 (owner decision W-52, answer 3) — the words of a saved contact's chat request.
 *
 * Once a first key at a saved contact waits for Accept, its banner must not say "New contact": the chooser
 * ([ChatRequestBanner.isKnownChat]) knows a chat only by a message of ours in it. Wanted: the chooser takes a second
 * input, "the address is a saved contact", and a saved contact's request uses the "in this chat" pair also when this
 * phone has not written there yet. The chat screen passes that input. No text changes.
 *
 * The two-input chooser is called by name, so this file compiles before it exists.
 */
class SavedContactRequestBannerSaysInThisChatTest {
    private fun message(outgoing: Boolean) =
        ChatMessage(
            id = "m-$outgoing",
            txId = null,
            text = "hi",
            timestamp = Instant.ofEpochSecond(1_759_000_000),
            isOutgoing = outgoing,
            peerAddress = "u1peer",
        )

    /** The chooser with both inputs; before it exists, the one-input chooser answers for an address that is not saved. */
    private fun saysInThisChat(
        messages: List<ChatMessage>,
        savedContact: Boolean,
    ): Boolean {
        val twoInputs =
            ChatRequestBanner::class.java.methods.firstOrNull { it.name == "isKnownChat" && it.parameterCount == 2 }
        if (twoInputs != null) return twoInputs.invoke(ChatRequestBanner, messages, savedContact) as Boolean
        if (savedContact) fail("the banner's chooser has no input for a saved contact")
        return ChatRequestBanner.isKnownChat(messages)
    }

    @Test
    fun `a saved contact's request says in this chat also when this phone has not written there`() {
        assertTrue(saysInThisChat(emptyList(), savedContact = true))
        assertTrue(saysInThisChat(listOf(message(outgoing = false)), savedContact = true))
    }

    @Test
    fun `guard - an address that is not saved keeps today's choice`() {
        assertFalse("not saved, no message of ours: the stranger pair", saysInThisChat(listOf(message(outgoing = false)), false))
        assertTrue("not saved, one message of ours: the in-this-chat pair", saysInThisChat(listOf(message(outgoing = true)), false))
    }

    @Test
    fun `the chat screen hands the chooser whether the address is a saved contact`() {
        val detail = ChatResourceText.source("$MAIN/view/ChatDetailView.kt")
        val screen = ChatResourceText.source("$MAIN/AndroidChat.kt")
        assertTrue(
            "the banner does not ask the chooser with the saved-contact input",
            "ChatRequestBanner.isKnownChat(conversation.messages, chatRequestFromSavedContact)" in detail,
        )
        assertTrue(
            "the chat screen does not say whether the address is a saved contact",
            Regex("""chatRequestFromSavedContact\s*=\s*viewModel\.isSavedContact\(peerAddress\)""").containsMatchIn(screen),
        )
    }

    @Test
    fun `guard - the words are unchanged in English and Spanish`() {
        assertEquals("Key exchange in this chat — tap Accept", ChatResourceText.strings("values")[SHORT])
        assertEquals("Intercambio de claves en este chat — toca Aceptar", ChatResourceText.strings("values-es")[SHORT])
        assertEquals(
            mapOf(
                "one" to EN_START + "Accept sends %1\$d handshake transaction" + EN_END,
                "other" to EN_START + "Accept sends up to %1\$d handshake transactions" + EN_END,
            ),
            ChatResourceText.plurals("values")[COUNT],
        )
        assertEquals(
            mapOf(
                "one" to ES_START + "Aceptar envía %1\$d transacción de enlace" + ES_END,
                "many" to ES_START + "Aceptar envía hasta %1\$d transacciones de enlace" + ES_END,
                "other" to ES_START + "Aceptar envía hasta %1\$d transacciones de enlace" + ES_END,
            ),
            ChatResourceText.plurals("values-es")[COUNT],
        )
    }

    private companion object {
        const val MAIN = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
        const val SHORT = "chat_request_banner_short_known"
        const val COUNT = "chat_request_banner_count_known"
        const val EN_START = "A key exchange arrived in this chat. Nothing has been paid. "
        const val EN_END =
            " (at least %2\$s ZEC including fees) to finish it. It does not prove who sent it: compare the safety number " +
                "with your contact."
        const val ES_START = "Llegó un intercambio de claves a este chat. No se ha pagado nada. "
        const val ES_END =
            " (al menos %2\$s ZEC con comisiones) para completarlo. No prueba quién lo envió: compara el número de " +
                "seguridad con tu contacto."
    }
}
