package co.electriccoin.zcash.ui.regression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1219 (app-texts): the AI tab showed English on a Spanish phone (Top up, Top-up history, Ask anything, Message AI...,
 * Model, Free, Previous chats, Multi-turn...). Wanted: no user-facing English literal in the AI screen files (all of it
 * is a string resource, ui/common/values{,-es}/ai_strings.xml), and the English and Spanish files hold the same keys.
 */
class AiTabHasNoHardCodedTextsTest {
    private val viewFiles =
        listOf("AiTabView", "AiTopupSheet", "AiTopupHistorySheet", "MarkdownText", "UsdAmountInput", "AndroidAiTab")

    private fun literalsWithText(file: String): List<String> {
        val found = mutableListOf<String>()
        PolSourceFiles.main("screen/ai/$file.kt").readLines().forEachIndexed { index, line ->
            val s = line.trim()
            val skipped =
                s.startsWith("//") ||
                    s.startsWith("*") ||
                    s.startsWith("/*") ||
                    s.startsWith("import") ||
                    "Log." in s
            if (skipped) return@forEachIndexed
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line).forEach { m ->
                val t = m.groupValues[1].replace(Regex("""\$\{[^}]*\}|\$\w+"""), "")
                val letters = t.count { it.isLetter() }
                val code = Regex("^[A-Z_]+$").matches(t) || "yyyy" in t
                if (!code && letters >= 3 && (t.trim().contains(' ') || t.firstOrNull()?.isUpperCase() == true)) {
                    found += "$file.kt:${index + 1} \"${t.take(40)}\""
                }
            }
        }
        return found
    }

    @Test
    fun `no user text literal is left in the AI screen files`() {
        val left = viewFiles.flatMap { literalsWithText(it) }
        assertEquals(emptyList(), left, "English literals in the AI screen (count ${left.size})")
    }

    @Test
    fun `the model price label says Free from a resource`() {
        val src = PolSourceFiles.main("screen/ai/AiApiClient.kt").readText()
        assertTrue("-> \"Free\"" !in src, "AiApiClient.priceLabel / priceLabelShort still return the English literal \"Free\"")
    }

    @Test
    fun `English and Spanish ai_strings hold the same keys`() {
        fun keys(v: String) =
            Regex("""<(?:string|plurals)\s+name="([^"]+)"""")
                .findAll(PolSourceFiles.res("common/$v/ai_strings.xml").readText())
                .map { it.groupValues[1] }
                .toSortedSet()
        assertEquals(keys("values"), keys("values-es"))
    }
}
