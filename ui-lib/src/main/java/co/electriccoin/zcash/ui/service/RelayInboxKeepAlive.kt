package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode

/**
 * ZC-1559: whether [SyncForegroundService] must stay in the foreground after a sync so the relay inbox keeps its
 * network on an idle phone (Android 16 blocks a backgrounded app's network). Needed for every relay chat (Open,
 * Tunnel) and for every peer whose Nostr key is held, which includes a Shielded chat with a call channel. [entries] is
 * the "zchat_conversation_mode" preference file ("mode:<peer>", "pubkey:<peer>"). Pure.
 */
object RelayInboxKeepAlive {
    private const val MODE_PREFIX = "mode:"
    private const val PUBKEY_PREFIX = "pubkey:"

    fun needed(entries: Map<String, *>): Boolean =
        entries.any { (key, value) ->
            when {
                key.startsWith(MODE_PREFIX) ->
                    value == ConversationMode.TUNNEL.name || value == ConversationMode.OPEN.name
                key.startsWith(PUBKEY_PREFIX) -> (value as? String)?.isNotBlank() == true
                else -> false
            }
        }
}
