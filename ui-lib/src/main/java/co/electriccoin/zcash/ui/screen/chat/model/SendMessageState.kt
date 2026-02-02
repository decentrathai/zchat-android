package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi

sealed class SendMessageState {
    data object Idle : SendMessageState()
    data object Sending : SendMessageState()
    data object Success : SendMessageState()
    data class Error(val message: String) : SendMessageState()

    /**
     * Funds need to be moved to Orchard pool before messaging is allowed.
     * This ensures maximum privacy for all ZCHAT messages.
     */
    data class NeedsOrchardShielding(
        val saplingBalance: Zatoshi,
        val transparentBalance: Zatoshi,
        val message: String = "For maximum privacy, ZCHAT uses the Orchard pool. Move your funds to Orchard to continue."
    ) : SendMessageState()
}
