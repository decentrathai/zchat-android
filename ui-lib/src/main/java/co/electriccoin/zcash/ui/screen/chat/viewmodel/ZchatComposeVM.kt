package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import kotlinx.coroutines.Job
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class ZchatComposeVM(
    private val contactBook: ContactBook,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val navigationRouter: NavigationRouter,
    private val prefillZchat: PrefillZchatUseCase,
    private val transactionRepository: TransactionRepository,
    private val zchatPreferences: ZchatPreferences,
    private val validateAddress: ValidateAddressUseCase
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

    // Conversation transport mode the user picks before the first message is sent.
    // Defaults to the most-private option (VAULT). Persisted per-peer in doSendMessage
    // BEFORE the conversation is created, so getOrCreateConversationId sees the chosen mode.
    private var selectedMode: ConversationMode = NEW_CHAT_DEFAULT_MODE

    // Track addresses we've ever sent outgoing messages to
    // This is used to determine if we need INIT format (include full address) or hash format
    private val sentToAddresses = MutableStateFlow<Set<String>>(emptySet())

    // #244: the synchronous isValidZcashAddress() is prefix/length only — a corrupted-but-length-valid
    // address passed it, so Send enabled and only the SDK rejected it at proposal time. We additionally
    // run the authoritative SDK validateAddress() (real Bech32m checksum) async and DISABLE Send only
    // when it returns Invalid. FAIL-OPEN: any validation error/timeout leaves the address treated as
    // valid, so a transient validation hiccup can never block a legitimate send.
    private var addressChecksumInvalid = false
    private var addressValidationJob: Job? = null

    companion object {
        // Estimated transaction fee for display (approximate, shown as "Fee: ~X ZEC")
        private const val ESTIMATED_FEE_ZATOSHI = 10000L

        // Minimal platform fee (same as MINIMAL tier: 1000 zatoshi = 0.00001 ZEC)
        private const val PLATFORM_FEE_MIN_ZATOSHI = 1000L

        // Conservative fee buffer for Send All calculation.
        // Must be >= actual network fee so the transaction doesn't fail with "insufficient funds".
        // Real shielded tx fees are ~10,000-20,000 zatoshi; we use 30,000 for safety margin.
        private const val SEND_ALL_FEE_BUFFER_ZATOSHI = 30000L

        // Fallback default for a NEW chat when the peer's NOSTR key is NOT yet known (the picker stays
        // visible so Vault/Open remain one tap away). Tunnel is the right starting point in that case:
        // E2E, instant, supports calls, and after the one-time ZBOOT handshake messages are free over
        // NOSTR — vs Vault which costs an on-chain tx per message. When the peer's NOSTR key IS known,
        // syncModeForRecipient prefers OPEN (free from message #1, no handshake). NOTE: this is the
        // COMPOSE picker default only; ConversationMode.DEFAULT (VAULT) is deliberately left unchanged
        // because it also governs how inbound messages are interpreted.
        private val NEW_CHAT_DEFAULT_MODE = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL
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
                    syncModeForRecipient()
                }
                updateState()
            } catch (e: Exception) {
                // Never surface a raw framework exception on the compose screen — map to ZCHAT copy.
                _state.value = errorState(e.toZchatUserMessage("Couldn't open New Chat. Please try again."))
            }
        }
    }

    /**
     * Build a recoverable Error state. The terminal Error screen otherwise stranded the user with
     * no way back and no retry — here we expose the existing back navigation plus a Retry that
     * re-runs [loadInitialState].
     */
    private fun errorState(message: String) =
        ZchatComposeState.Error(
            message = message,
            onBack = { navigationRouter.back() },
            onRetry = {
                _state.value = ZchatComposeState.Loading
                loadInitialState()
            }
        )

    private fun observeScannedAddress() {
        viewModelScope.launch {
            prefillZchat.scannedAddress.collectLatest { address ->
                if (address != null) {
                    recipientAddress = address
                    selectedContact = contactBook.getContact(address)
                    syncModeForRecipient()
                    prefillZchat.clear()
                    updateState()
                }
            }
        }
    }

    private fun updateState() {
        val contacts = contactBook.getAllContacts()
        // Treat the user's OWN address as not-sendable so the send button is disabled — a self-send
        // would spawn a sender==recipient conversation and corrupt threading. Non-wedging: this just
        // gates the button (no terminal Error state). userAddress may be null before it loads.
        val isValid = isValidZcashAddress(recipientAddress) &&
            !addressChecksumInvalid && // #244: SDK validateAddress said the address is malformed
            (userAddress == null || recipientAddress != userAddress)
        // Use transaction history to determine if this is first message
        // If we've ever sent to this address, they have our address (from INIT) so we can use hash format
        val isFirstMessage = !sentToAddresses.value.contains(recipientAddress)
        // Count with the v4 sizer (330/462) that matches the ACTUAL v4 builders the proposal packs —
        // the v3 calculateChunkCount (340/470) undercounts, understating cost and Send-All chunking.
        val chunkCount = if (message.isNotEmpty()) {
            ZMSGProtocol.calculateV4ChunkCount(message, isFirstMessage)
        } else 1

        // Calculate amounts. The platform fee is ALWAYS minimal (matches the displayed "Platform fee"
        // and what CreateChunkedMessageProposalUseCase actually charges) — never the full send amount,
        // which used to make this cost display show ~2x the real debit.
        val isSendAll = selectedAmount == MessageAmount.SEND_ALL
        val amountPerOutput = getEffectiveAmountZatoshi(chunkCount)
        val platformFee = PLATFORM_FEE_MIN_ZATOSHI
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
            // Always surface the spendable balance — including "0 ZEC" — so a user with an empty
            // wallet sees WHY sends / Send All fail instead of a blank line. formatZatoshi already
            // renders 0L as "0 ZEC".
            availableBalanceDisplay = formatZatoshi(spendableBalanceZatoshi),
            spendableBalanceZatoshi = spendableBalanceZatoshi,
            customAmountText = customAmountText,
            sendAllAmountDisplay = if (isSendAll && sendAllRecipientAmount > 0)
                "Recipient gets: ${formatZatoshi(sendAllRecipientAmount)}" +
                "\nPlatform fee: ${formatZatoshi(PLATFORM_FEE_MIN_ZATOSHI)}" +
                "\nNetwork fee: ~${formatZatoshi(SEND_ALL_FEE_BUFFER_ZATOSHI)}"
            else "",
            selectedMode = selectedMode,
            // OPEN (free NOSTR from message #1) is only offerable once we hold the peer's NOSTR key.
            openAvailable = isPeerNostrKeyKnown(),
            // When the free-OPEN path will be taken, the send costs nothing on-chain — the cost/amount
            // UI must say "Free" instead of a ZEC amount so the user isn't misled.
            isFreeOpenSend = isFreeOpenSend(),
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
            onCustomAmountChange = { onCustomAmountChange(it) },
            onModeSelect = { onModeSelect(it) }
        )
    }

    private fun getEffectiveAmountZatoshi(chunkCount: Int = 1): Long {
        val raw = when (selectedAmount) {
            MessageAmount.CUSTOM -> customAmountZatoshi
            MessageAmount.SEND_ALL -> calculateSendAllAmountPerOutput(chunkCount)
            else -> selectedAmount.zatoshi
        }
        // #bug-zero-send — an on-chain per-output amount of 0 is REJECTED by org.zecdev.zip321
        // (AmountTooSmall) and used to leak a raw exception to the UI. Coerce every NON-free-OPEN
        // on-chain send up to the dust minimum AT THE SOURCE so the displayed amount and the actual
        // charge agree (this same value feeds both updateState's cost display and doSendMessage's
        // proposal). Truly-free messaging routes over NOSTR (free-OPEN), never a 0-value tx.
        // EXCLUSIONS (must NOT be bumped):
        //  - SEND_ALL: a tiny-balance "Send All" must fail cleanly as InsufficientFunds, not be
        //    padded above the wallet's spendable balance.
        //  - free-OPEN: never touches the chain, so its amount is irrelevant (and left as-is).
        return if (selectedAmount == MessageAmount.SEND_ALL || isFreeOpenSend()) {
            raw
        } else {
            raw.coerceAtLeast(CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT.value)
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
        // Trim whitespace/newlines first — Zcash addresses never contain spaces, and a pasted
        // address commonly carries a trailing newline or stray space that would otherwise fail
        // validation and leave the user staring at a valid-looking address that won't send.
        val trimmed = address.trim()
        // Allow PASTING a ZCHAT contact code (zchat:c1?z=…&n=…&r=…) — the text form of the contact QR —
        // straight into the recipient field. Mirrors the camera scan: extract the Zcash address and,
        // when the code carries it, store the peer's NOSTR key so OPEN (free from message #1) becomes
        // available. Then continue with the bare address so the rest of the screen is unchanged.
        if (trimmed.startsWith("${co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode.SCHEME}:")) {
            val code = co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode.parse(trimmed)
            if (code != null && isValidZcashAddress(code.zcashAddress)) {
                if (code.supportsOpen) storePeerNostrFromCode(code)
                recipientAddress = code.zcashAddress
                selectedContact = contactBook.getContact(code.zcashAddress)
                syncModeForRecipient()
                updateState()
                return
            }
        }
        recipientAddress = trimmed
        selectedContact = contactBook.getContact(trimmed)
        syncModeForRecipient()
        updateState()
    }

    /**
     * Persist the peer's NOSTR identity from a pasted/scanned contact code, with the same key-change
     * guard the scan path and the other bind sites use: never silently overwrite a DIFFERENT existing
     * key (MITM/impersonation) — flag the key-changed banner + clear verification instead.
     */
    private fun storePeerNostrFromCode(code: co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode) {
        val pub = code.nostrPubkeyHex ?: return
        val existing = zchatPreferences.getPeerNostrPubkey(code.zcashAddress)
        if (existing != null && !existing.equals(pub, ignoreCase = true)) {
            zchatPreferences.setE2EKeyChanged(code.zcashAddress, true)
            zchatPreferences.setE2EVerified(code.zcashAddress, false)
            return
        }
        zchatPreferences.setPeerNostrPubkey(code.zcashAddress, pub)
        code.relayUrl?.let { zchatPreferences.setPeerNostrRelay(code.zcashAddress, it) }
    }

    /**
     * Keep the in-flight [selectedMode] aligned with whatever is already persisted for the
     * current recipient. If the peer has a stored mode (e.g. set earlier from the chat
     * overflow picker), show that; otherwise fall back to the default (VAULT). This guarantees
     * the compose picker and the post-creation overflow picker read the same single value.
     */
    private fun syncModeForRecipient() {
        // Respect a mode the user already chose for this peer; getConversationModeOrNull returns null
        // ONLY when truly unset, so an existing Vault/Tunnel/Open choice is always preserved.
        val stored = if (isValidZcashAddress(recipientAddress)) {
            zchatPreferences.getConversationModeOrNull(recipientAddress)
        } else {
            null
        }
        // Smart default for a NEW chat (no explicit mode yet): when we ALREADY hold the peer's NOSTR
        // key (scanned / pasted from their ZCHAT contact code), OPEN is the brilliant cold-start — a
        // free, instant, end-to-end-encrypted NOSTR DM from message #1 with NO on-chain handshake.
        // Without the key OPEN has nowhere to route, so fall back to Tunnel (one on-chain bootstrap,
        // then free NOSTR). The picker stays visible so Vault/Tunnel remain one tap away.
        val newChatDefault = if (isPeerNostrKeyKnown()) ConversationMode.OPEN else NEW_CHAT_DEFAULT_MODE
        val resolved = stored ?: newChatDefault
        // OPEN delivers a free NIP-17 gift-wrapped NOSTR DM from message #1 — but ONLY when we already
        // hold the peer's NOSTR key (scanned from their ZCHAT contact QR; persisted by ScanZashiAddressVM).
        // Without that key OPEN has nowhere to route, so coerce it to TUNNEL (one on-chain bootstrap, then
        // free NOSTR). When the key IS known, OPEN is honored so the very first message goes out free.
        selectedMode = if (resolved == ConversationMode.OPEN && !isPeerNostrKeyKnown()) {
            ConversationMode.TUNNEL
        } else {
            resolved
        }
        revalidateAddress()
    }

    /**
     * #244: authoritative async address validation. Cancels any prior in-flight check, then (only when
     * the fast prefix/length check passes) asks the SDK to validate. Sets [addressChecksumInvalid] true
     * ONLY when the SDK explicitly returns Invalid; on any exception/timeout it leaves it false
     * (fail-open). Stale results (recipient changed mid-flight) are discarded.
     */
    private fun revalidateAddress() {
        val addr = recipientAddress
        addressValidationJob?.cancel()
        if (!isValidZcashAddress(addr)) {
            addressChecksumInvalid = false
            return
        }
        addressValidationJob = viewModelScope.launch {
            val invalid = runCatching { validateAddress(addr) is AddressType.Invalid }.getOrDefault(false)
            if (recipientAddress == addr && addressChecksumInvalid != invalid) {
                addressChecksumInvalid = invalid
                updateState()
            }
        }
    }

    /** True iff we hold the peer's NOSTR pubkey for the current recipient — the precondition for a
     *  free OPEN send from message #1. Set when the user scans the peer's ZCHAT contact QR. */
    private fun isPeerNostrKeyKnown(): Boolean =
        isValidZcashAddress(recipientAddress) && zchatPreferences.getPeerNostrPubkey(recipientAddress) != null

    /** A free OPEN send is taken when OPEN is selected AND we hold the peer's NOSTR key. Such a send
     *  travels over NOSTR as a v4 INIT and NEVER touches the chain — so it must skip the ZEC cost
     *  disclaimer and the on-chain proposal path. */
    private fun isFreeOpenSend(): Boolean =
        selectedMode == ConversationMode.OPEN && isPeerNostrKeyKnown()

    private fun onModeSelect(mode: ConversationMode) {
        selectedMode = mode
        // Persist immediately so the choice survives process death / recomposition and is
        // already in place if the conversation is created. doSendMessage re-asserts it too.
        if (isValidZcashAddress(recipientAddress)) {
            zchatPreferences.setConversationMode(recipientAddress, mode)
            // Pin this as the user's explicit choice so a peer's ZMODE can't silently auto-adopt over
            // it (handleModeControl only auto-adopts when !isConversationModeExplicit). Only here —
            // NOT at doSendMessage's default fallback, which must leave never-chosen chats adoptable.
            zchatPreferences.setConversationModeExplicit(recipientAddress)
        }
        updateState()
    }

    private fun onMessageChange(newMessage: String) {
        message = newMessage
        updateState()
    }

    private fun onContactSelect(contact: Contact) {
        selectedContact = contact
        recipientAddress = contact.address
        syncModeForRecipient()
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
        // Parse as ZEC and convert to zatoshi via BigDecimal (DECIMAL128), NOT Double * 1e8 —
        // the latter loses precision and overflows toLong() to Long.MAX_VALUE for huge inputs,
        // displaying an absurd amount. valueOf() takes the Double's clean decimal string; an
        // invalid/negative/out-of-range amount yields 0.
        val zec = amountStr.toDoubleOrNull() ?: 0.0
        customAmountZatoshi = runCatching {
            java.math.BigDecimal.valueOf(zec).convertZecToZatoshi().value
        }.getOrDefault(0L).coerceAtLeast(0L)
        updateState()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onSendClick() {
        if (!isValidZcashAddress(recipientAddress) || message.isBlank()) return

        // A free OPEN send (peer NOSTR key known) costs no ZEC, so it must NOT trip the ZEC cost
        // disclaimer — that disclaimer is only about on-chain spend.
        if (!isFreeOpenSend() && !zchatPreferences.hasAcknowledgedMessageCost()) {
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

                // Re-entry guard (parity with GroupViewModel's SF-3 in-flight guard): a double-tap on
                // Send can dispatch two clicks before the isSending=true recomposition disables the
                // button. viewModelScope is Main.immediate, so the first tap has already set
                // isSending=true synchronously by the time the second runs — bail here so the second tap
                // can't build + directSubmit a SECOND on-chain proposal (double charge).
                if (currentState.isSending) return@launch

                // Defense-in-depth: never send to your OWN address. The send button is already
                // disabled for this case (isValidAddress=false in updateState), so just no-op —
                // we must NOT set a terminal Error state here, which would wedge the compose screen
                // with no input field and no way back.
                if (userAddress != null && recipientAddress == userAddress) {
                    _state.value = currentState.copy(isSending = false)
                    return@launch
                }
                _state.value = currentState.copy(isSending = true)

                val senderAddress = userAddress ?: throw IllegalStateException("User address not available")

                // FREE OPEN path (peer NOSTR key known): send the FIRST message as a v4 INIT over NOSTR
                // instead of an on-chain memo. The INIT carries our address so the recipient can place us
                // in their Message Requests inbox and reply. This NEVER spends ZEC. We persist the row +
                // publish synchronously (awaited) BEFORE navigating so the publish isn't cancelled when
                // this ViewModel is cleared on navigation.
                if (isFreeOpenSend()) {
                    sendFirstOpenMessageOverNostr(senderAddress, recipientAddress, message)
                    navigationRouter.replace(ChatDetail(recipientAddress))
                    return@launch
                }

                // The work below — SharedPreferences reads/writes and proposal creation/submit — was
                // running on the main thread (viewModelScope.launch defaults to Main.immediate),
                // tripping StrictMode DiskRead/DiskWrite violations (observed ~20x per send on device)
                // and janking the send. Move it off the main thread; the StateFlow updates above are
                // thread-safe to set from any thread, and the navigation below stays on Main.
                withContext(Dispatchers.IO) {
                    // Persist the chosen transport mode for this peer BEFORE the conversation is
                    // created, so the conversation comes into existence in the selected mode and the
                    // message router (and the post-creation overflow picker) read the same value.
                    // Defaults to VAULT when the user never touched the selector.
                    zchatPreferences.setConversationMode(recipientAddress, selectedMode)

                    // ZMSG v4 Protocol: Use conversation IDs for reliable threading.
                    // getOrCreateConversationId is atomic at the SharedPreferences level,
                    // safe across all VMs/services without needing a per-VM mutex.
                    // isNew tells us if this is the first message (INIT format needed).
                    val (convId, isNew) = zchatPreferences.getOrCreateConversationId(recipientAddress)

                    // Count chunks with the SAME (isNew) the proposal uses AND the v4 sizer that matches
                    // the actual v4 INIT/REPLY builders (330/462). The old code used tx-history isFirst +
                    // the v3 sizer (340/470), which could undercount — making Send All divide the balance
                    // by too few chunks and then exceed the spendable balance at build time.
                    val sendChunkCount = ZMSGProtocol.calculateV4ChunkCount(message, isNew)
                    val amountPerOutput = getEffectiveAmountZatoshi(sendChunkCount)
                    // Platform fee is always minimal — never the full send amount (the use-case clamps
                    // it too, but keep the caller honest so cost display and charge agree).
                    val platformFee = Zatoshi(PLATFORM_FEE_MIN_ZATOSHI)

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
                }

                // Navigate to the chat conversation that was just started (back on Main).
                navigationRouter.replace(ChatDetail(recipientAddress))

            } catch (e: Exception) {
                // The 0-ZEC AmountTooSmall wrapper (and any other SDK failure) is mapped to ZCHAT
                // copy here — the raw "…TransactionProposalNotCreatedException: AmountTooSmall(value=0)"
                // must never reach the full-screen Error state.
                _state.value = errorState(e.toZchatUserMessage("Couldn't send your message. Please try again."))
            }
        }
    }

    /**
     * Send the FIRST message of an OPEN conversation over NOSTR as a ZMSG v4 INIT.
     *
     * Why a v4 INIT (not raw text): the recipient has never met us, so a gift-wrap from our NOSTR pubkey
     * is "unknown sender" on their side (NostrChatBridge.dispatch drops unknown-pubkey DMs). The INIT
     * carries our Zcash ADDRESS, which is exactly what the recipient needs to (a) recognise who we are and
     * (b) reply over NOSTR. Their dispatch routes an unknown-pubkey INIT into a Message Requests inbox; on
     * accept they bind our pubkey→address and our subsequent (raw) OPEN messages flow normally.
     *
     * Money-safety: this path is GUARANTEED free — it only ever publishes to a relay. If the relay
     * publish fails (service not started / no acks) the row is marked FAILED and the user can retry; we
     * NEVER fall back to an on-chain charge for an OPEN message.
     */
    private suspend fun sendFirstOpenMessageOverNostr(
        senderAddress: String,
        recipient: String,
        text: String
    ) {
        val peerPub = zchatPreferences.getPeerNostrPubkey(recipient) ?: return
        // Lock the conversation to OPEN so the chat continues free over NOSTR after this first message.
        zchatPreferences.setConversationMode(recipient, ConversationMode.OPEN)
        // #250-r4: anchor the rotation index this peer can verify. We carry our Zcash address in the v4
        // INIT and they reply to our CURRENT NOSTR pubkey, so on first contact they hold our current
        // index. Seed knownIdx only when unset (-1 default) so future inbound rotation never regresses,
        // and so a later announce is never signed with an index the peer never held (rotation recovery).
        if (zchatPreferences.getPeerKnownOurRotationIndex(recipient) < 0) {
            zchatPreferences.setPeerKnownOurRotationIndex(recipient, zchatPreferences.getNostrRotationIndex())
        }
        val (convId, _) = zchatPreferences.getOrCreateConversationId(recipient)
        val wire = ZMSGProtocol.createV4InitMessage(convId, senderAddress, text)

        val localId = "nostr-out-${System.nanoTime()}"
        val nowMs = Instant.now().toEpochMilli()
        val sendingName = co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENDING.name
        // Optimistic persisted row so the message is visible the instant ChatDetail opens. txId stays
        // null (no ledger entry), mirroring the in-chat NOSTR send persistence.
        zchatPreferences.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = localId,
                text = text,
                timestampMillis = nowMs,
                peerAddress = recipient,
                isOutgoing = true,
                isPending = true,
                status = sendingName,
            )
        )
        val result = withContext(Dispatchers.IO) {
            runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(wire, peerPub) }.getOrNull()
        }
        val acks = result?.acks ?: 0
        val finalStatus = if (acks > 0) {
            co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENT
        } else {
            co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.FAILED
        }
        // Re-key to the STABLE shared rumor id so a reaction/reply correlates across both devices
        // (matches publishNostrAndRenderLocal). Falls back to the local id for legacy/no-id publishes.
        val finalId = result?.messageId?.takeIf { it.isNotEmpty() }?.let { "nmsg-$it" } ?: localId
        if (finalId != localId) zchatPreferences.removePendingMessage(localId)
        zchatPreferences.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = finalId,
                text = text,
                timestampMillis = nowMs,
                peerAddress = recipient,
                isOutgoing = true,
                isPending = false,
                status = finalStatus.name,
            )
        )
    }

    fun setScannedAddress(address: String) {
        recipientAddress = address
        selectedContact = contactBook.getContact(address)
        syncModeForRecipient()
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
