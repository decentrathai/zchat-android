package co.electriccoin.zcash.ui.common.usecase

import PaymentRequest
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class PrefillSendUseCase {
    private val bus = Channel<PrefillSendData>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    operator fun invoke() = bus.receiveAsFlow()

    /**
     * Drops any prefill still waiting for a Send screen. The bus is a rendezvous channel, so a request
     * made while Send is not on screen (a scanned request goes straight to Review) waits until the next
     * Send screen collects it. Once that payment is submitted, the waiting prefill is stale and must not
     * refill the form with a request that was already paid.
     */
    fun clear() {
        while (bus.tryReceive().isSuccess) {
            // Drain the channel
        }
    }

    fun requestFromTransactionDetail(value: DetailedTransactionData) =
        scope.launch {
            bus.send(
                PrefillSendData.All(
                    amount = value.transaction.amount,
                    address = value.recipient?.address,
                    fee = value.transaction.fee,
                    memos = value.memos
                )
            )
        }

    fun requestFromZip321(value: PaymentRequest) =
        scope.launch {
            val request = value.payments.firstOrNull()
            bus.send(
                PrefillSendData.All(
                    amount = request?.nonNegativeAmount?.value?.convertZecToZatoshi() ?: Zatoshi(0),
                    address = request?.recipientAddress?.value,
                    fee = null,
                    memos =
                        value.payments
                            .firstOrNull()
                            ?.memo
                            ?.data
                            ?.decodeToString()
                            ?.let { listOf(it) }
                )
            )
        }

    fun request(value: PrefillSendData) = scope.launch { bus.send(value) }
}

sealed interface PrefillSendData {
    data class All(
        val amount: Zatoshi,
        val address: String?,
        val fee: Zatoshi?,
        val memos: List<String>?,
    ) : PrefillSendData {
        /**
         * The amount the Send form is filled with. Only a resend from transaction history sets [fee], and
         * there [amount] is the gross total the wallet spent, so the fee comes off. Every other source
         * (scanned or pasted payment requests) passes the net amount the payee asked for with no fee.
         * A gross total is never below its fee, so a [fee] above [amount] means [amount] is already net
         * and it is used unchanged.
         */
        val sendFormAmount: Zatoshi
            get() =
                when {
                    fee == null -> amount
                    fee > amount -> amount
                    else -> amount - fee
                }
    }

    data class FromAddressScan(
        val address: String
    ) : PrefillSendData
}
