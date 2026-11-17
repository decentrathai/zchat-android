package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AI-KEY-R/T round 2 (W-97; ZC-1223; RT_ROUND2.md item 7, from GPT 4 and Grok's two P2s): the code does not know that credit
 * was paid, nor that an account is unlinked, in these lines, so they do not say it as fact. EN and ES.
 *
 * - LINKED shows for a linked account whose last paid answer was no. It says "Any AI credit you paid for is not deleted".
 * - KEPT shows for a missing, garbled or foreign link state, and a link may have succeeded with its answer lost.
 * - The Destroy PIN's NOT_YET has the same unknown. Both say "may hold paid credit" and "may not be linked".
 *
 * WHY IT FAILS TODAY: all three state the unknown as fact ("AI credit you paid for stays on ZCHAT's server, but it is not
 * linked"; "is not linked to your recovery phrase yet, ... the phrase could not open it").
 */
class ResetDoesNotStateUnknownCreditAsFactTest {
    private fun assertSays(
        key: String,
        value: String,
        wanted: List<String>,
        forbidden: List<String>,
    ) {
        for (word in wanted) assertTrue("$key lacks \"$word\": $value", word in value)
        for (word in forbidden) assertFalse("$key states as fact \"$word\": $value", word in value)
    }

    private fun forBothResetTexts(block: (file: String, base: String) -> Unit) {
        block(DW, "delete_wallet_checkbox_warning_unchecked")
        block(STR, "identity_reset_ai_note")
    }

    @Test
    fun `LINKED - English - paid credit is not asserted`() =
        forBothResetTexts { file, base ->
            val key = "${base}_ai_linked"
            val value = text(file, "values", key)
            assertSays(key, value, listOf("Any AI credit you paid for is not deleted", "opens it again"), emptyList())
            assertFalse("$key asserts paid credit: $value", Regex("""(?<!Any )AI credit you paid for""").containsMatchIn(value))
        }

    @Test
    fun `LINKED - Spanish - paid credit is not asserted`() =
        forBothResetTexts { file, base ->
            val key = "${base}_ai_linked"
            assertSays(
                key,
                text(file, "values-es", key),
                listOf("Cualquier saldo de IA que hayas pagado no se elimina", "se vuelve a abrir"),
                listOf("El saldo de IA que hayas pagado"),
            )
        }

    @Test
    fun `KEPT - English - may hold paid credit, may not be linked`() =
        forBothResetTexts { file, base ->
            val key = "${base}_ai_kept"
            assertSays(
                key,
                text(file, "values", key),
                listOf("may hold paid credit", "may not be linked"),
                listOf("AI credit you paid for", "it is not linked", "cannot open that credit yet"),
            )
        }

    @Test
    fun `KEPT - Spanish - puede tener saldo pagado, puede que no este vinculado`() =
        forBothResetTexts { file, base ->
            val key = "${base}_ai_kept"
            assertSays(
                key,
                text(file, "values-es", key),
                listOf("puede tener saldo pagado", "puede que todavía no esté vinculad"),
                listOf("El saldo de IA que hayas pagado", "no está vinculado", "todavía no puede abrir"),
            )
        }

    @Test
    fun `NOT_YET - English - may not be linked, and no claim that the phrase could not open it`() =
        assertSays(
            PIN_NOT_YET,
            text(STR, "values", PIN_NOT_YET),
            listOf("may hold paid credit", "may not be linked"),
            listOf("is not linked to your recovery phrase yet", "the phrase could not open it"),
        )

    @Test
    fun `NOT_YET - Spanish - puede que no este vinculada, and no claim that the phrase could not open it`() =
        assertSays(
            PIN_NOT_YET,
            text(STR, "values-es", PIN_NOT_YET),
            listOf("puede tener saldo pagado", "puede que todavía no esté vinculad"),
            listOf("todavía no está vinculada", "la frase no podría abrirla"),
        )

    private fun text(
        file: String,
        dir: String,
        key: String,
    ): String {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/$file".replace("{dir}", dir))
        assertTrue("missing ${xml.path}", xml.isFile)
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(xml)
                .getElementsByTagName("string")
        val value =
            (0 until nodes.length)
                .map { nodes.item(it) }
                .firstOrNull { it.attributes.getNamedItem("name").nodeValue == key }
                ?.textContent
                ?.replace("\\'", "'")
        assertNotNull("$dir: no $key in $file", value)
        return value!!
    }

    private val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "gradle.properties").isFile && File(it, "ui-lib").isDirectory }
            ?: error("repo root not found above the test working dir")
    }

    private companion object {
        const val DW = "delete_wallet/{dir}/strings.xml"
        const val STR = "common/{dir}/destroy_identity_strings.xml"
        const val PIN_NOT_YET = "destroy_pin_ai_not_yet"
    }
}
