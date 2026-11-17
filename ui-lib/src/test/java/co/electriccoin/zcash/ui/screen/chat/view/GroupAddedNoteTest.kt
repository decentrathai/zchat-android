package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 Q2-57b (ZC-922, W-137 B-4, skill app-texts): the two texts that come with "every group invitation waits for Join".
 *
 * (a) The note. The Seeker found itself in a group with nothing visible. The group screen shows a centred system pill
 *     "%1$s added you to this group." (the creator's saved name or short address) at the group's start, for every group
 *     whose creator is not us (no storage change; groups already stored show it too).
 * (b) The banner for a known contact. Every invitation now waits for Join, also a known contact's. A known contact's
 *     banner must not say "You haven't chatted with this address before" (group_invite_request_banner, a lie for a
 *     contact) and must not say "Open chat" (group_invite_join_known_free, a lie for a Tunnel or Shielded contact). The
 *     new group_invite_join_known says who added you, what Join sends, that the relays are tried first and that a price
 *     is shown only if ZChat has to pay (money-path: the paid branch keeps its own quote).
 *
 * "Ignore"/"Ignorar" keeps its wording (GroupInviteRequestCopyTest). The Spanish lines are drafts for the next native
 * review round (app-texts rule 9). The pins here are on the strings and on GroupDetailView.kt; the layout is [no-red].
 */
class GroupAddedNoteTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "repo root not found above the test working dir" }
    }

    private fun strings(dir: String): Map<String, String> =
        File(repoRoot, "ui-lib/src/main/res/ui")
            .listFiles()
            .orEmpty()
            .flatMap { File(it, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .fold(emptyMap()) { acc, f ->
                acc +
                    Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(f.readText())
                        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
            }

    private fun source(path: String): String = File(repoRoot, path).readText()

    private val groupDetailView = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt"
    private val androidChat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"

    private fun straight(text: String) = text.replace('’', '\'').replace('‘', '\'')

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    // ---- (a) the note ---------------------------------------------------------------------------------------------

    @Test
    fun `the added-you note has its English and Spanish text`() {
        assertEquals("%1\$s added you to this group.", strings("values")["group_note_added_you"])
        assertEquals("%1\$s te agregó a este grupo.", strings("values-es")["group_note_added_you"])
    }

    @Test
    fun `the group screen draws the added-you note from the string resource, for a creator who is not us`() {
        val src = source(groupDetailView)
        val uses = Regex("""R\.string\.group_note_added_you\b""").findAll(src).toList()
        assertTrue(uses.isNotEmpty(), "GroupDetailView.kt never draws R.string.group_note_added_you")
        // The note is for a creator who is not us: the creator's address is compared with ours near the text.
        val near = src.substring((uses.first().range.first - 3_000).coerceAtLeast(0), uses.first().range.first)
        assertTrue(
            near.contains("creatorAddress"),
            "the added-you note is not conditioned on the group's creator (creatorAddress) within the code above it",
        )
        assertFalse(
            Regex("""["']\s*added you to this group""").containsMatchIn(src),
            "the note text is a literal in GroupDetailView.kt, not the string resource",
        )
    }

    // QA2 U9 review follow-up, finding 4: whether the creator is us is decided with the self-address registry, as everywhere
    // else (GroupViewModel: isGroupSelfCreated / isSelfAddress). A stored creatorAddress in another representation of our
    // own address (a restore on a new install, drift) must not show "u1abc...xyz added you to this group." with our address.
    @Test
    fun `the added-you note does not compare the creator with our address by string`() {
        val src = source(groupDetailView)
        val start = src.indexOf("private fun GroupAddedYouNote(")
        assertTrue(start >= 0, "GroupAddedYouNote is gone from GroupDetailView.kt")
        val end = src.indexOf("\n}\n", start)
        val body = src.substring(start, end)
        assertFalse(
            Regex("""(==|!=)\s*currentUserAddress|currentUserAddress\s*(==|!=)""").containsMatchIn(body),
            "GroupAddedYouNote decides \"the creator is us\" by comparing strings with currentUserAddress; a stored creator in " +
                "another representation of our own address would show \"<our address> added you to this group.\"",
        )
    }

    @Test
    fun `the creator-is-us decision of the added-you note uses the self-address registry`() {
        val decided = source(groupDetailView) + "\n" + source(androidChat)
        assertTrue(
            Regex("""isSelfAddress\(|isGroupSelfCreated\(""").containsMatchIn(decided),
            "no isSelfAddress(...) or isGroupSelfCreated(...) in GroupDetailView.kt or AndroidChat.kt: the " +
                "creator-is-us decision of the added-you note is not drift-robust",
        )
    }

    // ---- (b) the banner for a known contact -------------------------------------------------------------------------

    @Test
    fun `the known-contact banner text is in English and Spanish and claims neither a stranger nor an Open chat`() {
        val en = straight(strings("values").getValue("group_invite_join_known"))
        val es = strings("values-es").getValue("group_invite_join_known")

        assertEquals(setOf("%1\$s", "%2\$s"), placeholders(en), en)
        assertEquals(placeholders(en), placeholders(es), "ES placeholders differ from EN: $es")
        assertTrue(en.startsWith("%1\$s added you to “%2\$s”."), en)
        assertTrue("Join sends your “I joined” reply to the group's creator" in en, en)
        assertTrue("ZChat tries the relays first" in en, en)
        assertTrue("shows a price only if it has to pay" in en, en)
        for (lie in listOf("haven't chatted", "Open chat", "nothing has been paid", "free")) {
            assertFalse(lie in en, "the known-contact banner says \"$lie\": $en")
        }

        assertTrue(es.startsWith("%1\$s te agregó a «%2\$s»."), es)
        assertTrue("Unirme" in es, es)
        assertTrue("relays" in es, es)
        assertTrue("precio" in es, es)
        for (lie in listOf("Nunca has chateado", "chat Open", "gratis")) {
            assertFalse(lie in es, "the Spanish known-contact banner says \"$lie\": $es")
        }
    }

    @Test
    fun `the group screen draws the known-contact banner text, and keeps the stranger banner for strangers`() {
        val src = source(groupDetailView)
        assertTrue(
            Regex("""R\.string\.group_invite_join_known\b""").containsMatchIn(src),
            "GroupDetailView.kt never draws R.string.group_invite_join_known: a known contact's invitation still reads " +
                "\"You haven't chatted with this address before\"",
        )
        assertTrue(Regex("""R\.string\.group_invite_request_banner\b""").containsMatchIn(src), "the stranger banner is gone")
        assertTrue(Regex("""R\.string\.group_invite_join_known_paid\b""").containsMatchIn(src), "the relay-failed banner is gone")
        assertTrue(Regex("""R\.string\.group_invite_join_known_free\b""").containsMatchIn(src), "the Open-chat banner is gone")
    }

    @Test
    fun `Ignore keeps its words (the button is not renamed)`() {
        assertEquals("Ignore", strings("values")["group_invite_request_ignore"])
        assertEquals("Ignorar", strings("values-es")["group_invite_request_ignore"])
        assertNotNull(strings("values")["group_invite_request_join"])
    }
}
