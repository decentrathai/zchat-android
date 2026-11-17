package co.electriccoin.zcash.ui.screen.send

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 (W-136/W-137, batch B1, unit U1-wallet): the wallet texts the second QA found misleading, in English and
 * Spanish. Resources are read from res/ui by name, so this file compiles before the fix.
 *
 * - Q2-02 (ZC-1610): "Enter the recipient first: the network fee depends on it." reads as a fee estimate that never
 *   comes; it is the reason Send all is not offered yet.
 * - Q2-03 (ZC-1611): the empty memo counter reads 512/512 (bytes left put into a typed/max template).
 * - Q2-08 (ZC-1613): the typo "This addresses type works just like Bitcoin addresses" on a privacy sentence.
 * - Q2-10 (ZC-1600): "Your coins are being sent…" / "were successfully sent" before anything is confirmed.
 * - Q2-19 (ZC-273): the shield info promises "about 10 blocks (~2–3 minutes)"; the minutes are wrong.
 * - Q2-97 (ZC-1669): the Support ZCHAT send has no title and the prefilled donation address no label. The address
 *   itself must not change (SupportZchatAddressTest pins it; this file only checks it is still the build constant).
 */
class WalletTextsSayWhatHappensTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private fun plain(text: String): String =
        text
            .replace(Regex("""<xliff:g[^>]*>"""), "")
            .replace("</xliff:g>", "")
            .replace("\\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun english(key: String): String = plain(en[key] ?: fail("values has no $key"))

    private fun spanish(key: String): String = plain(es[key] ?: fail("values-es has no $key"))

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    private fun samePlaceholders(key: String) =
        assertEquals(placeholders(english(key)), placeholders(spanish(key)), "the placeholders of $key differ")

    private fun mainSource(): Map<String, String> =
        java.io
            .File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(ChatResourceText.repoRoot).path to it.readText() }

    // ---- Q2-02 ----

    @Test
    fun q2_02_the_send_all_reason_says_it_is_about_send_all() {
        val key = "send_all_reason_needs_recipient"
        assertEquals("Send all needs the recipient first: the network fee depends on it.", english(key))
        val text = spanish(key)
        // W-137 ZC-1685: the reviewed line is an instruction that names the button: "Para usar «Enviar todo», ...".
        assertTrue("«Enviar todo»" in text, "the Spanish reason must name Enviar todo: $text")
        assertTrue("destinatario" in text, text)
        assertFalse(text.startsWith("Primero ingresa el destinatario"), "the Spanish reason is the old one: $text")
    }

    // ---- Q2-03 ----

    @Test
    fun q2_03_the_memo_counter_counts_the_typed_bytes() {
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/view/SendView.kt")
        assertFalse(
            "MAX_MEMO_LENGTH_BYTES - memoState.byteSize" in view,
            "SendView passes the bytes LEFT into the typed/max counter, so an empty memo reads 512/512"
        )
        assertTrue(
            Regex("""R\.string\.send_memo_bytes_counter,\s*memoState\.byteSize,""").containsMatchIn(view),
            "the counter's first argument must be the typed bytes (memoState.byteSize), so an empty memo reads 0/512"
        )
    }

    // ---- Q2-08 ----

    @Test
    fun q2_08_the_transparent_address_info_has_no_typo() {
        val text = english("receive_info_transparent_bullet_1")
        assertFalse("addresses type" in text, text)
        assertTrue(
            text.startsWith("This address type works like a Bitcoin address and offers NO PRIVACY."),
            text
        )
        assertTrue("public and visible on the blockchain" in text, "the rest of the sentence is unchanged: $text")
    }

    // ---- Q2-10 ----

    @Test
    fun q2_10_the_send_result_never_says_successfully_sent_before_a_confirmation() {
        val keys =
            listOf(
                "send_confirmation_sending_subtitle",
                "send_confirmation_sending_subtitle_transparent",
                "send_confirmation_success_subtitle_transparent",
                "send_confirmation_success_subtitle",
                "send_confirmation_swapping_subtitle_transparent",
            )
        for (key in keys) {
            val english = english(key).lowercase()
            val spanish = spanish(key).lowercase()
            assertFalse("coins" in english, "English $key still says coins: $english")
            assertFalse("successfully" in english, "English $key says successfully: $english")
            assertFalse("monedas" in spanish, "Spanish $key still says monedas: $spanish")
            assertFalse("exitosamente" in spanish, "Spanish $key says exitosamente: $spanish")
            samePlaceholders(key)
        }

        val sending = english("send_confirmation_sending_subtitle")
        assertTrue(sending.startsWith("Your ZEC is on its way to"), sending)
        assertTrue("%1\$s" in sending, "the sending line still names the recipient: $sending")
        assertTrue(spanish("send_confirmation_sending_subtitle").startsWith("Tu ZEC va en camino a"))

        val sent = english("send_confirmation_success_subtitle")
        assertTrue("Sent to the Zcash network" in sent, sent)
        assertTrue("not confirmed yet" in sent, "the sent line must say it is not confirmed yet: $sent")
        val enviado = spanish("send_confirmation_success_subtitle")
        assertTrue("Enviado a la red Zcash" in enviado, enviado)
        assertTrue("aún sin confirmar" in enviado, enviado)

        assertEquals("Your ZEC is being shielded", english("send_confirmation_sending_subtitle_transparent"))
        assertEquals(
            "Your ZEC is being shielded. Sent to the Zcash network — not confirmed yet.",
            english("send_confirmation_success_subtitle_transparent"),
        )
        assertEquals("Tu ZEC se está protegiendo", spanish("send_confirmation_sending_subtitle_transparent"))
        assertEquals(
            "Tu ZEC se está protegiendo. Enviado a la red Zcash — aún sin confirmar.",
            spanish("send_confirmation_success_subtitle_transparent"),
        )
    }

    // ---- Q2-19 ----

    @Test
    fun q2_19_the_shield_info_names_the_blocks_and_no_minutes() {
        val key = "home_info_transparent_message"
        val english = english(key)
        assertTrue("about 10 blocks" in english, english)
        assertFalse("minute" in english.lowercase(), "the shield info still promises minutes: $english")
        assertFalse("~" in english, english)
        val spanish = spanish(key)
        assertTrue("10 bloques" in spanish, "the Spanish shield info must name the 10 blocks too: $spanish")
        assertFalse("minuto" in spanish.lowercase(), spanish)
        samePlaceholders(key)
    }

    // ---- Q2-97 ----

    @Test
    fun q2_97_the_support_send_has_a_title_and_names_the_prefilled_address() {
        val helperKey =
            en.entries.firstOrNull { plain(it.value) == "ZChat donation address (prefilled)" }?.key
                ?: fail("no string reads \"ZChat donation address (prefilled)\"")
        val helperEs = spanish(helperKey)
        assertTrue("ZChat" in helperEs && "donaci" in helperEs, "the Spanish label must name ZChat's donations: $helperEs")
        assertFalse(helperEs == english(helperKey), "the Spanish label is the English text")

        val sendSources = mainSource().filterKeys { "/screen/send/" in it || "/screen/more/" in it }
        assertTrue(
            sendSources.values.any { "R.string.$helperKey" in it },
            "no Send or More file shows R.string.$helperKey"
        )

        val titleKeys = en.filterValues { plain(it) == "Support ZCHAT" }.keys
        assertTrue(titleKeys.isNotEmpty(), "no string reads Support ZCHAT")
        assertTrue(
            mainSource().filterKeys { "/screen/send/" in it }.values.any { src -> titleKeys.any { "R.string.$it" in src } },
            "the Send screen never shows a Support ZCHAT title (keys: $titleKeys)"
        )

        val more = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/more/MoreVM.kt")
        assertNotNull(
            Regex("""recipientAddress\s*=\s*BuildConfig\.ZCHAT_DONATION_ADDRESS""").find(more),
            "the support send must still prefill the build's donation address, never a typed one"
        )
    }
}
