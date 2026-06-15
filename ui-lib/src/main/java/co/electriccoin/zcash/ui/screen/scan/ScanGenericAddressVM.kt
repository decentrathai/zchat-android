package co.electriccoin.zcash.ui.screen.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ScanGenericAddressVM(
    private val args: ScanGenericAddressArgs,
    private val parseZip321: Zip321ParseUriValidationUseCase,
    private val navigateToScanAddress: NavigateToScanGenericAddressUseCase,
) : ViewModel() {
    val state = MutableStateFlow(ScanValidationState.NONE)

    private val mutex = Mutex()

    private var hasBeenScannedSuccessfully = false

    /**
     * Callback for Quantum Shield ZCPSK payloads. When a ZCPSK: QR is scanned, this
     * is invoked instead of the normal address-navigation flow. The callback receives
     * the full ZCPSK:<base64> payload string.
     */
    var onQuantumShieldScanned: ((String) -> Unit)? = null

    /**
     * Peer address for Quantum Shield context. Set before navigating to scanner
     * when the purpose is PSK exchange.
     */
    var quantumShieldPeerAddress: String? = null

    fun onScanned(result: String) =
        viewModelScope.launch {
            mutex.withLock {
                if (!hasBeenScannedSuccessfully) {
                    // Quantum Shield PSK payload: route via bridge
                    if (result.startsWith("ZCPSK:") &&
                        co.electriccoin.zcash.ui.screen.chat.filesharing.QuantumShieldScanBridge.hasPending()
                    ) {
                        state.update { ScanValidationState.VALID }
                        co.electriccoin.zcash.ui.screen.chat.filesharing.QuantumShieldScanBridge.consume(result)
                        hasBeenScannedSuccessfully = true
                        navigateToScanAddress.onScanCancelled(args) // go back to chat
                        return@withLock
                    }

                    runCatching {
                        when (val zip321ValidationResult = parseZip321(result)) {
                            is Zip321ParseUriValidation.Valid ->
                                onZip321Scanned(zip321ValidationResult)
                            is Zip321ParseUriValidation.SingleAddress ->
                                onZip321SingleAddressScanned(zip321ValidationResult)
                            else -> onAddressScanned(result)
                        }
                    }.onFailure { e ->
                        co.electriccoin.zcash.spackle.Twig.error(e) { "Scan validation failed" }
                        hasBeenScannedSuccessfully = false
                        state.update { ScanValidationState.INVALID }
                    }
                }
            }
        }

    private suspend fun onAddressScanned(result: String) {
        state.update { ScanValidationState.VALID }
        navigateToScanAddress.onScanned(
            address = result,
            amount = null,
            args = args
        )
        hasBeenScannedSuccessfully = true
    }

    private suspend fun onZip321SingleAddressScanned(result: Zip321ParseUriValidation.SingleAddress) {
        state.update { ScanValidationState.VALID }
        navigateToScanAddress.onScanned(
            address = result.address,
            amount = null,
            args = args
        )
        hasBeenScannedSuccessfully = true
    }

    private suspend fun onZip321Scanned(result: Zip321ParseUriValidation.Valid) {
        val payment =
            result.payment.payments.firstOrNull()
                ?: run {
                    // Malformed ZIP321 with no payments: route to the invalid-QR path
                    state.update { ScanValidationState.INVALID }
                    return
                }
        state.update { ScanValidationState.VALID }
        navigateToScanAddress.onScanned(
            address = payment.recipientAddress.value,
            amount = payment.nonNegativeAmount.value,
            args = args
        )
        hasBeenScannedSuccessfully = true
    }

    fun onScannedError() =
        viewModelScope.launch {
            mutex.withLock {
                if (!hasBeenScannedSuccessfully) {
                    state.update { ScanValidationState.INVALID }
                }
            }
        }

    fun onBack() =
        viewModelScope.launch {
            navigateToScanAddress.onScanCancelled(args)
        }
}
