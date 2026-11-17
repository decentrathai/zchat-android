package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.5): every text F4 adds exists in English and Spanish with the same
 * placeholders, and the two reasons the chat keeps on a row proven not sent are the exact English the app records
 * (so the bubble shows them in the phone's language). None promises that ZCHAT keeps trying.
 */
class MaybeSentStringsHaveSpanishTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val keys =
        mapOf(
            "chat_send_row_waiting_for_network" to listOf("%1\$s", "%2\$d"),
            "chat_send_row_checking" to emptyList(),
            "chat_send_row_mined_unattributed" to emptyList(),
            "chat_send_row_partly_sent" to emptyList(),
            "chat_send_failed_expired_not_sent" to emptyList(),
            "chat_send_failed_not_on_chain" to emptyList(),
            "chat_note_attempt_landed_late" to listOf("%1\$s"),
            "chat_delete_message_body_expired" to emptyList(),
            "chat_delete_message_body_not_on_chain" to emptyList(),
        )

    @Test
    fun `every F4 text has English and Spanish with the same placeholders`() {
        val english = strings("values")
        val spanish = strings("values-es")
        for ((key, placeholders) in keys) {
            val en = assertNotNull(english[key], "no English $key")
            val es = assertNotNull(spanish[key], "no Spanish $key")
            assertNotEquals(en, es, "$key: the Spanish text is still English")
            assertEquals(placeholders.sorted(), placeholdersOf(en), "$key: English placeholders")
            assertEquals(placeholdersOf(en), placeholdersOf(es), "$key: Spanish placeholders")
            for (promise in listOf("keeps trying", "keep trying", "will retry", "sigue intentando", "seguirá intentando")) {
                assertFalse(promise in en.lowercase() || promise in es.lowercase(), "$key promises \"$promise\"")
            }
        }
    }

    @Test
    fun `the recorded reasons are the exact English of their resources`() {
        val english = strings("values")
        for (
        (text, key) in
        listOf(
            ChatSendCopy.FAILED_EXPIRED_NOT_SENT to "chat_send_failed_expired_not_sent",
            ChatSendCopy.FAILED_NOT_ON_CHAIN to "chat_send_failed_not_on_chain",
        )
        ) {
            assertEquals(text, english[key], "$key must be the exact text the row records")
            val id = assertNotNull(ChatSendCopy.localizedResource(text), "no translation for: $text")
            assertEquals(R.string::class.java.getField(key).getInt(null), id)
        }
    }

    private fun placeholdersOf(text: String): List<String> =
        Regex("""%\d\$[sd]""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    private fun strings(dir: String): Map<String, String> {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_send_strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }
}
