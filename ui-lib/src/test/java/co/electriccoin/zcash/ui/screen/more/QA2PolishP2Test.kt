package co.electriccoin.zcash.ui.screen.more

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 polish lane p2 (interim phone pass of 2026-11-11, PASS_RESULT.md; FIX_LIST Q2-88, Q2-93, Q2-100).
 *
 * ZC-1704 Spanish leftovers (app-texts rules 1 and 9; the Spanish lines are agent drafts for the native-speaker round):
 *  - the Spanish What's New names the app's own Spanish labels ("Mi dirección", "Más"), says "Más →" twice, and calls
 *    the old Team contact by its Spanish resource name, never the English stored label;
 *  - the Tor screen's "Share feedback" button has a Spanish value;
 *  - the Spanish About headings are sentence case like every other Spanish heading.
 *  [no-red] The transaction-detail memo "🔐 ZCHAT message" is built in MemoDisplayText.kt, which another lane rewrites.
 *
 * ZC-1703 (Q2-93) the Tor screen closes with the back arrow of the other settings screens, not an X.
 * ZC-1707 (Q2-100, Q2-89/Advanced) the About footer line is not dim; the Advanced list has a bottom inset so its
 *  footnote is not cut. [no-red] Q2-88 back arrow peeking out behind the Limitations dialog: Compose layout,
 *  phone check.
 */
class QA2PolishP2Test {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun source(path: String) = File(repoRoot, path).readText()

    private fun strings(dir: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        listOf("ui-lib/src/main/res/ui", "ui-design-lib/src/main/res/ui")
            .map { File(repoRoot, it) }
            .flatMap { root -> root.listFiles().orEmpty().toList() }
            .flatMap { module -> File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .forEach { f ->
                val text = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(text)
                    .forEach { out[it.groupValues[1]] = it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }
            }
        return out
    }

    private val en by lazy { strings("values") }
    private val es by lazy { strings("values-es") }

    /** The 2.13.0 entry of a What's New file (from its heading to the next `## [`). */
    private fun entry(lang: String): String {
        val lines = source("docs/whatsNew/WHATS_NEW_$lang.md").lines()
        val start = lines.indexOfFirst { it.startsWith("## [2.13.0") }
        assertTrue(start >= 0, "$lang: no 2.13.0 entry")
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("## [") }
        return rest.take(if (end < 0) rest.size else end).joinToString("\n")
    }

    /** The whole `Text(...)` call that holds [anchor], found by balancing parentheses. */
    private fun callAround(src: String, anchor: String): String {
        val at = src.indexOf(anchor)
        assertTrue(at >= 0, "anchor not found: $anchor")
        val start = src.lastIndexOf("Text(", at)
        assertTrue(start >= 0, "no Text( before $anchor")
        var depth = 0
        var i = start + "Text".length
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced Text( around $anchor")
    }

    // ---- ZC-1704 text items -------------------------------------------------------------------------------

    @Test
    fun spanish_whats_new_uses_the_spanish_app_labels() {
        val text = entry("ES")
        assertFalse("(My Address)" in text, "ES What's New still says (My Address):\n$text")
        val myAddress = "(${es.getValue("chat_list_cd_my_address")})"
        assertTrue(myAddress in text, "ES What's New must name Mi dirección:\n$text")
        assertFalse("More →" in text, "ES What's New still says More →:\n$text")
        val path = "${es.getValue("settings_title")} → ${es.getValue("settings_feedback")}"
        assertEquals(2, Regex(Regex.escape(path)).findAll(text).count(), "expected \"$path\" twice:\n$text")
    }

    @Test
    fun spanish_whats_new_calls_the_old_team_contact_by_its_spanish_name() {
        val text = entry("ES")
        assertFalse(
            en.getValue("contact_team_old_renamed") in text,
            "ES What's New still carries the English stored contact name:\n$text"
        )
        assertTrue(
            es.getValue("contact_team_old_renamed") in text,
            "ES What's New must carry the Spanish resource name of the old Team contact:\n$text"
        )
    }

    @Test
    fun control_english_whats_new_names_the_english_app_labels() {
        val text = entry("EN")
        assertTrue("(${en.getValue("chat_list_cd_my_address")})" in text, text)
        assertTrue("${en.getValue("settings_title")} → ${en.getValue("settings_feedback")}" in text, text)
        assertTrue(en.getValue("contact_team_old_renamed") in text, text)
    }

    @Test
    fun the_tor_share_feedback_button_is_spanish() {
        assertEquals("Share feedback", en["tor_settings_share_feedback"])
        assertEquals("Enviar comentarios", es["tor_settings_share_feedback"])
    }

    @Test
    fun spanish_about_headings_are_sentence_case() {
        assertEquals("Por qué ZCHAT es diferente", es["about_privacy_title"])
        assertEquals("Sin registro", es["about_privacy_no_signup"])
        assertEquals("Código abierto y auditable", es["about_privacy_open_source"])
        // The English headings stay as they are.
        assertEquals("Why ZCHAT is Different", en["about_privacy_title"])
    }

    // ---- ZC-1703 (Q2-93) Tor closes with a back arrow -----------------------------------------------------

    @Test
    fun the_tor_screen_uses_the_back_arrow_not_the_x() {
        val base =
            source(
                "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/ZashiBaseSettingsOptIn.kt"
            )
        val param = Regex("""useBackArrow:\s*Boolean\s*=\s*false""")
        assertTrue(param.containsMatchIn(base), "no useBackArrow parameter (default false)")
        // ZC-1718 (p4): the arrow may be the top bar's own ZashiTopAppBarBackNavigation instead of a hand-drawn icon.
        assertTrue(
            "ic_zashi_navigation_back" in base || "ZashiTopAppBarBackNavigation" in base,
            "ZashiBaseSettingsOptIn must be able to draw the back arrow"
        )
        val tor = source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/tor/settings/TorSettingsView.kt")
        val ask = Regex("""useBackArrow\s*=\s*true""")
        assertTrue(ask.containsMatchIn(tor), "TorSettingsView must ask for the back arrow")
    }

    // ---- ZC-1707 (Q2-100, Advanced footnote) --------------------------------------------------------------

    @Test
    fun the_about_footer_line_is_not_dim() {
        val view = source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/about/view/AboutView.kt")
        val call = callAround(view, "R.string.about_privacy_footer")
        assertTrue("ZashiColors.Text.textPrimary" in call, "About footer must use textPrimary:\n$call")
        assertFalse("textTertiary" in call, "About footer is still the dim tertiary colour:\n$call")
    }

    @Test
    fun the_advanced_list_has_a_bottom_inset() {
        // ZC-1717 (p4): the first inset (consumeWindowInsets(paddingValues) then navigationBars) resolved to 0 dp. The
        // list now ends above the navigation bar through the Scaffold's own bottom padding (scaffoldScrollPadding
        // adds paddingValues' bottom, which holds the bar), and nothing cancels it.
        val view =
            source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/AdvancedSettingsView.kt")
        assertTrue(
            "scaffoldScrollPadding(paddingValues)" in view || "paddingValues.calculateBottomPadding()" in view,
            "AdvancedSettingsView must apply the Scaffold's bottom padding below its footnote and Reset button"
        )
    }
}
