package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * ZC-1479 + ZC-1472 (owner, W-122 answer 3; Fable M1): the price on the send button, the composer's own figure
 * (ChatViewModel.composerMessageCost, the least a message of the typed length costs). It is shown wherever a send
 * spends ZEC: every Shielded message, and a Tunnel message before its handshake is done (the send then goes on-chain).
 * Open, and a Tunnel whose handshake is done and whose relay publisher is ready (tunnelSendIsFree), send for free and
 * show no price and no "Free" row. The same rule the composer cost row used, so no paid send loses its price.
 */
object SendCostLabel {
    fun text(
        mode: ConversationMode,
        tunnelSendIsFree: Boolean,
        cost: Zatoshi,
    ): Zatoshi? = if (mode.isShieldedOnlyTransport || (mode.needsBootstrap && !tunnelSendIsFree)) cost else null
}
