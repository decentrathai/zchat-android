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
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.screen.restore.date.RestoreBDDateArgs
import java.math.BigDecimal
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class RestoreBDHeightVM(
    private val restoreBDHeight: RestoreBDHeight,
    private val navigationRouter: NavigationRouter,
    private val restoreWallet: RestoreWalletUseCase,
) : ViewModel() {
    private val blockHeightText = MutableStateFlow(NumberTextFieldInnerState())

    private val isRestoring = MutableStateFlow(false)

    private val restoreError = MutableStateFlow<StringResource?>(null)

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
        combine(blockHeightText, isRestoring, restoreError) { text, restoring, error ->
            createState(text, restoring, error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(blockHeightText.value, isRestoring.value, restoreError.value)
        )

    /**
     * Safely converts the parsed [amount] to a whole-number block height.
     *
     * Returns null (treated as invalid) when the value is absent, has a non-zero fractional
     * part (e.g. "1000000.5"), or overflows [Long] (e.g. a value larger than [Long.MAX_VALUE]).
     * [java.math.BigInteger.longValueExact] throws on overflow, hence the [runCatching] guard.
     */
    private fun parsedBlockHeightOrNull(amount: BigDecimal?): Long? {
        val whole = amount?.takeIf { it.stripTrailingZeros().scale() <= 0 } ?: return null
        return runCatching { whole.toBigInteger().longValueExact() }.getOrNull()
    }

    private fun createState(
        blockHeight: NumberTextFieldInnerState,
        restoring: Boolean,
        error: StringResource?
    ): RestoreBDHeightState {
        val saplingActivationHeight = VersionInfo.NETWORK.saplingActivationHeight.value
        val parsedHeight = parsedBlockHeightOrNull(blockHeight.amount)
        val isEmpty = blockHeight.innerTextFieldState.value.isEmpty()
        val isHigherThanSaplingActivationHeight =
            parsedHeight?.let { it >= saplingActivationHeight } ?: false
        val isValid = !isEmpty && isHigherThanSaplingActivationHeight

        val fieldError: StringResource? =
            error
                ?: when {
                    isEmpty -> null
                    parsedHeight == null -> stringRes(R.string.restore_bd_error_not_whole)
                    parsedHeight < saplingActivationHeight ->
                        stringRes(R.string.restore_bd_error_too_low, saplingActivationHeight.toString())
                    else -> null
                }

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
                    isEnabled = isValid && !restoring,
                    isLoading = restoring,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ),
            estimate = ButtonState(stringRes(R.string.restore_bd_height_btn), onClick = ::onEstimateClick),
            blockHeight =
                NumberTextFieldState(
                    innerState = blockHeight,
                    explicitError = fieldError,
                    onValueChange = ::onValueChanged
                )
        )
    }

    private fun onEstimateClick() = navigationRouter.forward(RestoreBDDateArgs(seed = restoreBDHeight.seed))

    @Suppress("TooGenericExceptionCaught")
    private fun onRestoreClick() {
        if (isRestoring.value) return
        val blockHeight = parsedBlockHeightOrNull(blockHeightText.value.amount) ?: return
        // Hidden for now - Tor opt-in is a Zashi feature, skip dialog and restore directly
        viewModelScope.launch {
            isRestoring.update { true }
            restoreError.update { null }
            try {
                restoreWallet(
                    seedPhrase = SeedPhrase.new(restoreBDHeight.seed.trim()),
                    enableTor = false,
                    birthday = BlockHeight.new(blockHeight)
                )
            } catch (e: Exception) {
                Twig.error(e) { "Failed to restore wallet from birthday height" }
                restoreError.update { stringRes(R.string.restore_bd_error_restore_failed) }
            } finally {
                isRestoring.update { false }
            }
        }
    }

    private fun onBack() = navigationRouter.back()

    private fun onInfoButtonClick() = navigationRouter.forward(SeedInfo)

    private fun onValueChanged(state: NumberTextFieldInnerState) {
        restoreError.update { null }
        blockHeightText.update { state }
    }
}
