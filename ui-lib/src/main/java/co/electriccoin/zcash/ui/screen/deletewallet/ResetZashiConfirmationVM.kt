package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
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

    private fun createBottomSheetState(isResetting: Boolean): ResetZashiConfirmationState =
        ResetZashiConfirmationState(
            onBack = ::onDismissBottomSheet,
            onConfirm = ::onConfirmCLick,
            onCancel = ::onDismissBottomSheet,
            isResetting = isResetting
        )

    private fun onDismissBottomSheet() = navigationRouter.back()

    private fun onConfirmCLick() {
        if (resetJob?.isActive == true) return
        mutableState.value = createBottomSheetState(isResetting = true)
        resetJob = viewModelScope.launch { resetZashi(keepFiles = args.keepFiles) }
    }
}
