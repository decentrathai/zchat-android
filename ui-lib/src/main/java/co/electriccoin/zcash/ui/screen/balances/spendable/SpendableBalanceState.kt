package co.electriccoin.zcash.ui.screen.balances.spendable

import androidx.compose.runtime.Immutable
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.StringResource

@Immutable
data class SpendableBalanceState(
    val title: StringResource,
    val message: StringResource,
    val rows: List<SpendableBalanceRowState>,
    val shieldButton: SpendableBalanceShieldButtonState?,
    val positive: ButtonState,
    override val onBack: () -> Unit,
    /** [message] with every balance figure replaced by the placeholder, shown while balances are hidden. */
    val hiddenMessage: StringResource = message,
) : ModalBottomSheetState

@Immutable
data class SpendableBalanceRowState(
    val title: StringResource,
    val icon: ImageResource,
    val value: StringResource
)

@Immutable
data class SpendableBalanceShieldButtonState(
    val amount: Zatoshi,
    val onShieldClick: () -> Unit,
)
