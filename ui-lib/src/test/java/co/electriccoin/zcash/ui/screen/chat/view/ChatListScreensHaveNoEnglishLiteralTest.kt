package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ES-A (fix-all 2.13.0, train T4c; Spanish fold spec §1, §3.1; KI-9 / ZC-007): on a Spanish phone the chat list
 * and the screens it opens still show English: the search bar, the ⚙ menu ("Check for Updates / Invite Friend /
 * Settings"), the tabs "Chats" / "AI", "No conversations found", the message-request banner and its dialog, the
 * sync banner, the empty list and its privacy dialog, the row labels ("DELETE", "Muted", "Draft:"), the Leave Group
 * dialog, "My photo", the update dialogs the menu opens, the Invite Friend screen, the contact-name dialog and the
 * Welcome ZEC card. Each is a hard-coded English literal.
 *
 * The guard: every file (or brace-matched block) in [scope] holds no English UI literal. The Destroy PIN dialogs of
 * ChatListView were moved by FD-8a (7443c5c95, train T4b) before this batch, so no region is deferred.
 */
class ChatListScreensHaveNoEnglishLiteralTest {
    private val base = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen"

    private data class Scope(
        val path: String,
        // Anchors of the blocks in scope; empty = the whole file.
        val anchors: List<String> = emptyList(),
    )

    private val scope =
        listOf(
            Scope("$base/chat/view/ChatListView.kt"),
            Scope("$base/chat/AndroidChat.kt", listOf("fun AndroidChatList(", "private fun MessageRequestsDialog(")),
            Scope(
                "$base/chat/view/ChatDetailView.kt",
                listOf("if (showWelcomeZecConfirm) {", "if (showNicknameDialog) {", "private fun WelcomeZecCard("),
            ),
            Scope("$base/invite/view/InviteFriendView.kt"),
            Scope("$base/invite/viewmodel/InviteFriendVM.kt"),
            Scope("$base/invite/AndroidInviteFriend.kt"),
            Scope("$base/chat/view/ZchatAvatar.kt"),
            Scope("$base/update/UpdateChecker.kt", listOf("fun UpdateCheckOverlay(")),
            Scope("$base/update/UpdateRequiredBar.kt"),
        )

    // Literals that are never shown, each with its reason.
    private val allowedExact =
        setOf(
            // MIME types handed to the system picker and the share sheet.
            "image/*",
            "text/plain",
            // The file-name prefix of the shared invite QR picture.
            "zchat_invite",
            // QR-7: the file-name prefix of the shared free-chat QR picture.
            "zchat_free_chat",
            // The ClipData label of the copied address; the system does not show it.
            "ZCHAT Address",
            // A chain id passed to the address checks.
            "zcash",
            // A LazyColumn key prefix.
            "group_",
            // The sync bar's clock pattern.
            "HH:mm:ss",
            // The skeleton handed to DateFormat.getBestDateTimePattern (the phone turns it into its own order).
            "MMMd",
            // Today's time pattern (24-hour, unchanged).
            "HH:mm",
            // The tag of the empty list's link annotation.
            "privacy_link",
        )
    private val allowedPatterns = listOf(Regex("^https://"))

    private fun rangesOf(
        lexed: KotlinUiLiterals.Lexed,
        s: Scope
    ): List<IntRange> = if (s.anchors.isEmpty()) listOf(lexed.source.indices) else s.anchors.map { KotlinUiLiterals.block(lexed, it) }

    @Test
    fun every_listed_screen_reads_its_texts_from_resources() {
        val found =
            scope.flatMap { s ->
                if (s.path == "$base/update/UpdateRequiredBar.kt") {
                    assertTrue(File(ChatResourceText.repoRoot, s.path).isFile, "ZC-538: the required localized update bar is absent")
                }
                val lexed = KotlinUiLiterals.lex(ChatResourceText.source(s.path))
                KotlinUiLiterals
                    .offenders(lexed, allowedExact, allowedPatterns, rangesOf(lexed, s))
                    .map { "${s.path.substringAfterLast('/')}:$it" }
            }
        assertTrue(found.isEmpty(), "${found.size} English UI literals are shown on these screens:\n" + found.joinToString("\n"))
    }

    @Test
    fun the_updater_failure_texts_are_resources() {
        val src = ChatResourceText.source("$base/update/UpdateChecker.kt")
        assertFalse(
            "DOWNLOADED_UPDATE_REFUSED_MESSAGE" in src,
            "the refused-download text is still an English constant (DOWNLOADED_UPDATE_REFUSED_MESSAGE)",
        )
        assertFalse(Regex("""UpdateState\.Failed\(\s*"""").containsMatchIn(src), "an update failure is still built from an English literal")
        assertTrue("R.string.update_dialog_failed_not_newer" in src, "the refused-download text is not the resource")
    }

    @Test
    fun the_updater_keeps_its_store_build_guard_first() {
        // test_native_alignment.py pins the same line; moving the texts must not move it.
        val src = ChatResourceText.source("$base/update/UpdateChecker.kt")
        assertTrue(
            Regex("""fun UpdateCheckOverlay\(\)\s*\{(?:[^{}]|\n)*?if \(!BuildConfig\.IS_SELF_UPDATE_ENABLED\) return""")
                .containsMatchIn(src),
            "UpdateCheckOverlay must still return first in a store build",
        )
    }

    @Test
    fun the_three_tab_bars_use_the_tab_resources() {
        val files =
            listOf(
                "$base/chat/view/ChatListView.kt",
                "$base/wallettab/WalletTabView.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt",
            )
        for (path in files) {
            val lexed = KotlinUiLiterals.lex(ChatResourceText.source(path))
            val shown = lexed.literals.map { it.text }
            for (word in listOf("Chats", "AI")) {
                assertFalse(word in shown, "${path.substringAfterLast('/')} still shows the literal \"$word\"")
            }
            for (key in listOf("R.string.tab_chats", "R.string.tab_ai")) {
                assertTrue(key in lexed.blanked, "${path.substringAfterLast('/')} does not read $key")
            }
        }
    }

    @Test
    fun every_scope_anchor_is_found_exactly_once() {
        for (s in scope) {
            if (s.path == "$base/update/UpdateRequiredBar.kt") {
                assertTrue(File(ChatResourceText.repoRoot, s.path).isFile, "ZC-538: the required localized update bar is absent")
            }
            val lexed = KotlinUiLiterals.lex(ChatResourceText.source(s.path))
            for (anchor in s.anchors) {
                assertEquals(1, KotlinUiLiterals.anchorOffsets(lexed, anchor).size, "${s.path}: anchor \"$anchor\"")
            }
        }
    }

    @Test
    fun the_scanner_flags_a_planted_literal_and_skips_comments_logs_and_allowed_values() {
        val fixture =
            """
            fun Screen() {
                // Text("Hello")
                /* Text("Hello") */
                Log.d(TAG, "x " +
                    "y line")
                val mime = "image/*"
                val url = "https://a.example"
                val c = '"'
                val q = '\''
                Text("Hello")
                Text("${'$'}{if (open) "Hide Details" else "x"} ${'$'}count")
                Text(stringResource(R.string.ok))
            }
            """.trimIndent()
        val lexed = KotlinUiLiterals.lex(fixture)
        val found = KotlinUiLiterals.offenders(lexed, allowedExact, allowedPatterns)
        assertEquals(listOf("10: \"Hello\"", "11: \"Hide Details\""), found, "the scanner's own check")
        val block = KotlinUiLiterals.block(lexed, "fun Screen(")
        assertEquals(fixture.lastIndexOf('}'), block.last, "the block ends at the function's own closing brace")
    }
}
