package co.electriccoin.zcash.ui.common.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Use case for prefilling ZCHAT compose screen with a scanned address.
 */
class PrefillZchatUseCase {
    private val _scannedAddress = MutableStateFlow<String?>(null)
    val scannedAddress: StateFlow<String?> = _scannedAddress.asStateFlow()

    fun request(address: String) {
        _scannedAddress.value = address
    }

    fun consume(): String? {
        val address = _scannedAddress.value
        _scannedAddress.value = null
        return address
    }

    fun clear() {
        _scannedAddress.value = null
    }
}
