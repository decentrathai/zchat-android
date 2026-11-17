package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.common.tex.PlainSendVerdict
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 (W-136/W-137, batch B1, unit U1-wallet), the transaction detail.
 *
 * - Q2-04 (ZC-1455): a normal pending send was drawn as an error (red icon, red "Not confirmed yet", the pay-twice
 *   warning first). The waiting footer must say it is usually mined within minutes, then the deadline, and must not
 *   be drawn in the same error style as "Not sent". The key and its two arguments (block, minutes) stay, so the
 *   tests that pin them (PlainSendStatusStringsTest, WaitingTextsNameTheBlockTheirMinutesCountToTest, ...) hold.
 * - Q2-09 (ZC-1600, ZC-731): the Fee row said "< 0.001 ZEC" while Review showed the exact 0.0001. The row shows the
 *   transaction's exact fee; when the transaction has no fee (null) it says the fee is not known yet, never a typed
 *   figure; `transaction_detail_fee_minimal` is retired (owner decision W-137, FIX_PLAN_W137 "Q2-09").
 */
class PendingSendAndFeeRowsAreHonestTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private val vmPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt"
    private val viewPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailView.kt"

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    // ---- Q2-04 ----

    @Test
    fun q2_04_the_waiting_body_says_usually_mined_within_minutes_first() {
        val english = en["transaction_detail_waiting_body"] ?: fail("no English transaction_detail_waiting_body")
        val spanish = es["transaction_detail_waiting_body"] ?: fail("no Spanish transaction_detail_waiting_body")
        assertTrue(english.startsWith("Usually mined within minutes."), english)
        assertTrue("could pay twice" in english, "the pay-twice warning stays, after the deadline: $english")
        assertTrue(spanish.startsWith("Suele minarse en minutos."), spanish)
        assertTrue("dos veces" in spanish, spanish)
        assertEquals(setOf("%1\$s", "%2\$d"), placeholders(english))
        assertEquals(placeholders(english), placeholders(spanish))
    }

    @Test
    fun q2_04_a_waiting_send_is_not_drawn_like_a_send_that_was_never_sent() {
        val tx = "%064x".format(0x404)
        val waiting =
            plainSendFooter(
                tx,
                TexCheck(
                    statuses = emptyList(),
                    seenTxIds = setOf(tx),
                    plainSends = mapOf(tx to PlainSendVerdict.Waiting(expiryHeight = 5_000L, tip = 4_980L)),
                ),
                false,
            ) ?: fail("no footer for a waiting send")
        val notSent =
            plainSendFooter(
                tx,
                TexCheck(statuses = emptyList(), seenTxIds = setOf(tx), plainSends = mapOf(tx to PlainSendVerdict.NotSent)),
                false,
            ) ?: fail("no footer for a send proven never sent")

        // Everything the footer carries besides its two texts: the style the view draws it in.
        val styleFields =
            ErrorFooter::class.java
                .declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .filter { it.name != "title" && it.name != "subtitle" }
                .onEach { it.isAccessible = true }
        assertTrue(
            styleFields.isNotEmpty(),
            "the footer carries only its texts, so a waiting send is drawn in the same red error style as 'Not sent'"
        )
        assertTrue(
            styleFields.any { it.get(waiting) != it.get(notSent) },
            "the waiting footer and the not-sent footer carry the same style: ${styleFields.map { it.name }}"
        )

        val view = ChatResourceText.source(viewPath)
        val body = view.substringAfter("fun TransactionErrorFooter(").substringBefore("\n}\n")
        assertTrue(
            "textSecondary" in body || "textTertiary" in body,
            "TransactionErrorFooter draws every footer in textError; a waiting send needs the secondary colour"
        )
    }

    // ---- Q2-09 ----

    @Test
    fun q2_09_the_fee_row_never_shows_a_typed_less_than_figure() {
        for ((dir, strings) in listOf("values" to en, "values-es" to es)) {
            assertFalse(
                "transaction_detail_fee_minimal" in strings,
                "$dir still has transaction_detail_fee_minimal (\"< 0.001\"); W-137 retires it"
            )
        }
        val vm = ChatResourceText.source(vmPath)
        assertFalse("transaction_detail_fee_minimal" in vm, "TransactionDetailVM still shows \"< 0.001 ZEC\"")
        assertFalse("MIN_FEE_THRESHOLD" in vm, "TransactionDetailVM still rounds a small fee to \"< 0.001\"")
        assertTrue("R.string.transaction_detail_fee," in vm, "the exact fee row (transaction_detail_fee) is gone")
    }

    @Test
    fun q2_09_a_transaction_without_a_fee_says_the_fee_is_not_known_yet() {
        val key =
            en.entries
                .firstOrNull { it.key.startsWith("transaction_detail_") && "fee not known yet" in it.value.lowercase() }
                ?.key ?: fail("no transaction_detail_ string says \"fee not known yet\"")
        val english = en.getValue(key)
        val spanish = assertNotNull(es[key], "no Spanish $key")
        assertFalse(Regex("""\d""").containsMatchIn(english), "the unknown-fee text must carry no figure: $english")
        assertFalse(Regex("""\d""").containsMatchIn(spanish), "the unknown-fee text must carry no figure: $spanish")
        assertTrue("comisión" in spanish.lowercase(), spanish)
        assertTrue("aún" in spanish.lowercase() || "todavía" in spanish.lowercase(), spanish)
        assertTrue("R.string.$key" in ChatResourceText.source(vmPath), "TransactionDetailVM never shows R.string.$key")
    }
}
