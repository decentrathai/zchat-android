package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.deletewallet.keepDataWarning
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P2h — honest text, second pass (PART2_SPEC "## P2h"). English and Spanish in step.
 *
 * - Open and Tunnel chats go through three Nostr relays (NostrRelayPool.DEFAULT_RELAYS, one of them ZChat's),
 *   so "No server stores your messages" (empty chat list) and "No Central Server ... directly to the
 *   blockchain" (onboarding) are false for the free modes.
 * - "Maximum metadata privacy": the server a wallet syncs from sees the sender's IP address, so Shielded is
 *   the most private mode, not "maximum".
 * - The mode-switch dialog promises each side can stay in its own mode; a contact on ZChat 2.12.4 or older
 *   does not see Shielded messages until they update.
 * - A failed outgoing payment request is headed "Payment Request Sent".
 * - Delete Wallet: with "keep" ticked the warning said the data "cannot be recovered", and the warning never
 *   changed with the box. Since P2e the kept data comes back when the same recovery phrase is restored, and
 *   a different phrase no longer carries the chats over (they are set aside).
 * - The send notices ChatViewModel reports in English (P2a "may already have been sent", SR-2 "not
 *   confirmed yet", the Retry refusals, and the new wait-for-a-block notice) have a Spanish translation.
 */
class HonestCopySecondPassTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    // ---- privacy claims ----

    @Test
    fun empty_chat_list_does_not_say_no_server_stores_messages() {
        val body = emptyChatPrivacyBody().lowercase()
        assertFalse("no server stores" in body, body)
        assertFalse("not even us" in body, body)
        assertTrue("relay" in body, "the free modes' relays must be named: $body")
    }

    @Test
    fun onboarding_does_not_claim_no_central_server_in_english_or_spanish() {
        for (dir in listOf("values", "values-es")) {
            val strings = strings("onboarding", dir)
            val title = assertNotNull(strings["onboarding_how_no_server_title"], "$dir: title missing")
            val desc = assertNotNull(strings["onboarding_how_no_server_desc"], "$dir: description missing")
            val text = "$title $desc".lowercase()
            assertFalse("no central server" in text, "$dir: $text")
            assertFalse("sin servidor central" in text, "$dir: $text")
            assertFalse("directly to the blockchain" in text, "$dir: $text")
            assertFalse("directamente a la blockchain" in text, "$dir: $text")
            assertTrue("relay" in text, "$dir: the relays must be named: $text")
        }
    }

    @Test
    fun no_maximum_metadata_privacy_claim() {
        val texts =
            listOf(MODE_INTRO_SHIELDED_BODY, modeSwitchRequestBody("Ana", "Shielded", "Tunnel")) +
                ConversationMode.entries.map { modeBlurb(it).first }
        for (t in texts) {
            val lower = t.lowercase()
            assertFalse("maximum metadata privacy" in lower, t)
            assertFalse("max metadata privacy" in lower, t)
            assertFalse("maximum privacy" in lower, t)
        }
    }

    @Test
    fun mode_switch_dialog_states_the_version_limit() {
        val body = modeSwitchRequestBody("Ana", "Shielded", "Tunnel")
        assertTrue("2.13" in body, "the stay-in-your-own-mode promise must name the version it needs: $body")
    }

    // ---- payment request header ----

    @Test
    fun a_failed_payment_request_is_not_headed_sent() {
        val header = paymentRequestHeader(isOutgoing = true, failed = true)
        assertFalse("request sent" in header.lowercase(), header)
        assertTrue("not sent" in header.lowercase(), header)
        assertEquals("Payment Request Sent", paymentRequestHeader(isOutgoing = true, failed = false))
        assertEquals("Payment Requested", paymentRequestHeader(isOutgoing = false, failed = false))
    }

    // ---- Delete Wallet ----

    @Test
    fun delete_wallet_keep_warning_matches_the_box_in_english_and_spanish() {
        assertNotEquals(keepDataWarning(true), keepDataWarning(false), "the warning must change with the box")
        val checked = resourceName(keepDataWarning(true))
        val unchecked = resourceName(keepDataWarning(false))
        val en = strings("delete_wallet", "values")
        val es = strings("delete_wallet", "values-es")
        val enChecked = en.getValue(checked).lowercase()
        val esChecked = es.getValue(checked).lowercase()
        assertFalse("cannot be recovered" in enChecked, enChecked)
        assertFalse("no se podrán recuperar" in esChecked || "no se pueden recuperar" in esChecked, esChecked)
        assertTrue("same recovery phrase" in enChecked, enChecked)
        assertTrue("misma frase de recuperación" in esChecked, esChecked)
        assertTrue("deleted" in en.getValue(unchecked).lowercase(), en.getValue(unchecked))
        assertTrue("eliminar" in es.getValue(unchecked).lowercase(), es.getValue(unchecked))
        val enTitle = en.getValue("delete_wallet_checkbox_title").lowercase()
        assertFalse("would carry these chats" in enTitle, "P2e: a different phrase no longer inherits the chats: $enTitle")
        val esTitle = es.getValue("delete_wallet_checkbox_title").lowercase()
        assertFalse("pasarían a la nueva billetera" in esTitle, esTitle)
    }

    // ---- send notices in Spanish ----

    @Test
    fun every_send_notice_has_an_english_and_a_spanish_resource() {
        val notices =
            listOf(
                SubmitAttemptProof.MAY_HAVE_BEEN_SENT,
                SubmitAttemptProof.ROW_NOTICE,
                ChatSendCopy.MESSAGE_UNCONFIRMED,
                ChatSendCopy.PAYMENT_REQUEST_UNCONFIRMED,
                ChatSendCopy.PAYMENT_UNCONFIRMED,
                ChatSendCopy.REACTION_UNCONFIRMED,
                ChatSendCopy.RETRY_MODE_CHANGED,
                ChatSendCopy.RETRY_STORAGE_FAILED,
                ChatSendCopy.RETRY_ALREADY_CLAIMED,
                ChatSendCopy.PAYMENT_REQUEST_NOT_CONNECTED,
                ChatSendCopy.QUEUED_WAITING_FOR_BLOCK,
            )
        val en = allStrings("values")
        val es = allStrings("values-es")
        for (notice in notices) {
            val id = assertNotNull(ChatSendCopy.localizedResource(notice), "no translation for: $notice")
            val name = resourceName(id)
            assertEquals(notice, en[name], "English resource $name must be the exact text the app reports")
            val spanish = assertNotNull(es[name], "Spanish resource $name missing")
            assertTrue(spanish.isNotBlank() && spanish != notice, "Spanish resource $name is not translated")
        }
        // Round 8 D8: "Message sent" is a send notice with its own resource now; any text the map does not know stays.
        assertEquals(null, ChatSendCopy.localizedResource("synthetic text no screen shows"), "unrelated text keeps its English")
    }

    @Test
    fun a_failed_free_payment_request_points_to_retry() {
        assertTrue("retry" in ChatSendCopy.PAYMENT_REQUEST_NOT_CONNECTED.lowercase(), ChatSendCopy.PAYMENT_REQUEST_NOT_CONNECTED)
    }

    // ---- helpers ----

    private fun resourceName(id: Int): String =
        assertNotNull(
            R.string::class.java.fields
                .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
                ?.name,
            "no string resource with id $id",
        )

    private fun strings(
        module: String,
        dir: String
    ): Map<String, String> =
        File(resRoot, "$module/$dir")
            .listFiles { f -> f.extension == "xml" }
            .orEmpty()
            .fold(emptyMap()) { acc, f -> acc + parse(f.readText()) }

    private fun allStrings(dir: String): Map<String, String> =
        resRoot
            .listFiles()
            .orEmpty()
            .fold(emptyMap()) { acc, module -> acc + strings(module.name, dir) }

    private fun parse(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
            }
}
