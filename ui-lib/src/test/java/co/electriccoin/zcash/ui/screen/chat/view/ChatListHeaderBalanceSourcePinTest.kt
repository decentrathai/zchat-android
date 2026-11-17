package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 8 P11 (PHONE_TRIAGE ES-3) — the Chats header cut the balance to "0.0".
 *
 * JVM half of the pin; the layout itself is measured on a phone by the androidTest
 * ChatListHeaderBalanceNotClippedTest. The balance used to share one line with the avatar and the wordmark, in a
 * `Text(maxLines = 1)` that wraps by default, so a narrow title slot broke "0.01320586 ZEC" after "0.0" and hid the
 * rest. The header now lives in ChatListHeaderTitle: the wordmark on line 1, the balance alone on line 2 in a text
 * that never wraps (softWrap = false, maxLines = 1), and the USD suffix in weight(1f, fill = false) with an
 * Ellipsis, so the suffix is the only part that can shrink. The B12 tap (open the Wallet tab, label "Open wallet")
 * stays. The balance figure is unchanged: the SDK formatter's 8 decimals with trailing zeros trimmed.
 */
class ChatListHeaderBalanceSourcePinTest {
    private val headerPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListHeaderTitle.kt"
    private val listPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"

    private fun header(): String {
        val file = File(ChatResourceText.repoRoot, headerPath)
        assertTrue(file.isFile, "ChatListHeaderTitle.kt does not exist: the balance still sits inline in ChatListView")
        return file.readText()
    }

    /** The call (`Name(...)`) that encloses the first occurrence of [marker], from its name to its closing paren. */
    private fun enclosingCall(
        src: String,
        marker: String
    ): String {
        val at = src.indexOf(marker)
        assertTrue(at >= 0, "$marker is not in ChatListHeaderTitle.kt")
        var depth = 0
        var open = -1
        for (i in at downTo 0) {
            when (src[i]) {
                ')' -> depth++
                '(' ->
                    if (depth == 0) {
                        val name = Regex("""(\w+)\s*$""").find(src.substring(0, i))?.groupValues?.get(1)
                        if (name == "Text" || name == "BasicText") {
                            open = i
                            break
                        }
                    } else {
                        depth--
                    }
            }
        }
        if (open < 0) fail("$marker is not inside a Text or BasicText call")
        depth = 0
        for (j in open until src.length) {
            when (src[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open, j + 1)
                }
            }
        }
        fail("unbalanced call around $marker")
    }

    @Test
    fun the_balance_text_never_wraps() {
        val call = enclosingCall(header(), "CHAT_LIST_HEADER_BALANCE_TAG")
        assertTrue(Regex("""softWrap\s*=\s*false""").containsMatchIn(call), "the balance text may wrap:\n$call")
        assertTrue(Regex("""maxLines\s*=\s*1\b""").containsMatchIn(call), "the balance text is not one line:\n$call")
    }

    @Test
    fun only_the_usd_suffix_can_shrink() {
        val call = enclosingCall(header(), "CHAT_LIST_HEADER_USD_TAG")
        assertTrue(
            Regex("""weight\(\s*1f\s*,\s*fill\s*=\s*false\s*\)""").containsMatchIn(call),
            "the USD suffix is not weight(1f, fill = false):\n$call"
        )
        assertTrue("TextOverflow.Ellipsis" in call, "the USD suffix does not end in an ellipsis:\n$call")
        assertTrue(Regex("""maxLines\s*=\s*1\b""").containsMatchIn(call), "the USD suffix is not one line:\n$call")
    }

    @Test
    fun the_wordmark_is_line_one_and_the_balance_line_two() {
        val src = header()
        val column = src.indexOf("Column(")
        val wordmark = src.indexOf("\"ZChat\"")
        val balance = src.indexOf("CHAT_LIST_HEADER_BALANCE_TAG", src.indexOf("fun ChatListHeaderTitle"))
        assertTrue(column >= 0, "the header is not a Column")
        assertTrue(column in 0 until wordmark && wordmark < balance, "the wordmark does not come before the balance")
        val size = Regex("""fontSize\s*=\s*12\.sp""").containsMatchIn(enclosingCall(src, "CHAT_LIST_HEADER_BALANCE_TAG"))
        assertTrue(size, "the balance is not at 12sp")
    }

    @Test
    fun chat_list_view_uses_the_header_and_keeps_the_wallet_tap() {
        val list = ChatResourceText.source(listPath)
        val src = header()
        assertTrue("ChatListHeaderTitle(" in list, "ChatListView does not show ChatListHeaderTitle")
        assertTrue("balance.toZecString(formatLocale)" !in list, "ChatListView still formats the header balance inline")
        assertTrue("navigationRouter.replace(WalletTab)" in list, "the B12 tap no longer opens the Wallet tab")
        assertTrue("onClickLabel = \"Open wallet\"" in src, "the B12 tap lost its label")
    }

    @Test
    fun the_balance_figure_is_the_whole_amount() {
        assertEquals("0.01320586 ZEC", headerBalanceText(1_320_586L, Locale.US))
        assertEquals("0,01320586 ZEC", headerBalanceText(1_320_586L, Locale.forLanguageTag("es-ES")))
        assertEquals("0.00000001 ZEC", headerBalanceText(1L, Locale.US))
        assertEquals("0.00018 ZEC", headerBalanceText(18_000L, Locale.US))
        assertEquals("10 ZEC", headerBalanceText(1_000_000_000L, Locale.US))
        assertEquals("0 ZEC", headerBalanceText(0L, Locale.US))
    }

    /** chatListHeaderBalanceText(Zatoshi, Locale), looked up by reflection so this class compiles before it exists. */
    private fun headerBalanceText(
        zatoshi: Long,
        locale: Locale
    ): String {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.view.ChatListHeaderTitleKt") }
                .getOrElse { fail("ChatListHeaderTitleKt does not exist") }
        val method =
            cls.declaredMethods.firstOrNull { it.name == "chatListHeaderBalanceText" && it.parameterCount == 2 }
                ?: fail("chatListHeaderBalanceText(Zatoshi, Locale) does not exist")
        method.isAccessible = true
        return method.invoke(null, Zatoshi(zatoshi), locale) as String
    }
}
