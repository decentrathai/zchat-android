package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SR-A S-A10 (fix-all 2.13.0, train T5b; t5b SPECS §3 C-SRA-7): every Retry of a Shielded photo, file or voice note asks
 * the exact price again (PaidAction SHIELDED_ATTACHMENT_RETRY), and its question says what the code guarantees: the
 * earlier try was not paid (a row that may have been sent is never offered again), this sends it once more as one
 * Shielded transaction, and its link and key go inside it, never through the relays.
 *
 * At 97ba1a6a8 there is no such text: a Retry of a stored Shielded link is sent with no question at all.
 */
class ShieldedAttachmentRetryTextSaysNotPaidTest {
    private val key = "paid_action_shielded_attachment_retry"

    @Test
    fun `the retry question exists in English and Spanish and says the earlier try was not paid`() {
        val en = assertNotNull(ChatResourceText.strings("values")[key], "no English $key")
        val es = assertNotNull(ChatResourceText.strings("values-es")[key], "no Spanish $key")
        assertEquals(
            "Sending this again in a Shielded chat. The earlier try was not paid. This sends it once more, as one Shielded " +
                "transaction; its link and key go inside it, never through the relays.",
            en,
        )
        assertTrue(es.contains("El intento anterior no se pagó"), "the Spanish text does not say the earlier try was not paid: $es")
        assertTrue(es.contains("nunca por los relays"), "the Spanish text does not say the relays are never used: $es")
        assertNotEquals(en, es, "the Spanish text is the English one")
        assertEquals(placeholders(en), placeholders(es), "the placeholders differ")
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%\d+\$[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()
}
