package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1755 (QA2 p3c, Fable review of P3b, finding 2; supersedes the layout half of ZC-1705): the chat header at the default
 * font. P3b put the nickname hint of an unnamed chat on its own third line but grew the bar only when the font scale is
 * above 1. The header texts set only `fontSize`, so each takes the 28 sp line height of the app bar's title style
 * (Material 3 titleLarge): name 28 + chip row 28 + hint 28 = 84 dp in a 64 dp bar, and the bar clips its title. The hint
 * "Set nickname" / "Poner apodo" lost its line on every unnamed chat at 1.0x, where before P3b it was visible in the chip row.
 *
 * Wanted:
 * - every text of the header carries its own line height (the name 17 sp, the address, hint and offline texts 12 sp, the
 *   mode chip 11 sp): about 1.3 times the size, the hint about 16 sp;
 * - the bar height comes from the lines it holds at every font scale, 1.0 included: `chatHeaderBarHeightDp(fontScale,
 *   hasHint)` (ChatHeaderMetrics.kt) is at least name line + chip row + hint line (the hint taking two lines from 1.5x,
 *   where the one-line hint no longer fits beside the avatar), never below the 64 dp default, and not much taller than that.
 *
 * The drawing itself is a phone check: an unnamed chat at 1.0x and at 1.3x shows the whole hint, a named chat at both shows
 * name and chip row clear of the first message.
 *
 * Source and arithmetic checks; the function is read by reflection so a missing helper fails this class, not the build of
 * every other test. Synthetic input only.
 */
class ChatHeaderLineHeightsQa2Test {
    private val detail by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
    }

    /** The parameters of the `Text(` whose first line is [anchor], up to the first line holding only the closing bracket. */
    private fun textCall(anchor: String): String {
        val match = Regex("(?s)" + Regex.escape(anchor) + ".*?\\n\\s*\\)").find(detail)
        return assertNotNull(match, "ChatDetailView.kt has no header text with `$anchor`").value
    }

    private fun lineHeightOf(anchor: String): Int {
        val call = textCall(anchor)
        val found = Regex("""lineHeight\s*=\s*(\d+)\.sp""").find(call)
        return assertNotNull(found, "the header text `$anchor` has no explicit lineHeight").groupValues[1].toInt()
    }

    private fun fontSizeOf(anchor: String): Int =
        assertNotNull(Regex("""fontSize\s*=\s*(\d+)\.sp""").find(textCall(anchor)), "no fontSize on `$anchor`").groupValues[1].toInt()

    private val anchors =
        mapOf(
            "name" to "text = conversation.displayName,",
            "address" to "text = \" · \$subText\",",
            "hint" to "text = stringResource(R.string.chat_nickname_hint),",
            "offline" to "text = stringResource(R.string.chat_header_offline),",
            "chip" to "text = modeLabel,",
        )

    @Test
    fun `every text of the header has its own line height, about 1_3 times its size`() {
        for ((what, anchor) in anchors) {
            val size = fontSizeOf(anchor)
            val line = lineHeightOf(anchor)
            assertTrue(
                line >= size + 2 && line * 10 <= size * 15,
                "the $what text ($size sp) has line height $line sp; wanted between ${size + 2} and ${size * 3 / 2} sp",
            )
        }
        val hint = lineHeightOf(anchors.getValue("hint"))
        assertTrue(hint in 15..17, "the nickname hint line height is $hint sp; wanted about 16 sp")
    }

    private fun heightDp(
        fontScale: Float,
        hasHint: Boolean,
    ): Float {
        val type =
            try {
                Class.forName("co.electriccoin.zcash.ui.screen.chat.view.ChatHeaderMetricsKt")
            } catch (e: ClassNotFoundException) {
                throw AssertionError("ChatHeaderMetrics.kt does not exist: the bar height is not computed from the lines it holds", e)
            }
        val method = type.getMethod("chatHeaderBarHeightDp", Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        return method.invoke(null, fontScale, hasHint) as Float
    }

    private val chipRowDp = 24f // the lock's 24 dp box, the tallest part of the chip row at these scales

    private fun needed(
        fontScale: Float,
        hintLines: Int,
    ): Float {
        val name = lineHeightOf(anchors.getValue("name"))
        val hint = lineHeightOf(anchors.getValue("hint"))
        return (name + hintLines * hint) * fontScale + chipRowDp
    }

    @Test
    fun `an unnamed chat's bar holds name, chip row and hint at 1_0x and 1_3x`() {
        for (scale in listOf(1.0f, 1.3f)) {
            val height = heightDp(scale, hasHint = true)
            val lines = needed(scale, hintLines = 1)
            assertTrue(height >= lines, "at ${scale}x the unnamed bar is $height dp; its three lines need $lines dp")
            assertTrue(height >= 64f, "at ${scale}x the unnamed bar is $height dp, below the 64 dp default")
            assertTrue(height <= lines + 12f, "at ${scale}x the unnamed bar is $height dp, far taller than its lines ($lines dp)")
        }
    }

    @Test
    fun `the hint takes two lines in a bar from 1_5x`() {
        val scale = 2.0f
        val height = heightDp(scale, hasHint = true)
        val lines = needed(scale, hintLines = 2)
        assertTrue(height >= lines, "at ${scale}x the unnamed bar is $height dp; a two-line hint needs $lines dp")
    }

    @Test
    fun `a named chat's bar holds name and chip row at 1_0x and 1_3x and is not taller than needed`() {
        for (scale in listOf(1.0f, 1.3f)) {
            val height = heightDp(scale, hasHint = false)
            val lines = needed(scale, hintLines = 0)
            assertTrue(height >= lines, "at ${scale}x the named bar is $height dp; its two lines need $lines dp")
            assertTrue(height >= 64f, "at ${scale}x the named bar is $height dp, below the 64 dp default")
            assertTrue(height <= maxOf(64f, lines + 12f), "at ${scale}x the named bar is $height dp, far taller than its lines ($lines dp)")
        }
    }

    @Test
    fun `the bar height comes from the helper at every scale, not only above 1`() {
        val top = detail.indexOf("topBar = {")
        assertTrue(top >= 0, "no topBar in ChatDetailView")
        val region = detail.substring(top, detail.indexOf("colors = TopAppBarDefaults.topAppBarColors(", top))
        assertTrue(
            Regex("""expandedHeight\s*=\s*chatHeaderBarHeightDp\(\s*fontScale\s*,\s*hasHint\s*=\s*!conversation\.hasContactName\s*\)""")
                .containsMatchIn(region),
            "the app bar height is not chatHeaderBarHeightDp(fontScale, hasHint = !conversation.hasContactName)",
        )
        assertTrue("fontScale > 1f" !in region, "the bar still grows only when the font scale is above 1")
        assertEquals(-1, region.indexOf("hintLineExtra"), "the one-off hint allowance (hintLineExtra) is still there")
    }
}
