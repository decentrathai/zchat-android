package co.electriccoin.zcash.ui.screen.chat

import kotlinx.serialization.Serializable

/**
 * Navigation route for the chat list screen.
 */
@Serializable
object ChatList

/**
 * Navigation route for a chat detail screen.
 */
@Serializable
data class ChatDetail(val peerAddress: String)

/**
 * Navigation route for composing a new chat message.
 */
@Serializable
object ZchatCompose

/**
 * Navigation route for ZCHAT receive address screen.
 */
@Serializable
object ZchatReceive

/**
 * Navigation route for contact book screen.
 */
@Serializable
object ContactBookRoute
