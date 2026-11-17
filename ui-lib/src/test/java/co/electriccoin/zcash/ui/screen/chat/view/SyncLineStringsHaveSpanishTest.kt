package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SY-2 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §3.4 with §9 row 7): the chat list's status line gets four
 * new states besides "Synced". Their keys use the prefix `chat_list_sync_line_` (`chat_list_status_*` is the
 * user-status dialog's) and live in `ui/common/values{,-es}/chat_leftover_strings.xml` next to
 * `chat_list_status_synced`, with the same placeholder in both languages. The Spanish "catching up" uses the verb
 * the catch-up bar above it uses («Sincronizando»), one verb for one process.
 */
class SyncLineStringsHaveSpanishTest {
    private val english =
        mapOf(
            "chat_list_sync_line_catching_up" to "Catching up %1\$d%",
            "chat_list_sync_line_offline" to "Offline",
            "chat_list_sync_line_stopped" to "Sync stopped",
            "chat_list_sync_line_connecting" to "Connecting…",
        )

    private fun leftovers(dir: String): String =
        File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_leftover_strings.xml").readText()

    @Test
    fun the_four_keys_are_next_to_synced_in_english_and_spanish() {
        for (dir in listOf("values", "values-es")) {
            val xml = leftovers(dir)
            assertTrue("<string name=\"chat_list_status_synced\"" in xml, "chat_list_status_synced left $dir")
            for (key in english.keys) assertTrue("<string name=\"$key\"" in xml, "$key is not in $dir/chat_leftover_strings.xml")
        }
    }

    @Test
    fun the_english_says_what_the_line_means() {
        val en = ChatResourceText.strings("values")
        for ((key, text) in english) assertEquals(text, en[key]?.replace("%%", "%"), key)
    }

    @Test
    fun the_spanish_differs_keeps_the_placeholder_and_uses_the_bars_verb() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val placeholders = Regex("""%\d\$[sd]""")
        for (key in english.keys) {
            val e = assertNotNull(en[key], "no English $key")
            val s = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(e, s, "$key: the Spanish is the English")
            assertEquals(
                placeholders.findAll(e).map { it.value }.toList(),
                placeholders.findAll(s).map { it.value }.toList(),
                "$key: English and Spanish use different placeholders: \"$e\" / \"$s\"",
            )
        }
        val bar = assertNotNull(es["upgrade_catch_up_title"], "no Spanish catch-up bar title")
        val line = assertNotNull(es["chat_list_sync_line_catching_up"])
        assertTrue(
            bar.startsWith("Sincronizando") && line.startsWith("Sincronizando"),
            "the line and the bar use different verbs: \"$line\" / \"$bar\""
        )
        assertEquals("Sin conexión", es["chat_list_sync_line_offline"])
    }
}
