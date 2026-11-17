package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.3 R3, §1.5, §1.4 step 7): a send the chain has not settled says what it is
 * waiting for, in English and Spanish, instead of "May already have been sent" for good.
 *
 * At HEAD every FAILED row whose submit started shows the one ROW_NOTICE, whatever the chain says. Wanted, from the
 * history pass's verdict on the row:
 *  - its transaction can still be mined: chat_send_row_waiting_for_network, with the block (%1$s) and the minutes
 *    (%2$d) after which, if it was not mined, it can be sent again;
 *  - nothing is known yet (no scanned tip, no expiry): chat_send_row_checking;
 *  - a mined payment to this contact may be it: chat_send_row_mined_unattributed;
 *  - part confirmed, part expired: chat_send_row_partly_sent;
 *  - once proven not sent, the row's reason is chat_send_failed_expired_not_sent (with Retry).
 * The chat screen shows the row notice through these resources. None of them says the send "keeps trying" (the SDK does
 * not rebroadcast a transaction that never reached its first submit, review point 10). The Retry refusal keeps its text.
 */
class WaitingRowShowsWaitingCopyTest {
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
        )

    @Test
    fun `every row text exists in English and Spanish with the same placeholders`() {
        val english = strings("values")
        val spanish = strings("values-es")
        for ((key, placeholders) in keys) {
            val en = assertNotNull(english[key], "no English $key")
            val es = assertNotNull(spanish[key], "no Spanish $key")
            assertNotEquals(en, es, "$key: the Spanish text is still English")
            assertEquals(placeholders.sorted(), placeholdersOf(en), "$key: English placeholders")
            assertEquals(placeholdersOf(en), placeholdersOf(es), "$key: Spanish placeholders differ")
            for (promise in listOf("keeps trying", "keep trying", "will retry", "sigue intentando")) {
                assertFalse(promise in en.lowercase() || promise in es.lowercase(), "$key promises \"$promise\"")
            }
        }
    }

    @Test
    fun `the chat screen shows the row texts for the pass's verdict`() {
        val sources = chatSources()
        for (key in listOf(
            "chat_send_row_waiting_for_network",
            "chat_send_row_checking",
            "chat_send_row_mined_unattributed",
            "chat_send_row_partly_sent"
        )) {
            assertTrue(sources.any { "R.string.$key" in it.readText() }, "no chat source shows R.string.$key")
        }
        val view = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt").readText()
        assertTrue("attemptVerdict" in view, "ChatDetailView does not read the pass's verdict for the row notice")
    }

    @Test
    fun `the Retry refusal keeps its text`() {
        assertTrue(SubmitAttemptProof.MAY_HAVE_BEEN_SENT.contains("may already have been sent", ignoreCase = true))
        assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, strings("values")["chat_send_may_have_been_sent"])
    }

    private fun chatSources(): List<File> =
        File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

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
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
