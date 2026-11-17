package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SR-A (fix-all 2.13.0, train T5b; photos-privacy SPEC §6 S-A1..S-A8 with t5b SPECS §3's text changes): the texts of a
 * Shielded attachment's exact price question and of its refusals are string resources in English and Spanish.
 *
 * At 97ba1a6a8: the photo's no-funds and still-confirming reasons are English literals in ChatViewModel
 * (photoSendErrorMessage), shown in English on a Spanish phone; there is no exact-price line (every question says
 * "at least"); no question names a Shielded attachment; the VAULT branch that sent the link over the relays is still there.
 *
 * Wanted (S-A1, S-A2 also carry the 2.12.4 sentence): each key exists in values/ and values-es/ with the same
 * placeholders and a Spanish text that is not the English one; the four ChatSendCopy row reasons map to their resources
 * with the English equal to the constant; PaidActionCopy names the Shielded attachment's texts; the English literals and
 * the relay branch's log line are gone from ChatViewModel.
 */
class ShieldedAttachmentTextsAreResourcesTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")
    private val enPlurals = ChatResourceText.plurals("values")
    private val esPlurals = ChatResourceText.plurals("values-es")
    private val cvm = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"

    private val keys =
        listOf(
            "paid_action_shielded_attachment",
            "paid_action_shielded_attachment_memos",
            "paid_action_cost_exact",
            "chat_attachment_price_changed",
            "chat_attachment_no_funds_title",
            "chat_attachment_no_funds_body",
            "chat_attachment_no_funds_switch_tunnel",
            "chat_attachment_no_funds_not_now",
            "chat_attachment_still_confirming",
            "chat_attachment_no_funds_row",
            "chat_attachment_price_unavailable",
        )

    @Test
    fun `every Shielded attachment text exists in English and Spanish with the same placeholders`() {
        for (key in keys) {
            val english = en[key] ?: fail("no English $key")
            val spanish = es[key] ?: fail("no Spanish $key")
            assertNotEquals(english, spanish, "the Spanish $key is the English text")
            assertEquals(placeholders(english), placeholders(spanish), "the placeholders of $key differ")
        }
        assertEquals(listOf("%1\$d"), placeholders(en.getValue("paid_action_shielded_attachment_memos")))
        assertEquals(listOf("%1\$s", "%2\$s", "%3\$s", "%4\$s"), placeholders(en.getValue("paid_action_cost_exact")))
        assertEquals(listOf("%1\$s", "%2\$s"), placeholders(en.getValue("chat_attachment_no_funds_body")))
    }

    @Test
    fun `the two-transaction exact line is a plural in English and Spanish`() {
        val english = enPlurals["paid_action_cost_exact_many"] ?: fail("no English plurals paid_action_cost_exact_many")
        val spanish = esPlurals["paid_action_cost_exact_many"] ?: fail("no Spanish plurals paid_action_cost_exact_many")
        for ((quantity, text) in english) {
            assertEquals(listOf("%1\$d", "%2\$s", "%3\$s"), placeholders(text), "English $quantity")
        }
        for ((quantity, text) in spanish) {
            assertEquals(listOf("%1\$d", "%2\$s", "%3\$s"), placeholders(text), "Spanish $quantity")
        }
        assertTrue(english.getValue("other").contains("You pay exactly this."), "the plural does not say the price is exact")
    }

    @Test
    fun `the question says the price is exact and that a 2_12_4 contact can't see it until they update`() {
        assertTrue(en.getValue("paid_action_cost_exact").contains("You pay exactly this."))
        assertFalse(en.getValue("paid_action_cost_exact").contains("at least"), "the exact line says 'at least'")
        for (key in listOf("paid_action_shielded_attachment", "paid_action_shielded_attachment_memos")) {
            assertTrue(
                en.getValue(key).contains("A contact still on ZChat 2.12.4 or older can't see it until they update."),
                "$key does not carry the 2.12.4 sentence",
            )
            assertTrue(
                es.getValue(key).contains("Un contacto que siga en ZChat 2.12.4 o anterior no lo verá hasta que actualice."),
                "the Spanish $key does not carry the 2.12.4 sentence",
            )
            assertTrue(en.getValue(key).contains("never through the relays"), "$key does not say the relays are never used")
        }
    }

    @Test
    fun `the row reasons are ChatSendCopy constants backed by their resources`() {
        val wanted =
            mapOf(
                "ATTACHMENT_PRICE_CHANGED" to "chat_attachment_price_changed",
                "ATTACHMENT_NO_FUNDS_ROW" to "chat_attachment_no_funds_row",
                "ATTACHMENT_STILL_CONFIRMING" to "chat_attachment_still_confirming",
                "ATTACHMENT_PRICE_UNAVAILABLE" to "chat_attachment_price_unavailable",
            )
        for ((constant, key) in wanted) {
            val text =
                try {
                    ChatSendCopy::class.java.getField(constant).get(null) as String
                } catch (e: NoSuchFieldException) {
                    throw AssertionError("ChatSendCopy has no $constant", e)
                }
            assertEquals(en[key], text, "ChatSendCopy.$constant is not the English $key")
            assertEquals(ChatResourceText.stringId(key), ChatSendCopy.localizedResource(text), "ChatSendCopy.$constant shows no resource")
        }
    }

    @Test
    fun `PaidActionCopy names the Shielded attachment's question and its retry`() {
        fun action(name: String): PaidAction =
            PaidAction.entries.firstOrNull { it.name == name } ?: fail("PaidAction has no $name")
        assertEquals(ChatResourceText.stringId("paid_action_shielded_attachment"), PaidActionCopy.actionText(action("SHIELDED_ATTACHMENT")))
        assertEquals(
            ChatResourceText.stringId("paid_action_shielded_attachment_retry"),
            PaidActionCopy.actionText(action("SHIELDED_ATTACHMENT_RETRY")),
        )
    }

    @Test
    fun `the English photo literals and the Shielded relay route are gone from ChatViewModel`() {
        val source = ChatResourceText.source(cvm)
        for (literal in listOf(
            "Couldn’t send the photo — your ZEC is still confirming",
            "Couldn’t send the photo — a Shielded chat needs a little ZEC",
            "\"Photo send failed\"",
            "VAULT file send",
        )) {
            assertFalse(source.contains(literal), "ChatViewModel still holds '$literal'")
        }
        assertNotNull(ChatResourceText.stringId("chat_attachment_no_funds_title"))
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%\d+\$[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()
}
