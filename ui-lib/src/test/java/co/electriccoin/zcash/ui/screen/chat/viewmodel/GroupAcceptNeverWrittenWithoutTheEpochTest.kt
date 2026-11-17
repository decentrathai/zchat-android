package co.electriccoin.zcash.ui.screen.chat.viewmodel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-506 Part A — a source scan (the pattern of PaidSendCallSiteLockTest): the app can only write a group reply
 * (GROUP_ACCEPT) that says which invitation it answers.
 *
 * - `ZMSGGroupProtocol.createGroupAcceptMessage(` is called from exactly one function of the app,
 *   `ChatViewModel.groupAcceptMemo`, and that call passes `keyEpoch =`.
 * - `groupAcceptMemo(` is called from exactly the known places (two in `processGroupInviteLocked`: the reply kept for
 *   Join on a stranger's invite and the free reply to a known contact; one in `offerGroupReopen`: a new invite after a
 *   removal), and every call passes the invite's `keyEpoch`.
 * - Neither function looks at a size: there is no shorter form "when it would not fit". (The value is always written;
 *   a reply over 512 bytes travels over the relays with it.)
 *
 * A new caller, or a size check around the builder, fails this test until it is reviewed and listed here.
 * Today the one call passes no key epoch at all.
 */
class GroupAcceptNeverWrittenWithoutTheEpochTest {
    @Test
    fun the_builder_is_called_only_from_groupAcceptMemo_and_always_with_a_key_epoch() {
        val calls = callsOf("createGroupAcceptMessage(")
        assertEquals(listOf("ChatViewModel.kt#groupAcceptMemo"), calls.map { it.site }, "who builds a GROUP_ACCEPT")
        val call = calls.single()
        assertTrue("keyEpoch =" in call.text, "the one GROUP_ACCEPT the app builds passes no key epoch: ${call.text}")
    }

    @Test
    fun groupAcceptMemo_has_exactly_the_known_call_sites_and_each_passes_the_invites_key_epoch() {
        val calls = callsOf("groupAcceptMemo(")
        assertEquals(
            mapOf("ChatViewModel.kt#processGroupInviteLocked" to 2, "ChatViewModel.kt#offerGroupReopen" to 1),
            calls.groupingBy { it.site }.eachCount(),
            "who asks for a GROUP_ACCEPT memo (file#function to calls); review a new caller and list it",
        )
        val without = calls.filterNot { Regex("\\bkeyEpoch\\b").containsMatchIn(it.text) }
        assertEquals(emptyList(), without.map { "${it.site}: ${it.text}" }, "calls that do not pass the invite's key epoch")
    }

    @Test
    fun neither_function_holds_a_size_check() {
        for ((file, function) in listOf("ChatViewModel.kt" to "groupAcceptMemo", "ZMSGGroupProtocol.kt" to "createGroupAcceptMessage")) {
            val body = bodyOf(file, function)
            val hits = SIZE_CHECKS.filter { it.containsMatchIn(body) }.map { it.pattern }
            assertEquals(emptyList(), hits, "$file#$function compares a size (a shorter GROUP_ACCEPT without the key epoch?)")
        }
    }

    private data class Call(
        val site: String,
        val text: String,
    )

    /** Every call of [needle] in the app's main Kotlin: where (file#member function) and its text up to the closing bracket. */
    private fun callsOf(needle: String): List<Call> {
        val name = needle.removeSuffix("(")
        val declaration = Regex("\\bfun\\s+$name\\(")
        val result = mutableListOf<Call>()
        mainJava
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val lines = file.readLines()
                var member: String? = null
                lines.forEachIndexed { i, line ->
                    MEMBER_FUN.find(line)?.let { member = it.groupValues[1] }
                    val code = line.trim()
                    val isComment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                    if (isComment || declaration.containsMatchIn(line) || !line.contains(needle)) return@forEachIndexed
                    result += Call("${file.name}#${member ?: "?"}", callText(lines, i, line.indexOf(needle) + needle.length - 1))
                }
            }
        return result
    }

    /** The source from the opening bracket at [open] in line [start] to its closing bracket, on one line. */
    private fun callText(
        lines: List<String>,
        start: Int,
        open: Int,
    ): String {
        val text = StringBuilder()
        var depth = 0
        for (i in start until minOf(lines.size, start + 40)) {
            val line = if (i == start) lines[i].substring(open) else lines[i]
            for (c in line) {
                text.append(c)
                if (c == '(') depth++
                if (c == ')' && --depth == 0) return text.toString().replace(Regex("\\s+"), " ")
            }
            text.append(' ')
        }
        fail("no closing bracket within 40 lines of line ${start + 1}")
    }

    /** The source of the member function [function] of [fileName], from its `fun` line to its closing brace. */
    private fun bodyOf(
        fileName: String,
        function: String,
    ): String {
        val file = mainJava.walkTopDown().single { it.isFile && it.name == fileName }
        val lines = file.readLines()
        val start = lines.indexOfFirst { MEMBER_FUN.find(it)?.groupValues?.get(1) == function }
        assertTrue(start >= 0, "$fileName has no member function $function")
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private companion object {
        /** A class member function: exactly four spaces of indent (local functions are indented deeper). */
        val MEMBER_FUN =
            Regex(
                "^ {4}(?:(?:private|internal|public|protected|override|suspend|inline|operator)\\s+)*fun\\s+" +
                    "(?:<[^>]*>\\s*)?`?(\\w+)`?\\s*\\(",
            )

        val SIZE_CHECKS =
            listOf(
                Regex("MAX_MEMO"),
                Regex("\\b512\\b"),
                Regex("\\.size\\b"),
                Regex("\\.length\\b"),
                Regex("\\btakeIf\\b"),
                Regex("\\btakeUnless\\b"),
                Regex("firstOverflowingMemoBytes"),
            )

        val mainJava: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java") }
                .firstOrNull { it.resolve("co/electriccoin/zcash/ui/screen/chat").isDirectory }
                ?: fail("could not locate ui-lib/src/main/java from ${start.path}")
        }
    }
}
