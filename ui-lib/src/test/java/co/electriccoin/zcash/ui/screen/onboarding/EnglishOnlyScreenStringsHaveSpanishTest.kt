package co.electriccoin.zcash.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * ZC-232 (T7b triage 13 Oct, SMALL work order; also closes ZC-312): eleven texts are shown in English on a Spanish
 * phone, because their screen's `values-es` file has no line for them: onboarding identity (6) and the import
 * subtitle, the receive screen's warning for transparent funds, the two restore-QR errors and the swap's "no route"
 * error. Two more lines, the ticker names ZEC and TAZ, are not words to translate and are not marked so.
 *
 * Wanted: each of the eleven has a Spanish line (an agent draft for the reviewers' round) with the same placeholders;
 * `zcash_token_zec` and `zcash_token_taz` are `translatable="false"`. `tools/qa/string_parity.py` on the whole tree
 * then passes.
 */
class EnglishOnlyScreenStringsHaveSpanishTest {
    private val repo: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        dir ?: throw AssertionError("repo root not found")
    }

    private val keys =
        mapOf(
            "onboarding" to
                listOf(
                    "onboarding_import_existing_wallet_subtitle",
                    "onboarding_identity_title",
                    "onboarding_identity_subtitle",
                    "onboarding_identity_copy",
                    "onboarding_identity_share",
                    "onboarding_identity_continue",
                    "onboarding_identity_loading",
                ),
            "receive" to listOf("receive_transparent_shield_warning"),
            "restore" to listOf("restore_qr_scan_error_checksum", "restore_qr_scan_error_words"),
            "swap" to listOf("swap_quote_error_no_route"),
        )

    @Test
    fun every_english_only_screen_text_has_a_spanish_line_with_the_same_placeholders() {
        val missing = mutableListOf<String>()
        keys.forEach { (screen, names) ->
            val en = strings("ui-lib/src/main/res/ui/$screen/values/strings.xml")
            val es = strings("ui-lib/src/main/res/ui/$screen/values-es/strings.xml")
            names.forEach { name ->
                val english = en[name] ?: throw AssertionError("fixture: no English $name")
                val spanish = es[name]
                if (spanish == null) {
                    missing += "$screen/$name"
                } else {
                    assertNotEquals("$name is not translated", english, spanish)
                    assertEquals("$name: the Spanish placeholders differ", placeholders(english), placeholders(spanish))
                }
            }
        }
        assertEquals("English-only texts on a Spanish phone", emptyList<String>(), missing)
    }

    @Test
    fun the_spanish_lines_use_the_apps_own_words() {
        val receive = strings("ui-lib/src/main/res/ui/receive/values-es/strings.xml")["receive_transparent_shield_warning"].orEmpty()
        val restore = strings("ui-lib/src/main/res/ui/restore/values-es/strings.xml")
        val checksum = restore["restore_qr_scan_error_checksum"].orEmpty()
        assertTrue("the Shield action is «proteger» in Spanish: $receive", "proteg" in receive.lowercase())
        assertTrue("a recovery phrase is «frase de recuperación»: $checksum", "frase de recuperación" in checksum)
        listOf(checksum, restore["restore_qr_scan_error_words"].orEmpty()).forEach { line ->
            assertTrue("«semilla» is a retired word: $line", "semilla" !in line.lowercase())
        }
    }

    @Test
    fun the_ticker_names_are_not_translatable() {
        val file = File(repo, "sdk-ext-lib/src/main/res/values/strings.xml").readText()
        listOf("zcash_token_zec", "zcash_token_taz").forEach { name ->
            val line = Regex("""<string[^>]*name="$name"[^>]*>""").find(file)?.value.orEmpty()
            assertTrue("no $name in sdk-ext-lib", line.isNotEmpty())
            assertTrue("$name is not marked translatable=\"false\": $line", "translatable=\"false\"" in line)
        }
    }

    private fun strings(path: String): Map<String, String> {
        val file = File(repo, path)
        if (!file.isFile) fail("expected ${file.path}")
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sdf]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()
}
