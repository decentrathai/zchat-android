package co.electriccoin.zcash.ui.common.mapper

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.usecase.ActivityData
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 Q2-12 (ZC-1615, W-137 batch B1): a pending Activity row has no second line (the SDK gives an unmined
 * transaction no time, and ActivityMapper's subtitle is null without one), so the list jumps when the row turns
 * Completed and gains its date. A pending row says "Just now" ("Ahora mismo") until it has a time.
 */
class PendingActivityRowSaysJustNowTest {
    private val justNow: Int by lazy {
        val key =
            ChatResourceText
                .strings("values")
                .entries
                .firstOrNull { it.key.startsWith("transaction_history") && it.value.trim() == "Just now" }
                ?.key ?: fail("no transaction_history string reads \"Just now\"")
        val spanish = ChatResourceText.strings("values-es")[key] ?: fail("no Spanish $key")
        assertEquals("Ahora mismo", spanish.trim())
        ChatResourceText.stringId(key) ?: fail("no R.string.$key")
    }

    private fun subtitleOf(transaction: Transaction) =
        ActivityMapper()
            .createTransactionState(
                data =
                    ActivityData.ByTransaction(
                        transaction,
                        TransactionMetadata(isBookmarked = false, isRead = true, note = null, swapMetadata = null)
                    ),
                restoreTimestamp = Instant.EPOCH,
                onTransactionClick = {},
                onSwapClick = {},
                onDisplayed = {},
            ).subtitle

    @Test
    fun a_pending_send_without_a_time_says_just_now() {
        assertEquals(stringRes(justNow), subtitleOf(sendPending()), "a pending send row has no second line")
    }

    @Test
    fun a_pending_receive_without_a_time_says_just_now() {
        assertEquals(stringRes(justNow), subtitleOf(receivePending()), "a pending receive row has no second line")
    }

    @Test
    fun a_mined_send_keeps_its_date_line() {
        val at = Instant.now()
        val subtitle = subtitleOf(sendSuccess(at))
        assertTrue(subtitle != null && subtitle != stringRes(justNow), "a mined send must keep its date line: $subtitle")
    }

    private val id = TransactionId.new("%064x".format(0x612))

    private fun sendPending() =
        SendTransaction.Pending(
            id = id,
            amount = Zatoshi(AMOUNT),
            timestamp = null,
            memoCount = 0,
            fee = Zatoshi(FEE),
            transactionOutputs = emptyList(),
            overview = overview(mined = null, state = TransactionState.Pending, at = null),
            recipient = null,
        )

    private fun receivePending() =
        ReceiveTransaction.Pending(
            id = id,
            amount = Zatoshi(AMOUNT),
            timestamp = null,
            memoCount = 0,
            transactionOutputs = emptyList(),
            overview = overview(mined = null, state = TransactionState.Pending, at = null),
            recipient = null,
        )

    private fun sendSuccess(at: Instant) =
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
