package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException

sealed class SendMessageState {
    data object Idle : SendMessageState()
    data object Sending : SendMessageState()

    /**
     * A send completed. [label] is the action-specific confirmation shown to the user — a payment
     * must NOT report "Message sent" (the old hardcoded toast), or the user can't tell whether real
     * ZEC actually moved. Defaults to the generic text for plain messages (Round 8 D8: ChatSendCopy.MESSAGE_SENT, the
     * same English, so a Spanish phone reads it in Spanish).
     */
    data class Success(
        val label: String = ChatSendCopy.MESSAGE_SENT,
    ) : SendMessageState()
    data class Error(val message: String) : SendMessageState()

    /**
     * Round 8 D7 (sweep #13) — a status broadcast that did not reach every contact: [reached] of [total] got it. The
     * chat list shows [ChatSendCopy.STATUS_PARTIAL] with both counts through its string resource.
     * ZC-283: [maybe] of the others may still get it (their memo was handed to the SDK and nothing proves it was not
     * stored); they are never counted as not reached and are not sent the same status again.
     */
    data class StatusPartial(
        val reached: Int,
        val total: Int,
        val maybe: Int = 0,
    ) : SendMessageState()

    /**
     * Funds are only in Sapling and/or transparent: nothing is in a pool that can carry a message.
     * Shielding moves them into Ironwood, the shielded pool value lands in since NU6.3.
     */
    data class NeedsOrchardShielding(
        val saplingBalance: Zatoshi,
        val transparentBalance: Zatoshi,
        // R2-7 (c): the reason is that sending from other pools would publish the amount.
        val message: String = ChatSendCopy.SHIELD_NEEDED
    ) : SendMessageState()

    /**
     * ZChat is about to pay for AUTOMATIC handshake memos (KEX / KEXACK / ZBOOT / time-lock /
     * group-accept) that no user gesture asked for. Ask once, quoting the whole queued bill.
     * [pendingPeers] is the number of paid transactions the queued setups can spend (HS-6: a Tunnel chat
     * setup is two, a KEX or KEXACK plus the ZBOOT that follows), not the number of contacts.
     *
     * R6-4 — [groupJoins] is how many of them are our "I joined" reply (GROUP_ACCEPT) to a group we were
     * added to. Until that reply is sent the group's creator keeps us Invited and sends us no group
     * message, so the question says so instead of only talking about secure chats.
     */
    data class NeedsHandshakeSpendConsent(
        val pendingPeers: Int,
        val estimatedCost: Zatoshi,
        val groupJoins: Int = 0,
    ) : SendMessageState()

    /**
     * R2-2 (b) — a tap is about to pay for on-chain transactions it does not obviously pay for (a Shielded
     * control, a reaction, a re-KEX, the first message's key exchange, a status broadcast). Nothing is paid
     * until ChatViewModel.confirmPaidAction; ChatViewModel.dismissPaidAction drops it.
     */
    data class NeedsPaidActionConfirmation(
        val confirmation: PaidActionConfirmation,
    ) : SendMessageState()

    /**
     * SR-C2 — a photo, file or voice note was refused before anything was prepared, uploaded or paid: it would be locked
     * with this chat's end-to-end key, and the chat has none ([reason] says why and picks the text). The chat screen
     * shows a notice; only [AttachmentBlockReason.NEEDS_KEY_EXCHANGE] offers "Switch to Shielded".
     */
    data class AttachmentNeedsKeyExchange(
        val reason: AttachmentBlockReason,
    ) : SendMessageState()

    /**
     * SR-C1 (KI-37) — a photo, file or voice note is ready for [peerAddress], whose newest relay message came from ZChat
     * 2.12 or older: that version shows the attachment's link as a line of code. Nothing is uploaded, sent or paid until
     * the user answers "Send anyway" (ChatViewModel.confirmLegacyContactAttachment) or "Cancel"
     * (ChatViewModel.cancelLegacyContactAttachment); a warning dismissed or replaced any other way counts as Cancel.
     * [askId] tells two warnings for the same contact apart.
     */
    data class LegacyContactAttachmentWarning(
        val peerAddress: String,
        val askId: Long,
    ) : SendMessageState()

    /**
     * SR-A (S-A5) — a photo, file or voice note in a Shielded chat with [peerAddress] was refused before anything was
     * uploaded or paid: its link goes on-chain, which costs at least [floor], and the wallet can spend [spendable] now.
     * The chat offers "Switch to Tunnel" (its own question when that switch is paid) or "Not now".
     */
    data class ShieldedAttachmentNeedsFunds(
        val peerAddress: String,
        val floor: Zatoshi,
        val spendable: Zatoshi,
    ) : SendMessageState()

    /**
     * Funds are shielded — but in Orchard, which NU6.3 sealed. An Orchard Action may only pay its
     * own address (ZIP 258), so this wallet cannot reach a peer on-chain, and letting the SDK try
     * would fund a turnstile crossing whose amount is public (ZIP 318). The send is refused. The
     * no-pending text is neutral: the chat dialog adds the matching action line (chatSweepPrompt).
     */
    data class NeedsIronwoodMigration(
        val orchardBalance: Zatoshi,
        /** Ironwood value that has landed but is still confirming; > 0 turns "move" into "wait". */
        val ironwoodPending: Zatoshi = Zatoshi(0),
        /**
         * Round 8 D7 (sweep #10) — the part of [ironwoodPending] that is our own change (a send's change, a shielding,
         * a crossing). Only a wallet with none of it has "first Ironwood funds" arriving.
         */
        val ironwoodChangePending: Zatoshi = Zatoshi(0),
        // R4-6 (12): named in ChatSendCopy so the dialog shows it in the phone's language. Same words.
        // Round 8 D7 (sweep #10): "your first Ironwood funds have arrived" only for a deposit from someone else
        // (valuePending) with none of our own change pending; our own change gets the plain "still confirming" copy.
        val message: String =
            when {
                ironwoodPending > Zatoshi(0) && ironwoodChangePending == Zatoshi(0) ->
                    ChatSendCopy.IRONWOOD_FIRST_FUNDS_CONFIRMING
                ironwoodPending > Zatoshi(0) -> OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE
                else -> ChatSendCopy.ORCHARD_POOL_SEALED
            },
        /** Progress of the note that unblocks messaging soonest; null when unknown (the copy then stays as-is). */
        val confirmationProgress: ConfirmationProgress? = null,
    ) : SendMessageState()
}

/**
 * SR-C2 — why a photo, file or voice note cannot be sent in a chat right now (ChatViewModel.attachmentBlockReason).
 * Every attachment's file key is wrapped with the chat's end-to-end shared key, which only a key exchange makes.
 */
enum class AttachmentBlockReason {
    /** An Open or Tunnel chat with no key exchange: a Shielded message would make one. */
    NEEDS_KEY_EXCHANGE,

    /** A Shielded chat whose key exchange has not happened yet: its first text message makes it. */
    NEEDS_KEY_EXCHANGE_SHIELDED,

    /** We hold our key for this contact, but their answer to the key exchange has not arrived. */
    KEY_EXCHANGE_UNANSWERED,

    /** A restored wallet is still reading its history, so ZChat does not create a key for this contact yet. */
    STILL_READING_HISTORY,

    /** ZC-1474: we hold our key for this contact, but our key exchange is still owed: it was never sent (nothing on chain). */
    KEX_NOT_SENT,

    /** ZC-1474: the contact's key exchange arrived and waits for our Accept in this chat (its price is asked there). */
    PEER_MUST_ACCEPT,
}

/** ZC-1474 — what the notice for an [AttachmentBlockReason] offers; the chat screen wires the action. */
enum class AttachmentBlockAction {
    /** "Switch to Shielded" (the mode switch asks first whenever it would pay). */
    SWITCH_TO_SHIELDED,

    /** Send a text message first: it makes the key exchange (priced before anything is paid). */
    SEND_A_MESSAGE,

    /**
     * Send our owed key exchange through the menu's Reconnect: paid at once when the standing consent for handshake
     * transactions was given, otherwise nothing is paid until the user agrees (ZC-1729, ZC-1756).
     */
    SEND_KEY_EXCHANGE,

    /** Accept the contact's key exchange held in this chat (priced before anything is paid). */
    ACCEPT_KEY_EXCHANGE,

    /** Nothing to do here but wait. */
    WAIT,
}

/** ZC-1474: the action of each reason. */
val AttachmentBlockReason.action: AttachmentBlockAction
    get() =
        when (this) {
            AttachmentBlockReason.NEEDS_KEY_EXCHANGE -> AttachmentBlockAction.SWITCH_TO_SHIELDED
            AttachmentBlockReason.NEEDS_KEY_EXCHANGE_SHIELDED -> AttachmentBlockAction.SEND_A_MESSAGE
            AttachmentBlockReason.KEX_NOT_SENT -> AttachmentBlockAction.SEND_KEY_EXCHANGE
            AttachmentBlockReason.PEER_MUST_ACCEPT -> AttachmentBlockAction.ACCEPT_KEY_EXCHANGE
            AttachmentBlockReason.KEY_EXCHANGE_UNANSWERED, AttachmentBlockReason.STILL_READING_HISTORY -> AttachmentBlockAction.WAIT
        }
