package co.electriccoin.zcash.ui.screen.chat.filesharing

/**
 * Temporary bridge between the QR scanner and the Quantum Shield flow.
 *
 * When the user taps "Scan Peer's QR" in the Quantum Shield dialog,
 * the chat screen sets [pendingCallback] before navigating to the scanner.
 * The scanner checks for ZCPSK: prefix and calls [consume] which invokes
 * and clears the callback.
 *
 * This is a workaround for the navigation-scoped ViewModel limitation
 * (scanner VM is created fresh, can't hold chat-screen state). A proper
 * fix would use SavedStateHandle or navigation result APIs.
 */
object QuantumShieldScanBridge {
    private var pendingCallback: ((String) -> Unit)? = null
    private var pendingPeerAddress: String? = null

    fun setPending(peerAddress: String, callback: (String) -> Unit) {
        pendingPeerAddress = peerAddress
        pendingCallback = callback
    }

    fun hasPending(): Boolean = pendingCallback != null

    fun consume(zcpskPayload: String): Boolean {
        val cb = pendingCallback ?: return false
        pendingCallback = null
        pendingPeerAddress = null
        cb(zcpskPayload)
        return true
    }

    fun clear() {
        pendingCallback = null
        pendingPeerAddress = null
    }
}
