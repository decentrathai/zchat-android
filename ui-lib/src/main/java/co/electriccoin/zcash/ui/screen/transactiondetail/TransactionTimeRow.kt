package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * The transaction detail's time row: "Completed <time>" ([isStatus] false), or "Status: <state>" ([isStatus] true).
 */
data class TransactionTimeRow(
    val isStatus: Boolean,
    val message: StringResource,
)

/**
 * T3b-2 (train T3 finding 8): the time row follows the transaction's state, as the header does. The SDK can give a
 * transaction that was never mined a time (an expired TEX second step read "Sending failed" over "Completed <time>"),
 * so the time alone does not say it completed. Mined: "Completed <time>". Not mined yet: "Status: Pending". Expired
 * without being mined: "Status: Expired — never confirmed".
 */
fun transactionTimeRowOf(
    transaction: Transaction,
    mapper: CommonTransactionDetailMapper,
): TransactionTimeRow =
    when (transaction) {
        is SendTransaction.Failed, is ReceiveTransaction.Failed, is ShieldTransaction.Failed ->
            TransactionTimeRow(isStatus = true, message = stringRes(R.string.transaction_detail_status_expired))
        is SendTransaction.Pending, is ReceiveTransaction.Pending, is ShieldTransaction.Pending ->
            TransactionTimeRow(isStatus = true, message = stringRes(R.string.transaction_detail_pending))
        else ->
            TransactionTimeRow(
                isStatus = transaction.timestamp == null,
                message = mapper.createTransactionDetailTimestamp(transaction.timestamp),
            )
    }
