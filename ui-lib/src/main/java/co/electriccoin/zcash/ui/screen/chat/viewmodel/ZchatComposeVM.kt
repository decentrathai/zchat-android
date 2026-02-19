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
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
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
    private var customAmountText: String = ""
    private var showAmountDialog = false
    private var spendableBalanceZatoshi: Long = 0L

    // Track addresses we've ever sent outgoing messages to
    // This is used to determine if we need INIT format (include full address) or hash format
    private val sentToAddresses = MutableStateFlow<Set<String>>(emptySet())

    companion object {
        // Estimated transaction fee for display (approximate, shown as "Fee: ~X ZEC")
        private const val ESTIMATED_FEE_ZATOSHI = 10000L

        // Minimal platform fee (same as MINIMAL tier: 1000 zatoshi = 0.00001 ZEC)
        private const val PLATFORM_FEE_MIN_ZATOSHI = 1000L

        // Conservative fee buffer for Send All calculation.
        // Must be >= actual network fee so the transaction doesn't fail with "insufficient funds".
        // Real shielded tx fees are ~10,000-20,000 zatoshi; we use 30,000 for safety margin.
        private const val SEND_ALL_FEE_BUFFER_ZATOSHI = 30000L
    }

    init {
        loadInitialState()
        observeScannedAddress()
        loadSentToAddresses()
        observeBalance()
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

    private fun observeBalance() {
        viewModelScope.launch {
            getSelectedWalletAccount.observe().collectLatest { account ->
                spendableBalanceZatoshi = account?.spendableShieldedBalance?.value ?: 0L
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

        // Calculate amounts (Send All uses separate platform fee)
        val isSendAll = selectedAmount == MessageAmount.SEND_ALL
        val amountPerOutput = getEffectiveAmountZatoshi(chunkCount)
        val platformFee = if (isSendAll) PLATFORM_FEE_MIN_ZATOSHI else amountPerOutput
        val totalAmount = amountPerOutput * chunkCount + platformFee
        val isZero = amountPerOutput == 0L

        // For Send All: show what recipient will receive
        val sendAllRecipientAmount = if (isSendAll) amountPerOutput * chunkCount else 0L

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
            availableBalanceDisplay = if (spendableBalanceZatoshi > 0)
                formatZatoshi(spendableBalanceZatoshi) else "",
            customAmountText = customAmountText,
            sendAllAmountDisplay = if (isSendAll && sendAllRecipientAmount > 0)
                "Recipient gets: ${formatZatoshi(sendAllRecipientAmount)}" +
                "\nPlatform fee: ${formatZatoshi(PLATFORM_FEE_MIN_ZATOSHI)}" +
                "\nNetwork fee: ~${formatZatoshi(SEND_ALL_FEE_BUFFER_ZATOSHI)}"
            else "",
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

    private fun getEffectiveAmountZatoshi(chunkCount: Int = 1): Long {
        return when (selectedAmount) {
            MessageAmount.CUSTOM -> customAmountZatoshi
            MessageAmount.SEND_ALL -> calculateSendAllAmountPerOutput(chunkCount)
            else -> selectedAmount.zatoshi
        }
    }

    /**
     * Calculate the amount per message output for "Send All".
     * Platform fee is always minimal (PLATFORM_FEE_MIN_ZATOSHI), so the recipient gets:
     * amountPerOutput = (spendableBalance - platformFee - networkFeeBuffer) / chunkCount
     */
    private fun calculateSendAllAmountPerOutput(chunkCount: Int): Long {
        val available = spendableBalanceZatoshi - PLATFORM_FEE_MIN_ZATOSHI - SEND_ALL_FEE_BUFFER_ZATOSHI
        if (available <= 0 || chunkCount <= 0) return 0L
        return (available / chunkCount).coerceAtLeast(0L)
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
        if (amount != MessageAmount.CUSTOM && amount != MessageAmount.SEND_ALL) {
            showAmountDialog = false
        }
        updateState()
    }

    private fun onCustomAmountChange(amountStr: String) {
        // Store raw text to prevent text field glitching from round-trip conversion
        customAmountText = amountStr
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

                // Calculate chunk count for proper Send All amount calculation
                val isFirstForSend = !sentToAddresses.value.contains(recipientAddress)
                val sendChunkCount = ZMSGProtocol.calculateChunkCount(message, isFirstForSend)
                val amountPerOutput = getEffectiveAmountZatoshi(sendChunkCount)
                val isSendAll = selectedAmount == MessageAmount.SEND_ALL
                val platformFee = if (isSendAll) Zatoshi(PLATFORM_FEE_MIN_ZATOSHI) else Zatoshi(amountPerOutput)

                // ZMSG v4 Protocol: Use conversation IDs for reliable threading.
                // getOrCreateConversationId is atomic at the SharedPreferences level,
                // safe across all VMs/services without needing a per-VM mutex.
                // isNew tells us if this is the first message (INIT format needed).
                val (convId, isNew) = zchatPreferences.getOrCreateConversationId(recipientAddress)

                // Create the proposal using chunked message use case with direct submit
                createChunkedMessageProposal(
                    destinationAddress = recipientAddress,
                    senderAddress = senderAddress,
                    message = message,
                    isFirstMessage = isNew,
                    amountPerOutput = Zatoshi(amountPerOutput),
                    platformFeeAmount = platformFee,
                    directSubmit = true,
                    skipNavigation = true,
                    conversationId = convId
                )

                // Navigate to the chat conversation that was just started
                navigationRouter.replace(ChatDetail(recipientAddress))

            } catch (e: Exception) {
                _state.value = ZchatComposeState.Error(e.message ?: "Failed to send message")
            }
        }
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
