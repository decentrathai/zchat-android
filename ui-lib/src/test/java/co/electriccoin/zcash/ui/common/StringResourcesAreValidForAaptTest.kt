package co.electriccoin.zcash.ui.common

import org.junit.Assert.assertTrue
import org.xml.sax.Attributes
import org.xml.sax.Locator
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory
import kotlin.test.Test

/**
 * ZC-1101: aapt2 rejects a string value with an apostrophe that is not written \' (and not inside a whole-value
 * "…" quote) or a malformed \u escape, and the APK build fails; the unit suite never runs aapt2. This test reads
 * every <string>, <plurals>/<item> and <string-array>/<item> of the main resources the way aapt2 does and names
 * file:line:name for each value it would refuse.
 */
class StringResourcesAreValidForAaptTest {
    private fun resDirs(): List<File> =
        listOf("src/main/res", "../app/src/main/res").map { File(it) }.filter { it.isDirectory }

    // aapt2's own state machine: an unescaped double quote opens or closes a quoted run, and inside a quoted run
    // an apostrophe is fine; outside one it must be \'. A quote left open at the end is refused. Other
    // backslash escapes (for example a typographic \') are accepted by aapt2, which drops the backslash.
    private fun problems(value: String): List<String> {
        val v = value.trim()
        val out = mutableListOf<String>()
        var inQuote = false
        var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c == '\\' -> {
                    val n = v.getOrNull(i + 1)
                    when {
                        n == null -> out += "trailing backslash"
                        n == 'u' -> {
                            val hex = v.substring(i + 2, minOf(i + 6, v.length))
                            if (hex.length != 4 || !hex.all { it in "0123456789abcdefABCDEF" }) {
                                out += "malformed \\u escape"
                            }
                            i += 1
                        }
                        else -> i += 1
                    }
                }
                c == '"' -> inQuote = !inQuote
                c == '\'' && !inQuote -> out += "unescaped apostrophe at $i"
            }
            i++
        }
        if (inQuote) out += "unbalanced double quote"
        return out
    }

    private fun isValuesDir(dir: File?): Boolean = dir?.name.orEmpty().startsWith("values")

    @Test
    fun everyStringValueIsAcceptedByAapt2() {
        val bad = mutableListOf<String>()
        var checked = 0
        for (res in resDirs()) {
            res
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" && isValuesDir(it.parentFile) }
                .forEach { file ->
                    val parser = SAXParserFactory.newInstance().newSAXParser()
                    parser.parse(
                        file,
                        object : DefaultHandler() {
                            lateinit var loc: Locator
                            var name = ""
                            var line = 0
                            var depth = 0
                            var inValue = false
                            var plural = ""
                            val text = StringBuilder()

                            override fun setDocumentLocator(locator: Locator) {
                                loc = locator
                            }

                            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                                when {
                                    !inValue && (q == "string" || q == "item") -> {
                                        inValue = true
                                        depth = 0
                                        text.setLength(0)
                                        line = loc.lineNumber
                                        name = a.getValue("name") ?: plural
                                    }
                                    !inValue && (q == "plurals" || q == "string-array") ->
                                        plural = a.getValue("name") ?: ""
                                    inValue -> depth++
                                }
                            }

                            override fun characters(ch: CharArray, s: Int, n: Int) {
                                if (inValue) text.append(ch, s, n)
                            }

                            override fun endElement(u: String?, l: String?, q: String) {
                                if (inValue && depth > 0) {
                                    depth--
                                } else if (inValue) {
                                    inValue = false
                                    checked++
                                    problems(text.toString()).forEach {
                                        bad += "${file.path}:$line:$name: $it"
                                    }
                                }
                            }
                        },
                    )
                }
        }
        assertTrue("no string resources found", checked > 0)
        assertTrue(
            "aapt2 would refuse ${bad.size} string value(s):\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }
}
