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

    // M1: a scanned contact code whose messaging key already belongs to this other address. The scan bound nothing and
    // prefilled nothing; New Chat shows the "already belongs to" notice for it.
    private val _keyBoundElsewhere = MutableStateFlow<String?>(null)
    val keyBoundElsewhere: StateFlow<String?> = _keyBoundElsewhere.asStateFlow()

    fun requestKeyBoundElsewhere(existingPeer: String) {
        _keyBoundElsewhere.value = existingPeer
    }

    fun clearKeyBoundElsewhere() {
        _keyBoundElsewhere.value = null
    }
}
