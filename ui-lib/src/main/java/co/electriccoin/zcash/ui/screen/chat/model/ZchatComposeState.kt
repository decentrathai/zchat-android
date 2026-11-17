package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Amount options for message transactions.
 */
enum class MessageAmount(val zatoshi: Long, val label: String, val description: String) {
    // NOTE: there is deliberately NO "0 ZEC (Free)" tier. A 0-value output is rejected on-chain
    // (org.zecdev.zip321 → AmountTooSmall) and advertising it as "free" was a lie — the send either
    // failed with a raw exception or (with the source-level dust coerce) silently charged the
    // minimum. Truly-free messaging is Tunnel/Open over NOSTR, never a 0-value transaction. The
    // smallest on-chain tier is MINIMAL.
    MINIMAL(1000L, "0.00001 ZEC", "Minimal (recommended)"),
    SMALL(10000L, "0.0001 ZEC", "Small tip"),
    SEND_ALL(-2L, "Send All", "Send entire available balance"),
    CUSTOM(-1L, "Custom", "Set custom amount")
}

/**
 * State for the ZCHAT compose/new message screen.
 */
sealed class ZchatComposeState {
    data object Loading : ZchatComposeState()

    data class Ready(
        val contacts: List<Contact>,
        val recipientAddress: String = "",
        val message: String = "",
        val isValidAddress: Boolean = false,
        // G3 8c: the recipient is an address of this wallet (any account, or the default address). isValidAddress is
        // false for it too (Send stays off); the recipient field says why.
        val isOwnAddress: Boolean = false,
        val isSending: Boolean = false,
        // ZC-601: Send was tapped and the exact price is being worked out; nothing is sent yet (never "Sending").
        val isAskingPrice: Boolean = false,
        val selectedContact: Contact? = null,
        val showAddContactDialog: Boolean = false,
        val contactName: String = "",
        // ZC-733-B: the saved names cannot be read (the list says so), and the last Add contact was refused.
        val contactNamesUnreadable: Boolean = false,
        val contactNotSaved: Boolean = false,
        val maxMessageLength: Int = 4500,
        val chunkCount: Int = 1,
        val messageCost: String = "0.00001 ZEC",
        // Amount settings
        val selectedAmount: MessageAmount = MessageAmount.MINIMAL,
        val customAmountZatoshi: Long = 1000L,
        val showAmountDialog: Boolean = false,
        val totalAmountDisplay: String = "0.00001 ZEC",
        // QA2 Q2-91 (ZC-1664): the total without the ZChat fee, for the card that names the fee apart.
        val amountDisplay: String = "0.00001 ZEC",
        // QA2 Q2-84 (ZC-1660): a custom amount above the available balance (warning shown, "Done" waits).
        val isAmountOverBalance: Boolean = false,
        val feeDisplay: String = "~0.00001 ZEC",
        val isZeroAmount: Boolean = false,
        val availableBalanceDisplay: String = "",
        val spendableBalanceZatoshi: Long = 0L,
        val customAmountText: String = "",
        val sendAllAmountDisplay: String = "",
        // Send All's one-line status on the amount card: why it cannot fill, "working out…", the amount
        // kept for the secure handshake (C-8), or "Amount updated – tap Send again" (C-9).
        val sendAllNote: String = "",
        // Conversation transport mode chosen before the first message is sent. New-chat composer
        // defaults to TUNNEL (smart default; the VM re-asserts it and respects any per-peer stored
        // choice). NOT ConversationMode.DEFAULT — that stays VAULT for inbound message interpretation.
        val selectedMode: ConversationMode = ConversationMode.TUNNEL,
        // True once we hold the peer's NOSTR key (scanned from their ZCHAT contact QR): only then can
        // OPEN deliver a free NOSTR DM from message #1, so the mode selector offers OPEN only when true.
        val openAvailable: Boolean = false,
        // True when the pending send will take the free-OPEN path (OPEN selected AND key known): the
        // amount/cost UI then shows "Free" instead of a ZEC amount.
        val isFreeOpenSend: Boolean = false,
        // Round 8 P4 (F6): the recipient already has a chat. Send sends nothing from here: it opens that chat with the
        // text as its draft. The mode picker and the amount are hidden; the chat keeps its own mode.
        val opensExistingChat: Boolean = false,
        // Callbacks
        val onRecipientChange: (String) -> Unit,
        val onMessageChange: (String) -> Unit,
        val onContactSelect: (Contact) -> Unit,
        val onSendClick: () -> Unit,
        val onScanQrClick: () -> Unit,
        // ZC-1461: opens My code (our free chat code and our shielded address).
        val onMyCodeClick: () -> Unit = {},
        val onBack: () -> Unit,
        val onAddContact: (String, String) -> Unit,
        val onShowAddContactDialog: () -> Unit,
        val onDismissAddContactDialog: () -> Unit,
        val onContactNameChange: (String) -> Unit,
        val onShowAmountDialog: () -> Unit,
        val onDismissAmountDialog: () -> Unit,
        val onAmountSelect: (MessageAmount) -> Unit,
        val onCustomAmountChange: (String) -> Unit,
        val onModeSelect: (ConversationMode) -> Unit,
        // M1: why a pasted or scanned contact code bound nothing (shown under the recipient field), or null.
        val codeNotice: ContactCodeNotice? = null,
        // M1: the notice's action, for ContactCodeNotice.KeyAlreadyBound: opens that chat.
        val onOpenExistingChat: (String) -> Unit = {},
    ) : ZchatComposeState()

    data class Error(
        val message: String,
        val onBack: () -> Unit,
        val onRetry: () -> Unit
    ) : ZchatComposeState()

    data class SendSuccess(
        val recipientAddress: String,
        val isNewContact: Boolean,
        val onAddToContacts: () -> Unit,
        val onDone: () -> Unit
    ) : ZchatComposeState()

    /**
     * D2 (REVIEW_7C F11): the submit started and the failure does not prove that nothing was stored, so the send may
     * still go out. [message] is SubmitAttemptProof.MAY_HAVE_BEEN_SENT, or ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT for
     * a value send. Unlike [Error] there is no Retry: the only action closes the screen.
     */
    data class MayHaveBeenSent(
        val message: String,
        val onClose: () -> Unit
    ) : ZchatComposeState()
}

/**
 * M1 (fix-all 2.13.0, train T4b) — why a contact code pasted into New Chat, or scanned for it, bound nothing. The screen
 * shows the matching text under the recipient field; nothing was saved in either case.
 */
sealed interface ContactCodeNotice {
    /** The code's address fails the address check (for example a code cut short on the way). */
    data object AddressInvalid : ContactCodeNotice

    /**
     * The code's messaging (relay) key already belongs to [existingPeer], shown as [displayName] (the contact's name, or
     * the address shortened). The recipient is not set to the code's address; the action opens [existingPeer]'s chat.
     */
    data class KeyAlreadyBound(
        val existingPeer: String,
        val displayName: String,
    ) : ContactCodeNotice
}
