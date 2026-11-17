package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZecString
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 8 P9 (PHONE_TRIAGE ES-2) — a Spanish phone reads chat prices with an English decimal point: "…al menos
 * 0.00017 ZEC…" (logs/es/rel-09), while the balances on the same screens say "0,01320586 ZEC".
 *
 * `PaidActionCopy.zec` and `zecDisplay` (both new in 2.13.0) build the figure with `BigDecimal.toPlainString()`,
 * which knows no language. Wanted: both take the locale the screen formats with (rememberDesiredFormatLocale())
 * and write the figure the way the balances do, `Zatoshi.toZecString(locale)` (the SDK: 8 fraction digits), with the
 * trailing zeros and a trailing decimal separator trimmed. The large figure is compared with that formatter, not with a
 * literal: Android's ICU groups Spanish thousands differently from the JVM.
 *
 * Also wanted: no caller keeps a locale-less call, and no Spanish string resource writes a ZEC amount with a point.
 *
 * The two-argument functions are reached by reflection so that this file compiles before they exist.
 */
class ZecAmountFollowsLanguageTest {
    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.forLanguageTag("en-US")

    private fun invoke(
        target: Any?,
        owner: Class<*>,
        name: String,
        zatoshi: Zatoshi,
        locale: Locale,
    ): String {
        val method =
            runCatching { owner.getMethod(name, Zatoshi::class.java, Locale::class.java) }.getOrNull()
                ?: fail("${owner.simpleName}.$name(Zatoshi, Locale) does not exist: the figure cannot follow the language")
        return try {
            method.invoke(target, zatoshi, locale) as String
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun paidActionZec(
        zatoshi: Zatoshi,
        locale: Locale,
    ) = invoke(PaidActionCopy, PaidActionCopy::class.java, "zec", zatoshi, locale)

    private fun zecDisplay(
        zatoshi: Zatoshi,
        locale: Locale,
    ) = invoke(null, Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ZecDisplayKt"), "zecDisplay", zatoshi, locale)

    private val formatters: List<Pair<String, (Zatoshi, Locale) -> String>> =
        listOf("PaidActionCopy.zec" to ::paidActionZec, "zecDisplay" to ::zecDisplay)

    @Test
    fun spanish_writes_the_handshake_price_and_one_zatoshi_with_a_comma() {
        formatters.forEach { (name, format) ->
            assertEquals("0,00017", format(Zatoshi(17_000), es), "$name, es-ES, 17,000 zatoshi")
            assertEquals("0,00000001", format(Zatoshi(1), es), "$name, es-ES, 1 zatoshi")
        }
    }

    @Test
    fun english_keeps_the_point() {
        formatters.forEach { (name, format) ->
            assertEquals("0.00017", format(Zatoshi(17_000), en), "$name, en-US, 17,000 zatoshi")
            assertEquals("1", format(Zatoshi(100_000_000), en), "$name, en-US, 1 ZEC has no trailing separator")
            assertEquals("0", format(Zatoshi(0), en), "$name, en-US, nothing")
        }
    }

    /** A large amount reads exactly as the balance formatter writes it for the same language (grouping included). */
    @Test
    fun a_large_amount_matches_the_balance_formatter() {
        val large = Zatoshi(123_456_789_012L)
        formatters.forEach { (name, format) ->
            for (locale in listOf(es, en)) {
                assertEquals(large.toZecString(locale), format(large, locale), "$name, $locale, ${large.value} zatoshi")
            }
            // Trailing zeros go, and never a digit before the separator: 1,234.5 ZEC.
            val whole = Zatoshi(123_450_000_000L)
            for (locale in listOf(es, en)) {
                val full = whole.toZecString(locale)
                assertEquals(full.dropLast(7), format(whole, locale), "$name, $locale, ${whole.value} zatoshi")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ callers

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    /** The argument list of the call whose "(" is at [open], up to its matching ")". */
    private fun argumentsAt(
        text: String,
        open: Int,
    ): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        error("unbalanced call at $open")
    }

    /** How many top-level arguments [args] holds (commas outside nested brackets, braces and strings). */
    private fun argumentCount(args: String): Int {
        if (args.isBlank()) return 0
        var depth = 0
        var inString = false
        var count = 1
        for (c in args) {
            when {
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> count++
            }
        }
        // A trailing comma (Kotlin allows one) does not add an argument.
        return if (args.trimEnd().endsWith(",")) count - 1 else count
    }

    @Test
    fun no_caller_formats_a_price_without_the_language() {
        val call = Regex("""(?<!fun )(PaidActionCopy\s*\.\s*zec|\bzecDisplay)\s*\(""")
        val found =
            File(repoRoot, "ui-lib/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val text = file.readText()
                    call.findAll(text).mapNotNull { match ->
                        val open = match.range.last
                        val args = argumentsAt(text, open)
                        if (argumentCount(args) < 2) {
                            "${file.relativeTo(repoRoot)}:${text.substring(0, open).count { it == '\n' } + 1} " +
                                match.value.replace(Regex("\\s+"), "") + args.trim() + ")"
                        } else {
                            null
                        }
                    }
                }.toList()
        assertTrue(found.isEmpty(), "a ZEC price is still formatted without the phone's language:\n" + found.joinToString("\n"))
    }

    @Test
    fun no_spanish_string_writes_a_zec_amount_with_a_point() {
        val amount = Regex("""\d\.\d+\s*ZEC""")
        val found =
            File(repoRoot, "ui-lib/src/main/res")
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.parentFile?.name == "values-es" }
                .flatMap { file ->
                    // The xliff "example" attributes and comments are never shown.
                    val shown =
                        file
                            .readText()
                            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("""example="[^"]*""""), "")
                    amount.findAll(shown).map { "${file.relativeTo(repoRoot)}: ${it.value}" }
                }.toList()
        assertTrue(found.isEmpty(), "a Spanish string writes a ZEC amount with a point:\n" + found.joinToString("\n"))
    }
}
