package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ResetZashiConfirmationVM(
    private val args: ResetZashiConfirmationArgs,
    private val resetZashi: ResetZashiUseCase,
    private val navigationRouter: NavigationRouter
) : ViewModel() {
    private val mutableState = MutableStateFlow(createBottomSheetState(isResetting = false))

    val state: StateFlow<ResetZashiConfirmationState?> = mutableState.asStateFlow()

    private var resetJob: Job? = null

    private fun createBottomSheetState(
        isResetting: Boolean,
        failedText: Int? = null
    ): ResetZashiConfirmationState =
        ResetZashiConfirmationState(
            onBack = ::onDismissBottomSheet,
            onConfirm = ::onConfirmCLick,
            onCancel = ::onDismissBottomSheet,
            isResetting = isResetting,
            failedText = failedText
        )

    // ZC-090-R: while the Reset runs, Back does nothing (and if the screen goes away anyway, the Reset goes on).
    private fun onDismissBottomSheet() {
        if (resetJob?.isActive == true) return
        navigationRouter.back()
    }

    private fun onConfirmCLick() {
        if (resetJob?.isActive == true) return
        mutableState.value = createBottomSheetState(isResetting = true)
        resetJob = viewModelScope.launch {
            // ZC-090-R: a started Reset ends with ZCHAT's restart, so the sheet stays as it is; one that was cancelled
            // or did not start gives the buttons back, and one that did not start says so here (no error screen).
            when (resetZashi(keepFiles = args.keepFiles, aiUnreadableWarningShown = args.aiUnreadableWarningShown)) {
                ResetAnswer.STARTED -> Unit
                ResetAnswer.CANCELLED -> mutableState.value = createBottomSheetState(isResetting = false)
                ResetAnswer.NOT_STARTED ->
                    mutableState.value =
                        createBottomSheetState(isResetting = false, failedText = R.string.delete_wallet_failed)
                ResetAnswer.NOT_STARTED_PAYMENT_RUNNING ->
                    mutableState.value =
                        createBottomSheetState(isResetting = false, failedText = R.string.delete_wallet_failed_payment_running)
                ResetAnswer.NOT_STARTED_PAYMENT_OPEN ->
                    mutableState.value =
                        createBottomSheetState(isResetting = false, failedText = R.string.delete_wallet_failed_payment_open)
            }
        }
    }
}
