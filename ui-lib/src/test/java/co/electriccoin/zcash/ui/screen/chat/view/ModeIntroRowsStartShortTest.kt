package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1457 (owner findings 2 Nov, point 1; FIX_PLAN REVISION 1, B2; L6): the first-run "Choose how each chat travels"
 * dialog (ConversationModeIntroDialog) stacks three long bodies (~90 words for Shielded alone). The owner wants three
 * short options and the details only on a tap.
 *
 * Wanted, in `screen.chat.model`: `object ModeIntroCopy { val rows: List<ModeIntroItem> }` with
 * `data class ModeIntroItem(val mode: ConversationMode, @StringRes val subtitleRes: Int, @StringRes val bodyRes: Int)`,
 * rows Shielded, Tunnel, Open in that order:
 * - the subtitle is one short line in English (≤ 40 characters) and Spanish (≤ 52), and names the cost (Shielded and
 *   Tunnel: ZEC; Open: free), app-texts rule 2;
 * - the body is the existing long text, unchanged in English (the honesty tests keep reading MODE_INTRO_*), with Spanish;
 * - the dialog draws the rows collapsed (a saved expand state per row, rememberSaveable) and the body only when expanded;
 *   the text slot keeps its verticalScroll (ModeDialogsScrollAtLargeFontTest).
 *
 * The new API is reached by name, so this file compiles before it exists.
 */
class ModeIntroRowsStartShortTest {
    private data class Row(
        val mode: ConversationMode,
        val subtitle: String,
        val body: String,
    )

    private val rows: List<Row> by lazy {
        val copy =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ModeIntroCopy") }.getOrElse {
                throw AssertionError("no co.electriccoin.zcash.ui.screen.chat.model.ModeIntroCopy: the intro still shows three long bodies")
            }
        val list = copy.getMethod("getRows").invoke(copy.getField("INSTANCE").get(null)) as List<*>
        list.map { item ->
            val cls = item!!.javaClass

            fun res(getter: String): String {
                val id = cls.getMethod(getter).invoke(item) as Int
                return ChatResourceText.stringName(id) ?: throw AssertionError("no string resource with id $id")
            }
            Row(cls.getMethod("getMode").invoke(item) as ConversationMode, res("getSubtitleRes"), res("getBodyRes"))
        }
    }

    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun three_rows_shielded_tunnel_open() {
        assertEquals(listOf(ConversationMode.VAULT, ConversationMode.TUNNEL, ConversationMode.OPEN), rows.map { it.mode })
    }

    @Test
    fun each_subtitle_is_one_short_line_that_names_the_cost() {
        for (row in rows) {
            val english = assertNotNull(en[row.subtitle], "values has no ${row.subtitle}")
            val spanish = assertNotNull(es[row.subtitle], "values-es has no ${row.subtitle}")
            assertNotEquals(english, spanish, "${row.subtitle} is not translated")
            assertTrue(english.length <= EN_MAX, "${row.mode} subtitle is ${english.length} characters: $english")
            assertTrue(spanish.length <= ES_MAX, "${row.mode} Spanish subtitle is ${spanish.length} characters: $spanish")
            assertFalse('\n' in english || '\n' in spanish, "${row.mode} subtitle is more than one line")
            when (row.mode) {
                ConversationMode.VAULT, ConversationMode.TUNNEL -> {
                    assertTrue("ZEC" in english && "ZEC" in spanish, "${row.mode} subtitle must name its ZEC cost: $english / $spanish")
                }
                ConversationMode.OPEN -> {
                    assertTrue("free" in english.lowercase() && "gratis" in spanish.lowercase(), "Open subtitle: $english / $spanish")
                }
            }
        }
    }

    @Test
    fun the_long_text_is_kept_unchanged_behind_the_tap() {
        val expected =
            mapOf(
                ConversationMode.VAULT to MODE_INTRO_SHIELDED_BODY,
                ConversationMode.TUNNEL to MODE_INTRO_TUNNEL_BODY,
                ConversationMode.OPEN to MODE_INTRO_OPEN_BODY,
            )
        for (row in rows) {
            assertEquals(expected[row.mode], en[row.body], "${row.mode} body (${row.body}) must stay the pinned English")
            val spanish = assertNotNull(es[row.body], "values-es has no ${row.body}")
            assertNotEquals(en[row.body], spanish, "${row.body} is not translated")
        }
    }

    @Test
    fun the_dialog_starts_collapsed_and_shows_the_body_only_when_expanded() {
        val src = SourceScan.stripComments(ChatResourceText.source(CMU))
        val dialog = functionBody(src, "ConversationModeIntroDialog")
        assertTrue("ModeIntroCopy.rows" in dialog, "the dialog does not draw ModeIntroCopy.rows")
        // The expandable row: ModeIntroRow while it exists, else whatever the dialog inlines.
        val rowFun = if ("fun ModeIntroRow(" in src) functionBody(src, "ModeIntroRow") + dialog else dialog
        assertTrue("rememberSaveable" in rowFun || "rememberSaveable" in dialog, "no saved expand state (rotation loses it)")
        assertTrue(
            "AnimatedVisibility(" in rowFun || Regex("""if\s*\(\s*\w*[eE]xpanded\w*\s*\)""").containsMatchIn(rowFun),
            "the row does not hide its body until expanded",
        )
    }

    /** From `fun [name](` to the first top-level closing brace after it. */
    private fun functionBody(
        src: String,
        name: String,
    ): String {
        val at = src.indexOf("fun $name(")
        assertTrue(at >= 0, "no fun $name")
        val end = src.indexOf("\n}\n", at)
        assertTrue(end >= 0, "fun $name has no end")
        return src.substring(at, end + 2)
    }

    private companion object {
        const val CMU = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ConversationModeUi.kt"
        const val EN_MAX = 40
        const val ES_MAX = 52
    }
}
