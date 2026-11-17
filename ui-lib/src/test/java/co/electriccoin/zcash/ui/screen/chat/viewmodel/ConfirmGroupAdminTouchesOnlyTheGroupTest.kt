package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R1, R8 and R11; section 4.1 class 11) — what the admin confirmation may touch,
 * pinned in the source (the pattern of [PaidSendCallSiteLockTest]).
 *
 * R11: the repair never writes or follows an address alias, never writes a contact's key or its verified mark, never
 * rewrites the roster, never pays and never sends a handshake. So the new rule file `GroupAdminCheck.kt` and the
 * bodies of `ChatViewModel.confirmGroupAdmin` and `ChatViewModel.loadGroupAdminPreview` hold none of
 * `setPeerAddressAlias`, `clearPeerAddressAlias`, `setE2EVerified`, `setE2EPeerPublicKey`, `saveGroupMembers`,
 * `createChunkedMessageProposal`, `sendGroupMemoWithRetry`, `sendAutomaticHandshakeMemo`.
 *
 * R1: the gate (`ChatViewModel.verifyGroupAdminControl`) verifies against the group's creator key and nothing else;
 * the address comparison (`isGroupControlAuthorized(`) is gone from its body.
 *
 * At `00f1a349b` the file and the two functions do not exist, and the gate still calls the address comparison.
 */
class ConfirmGroupAdminTouchesOnlyTheGroupTest {
    @Test
    fun the_rule_file_touches_no_alias_no_contact_key_no_roster_and_no_send() {
        val file = File(mainJava, "$CHAT/model/GroupAdminCheck.kt")
        assertTrue(file.isFile, "no rule file screen/chat/model/GroupAdminCheck.kt")
        assertEquals(emptyList(), forbiddenIn(code(file.readLines())), "GroupAdminCheck.kt names operations the repair must never use")
    }

    @Test
    fun the_confirmation_touches_no_alias_no_contact_key_no_roster_and_no_send() {
        assertEquals(
            emptyList(),
            forbiddenIn(memberBody("confirmGroupAdmin")),
            "confirmGroupAdmin names operations the confirmation must never use",
        )
    }

    @Test
    fun the_preview_touches_no_alias_no_contact_key_no_roster_and_no_send() {
        assertEquals(
            emptyList(),
            forbiddenIn(memberBody("loadGroupAdminPreview")),
            "loadGroupAdminPreview names operations the preview must never use",
        )
    }

    @Test
    fun the_gate_no_longer_compares_addresses() {
        val gate = memberBody("verifyGroupAdminControl")
        assertTrue(
            "isGroupControlAuthorized(" !in gate,
            "verifyGroupAdminControl still calls isGroupControlAuthorized(: the address comparison decides, not the creator key alone",
        )
    }

    private fun forbiddenIn(code: String): List<String> = FORBIDDEN.filter { it in code }

    /** The code (comments left out) of every member function [name] of ChatViewModel, declaration to the next member. */
    private fun memberBody(name: String): String {
        val lines = File(mainJava, "$CHAT/viewmodel/ChatViewModel.kt").readLines()
        val declaration = Regex("^ {4}(?:(?:$MODIFIERS)\\s+)*fun\\s+(?:<[^>]*>\\s*)?$name\\s*\\(")
        val starts = lines.indices.filter { declaration.containsMatchIn(lines[it]) }
        if (starts.isEmpty()) fail("ChatViewModel has no member function $name")
        return starts.joinToString("\n") { start ->
            val end = (start + 1 until lines.size).firstOrNull { NEXT_MEMBER.containsMatchIn(lines[it]) } ?: lines.size
            code(lines.subList(start, end))
        }
    }

    /** [lines] without comment lines and trailing line comments. */
    private fun code(lines: List<String>): String =
        lines
            .filterNot { line -> line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .joinToString("\n") { it.substringBefore("//") }

    private companion object {
        const val CHAT = "co/electriccoin/zcash/ui/screen/chat"
        const val MODIFIERS = "private|internal|public|protected|override|suspend|inline|operator"

        val FORBIDDEN =
            listOf(
                "setPeerAddressAlias",
                "clearPeerAddressAlias",
                "setE2EVerified",
                "setE2EPeerPublicKey",
                "saveGroupMembers",
                "createChunkedMessageProposal",
                "sendGroupMemoWithRetry",
                "sendAutomaticHandshakeMemo",
            )

        /** The next thing declared at class level: a KDoc, an annotation or a declaration at exactly four spaces. */
        val NEXT_MEMBER =
            Regex("^ {4}(?:/\\*\\*|@|(?:(?:$MODIFIERS|data|sealed|enum|const|lateinit|companion)\\s+)*(?:fun|val|var|class|object)\\b)")

        val mainJava: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java") }
                .firstOrNull { it.resolve(CHAT).isDirectory }
                ?: fail("could not locate ui-lib/src/main/java from ${start.path}")
        }
    }
}
