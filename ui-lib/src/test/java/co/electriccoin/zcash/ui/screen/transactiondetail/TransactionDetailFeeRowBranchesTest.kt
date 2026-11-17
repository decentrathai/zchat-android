package co.electriccoin.zcash.ui.screen.transactiondetail

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.usecase.DetailedTransactionData
import co.electriccoin.zcash.ui.common.usecase.ReloadHandle
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * ZC-1683 (QA2 P3 polish; Fable wave-1 re-review): the Fee row of the transaction detail
 * (`TransactionDetailVM.createFeeStringRes`, QA2 Q2-09) has three branches and only a source-text test
 * (PendingSendAndFeeRowsAreHonestTest). This test calls the real function.
 *
 * - a transaction that has its fee shows the exact fee (`transaction_detail_fee`, the figure, the ticker);
 * - a pending transaction without a fee says "Fee not known yet" (`transaction_detail_fee_unknown`): it may learn it;
 * - a mined transaction without a fee, and an expired one (Failed), say "Fee not known" (`transaction_detail_fee_not_known`),
 *   never "yet" and never a typed figure;
 * - a receipt has no fee of ours (its own `fee` is null): a mined receipt says "Fee not known".
 *
 * A verification test: green at the base commit (register severity low, no known failure). The view model is allocated
 * without its constructor and the one field the function reads is set, as SendAgainTwoTapsOpenOneFormTest does.
 * Synthetic transactions only.
 */
class TransactionDetailFeeRowBranchesTest {
    private val id = TransactionId.new("%064x".format(0x1683))

    private fun overview(
        mined: Long?,
        state: TransactionState,
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
            null,
            state,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

    private fun send(
        kind: String,
        fee: Zatoshi?,
    ): Transaction {
        val at = if (kind == "success") MINED_AT else null
        return when (kind) {
            "pending" ->
                SendTransaction.Pending(id, Zatoshi(AMOUNT), null, 0, fee, emptyList(), overview(null, TransactionState.Pending), null)
            "success" ->
                SendTransaction.Success(id, Zatoshi(AMOUNT), at, 0, fee, emptyList(), overview(MINED, TransactionState.Confirmed), null)
            else ->
                SendTransaction.Failed(id, Zatoshi(AMOUNT), null, 0, fee, emptyList(), overview(null, TransactionState.Expired), null)
        }
    }

    private fun feeRow(transaction: Transaction): StringResource {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val vm = allocate.invoke(unsafe, TransactionDetailVM::class.java) as TransactionDetailVM
        val mapperField = TransactionDetailVM::class.java.getDeclaredField("mapper")
        mapperField.isAccessible = true
        mapperField.set(vm, CommonTransactionDetailMapper())
        val data =
            DetailedTransactionData(
                transaction = transaction,
                memos = emptyList(),
                contact = null,
                metadata = TransactionMetadata(isBookmarked = false, isRead = true, note = null, swapMetadata = null),
                swap = null,
                reloadHandle =
                    object : ReloadHandle {
                        override fun requestReload() = Unit
                    },
            )
        val method =
            TransactionDetailVM::class.java
                .getDeclaredMethod("createFeeStringRes", DetailedTransactionData::class.java)
                .apply { isAccessible = true }
        return method.invoke(vm, data) as StringResource
    }

    private val unknownYet = stringRes(R.string.transaction_detail_fee_unknown)
    private val notKnown = stringRes(R.string.transaction_detail_fee_not_known)

    @Test
    fun `a send that has its fee shows the exact fee with the ticker`() {
        val expected = stringRes(R.string.transaction_detail_fee, stringRes(Zatoshi(FEE), TickerLocation.HIDDEN), CURRENCY_TICKER)
        assertEquals(expected, feeRow(send("success", Zatoshi(FEE))), "a mined send")
        assertEquals(expected, feeRow(send("pending", Zatoshi(FEE))), "a pending send")
    }

    @Test
    fun `a pending send without a fee says the fee is not known yet`() {
        assertEquals(unknownYet, feeRow(send("pending", null)))
    }

    @Test
    fun `a mined send without a fee says the fee is not known, never yet`() {
        assertEquals(notKnown, feeRow(send("success", null)))
        assertNotEquals(unknownYet, feeRow(send("success", null)))
    }

    @Test
    fun `an expired send without a fee says the fee is not known, never yet`() {
        assertEquals(notKnown, feeRow(send("failed", null)))
        assertNotEquals(unknownYet, feeRow(send("failed", null)))
    }

    @Test
    fun `a receipt has no fee of ours and says the fee is not known`() {
        val receipt =
            ReceiveTransaction.Success(id, Zatoshi(AMOUNT), MINED_AT, 0, emptyList(), overview(MINED, TransactionState.Confirmed), null)
        assertEquals(notKnown, feeRow(receipt))
    }

    @Test
    fun `a shielding with its fee shows it and one without says not known`() {
        val mined = overview(MINED, TransactionState.Confirmed)
        val withFee = ShieldTransaction.Success(id, Zatoshi(AMOUNT), MINED_AT, 0, Zatoshi(FEE), emptyList(), mined, null)
        val withoutFee = ShieldTransaction.Success(id, Zatoshi(AMOUNT), MINED_AT, 0, null, emptyList(), mined, null)
        assertEquals(
            stringRes(R.string.transaction_detail_fee, stringRes(Zatoshi(FEE), TickerLocation.HIDDEN), CURRENCY_TICKER),
            feeRow(withFee),
        )
        assertEquals(notKnown, feeRow(withoutFee))
    }

    private companion object {
        const val AMOUNT = 10_000L
        const val FEE = 10_000L
        const val MINED = 3_498_700L
        const val EXPIRY = 3_498_756L
        val MINED_AT: Instant = Instant.ofEpochSecond(1_790_000_000)
    }
}
