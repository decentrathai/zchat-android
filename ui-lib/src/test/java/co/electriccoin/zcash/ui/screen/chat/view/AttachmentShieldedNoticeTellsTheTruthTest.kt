package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1749 and ZC-1754 (QA2 p3c, Fable review of P3b, finding 1): the Shielded attachment notice
 * `chat_attachment_needs_kex_shielded_body` ("Send a text message first").
 *
 * What really happens (traced): the notice shows for AttachmentBlockReason.NEEDS_KEY_EXCHANGE_SHIELDED, a Shielded chat
 * where this phone holds no key of its own for the contact. The user's first text message there reaches
 * ChatViewModel.sendMessage, which (firstOnChainMessageNeedsKex) asks the first-message question: the total of the key
 * exchange and the message, plus our relay identity when it must go on-chain. That question is asked whether or not the
 * standing consent ("Allow handshake transactions?") was given (the consent covers only the automatic setups, and a
 * Shielded chat's chat open starts none); nothing is stored or paid until the user confirms. FirstMessageKexIsNotLostTest
 * drives it with the consent on and gets the FIRST_MESSAGE question.
 * So the old English "ZChat shows the price before anything is paid" promised a price, not what is asked (the total of the
 * key exchange and the message, and a confirmation), and P3b's unplanned Spanish rewrite ("..., y Si has permitido las
 * transacciones de enlace, se paga sin otra pregunta; si no, ZChat te pregunta antes") was wrong twice: it said a payment
 * without a question, which this path never makes, and it carried a capital "Si" after "y".
 *
 * Wanted, in both languages: the text says the text message starts the key exchange, that ZChat shows the total for the key
 * exchange and the message, and that nothing is paid until the user confirms. It does not repeat "before anything is paid",
 * does not mention the allowed handshake transactions (they change nothing here), and is grammatical Spanish.
 *
 * ZC-1756: the KDoc of AttachmentBlockAction.SEND_KEY_EXCHANGE said "priced before anything is paid", the claim ZC-1729
 * removed from the cost line.
 *
 * Resource and source checks. Synthetic text only.
 */
class AttachmentShieldedNoticeTellsTheTruthTest {
    private val key = "chat_attachment_needs_kex_shielded_body"
    private val english by lazy { ChatResourceText.strings("values").getValue(key) }
    private val spanish by lazy { ChatResourceText.strings("values-es").getValue(key) }

    @Test
    fun `the English notice no longer says the price comes before anything is paid`() {
        assertFalse("before anything is paid" in english, "$key still says \"before anything is paid\": $english")
    }

    @Test
    fun `the Spanish notice no longer says the price comes before anything is paid`() {
        for (old in listOf("antes de pagar nada", "antes de que se pague nada", "antes de que pagues nada")) {
            assertFalse(old in spanish, "$key (Spanish) still says \"$old\": $spanish")
        }
    }

    @Test
    fun `the Spanish notice has no capital Si after y`() {
        assertFalse(
            Regex("""\b[yY] Si\b""").containsMatchIn(spanish),
            "$key (Spanish) has a capital \"Si\" after \"y\" in the middle of a sentence: $spanish",
        )
    }

    @Test
    fun `neither notice promises a payment without a question or leans on the standing consent`() {
        assertFalse(Regex("""(?i)without another question|allowed handshake""").containsMatchIn(english), "$key (English): $english")
        assertFalse(Regex("""(?i)sin otra pregunta|permitido las transacciones""").containsMatchIn(spanish), "$key (Spanish): $spanish")
    }

    @Test
    fun `the English notice says the message starts the key exchange, shows the total and pays nothing until the user confirms`() {
        assertTrue(
            Regex("""(?i)text message.*(starts|begins) the key exchange""").containsMatchIn(english),
            "no 'starts the key exchange': $english",
        )
        assertTrue(Regex("""(?i)\btotal\b""").containsMatchIn(english), "it names no total for the key exchange and the message: $english")
        assertTrue(
            Regex("""(?i)nothing is paid until you confirm""").containsMatchIn(english),
            "it does not say nothing is paid until the user confirms: $english",
        )
    }

    @Test
    fun `the Spanish notice says the message starts the key exchange, shows the total and pays nothing until the user confirms`() {
        assertTrue(
            Regex("""(?i)mensaje de texto.*inicia el intercambio de claves""").containsMatchIn(spanish),
            "no 'inicia el intercambio': $spanish",
        )
        assertTrue(Regex("""(?i)\btotal\b""").containsMatchIn(spanish), "it names no total for the key exchange and the message: $spanish")
        assertTrue(
            Regex("""(?i)no se paga nada hasta que lo confirmes""").containsMatchIn(spanish),
            "it does not say nothing is paid until the user confirms: $spanish",
        )
    }

    @Test
    fun `both notices keep what the earlier tests pin`() {
        // No switch button and no Open-chat talk (AttachmentsNeedAKeyExchangeTest), and the two languages differ.
        assertFalse("Open" in english || "Switch" in english, "the notice sends the user to a switch or talks about Open: $english")
        assertFalse("Open" in spanish || "Cambia" in spanish, "the notice sends the user to a switch or talks about Open: $spanish")
        assertTrue(english != spanish, "$key is not translated")
    }

    @Test
    fun `the KDoc of the send-key-exchange action does not promise a price before anything is paid`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/SendMessageState.kt")
        val at = source.indexOf("    SEND_KEY_EXCHANGE,")
        assertTrue(at >= 0, "SEND_KEY_EXCHANGE is gone from SendMessageState.kt")
        val doc = source.substring(source.lastIndexOf("/**", at), at)
        assertFalse("before anything is paid" in doc, "the KDoc of SEND_KEY_EXCHANGE still says it is priced before anything is paid: $doc")
        assertTrue("consent" in doc.lowercase(), "the KDoc of SEND_KEY_EXCHANGE does not mention the standing consent: $doc")
    }
}
