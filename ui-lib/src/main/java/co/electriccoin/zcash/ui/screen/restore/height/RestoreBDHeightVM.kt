package co.electriccoin.zcash.ui.screen.restore.height

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.screen.restore.date.RestoreBDDateArgs
import java.math.BigDecimal
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class RestoreBDHeightVM(
    private val restoreBDHeight: RestoreBDHeight,
    private val navigationRouter: NavigationRouter,
    private val restoreWallet: RestoreWalletUseCase,
) : ViewModel() {
    private val blockHeightText = MutableStateFlow(NumberTextFieldInnerState())

    init {
        // Prefill birthday from QR scan if available
        android.util.Log.d("ZCHAT_RESTORE", "RestoreBDHeightVM init: prefillBirthday=${restoreBDHeight.prefillBirthday}")
        restoreBDHeight.prefillBirthday?.let { birthday ->
            android.util.Log.d("ZCHAT_RESTORE", "Prefilling birthday: $birthday")
            blockHeightText.update {
                it.copy(
                    innerTextFieldState = it.innerTextFieldState.copy(value = stringRes(birthday.toString())),
                    amount = BigDecimal(birthday)
                )
            }
        }
    }

    val state: StateFlow<RestoreBDHeightState> =
        blockHeightText
            .map { text ->
                createState(text)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(blockHeightText.value)
            )

    private fun createState(blockHeight: NumberTextFieldInnerState): RestoreBDHeightState {
        val isHigherThanSaplingActivationHeight =
            blockHeight
                .amount
                ?.let {
                    it.toLong() >= VersionInfo.NETWORK.saplingActivationHeight.value
                }
                ?: false
        val isValid = !blockHeight.innerTextFieldState.value.isEmpty() && isHigherThanSaplingActivationHeight

        return RestoreBDHeightState(
            title = stringRes(R.string.restore_title),
            subtitle = stringRes(R.string.restore_bd_subtitle),
            message = stringRes(R.string.restore_bd_message),
            textFieldTitle = stringRes(R.string.restore_bd_text_field_title),
            textFieldHint = stringRes(R.string.restore_bd_text_field_hint),
            textFieldNote = stringRes(R.string.restore_bd_text_field_note),
            onBack = ::onBack,
            dialogButton =
                IconButtonState(
                    icon = R.drawable.ic_help,
                    onClick = ::onInfoButtonClick,
                ),
            restore =
                ButtonState(
                    stringRes(R.string.restore_bd_restore_btn),
                    onClick = ::onRestoreClick,
                    isEnabled = isValid,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ),
            estimate = ButtonState(stringRes(R.string.restore_bd_height_btn), onClick = ::onEstimateClick),
            blockHeight = NumberTextFieldState(innerState = blockHeight, onValueChange = ::onValueChanged)
        )
    }

    private fun onEstimateClick() = navigationRouter.forward(RestoreBDDateArgs(seed = restoreBDHeight.seed))

    private fun onRestoreClick() {
        val blockHeight = blockHeightText.value.amount?.toLong() ?: return
        // Hidden for now - Tor opt-in is a Zashi feature, skip dialog and restore directly
        viewModelScope.launch {
            restoreWallet(
                seedPhrase = SeedPhrase.new(restoreBDHeight.seed.trim()),
                enableTor = false,
                birthday = BlockHeight.new(blockHeight)
            )
        }
    }

    private fun onBack() = navigationRouter.back()

    private fun onInfoButtonClick() = navigationRouter.forward(SeedInfo)

    private fun onValueChanged(state: NumberTextFieldInnerState) = blockHeightText.update { state }
}
