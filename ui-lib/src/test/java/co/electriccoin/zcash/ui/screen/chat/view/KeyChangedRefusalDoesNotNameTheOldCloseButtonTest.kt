package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * ZC-1220 — the key-changed send refusal told the user to "tap × on this warning", but the banner's button is the
 * text button "Accept the new key" (chat_key_changed_accept_key); there is no × to tap. Neither refusal text (with and
 * without a remembered new key), English or Spanish, may name a "×" any more.
 */
class KeyChangedRefusalDoesNotNameTheOldCloseButtonTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun text(
        dir: String,
        name: String,
    ): String {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_send_strings.xml").readText()
        val pattern = Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return assertNotNull(pattern.find(xml), "$dir: $name is missing").groupValues[1]
    }

    private fun check(dir: String) {
        for (name in listOf("chat_key_changed_send_refused", "chat_key_changed_send_refused_no_key")) {
            val t = text(dir, name)
            assertFalse("×" in t, "$dir $name still says to tap ×, the banner has a text button: $t")
        }
    }

    @Test
    fun `English refusals do not name a x button`() = check("values")

    @Test
    fun `Spanish refusals do not name a x button`() = check("values-es")
}
