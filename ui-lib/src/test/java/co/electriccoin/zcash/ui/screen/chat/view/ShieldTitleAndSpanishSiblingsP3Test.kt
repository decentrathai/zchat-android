package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 P3 polish, Spanish and shield-title items from the Fable review of qa2/es c79c8c200.
 *
 * - ZC-1708 (medium): the shield success screen is shown right after the broadcast (TransactionProgressVM on
 *   SubmitResult.Success, before any confirmation) under the title "Shielded!" / "¡Protegido!" while the line below says
 *   "Your ZEC is being shielded". The title claims the shielding is done. Wanted, EN + ES: a "sent"-style title (the
 *   broadcast is what happened), never "Shielded" / "protegido"; the subtitle stays pending wording.
 * - ZC-1711: the ES reconnect line says "transacción de intercambio de claves" while the other ES lines for the same
 *   on-chain handshake payment and the consent prompt say "transacción de enlace".
 * - ZC-1712: two sibling ES lines keep the faults GPT's review fixed elsewhere: `balance_action_shield_message` says
 *   "dentro de la wallet" (the app says "billetera"), and `send_confirmation_waiting_subtitle` says "ya no podrá salir" (the
 *   reviewed `transaction_detail_waiting_body` says "ya no podrá confirmarse"; a transaction is confirmed, not sent out).
 *
 * ZC-1709 (the onboarding Spanish subtitle back under the 130-character cap) is pinned by
 * OnboardingSubtitleNamesWhatIsPaidTest.MAX_ES with the exact line in NewChatAndModeTextsSayWhatPaysTest and
 * SpanishReviewFixesW137Test. The Spanish lines are agent drafts for the train's REVIEW.md list (app-texts rule 9).
 */
class ShieldTitleAndSpanishSiblingsP3Test {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun plain(text: String): String =
        text
            .replace(Regex("""<xliff:g[^>]*>"""), "")
            .replace("</xliff:g>", "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    // ---- ZC-1708 ----

    @Test
    fun `the shield success title is shown at the broadcast, so it says sent and never shielded`() {
        val key = "send_confirmation_success_title_transparent"
        val english = plain(assertNotNull(en[key], "values has no $key"))
        val spanish = plain(assertNotNull(es[key], "values-es has no $key"))
        assertFalse("shield" in english.lowercase(), "the English title still says shielded: $english")
        assertFalse("protegid" in spanish.lowercase(), "the Spanish title still says protegido: $spanish")
        assertTrue(
            Regex("""^(Sent|Submitted|Sending)\b""").containsMatchIn(english),
            "the English title is not a \"Sent!\"-style broadcast title: $english",
        )
        assertTrue(
            Regex("""^¡?(Enviad|Enviando)""").containsMatchIn(spanish),
            "the Spanish title is not a \"¡Enviado!\"-style broadcast title: $spanish",
        )
    }

    @Test
    fun `the line under the title keeps saying the shielding is still happening`() {
        assertEquals(
            "Your ZEC is being shielded. Sent to the Zcash network — not confirmed yet.",
            plain(en.getValue("send_confirmation_success_subtitle_transparent")),
        )
        assertEquals(
            "Tu ZEC se está protegiendo. Enviado a la red Zcash — aún sin confirmar.",
            plain(es.getValue("send_confirmation_success_subtitle_transparent")),
        )
    }

    @Test
    fun `the shield title is the one the broadcast screen and the progress view model read`() {
        val vm =
            ChatResourceText.source(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactionprogress/TransactionProgressVM.kt",
            )
        assertTrue(
            Regex("""is ShieldTransactionProposal\s*->\s*stringRes\(R\.string\.send_confirmation_success_subtitle_transparent\)""")
                .containsMatchIn(vm),
            "the shield subtitle is no longer the pending one at the broadcast",
        )
        assertTrue("R.string.send_confirmation_success_title_transparent" in vm, "the progress view model no longer reads the shield title")
    }

    // ---- ZC-1711 ----

    @Test
    fun `the Spanish reconnect line names the handshake payment as the rest of the app does`() {
        val reconnect = plain(assertNotNull(es["chat_menu_reconnect_subtitle"], "values-es has no chat_menu_reconnect_subtitle"))
        assertTrue("transacción de enlace" in reconnect, "the reconnect line does not say \"transacción de enlace\": $reconnect")
        val offenders =
            es.filterValues { Regex("""transacci(ón|ones) de intercambio de claves""").containsMatchIn(plain(it)) }.keys
        assertEquals(
            emptySet<String>(),
            offenders,
            "Spanish lines still call the handshake payment \"transacción de intercambio de claves\"",
        )
        // The term lives in <string> entries and in <plurals> items (ChatResourceText.strings() reads only the former).
        val inStrings = es.count { "transacción de enlace" in it.value || "transacciones de enlace" in it.value }
        val inPlurals =
            ChatResourceText.plurals("values-es").values.sumOf { items ->
                items.count { "transacción de enlace" in it.value || "transacciones de enlace" in it.value }
            }
        assertTrue(
            inStrings + inPlurals >= 10,
            "the established Spanish term \"transacción de enlace\" is no longer the one the app uses " +
                "(strings=$inStrings, plurals items=$inPlurals)",
        )
    }

    // ---- ZC-1712 ----

    @Test
    fun `the Spanish shield dialog says billetera, not wallet`() {
        val key = "balance_action_shield_message"
        val spanish = plain(assertNotNull(es[key], "values-es has no $key"))
        assertFalse("wallet" in spanish.lowercase(), "$key still says \"wallet\": $spanish")
        assertTrue("billetera" in spanish, "$key does not say \"billetera\": $spanish")
        assertEquals(placeholders(en.getValue(key)), placeholders(es.getValue(key)), "$key: the placeholders differ")
        assertFalse("dentro de la wallet" in spanish, spanish)
    }

    @Test
    fun `the Spanish waiting subtitle says a transaction is confirmed, not that it cannot go out`() {
        val key = "send_confirmation_waiting_subtitle"
        val spanish = plain(assertNotNull(es[key], "values-es has no $key"))
        assertFalse("podrá salir" in spanish, "$key still says \"ya no podrá salir\": $spanish")
        assertTrue("confirmarse" in spanish, "$key does not say the transaction can no longer be confirmed: $spanish")
        assertEquals(setOf("%1\$s", "%2\$d"), placeholders(spanish), "$key must name the block and the minutes")
        assertTrue("no la envíes de nuevo" in spanish.lowercase(), "$key lost its warning not to send it again: $spanish")
        assertTrue("podrás enviarla de nuevo" in spanish, "$key lost the later permission to send it again: $spanish")
    }
}
