package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D3-3 S5 (fix-all 2.13.0, train T5; t5-specs SPECS §2.5, §2.7 items 8 and 9): where the claim's answers are shown.
 *
 * - AndroidChat shows one dialog per QsProblem value: an exhaustive `when` over the problem with no `else`, so a new
 *   value cannot fall into the "no code" dialog (the addendum assumed F11's guard covered this; it covers only the
 *   Invalid QR code toast, which stays: `problem == null`).
 * - The "no code" dialog shows the DOWNGRADED text when that alert is set.
 * - ChatDetailView's ACTIVE banner shows the alert and a "Turn off here" button that opens the existing turn-off
 *   confirm: one tap never turns off a working shield, and nothing turns off by itself. The dialogs whose text says
 *   "Tap Turn off here" have that button too, and it also asks first.
 * - The alert reaches ChatDetailView as `quantumShieldAlert` (a String, like `quantumShieldStatus`), and the refresh tick
 *   recomputes the alerts right after it retries the claims.
 */
class QuantumShieldClaimUiTest {
    private val ui = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat")
    private val chat = File(ui, "AndroidChat.kt").readText()
    private val detail = File(ui, "view/ChatDetailView.kt").readText()
    private val vm = File(ui, "viewmodel/ChatViewModel.kt").readText()

    private val problemTitles =
        mapOf(
            "NOT_THIS_CHATS_CODE" to "R.string.quantum_shield_not_this_chat_title",
            "QR_OLD_FORMAT" to "R.string.quantum_shield_qr_old_format_title",
            "OTHER_PHONE_SET_UP_AGAIN" to "R.string.quantum_shield_set_up_again_title",
        )

    private fun block(
        src: String,
        header: String,
    ): String {
        val start = src.indexOf(header)
        assertTrue(start >= 0, "not found: $header")
        var depth = 0
        var i = src.indexOf('{', start)
        val open = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced: $header")
    }

    /** The text of one branch of the problem `when`: from its label to the next top-level label. */
    private fun branch(
        whenBlock: String,
        label: String,
    ): String {
        val start = whenBlock.indexOf("$label ->")
        assertTrue(start >= 0, "the problem dialog has no branch $label")
        val next =
            (problemTitles.keys.map { "QsProblem.$it ->" } + "null ->")
                .map { whenBlock.indexOf(it, start + 1) }
                .filter { it > start }
                .minOrNull() ?: whenBlock.length
        return whenBlock.substring(start, next)
    }

    @Test
    fun `every QsProblem value has its own dialog branch and none falls into the no-code dialog`() {
        val values =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.crypto.QsProblem")
                .let { checkNotNull(it.enumConstants) }
                .map { it.toString() }
                .toSet()
        assertEquals(problemTitles.keys, values, "QsProblem gains exactly QR_OLD_FORMAT and OTHER_PHONE_SET_UP_AGAIN")
        val whenBlock = block(chat, "when (confirmation.problem) {")
        assertFalse("else ->" in whenBlock, "an exhaustive when with no else: a new value must get its own branch")
        for ((value, title) in problemTitles) {
            val text = branch(whenBlock, "QsProblem.$value")
            assertTrue(title in text, "QsProblem.$value shows its own title $title")
            assertFalse("quantum_shield_no_code_title" in text, "QsProblem.$value never shows the no-code dialog")
        }
        val none = branch(whenBlock, "null")
        assertTrue("R.string.quantum_shield_no_code_title" in none && "R.string.quantum_shield_code_title" in none)
    }

    @Test
    fun `the invalid-code toast stays for unreadable codes only`() {
        val start = chat.indexOf("viewModel.completeQuantumShield(peerAddress, zcpskPayload)")
        val end = chat.indexOf("R.string.quantum_shield_invalid_code", start)
        assertTrue(start >= 0 && end > start)
        assertTrue("viewModel.quantumShieldSas.value?.problem == null" in chat.substring(start, end))
    }

    @Test
    fun `the no-code dialog shows the DOWNGRADED text when that alert is set`() {
        val none = branch(block(chat, "when (confirmation.problem) {"), "null")
        assertTrue("QsAlert.DOWNGRADED" in none, "the no-code dialog reads the alert")
        assertTrue("R.string.quantum_shield_downgraded_body" in none && "R.string.quantum_shield_downgraded_title" in none)
        assertTrue("R.string.quantum_shield_turn_off_here" in none, "its text says Tap Turn off here: the button is there")
    }

    @Test
    fun `the dialogs' Turn off here asks first`() {
        val whenBlock = block(chat, "when (confirmation.problem) {")
        val again = branch(whenBlock, "QsProblem.OTHER_PHONE_SET_UP_AGAIN")
        assertTrue("R.string.quantum_shield_turn_off_here" in again, "the set-up-again text says Tap Turn off here")
        assertFalse("resetQuantumShield(" in whenBlock, "no dialog button turns the shield off without the confirm")
        assertTrue("R.string.quantum_shield_turn_off_title" in chat, "AndroidChat has the turn-off confirm")
        assertTrue("R.string.quantum_shield_turned_off_toast" in chat, "the turned-off toast is a resource")
        assertFalse("\"Extra Security (Post-Quantum) turned off\"" in chat, "no English literal is left")
    }

    @Test
    fun `the alert reaches ChatDetailView through its parameter lists`() {
        assertTrue("viewModel.quantumShieldAlerts" in chat, "AndroidChat collects the alert holder")
        assertTrue(Regex("quantumShieldAlert\\s*=").containsMatchIn(chat), "AndroidChat passes the alert")
        assertEquals(2, Regex("quantumShieldAlert: String = \"NONE\"").findAll(detail).count(), "both declarations")
        assertEquals(1, Regex("quantumShieldAlert = quantumShieldAlert").findAll(detail).count(), "and the pass-through")
    }

    // ZC-1479 (owner W-122 answer 3): the violet ACTIVE banner left the chat body. The body keeps only the two warnings of
    // the other phone's signed claim, with "Turn off here" opening the existing confirm; the reassuring "keys confirmed"
    // line moved into the header mode-chip dialog. Nothing turns Extra Security off without the confirm.
    @Test
    fun `the body keeps only the two claim warnings and Turn off here opens the existing confirm`() {
        val start = detail.indexOf("claimAlert == QsAlert.KEYS_DIFFER || claimAlert == QsAlert.DOWNGRADED")
        val end = detail.indexOf("ChatStatusLines.show(", start)
        assertTrue(start >= 0 && end > start, "the warnings block sits in the body before the status lines")
        val warnings = detail.substring(start, end)
        for (key in listOf(
            "R.string.quantum_shield_keys_differ_title",
            "R.string.quantum_shield_keys_differ_body",
            "R.string.quantum_shield_downgraded_title",
            "R.string.quantum_shield_turn_off_here",
        )) {
            assertTrue(key in warnings, "the warnings block shows $key")
        }
        assertTrue("showQsTurnOffDialog = true" in warnings, "Turn off here opens the confirm")
        assertFalse("onResetQuantumShield()" in warnings, "Turn off here never turns the shield off by itself")
        val confirmStart = detail.indexOf("if (showQsTurnOffDialog)")
        assertTrue(confirmStart >= 0, "the existing confirm is still there")
        val confirm = detail.substring(confirmStart, minOf(detail.length, confirmStart + 1500))
        assertTrue("R.string.quantum_shield_turn_off_title" in confirm, "the existing confirm")
        assertEquals(1, Regex("onResetQuantumShield\\(\\)").findAll(detail).count(), "only the confirm turns the shield off")
        val chip = detail.indexOf("dialogClaimAlert == QsAlert.CONFIRMED")
        assertTrue(
            chip >= 0 && detail.indexOf("R.string.quantum_shield_keys_confirmed", chip) > chip,
            "keys confirmed is in the mode-chip dialog",
        )
    }

    @Test
    fun `the refresh tick recomputes the alerts after retrying the claims`() {
        val tick = block(vm, "private suspend fun performAutoRefresh()")
        val lines = tick.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val retry = lines.indexOf("retryQsClaims()")
        assertTrue(retry >= 0)
        assertEquals("recomputeQsAlerts()", lines.getOrNull(retry + 1))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        return dir ?: error("repo root not found above the test working dir")
    }
}
