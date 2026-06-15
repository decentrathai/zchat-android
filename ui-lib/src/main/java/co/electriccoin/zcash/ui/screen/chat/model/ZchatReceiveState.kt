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
        val onCopyAddress: () -> Unit,
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
