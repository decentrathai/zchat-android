package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PendingSendEstimate
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2-7 (c), plan step p2-a46ef68ede15 (TXP-F14), with the P2h leftovers it overlaps. English and Spanish.
 *
 * At bdaa5103b:
 * - the two one-time "Message Cost" dialogs (AndroidChat) say a message costs "typically 0.00001 ZEC per
 *   message chunk, plus network fees"; a short Shielded message costs at least 0.00017 ZEC (FINAL_PLAN §2.6:
 *   the network fee, a 0.00001 ZEC ZChat fee and 0.00001 ZEC delivered with the message);
 * - the shortfall error says "switch this chat to Tunnel/Open ... to message free", but Tunnel needs a paid
 *   handshake and Open works only with a contact whose chat key you have; the amount-too-small error says the
 *   same;
 * - a Tunnel message waiting for the contact's handshake says "Waiting for secure connection…" for ever;
 * - an on-chain send counts down "~75s" and then says "any moment…" for as long as it waits (a queued send
 *   waits for its ZEC to confirm, minutes, and gives up after up to four five-minute waits);
 * - Retry never says why the send failed (RetryShowsFailureReasonTest covers the ViewModel side);
 * - "maximum privacy", "No server ever sees your content", "the private network" and "~50 messages"
 *   (0.005 ZEC pays for at most 29 short Shielded messages) remain in onboarding and chat.
 */
class HonestCostAndWaitCopyTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        assertNotNull(dir, "repo root not found")
    }

    private fun source(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/$path").readText()

    // ---- cost ----

    @Test
    fun the_message_cost_dialogs_quote_the_real_floor() {
        val chat = source("chat/AndroidChat.kt")
        assertFalse("0.00001 ZEC per message chunk" in chat, "a Message Cost dialog still quotes 0.00001 ZEC per message")
        assertEquals(
            2,
            Regex("""R\.string\.chat_message_cost_body""").findAll(chat).count(),
            "both Message Cost dialogs must show chat_message_cost_body",
        )
        val en = common("values").getValue("chat_message_cost_body")
        val es = common("values-es").getValue("chat_message_cost_body")
        assertTrue("at least 0.00017 zec" in en.lowercase(), en)
        assertTrue("0.00001 zec zchat fee" in en.lowercase(), en)
        assertTrue("al menos 0,00017 zec" in es.lowercase(), es)
    }

    @Test
    fun a_shortfall_does_not_promise_free_messages_by_switching_modes() {
        val msg = CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE
        assertFalse("message free" in msg.lowercase(), msg)
        assertFalse("tunnel/open" in msg.lowercase(), msg)
        assertTrue(msg.startsWith("Insufficient balance"), "the error mapper keys on this prefix: $msg")
        assertTrue("0.00017" in msg, msg)
        assertEquals(msg, msg.toZchatUserMessage("fallback"), "the mapper must pass the shortfall text through")
        assertSpanish(msg)

        val tooSmall = IllegalStateException("AmountTooSmall (value=0)").toZchatUserMessage("fallback")
        assertFalse("for free" in tooSmall.lowercase(), tooSmall)
        assertFalse("tunnel/open" in tooSmall.lowercase(), tooSmall)
    }

    @Test
    fun message_count_figures_match_the_floor() {
        val detail = source("chat/view/ChatDetailView.kt")
        assertFalse("~50 messages" in detail, "Send Welcome ZEC still promises ~50 messages for 0.005 ZEC")
        assertTrue("R.string.chat_welcome_zec_hint" in detail, "Send Welcome ZEC must show chat_welcome_zec_hint")
        for (dir in listOf("values", "values-es")) {
            val hint = common(dir).getValue("chat_welcome_zec_hint")
            assertTrue("25" in hint && "50" !in hint, "$dir: $hint")
            val friend = strings("onboarding", dir)["onboarding_getzec_friend_desc"] ?: fail("$dir: onboarding_getzec_friend_desc")
            assertFalse("~50" in friend || "50 " in friend, "$dir: $friend")
            assertTrue("25" in friend, "$dir: $friend")
        }
    }

    // ---- waiting ----

    @Test
    fun a_tunnel_wait_says_what_to_do_after_ten_minutes() {
        val detail = source("chat/view/ChatDetailView.kt")
        assertFalse("\"Waiting for secure connection…\"" in detail, "the Tunnel wait still never changes")
        // R10A-2 (F-R9P-3): the screen picks every pending line with rowLabelRes, which gives a Tunnel wait tunnelWaitLabelRes.
        assertTrue("rowLabelRes(" in detail, "ChatDetailView must pick the pending line with PendingSendEstimate.rowLabelRes")
        val early = labelName("tunnelWaitLabelRes", 0L)
        assertEquals(early, labelName("tunnelWaitLabelRes", 599L))
        val stalled = labelName("tunnelWaitLabelRes", 600L)
        for (elapsed in listOf(0L, 599L, 600L)) {
            assertEquals(
                labelName("tunnelWaitLabelRes", elapsed),
                resourceName(PendingSendEstimate.rowLabelRes("tunnel-wait-1", null, elapsed)),
                "a Tunnel wait row after ${elapsed}s must show the Tunnel wait label",
            )
        }
        assertNotEquals(early, stalled, "after 10 minutes the Tunnel wait must change")
        val en = common("values").getValue(stalled)
        val es = common("values-es").getValue(stalled)
        assertTrue("%1\$d min" in en && "open zchat" in en.lowercase() && "2.13" in en, en)
        assertTrue("%1\$d min" in es && "abr" in es.lowercase() && "2.13" in es, es)
    }

    @Test
    fun an_onchain_send_has_no_block_countdown_and_says_what_to_do_when_slow() {
        val estimate = source("chat/model/PendingSendEstimate.kt")
        assertFalse("any moment" in estimate, "the pending label still says 'any moment…'")
        assertFalse("s left" in estimate, "the pending label still counts down seconds")
        val usual = labelName("labelRes", 0L)
        assertEquals(usual, labelName("labelRes", 179L))
        val slow = labelName("labelRes", 180L)
        assertNotEquals(usual, slow, "after 3 minutes the on-chain wait must change")
        for (dir in listOf("values", "values-es")) {
            val strings = common(dir)
            assertFalse("75" in strings.getValue(usual), "$dir: ${strings.getValue(usual)}")
            assertTrue("%1\$d min" in strings.getValue(slow), "$dir: ${strings.getValue(slow)}")
        }
        assertTrue("keep zchat open" in common("values").getValue(slow).lowercase())
        assertTrue("retry" in common("values").getValue(slow).lowercase())
    }

    // ---- Retry reason ----

    @Test
    fun retry_shows_the_failure_reason() {
        val detail = source("chat/view/ChatDetailView.kt")
        // R4-6 (12): the label became a string resource, so the anchor is its name, not its English.
        val retry = detail.substringBefore("contentDescription = stringResource(R.string.chat_retry_sending)")
        assertTrue("failureReasonFor(" in retry.takeLast(3000), "the Retry row does not show why the send failed")

        val method =
            ChatSendCopy::class.java.methods.firstOrNull { it.name == "failureReasonFor" }
                ?: fail("ChatSendCopy.failureReasonFor(message) is missing")
        val failed =
            ChatMessage(
                id = "pending_synthetic",
                txId = null,
                text = "synthetic",
                timestamp = Instant.EPOCH,
                isOutgoing = true,
                peerAddress = "usynthetic",
                status = MessageStatus.FAILED,
            )
        val unknown = method.invoke(ChatSendCopy, failed) as String?
        assertNotNull(unknown, "a failed message with no recorded reason shows nothing")
        assertSpanish(unknown)
        assertNull(method.invoke(ChatSendCopy, failed.copy(status = MessageStatus.SENT)), "a sent message shows a reason")
        assertNull(
            method.invoke(ChatSendCopy, failed.copy(submitStartedAtMillis = 1L)),
            "a possibly-sent message already shows its own notice; no second reason",
        )
    }

    @Test
    fun every_new_send_failure_reason_has_spanish() {
        val reasons =
            ChatSendCopy::class.java.declaredFields
                .filter { it.name.startsWith("FAILED_") && it.type == String::class.java }
                .map {
                    it.isAccessible = true
                    it.get(null) as String
                }
        assertTrue(reasons.size >= 4, "expected the failure reasons as ChatSendCopy.FAILED_* constants: $reasons")
        reasons.forEach(::assertSpanish)
    }

    // ---- P2h leftovers ----

    @Test
    fun no_maximum_privacy_or_no_server_claims_remain() {
        for (path in listOf("chat/model/SendMessageState.kt", "chat/AndroidChat.kt", "chat/view/ChatDetailView.kt")) {
            assertFalse("maximum privacy" in source(path).lowercase(), "$path still says 'maximum privacy'")
        }
        val phrases =
            mapOf(
                "values" to listOf("maximum privacy", "no server ever sees", "private network"),
                "values-es" to listOf("máxima privacidad", "ningún servidor", "red privada"),
            )
        for ((dir, bad) in phrases) {
            val onboarding = strings("onboarding", dir)
            for (key in listOf("onboarding_how_encrypted_desc", "onboarding_how_latency_desc")) {
                val text = onboarding[key] ?: fail("$dir has no $key")
                bad.forEach { assertFalse(it in text.lowercase(), "$dir/$key: $text") }
            }
        }
    }

    // ---- helpers ----

    private fun labelName(
        function: String,
        elapsedSeconds: Long
    ): String {
        val method =
            PendingSendEstimate::class.java.methods.firstOrNull { it.name == function }
                ?: fail("PendingSendEstimate.$function(elapsedSeconds) is missing")
        return resourceName(method.invoke(PendingSendEstimate, elapsedSeconds) as Int)
    }

    private fun assertSpanish(text: String) {
        val id = ChatSendCopy.localizedResource(text) ?: fail("no Spanish for: $text")
        val name = resourceName(id)
        assertEquals(text, common("values")[name], "English resource $name must be the exact text the app reports")
        val es = common("values-es")[name] ?: fail("values-es has no $name")
        assertTrue(es.isNotBlank() && es != text, "$name is not translated")
    }

    private fun resourceName(id: Int): String =
        assertNotNull(
            R.string::class.java.fields
                .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
                ?.name,
            "no string resource with id $id",
        )

    private fun common(dir: String): Map<String, String> = strings("common", dir)

    private fun strings(
        module: String,
        dir: String
    ): Map<String, String> =
        File(repoRoot, "ui-lib/src/main/res/ui/$module/$dir")
            .listFiles { f -> f.extension == "xml" }
            .orEmpty()
            .flatMap { f ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(f)
                        .getElementsByTagName("string")
                (0 until nodes.length)
                    .map { nodes.item(it) as Element }
                    .map { it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\\"", "\"") }
            }.toMap()
}
