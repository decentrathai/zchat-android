package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.call.CallSignalEnvelope
import co.electriccoin.zcash.ui.common.notification.OpenChat
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZATTMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZPROFMessage

/**
 * R9-6 (REVIEW_R8 F6 = device D-F6): alerts the user to a message that arrived over the relay (Open and Tunnel chats).
 * Before this, SyncForegroundService only alerted on-chain messages, so a relay message in the background raised
 * nothing at all.
 *
 * NostrChatBridge.dispatch calls [onInbound] for every relay DM from a mapped contact, after its replay and freshness
 * checks and before the chat collector gets it. The alert follows the on-chain rule (chatAlert): the privacy level,
 * mute, the in-app banner in the foreground, nothing for the chat that is open on screen. Nothing is raised for:
 *  - a control payload the chat never shows as a message ([relayMessagePreview] returns null);
 *  - an event the chat already marked seen, or one this notifier already handled. The relay replays stored
 *    gift-wraps on every resubscribe, and when no chat screen is running nothing marks them seen, so the notifier
 *    keeps its own bounded memory of the event ids and rumor ids it handled.
 */
internal class RelayMessageNotifier(
    private val prefs: ZchatPreferences,
    private val isInForeground: () -> Boolean,
    private val deliver: (ChatAlert) -> Unit,
) {
    private val handled =
        object : LinkedHashMap<String, Boolean>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_REMEMBERED
        }

    fun onInbound(chat: NostrChatBridge.InboundChat) {
        val peer = chat.peerAddress
        if (peer.isEmpty()) return // an unattributed rotation announce, never a message
        val preview = relayMessagePreview(chat.plaintext) ?: return
        val keys = listOfNotNull("e:${chat.eventId}", chat.rumorId.takeIf { it.isNotEmpty() }?.let { "r:$it" })
        synchronized(handled) {
            if (keys.any { handled.containsKey(it) }) return
            // KI-24: also a wrap older than the seen list's floor (its id may have left the list; this memory is
            // only as old as the process), so a relay replay of an old message never alerts again.
            if (prefs.hasSeenNostrEvent(chat.eventId, chat.wrapCreatedAtSec)) return
            keys.forEach { handled[it] = true }
        }
        val alert =
            chatAlert(
                privacy = prefs.getNotificationPrivacy(),
                senderAddress = peer,
                senderLabel = prefs.getDisplayName(peer),
                messagePreview = truncateChatPreview(preview),
                muted = prefs.isConversationMuted(peer),
                isInForeground = isInForeground(),
                openChatPeer = OpenChat.peerAddress,
                key = "nostr:${chat.eventId}",
            ) ?: return
        deliver(alert)
    }

    private companion object {
        const val MAX_REMEMBERED = 2_000
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * R9-6: what a relay message's alert may show, or null when the payload is a control the chat never shows as a
 * message (ChatViewModel.observeNostrInbound handles these without a row). Mirrors that handler: a known contact's
 * first Open message may come as a v4 INIT (unwrapped to its text), and a reply carries a reply marker (stripped).
 * Files, payment requests and time-locked messages get the same generic labels as on-chain: never the raw
 * "ZFILE|…" string, a request's free-text reason or a locked message's content.
 */
internal fun relayMessagePreview(plaintext: String): String? {
    if (isRelayControl(plaintext)) return null
    val unwrapped = ZMSGProtocol.parseV4Init(plaintext)?.second ?: plaintext
    val body = stripReplyMarker(unwrapped)
    if (body.isBlank() || isRelayControl(body)) return null
    return when {
        ZMSGProtocol.isTimeLock(body) -> "🔒 Time-locked message"
        ZMSGProtocol.isPaymentRequest(body) -> "💸 Payment request"
        ZFILEMessage.isFileMessage(body) -> ZFILEMessage.parse(body)?.let { "📎 ${it.displayText}" } ?: "📎 File"
        // ZC-1474: a free attachment's line holds its file key: its file label only; a malformed one is never shown.
        ZATTMessage.isZatt(body) -> ZATTMessage.parse(body)?.let { "📎 ${it.toFileRef().displayText}" }
        else -> body
    }
}

private fun isRelayControl(text: String): Boolean =
    ZBootMessage.isBootMessage(text) ||
        CallSignalEnvelope.isSignal(text) ||
        ZMSGProtocol.isDisappearSetting(text) ||
        ZMSGProtocol.isModeChange(text) ||
        ZPROFMessage.isProfileMessage(text) ||
        ZMSGGroupProtocol.isGroupMessage(text) ||
        ZMSGProtocol.isStatus(text) ||
        ZMSGProtocol.isReadReceipt(text) ||
        ZMSGProtocol.isReaction(text) ||
        ZMSGProtocol.isKEXMessage(text) ||
        ZMSGProtocol.isKEXAckMessage(text) ||
        ZMSGProtocol.isUnlock(text) ||
        text.startsWith(ZMSGConstants.REMOTE_KILL_PREFIX)

/** The reply marker ChatViewModel.tagReply prepends ("\u0001RPL:<id>[\u001f<preview>]\u0001"), removed as untagReply does. */
private fun stripReplyMarker(content: String): String {
    if (!content.startsWith(REPLY_REF_PREFIX)) return content
    val end = content.indexOf(REPLY_REF_SENTINEL, REPLY_REF_PREFIX.length)
    if (end == -1) return content
    val ref = content.substring(REPLY_REF_PREFIX.length, end)
    val id = ref.substringBefore(REPLY_PREVIEW_SEPARATOR)
    if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return content
    return content.substring(end + 1)
}

private const val REPLY_REF_SENTINEL = '\u0001'
private const val REPLY_REF_PREFIX = "${REPLY_REF_SENTINEL}RPL:"
private const val REPLY_PREVIEW_SEPARATOR = '\u001f'
