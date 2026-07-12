package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState

@Immutable
data class InsufficientFundsState(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    override val onBack: () -> Unit,
) : ModalBottomSheetState
