package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.StringResource

@Immutable
data class InsufficientFundsState(
    val title: StringResource,
    val description: StringResource,
    override val onBack: () -> Unit,
    /** [description] with every balance figure replaced by the placeholder, shown while balances are hidden. */
    val hiddenDescription: StringResource = description,
) : ModalBottomSheetState
