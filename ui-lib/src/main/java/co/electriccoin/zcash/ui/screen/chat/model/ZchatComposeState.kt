package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Amount options for message transactions.
 */
enum class MessageAmount(val zatoshi: Long, val label: String, val description: String) {
    ZERO(0L, "0 ZEC", "Free (may be delayed by miners)"),
    MINIMAL(1000L, "0.00001 ZEC", "Minimal (recommended)"),
    SMALL(10000L, "0.0001 ZEC", "Small tip"),
    MEDIUM(100000L, "0.001 ZEC", "Medium tip"),
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
        val onCustomAmountChange: (String) -> Unit
    ) : ZchatComposeState()

    data class Error(val message: String) : ZchatComposeState()

    data class SendSuccess(
        val recipientAddress: String,
        val isNewContact: Boolean,
        val onAddToContacts: () -> Unit,
        val onDone: () -> Unit
    ) : ZchatComposeState()
}
