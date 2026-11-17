package co.electriccoin.zcash.ui.common.notification

/**
 * R9-6 (REVIEW_R8 F6): the 1:1 chat that is on screen, if any. The chat screen sets it while it is shown, and the
 * chat alerts (SyncForegroundService.chatAlert) raise nothing for a message to that chat while the app is in the
 * foreground: the message appears in the open chat. In the background the value is ignored, so a chat left open
 * behind the lock screen still notifies.
 */
object OpenChat {
    @Volatile
    var peerAddress: String? = null
        private set

    @Synchronized
    fun opened(peerAddress: String) {
        this.peerAddress = peerAddress
    }

    /** Clears the open chat only when it is still [peerAddress], so a screen closing late cannot clear a newer one. */
    @Synchronized
    fun closed(peerAddress: String) {
        if (this.peerAddress == peerAddress) this.peerAddress = null
    }
}
