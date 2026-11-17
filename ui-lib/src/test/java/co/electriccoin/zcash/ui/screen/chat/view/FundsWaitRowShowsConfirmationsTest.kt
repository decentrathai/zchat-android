package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.confirmationProgress
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1271, ZC-1290: a row waiting for its funds says how far they are, "N of 10" and the minutes left, from
 * [confirmationProgress]. Today every slow on-chain row says "Keep ZChat open; if it stops trying, Retry shows why",
 * which tells the user nothing and names a Retry that row does not have.
 *
 * The seam, in English and Spanish: the string `chat_pending_funds_confirming`, taking the count (%1$d), the confirmations
 * needed (%2$d) and the minutes (%3$d), which ChatDetailView's pending row fills from a ConfirmationProgress. Read from the
 * sources, as HonestCostAndWaitCopyTest reads them: a JVM test cannot render the row. A phone check of the row is still owed.
 */
class FundsWaitRowShowsConfirmationsTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        assertNotNull(dir, "repo root not found")
    }

    private val strings: Map<String, Map<String, String>> by lazy {
        mapOf("values" to read("values"), "values-es" to read("values-es"))
    }

    private fun read(locale: String): Map<String, String> {
        val found = mutableMapOf<String, String>()
        File(repoRoot, "ui-lib/src/main/res/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name == locale }
            .forEach { file ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file)
                        .getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    found[node.getAttribute("name")] = node.textContent
                }
            }
        return found
    }

    @Test
    fun `the funds wait line takes the count, the confirmations needed and the minutes, in both languages`() {
        for ((locale, texts) in strings) {
            val line = assertNotNull(texts["chat_pending_funds_confirming"], "$locale has no chat_pending_funds_confirming")
            for (slot in listOf("%1\$d", "%2\$d", "%3\$d")) assertTrue(slot in line, "$locale funds wait line lacks $slot: $line")
        }
    }

    @Test
    fun `the pending row fills the funds wait line from the confirmation progress`() {
        val row =
            File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt").readText()
        val at = row.indexOf("R.string.chat_pending_funds_confirming")
        assertTrue(at >= 0, "the pending row does not use chat_pending_funds_confirming")
        val around = row.substring((at - WINDOW).coerceAtLeast(0), (at + WINDOW).coerceAtMost(row.length))
        for (field in listOf("confirmations", "required", "etaMinutes")) assertTrue(field in around, "the row does not show $field")
    }

    @Test
    fun `a slow on-chain row no longer says Retry shows why`() {
        assertFalse(
            "Retry shows why" in strings.getValue("values").getValue("chat_pending_onchain_slow"),
            "the slow row still says Retry shows why",
        )
        assertFalse(
            "Reintentar muestra el motivo" in strings.getValue("values-es").getValue("chat_pending_onchain_slow"),
            "the Spanish slow row still says Retry shows why",
        )
    }

    /** Guard, green before and after: the data the line shows, for a deposit 4 blocks deep. */
    @Test
    fun `guard - the progress reads 4 of 10 with the minutes left`() {
        val progress = assertNotNull(confirmationProgress(networkHeight = HEIGHT, minedHeight = HEIGHT - 3, trusted = false))
        assertEquals(4, progress.confirmations)
        assertEquals(10, progress.required)
        assertTrue(progress.etaMinutes > 0, "no minutes left at 4 of 10")
    }

    private companion object {
        const val WINDOW = 500
        const val HEIGHT = 3_500_000L
    }
}
