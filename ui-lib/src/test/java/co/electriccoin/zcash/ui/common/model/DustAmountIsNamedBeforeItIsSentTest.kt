package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 Q2-18 / owner item B-1 (ZC-1419, ZC-1427; W-137, FIX_PLAN_W137 "B-1"): a note of Zip317.MARGINAL_FEE_ZATOSHI
 * (5,000 zat) or less costs at least its own value to spend, so the wallet never counts it. Activity says
 * "Received 0.00001 — Completed" and the balance never moves; plain Send takes such an amount with no word (only
 * Send all warned); the receiver's detail says nothing.
 *
 * The design: one seam `DustAmount.isUnspendable(zat)` (true for 1..Zip317.MARGINAL_FEE_ZATOSHI); a warning in plain
 * Send (`send_amount_dust_warning`, never an AmountInvalidReason, never blocking) and the same as a MessageState item
 * on Review; a "too small to spend" note (`transaction_detail_received_too_small`, ReceiveShieldedState.tooSmallNote)
 * on a received detail when 0 < total received <= the limit. Every figure is formatted from the constant, never typed
 * into a text. Reached by name, so this file compiles before the fix.
 */
class DustAmountIsNamedBeforeItIsSentTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")
    private val root = ChatResourceText.repoRoot
    private val mainJava = File(root, "ui-lib/src/main/java/co/electriccoin/zcash/ui")

    private val dust: Pair<Any?, java.lang.reflect.Method> by lazy {
        val type =
            try {
                Class.forName("co.electriccoin.zcash.ui.common.model.DustAmount")
            } catch (e: ClassNotFoundException) {
                throw AssertionError("there is no co.electriccoin.zcash.ui.common.model.DustAmount", e)
            }
        val instance = type.getField("INSTANCE").get(null)
        val method =
            type.methods.firstOrNull {
                it.name == "isUnspendable" && it.parameterCount == 1 && it.parameterTypes[0] == Long::class.javaPrimitiveType
            } ?: throw AssertionError("DustAmount has no isUnspendable(zat: Long): Boolean")
        instance to method
    }

    private fun isUnspendable(zat: Long): Boolean = dust.second.invoke(dust.first, zat) as Boolean

    private fun sources(under: String): Map<String, String> =
        File(mainJava, under)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(mainJava).path to it.readText() }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    @Test
    fun the_limit_is_the_marginal_fee_and_nothing_else() {
        assertFalse(isUnspendable(0L), "nothing received is not dust")
        assertFalse(isUnspendable(-1L), "a negative amount is not dust")
        assertTrue(isUnspendable(1L))
        assertTrue(isUnspendable(1_000L), "the 0.00001 ZEC a Shielded message leaves is dust")
        assertTrue(isUnspendable(4_333L), "the 4,333 zat Send all of ZC-1419 is dust")
        assertTrue(isUnspendable(Zip317.MARGINAL_FEE_ZATOSHI), "a note of exactly the marginal fee is dust")
        assertFalse(isUnspendable(Zip317.MARGINAL_FEE_ZATOSHI + 1), "one zat above the marginal fee is spendable")
        assertFalse(isUnspendable(10_000L))
    }

    @Test
    fun the_seam_reads_the_constant_not_a_typed_figure() {
        val file =
            sources("").entries.firstOrNull { Regex("""\bobject DustAmount\b""").containsMatchIn(it.value) }
                ?: fail("no source declares object DustAmount")
        assertTrue("MARGINAL_FEE_ZATOSHI" in file.value, "${file.key} does not use Zip317.MARGINAL_FEE_ZATOSHI")
        assertFalse(Regex("""\b5_?000L?\b""").containsMatchIn(file.value), "${file.key} types the 5,000 limit")
    }

    @Test
    fun both_texts_exist_in_both_languages_and_take_the_figure_as_an_argument() {
        for (key in listOf("send_amount_dust_warning", "transaction_detail_received_too_small")) {
            val english = en[key] ?: fail("values has no $key")
            val spanish = es[key] ?: fail("values-es has no $key")
            assertTrue(placeholders(english).isNotEmpty(), "$key must take the figure as an argument: $english")
            assertEquals(placeholders(english), placeholders(spanish), "the placeholders of $key differ")
            for ((dir, text) in listOf("values" to english, "values-es" to spanish)) {
                for (typed in listOf("0.00005", "0,00005", "5000", "5,000", "5.000", "5 000")) {
                    assertFalse(typed in text, "$dir $key types the figure $typed: $text")
                }
            }
        }
        val note = en.getValue("transaction_detail_received_too_small").lowercase()
        assertTrue("too small to spend" in note, note)
        assertTrue("not counted in your balance" in note, note)
        val nota = es.getValue("transaction_detail_received_too_small").lowercase()
        assertTrue("demasiado pequeña para gastar" in nota || "demasiado pequeño para gastar" in nota, nota)
        assertTrue("saldo" in nota, nota)
    }

    @Test
    fun plain_send_warns_and_never_refuses() {
        val send = sources("screen/send")
        assertTrue(
            send.values.any { "R.string.send_amount_dust_warning" in it },
            "plain Send never shows send_amount_dust_warning"
        )
        assertTrue(send.values.any { "DustAmount.isUnspendable" in it }, "plain Send does not ask DustAmount")

        // Never blocking: the warning is not an amount the field refuses.
        val reasons =
            File(root, "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/util/AmountTextReader.kt").readText()
        val reasonBlock = reasons.substringAfter("sealed interface AmountInvalidReason").substringBefore("\n}\n")
        assertFalse(Regex("""Dust|TooSmall|Unspendable""").containsMatchIn(reasonBlock), "dust became an AmountInvalidReason")
        val amountState = File(mainJava, "screen/send/model/AmountState.kt").readText()
        assertFalse("DustAmount" in amountState, "AmountState decides validity by dust; the warning must not block")
    }

    @Test
    fun review_lists_the_warning_as_a_message_item() {
        val vm = File(mainJava, "screen/reviewtransaction/ReviewTransactionVM.kt").readText()
        assertTrue("DustAmount.isUnspendable" in vm, "Review does not ask DustAmount")
        assertTrue("R.string.send_amount_dust_warning" in vm, "Review never shows send_amount_dust_warning")
    }

    @Test
    fun a_received_dust_detail_says_too_small_to_spend() {
        val fields =
            Class
                .forName("co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveShieldedState")
                .declaredFields
                .map { it.name }
        assertTrue("tooSmallNote" in fields, "ReceiveShieldedState has no tooSmallNote: $fields")
        val vm = File(mainJava, "screen/transactiondetail/TransactionDetailVM.kt").readText()
        assertTrue("R.string.transaction_detail_received_too_small" in vm, "the received detail never says too small to spend")
        assertTrue("DustAmount.isUnspendable" in vm, "the received detail does not ask DustAmount")
    }
}
