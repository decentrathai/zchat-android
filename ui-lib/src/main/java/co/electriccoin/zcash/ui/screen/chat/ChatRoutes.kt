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

/**
 * Navigation route for creating a new group.
 */
@Serializable
object CreateGroup

/**
 * Navigation route for group chat detail.
 */
@Serializable
data class GroupDetail(val groupId: String)

/**
 * Navigation route for group settings.
 */
@Serializable
data class GroupSettings(val groupId: String)
