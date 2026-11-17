package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailInfoState

@Immutable
data class TransactionDetailState(
    val onBack: () -> Unit,
    val bookmarkButton: IconButtonState?,
    val header: TransactionDetailHeaderState,
    val info: TransactionDetailInfoState?,
    val errorFooter: ErrorFooter?,
    val primaryButton: ButtonState?,
    val secondaryButton: ButtonState?
)

@Immutable
data class ErrorFooter(
    val title: StringResource,
    val subtitle: StringResource,
    // QA2 Q2-04 (ZC-1455): false for a send that is only waiting to be mined, drawn in the secondary colour; a real
    // problem ("Not sent", a TEX or swap error) keeps the error colour.
    val isError: Boolean = true,
)
