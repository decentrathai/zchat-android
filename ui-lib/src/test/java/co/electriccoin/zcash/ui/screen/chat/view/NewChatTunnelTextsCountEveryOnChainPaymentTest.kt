package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.automaticSetupTransactions
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-632 (fix-all 2.13.0, train T6b) — the texts that tell a user what Tunnel costs count one on-chain payment where
 * the code makes more.
 *
 * At the base:
 * - New Chat, the tag under the Tunnel chip: "1× on-chain, then free"; the caption: "First message is a one-time
 *   on-chain handshake (small ZEC fee)"; the "Open needs your contact's code" dialog: "Use Tunnel — one small on-chain
 *   handshake, then free". The code pays the first message as its own transaction and then the handshake as one or two
 *   more (our key exchange; our relay identity on-chain when it cannot go over the relays): two or three transactions.
 * - In a chat, the Tunnel row of "Choose how each chat travels" and the note after a switch to Tunnel: "it costs one
 *   on-chain payment". The switch's own question counts up to three (the mode change when the contact cannot be reached
 *   over the relays, our key exchange, our relay identity).
 *
 * Wanted: each text states the count the code gives, in English and in Spanish, and the floor of one transaction
 * ([onChainSendMinimumCost]) where it names a price. The counts and the floor are computed here from the code, so a
 * change of the code's count turns these texts red. Texts only: no test here drives a send.
 *
 * QA2 (W-137, Q2-23 / Q2-29, ZC-1622 / ZC-983): the New Chat cue counts in figures ("2–3 on-chain payments (first
 * message, then handshake)") so it stays short, and a switch in a chat counts "two or three", not "up to three";
 * NewChatAndModeTextsSayWhatPaysTest pins the full sentences.
 */
class NewChatTunnelTextsCountEveryOnChainPaymentTest {
    // As getString returns them: "\\n" in the XML is a line break.
    private val en = ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") }
    private val es = ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") }

    private val peer = "peer"

    private val keyExchange = listOf(QueuedAutomaticSetup(AutomaticSetupKind.KEX, peer))

    /** A key exchange whose relay identity goes free over the relays, and one whose relay identity goes on-chain. */
    private val handshakeLeast = automaticSetupTransactions(keyExchange, emptyList()) { false }
    private val handshakeMost = automaticSetupTransactions(keyExchange, emptyList())

    /** New Chat in Tunnel: the first message is its own transaction, then the handshake. */
    private val newChatLeast = 1 + handshakeLeast
    private val newChatMost = 1 + handshakeMost

    /** A switch to Tunnel in a chat with no finished handshake: the mode change itself, then the handshake. */
    private val switchLeast = 1 + handshakeLeast
    private val switchMost = 1 + handshakeMost

    /** The least one on-chain chat transaction costs, as the texts write it ("0.00017"; Spanish with a comma). */
    private val floorEn: String =
        BigDecimal(onChainSendMinimumCost().value).movePointLeft(8).stripTrailingZeros().toPlainString()
    private val floorEs: String = floorEn.replace('.', ',')

    private val wordsEn = mapOf(1 to "one", 2 to "two", 3 to "three")
    private val wordsEs = mapOf(1 to "una", 2 to "dos", 3 to "tres")

    private fun wordEn(count: Int): String = assertNotNull(wordsEn[count], "no English word for $count: the code's count changed")

    private fun wordEs(count: Int): String = assertNotNull(wordsEs[count], "no Spanish word for $count: the code's count changed")

    private fun text(
        strings: Map<String, String>,
        key: String,
    ): String = assertNotNull(strings[key], "no $key")

    /** Every key this batch corrects. */
    private val keys =
        listOf(
            "new_chat_mode_tag_tunnel",
            "new_chat_mode_blurb_tunnel",
            "new_chat_open_needs_code_body",
            "chat_mode_note_tunnel_body",
        )

    @Test
    fun the_code_counts_two_or_three_transactions_for_a_new_tunnel_chat() {
        // Control (green before and after): what the texts below are held to.
        assertEquals(1, handshakeLeast, "a key exchange whose relay identity goes over the relays is one transaction")
        assertEquals(2, handshakeMost, "a key exchange whose relay identity goes on-chain is two transactions")
        assertEquals(2, newChatLeast)
        assertEquals(3, newChatMost)
        assertEquals(2, switchLeast)
        assertEquals(3, switchMost)
        assertEquals("0.00017", floorEn)
    }

    @Test
    fun the_tunnel_tag_names_the_codes_count_and_never_one() {
        for ((language, strings) in listOf("English" to en, "Spanish" to es)) {
            val tag = text(strings, "new_chat_mode_tag_tunnel")
            assertFalse("1×" in tag, "$language tag still counts one on-chain payment: $tag")
            assertTrue(
                tag.startsWith("$newChatLeast–$newChatMost "),
                "$language tag must start with the code's count, $newChatLeast–$newChatMost: $tag",
            )
        }
    }

    @Test
    fun the_tunnel_tag_fits_its_chip() {
        // Control (green before and after): the old Spanish tag of 29 characters already filled the chip on the HONOR.
        for (strings in listOf(en, es)) {
            val tag = text(strings, "new_chat_mode_tag_tunnel")
            assertTrue(tag.length <= TAG_MAX_CHARS, "the tag is ${tag.length} characters, more than $TAG_MAX_CHARS: $tag")
        }
    }

    @Test
    fun the_english_caption_names_each_payment_and_the_floor() {
        val caption = text(en, "new_chat_mode_blurb_tunnel")
        assertFalse("one-time" in caption, "the caption still says the cost is one-time: $caption")
        assertFalse("First message is a" in caption, "the caption still says the first message is the handshake: $caption")
        // QA2 Q2-23 (W-137): the short cue counts in figures, "2–3", so "Send Message" stays on the screen.
        val count = "$newChatLeast–$newChatMost on-chain payments"
        assertTrue(count in caption, "the caption must say \"$count\": $caption")
        assertTrue("(first message, then handshake)" in caption, "the caption must name both payments: $caption")
        assertTrue("at least $floorEn ZEC" in caption, "the caption must name the floor, at least $floorEn ZEC: $caption")
        assertEquals(caption, ConversationMode.TUNNEL.composeBlurb(), "composeBlurb() must stay equal to the resource")
    }

    @Test
    fun the_spanish_caption_names_each_payment_and_the_floor() {
        val caption = text(es, "new_chat_mode_blurb_tunnel")
        assertFalse("único" in caption, "the caption still says the cost is one-time: $caption")
        assertFalse("El primer mensaje es un" in caption, "the caption still says the first message is the handshake: $caption")
        val count = "$newChatLeast–$newChatMost pagos en la cadena"
        assertTrue(count in caption, "the caption must say «$count»: $caption")
        assertTrue(
            "(primer mensaje y luego el intercambio de claves)" in caption,
            "the caption must name both payments: $caption",
        )
        assertTrue("al menos $floorEs ZEC" in caption, "the caption must name the floor, al menos $floorEs ZEC: $caption")
    }

    @Test
    fun the_open_needs_code_dialog_counts_tunnels_payments() {
        val english = text(en, "new_chat_open_needs_code_body")
        assertFalse("one small on-chain handshake" in english, "the dialog still counts one payment: $english")
        val countEn = "${wordEn(newChatLeast)} or ${wordEn(newChatMost)} on-chain payments"
        assertTrue(countEn in english, "the dialog must say \"$countEn\": $english")

        val spanish = text(es, "new_chat_open_needs_code_body")
        assertFalse("un pequeño intercambio de claves" in spanish, "the dialog still counts one payment: $spanish")
        val countEs = "${wordEs(newChatLeast)} o ${wordEs(newChatMost)} pagos en la cadena"
        assertTrue(countEs in spanish, "the dialog must say «$countEs»: $spanish")
    }

    @Test
    fun the_chat_side_tunnel_texts_count_the_switchs_payments() {
        // QA2 Q2-29 (W-137): "two or three", as New Chat counts, and the exact number is shown before paying.
        val countEn = "${wordEn(switchLeast)} or ${wordEn(switchMost)} on-chain payments, each at least $floorEn ZEC"
        val intro = MODE_INTRO_TUNNEL_BODY
        assertFalse("one on-chain payment" in intro, "\"Choose how each chat travels\" still counts one payment: $intro")
        assertTrue(countEn in intro, "\"Choose how each chat travels\" must say \"$countEn\": $intro")

        val english = text(en, "chat_mode_note_tunnel_body")
        assertFalse("one on-chain payment" in english, "the note after a switch still counts one payment: $english")
        assertTrue(countEn in english, "the note after a switch must say \"$countEn\": $english")
        val (_, pinned) = assertNotNull(modeSecurityNoteCopy(ConversationMode.TUNNEL))
        assertEquals(pinned, english, "the note's English resource must stay equal to modeSecurityNoteCopy")

        val spanish = text(es, "chat_mode_note_tunnel_body")
        val countEs = "${wordEs(switchLeast)} o ${wordEs(switchMost)} pagos en la cadena, cada uno de al menos $floorEs ZEC"
        assertFalse("un pago en la cadena" in spanish, "the note after a switch still counts one payment: $spanish")
        assertTrue(countEs in spanish, "the note after a switch must say «$countEs»: $spanish")
    }

    @Test
    fun no_text_of_either_language_keeps_a_count_of_one_for_tunnel() {
        val falseCounts =
            listOf(
                "1×",
                "one-time on-chain",
                "one small on-chain handshake",
                "one on-chain payment",
                "único en la cadena",
                "un pequeño intercambio de claves en la cadena",
                "un pago en la cadena",
            )
        val found =
            (en.entries.map { "values" to it } + es.entries.map { "values-es" to it })
                .filter { (_, entry) -> "Tunnel" in entry.value || entry.key in keys }
                .flatMap { (dir, entry) ->
                    falseCounts.filter { it in entry.value }.map { "$dir ${entry.key}: \"$it\"" }
                }
        assertTrue(found.isEmpty(), "texts that still count one on-chain payment for Tunnel:\n" + found.joinToString("\n"))
    }

    @Test
    fun the_corrected_spanish_texts_use_the_reviewed_terms() {
        // Control (green before and after): references/es.md, as SpanishReviewedTermsTest.
        val retired = listOf("cartera", "on-chain", "relés", "saludo", "frase semilla", "monedero", "coste")
        for (key in keys) {
            val spanish = text(es, key)
            val hits = retired.filter { spanish.contains(it, ignoreCase = true) }
            assertTrue(hits.isEmpty(), "$key uses retired Spanish terms $hits: $spanish")
            assertFalse(spanish == en[key], "$key is English in the Spanish file")
        }
    }

    private companion object {
        /** The longest tag the Tunnel chip shows uncut on a 360 dp screen (the HONOR dump of SR-D #4). */
        const val TAG_MAX_CHARS = 29
    }
}
