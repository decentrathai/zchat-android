package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
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
                titleRes =
                    when (args.context) {
                        InsufficientFundsContext.PAYMENT -> R.string.insufficient_funds_payment_title
                        InsufficientFundsContext.MESSAGE -> R.string.insufficient_funds_title
                        InsufficientFundsContext.SWAP -> R.string.insufficient_funds_swap_title
                    },
                descriptionRes =
                    when (args.context) {
                        InsufficientFundsContext.PAYMENT -> R.string.insufficient_funds_payment_description
                        InsufficientFundsContext.MESSAGE -> R.string.insufficient_funds_description
                        InsufficientFundsContext.SWAP -> R.string.insufficient_funds_swap_description
                    },
                onBack = { navigationRouter.back() }
            )
        ).asStateFlow()
}
