package co.electriccoin.zcash.ui.common.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Use case to pass scanned QR data from Scan screen to RestoreSeed screen.
 */
class PrefillRestoreSeedUseCase {
    private val _scannedQrData = MutableStateFlow<String?>(null)
    val scannedQrData: StateFlow<String?> = _scannedQrData.asStateFlow()

    fun request(qrData: String) {
        _scannedQrData.value = qrData
    }

    fun consume(): String? {
        val data = _scannedQrData.value
        _scannedQrData.value = null
        return data
    }

    fun clear() {
        _scannedQrData.value = null
    }
}
