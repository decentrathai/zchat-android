package co.electriccoin.zcash.ui.screen.error

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError

class NavigateToErrorUseCase(
    private val navigationRouter: NavigationRouter,
) {
    private var args: ErrorArgs? = null

    operator fun invoke(args: ErrorArgs) {
        this.args = args
        when (args) {
            is ErrorArgs.ShieldingError -> navigationRouter.forward(ErrorDialog)
            // Route EVERY wallet-sync failure to the actionable sync-error sheet (Try again / Switch
            // server / Contact Support) — never the raw-stack-trace ErrorBottomSheet. Previously only
            // HTTP 5xx / transport / Tor errors got the actionable sheet; DNS, timeout, TLS (e.g. a
            // lightwalletd cert lapse), gRPC UNAVAILABLE and DB errors fell through to a sheet whose
            // body was the raw exception trace ("…FailedSynchronization…"), which a user reads as
            // "Failed to sync wallet" and cannot act on. The full trace is still available via the
            // sheet's Report / Contact Support button (SendEmailUseCase).
            is ErrorArgs.SyncError -> navigationRouter.forward(SyncErrorArgs)
            is ErrorArgs.General -> navigationRouter.forward(ErrorDialog)
            is ErrorArgs.ShieldingGeneralError -> navigationRouter.forward(ErrorDialog)
            is ErrorArgs.SynchronizerTorInitError -> navigationRouter.forward(ErrorDialog)
        }
    }

    // Args live in this (singleton) use case, not in the nav route, so after process death the
    // ErrorDialog/ErrorBottomSheet destination can be restored while `args` is null (also possible if
    // a stacked ErrorVM.onCleared() cleared it). Fall back to a benign General error instead of
    // crashing at launch with a null-cast NPE.
    fun requireCurrentArgs(): ErrorArgs =
        args ?: ErrorArgs.General(IllegalStateException("Error details are no longer available"))

    fun clear() {
        args = null
    }
}

sealed interface ErrorArgs {
    data class SyncError(
        val synchronizerError: SynchronizerError
    ) : ErrorArgs

    data class ShieldingError(
        val error: SubmitResult
    ) : ErrorArgs

    data class ShieldingGeneralError(
        val exception: Exception
    ) : ErrorArgs

    data class General(
        val exception: Exception
    ) : ErrorArgs

    data object SynchronizerTorInitError : ErrorArgs
}
