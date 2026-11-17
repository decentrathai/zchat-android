package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import java.io.File
import java.util.Locale

/**
 * Round 8 D8 — the app's string resources read straight from res/ui, for tests that check what a screen says
 * without an Android runtime. [strings] and [plurals] read every module's values (English) or values-es
 * (Spanish) files; [english] and [englishPlural] turn an R id into the English text the phone would show.
 */
internal object ChatResourceText {
    val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        dir ?: error("repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    fun source(path: String): String = File(repoRoot, path).readText()

    /** Every `<string>` in [dir] ("values" or "values-es") of every res/ui module, unescaped. */
    fun strings(dir: String): Map<String, String> =
        xmlFiles(dir).fold(emptyMap()) { acc, f ->
            acc +
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(f.readText())
                    .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }

    /** Every `<plurals>` in [dir]: name to (quantity to text). */
    fun plurals(dir: String): Map<String, Map<String, String>> =
        xmlFiles(dir).fold(emptyMap()) { acc, f ->
            acc +
                Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(f.readText())
                    .associate { p ->
                        p.groupValues[1] to
                            Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                                .findAll(p.groupValues[2])
                                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
                    }
        }

    private val englishStrings by lazy { strings("values") }
    private val englishPlurals by lazy { plurals("values") }

    /** The name of the string resource with [id], or null. */
    fun stringName(id: Int): String? =
        R.string::class.java.fields
            .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
            ?.name

    /** The id of the string resource [name], or null when this build has none. */
    fun stringId(name: String): Int? =
        R.string::class.java.fields
            .firstOrNull { it.name == name }
            ?.getInt(null)

    private fun pluralsName(id: Int): String? =
        R.plurals::class.java.fields
            .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
            ?.name

    /** The English text of string resource [id], as getString(id) returns it. */
    fun english(id: Int): String {
        val name = stringName(id) ?: error("no string resource with id $id")
        return englishStrings[name] ?: error("no English text for $name")
    }

    /** The English text of plurals resource [id] for [quantity], formatted with [args] (English: one / other). */
    fun englishPlural(
        id: Int,
        quantity: Int,
        args: List<Any>,
    ): String {
        val name = pluralsName(id) ?: error("no plurals resource with id $id")
        val items = englishPlurals[name] ?: error("no English plurals for $name")
        val pattern = (if (quantity == 1) items["one"] else null) ?: items.getValue("other")
        return String.format(Locale.ROOT, pattern, *args.toTypedArray())
    }

    private fun xmlFiles(dir: String): List<File> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module -> File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }

    private fun unescape(raw: String): String =
        raw
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
