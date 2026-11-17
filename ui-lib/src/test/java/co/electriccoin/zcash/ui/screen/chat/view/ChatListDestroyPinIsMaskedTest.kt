package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.pluralStringRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FD-8a (fix-all 2.13.0, train T4b; destroy SPEC "FD-8a", review items 7-9) — the chat list's own Destroy All dialogs
 * (the footer's Set Destroy PIN, Destroy All Data verify and final DESTROY ALL dialogs in ChatListView.kt) type the
 * Destroy PIN in plain text on a text keyboard (uiautomator reads password=false in the Set and the Enter dialogs), the
 * Set button is disabled while the two PINs differ, so "PINs do not match" can never show (and a 3-digit PIN never
 * shows "PIN must be at least 4 digits"), and every text of the three dialogs and of the device-credential reset is a
 * hard-coded English literal on a Spanish phone. The Settings → Emergency Destroy screen and onboarding already mask
 * the PIN (PasswordVisualTransformation + KeyboardType.NumberPassword).
 *
 * FD-8c (same dialog): the verify dialog calls the Boolean shim verifyDestroyPin, so during a lockout even the RIGHT
 * PIN says "Incorrect PIN" and the user is never told to wait. Wanted: the lockout-aware result, and the existing
 * destroy_locked_minutes / destroy_locked_seconds / destroy_pin_incorrect_attempts texts, never a wait shorter than
 * the real one.
 *
 * Nothing about what is wiped, or when, changes; the PIN hash, the attempt counter and the lockout are untouched.
 * Reads the view's source and the string resources, as the other copy tests do; the FD-8c text choice is a pure
 * function called by reflection so that this file compiles before it exists.
 */
class ChatListDestroyPinIsMaskedTest {
    private val viewPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"
    private val src: String by lazy { ChatResourceText.source(viewPath) }

    private fun region(
        from: String,
        to: String,
    ): String {
        val start = src.indexOf(from)
        val end = src.indexOf(to, start + 1)
        assertTrue(start >= 0 && end > start, "fixture: \"$from\" .. \"$to\" in ChatListView.kt")
        return src.substring(start, end)
    }

    /** The verify and the final dialogs (DESTROY-ADV-P: the Set dialog is gone, W-52). */
    private val dialogs: String by lazy { region("// PIN Verify Dialog", "// Group Chat Coming Soon Dialog") }
    private val verifyDialog: String by lazy { region("// PIN Verify Dialog", "// Final Destroy Confirmation Dialog") }
    private val resetLauncher: String by lazy { region("fun launchDestroyScreenLock()", "val userAddress = ") }

    /** The argument text of every call to [callee] in [text], parentheses balanced. */
    private fun calls(
        text: String,
        callee: String,
    ): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("$callee(", from)
            if (at < 0) return out
            var depth = 0
            var i = at + callee.length
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            out += text.substring(at + callee.length + 1, i)
            from = i
        }
    }

    /** [text] without `//` comment lines, so a comment that quotes a text does not count. */
    private fun code(text: String): String = text.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun every_destroy_PIN_field_on_the_chat_list_is_masked_and_numeric() {
        val fields = calls(region("// PIN Verify Dialog", "// Final Destroy Confirmation Dialog"), "OutlinedTextField")
        assertEquals(1, fields.size, "fixture: the verify dialog has one PIN field (DESTROY-ADV-P: no Set dialog)")
        fields.forEachIndexed { n, field ->
            assertTrue(
                "visualTransformation = PasswordVisualTransformation()" in field,
                "destroy PIN field ${n + 1} shows the digits (no PasswordVisualTransformation)",
            )
            assertTrue(
                Regex("""keyboardOptions\s*=\s*KeyboardOptions\(\s*keyboardType\s*=\s*KeyboardType\.NumberPassword""")
                    .containsMatchIn(field),
                "destroy PIN field ${n + 1} does not ask for the numeric PIN keyboard (KeyboardType.NumberPassword)",
            )
        }
    }

    @Test
    fun the_chat_list_destroy_dialogs_have_no_hard_coded_english() {
        val scanned = code(dialogs) + "\n" + code(resetLauncher)
        val patterns =
            listOf(
                Regex("""Text\(\s*""""),
                Regex("""text\s*=\s*""""),
                Regex("""Text\(\s*if\s*\([^)]*\)\s*""""),
                Regex("""pinError\s*=\s*""""),
            )
        val found = patterns.flatMap { p -> p.findAll(scanned).map { m -> scanned.lineAt(m.range.first) } }.distinct()
        val credential = calls(scanned, "createConfirmDeviceCredentialIntent").filter { '"' in it }
        assertTrue(
            found.isEmpty() && credential.isEmpty(),
            "hard-coded English in the chat-list Destroy dialogs:\n" +
                (found + credential.map { "createConfirmDeviceCredentialIntent($it)" }).joinToString("\n"),
        )
    }

    @Test
    fun the_chat_list_destroy_strings_are_translated() {
        val required =
            listOf(
                "chat_destroy_verify_warning",
                "chat_destroy_forgot_pin",
                "chat_destroy_screen_lock_prompt",
                "chat_destroy_verify",
                "chat_destroy_verifying",
                "chat_destroy_final_title",
                "chat_destroy_final_cannot_undo",
                "chat_destroy_final_list_intro",
                "chat_destroy_final_item_messages",
                "chat_destroy_final_item_contacts",
                "chat_destroy_final_item_wallet",
                "chat_destroy_final_wallet_note",
                "chat_destroy_final_item_settings",
                "chat_destroy_final_confirm",
                "chat_destroy_final_uninstall_note",
            )
        val used = Regex("""R\.string\.(chat_destroy_\w+)""").findAll(src).map { it.groupValues[1] }.toSet()
        val missingUse = required.filterNot { it in used }
        assertTrue(missingUse.isEmpty(), "ChatListView.kt does not use: $missingUse")

        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val placeholder = Regex("""%(\d+\$)?[sd]""")
        val problems =
            (required + used).distinct().mapNotNull { key ->
                val e = en[key]
                val s = es[key]
                when {
                    e == null || s == null -> "$key: missing in ${if (e == null) "values" else "values-es"}"
                    e == s -> "$key: not translated"
                    placeholder.findAll(e).map { it.value }.toList() != placeholder.findAll(s).map { it.value }.toList() ->
                        "$key: placeholders differ"
                    else -> null
                }
            }
        assertTrue(problems.isEmpty(), "chat-list Destroy strings:\n${problems.joinToString("\n")}")
    }

    @Test
    fun the_verify_dialog_says_when_the_PIN_is_locked_out() {
        assertTrue(
            Regex("""onVerifyDestroyPin:\s*suspend\s*\(String\)\s*->\s*DestroyPinVerifyResult""").containsMatchIn(src),
            "ChatListView's onVerifyDestroyPin still returns a Boolean: a lockout reads as \"Incorrect PIN\"",
        )
        val chat = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val wiring =
            Regex("""onVerifyDestroyPin\s*=\s*\{[^}]*\}""").find(chat)?.value
                ?: fail("fixture: onVerifyDestroyPin = { … } in AndroidChat.kt")
        assertTrue(
            "verifyDestroyPinWithLockout(" in wiring,
            "AndroidChat still passes the Boolean shim verifyDestroyPin: $wiring",
        )
        val verify = code(verifyDialog)
        assertTrue(
            "destroyPinVerifyErrorText(" in verify,
            "the verify dialog does not turn a Failed or LockedOut result into its text",
        )
        // Only a right PIN opens the final dialog.
        val success = verify.indexOf("DestroyPinVerifyResult.Success")
        val open = verify.indexOf("showDestroyDialog = true")
        assertTrue(success >= 0 && open > success, "the final dialog must open only on DestroyPinVerifyResult.Success")
        assertTrue(
            Regex("""getOrDefault\(\s*DestroyPinVerifyResult\.Failed\(""").containsMatchIn(verify),
            "a verify that throws must count as a failure, never as a success",
        )
    }

    @Test
    fun the_lockout_text_never_promises_a_shorter_wait() {
        val cases =
            listOf(
                DestroyPinVerifyResult.Success to null,
                DestroyPinVerifyResult.Failed(attemptsRemaining = 4) to pluralStringRes(R.plurals.destroy_pin_incorrect_attempts, 4, 4),
                DestroyPinVerifyResult.Failed(attemptsRemaining = 1) to pluralStringRes(R.plurals.destroy_pin_incorrect_attempts, 1, 1),
                DestroyPinVerifyResult.Failed(attemptsRemaining = 0) to stringRes(R.string.destroy_pin_incorrect),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 1L) to stringRes(R.string.destroy_locked_seconds, 1),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 59_001L) to stringRes(R.string.destroy_locked_seconds, 60),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 60_000L) to stringRes(R.string.destroy_locked_seconds, 60),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 119_000L) to stringRes(R.string.destroy_locked_seconds, 119),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 120_000L) to stringRes(R.string.destroy_locked_minutes, 2),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 4 * 60_000L + 1L) to stringRes(R.string.destroy_locked_minutes, 5),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 5 * 60_000L) to stringRes(R.string.destroy_locked_minutes, 5),
                DestroyPinVerifyResult.LockedOut(remainingMillis = 60 * 60_000L) to stringRes(R.string.destroy_locked_minutes, 60),
            )
        val method =
            try {
                Class
                    .forName("co.electriccoin.zcash.ui.screen.chat.view.ChatDestroyPinTextKt")
                    .getMethod("destroyPinVerifyErrorText", DestroyPinVerifyResult::class.java)
            } catch (e: ReflectiveOperationException) {
                fail("there is no destroyPinVerifyErrorText(DestroyPinVerifyResult) in ChatDestroyPinText.kt: $e")
            }
        for ((result, wanted) in cases) {
            val got = method.invoke(null, result) as StringResource?
            assertEquals(wanted, got, "text for $result")
        }
    }

    private fun String.lineAt(index: Int): String {
        val start = lastIndexOf('\n', index) + 1
        val end = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(start, end).trim()
    }
}
