package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.AttachmentBlockAction
import co.electriccoin.zcash.ui.screen.chat.model.AttachmentBlockReason
import co.electriccoin.zcash.ui.screen.chat.model.action
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1474 train wiring (OT213n W4): AttachmentKeyExchangeDialog shows one body AND one action per AttachmentBlockReason
 * (B1b's reason enum and its pure `AttachmentBlockReason.action`): Switch to Shielded, send a message, send the owed key
 * exchange (its existing priced setup), accept the contact's key exchange (the existing Accept banner and its price), or
 * wait (OK only). At the train head e626e879d the dialog still branches on NEEDS_KEY_EXCHANGE alone: it never reads
 * `.action`, so KEX_NOT_SENT and PEER_MUST_ACCEPT get only "Not now", and a TODO(OT213n-train-merge ZC-1474) stands in.
 */
class AttachmentNoticeOffersOneActionPerReasonTest {
    private val dialog by lazy { codeOnly(ChatResourceText.source(DIALOG)) }
    private val androidChat by lazy { codeOnly(ChatResourceText.source(ANDROID_CHAT)) }

    @Test
    fun every_reason_has_its_own_body() {
        val bodies = AttachmentBlockReason.entries.associateWith { attachmentKeyExchangeBody(it) }
        assertEquals(AttachmentBlockReason.entries.size, bodies.values.toSet().size, "two reasons share a body: $bodies")
    }

    @Test
    fun every_reason_maps_to_an_action_and_every_action_is_used() {
        val used = AttachmentBlockReason.entries.map { it.action }.toSet()
        assertEquals(AttachmentBlockAction.entries.toSet(), used, "an AttachmentBlockAction no reason leads to")
    }

    @Test
    fun the_dialog_draws_the_reasons_action() {
        assertTrue(
            Regex("""\breason\s*\.\s*action\b""").containsMatchIn(dialog),
            "AttachmentKeyExchangeDialog never reads reason.action: its button does not follow the reason",
        )
        val missing = AttachmentBlockAction.entries.filterNot { Regex("""\b${it.name}\b""").containsMatchIn(dialog) }
        assertTrue(missing.isEmpty(), "AttachmentKeyExchangeDialog draws no button for $missing")
    }

    @Test
    fun the_dialog_takes_a_callback_for_each_action_that_goes_somewhere() {
        // Switch to Shielded, send the owed key exchange, accept the contact's key exchange, and the dismissal.
        val params = argumentsAt(dialog, dialog.indexOf("fun AttachmentKeyExchangeDialog("))
        val callbacks = Regex("""\w+\s*:\s*\(\)\s*->\s*Unit""").findAll(params).count()
        assertTrue(
            callbacks >= 4,
            "AttachmentKeyExchangeDialog takes $callbacks `() -> Unit` callbacks; it needs one per action that leads somewhere (>= 4)",
        )
    }

    @Test
    fun the_chat_screen_wires_each_callback() {
        val at = androidChat.indexOf("AttachmentKeyExchangeDialog(")
        assertTrue(at >= 0, "AndroidChat no longer shows AttachmentKeyExchangeDialog")
        val args = argumentsAt(androidChat, at)
        val lambdas = Regex("""\w+\s*=\s*\{""").findAll(args).count()
        assertTrue(lambdas >= 4, "AndroidChat wires $lambdas actions of AttachmentKeyExchangeDialog; it needs >= 4")
    }

    @Test
    fun the_merge_todo_is_gone() {
        assertFalse(
            "TODO(OT213n-train-merge ZC-1474)" in ChatResourceText.source(DIALOG),
            "the TODO(OT213n-train-merge ZC-1474) comment is still in AttachmentKeyExchangeDialog.kt",
        )
    }

    private companion object {
        const val DIALOG = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/AttachmentKeyExchangeDialog.kt"
        const val ANDROID_CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"

        /** The text between the `(` at or after [from] and its matching `)`. */
        fun argumentsAt(
            src: String,
            from: Int,
        ): String {
            require(from >= 0) { "call not found" }
            val open = src.indexOf('(', from)
            var depth = 0
            for (i in open until src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return src.substring(open + 1, i)
                }
            }
            error("unbalanced call at $from")
        }

        // The source without whole-line comments: lines starting with a line-comment, a block-comment opener or a
        // KDoc star. Line-based on purpose: a regex over block comments would also eat code after a MIME literal
        // such as "image/" + "*" in AndroidChat.
        fun codeOnly(text: String): String =
            text
                .lines()
                .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") } }
                .joinToString("\n")
    }
}
