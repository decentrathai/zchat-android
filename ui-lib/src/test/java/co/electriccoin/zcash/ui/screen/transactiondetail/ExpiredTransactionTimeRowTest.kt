package co.electriccoin.zcash.ui.screen.transactiondetail

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T3b-2 item 4 (fix-all 2.13.0, train T3b; train T3 phone finding 8, HONOR F3 check 4): the detail of a TEX send's
 * second step that expired without ever being sent showed the header "Sending failed" and, under it, the row
 * "Completed Sep 28, 2026 05:55". The two contradict each other: nothing completed.
 *
 * Cause at f4b0853c7: TransactionDetailVM decides the row by `timestamp == null` alone ("Status: Pending" when there is
 * no time, "Completed <time>" otherwise), and the SDK gives the expired, never-mined transaction a time. So an expired
 * transaction (SendTransaction.Failed, and the same for a shielding or a receive that expired) reads "Completed", and
 * a transaction that is not mined yet but has a time reads "Completed" under the header "Sending".
 *
 * What it must be: the row follows the transaction's state, as the header does. Mined: "Completed <time>". Not mined
 * yet: "Status: Pending". Expired: "Status: Expired — never confirmed", in English and Spanish. Reached by name
 * (TransactionTimeRowKt.transactionTimeRowOf) so this file compiles before the fix.
 */
class ExpiredTransactionTimeRowTest {
    private val time: Instant = Instant.parse("2026-09-28T03:55:00Z")

    @Test
    fun an_expired_send_with_a_time_says_expired_never_completed() {
        val row = timeRowOf(sendFailed(time))
        assertEquals(true, isStatus(row), "an expired send's row must be a status, not 'Completed'")
        assertEquals(stringRes(stringId("transaction_detail_status_expired")), message(row))
    }

    @Test
    fun an_expired_shielding_says_expired_too() {
        val row = timeRowOf(shieldFailed(time))
        assertEquals(true, isStatus(row))
        assertEquals(stringRes(stringId("transaction_detail_status_expired")), message(row))
    }

    @Test
    fun a_send_not_mined_yet_says_pending_even_with_a_time() {
        val row = timeRowOf(sendPending(time))
        assertEquals(true, isStatus(row), "an unmined send reads 'Completed'")
        assertEquals(stringRes(stringId("transaction_detail_pending")), message(row))
    }

    /** The old behaviour, kept (it needs the new function, so it fails before the fix): a mined send is Completed. */
    @Test
    fun a_mined_send_still_says_completed_with_its_time() {
        val row = timeRowOf(sendSuccess(time))
        assertEquals(false, isStatus(row))
        assertEquals(stringResByDateTime(time.atZone(ZoneId.systemDefault()), true), message(row))
    }

    @Test
    fun the_expired_text_is_in_english_and_spanish_and_never_says_completed() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val english = en["transaction_detail_status_expired"] ?: fail("values has no transaction_detail_status_expired")
        val spanish = es["transaction_detail_status_expired"] ?: fail("values-es has no transaction_detail_status_expired")
        assertNotEquals(english, spanish)
        assertTrue("Expired" in english, english)
        assertFalse("ompleted" in english, english)
        assertTrue("Caducada" in spanish || "Caducó" in spanish, spanish)
        assertFalse("ompletad" in spanish, spanish)
    }

    @Test
    fun the_detail_screen_reads_its_time_row_from_the_transaction_state() {
        val vm = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt")
        assertTrue("transactionTimeRowOf(" in vm, "TransactionDetailVM does not build its time row from the state")
        assertFalse("timestamp == null" in vm, "TransactionDetailVM still decides the row by the time alone")
    }

    // --------------------------------------------------------------- harness

    private fun timeRowOf(transaction: Transaction): Any {
        val type =
            try {
                Class.forName("co.electriccoin.zcash.ui.screen.transactiondetail.TransactionTimeRowKt")
            } catch (e: ClassNotFoundException) {
                throw AssertionError("there is no transactionTimeRowOf(transaction, mapper)", e)
            }
        val method =
            type.methods.firstOrNull { it.name == "transactionTimeRowOf" && it.parameterCount == 2 }
                ?: throw AssertionError("there is no transactionTimeRowOf(transaction, mapper)")
        return assertNotNull(method.invoke(null, transaction, CommonTransactionDetailMapper()))
    }

    private fun isStatus(row: Any): Boolean = row.javaClass.getMethod("isStatus").invoke(row) as Boolean

    private fun message(row: Any): StringResource = row.javaClass.getMethod("getMessage").invoke(row) as StringResource

    private fun stringId(name: String): Int = ChatResourceText.stringId(name) ?: fail("there is no string '$name'")

    private val id = TransactionId.new("%064x".format(0x3B2))

    private fun sendFailed(at: Instant?) =
        SendTransaction.Failed(
            id = id,
            amount = Zatoshi(AMOUNT),
            timestamp = at,
            memoCount = 0,
            fee = Zatoshi(FEE),
            transactionOutputs = emptyList(),
            overview = overview(mined = null, state = TransactionState.Expired, at = at),
            recipient = null,
        )

    private fun sendPending(at: Instant?) =
        SendTransaction.Pending(
            id = id,
            amount = Zatoshi(AMOUNT),
            timestamp = at,
            memoCount = 0,
            fee = Zatoshi(FEE),
            transactionOutputs = emptyList(),
            overview = overview(mined = null, state = TransactionState.Pending, at = at),
            recipient = null,
        )

    private fun sendSuccess(at: Instant?) =
        SendTransaction.Success(
            id = id,
            amount = Zatoshi(AMOUNT),
            timestamp = at,
            memoCount = 0,
            fee = Zatoshi(FEE),
            transactionOutputs = emptyList(),
            overview = overview(mined = MINED, state = TransactionState.Confirmed, at = at),
            recipient = null,
        )

    private fun shieldFailed(at: Instant?) =
        ShieldTransaction.Failed(
            id = id,
            amount = Zatoshi(AMOUNT),
            timestamp = at,
            memoCount = 0,
            fee = Zatoshi(FEE),
            transactionOutputs = emptyList(),
            overview = overview(mined = null, state = TransactionState.Expired, at = at),
            recipient = null,
        )

    private fun overview(
        mined: Long?,
        state: TransactionState,
        at: Instant?,
    ): TransactionOverview =
        TransactionOverview(
            id,
            mined?.let { BlockHeight.new(it) },
            BlockHeight.new(EXPIRY),
            null,
            null,
            true,
            Zatoshi(AMOUNT + FEE),
            Zatoshi(AMOUNT + FEE),
            Zatoshi(0),
            Zatoshi(FEE),
            false,
            0,
            1,
            0,
            at?.epochSecond,
            state,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

    private companion object {
        const val AMOUNT = 10_000L
        const val FEE = 10_000L
        const val MINED = 3_498_700L
        const val EXPIRY = 3_498_756L
    }
}
