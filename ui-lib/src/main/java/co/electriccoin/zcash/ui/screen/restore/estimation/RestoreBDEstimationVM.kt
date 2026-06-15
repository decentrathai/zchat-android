package co.electriccoin.zcash.ui.screen.restore.estimation

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RestoreBDEstimationVM(
    private val args: RestoreBDEstimationArgs,
    private val navigationRouter: NavigationRouter,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val restoreWallet: RestoreWalletUseCase,
) : ViewModel() {
    private val isRestoring = MutableStateFlow(false)

    private val restoreError = MutableStateFlow<StringResource?>(null)

    val state: StateFlow<RestoreBDEstimationState> =
        combine(isRestoring, restoreError) { restoring, error ->
            createState(restoring, error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(isRestoring.value, restoreError.value)
        )

    private fun createState(
        restoring: Boolean,
        error: StringResource?
    ) = RestoreBDEstimationState(
        title = stringRes(R.string.restore_title),
        subtitle = stringRes(R.string.restore_bd_estimation_subtitle),
        message = stringRes(R.string.restore_bd_estimation_message),
        dialogButton =
            IconButtonState(
                icon = R.drawable.ic_help,
                onClick = ::onInfoButtonClick,
            ),
        onBack = ::onBack,
        text = stringResByNumber(args.blockHeight, 0),
        copy =
            ButtonState(
                text = stringRes(R.string.restore_bd_estimation_copy),
                icon = R.drawable.ic_copy,
                onClick = ::onCopyClick
            ),
        restore =
            ButtonState(
                text = stringRes(R.string.restore_bd_estimation_restore),
                onClick = ::onRestoreClick,
                isEnabled = !restoring,
                isLoading = restoring,
                hapticFeedbackType = HapticFeedbackType.Confirm
            ),
        error = error,
    )

    private fun onCopyClick() {
        copyToClipboard(
            value = args.blockHeight.toString()
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onRestoreClick() {
        if (isRestoring.value) return
        // Hidden for now - Tor opt-in is a Zashi feature, skip dialog and restore directly
        viewModelScope.launch {
            isRestoring.update { true }
            restoreError.update { null }
            try {
                restoreWallet(
                    seedPhrase = SeedPhrase.new(args.seed.trim()),
                    enableTor = false,
                    birthday = BlockHeight.new(args.blockHeight)
                )
            } catch (e: Exception) {
                Twig.error(e) { "Failed to restore wallet from estimated height" }
                restoreError.update { stringRes(R.string.restore_bd_error_restore_failed) }
            } finally {
                isRestoring.update { false }
            }
        }
    }

    private fun onBack() = navigationRouter.back()

    private fun onInfoButtonClick() = navigationRouter.forward(SeedInfo)
}
