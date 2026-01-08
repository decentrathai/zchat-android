package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockInfo
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockType
import co.electriccoin.zcash.ui.screen.chat.model.UnknownReason
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Instant

class ChatViewModel(
    private val transactionRepository: TransactionRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val accountDataSource: AccountDataSource,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val addressCache: AddressCache,
    private val zchatPreferences: ZchatPreferences,
    private val synchronizerProvider: SynchronizerProvider,
    private val exchangeRateRepository: ExchangeRateRepository
) : ViewModel() {

    private val _chatListState = MutableStateFlow<ChatListState>(ChatListState.Loading)
    val chatListState: StateFlow<ChatListState> = _chatListState.asStateFlow()

    private val _currentUserAddress = MutableStateFlow<String?>(null)
    val currentUserAddress: StateFlow<String?> = _currentUserAddress.asStateFlow()

    private val _sendMessageState = MutableStateFlow<SendMessageState>(SendMessageState.Idle)
    val sendMessageState: StateFlow<SendMessageState> = _sendMessageState.asStateFlow()

    // Disclaimer dialog state
    private val _showCostDisclaimer = MutableStateFlow(false)
    val showCostDisclaimer: StateFlow<Boolean> = _showCostDisclaimer.asStateFlow()

    // Pending message (stored when disclaimer needs to be shown)
    private var pendingMessage: Pair<String, String>? = null // (peerAddress, message)

    // Sync status
    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _secondsUntilNextSync = MutableStateFlow(AUTO_REFRESH_INTERVAL_SECONDS)
    private val _blockHeight = MutableStateFlow<Long?>(null)
    private val _zecPriceUsd = MutableStateFlow<Double?>(null)

    // Track which addresses we've already sent INIT messages to
    private val sentInitTo = mutableSetOf<String>()

    // Track hidden messages (message IDs the user has chosen to hide)
    // Initialized from preferences and updated reactively
    private val hiddenMessages = MutableStateFlow<Set<String>>(emptySet())

    // Pending messages that are being sent (not yet confirmed on blockchain)
    // These are shown immediately in the chat for smooth UX
    private val pendingMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    // User status (own status text)
    private val _userStatus = MutableStateFlow(UserStatus.DEFAULT)
    val userStatus: StateFlow<UserStatus> = _userStatus.asStateFlow()

    // Current block height exposed for time-lock UI
    val currentBlockHeight: StateFlow<Long?> = _blockHeight.asStateFlow()

    // Peer statuses (cached from incoming messages)
    private val peerStatuses = MutableStateFlow<Map<String, UserStatus>>(emptyMap())

    // Time-lock unlocks: Map of locked message txId -> unlock txId
    // This tracks which locked messages have been unlocked (by payment or answer)
    private val unlockedMessages = MutableStateFlow<Map<String, String>>(emptyMap())

    // Remote kill callback - set by the UI to handle app destruction
    private var onRemoteKillDetected: (() -> Unit)? = null

    // Track processed transaction IDs to avoid duplicate kill detection
    private val processedKillCheckTxIds = mutableSetOf<String>()

    // Auto-refresh timer job
    private var autoRefreshJob: Job? = null
    private var countdownJob: Job? = null

    init {
        // Load hidden messages from preferences
        hiddenMessages.value = zchatPreferences.getHiddenMessageIds()
        // Load user status from preferences
        loadUserStatus()
        // Load peer statuses from preferences
        loadPeerStatuses()
        loadConversations()
        startAutoRefreshTimer()
        observeBlockHeight()
        observeExchangeRate()
    }

    private fun loadUserStatus() {
        val statusText = zchatPreferences.getUserStatus()
        val statusTimestamp = zchatPreferences.getUserStatusTimestamp()
        _userStatus.value = if (statusText.isNotBlank()) {
            UserStatus(statusText, Instant.ofEpochMilli(statusTimestamp))
        } else {
            UserStatus.DEFAULT
        }
    }

    private fun loadPeerStatuses() {
        val storedStatuses = zchatPreferences.getAllPeerStatuses()
        peerStatuses.value = storedStatuses.mapValues { (_, status) ->
            UserStatus(status)
        }
    }

    private fun observeBlockHeight() {
        viewModelScope.launch {
            try {
                val synchronizer = synchronizerProvider.getSynchronizer()
                synchronizer.networkHeight.collect { height ->
                    _blockHeight.value = height?.value
                }
            } catch (_: Exception) {
                // Silently fail - block height is optional
            }
        }
    }

    private fun observeExchangeRate() {
        viewModelScope.launch {
            exchangeRateRepository.state.collect { state ->
                _zecPriceUsd.value = when (state) {
                    is ExchangeRateState.Data -> state.currencyConversion?.priceOfZec
                    else -> null
                }
            }
        }
    }

    private fun loadConversations() {
        viewModelScope.launch {
            try {
                // Get current user address - use default unified address for consistency after restore
                // IMPORTANT: Do NOT use fallback to account.unified.address - it may be a different diversified address
                val userAddress = getDefaultUnifiedAddress()
                _currentUserAddress.value = userAddress

                // Combine transactions, account balance, sync status, block height, exchange rate, hidden messages, and pending messages
                // First combine the sync status flows into a single data class to reduce combine parameters
                val syncStatusFlow = combine(
                    _lastSyncTime,
                    _isRefreshing,
                    _secondsUntilNextSync,
                    _blockHeight,
                    _zecPriceUsd
                ) { lastSync, isRefreshing, secondsUntilNext, blockHeight, zecPrice ->
                    SyncStatus(lastSync as? Instant, isRefreshing as Boolean, secondsUntilNext as Int, blockHeight as? Long, zecPrice as? Double)
                }

                combine(
                    transactionRepository.transactions.filterNotNull(),
                    accountDataSource.selectedAccount,
                    syncStatusFlow,
                    hiddenMessages,
                    pendingMessages
                ) { transactions, walletAccount, syncStatus, hiddenMsgIds, pending ->
                    @Suppress("UNCHECKED_CAST")
                    val txList = transactions as List<Transaction>

                    // Convert transactions to conversations and merge pending messages
                    val conversations = convertToConversations(txList, userAddress, hiddenMsgIds, pending)
                    // Add peer statuses to conversations
                    val conversationsWithStatus = conversations.map { conversation ->
                        val peerStatus = peerStatuses.value[conversation.peerAddress]
                        conversation.copy(peerStatus = peerStatus)
                    }
                    val balance = walletAccount?.totalBalance ?: Zatoshi(0)
                    ChatListState.Success(
                        conversations = conversationsWithStatus,
                        currentUserAddress = userAddress,
                        balance = balance,
                        lastSyncTime = syncStatus.lastSyncTime,
                        isRefreshing = syncStatus.isRefreshing,
                        secondsUntilNextSync = syncStatus.secondsUntilNextSync,
                        blockHeight = syncStatus.blockHeight,
                        zecPriceUsd = syncStatus.zecPriceUsd
                    )
                }.collectLatest { state ->
                    _chatListState.value = state
                }
            } catch (e: Exception) {
                _chatListState.value = ChatListState.Error(e.message ?: "Failed to load conversations")
            }
        }
    }

    private suspend fun convertToConversations(
        transactions: List<Transaction>,
        userAddress: String,
        hiddenMsgIds: Set<String> = emptySet(),
        pendingMsgs: List<ChatMessage> = emptyList()
    ): List<Conversation> {
        val messagesByPeer = mutableMapOf<String, MutableList<ChatMessage>>()

        // Track confirmed message IDs to remove from pending later
        val confirmedIds = mutableSetOf<String>()

        for (tx in transactions) {
            val messageId = tx.id.txIdString()

            // Skip hidden messages
            if (messageId in hiddenMsgIds) continue

            // Get memos for this transaction
            val memos = transactionRepository.getMemos(tx)
            if (memos.isEmpty()) continue

            val memoText = memos.joinToString("\n").trim()

            // Check for remote kill signal on incoming (received) transactions
            if (tx is co.electriccoin.zcash.ui.common.repository.ReceiveTransaction) {
                checkForRemoteKill(
                    amountZatoshi = tx.amount.value,
                    memo = memoText,
                    txId = messageId
                )
            }

            // Check for status messages (not regular chat messages)
            if (ZMSGProtocol.isStatus(memoText)) {
                // Parse and store peer status, but don't add as a chat message
                val parsedStatus = ZMSGProtocol.parseStatus(memoText, addressCache)
                if (parsedStatus != null && parsedStatus.senderAddress != null) {
                    val updatedStatuses = peerStatuses.value +
                        (parsedStatus.senderAddress to UserStatus(parsedStatus.statusText))
                    peerStatuses.value = updatedStatuses
                    zchatPreferences.setPeerStatus(parsedStatus.senderAddress, parsedStatus.statusText)
                }
                continue // Status messages don't appear in chat
            }

            // Skip reactions and read receipts from appearing in chat (they're metadata)
            if (ZMSGProtocol.isReaction(memoText) || ZMSGProtocol.isReadReceipt(memoText)) {
                continue
            }

            // Check for payment requests
            var paymentRequestInfo: PaymentRequestInfo? = null
            if (ZMSGProtocol.isPaymentRequest(memoText)) {
                val parsedRequest = ZMSGProtocol.parsePaymentRequest(memoText, addressCache)
                if (parsedRequest != null) {
                    paymentRequestInfo = PaymentRequestInfo(
                        amountZatoshi = parsedRequest.amountZatoshi,
                        reason = parsedRequest.reason,
                        isPaid = false, // TODO: Track paid status
                        paidTxId = null
                    )
                }
            }

            // Check for unlock messages (track which locked messages have been unlocked)
            if (ZMSGProtocol.isUnlock(memoText)) {
                val parsedUnlock = ZMSGProtocol.parseUnlock(memoText, addressCache)
                if (parsedUnlock != null) {
                    // Track the unlock
                    unlockedMessages.value = unlockedMessages.value +
                        (parsedUnlock.originalTxId to messageId)
                }
                continue // Unlock messages don't appear as chat messages
            }

            // Check for time-locked messages
            var timeLockInfo: TimeLockInfo? = null
            if (ZMSGProtocol.isTimeLock(memoText)) {
                val parsedTimeLock = ZMSGProtocol.parseTimeLock(memoText, addressCache)
                if (parsedTimeLock != null) {
                    val currentTime = System.currentTimeMillis() / 1000
                    val currentHeight = _blockHeight.value
                    val isPaymentUnlocked = unlockedMessages.value.containsKey(messageId)
                    val unlockTxId = unlockedMessages.value[messageId]

                    // Determine if unlocked
                    val isUnlocked = when (parsedTimeLock.lockType) {
                        co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.SCHEDULED ->
                            parsedTimeLock.unlockTimestamp != null && currentTime >= parsedTimeLock.unlockTimestamp
                        co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.BLOCK_HEIGHT ->
                            currentHeight != null && parsedTimeLock.unlockBlockHeight != null &&
                                currentHeight >= parsedTimeLock.unlockBlockHeight
                        co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.PAYMENT ->
                            isPaymentUnlocked
                        co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.CONDITIONAL ->
                            isPaymentUnlocked // Conditional unlocks tracked the same way
                    }

                    timeLockInfo = TimeLockInfo(
                        lockType = when (parsedTimeLock.lockType) {
                            co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.SCHEDULED -> TimeLockType.SCHEDULED
                            co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.BLOCK_HEIGHT -> TimeLockType.BLOCK_HEIGHT
                            co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.PAYMENT -> TimeLockType.PAYMENT
                            co.electriccoin.zcash.ui.screen.chat.model.TimeLockType.CONDITIONAL -> TimeLockType.CONDITIONAL
                        },
                        unlockTimestamp = parsedTimeLock.unlockTimestamp,
                        unlockBlockHeight = parsedTimeLock.unlockBlockHeight,
                        requiredPaymentZatoshi = parsedTimeLock.requiredPayment,
                        hint = parsedTimeLock.hint,
                        answerHash = parsedTimeLock.answerHash,
                        isUnlocked = isUnlocked,
                        unlockedBy = unlockTxId
                    )
                }
            }

            // Determine peer address and direction
            val isOutgoing = tx is co.electriccoin.zcash.ui.common.repository.SendTransaction

            val peerAddress: String
            val displayMessage: String
            val unknownReason: UnknownReason?

            if (isOutgoing) {
                // For outgoing, get recipient from transaction
                peerAddress = (tx as? co.electriccoin.zcash.ui.common.repository.SendTransaction)?.recipient?.address
                    ?: continue

                // Check if this is a chunked message (multiple memos in same tx)
                val hasChunkedMemos = memos.any { ZMSGProtocol.isChunkedMemo(it) }

                displayMessage = if (hasChunkedMemos) {
                    // Reassemble chunked message
                    val reassembled = ZMSGProtocol.reassembleChunks(memos, addressCache)
                    reassembled?.message ?: extractMessageContent(memos.joinToString("\n"))
                } else {
                    // Single memo - extract message content
                    extractMessageContent(memos.joinToString("\n").trim())
                }

                unknownReason = null
                // Track that we've communicated with this address
                sentInitTo.add(peerAddress)
            } else {
                // For incoming, check if chunked or single memo
                val hasChunkedMemos = memos.any { ZMSGProtocol.isChunkedMemo(it) }

                val parsed = if (hasChunkedMemos) {
                    // Reassemble chunked message
                    ZMSGProtocol.reassembleChunks(memos, addressCache)
                } else {
                    // Single memo - parse normally
                    val memoText = memos.joinToString("\n").trim()
                    if (memoText.isNotBlank()) {
                        ZMSGProtocol.parseMemo(memoText, addressCache)
                    } else {
                        null
                    }
                }

                if (parsed == null) continue

                // For reply messages (RPL format), try to find the correct conversation
                // by looking up the original transaction being replied to.
                // This handles cases where the sender's address might differ from
                // the address we originally communicated with (e.g., diversified addresses).
                val resolvedPeerAddress = if (parsed.replyToTxId != null) {
                    // Look for the original transaction in existing messages
                    val originalTxConversation = messagesByPeer.entries.find { (_, msgs) ->
                        msgs.any { it.txId?.txIdString() == parsed.replyToTxId }
                    }?.key
                    // Use the conversation's peer address if found, otherwise fall back to parsed address
                    originalTxConversation ?: parsed.senderAddress ?: "unknown"
                } else {
                    parsed.senderAddress ?: "unknown"
                }

                peerAddress = resolvedPeerAddress
                displayMessage = parsed.message
                unknownReason = parsed.reason

                // Cache the address mapping - link sender's current address to the conversation
                // This helps with future messages even if addresses change
                if (parsed.senderAddress != null && peerAddress != parsed.senderAddress) {
                    // The sender used a different address, cache the hash for this conversation
                    val senderHash = ZMSGProtocol.generateAddressHash(parsed.senderAddress)
                    addressCache.cacheAddress(senderHash, peerAddress)
                }

                // Note: We do NOT add to sentInitTo here because receiving a message
                // from someone doesn't mean they have OUR address. They only have our
                // address if WE have sent them a message with INIT format.
            }

            if (displayMessage.isBlank()) continue

            // For time-locked messages, use the parsed message content
            val finalMessage = if (timeLockInfo != null && ZMSGProtocol.isTimeLock(memoText)) {
                val parsedTimeLock = ZMSGProtocol.parseTimeLock(memoText, addressCache)
                parsedTimeLock?.message ?: displayMessage
            } else {
                displayMessage
            }

            // For payment requests, use the reason as display text
            val messageText = if (paymentRequestInfo != null) {
                paymentRequestInfo.reason.ifEmpty { "Payment request" }
            } else {
                finalMessage
            }

            val message = ChatMessage(
                id = messageId,
                txId = tx.id,
                text = messageText,
                timestamp = tx.timestamp ?: Instant.now(),
                isOutgoing = isOutgoing,
                peerAddress = peerAddress,
                isPending = tx is co.electriccoin.zcash.ui.common.repository.SendTransaction.Pending ||
                           tx is co.electriccoin.zcash.ui.common.repository.ReceiveTransaction.Pending,
                unknownReason = unknownReason,
                minedHeight = tx.overview.minedHeight?.value,
                timeLock = timeLockInfo,
                paymentRequest = paymentRequestInfo
            )

            messagesByPeer.getOrPut(peerAddress) { mutableListOf() }.add(message)
            confirmedIds.add(messageId)
        }

        // Track confirmed outgoing messages for better deduplication with pending messages
        // Key: peer address + message content hash, Value: confirmed message
        val confirmedOutgoingByContent = mutableMapOf<String, ChatMessage>()
        messagesByPeer.forEach { (peer, msgs) ->
            msgs.filter { it.isOutgoing }.forEach { msg ->
                val contentKey = "$peer|${msg.text.take(50)}"
                confirmedOutgoingByContent[contentKey] = msg
            }
        }

        // Add pending messages that haven't been confirmed yet
        // Use content-based matching for better deduplication
        val pendingToRemove = mutableListOf<String>()
        for (pendingMsg in pendingMsgs) {
            // Check if this pending message has a matching confirmed message
            val contentKey = "${pendingMsg.peerAddress}|${pendingMsg.text.take(50)}"
            val matchingConfirmed = confirmedOutgoingByContent[contentKey]

            if (matchingConfirmed != null) {
                // This pending message has been confirmed, mark for removal
                pendingToRemove.add(pendingMsg.id)
            } else if (pendingMsg.id !in confirmedIds) {
                // No matching confirmed message, show pending
                messagesByPeer.getOrPut(pendingMsg.peerAddress) { mutableListOf() }.add(pendingMsg)
            }
        }

        // Remove confirmed pending messages from the pending list
        if (pendingToRemove.isNotEmpty() || confirmedIds.isNotEmpty()) {
            val currentPending = pendingMessages.value
            val stillPending = currentPending.filter {
                it.id !in confirmedIds && it.id !in pendingToRemove
            }
            if (stillPending.size != currentPending.size) {
                pendingMessages.value = stillPending
            }
        }

        // Convert to Conversation objects (only include conversations with visible messages)
        return messagesByPeer
            .filter { (_, messages) -> messages.isNotEmpty() }
            .map { (peerAddress, messages) ->
                // Sort messages by block height (primary), then timestamp (secondary), then ID (for stability)
                // Block height ensures proper chronological order based on when tx was mined
                // Pending messages (null height) go last with high epoch timestamp
                val sortedMessages = messages.sortedWith(
                    compareBy<ChatMessage> { it.minedHeight ?: Long.MAX_VALUE } // Block height first
                        .thenBy { it.timestamp } // Then timestamp for same-block ordering
                        .thenBy { it.txId?.txIdString() ?: it.id } // ID for stability
                )
                Conversation(
                    peerAddress = peerAddress,
                    messages = sortedMessages,
                    lastMessage = sortedMessages.lastOrNull()
                )
            }.sortedByDescending { it.lastMessage?.timestamp }
    }

    /**
     * Extract just the message content from a ZMSG formatted memo.
     * For chunked messages, this extracts from a single chunk (use reassembleChunks for full message).
     */
    private fun extractMessageContent(memo: String): String {
        return when {
            // Chunked INIT: ZMSG|v3c|1/N|INIT|<address>|<message>
            memo.startsWith("ZMSG|v3c|") && memo.contains("|INIT|") -> {
                val initIndex = memo.indexOf("|INIT|")
                val afterInit = memo.substring(initIndex + 6)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // Chunked CONT: ZMSG|v3c|M/N|CONT|<message>
            memo.startsWith("ZMSG|v3c|") && memo.contains("|CONT|") -> {
                val contIndex = memo.indexOf("|CONT|")
                memo.substring(contIndex + 6)
            }
            // Chunked reply first: ZMSG|v3c|1/N|<hash>|<message>
            memo.startsWith("ZMSG|v3c|") -> {
                val parts = memo.split("|", limit = 5)
                if (parts.size >= 5) parts[4] else memo
            }
            // RPL with INIT: ZMSG|v3|RPL|<txid>|INIT|<address>|<message>
            memo.startsWith("ZMSG|v3|RPL|") && memo.contains("|INIT|") -> {
                val initIndex = memo.indexOf("|INIT|")
                val afterInit = memo.substring(initIndex + 6)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // RPL with hash: ZMSG|v3|RPL|<txid>|<hash>|<message>
            memo.startsWith("ZMSG|v3|RPL|") -> {
                val parts = memo.split("|", limit = 6)
                if (parts.size >= 6) parts[5] else memo
            }
            // Regular INIT: ZMSG|v3|INIT|<address>|<message>
            memo.startsWith("ZMSG|v3|INIT|") -> {
                val parts = memo.split("|", limit = 5)
                if (parts.size >= 5) parts[4] else memo
            }
            // Regular reply with hash: ZMSG|v3|<hash>|<message>
            memo.startsWith("ZMSG|v3|") -> {
                val parts = memo.split("|", limit = 4)
                if (parts.size >= 4) parts[3] else memo
            }
            // Legacy v2: ZMSG|v2|<address>|<message>
            memo.startsWith("ZMSG|v2|") -> {
                val parts = memo.split("|", limit = 4)
                if (parts.size >= 4) parts[3] else memo
            }
            else -> memo
        }
    }

    fun getConversation(peerAddress: String): Conversation? {
        return when (val state = _chatListState.value) {
            is ChatListState.Success -> state.conversations.find { it.peerAddress == peerAddress }
            else -> null
        }
    }

    /**
     * Manual refresh triggered by pull-to-refresh.
     * Forces the SDK to refresh transactions and balances, then updates the UI state.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Force SDK to refresh transactions and balances
                val synchronizer = synchronizerProvider.getSynchronizer() as SdkSynchronizer
                synchronizer.refreshTransactions()
                synchronizer.refreshAllBalances()
            } catch (e: Exception) {
                // Log but don't fail - the sync will continue in the background
                android.util.Log.w("ChatViewModel", "Failed to refresh: ${e.message}")
            }
            _lastSyncTime.value = Instant.now()
            _isRefreshing.value = false
            // Reset countdown
            resetCountdown()
        }
    }

    // ==========================================
    // REMOTE KILL FUNCTIONALITY
    // ==========================================

    /**
     * Set the callback to be called when a remote kill signal is detected.
     * The UI should pass a callback that triggers DestroyManager.destroyAll().
     */
    fun setRemoteKillCallback(callback: () -> Unit) {
        onRemoteKillDetected = callback
    }

    /**
     * Check if a transaction is a remote kill signal.
     * Kill signal requires:
     * 1. Remote kill to be enabled in preferences
     * 2. Transaction amount matches the configured kill amount
     * 3. Memo contains ZCHAT_DESTROY:<secret_phrase>
     */
    private fun checkForRemoteKill(amountZatoshi: Long, memo: String?, txId: String) {
        // Skip if already processed or remote kill not enabled
        if (txId in processedKillCheckTxIds) return
        if (!zchatPreferences.isRemoteKillEnabled()) return

        processedKillCheckTxIds.add(txId)

        val killPhrase = zchatPreferences.getRemoteKillPhrase() ?: return
        val killAmount = zchatPreferences.getRemoteKillAmount()

        // Check if amount matches
        if (amountZatoshi != killAmount) return

        // Check if memo matches kill signal format
        if (memo == null) return
        val expectedMemo = "ZCHAT_DESTROY:$killPhrase"

        if (memo.trim() == expectedMemo) {
            android.util.Log.w("ChatViewModel", "REMOTE KILL SIGNAL DETECTED!")
            // Trigger destruction
            onRemoteKillDetected?.invoke()
        }
    }

    /**
     * Start the auto-refresh timer that syncs every 60 seconds.
     */
    private fun startAutoRefreshTimer() {
        autoRefreshJob?.cancel()
        countdownJob?.cancel()

        // Set initial sync time
        _lastSyncTime.value = Instant.now()
        _secondsUntilNextSync.value = AUTO_REFRESH_INTERVAL_SECONDS

        // Start countdown
        startCountdown()

        // Start auto-refresh
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_SECONDS * 1000L)
                performAutoRefresh()
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var seconds = AUTO_REFRESH_INTERVAL_SECONDS
            while (seconds > 0) {
                _secondsUntilNextSync.value = seconds
                delay(1000L)
                seconds--
            }
            _secondsUntilNextSync.value = 0
        }
    }

    private fun resetCountdown() {
        _secondsUntilNextSync.value = AUTO_REFRESH_INTERVAL_SECONDS
        startCountdown()
    }

    private suspend fun performAutoRefresh() {
        // Don't set isRefreshing = true for auto-refresh
        // This prevents the pull-to-refresh animation from showing
        // Silently refresh transactions and balances from SDK
        try {
            val synchronizer = synchronizerProvider.getSynchronizer() as SdkSynchronizer
            synchronizer.refreshTransactions()
            synchronizer.refreshAllBalances()
        } catch (e: Exception) {
            // Log but don't fail - the sync will continue in the background
            android.util.Log.w("ChatViewModel", "Auto-refresh failed: ${e.message}")
        }
        _lastSyncTime.value = Instant.now()
        resetCountdown()
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
        countdownJob?.cancel()
    }

    /**
     * Send a chat message to the specified peer address.
     * Uses ZMSGv3 protocol:
     * - First message to a new contact: INIT format (includes full address)
     * - Subsequent messages: Hash format (saves ~238 bytes for longer messages)
     *
     * For long messages, automatically splits into multiple outputs (chunked messages).
     *
     * If user hasn't acknowledged message cost yet, shows disclaimer first.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendMessage(peerAddress: String, message: String, amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT) {
        if (_sendMessageState.value is SendMessageState.Sending) return

        // Check if user has acknowledged that messages cost ZEC
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            // Store pending message and show disclaimer
            pendingMessage = Pair(peerAddress, message)
            _showCostDisclaimer.value = true
            return
        }

        // User has acknowledged, proceed with sending
        doSendMessage(peerAddress, message, amountZatoshi)
    }

    companion object {
        const val AUTO_REFRESH_INTERVAL_SECONDS = 60
        // Default amount per message output (1000 zatoshi = 0.00001 ZEC)
        const val DEFAULT_MESSAGE_AMOUNT = 1000L
        // Predefined amount options for message sending
        val MESSAGE_AMOUNTS = listOf(
            1000L to "0.00001 ZEC",
            5000L to "0.00005 ZEC",
            10000L to "0.0001 ZEC",
            50000L to "0.0005 ZEC",
            100000L to "0.001 ZEC"
        )
    }

    /**
     * Called when user acknowledges the message cost disclaimer.
     * Saves the preference and sends the pending message.
     */
    fun acknowledgeCostDisclaimer() {
        zchatPreferences.setAcknowledgedMessageCost()
        _showCostDisclaimer.value = false

        // Send the pending message
        pendingMessage?.let { (peerAddress, message) ->
            pendingMessage = null
            doSendMessage(peerAddress, message)
        }
    }

    /**
     * Called when user dismisses the disclaimer without acknowledging.
     */
    fun dismissCostDisclaimer() {
        _showCostDisclaimer.value = false
        pendingMessage = null
    }

    /**
     * Internal function to actually send the message.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun doSendMessage(peerAddress: String, message: String, amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT) {
        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Create a pending message ID (temporary, will be replaced when tx is confirmed)
                val pendingId = "pending_${System.currentTimeMillis()}"

                // Add pending message immediately for smooth UX
                val pendingChatMessage = ChatMessage(
                    id = pendingId,
                    txId = null, // No tx yet for pending messages
                    text = message,
                    timestamp = Instant.now(),
                    isOutgoing = true,
                    peerAddress = peerAddress,
                    isPending = true,
                    status = MessageStatus.SENDING
                )
                pendingMessages.value = pendingMessages.value + pendingChatMessage

                // Determine if this is the first message to this contact
                // We need to check if the RECIPIENT has ever received our address
                // This happens when we've sent them at least one OUTGOING message before
                // If we've never sent to them, they don't have our address, so use INIT format
                val hasEverSentToThisPeer = hasOutgoingMessageTo(peerAddress)
                val isFirstMessage = !hasEverSentToThisPeer

                // Use the chunked message proposal use case with direct submit
                // skipNavigation = true keeps user on chat screen for smooth messaging flow
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = message,
                    isFirstMessage = isFirstMessage,
                    amountPerOutput = Zatoshi(amountZatoshi),
                    directSubmit = true,
                    skipNavigation = true
                )

                // Mark that we've sent to this address
                sentInitTo.add(peerAddress)

                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                // Mark pending message as FAILED instead of removing it
                pendingMessages.value = pendingMessages.value.map { msg ->
                    if (msg.id.startsWith("pending_") && msg.peerAddress == peerAddress && msg.status == MessageStatus.SENDING) {
                        msg.copy(status = MessageStatus.FAILED, isPending = false)
                    } else {
                        msg
                    }
                }
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send message")
            }
        }
    }

    /**
     * Check if we have ever sent an outgoing message to this peer address.
     * This is used to determine if the peer already has our address (from a previous INIT).
     */
    private fun hasOutgoingMessageTo(peerAddress: String): Boolean {
        val state = _chatListState.value
        if (state !is ChatListState.Success) return false

        val conversation = state.conversations.find { it.peerAddress == peerAddress }
        return conversation?.messages?.any { it.isOutgoing } == true
    }

    /**
     * Get the maximum message length for a given recipient.
     * With chunking support, much longer messages are now possible.
     *
     * @param peerAddress The recipient's address
     * @param maxChunks Maximum number of chunks to use (default 10, ~4500 chars)
     */
    fun getMaxMessageLength(peerAddress: String, maxChunks: Int = 10): Int {
        val isFirstMessage = !sentInitTo.contains(peerAddress)
        return ZMSGProtocol.getMaxChunkedMessageLength(isFirstMessage, maxChunks)
    }

    /**
     * Get the number of chunks that will be needed for a message.
     * Returns 1 for messages that fit in a single memo.
     */
    fun getChunkCount(message: String, peerAddress: String): Int {
        val isFirstMessage = !sentInitTo.contains(peerAddress)
        return ZMSGProtocol.calculateChunkCount(message, isFirstMessage)
    }

    /**
     * Get the total cost of sending a message (may include multiple outputs for chunked messages).
     */
    fun getMessageCost(message: String, peerAddress: String): Zatoshi {
        val isFirstMessage = !sentInitTo.contains(peerAddress)
        return createChunkedMessageProposal.getTotalCost(message, isFirstMessage)
    }

    fun resetSendState() {
        _sendMessageState.value = SendMessageState.Idle
    }

    /**
     * Hide all current messages in a chat.
     * New messages from this contact will still appear (creating a "fresh" chat).
     * The messages are not deleted from the blockchain, just hidden from the UI.
     */
    fun hideChat(peerAddress: String) {
        val state = _chatListState.value
        if (state !is ChatListState.Success) return

        val conversation = state.conversations.find { it.peerAddress == peerAddress }
        val messageIds = conversation?.messages?.map { it.id }?.toSet() ?: emptySet()

        if (messageIds.isNotEmpty()) {
            // Persist to preferences
            zchatPreferences.hideMessages(messageIds)
            // Update reactive state
            hiddenMessages.value = hiddenMessages.value + messageIds
        }
    }

    /**
     * Hide a single message from a chat.
     * The message is not deleted from the blockchain, just hidden from the UI.
     */
    fun hideMessage(messageId: String) {
        // Persist to preferences
        zchatPreferences.hideMessage(messageId)
        // Update reactive state
        hiddenMessages.value = hiddenMessages.value + messageId
    }

    /**
     * Unhide a previously hidden message.
     */
    fun unhideMessage(messageId: String) {
        // Update preferences
        zchatPreferences.unhideMessage(messageId)
        // Update reactive state
        hiddenMessages.value = hiddenMessages.value - messageId
    }

    /**
     * Get the current balance.
     */
    fun getBalance(): Zatoshi {
        val state = _chatListState.value
        return if (state is ChatListState.Success) state.balance else Zatoshi(0)
    }

    /**
     * Get the current ZEC price in USD.
     */
    fun getZecPriceUsd(): Double? = _zecPriceUsd.value

    /**
     * Send a ZEC payment to the specified peer address.
     *
     * @param peerAddress The recipient's address
     * @param amountZec Amount in ZEC (e.g., 0.5 for 0.5 ZEC)
     * @param memo Optional memo for the payment
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendPayment(peerAddress: String, amountZec: Double, memo: String = "") {
        if (_sendMessageState.value is SendMessageState.Sending) return

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                // Convert ZEC to zatoshi (1 ZEC = 100,000,000 zatoshi)
                val amountZatoshi = Zatoshi((amountZec * 100_000_000).toLong())

                // Create proposal with the chunked message use case but as a simple payment
                // The memo will be formatted as a simple note, not ZMSG format
                val paymentMemo = if (memo.isNotBlank()) "Payment: $memo" else "Payment from ZCHAT"

                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = _currentUserAddress.value ?: "",
                    message = paymentMemo,
                    isFirstMessage = false, // Use reply format (shorter) for payments
                    amountPerOutput = amountZatoshi,
                    directSubmit = true,
                    skipNavigation = false // Navigate to progress screen for payments
                )

                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send payment")
            }
        }
    }

    /**
     * Send a payment request to the specified peer.
     * Uses ZREQ protocol: ZREQ|<amount_zatoshi>|<sender_hash>|<reason>
     *
     * @param peerAddress The recipient's address
     * @param amountZatoshi Amount being requested in zatoshi
     * @param reason Optional reason for the request
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendPaymentRequest(peerAddress: String, amountZatoshi: Long, reason: String = "") {
        if (_sendMessageState.value is SendMessageState.Sending) return

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Create a pending message ID
                val pendingId = "pending_${System.currentTimeMillis()}"

                // Add pending message immediately for smooth UX
                val pendingChatMessage = ChatMessage(
                    id = pendingId,
                    txId = null,
                    text = reason.ifEmpty { "Payment request" },
                    timestamp = Instant.now(),
                    isOutgoing = true,
                    peerAddress = peerAddress,
                    isPending = true,
                    status = MessageStatus.SENDING,
                    paymentRequest = PaymentRequestInfo(
                        amountZatoshi = amountZatoshi,
                        reason = reason,
                        isPaid = false,
                        paidTxId = null
                    )
                )
                pendingMessages.value = pendingMessages.value + pendingChatMessage

                // Create payment request memo
                val requestMemo = ZMSGProtocol.createPaymentRequest(amountZatoshi, userAddress, reason)

                // Send with minimal amount (just to deliver the request)
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = requestMemo,
                    isFirstMessage = !sentInitTo.contains(peerAddress),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )

                sentInitTo.add(peerAddress)
                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                // Mark pending message as FAILED
                pendingMessages.value = pendingMessages.value.map { msg ->
                    if (msg.id.startsWith("pending_") && msg.peerAddress == peerAddress && msg.status == MessageStatus.SENDING) {
                        msg.copy(status = MessageStatus.FAILED, isPending = false)
                    } else {
                        msg
                    }
                }
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send payment request")
            }
        }
    }

    /**
     * Fulfill a payment request by sending the requested amount.
     *
     * @param peerAddress The address of the requester
     * @param amountZatoshi The amount being paid (in zatoshi)
     * @param originalRequestId The txId of the original request message
     */
    @Suppress("TooGenericExceptionCaught")
    fun fulfillPaymentRequest(peerAddress: String, amountZatoshi: Long, originalRequestId: String) {
        if (_sendMessageState.value is SendMessageState.Sending) return

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Send the payment with a memo referencing the request
                val paymentMemo = "ZREQ_FULFILL|$originalRequestId"

                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = paymentMemo,
                    isFirstMessage = false,
                    amountPerOutput = Zatoshi(amountZatoshi),
                    directSubmit = true,
                    skipNavigation = false, // Navigate to progress for payments
                    rawMemo = true
                )

                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to fulfill payment request")
            }
        }
    }

    /**
     * Send a reply to a specific message.
     * Uses ZMSGv3 reply format: ZMSG|v3|RPL|<quoted_txid>|...
     *
     * @param peerAddress The recipient's address
     * @param message The reply message
     * @param replyToId The transaction ID of the message being replied to
     * @param amountZatoshi The amount to send per message output
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendReply(peerAddress: String, message: String, replyToId: String, amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT) {
        if (_sendMessageState.value is SendMessageState.Sending) return

        // Check if user has acknowledged that messages cost ZEC
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            pendingMessage = Pair(peerAddress, message)
            _showCostDisclaimer.value = true
            return
        }

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Create a pending message ID
                val pendingId = "pending_${System.currentTimeMillis()}"

                // Find the original message to get preview text
                val originalMessage = findMessageById(replyToId)
                val replyPreview = originalMessage?.text?.take(50) ?: ""

                // Add pending message immediately for smooth UX
                val pendingChatMessage = ChatMessage(
                    id = pendingId,
                    txId = null,
                    text = message,
                    timestamp = Instant.now(),
                    isOutgoing = true,
                    peerAddress = peerAddress,
                    isPending = true,
                    status = MessageStatus.SENDING,
                    replyToId = replyToId,
                    replyToPreview = replyPreview
                )
                pendingMessages.value = pendingMessages.value + pendingChatMessage

                // Determine if this is the first message to this contact
                val hasEverSentToThisPeer = hasOutgoingMessageTo(peerAddress)
                val isFirstMessage = !hasEverSentToThisPeer

                // Create reply memo using ZMSGv3 reply format
                val replyMemo = if (isFirstMessage) {
                    ZMSGProtocol.createReplyInitMessage(userAddress, message, replyToId)
                } else {
                    ZMSGProtocol.createReplyMessage(userAddress, message, replyToId)
                }

                // Use the chunked message proposal use case
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = replyMemo,
                    isFirstMessage = false, // We're sending raw formatted memo
                    amountPerOutput = Zatoshi(amountZatoshi),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true // Tell the use case not to format this as ZMSG
                )

                sentInitTo.add(peerAddress)
                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                // Mark pending message as FAILED
                pendingMessages.value = pendingMessages.value.map { msg ->
                    if (msg.id.startsWith("pending_") && msg.peerAddress == peerAddress && msg.status == MessageStatus.SENDING) {
                        msg.copy(status = MessageStatus.FAILED, isPending = false)
                    } else {
                        msg
                    }
                }
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send reply")
            }
        }
    }

    /**
     * Send an emoji reaction to a message.
     * Uses ZREACT format: ZREACT|<target_txid>|<emoji>|<sender_hash>
     *
     * @param peerAddress The address of the chat partner
     * @param messageId The transaction ID of the message being reacted to
     * @param emoji The emoji reaction (e.g., "👍", "❤️")
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendReaction(peerAddress: String, messageId: String, emoji: String) {
        if (_sendMessageState.value is SendMessageState.Sending) return

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Create reaction memo
                val reactionMemo = ZMSGProtocol.createReaction(messageId, emoji, userAddress)

                // Send minimal amount with reaction memo
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = reactionMemo,
                    isFirstMessage = false,
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )

                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send reaction")
            }
        }
    }

    /**
     * Send a read receipt for a message.
     * Uses ZRCPT format: ZRCPT|<target_txid>|<sender_hash>
     *
     * Note: Read receipts cost ZEC (paid by sender who requested them).
     * The original message should include enough ZEC to cover the receipt.
     *
     * @param peerAddress The address of the chat partner
     * @param messageId The transaction ID of the message that was read
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendReadReceipt(peerAddress: String, messageId: String) {
        viewModelScope.launch {
            try {
                val userAddress = _currentUserAddress.value ?: return@launch

                // Create read receipt memo
                val receiptMemo = ZMSGProtocol.createReadReceipt(messageId, userAddress)

                // Send minimal amount with receipt memo
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = receiptMemo,
                    isFirstMessage = false,
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
            } catch (_: Exception) {
                // Silently fail for read receipts - they're not critical
            }
        }
    }

    /**
     * Find a message by its ID across all conversations.
     */
    private fun findMessageById(messageId: String): ChatMessage? {
        val state = _chatListState.value
        if (state !is ChatListState.Success) return null

        for (conversation in state.conversations) {
            val message = conversation.messages.find { it.id == messageId }
            if (message != null) return message
        }
        return null
    }

    /**
     * Data class to hold sync status information.
     */
    private data class SyncStatus(
        val lastSyncTime: Instant?,
        val isRefreshing: Boolean,
        val secondsUntilNextSync: Int,
        val blockHeight: Long?,
        val zecPriceUsd: Double?
    )

    // ==========================================
    // USER STATUS
    // ==========================================

    /**
     * Set the user's status text.
     * This updates the local status and optionally broadcasts it to contacts.
     *
     * @param status The new status text (max 100 characters)
     * @param broadcast If true, sends status to all contacts (costs ZEC per contact)
     */
    fun setUserStatus(status: String, broadcast: Boolean = false) {
        val truncatedStatus = status.take(100)
        zchatPreferences.setUserStatus(truncatedStatus)
        _userStatus.value = UserStatus(truncatedStatus)

        if (broadcast) {
            broadcastStatusToContacts(truncatedStatus)
        }
    }

    /**
     * Clear the user's status.
     */
    fun clearUserStatus() {
        zchatPreferences.setUserStatus("")
        _userStatus.value = UserStatus.DEFAULT
    }

    /**
     * Broadcast status update to all contacts.
     * This sends a ZSTAT message to each contact.
     *
     * Note: This costs ZEC per contact!
     */
    @Suppress("TooGenericExceptionCaught")
    private fun broadcastStatusToContacts(status: String) {
        val state = _chatListState.value
        if (state !is ChatListState.Success) return

        val userAddress = _currentUserAddress.value ?: return

        // Get all contacts (unique peer addresses)
        val contacts = state.conversations.map { it.peerAddress }.distinct()

        viewModelScope.launch {
            for (peerAddress in contacts) {
                try {
                    val statusMemo = ZMSGProtocol.createStatusMessage(status, userAddress)
                    createChunkedMessageProposal(
                        destinationAddress = peerAddress,
                        senderAddress = userAddress,
                        message = statusMemo,
                        isFirstMessage = false,
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true
                    )
                } catch (_: Exception) {
                    // Silently fail for status broadcasts - not critical
                }
            }
        }
    }

    /**
     * Update a peer's status from a received status message.
     * Called when parsing incoming messages.
     */
    fun updatePeerStatus(peerAddress: String, status: String) {
        zchatPreferences.setPeerStatus(peerAddress, status)
        peerStatuses.value = peerStatuses.value + (peerAddress to UserStatus(status))
    }

    /**
     * Get a peer's current status.
     */
    fun getPeerStatus(peerAddress: String): UserStatus? {
        return peerStatuses.value[peerAddress]
    }

    // ==========================================
    // TIME-LOCKED MESSAGES
    // ==========================================

    /**
     * Send a scheduled message (unlocks at future timestamp)
     *
     * @param peerAddress Recipient address
     * @param message The message content
     * @param unlockTimestamp Unix timestamp (seconds) when message becomes readable
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendScheduledMessage(peerAddress: String, message: String, unlockTimestamp: Long) {
        viewModelScope.launch {
            val userAddress = _currentUserAddress.value ?: return@launch
            try {
                val timeLockMemo = ZMSGProtocol.createScheduledMessage(message, userAddress, unlockTimestamp)
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = timeLockMemo,
                    isFirstMessage = !sentInitTo.contains(peerAddress),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
                sentInitTo.add(peerAddress)
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send scheduled message")
            }
        }
    }

    /**
     * Send a block-height locked message
     *
     * @param peerAddress Recipient address
     * @param message The message content
     * @param unlockHeight Block height when message becomes readable
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendBlockLockedMessage(peerAddress: String, message: String, unlockHeight: Long) {
        viewModelScope.launch {
            val userAddress = _currentUserAddress.value ?: return@launch
            try {
                val timeLockMemo = ZMSGProtocol.createBlockLockedMessage(message, userAddress, unlockHeight)
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = timeLockMemo,
                    isFirstMessage = !sentInitTo.contains(peerAddress),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
                sentInitTo.add(peerAddress)
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send block-locked message")
            }
        }
    }

    /**
     * Send a payment-to-reveal message
     *
     * @param peerAddress Recipient address
     * @param message The message content
     * @param requiredZatoshi Amount required to unlock (in zatoshi)
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendPaymentLockedMessage(peerAddress: String, message: String, requiredZatoshi: Long) {
        viewModelScope.launch {
            val userAddress = _currentUserAddress.value ?: return@launch
            try {
                val timeLockMemo = ZMSGProtocol.createPaymentLockedMessage(message, userAddress, requiredZatoshi)
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = timeLockMemo,
                    isFirstMessage = !sentInitTo.contains(peerAddress),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
                sentInitTo.add(peerAddress)
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send payment-locked message")
            }
        }
    }

    /**
     * Send a conditional message (secret answer required)
     *
     * @param peerAddress Recipient address
     * @param message The message content
     * @param answer The secret answer (will be hashed)
     * @param hint A hint for the recipient
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendConditionalMessage(peerAddress: String, message: String, answer: String, hint: String) {
        viewModelScope.launch {
            val userAddress = _currentUserAddress.value ?: return@launch
            try {
                val timeLockMemo = ZMSGProtocol.createConditionalMessage(message, userAddress, answer, hint)
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = timeLockMemo,
                    isFirstMessage = !sentInitTo.contains(peerAddress),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
                sentInitTo.add(peerAddress)
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to send conditional message")
            }
        }
    }

    /**
     * Unlock a payment-locked message by sending the required payment
     *
     * @param lockedMessageTxId The transaction ID of the locked message
     * @param senderAddress The address of the message sender (to send payment to)
     * @param amount The required amount in zatoshi
     */
    @Suppress("TooGenericExceptionCaught")
    fun unlockPaymentMessage(lockedMessageTxId: String, senderAddress: String, amount: Long) {
        viewModelScope.launch {
            val userAddress = _currentUserAddress.value ?: return@launch
            try {
                val unlockMemo = ZMSGProtocol.createUnlockPayment(lockedMessageTxId, userAddress)
                // Send payment with unlock memo to the original sender
                createChunkedMessageProposal(
                    destinationAddress = senderAddress,
                    senderAddress = userAddress,
                    message = unlockMemo,
                    isFirstMessage = !sentInitTo.contains(senderAddress),
                    amountPerOutput = Zatoshi(amount),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
                sentInitTo.add(senderAddress)
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to unlock message")
            }
        }
    }

    /**
     * Unlock a conditional message by providing the answer
     *
     * @param lockedMessageTxId The transaction ID of the locked message
     * @param senderAddress The address of the message sender
     * @param answer The answer to unlock
     * @param answerHash The hash to verify against
     */
    @Suppress("TooGenericExceptionCaught")
    fun unlockConditionalMessage(
        lockedMessageTxId: String,
        senderAddress: String,
        answer: String,
        answerHash: String
    ): Boolean {
        // Verify the answer first
        if (!ZMSGProtocol.verifyConditionalAnswer(answer, answerHash)) {
            _sendMessageState.value = SendMessageState.Error("Incorrect answer")
            return false
        }

        viewModelScope.launch {
            val userAddress = _currentUserAddress.value ?: return@launch
            try {
                val unlockMemo = ZMSGProtocol.createUnlockAnswer(lockedMessageTxId, answer, userAddress)
                // Send unlock answer memo to the original sender
                createChunkedMessageProposal(
                    destinationAddress = senderAddress,
                    senderAddress = userAddress,
                    message = unlockMemo,
                    isFirstMessage = !sentInitTo.contains(senderAddress),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
                sentInitTo.add(senderAddress)

                // Locally unlock the message immediately
                unlockedMessages.value = unlockedMessages.value + (lockedMessageTxId to "local")
            } catch (e: Exception) {
                _sendMessageState.value = SendMessageState.Error(e.message ?: "Failed to submit answer")
            }
        }
        return true
    }

    /**
     * Get the current block height
     */
    fun getCurrentBlockHeight(): Long? = _blockHeight.value
}
