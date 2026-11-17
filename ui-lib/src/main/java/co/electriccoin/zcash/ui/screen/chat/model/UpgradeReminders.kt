package co.electriccoin.zcash.ui.screen.chat.model

import java.time.Instant
import java.time.LocalDate

/**
 * Bridge 3 (transition plan section 2.3): what a chat tells the user about contacts still on ZChat 2.12.4 or
 * older, which can't see Shielded messages or ZEC sent after the Zcash network upgrade until they update.
 */
enum class ShieldedReminderUi {
    /** Nothing: not a Shielded chat, or a chat we can't reply in. */
    NONE,

    /** The reminder: short label, tap for the full text and its buttons. */
    BANNER,

    /** Hidden by the user: a small "May need update" chip that opens the reminder again. */
    CHIP,
}

/** What "Chat free instead" opens. */
enum class ChatFreeInstead {
    /** The existing Conversation mode picker (Open is selectable: we hold the contact's chat key). */
    MODE_PICKER,

    /** No chat key stored for the contact: the no-key text explains how to get their invite. */
    NO_KEY_TEXT,
}

object UpgradeReminders {
    /**
     * Every Shielded (Vault) chat shows the reminder, and only Shielded chats (a chat with an unknown sender
     * can't be answered, so it has none). Hide leaves the chip in that chat; the chip clears [hiddenByUser].
     * ZC-1270: a contact [contactProven] to run 2.13 ([contactIsProven213]) needs no reminder, and no chip either.
     * ZC-1941 (owner W-148): the reminder ends: from [SHIELDED_REMINDER_ENDS] ([today], the phone's local date) neither
     * the banner nor the chip shows.
     */
    fun shieldedReminder(
        mode: ConversationMode,
        canReply: Boolean,
        hiddenByUser: Boolean,
        contactProven: Boolean = false,
        today: LocalDate = LocalDate.now(),
    ): ShieldedReminderUi =
        when {
            mode != ConversationMode.VAULT || !canReply || contactProven -> ShieldedReminderUi.NONE
            !today.isBefore(SHIELDED_REMINDER_ENDS) -> ShieldedReminderUi.NONE
            hiddenByUser -> ShieldedReminderUi.CHIP
            else -> ShieldedReminderUi.BANNER
        }

    /**
     * ZC-1270: the contact runs 2.13 or newer. Proof is the Ironwood capability tag of their relay messages, or an on-chain
     * message from them after NU6.3, which 2.12.4 cannot send. An older row proves nothing (2.12.4 could send then), nor does
     * a memo that is not authenticated (anyone who pays the address can write one), a relay message, or a local row.
     */
    fun contactIsProven213(
        peerCapIronwood: Boolean?,
        messages: List<ChatMessage>,
    ): Boolean = peerCapIronwood == true || messages.any { it.isOnChainProofFromContact() }

    private fun ChatMessage.isOnChainProofFromContact(): Boolean =
        !isOutgoing &&
            authenticated &&
            !isPending &&
            !isSystemNote &&
            !isAiMessage &&
            callLog == null &&
            !isFreeMessage() &&
            !timestamp.isBefore(FIRST_DAY_AFTER_NU6_3)

    /** Open needs the contact's chat key; without one the picker can't offer it, so the no-key text explains. */
    fun chatFreeInstead(hasPeerChatKey: Boolean): ChatFreeInstead =
        if (hasPeerChatKey) ChatFreeInstead.MODE_PICKER else ChatFreeInstead.NO_KEY_TEXT

    /** New Chat shows the version line while Shielded is selected. */
    fun showsComposeLine(selected: ConversationMode): Boolean = selected == ConversationMode.VAULT

    /**
     * An Open chat shows the chat-request hint until a free message from the contact arrives (a message that
     * came over the relays: its id is the rumor id "nmsg-…", or "nostr-…" for peers that send none).
     * ZC-1465: or until any authenticated relay rumor came from the contact's bound relay key ([heardFromPeer], a read
     * receipt or a mode change included, ChatViewModel.wasHeardFromOverRelays): the contact has the chat, so the
     * request claim would be false.
     */
    fun showsChatRequestHint(
        mode: ConversationMode,
        messages: List<ChatMessage>,
        heardFromPeer: Boolean = false,
    ): Boolean = mode == ConversationMode.OPEN && !heardFromPeer && messages.none { !it.isOutgoing && it.isFreeMessage() }

    private fun ChatMessage.isFreeMessage(): Boolean = id.startsWith("nmsg-") || id.startsWith("nostr-")

    /** ZC-1941: the first day without the Shielded-chat reminder, about 30 days after the 2.13.0 release (17 Nov 2026). */
    val SHIELDED_REMINDER_ENDS: LocalDate = LocalDate.of(2026, 12, 17)

    /** NU6.3 activated on 2026-07-28 (height 3,428,143). The whole of that day counts as before it: a row must be clearly after. */
    private val FIRST_DAY_AFTER_NU6_3: Instant = Instant.parse("2026-07-29T00:00:00Z")
}

/** The share targets "Ask them to update" must not offer: ZChat itself, so the text can't be posted into a chat. */
object ZchatShareExclusions {
    /** The activity-alias that receives ACTION_SEND (app/src/main/AndroidManifest.xml). */
    const val SHARE_TARGET_ALIAS = "co.electricoin.zcash.LauncherActivity"

    /** Every ZChat build's application id starts with this (release, foss, debug, testnet). */
    const val PACKAGE_PREFIX = "xyz.zsend.zchat"

    /**
     * ZChat's own share target is always excluded; so is every resolved target of another installed ZChat
     * build. Pairs are (package name, class name).
     */
    fun excluded(
        ownPackage: String,
        resolvedTargets: List<Pair<String, String>>,
    ): List<Pair<String, String>> =
        (
            listOf(ownPackage to SHARE_TARGET_ALIAS) +
                resolvedTargets.filter { (pkg, _) -> pkg == ownPackage || isZchatPackage(pkg) }
        ).distinct()

    private fun isZchatPackage(pkg: String): Boolean = pkg == PACKAGE_PREFIX || pkg.startsWith("$PACKAGE_PREFIX.")
}
