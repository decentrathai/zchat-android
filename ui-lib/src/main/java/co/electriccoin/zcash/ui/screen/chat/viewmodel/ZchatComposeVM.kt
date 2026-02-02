package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant

class ZchatComposeVM(
    private val contactBook: ContactBook,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val navigationRouter: NavigationRouter,
    private val prefillZchat: PrefillZchatUseCase,
    private val transactionRepository: TransactionRepository,
    private val zchatPreferences: ZchatPreferences
) : ViewModel() {

    private val _state = MutableStateFlow<ZchatComposeState>(ZchatComposeState.Loading)
    val state: StateFlow<ZchatComposeState> = _state.asStateFlow()

    // Disclaimer dialog state
    private val _showCostDisclaimer = MutableStateFlow(false)
    val showCostDisclaimer: StateFlow<Boolean> = _showCostDisclaimer.asStateFlow()

    private var recipientAddress = ""
    private var message = ""
    private var selectedContact: Contact? = null
    private var userAddress: String? = null
    private var showAddContactDialog = false
    private var contactName = ""

    // Amount settings
    private var selectedAmount: MessageAmount = MessageAmount.MINIMAL
    private var customAmountZatoshi: Long = 1000L
    private var showAmountDialog = false

    // Track addresses we've ever sent outgoing messages to
    // This is used to determine if we need INIT format (include full address) or hash format
    private val sentToAddresses = MutableStateFlow<Set<String>>(emptySet())

    companion object {
        // Estimated transaction fee (this is approximate)
        private const val ESTIMATED_FEE_ZATOSHI = 1000L
    }

    init {
        loadInitialState()
        observeScannedAddress()
        loadSentToAddresses()
    }

    /**
     * Load addresses we've sent to from transaction history.
     * This determines if they already have our address from a previous INIT message.
     */
    private fun loadSentToAddresses() {
        viewModelScope.launch {
            transactionRepository.transactions.collectLatest { transactions ->
                val sentTo = mutableSetOf<String>()
                transactions?.forEach { tx ->
                    if (tx is SendTransaction) {
                        tx.recipient?.address?.let { sentTo.add(it) }
                    }
                }
                sentToAddresses.value = sentTo
                // Refresh UI state when sent addresses change
                updateState()
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                // Use default unified address for consistency after wallet restore
                // IMPORTANT: Do NOT use fallback to account.unified.address - it may be a different diversified address
                userAddress = getDefaultUnifiedAddress()
                // Check for prefilled address from QR scan
                prefillZchat.consume()?.let { scannedAddress ->
                    recipientAddress = scannedAddress
                    selectedContact = contactBook.getContact(scannedAddress)
                }
                updateState()
            } catch (e: Exception) {
                _state.value = ZchatComposeState.Error(e.message ?: "Failed to load")
            }
        }
    }

    private fun observeScannedAddress() {
        viewModelScope.launch {
            prefillZchat.scannedAddress.collectLatest { address ->
                if (address != null) {
                    recipientAddress = address
                    selectedContact = contactBook.getContact(address)
                    prefillZchat.clear()
                    updateState()
                }
            }
        }
    }

    private fun updateState() {
        val contacts = contactBook.getAllContacts()
        val isValid = isValidZcashAddress(recipientAddress)
        // Use transaction history to determine if this is first message
        // If we've ever sent to this address, they have our address (from INIT) so we can use hash format
        val isFirstMessage = !sentToAddresses.value.contains(recipientAddress)
        val chunkCount = if (message.isNotEmpty()) {
            ZMSGProtocol.calculateChunkCount(message, isFirstMessage)
        } else 1

        // Calculate amount per output
        val amountPerOutput = getEffectiveAmountZatoshi()
        val totalAmount = amountPerOutput * chunkCount
        val isZero = amountPerOutput == 0L

        _state.value = ZchatComposeState.Ready(
            contacts = contacts,
            recipientAddress = recipientAddress,
            message = message,
            isValidAddress = isValid,
            isSending = false,
            selectedContact = selectedContact,
            showAddContactDialog = showAddContactDialog,
            contactName = contactName,
            maxMessageLength = ZMSGProtocol.getMaxChunkedMessageLength(isFirstMessage, 10),
            chunkCount = chunkCount,
            messageCost = formatZatoshi(totalAmount),
            // Amount settings
            selectedAmount = selectedAmount,
            customAmountZatoshi = customAmountZatoshi,
            showAmountDialog = showAmountDialog,
            totalAmountDisplay = formatZatoshi(totalAmount),
            feeDisplay = "~${formatZatoshi(ESTIMATED_FEE_ZATOSHI)}",
            isZeroAmount = isZero,
            // Callbacks
            onRecipientChange = { onRecipientChange(it) },
            onMessageChange = { onMessageChange(it) },
            onContactSelect = { onContactSelect(it) },
            onSendClick = { onSendClick() },
            onScanQrClick = { onScanQrClick() },
            onBack = { navigationRouter.back() },
            onAddContact = { addr, name -> onAddContact(addr, name) },
            onShowAddContactDialog = { showAddContactDialog() },
            onDismissAddContactDialog = { dismissAddContactDialog() },
            onContactNameChange = { onContactNameChange(it) },
            onShowAmountDialog = { showAmountDialog() },
            onDismissAmountDialog = { dismissAmountDialog() },
            onAmountSelect = { onAmountSelect(it) },
            onCustomAmountChange = { onCustomAmountChange(it) }
        )
    }

    private fun getEffectiveAmountZatoshi(): Long {
        return when (selectedAmount) {
            MessageAmount.CUSTOM -> customAmountZatoshi
            else -> selectedAmount.zatoshi
        }
    }

    private fun onRecipientChange(address: String) {
        recipientAddress = address
        selectedContact = contactBook.getContact(address)
        updateState()
    }

    private fun onMessageChange(newMessage: String) {
        message = newMessage
        updateState()
    }

    private fun onContactSelect(contact: Contact) {
        selectedContact = contact
        recipientAddress = contact.address
        updateState()
    }

    private fun onScanQrClick() {
        navigationRouter.forward(ScanArgs(flow = ScanFlow.ZCHAT, isScanZip321Enabled = false))
    }

    private fun showAddContactDialog() {
        showAddContactDialog = true
        contactName = ""
        updateState()
    }

    private fun dismissAddContactDialog() {
        showAddContactDialog = false
        contactName = ""
        updateState()
    }

    private fun onContactNameChange(name: String) {
        contactName = name
        updateState()
    }

    private fun onAddContact(address: String, name: String) {
        if (name.isNotBlank() && isValidZcashAddress(address)) {
            contactBook.addContact(
                Contact(
                    address = address,
                    name = name.trim(),
                    addedAt = Instant.now()
                )
            )
            showAddContactDialog = false
            contactName = ""
            selectedContact = contactBook.getContact(address)
            updateState()
        }
    }

    private fun showAmountDialog() {
        showAmountDialog = true
        updateState()
    }

    private fun dismissAmountDialog() {
        showAmountDialog = false
        updateState()
    }

    private fun onAmountSelect(amount: MessageAmount) {
        selectedAmount = amount
        if (amount != MessageAmount.CUSTOM) {
            showAmountDialog = false
        }
        updateState()
    }

    private fun onCustomAmountChange(amountStr: String) {
        // Parse as ZEC and convert to zatoshi
        val zec = amountStr.toDoubleOrNull() ?: 0.0
        customAmountZatoshi = (zec * 100_000_000).toLong().coerceAtLeast(0)
        updateState()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onSendClick() {
        if (!isValidZcashAddress(recipientAddress) || message.isBlank()) return

        // Check if user has acknowledged that messages cost ZEC
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            _showCostDisclaimer.value = true
            return
        }

        doSendMessage()
    }

    /**
     * Called when user acknowledges the message cost disclaimer.
     */
    fun acknowledgeCostDisclaimer() {
        zchatPreferences.setAcknowledgedMessageCost()
        _showCostDisclaimer.value = false
        doSendMessage()
    }

    /**
     * Called when user dismisses the disclaimer without acknowledging.
     */
    fun dismissCostDisclaimer() {
        _showCostDisclaimer.value = false
    }

    /**
     * Internal function to actually send the message.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun doSendMessage() {
        viewModelScope.launch {
            try {
                // Update state to show sending
                val currentState = _state.value as? ZchatComposeState.Ready ?: return@launch
                _state.value = currentState.copy(isSending = true)

                val senderAddress = userAddress ?: throw IllegalStateException("User address not available")

                // Check if we've ever sent an outgoing message to this recipient
                // If yes, they already have our address, so we can use hash format
                // If no, this is a first message and we need to include our address (INIT format)
                val hasEverSentToThisPeer = hasOutgoingMessageTo(recipientAddress)
                val isFirstMessage = !hasEverSentToThisPeer

                val amountPerOutput = getEffectiveAmountZatoshi()

                // Create the proposal using chunked message use case with direct submit
                createChunkedMessageProposal(
                    destinationAddress = recipientAddress,
                    senderAddress = senderAddress,
                    message = message,
                    isFirstMessage = isFirstMessage,
                    amountPerOutput = Zatoshi(amountPerOutput),
                    directSubmit = true
                )

                // Show success state (navigation to progress happens in use case)

            } catch (e: Exception) {
                _state.value = ZchatComposeState.Error(e.message ?: "Failed to send message")
            }
        }
    }

    /**
     * Check if we have ever sent an outgoing message to this peer address.
     * This determines if they already have our address from a previous INIT message.
     * Uses the cached sentToAddresses for consistency with updateState().
     */
    private fun hasOutgoingMessageTo(peerAddress: String): Boolean {
        return sentToAddresses.value.contains(peerAddress)
    }

    fun setScannedAddress(address: String) {
        recipientAddress = address
        selectedContact = contactBook.getContact(address)
        updateState()
    }

    private fun isValidZcashAddress(address: String): Boolean {
        // Unified address: starts with "u1" and length > 100
        // Sapling address: starts with "zs" and length > 70
        return when {
            address.startsWith("u1") && address.length > 100 -> true
            address.startsWith("zs") && address.length > 70 -> true
            else -> false
        }
    }

    private fun formatZatoshi(zatoshi: Long): String {
        val zec = zatoshi / 100_000_000.0
        return if (zatoshi == 0L) {
            "0 ZEC"
        } else {
            String.format("%.5f ZEC", zec)
        }
    }
}
