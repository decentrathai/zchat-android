package co.electriccoin.zcash.ui.screen.scankeystone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.KeystoneFirmwareBelowMinimumException
import co.electriccoin.zcash.ui.common.repository.ParsePCZTException
import co.electriccoin.zcash.ui.common.usecase.InvalidKeystonePCZTQRException
import co.electriccoin.zcash.ui.common.usecase.ParseKeystonePCZTUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ScanKeystonePCZTViewModel(
    private val parseKeystonePCZT: ParseKeystonePCZTUseCase
) : ViewModel() {
    val validationState = MutableStateFlow(ScanValidationState.NONE)

    val state =
        MutableStateFlow(
            ScanKeystoneState(
                progress = null,
                message = stringRes(R.string.scan_keystone_info_transaction),
            )
        )

    @Suppress("TooGenericExceptionCaught")
    fun onScanned(result: String) =
        viewModelScope.launch {
            try {
                val scanResult = parseKeystonePCZT(result)
                state.update { it.copy(progress = scanResult.progress) }
            } catch (_: InvalidKeystonePCZTQRException) {
                validationState.update { ScanValidationState.INVALID }
            } catch (_: ParsePCZTException) {
                validationState.update { ScanValidationState.INVALID }
            } catch (_: KeystoneFirmwareBelowMinimumException) {
                // K0-4 (2.13.0): the signature came from firmware below 3.0.2. It was not stored, so nothing was sent;
                // say what to do instead of "invalid QR".
                state.update { it.copy(progress = null, message = stringRes(R.string.keystone_firmware_below_minimum)) }
                validationState.update { ScanValidationState.INVALID }
            } catch (e: Exception) {
                // Unexpected error: surface the INVALID UI so the user can retry
                // instead of being stuck on an indefinite spinner.
                Twig.error(e) { "Unexpected error parsing Keystone PCZT QR" }
                validationState.update { ScanValidationState.INVALID }
            }
        }
}
