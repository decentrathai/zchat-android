package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TX-1 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §4 with §9 row 8). On the Fold after `install -r`, a chat
 * showed "🔀 u1lqy0vp...vh55cu asked to switch this chat to Vault (shielded on-chain)." while its header said
 * "Seeker213" (fold run U1). Two causes:
 * - (a) a system note is stored as finished text when it happens (R5-4), and ChatDetailView draws it as stored
 *   (`SystemNotePill(message.text)`), so a 2.12.4 note keeps 2.12.4's words ("Vault", "via relay") and any note keeps
 *   the language and the name it was written with;
 * - (b) the note sites name the peer with `zchatPreferences.getDisplayName`, the nickname or the short address, and
 *   never the contact-book name the header shows (header order: nickname, then contact name; ChatViewModel's
 *   conversation build and `emptyConversationFor`).
 *
 * The fix draws a mode note in today's words with the header's name (`SystemNoteText.display`, no data migration) and
 * names the peer at the four note sites with `peerLabel` (nickname, contact name, short address). The scan is limited
 * to those four functions: the public `fun getDisplayName` of the view model is UI API and stays. Re-anchored at
 * `e3f242bcf`: F4's note site that the spec calls `provenNotSentMessage` is `noteLateLandings` (the
 * `chat_note_attempt_landed_late` note). The tunnel note moves to a string resource.
 */
class SystemNotesShowTodaysWordsTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun src(path: String): String =
        stripComments(File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText())

    private val viewModel by lazy { src("screen/chat/viewmodel/ChatViewModel.kt") }
    private val detailView by lazy { src("screen/chat/view/ChatDetailView.kt") }

    @Test
    fun the_chat_screen_draws_a_system_note_in_todays_words_with_the_header_name() {
        assertFalse(
            "SystemNotePill(message.text)" in detailView,
            "ChatDetailView draws a system note as stored, so a 2.12.4 note keeps \"Vault\" and the old name",
        )
        val draw =
            Regex(
                """SystemNotePill\(\s*SystemNoteText\.display\(\s*message\.id,\s*message\.text,\s*(?:conversation\.displayName|noteName),"""
            )
        assertTrue(
            draw.containsMatchIn(detailView),
            "ChatDetailView must draw a system note through SystemNoteText.display(message.id, message.text, " +
                "noteName, …), the header's current name or \"Your contact\" (QA2 Q2-41)",
        )
    }

    @Test
    fun the_four_note_sites_name_the_peer_like_the_chat_header() {
        for (site in NOTE_SITES) {
            val body = functionBody(viewModel, site)
            assertFalse("getDisplayName(" in body, "$site names the peer by nickname or address only (getDisplayName)")
            assertTrue("peerLabel(" in body, "$site must name the peer with peerLabel(…)")
        }
        val label = functionBody(viewModel, "peerLabel")
        val nickname = label.indexOf("zchatPreferences.getNickname(")
        val contact = label.indexOf("contactBook.getContact(")
        val short = label.indexOf("zchatPreferences.getDisplayName(")
        assertTrue(nickname >= 0 && contact >= 0 && short >= 0, "peerLabel reads the nickname, the contact name and the short address")
        assertTrue(nickname < contact && contact < short, "peerLabel's order must be the header's: nickname, contact name, short address")
    }

    @Test
    fun the_tunnel_note_is_a_string_resource() {
        assertFalse("🚇 Secure tunnel" in viewModel, "the tunnel note is still an English literal in ChatViewModel")
        assertTrue("R.string.chat_note_tunnel_established" in viewModel, "the tunnel note must use chat_note_tunnel_established")
    }

    @Test
    fun the_fold_u1_note_reads_in_2130_words_with_the_header_name() {
        val u1 = "🔀 u1lqy0vp...vh55cu asked to switch this chat to Vault (shielded on-chain)."
        val id = "sysnote-mode-0123456789ab-1759000000000"
        assertEquals("🔀 Seeker213 asked to switch this chat to Shielded (on-chain).", display(id, u1, "Seeker213", copy("values")))
        assertEquals("🔀 Seeker213 pide cambiar este chat a Shielded (en la cadena).", display(id, u1, "Seeker213", copy("values-es")))
    }

    private fun display(
        id: String,
        text: String,
        name: String,
        copy: ChatNoteCopy,
    ): String {
        val cls =
            try {
                Class.forName("co.electriccoin.zcash.ui.screen.chat.model.SystemNoteText")
            } catch (e: ClassNotFoundException) {
                fail("screen.chat.model.SystemNoteText is missing")
            }
        val method =
            try {
                cls.getMethod("display", String::class.java, String::class.java, String::class.java, ChatNoteCopy::class.java)
            } catch (e: NoSuchMethodException) {
                fail("SystemNoteText.display(id: String, text: String, name: String, copy: ChatNoteCopy) is missing")
            }
        return method.invoke(cls.getField("INSTANCE").get(null), id, text, name, copy) as String
    }

    /** The app's strings of one values folder of ui/common, resolved by R id, formatted as Android does. */
    private fun copy(dir: String): ChatNoteCopy {
        val texts = HashMap<String, String>()
        File(repoRoot, "ui-lib/src/main/res/ui/common/$dir").listFiles { f -> f.extension == "xml" }!!.forEach { f ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(f.readText())
                .forEach { texts[it.groupValues[1]] = it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
        }
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return ChatNoteCopy { id, args ->
            val key = names[id] ?: fail("unknown string id $id")
            String.format(texts[key] ?: fail("no $dir text for $key"), *args)
        }
    }

    /** The body of `fun <name>(`, from its first `{` or `=` to the matching end (braces and parentheses balanced). */
    private fun functionBody(
        code: String,
        name: String,
    ): String {
        val start = Regex("""fun $name\(""").find(code)?.range?.first ?: fail("ChatViewModel has no fun $name(")
        var i = code.indexOf('(', start)
        var depth = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        val open = code.indexOf('{', i)
        val eq = code.indexOf('=', i)
        if (eq in 0 until open) {
            // Expression body (`fun x(...): T =`): up to the next blank line.
            val end = Regex("""\n\s*\n""").find(code, eq)?.range?.first ?: code.length
            return code.substring(eq, end)
        }
        depth = 0
        var j = open
        while (j < code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, j + 1)
                }
            }
            j++
        }
        return code.substring(open)
    }

    /** Line and block comments out, string literals kept whole. */
    private fun stripComments(code: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    out.append(code, i, end)
                    i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '"' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    val end = minOf(j + 1, code.length)
                    out.append(code, i, end)
                    i = end
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '\'' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    val end = minOf(j + 1, code.length)
                    out.append(code, i, end)
                    i = end
                }
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    i = end
                }
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        /** The four ChatViewModel functions that write a note naming the peer (spec §4.1 (b), re-anchored). */
        val NOTE_SITES = listOf("handleDisappearControl", "handleModeControl", "noteFirstZcashOnlyMessage", "noteLateLandings")
    }
}
