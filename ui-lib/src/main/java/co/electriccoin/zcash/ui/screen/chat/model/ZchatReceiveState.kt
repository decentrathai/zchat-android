package co.electriccoin.zcash.ui.screen.chat.model

import androidx.compose.runtime.Immutable

@Immutable
sealed class ZchatReceiveState {
    @Immutable
    data object Loading : ZchatReceiveState()

    @Immutable
    data class Success(
        val shieldedAddress: String,
        val transparentAddress: String,
        val showingTransparent: Boolean = false,
        // ZCHAT contact code: the shielded address + (when derivable) our NOSTR key + relay, offered as
        // COPYABLE TEXT ([contactCodeText]) so a peer can paste it to start a FREE NOSTR ("Open") chat from
        // message #1. It is NOT rendered as the on-screen QR — that QR stays the bare payable address so any
        // wallet can pay. [supportsOpen] is true only when the code actually carries the NOSTR key.
        val contactCodeText: String = shieldedAddress,
        val supportsOpen: Boolean = false,
        val onCopyAddress: () -> Unit,
        val onCopyContactCode: () -> Unit = {},
        val onShowTransparent: () -> Unit,
        val onShowShielded: () -> Unit,
        val onBack: () -> Unit
    ) : ZchatReceiveState()

    @Immutable
    data class Error(
        val message: String,
        val onRetry: () -> Unit,
        val onBack: () -> Unit
    ) : ZchatReceiveState()
}
