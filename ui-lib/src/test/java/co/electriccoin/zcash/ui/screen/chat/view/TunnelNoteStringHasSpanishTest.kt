package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * TX-1 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §4.4): the "Secure tunnel established" note was an English
 * literal in ChatViewModel, so a Spanish phone showed it in English. It becomes `chat_note_tunnel_established` in
 * `ui/common/values{,-es}/chat_surface_strings.xml`: the English is the words 2.13.0 wrote, the Spanish differs, neither
 * has a placeholder, and neither says "Vault" (2.13.0 names the mode Tunnel and the paid one Shielded).
 */
class TunnelNoteStringHasSpanishTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    @Test
    fun the_tunnel_note_has_english_and_spanish() {
        val en = assertNotNull(strings("values")[KEY], "no English $KEY")
        val es = assertNotNull(strings("values-es")[KEY], "no Spanish $KEY")
        assertEquals(ENGLISH, en, "$KEY: the English must be the words 2.13.0 writes today")
        assertNotEquals(en, es, "$KEY: the Spanish text is still English")
        for (text in listOf(en, es)) {
            assertFalse(Regex("""%\d?\$?[sd]""").containsMatchIn(text), "$KEY takes no placeholder: $text")
            assertFalse("Vault" in text, "$KEY says Vault: $text")
            assertFalse(text.isBlank())
        }
        assertEquals(true, es.startsWith("🚇"), "$KEY: the Spanish keeps the note's 🚇")
    }

    private fun strings(dir: String): Map<String, String> {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_surface_strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private companion object {
        const val KEY = "chat_note_tunnel_established"
        const val ENGLISH =
            "🚇 Secure tunnel established — this chat now uses Tunnel: free and instant, still end-to-end encrypted. " +
                "Change it anytime from the chat menu."
    }
}
