package co.electriccoin.zcash.ui.common.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class InAppNotification(
    val senderName: String,
    val messagePreview: String,
    val peerAddress: String,
    // B8 — when true this banner opens the Requests sheet (not a ChatDetail). peerAddress is empty/unused.
    val openRequests: Boolean = false,
)

interface InAppNotificationManager {
    val notification: StateFlow<InAppNotification?>
    fun show(notification: InAppNotification)
    fun dismiss()
}

class InAppNotificationManagerImpl : InAppNotificationManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _notification = MutableStateFlow<InAppNotification?>(null)
    override val notification: StateFlow<InAppNotification?> = _notification

    private var dismissJob: Job? = null

    override fun show(notification: InAppNotification) {
        dismissJob?.cancel()
        _notification.value = notification
        dismissJob = scope.launch {
            delay(AUTO_DISMISS_MS)
            _notification.value = null
        }
    }

    override fun dismiss() {
        dismissJob?.cancel()
        _notification.value = null
    }

    companion object {
        private const val AUTO_DISMISS_MS = 4000L
    }
}
