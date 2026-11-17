package co.electriccoin.zcash.ui.screen.reviewtransaction

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 Q2-01 (ZC-1596, W-137 batch B1): significant ZEC digits were drawn as tiny subscripts ("0.000₈", fee
 * "0.000₁") on the Wallet headline, the Send headline, the CONFIRMATION amount/fee/total and the Support send headline.
 * The cause is the split-digit style: StyledBalance draws the digits after the fourth decimal in its
 * leastSignificantPart style, 8.sp on Review (FinancialInfoWidget) and textXs (11.sp) under the 48.sp header2 headline
 * (BalanceWidgetHeader, shared by Wallet, Home, Send, Pay and Review).
 *
 * What it must be (FIX_LIST Q2-01): the trailing digits at the leading size, or at least 60 % of it and never under
 * 12.sp. Read from the sources (each StyledBalanceDefaults.textStyles call in ui-lib, sizes resolved from
 * ZashiTypographyInternal and any `fontSize = N.sp` override), so this file compiles before the fix.
 */
class TrailingZecDigitsAreLegibleTest {
    private val root = ChatResourceText.repoRoot

    private val typography: Map<String, Int> by lazy {
        val src =
            File(root, "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/theme/typography/ZashiTypographyInternal.kt")
                .readText()
        Regex("""val (\w+): TextStyle =\s*TextStyle\(\s*fontSize = (\d+)\.sp""")
            .findAll(src)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
            .also { assertTrue(it.size > 10, "could not read the typography sizes") }
    }

    /** The text inside the parentheses that open at [open] (the index of '('). */
    private fun balanced(
        src: String,
        open: Int
    ): String {
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                }
            }
        }
        fail("unbalanced parentheses")
    }

    private fun sizeOf(
        expr: String,
        where: String
    ): Int {
        Regex("""fontSize\s*=\s*(\d+)\.sp""").find(expr)?.let { return it.groupValues[1].toInt() }
        val token = Regex("""ZashiTypography\.(\w+)""").find(expr)?.groupValues?.get(1)
        return token?.let { typography[it] } ?: fail("$where: cannot read the size of `${expr.trim()}`")
    }

    private val uiLibSources: List<File> by lazy {
        File(root, "ui-lib/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    @Test
    fun every_split_balance_draws_its_trailing_digits_legibly() {
        var calls = 0
        for (file in uiLibSources) {
            val src = file.readText()
            var from = 0
            while (true) {
                val at = src.indexOf("StyledBalanceDefaults.textStyles(", from)
                if (at < 0) break
                calls++
                val args = balanced(src, src.indexOf('(', at))
                val where = "${file.name}:${src.substring(0, at).count { it == '\n' } + 1}"
                val mostAt = args.indexOf("mostSignificantPart")
                val leastAt = args.indexOf("leastSignificantPart")
                if (mostAt < 0 || leastAt < 0) fail("$where: name both parts, the defaults draw the tail at 11.sp")
                val mostExpr = if (mostAt < leastAt) args.substring(mostAt, leastAt) else args.substring(mostAt)
                val leastExpr = if (leastAt < mostAt) args.substring(leastAt, mostAt) else args.substring(leastAt)
                val most = sizeOf(mostExpr.substringAfter('='), where)
                val least = sizeOf(leastExpr.substringAfter('='), where)
                assertTrue(least >= MIN_TAIL_SP, "$where: trailing ZEC digits at $least.sp (leading $most.sp); at least 12.sp")
                assertTrue(
                    least * 10 >= most * 6,
                    "$where: trailing ZEC digits at $least.sp under a $most.sp lead; at least 60 % of the lead"
                )
                from = at + 1
            }
        }
        assertTrue(calls >= 2, "expected the Review and the balance-header split styles, found $calls")
    }

    @Test
    fun review_has_no_8sp_digits() {
        val review =
            File(
                root,
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/reviewtransaction/ReviewTransactionView.kt"
            ).readText()
        assertFalse(Regex("""fontSize\s*=\s*8\.sp""").containsMatchIn(review), "ReviewTransactionView still draws ZEC digits at 8.sp")
    }

    private companion object {
        const val MIN_TAIL_SP = 12
    }
}
