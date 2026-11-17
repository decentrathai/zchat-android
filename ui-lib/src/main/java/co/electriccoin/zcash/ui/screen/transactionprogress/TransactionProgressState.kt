package co.electriccoin.zcash.ui.screen.transactionprogress

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

@Immutable
sealed interface TransactionProgressState {
    val onBack: () -> Unit
}

@Immutable
data class SendingTransactionState(
    val title: StringResource,
    val text: StringResource,
    override val onBack: () -> Unit
) : TransactionProgressState

@Immutable
data class SuccessfulTransactionState(
    val title: StringResource,
    val text: StringResource,
    val middleButton: ButtonState?,
    val primaryButton: ButtonState,
    val secondaryButton: ButtonState?,
    override val onBack: () -> Unit
) : TransactionProgressState

@Immutable
data class FailureTransactionState(
    val title: StringResource,
    val text: StringResource,
    val onViewTransactionClick: (() -> Unit)?,
    val onCloseClick: () -> Unit,
    val onReportClick: () -> Unit,
    override val onBack: () -> Unit
) : TransactionProgressState

/**
 * The submit ended in SubmitResult.SentUnconfirmed: the settlement call threw after the SDK may have broadcast,
 * so the coins may be on their way or not. A warning, painted like the connection-issue screen: never the
 * success screen (nothing confirmed it) and never "Send Failed — try again" (that pays twice). Every exit goes
 * to the transaction history, where the truth appears after the next sync.
 *
 * T3-first: also a multi-transaction send (TEX) whose accepted parts are out and whose other parts failed only on
 * the transport, with its own "Sent — Waiting for the Network" title and text (SubmitResult.Partial with
 * unacceptedLegsResent).
 */
@Immutable
data class SentUnconfirmedTransactionState(
    val title: StringResource,
    val text: StringResource,
    /** Opens the transaction when its id is known, otherwise the transaction history. */
    val onViewTransactionClick: () -> Unit,
    val onCloseClick: () -> Unit,
    override val onBack: () -> Unit
) : TransactionProgressState

@Immutable
data class GrpcFailureTransactionState(
    val onCloseClick: () -> Unit,
    val onViewTransactionClick: () -> Unit,
    override val onBack: () -> Unit
) : TransactionProgressState

/**
 * Part of a multi-transaction send was not accepted. [title], [text1] and [text2] say what happens next: for a TEX send
 * the TEX checker finishes or undoes it by itself (T3b-2), otherwise support is needed. [onViewTransactionClick], when
 * set, opens the last part, whose detail shows the checker's live status.
 */
@Immutable
data class MultipleFailuresTransactionState(
    val showBackButton: Boolean,
    val transactionIds: List<String>,
    val onCopyClick: () -> Unit,
    val onSupportClick: () -> Unit,
    override val onBack: () -> Unit,
    val title: StringResource = stringRes(R.string.send_confirmation_multiple_trx_failure_title),
    val text1: StringResource = stringRes(R.string.send_confirmation_multiple_trx_failure_text_1),
    val text2: StringResource = stringRes(R.string.send_confirmation_multiple_trx_failure_text_2),
    val onViewTransactionClick: (() -> Unit)? = null,
) : TransactionProgressState
