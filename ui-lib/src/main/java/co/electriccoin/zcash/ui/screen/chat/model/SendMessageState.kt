package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi

sealed class SendMessageState {
    data object Idle : SendMessageState()
    data object Sending : SendMessageState()

    /**
     * A send completed. [label] is the action-specific confirmation shown to the user — a payment
     * must NOT report "Message sent" (the old hardcoded toast), or the user can't tell whether real
     * ZEC actually moved. Defaults to the generic text for plain messages.
     */
    data class Success(val label: String = "Message sent") : SendMessageState()
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
