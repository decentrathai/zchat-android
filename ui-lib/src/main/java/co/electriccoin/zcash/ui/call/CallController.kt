package co.electriccoin.zcash.ui.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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

    // ZC-1981: the hosts (MainActivity, IncomingCallActivity) that draw a call screen while started.
    private val callScreenHosts = MutableStateFlow<Set<Any>>(emptySet())

    /** ZC-1981: true while a call screen is drawn in a started activity (OngoingCallNotice.cardFor). */
    val callScreenShown: Flow<Boolean> = callScreenHosts.map { it.isNotEmpty() }.distinctUntilChanged()

    fun setCallScreenShown(
        host: Any,
        shown: Boolean,
    ) {
        callScreenHosts.update { if (shown) it + host else it - host }
    }
}
