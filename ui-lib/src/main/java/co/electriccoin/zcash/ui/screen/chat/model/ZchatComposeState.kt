package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Amount options for message transactions.
 */
enum class MessageAmount(val zatoshi: Long, val label: String, val description: String) {
    ZERO(0L, "0 ZEC", "Free (may be delayed by miners)"),
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
        val isSending: Boolean = false,
        val selectedContact: Contact? = null,
        val showAddContactDialog: Boolean = false,
        val contactName: String = "",
        val maxMessageLength: Int = 4500,
        val chunkCount: Int = 1,
        val messageCost: String = "0.00001 ZEC",
        // Amount settings
        val selectedAmount: MessageAmount = MessageAmount.MINIMAL,
        val customAmountZatoshi: Long = 1000L,
        val showAmountDialog: Boolean = false,
        val totalAmountDisplay: String = "0.00001 ZEC",
        val feeDisplay: String = "~0.00001 ZEC",
        val isZeroAmount: Boolean = false,
        val availableBalanceDisplay: String = "",
        val spendableBalanceZatoshi: Long = 0L,
        val customAmountText: String = "",
        val sendAllAmountDisplay: String = "",
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
        // Callbacks
        val onRecipientChange: (String) -> Unit,
        val onMessageChange: (String) -> Unit,
        val onContactSelect: (Contact) -> Unit,
        val onSendClick: () -> Unit,
        val onScanQrClick: () -> Unit,
        val onBack: () -> Unit,
        val onAddContact: (String, String) -> Unit,
        val onShowAddContactDialog: () -> Unit,
        val onDismissAddContactDialog: () -> Unit,
        val onContactNameChange: (String) -> Unit,
        val onShowAmountDialog: () -> Unit,
        val onDismissAmountDialog: () -> Unit,
        val onAmountSelect: (MessageAmount) -> Unit,
        val onCustomAmountChange: (String) -> Unit,
        val onModeSelect: (ConversationMode) -> Unit
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
}
