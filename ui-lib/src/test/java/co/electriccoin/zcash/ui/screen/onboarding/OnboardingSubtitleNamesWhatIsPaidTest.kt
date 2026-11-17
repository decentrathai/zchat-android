package co.electriccoin.zcash.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-962 (review 4 of train T7a1, 14 Oct, finding 2; skill app-texts rule 2): the onboarding identity subtitle says
 * friends can message you "for free" (Spanish «gratis»), but a Shielded message is a Zcash transaction and costs a small
 * fee (at least 0.00017 ZEC for a short one). The claim is true only for the relay modes.
 *
 * Wanted, in English and Spanish: the subtitle still asks to share the invite code, names what pays and keeps the mode
 * names as the picker shows them. It stays short enough for the subtitle under the title (about three lines on a 360 dp
 * screen), and the old sentences are gone.
 *
 * QA2 Q2-103 (W-137, ZC-1594): "Open and Tunnel messages are free" hid that Tunnel's setup pays on-chain. The subtitle
 * now says Tunnel pays its setup and Shielded pays per message (NewChatAndModeTextsSayWhatPaysTest pins the sentences);
 * the limits grew by one short clause (English 90 to 105 characters, Spanish 120 to 130).
 */
class OnboardingSubtitleNamesWhatIsPaidTest {
    private val repo: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        dir ?: throw AssertionError("repo root not found")
    }

    private fun subtitle(values: String): String {
        val file = File(repo, "ui-lib/src/main/res/ui/onboarding/$values/strings.xml")
        assertTrue("fixture: no ${file.path}", file.isFile)
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<string name="$KEY"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace("\\'", "'")
            ?: throw AssertionError("$values has no $KEY")
    }

    @Test
    fun `the English subtitle says Tunnel setup and Shielded messages pay`() {
        val en = subtitle("values")
        assertFalse("the English subtitle still promises every message for free", en == OLD_EN)
        assertFalse("the English subtitle still calls Tunnel free: $en", en == OLD_EN_TUNNEL_FREE)
        assertTrue("the English subtitle does not say you pay a Tunnel setup: $en", en.contains("in Tunnel you pay a setup"))
        assertTrue("the English subtitle no longer asks to share the invite code: $en", en.contains("invite code"))
        listOf("Open", "Tunnel", "Shielded").forEach { mode ->
            assertTrue("the English subtitle does not name $mode: $en", en.contains(mode))
        }
        assertTrue("the English subtitle does not say Shielded charges per message: $en", en.contains("in Shielded per message"))
        assertTrue("the English subtitle is longer than $MAX_EN characters (${en.length})", en.length <= MAX_EN)
    }

    @Test
    fun `the Spanish subtitle says the same`() {
        val es = subtitle("values-es")
        assertFalse("the Spanish subtitle still promises every message «gratis»", es == OLD_ES)
        assertFalse("the Spanish subtitle still calls Tunnel free: $es", es == OLD_ES_TUNNEL_FREE)
        assertTrue("the Spanish subtitle does not say Tunnel pays: $es", es.contains("Tunnel paga"))
        assertTrue("the Spanish subtitle no longer asks to share the invite code: $es", es.contains("código de invitación"))
        listOf("Open", "Tunnel", "Shielded").forEach { mode ->
            assertTrue("the Spanish subtitle does not name $mode: $es", es.contains(mode))
        }
        assertTrue("the Spanish subtitle does not say Shielded pays: $es", es.contains("Shielded paga"))
        assertTrue("the Spanish subtitle is longer than $MAX_ES characters (${es.length})", es.length <= MAX_ES)
    }

    @Test
    fun `control - the subtitle has no placeholder in either language`() {
        val both = subtitle("values") + subtitle("values-es")
        val placeholders = Regex("%[0-9]*\\$?[sd]").findAll(both).map { it.value }.toList()
        assertEquals(emptyList<String>(), placeholders)
    }

    private companion object {
        const val KEY = "onboarding_identity_subtitle"
        const val OLD_EN = "Share your invite code so friends can message you for free."
        const val OLD_ES = "Comparte tu código de invitación para que tus amigos puedan escribirte gratis."
        const val OLD_EN_TUNNEL_FREE =
            "Share your invite code. Open and Tunnel messages are free; Shielded ones cost a small fee."
        const val OLD_ES_TUNNEL_FREE =
            "Comparte tu código de invitación. Los mensajes Open y Tunnel son gratis; los Shielded cuestan una " +
                "pequeña comisión."
        const val MAX_EN = 105

        // W-137 ZC-1709: back to 130; the Spanish line is the 128-character "Open es gratis; en Tunnel pagas ..." wording.
        const val MAX_ES = 130
    }
}
