package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InsufficientFundsVM(
    args: InsufficientFundsArgs,
    private val navigationRouter: NavigationRouter
) : ViewModel() {
    val state: StateFlow<InsufficientFundsState?> =
        MutableStateFlow<InsufficientFundsState?>(
            InsufficientFundsState(
                title = title(args),
                description = description(args) { stringRes(it) },
                onBack = { navigationRouter.back() },
                hiddenDescription = description(args) { HIDDEN_AMOUNT },
            )
        ).asStateFlow()

    private fun title(args: InsufficientFundsArgs): StringResource =
        if (args.shortfall() is ShortfallReason.StillConfirming) {
            stringRes(R.string.insufficient_funds_confirming_title)
        } else {
            when (args.context) {
                InsufficientFundsContext.PAYMENT -> stringRes(R.string.insufficient_funds_payment_title)
                InsufficientFundsContext.MESSAGE -> stringRes(R.string.insufficient_funds_title)
                InsufficientFundsContext.SWAP -> stringRes(R.string.insufficient_funds_swap_title)
            }
        }

    /**
     * The sheet says what the wallet state shows, with the numbers, and never that a transaction is
     * pending unless the shortfall is [ShortfallReason.StillConfirming]. Each balance figure is written
     * by [balance], so hidden balances stay hidden; what the send needs is not a balance and stays.
     */
    private fun description(
        args: InsufficientFundsArgs,
        balance: (Zatoshi) -> StringResource,
    ): StringResource =
        when (val shortfall = args.shortfall()) {
            is ShortfallReason.StillConfirming ->
                stringRes(
                    R.string.insufficient_funds_confirming_description,
                    balance(shortfall.pending),
                    stringRes(shortfall.need)
                )
            is ShortfallReason.OrchardStranded ->
                stringRes(
                    R.string.insufficient_funds_orchard_description,
                    stringRes(shortfall.need),
                    balance(shortfall.have),
                    balance(shortfall.orchard)
                )
            is ShortfallReason.NotEnough -> {
                val body =
                    shortfall.need?.let {
                        stringRes(
                            if (args.context == InsufficientFundsContext.MESSAGE) {
                                R.string.insufficient_funds_message_not_enough_description
                            } else {
                                R.string.insufficient_funds_not_enough_description
                            },
                            stringRes(it),
                            balance(shortfall.have)
                        )
                    } ?: contextDescription(args.context)
                if (shortfall.pending > Zatoshi(0)) {
                    body + stringRes("\n\n") +
                        stringRes(R.string.insufficient_funds_confirming_note, balance(shortfall.pending))
                } else {
                    body
                }
            }
            null -> contextDescription(args.context)
        }

    private fun contextDescription(context: InsufficientFundsContext): StringResource =
        when (context) {
            InsufficientFundsContext.PAYMENT -> stringRes(R.string.insufficient_funds_payment_description)
            InsufficientFundsContext.MESSAGE -> stringRes(R.string.insufficient_funds_description)
            InsufficientFundsContext.SWAP -> stringRes(R.string.insufficient_funds_swap_description)
        }

    private companion object {
        val HIDDEN_AMOUNT = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    }
}
