package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ES-W (fix-all 2.13.0, train T4c; Spanish fold spec §1.7, §3.5, §5) — the chat's "Send Welcome ZEC" card asks
 * "Send 0.005 ZEC? This sends 0.005 ZEC from your wallet to <name> as a welcome", then `sendPayment` submits at once
 * (`directSubmit = true`, no review screen) a payment whose outlay is the 500,000 zatoshi, the 1,000-zatoshi platform
 * fee and the ZIP 317 fee of 3 actions (15,000): at least 516,000 zatoshi leave the wallet, and the confirm names none
 * of the fees. It also lacks the 2.12.4 line the other two in-chat payment confirms show (`upgrade_payment_line` +
 * `upgrade_payment_not_lost`), although a welcome goes to a new contact, who may still run 2.12.4.
 *
 * Wanted:
 * - one object, `WelcomeZec`, names the amount (500,000 zatoshi), the memo ("Welcome to ZCHAT!", unchanged on the wire)
 *   and the least the send takes (`paymentOutlay` of that amount and memo, the sizer the Payment dialog uses);
 * - the send passes `WelcomeZec.AMOUNT_ZEC`, a Double that `sendPayment`'s own conversion turns back into exactly
 *   500,000 zatoshi, and `WelcomeZec.MEMO`; no `0.005` literal is left in AndroidChat (comments stripped);
 * - the confirm names what leaves the wallet in the phone's number format, and shows the 2.12.4 line. D-6 (fix-all
 *   2.13.0, owner W-10): the exact figure of the payment's quote ("exactly", with the platform and network fees), no
 *   longer the floor; `outlayFloor()` stays for the 516,000 pins below;
 * - the card, the confirm title and both buttons show the amount from `WelcomeZec.amount`, in the phone's format.
 *
 * `WelcomeZec` is read by reflection so this file compiles before the object exists. The views are read as source,
 * as the other copy tests do.
 */
class WelcomeZecQuotesWhatLeavesTheWalletTest {
    private val welcomeZecClass: Class<*>? by lazy {
        runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.WelcomeZec") }.getOrNull()
    }

    private fun welcomeZec(): Class<*> = welcomeZecClass ?: fail("WelcomeZec (screen.chat.model) does not exist")

    /** A member of the Kotlin object: a public static field (const val) or its getter on INSTANCE. */
    private fun member(name: String): Any? {
        val cls = welcomeZec()
        runCatching { return cls.getField(name).get(null) }
        val instance = cls.getField("INSTANCE").get(null)
        val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
        val method = cls.methods.firstOrNull { it.name == getter && it.parameterCount == 0 } ?: fail("WelcomeZec has no $name")
        return method.invoke(instance)
    }

    private fun outlayFloor(): Zatoshi {
        val cls = welcomeZec()
        val instance = cls.getField("INSTANCE").get(null)
        val method =
            cls.methods.firstOrNull { it.name == "outlayFloor" && it.parameterCount == 0 } ?: fail("WelcomeZec has no outlayFloor()")
        return method.invoke(instance) as Zatoshi
    }

    private val androidChat: String by lazy {
        stripComments(ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"))
    }

    private val chatDetail: String by lazy {
        stripComments(ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"))
    }

    /** The `if (showWelcomeZecConfirm) { … }` block of ChatDetailView. */
    private val confirmBlock: String by lazy { braceBlock(chatDetail, "if (showWelcomeZecConfirm)") }

    /** The body of `WelcomeZecCard`. */
    private val cardBlock: String by lazy { braceBlock(chatDetail, "private fun WelcomeZecCard(") }

    /** Control, green now: the sizer's floor for the welcome is 500,000 + 1,000 platform + 15,000 ZIP 317 (3 actions). */
    @Test
    fun the_welcome_outlay_floor_is_516000_zatoshi() {
        assertEquals(516_000L, paymentOutlay(Zatoshi(500_000), paymentMemoChunks("Welcome to ZCHAT!")).value)
    }

    @Test
    fun one_constant_names_the_amount_and_the_memo() {
        assertEquals(500_000L, member("AMOUNT_ZATOSHI"), "WelcomeZec.AMOUNT_ZATOSHI")
        assertEquals("Welcome to ZCHAT!", member("MEMO"), "WelcomeZec.MEMO (the memo on the wire is unchanged)")
        assertEquals(Zatoshi(500_000L), member("amount"), "WelcomeZec.amount")
        assertEquals(516_000L, outlayFloor().value, "WelcomeZec.outlayFloor()")
        assertEquals(
            paymentOutlay(Zatoshi(500_000L), paymentMemoChunks("Welcome to ZCHAT!")),
            outlayFloor(),
            "outlayFloor() is not the Payment dialog's sizer",
        )
    }

    /** Review item 2: the send keeps ChatViewModel's Double overload, so the Double must convert back exactly. */
    @Test
    fun the_double_the_send_takes_is_exactly_500000_zatoshi() {
        val amountZec = member("AMOUNT_ZEC") as? Double ?: fail("WelcomeZec.AMOUNT_ZEC is not a Double")
        // The conversion sendPayment makes (ChatViewModel.sendPayment: BigDecimal.valueOf(amountZec).convertZecToZatoshi()).
        assertEquals(500_000L, BigDecimal.valueOf(amountZec).convertZecToZatoshi().value)
    }

    @Test
    fun the_send_uses_the_constant() {
        assertTrue("0.005" !in androidChat, "AndroidChat.kt (comments stripped) still holds a 0.005 literal")
        val send = braceBlock(androidChat, "onSendWelcomeZec = {")
        val call =
            Regex("""sendPayment\(([^)]*)\)""").find(send)?.groupValues?.get(1)
                ?: fail("onSendWelcomeZec does not call sendPayment:\n$send")
        assertTrue("WelcomeZec.AMOUNT_ZEC" in call, "the welcome send does not pass WelcomeZec.AMOUNT_ZEC: sendPayment($call)")
        assertTrue("WelcomeZec.MEMO" in call, "the welcome send does not pass WelcomeZec.MEMO: sendPayment($call)")
    }

    /** Review item 1: as the Payment dialog and the pay-request confirm do (Bridge 3). */
    @Test
    fun the_confirm_says_a_2124_contact_sees_it_after_updating() {
        assertTrue(
            Regex("""R\.string\.upgrade_payment_line\s*,\s*conversation\.displayName""").containsMatchIn(confirmBlock),
            "the Welcome confirm does not show upgrade_payment_line with the contact's name:\n$confirmBlock",
        )
        assertTrue("R.string.upgrade_payment_not_lost" in confirmBlock, "the Welcome confirm does not show upgrade_payment_not_lost")
    }

    @Test
    fun the_confirm_names_the_least_that_leaves_the_wallet() {
        assertTrue(
            Regex("""stringResource\(\s*R\.string\.welcome_zec_confirm_body\s*,""").containsMatchIn(confirmBlock),
            "the Welcome confirm does not read welcome_zec_confirm_body:\n$confirmBlock",
        )
        assertTrue(
            Regex("""PaidActionCopy\.zec\(\s*\w+\.total\s*,""").containsMatchIn(confirmBlock) && "onQuotePayment(" in confirmBlock,
            "the Welcome confirm does not format its quote's exact total with PaidActionCopy.zec:\n$confirmBlock",
        )
        assertTrue("rememberDesiredFormatLocale()" in confirmBlock, "the Welcome confirm does not use the phone's number format")
        assertTrue("R.string.welcome_zec_confirm_title" in confirmBlock, "the Welcome confirm title is not welcome_zec_confirm_title")
        assertTrue("R.string.paid_action_cancel" in confirmBlock, "the Welcome confirm's Cancel is not paid_action_cancel")
        assertTrue("0.005" !in chatDetail, "ChatDetailView (comments stripped) still holds a 0.005 literal")
        for (literal in listOf("Text(\"Cancel\")", "\"Send 0.005 ZEC?\"", "as a welcome", "Locale.US")) {
            assertTrue(literal !in confirmBlock, "the Welcome confirm still has $literal")
        }
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val enBody = assertNotNull(en["welcome_zec_confirm_body"], "no English welcome_zec_confirm_body")
        val esBody = assertNotNull(es["welcome_zec_confirm_body"], "no Spanish welcome_zec_confirm_body")
        assertTrue("exactly %3\$s ZEC leaves your wallet" in enBody, "the English body does not name the exact figure: $enBody")
        assertTrue("exactamente %3\$s ZEC" in esBody, "the Spanish body does not name the exact figure: $esBody")
    }

    @Test
    fun the_card_and_the_button_show_the_same_amount_in_the_phones_format() {
        for ((name, block) in listOf("WelcomeZecCard" to cardBlock, "the Welcome confirm" to confirmBlock)) {
            assertTrue("R.string.welcome_zec_send_button" in block, "$name does not read welcome_zec_send_button:\n$block")
            assertTrue(
                Regex("""PaidActionCopy\.zec\(\s*WelcomeZec\.amount\s*,""").containsMatchIn(block),
                "$name does not format WelcomeZec.amount with PaidActionCopy.zec:\n$block",
            )
            assertTrue("rememberDesiredFormatLocale()" in block, "$name does not use the phone's number format")
            assertTrue("\"Send 0.005 ZEC\"" !in block, "$name still shows the literal \"Send 0.005 ZEC\"")
        }
        assertTrue("R.string.welcome_zec_card_title" in cardBlock, "WelcomeZecCard's title is not welcome_zec_card_title")
        assertTrue("\"Send Welcome ZEC\"" !in cardBlock, "WelcomeZecCard still shows the literal \"Send Welcome ZEC\"")
        // HonestCostAndWaitCopyTest pins the hint; it stays.
        assertTrue("R.string.chat_welcome_zec_hint" in cardBlock, "WelcomeZecCard lost chat_welcome_zec_hint")
    }

    private companion object {
        /**
         * Kotlin source without line and (nested) block comments. String and char literals are kept whole, so a MIME
         * pattern such as image-slash-star or a URL inside a string never opens a comment.
         */
        fun stripComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            val n = source.length
            while (i < n) {
                val c = source[i]
                when {
                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                        out.append(source, i, end)
                        i = end
                    }
                    c == '"' || c == '\'' -> {
                        var j = i + 1
                        while (j < n && source[j] != c && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                        val end = minOf(j + 1, n)
                        out.append(source, i, end)
                        i = end
                    }
                    source.startsWith("//", i) -> {
                        while (i < n && source[i] != '\n') i++
                    }
                    source.startsWith("/*", i) -> {
                        var depth = 0
                        while (i < n) {
                            if (source.startsWith("/*", i)) {
                                depth++
                                i += 2
                            } else if (source.startsWith("*/", i)) {
                                depth--
                                i += 2
                                if (depth == 0) break
                            } else {
                                i++
                            }
                        }
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** From [anchor] to the brace that closes the first `{` after it. */
        fun braceBlock(
            source: String,
            anchor: String,
        ): String {
            val start = source.indexOf(anchor)
            if (start < 0) fail("\"$anchor\" not found")
            val open = source.indexOf('{', start)
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return source.substring(start, i + 1)
                }
            }
            fail("unbalanced braces after \"$anchor\"")
        }
    }
}
