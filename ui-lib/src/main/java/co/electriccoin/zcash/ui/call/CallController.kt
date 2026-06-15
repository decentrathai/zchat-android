package co.electriccoin.zcash.ui.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide handle on the active [VoiceCallManager]. The foreground service registers
 * its instance here on start; the chat UI grabs it through [current] to place / accept /
 * hangup calls.
 *
 * Using a static accessor instead of Koin DI because (a) Koin scopes within Service are
 * fiddly and (b) there's always at most one VoiceCallManager per process.
 */
object CallController {
    private val _manager: MutableStateFlow<VoiceCallManager?> = MutableStateFlow(null)
    val current: StateFlow<VoiceCallManager?> = _manager.asStateFlow()

    fun register(manager: VoiceCallManager) { _manager.value = manager }
    fun unregister() { _manager.value = null }
}
