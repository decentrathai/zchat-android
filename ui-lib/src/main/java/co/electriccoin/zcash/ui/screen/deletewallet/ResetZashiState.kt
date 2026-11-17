package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState

@Immutable
data class ResetZashiState(
    val onBack: () -> Unit,
    val checkboxState: CheckboxState,
    val buttonState: ButtonState,
    /** ZC-090-R: what stays after a Reset without keep (delete_wallet_stays_unchecked), only with the box unticked. */
    val staysText: Int? = null,
)
