package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.5, t5b SPECS §2 C-M2-8; skill app-texts): the refusals of a first
 * message whose two transactions the wallet cannot pay, or could not check.
 *
 * Wanted, in chat_send_strings.xml, English and Spanish, no placeholder: `chat_first_message_plan_unfundable` and
 * `chat_first_message_plan_unchecked` (shown at the send, the typed text is back in the box), and the row variants
 * `chat_first_message_plan_unfundable_row` and `chat_first_message_plan_unchecked_row` (shown above Retry on a queued or
 * retried row, so they name Retry and never the box). Every one says nothing was paid; the Spanish uses "Agrega ZEC"
 * (the app's register), not "Añade". Each ChatSendCopy constant is the English byte for byte and maps to its key.
 * The constants are reached by name (they do not exist at HEAD).
 */
class FirstMessagePlanTextsHaveSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val keys =
        mapOf(
            "FIRST_MESSAGE_PLAN_UNFUNDABLE" to "chat_first_message_plan_unfundable",
            "FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW" to "chat_first_message_plan_unfundable_row",
            "FIRST_MESSAGE_PLAN_UNCHECKED" to "chat_first_message_plan_unchecked",
            "FIRST_MESSAGE_PLAN_UNCHECKED_ROW" to "chat_first_message_plan_unchecked_row",
        )

    @Test
    fun `every refusal exists in English and Spanish with no placeholder and says nothing was paid`() {
        for (key in keys.values) {
            val english = assertNotNull(en[key], "$key has no English text")
            val spanish = assertNotNull(es[key], "$key has no Spanish text")
            assertFalse('%' in english || '%' in spanish, "$key must carry no placeholder")
            assertTrue("Nothing was paid" in english, "$key: $english")
            assertTrue("No se pagó nada" in spanish, "$key: $spanish")
            assertTrue(english.startsWith("Not sent"), "$key: $english")
            assertTrue(spanish.startsWith("No enviado"), "$key: $spanish")
        }
    }

    @Test
    fun `the box texts say the text is still in the box, the row texts name Retry and not the box`() {
        for (key in listOf("chat_first_message_plan_unfundable", "chat_first_message_plan_unchecked")) {
            assertTrue("still in the box" in en.getValue(key), en.getValue(key))
            assertTrue("sigue en el cuadro" in es.getValue(key), es.getValue(key))
        }
        for (key in listOf("chat_first_message_plan_unfundable_row", "chat_first_message_plan_unchecked_row")) {
            val english = assertNotNull(en[key], "$key has no English text")
            val spanish = assertNotNull(es[key], "$key has no Spanish text")
            assertTrue("Retry" in english && "box" !in english, "$key: $english")
            assertTrue("Reintentar" in spanish && "cuadro" !in spanish, "$key: $spanish")
        }
    }

    @Test
    fun `the unfundable texts ask for ZEC in the app's Spanish register`() {
        for (key in listOf("chat_first_message_plan_unfundable", "chat_first_message_plan_unfundable_row")) {
            assertTrue("Add ZEC" in en.getValue(key), en.getValue(key))
            assertTrue("Agrega ZEC" in es.getValue(key), es.getValue(key))
            assertFalse("Añade" in es.getValue(key), es.getValue(key))
        }
    }

    @Test
    fun `ChatSendCopy holds each English text and maps it to its key`() {
        for ((constantName, key) in keys) {
            val constant =
                runCatching { ChatSendCopy::class.java.getField(constantName).get(null) as String }
                    .getOrElse { throw AssertionError("ChatSendCopy.$constantName does not exist") }
            assertEquals(en[key], constant, constantName)
            val id = assertNotNull(ChatSendCopy.localizedResource(constant), "ChatSendCopy's map does not know $constantName")
            assertEquals(key, ChatResourceText.stringName(id))
        }
    }
}
