package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.common.util.redactConvId
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.WalletSyncStatus
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PoolType
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockInfo
import co.electriccoin.zcash.ui.screen.chat.model.TimeLockType
import co.electriccoin.zcash.ui.screen.chat.model.UnknownReason
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.model.AdminPolicy
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.Instant
import java.util.Collections

@OptIn(FlowPreview::class)
class ChatViewModel(
    private val transactionRepository: TransactionRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val accountDataSource: AccountDataSource,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val addressCache: AddressCache,
    private val zchatPreferences: ZchatPreferences,
    private val synchronizerProvider: SynchronizerProvider,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val contactBook: ContactBook,
    private val walletSnapshotDataSource: WalletSnapshotDataSource,
    private val persistableWalletProvider: PersistableWalletProvider
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

    // Pending message (stored when disclaimer needs to be shown).
    // Carries all send params so reply context (replyToId, amountZatoshi) isn't lost.
    private data class PendingMessageParams(
        val peerAddress: String,
        val message: String,
        val replyToId: String? = null,
        val amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT,
        val paymentRequestAmount: Long? = null, // Non-null = payment request
        val paymentRequestReason: String = ""
    )
    private var pendingMessage: PendingMessageParams? = null

    // Sync status
    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _secondsUntilNextSync = MutableStateFlow(AUTO_REFRESH_INTERVAL_SECONDS)
    private val _blockHeight = MutableStateFlow<Long?>(null)
    private val _zecPriceUsd = MutableStateFlow<Double?>(null)

    // Wallet sync progress for restore/sync indicator
    private val _walletSyncStatus = MutableStateFlow(WalletSyncStatus())

    // Track hidden messages (message IDs the user has chosen to hide)
    // Initialized from preferences and updated reactively
    private val hiddenMessages = MutableStateFlow<Set<String>>(emptySet())

    // Pending messages that are being sent (not yet confirmed on blockchain)
    // These are shown immediately in the chat for smooth UX
    private val pendingMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    // Message queue: when a send is in progress, additional sends are queued
    // and processed sequentially (Zcash can't send two txs simultaneously).
    private data class QueuedMessage(
        val peerAddress: String,
        val message: String,
        val amountZatoshi: Long,
        val pendingId: String,
        val retryCount: Int = 0
    )
    private val messageQueue = mutableListOf<QueuedMessage>()

    // User status (own status text)
    private val _userStatus = MutableStateFlow(UserStatus.DEFAULT)
    val userStatus: StateFlow<UserStatus> = _userStatus.asStateFlow()

    // Wallet birthday (block height from which to start scanning)
    private var walletBirthday: Long? = null

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
    // Thread-safe: accessed from multiple coroutines
    private val processedKillCheckTxIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Mutex to prevent race conditions between sync (receiving INIT) and send
    // (generating ConvID) for the same peer. Both paths write to conversation mappings.
    private val convIdMutex = Mutex()

    // E2E ratchet — persistent encrypted store for counters/seen-counter sets. Survives
    // app restart so replay protection and counter state are maintained across sessions.
    private val ratchetStateStore = zchatPreferences.getRatchetStateStore()
    private val messageProcessors = mutableMapOf<String, co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor>()

    // Gate that loadConversations awaits before processing, ensuring
    // validateAndRepairConvIdMappings completes first to prevent reading partial repairs.
    private val repairComplete = CompletableDeferred<Unit>()

    // Conversation loading job — tracked so setNickname can cancel+restart without
    // spawning duplicate collection coroutines that race to write _chatListState.
    private var conversationLoadJob: Job? = null

    // Auto-refresh timer job
    private var autoRefreshJob: Job? = null
    private var countdownJob: Job? = null

    init {
        // Load hidden messages from preferences
        hiddenMessages.value = zchatPreferences.getHiddenMessageIds()
        // Load pending messages from preferences (persisted across navigation)
        loadPendingMessagesFromPrefs()
        // Load user status from preferences
        loadUserStatus()
        // Load peer statuses from preferences
        loadPeerStatuses()
        // Load wallet birthday for sync status display
        viewModelScope.launch {
            try {
                val wallet = persistableWalletProvider.getPersistableWallet()
                walletBirthday = wallet?.birthday?.value
            } catch (e: Exception) {
                Log.d("ZCHAT_FLOW", "Could not load wallet birthday: ${e.message}")
            }
        }
        // Validate and repair conversation ID mappings (fix for misrouted messages after restore)
        validateAndRepairConvIdMappings()
        loadConversations()
        startAutoRefreshTimer()
        observeBlockHeight()
        observeExchangeRate()
        observeWalletSyncStatus()
    }

    /**
     * Validate and repair conversation ID mappings.
     * This function detects missing or inconsistent convId mappings and repairs them.
     * Essential for fixing message routing issues after wallet restore.
     *
     * Repair strategy (single-pass, snapshot-based):
     * 1. Load both convId→peer and peer→convId snapshots
     * 2. Build consistent target state in memory (conv→peer is authoritative)
     * 3. Write only entries that differ from current state
     * 4. Log all repairs for debugging
     *
     * Uses snapshots exclusively — never reads live data during iteration to prevent
     * stale-snapshot corruption where pass N sees pass N-1's writes as inconsistencies.
     *
     * Runs with convIdMutex held to prevent races with send/receive paths.
     */
    private fun validateAndRepairConvIdMappings() {
        viewModelScope.launch {
            try {
                convIdMutex.withLock {
                    try {
                        val allConvMappings = zchatPreferences.getAllConversationMappings() // convId -> peer
                        val allPeerMappings = zchatPreferences.getAllPeerToConvIdMappings() // peer -> convId
                        var repairCount = 0
                        var orphanCount = 0

                        Log.d("ZCHAT_REPAIR", "=== Validating ConvId Mappings (conv=${allConvMappings.size}, peer=${allPeerMappings.size}) ===")

                        // Build consistent target state in memory from both snapshots.
                        // Conv→peer direction is authoritative; peer→convId fills gaps.
                        val targetConvToPeer = mutableMapOf<String, String>() // convId -> peer
                        val targetPeerToConv = mutableMapOf<String, String>() // peer -> convId

                        // Phase 1: conv→peer entries are authoritative
                        val orphanConvIds = mutableListOf<String>()
                        for ((convId, peer) in allConvMappings) {
                            if (peer.isBlank()) {
                                Log.w("ZCHAT_REPAIR", "Orphaned conv entry with blank peer: ${convId.redactConvId()}")
                                orphanConvIds.add(convId)
                                orphanCount++
                                continue
                            }
                            targetConvToPeer[convId] = peer
                            // First convId seen for a peer wins
                            if (!targetPeerToConv.containsKey(peer)) {
                                targetPeerToConv[peer] = convId
                            }
                        }

                        // Phase 2: peer→convId fills gaps only (does not override authoritative entries)
                        for ((peer, convId) in allPeerMappings) {
                            if (!targetPeerToConv.containsKey(peer)) {
                                targetPeerToConv[peer] = convId
                                if (!targetConvToPeer.containsKey(convId)) {
                                    targetConvToPeer[convId] = peer
                                }
                            }
                        }

                        // Write only entries where this convId is the CHOSEN one for this peer.
                        // Skip entries where multiple convIds map to the same peer but this isn't
                        // the winner — calling setConversationMapping would overwrite the reverse mapping.
                        for ((convId, peer) in targetConvToPeer) {
                            val chosenConvId = targetPeerToConv[peer]
                            if (chosenConvId != convId) {
                                // This convId lost the "first wins" tie for this peer — skip write
                                Log.d("ZCHAT_REPAIR", "Skipping non-primary mapping: ${convId.redactConvId()} -> ${peer.redactAddress()} (primary is ${chosenConvId?.redactConvId()})")
                                continue
                            }
                            val currentForward = allConvMappings[convId]
                            val currentReverse = allPeerMappings[peer]
                            if (currentForward != peer || currentReverse != convId) {
                                Log.w("ZCHAT_REPAIR", "Repairing mapping: ${convId.redactConvId()} <-> ${peer.redactAddress()} " +
                                    "(was forward=${currentForward?.redactAddress()}, reverse=${currentReverse?.redactConvId()})")
                                zchatPreferences.setConversationMapping(convId, peer)
                                repairCount++
                            }
                        }

                        // Delete blank-peer orphan conv: entries to prevent getPeerByConversationId
                        // from returning blank strings that create degenerate conversation keys.
                        for (orphanConvId in orphanConvIds) {
                            zchatPreferences.removeConversationMapping(orphanConvId)
                            Log.w("ZCHAT_REPAIR", "Deleted orphan conv entry: ${orphanConvId.redactConvId()}")
                        }

                        if (repairCount > 0 || orphanCount > 0) {
                            Log.i("ZCHAT_REPAIR", "Repaired $repairCount mappings, deleted $orphanCount orphans")
                        } else {
                            Log.d("ZCHAT_REPAIR", "All ConvId mappings are consistent")
                        }
                    } catch (e: Exception) {
                        Log.e("ZCHAT_REPAIR", "Error validating ConvId mappings", e)
                    }
                }
            } finally {
                // Always open the gate, even if mutex acquisition or coroutine is cancelled.
                // This prevents loadConversations() from hanging forever.
                repairComplete.complete(Unit)
            }
        }
    }

    /**
     * Load pending messages from preferences.
     * These are messages that were sent but not yet confirmed on blockchain.
     * Persisting them ensures they survive screen navigation.
     */
    private fun loadPendingMessagesFromPrefs() {
        val savedPending = zchatPreferences.getPendingMessages()
        if (savedPending.isNotEmpty()) {
            val chatMessages = savedPending.map { data ->
                ChatMessage(
                    id = data.id,
                    txId = null,
                    text = data.text,
                    timestamp = java.time.Instant.ofEpochMilli(data.timestampMillis),
                    isOutgoing = true,
                    peerAddress = data.peerAddress,
                    isPending = true
                )
            }
            pendingMessages.value = chatMessages
            Log.d("ZCHAT_PENDING", "Loaded ${chatMessages.size} pending messages from preferences")
        }
    }

    /**
     * Check if this would be the first message to a peer using v4 conversation IDs.
     * Returns true if we haven't established a conversation ID with this peer yet.
     */
    private fun isFirstMessageTo(peerAddress: String): Boolean {
        return zchatPreferences.getConversationId(peerAddress) == null
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

    /**
     * Observe wallet snapshot to provide detailed sync progress for the UI.
     * Shows progress percentage and description during wallet restore/sync.
     */
    private fun observeWalletSyncStatus() {
        viewModelScope.launch {
            walletSnapshotDataSource.observe().collect { snapshot ->
                if (snapshot == null) {
                    _walletSyncStatus.value = WalletSyncStatus()
                    return@collect
                }

                val progress = snapshot.progress.decimal * 100f
                val isRestoring = snapshot.restoringState == WalletRestoringState.RESTORING
                val isInitiating = snapshot.restoringState == WalletRestoringState.INITIATING
                // Don't show sync progress for new wallets (SYNCING restoring state means
                // wallet was created with current chain height and has no history to scan)
                val isNewWallet = snapshot.restoringState == WalletRestoringState.SYNCING
                val isSyncing = !isNewWallet && snapshot.status == Synchronizer.Status.SYNCING

                val statusMessage = when {
                    isRestoring && progress < 10f -> "Initializing wallet restore..."
                    isRestoring && progress < 50f -> "Scanning blockchain history..."
                    isRestoring && progress < 90f -> "Retrieving your messages..."
                    isRestoring -> "Finalizing restore..."
                    isInitiating && progress < 10f -> "Setting up new wallet..."
                    isInitiating -> "Syncing initial data..."
                    isSyncing && progress < 50f -> "Checking for new messages..."
                    isSyncing && progress < 90f -> "Updating wallet..."
                    isSyncing -> "Almost done..."
                    snapshot.status == Synchronizer.Status.DISCONNECTED -> "Disconnected - reconnecting..."
                    snapshot.status == Synchronizer.Status.STOPPED -> "Sync stopped"
                    else -> ""
                }

                // Format block range if available (for UI display)
                // Uses wallet birthday as the start of the scan range (not block 0)
                val blockHeight = _blockHeight.value
                val scanningRange = if ((isRestoring || isInitiating || isSyncing) && blockHeight != null && blockHeight > 0) {
                    val birthday = walletBirthday ?: 0L
                    val blocksToScan = blockHeight - birthday
                    val scannedBlocks = (blocksToScan * progress / 100).toLong()
                    val currentScanBlock = birthday + scannedBlocks
                    "Blocks ${formatNumber(currentScanBlock)} - ${formatNumber(blockHeight)}"
                } else null

                _walletSyncStatus.value = WalletSyncStatus(
                    isRestoring = isRestoring,
                    isInitiating = isInitiating,
                    isSyncing = isSyncing,
                    progress = progress,
                    statusMessage = statusMessage,
                    scanningRange = scanningRange
                )
            }
        }
    }

    private fun formatNumber(number: Long): String {
        return String.format("%,d", number)
    }

    private fun loadConversations() {
        // Cancel previous collection job to prevent duplicate coroutines
        conversationLoadJob?.cancel()
        conversationLoadJob = viewModelScope.launch {
            try {
                // Wait for convID repair to finish before processing conversations,
                // otherwise we may read partially-repaired mappings.
                repairComplete.await()

                // Get current user address - use default unified address for consistency after restore
                // IMPORTANT: Do NOT use fallback to account.unified.address - it may be a different diversified address
                val userAddress = getDefaultUnifiedAddress()
                _currentUserAddress.value = userAddress

                // Split into two flows to avoid cancelling expensive convertToConversations()
                // every time the countdown timer ticks (every second).
                //
                // Flow 1: Expensive conversation conversion — only re-runs when data changes
                // Flow 2: Lightweight sync metadata — can change every second without cost

                // Flow 1: Conversations (expensive, only on data changes)
                val conversationsFlow = combine(
                    transactionRepository.transactions.filterNotNull()
                        .debounce(300), // Batch rapid emissions during sync to avoid reprocessing
                    hiddenMessages,
                    pendingMessages
                ) { transactions, hiddenMsgIds, pending ->
                    @Suppress("UNCHECKED_CAST")
                    val txList = transactions as List<Transaction>
                    val receiveCount = txList.count { it is co.electriccoin.zcash.ui.common.repository.ReceiveTransaction }
                    val sendCount = txList.count { it is co.electriccoin.zcash.ui.common.repository.SendTransaction }
                    Log.d("ZCHAT_FLOW", "=== Transactions flow emitted: total=${txList.size} (rx=$receiveCount tx=$sendCount) pending=${pending.size} hidden=${hiddenMsgIds.size} ===")
                    convertToConversations(txList, userAddress, hiddenMsgIds, pending)
                }

                // Flow 2: Sync status (cheap, changes every second)
                val syncStatusFlow = combine(
                    _lastSyncTime,
                    _isRefreshing,
                    _secondsUntilNextSync,
                    _blockHeight,
                    _zecPriceUsd
                ) { lastSync, isRefreshing, secondsUntilNext, blockHeight, zecPrice ->
                    SyncStatus(lastSync as? Instant, isRefreshing as Boolean, secondsUntilNext as Int, blockHeight as? Long, zecPrice as? Double)
                }

                val combinedSyncFlow = combine(syncStatusFlow, _walletSyncStatus) { sync, walletSync ->
                    sync to walletSync
                }

                // Combine conversations (cached) with sync metadata (fast-changing)
                combine(
                    conversationsFlow,
                    accountDataSource.selectedAccount,
                    combinedSyncFlow
                ) { conversations, walletAccount, syncPair ->
                    val (syncStatus, walletSyncStatus) = syncPair

                    // Add peer statuses, drafts, and E2E status to conversations
                    val drafts = zchatPreferences.getAllDrafts()
                    val conversationsWithStatus = conversations.map { conversation ->
                        val peerStatus = peerStatuses.value[conversation.peerAddress]
                        val draft = drafts[conversation.peerAddress]
                        val e2eEnabled = zchatPreferences.isE2EEnabled(conversation.peerAddress)
                        val e2eKeyExchangeComplete = zchatPreferences.isE2EKeyExchangeComplete(conversation.peerAddress)
                        conversation.copy(
                            peerStatus = peerStatus,
                            draft = draft,
                            e2eEnabled = e2eEnabled,
                            e2eKeyExchangeComplete = e2eKeyExchangeComplete,
                            isMuted = zchatPreferences.isConversationMuted(conversation.peerAddress)
                        )
                    }
                    val balance = walletAccount?.totalBalance ?: Zatoshi(0)
                    val privacyStatus = computePrivacyStatus(walletAccount)
                    // Load groups from preferences
                    val groups = loadAllGroups()
                    ChatListState.Success(
                        conversations = conversationsWithStatus,
                        groups = groups,
                        currentUserAddress = userAddress,
                        balance = balance,
                        lastSyncTime = syncStatus.lastSyncTime,
                        isRefreshing = syncStatus.isRefreshing,
                        secondsUntilNextSync = syncStatus.secondsUntilNextSync,
                        blockHeight = syncStatus.blockHeight,
                        zecPriceUsd = syncStatus.zecPriceUsd,
                        privacyStatus = privacyStatus,
                        syncStatus = walletSyncStatus
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

        // IMPORTANT: Sort transactions in chronological order (oldest first) so that
        // when processing replies, the original message has already been added to messagesByPeer.
        // This fixes the bug where replies were placed in new conversations because the
        // replyToTxId lookup couldn't find the original message (which was processed later).
        //
        // Sort by timestamp first (not minedHeight) to ensure pending outgoing messages
        // are processed in the correct chronological position. Previously, null minedHeight
        // was treated as Long.MAX_VALUE, causing pending outgoing messages to be sorted last.
        // This meant incoming REF replies couldn't find the original outgoing message.
        val sortedTransactions = transactions.sortedWith(
            compareBy<Transaction> { it.timestamp ?: Instant.MIN }
                .thenBy { it.overview.minedHeight?.value ?: 0L }
        )

        // Diagnostic counters for summary telemetry
        var diagTotal = 0; var diagOutgoing = 0; var diagIncoming = 0
        var diagSkipHidden = 0; var diagSkipNoMemo = 0; var diagSkipStatus = 0
        var diagSkipReaction = 0; var diagSkipKEX = 0; var diagSkipGroup = 0
        var diagSkipUnlock = 0; var diagSkipParseFail = 0; var diagSkipPeerFail = 0
        var diagSkipBlank = 0; var diagAdded = 0

        for (tx in sortedTransactions) {
            diagTotal++
            val messageId = tx.id.txIdString()
            val txType = if (tx is co.electriccoin.zcash.ui.common.repository.SendTransaction) "SEND" else "RECEIVE"
            val height = tx.overview.minedHeight?.value ?: -1L
            if (txType == "SEND") diagOutgoing++ else diagIncoming++
            Log.d("ZCHAT_THREADING", "Processing tx: $messageId, type=$txType, height=$height")

            // Skip hidden messages
            if (messageId in hiddenMsgIds) {
                diagSkipHidden++
                Log.d("ZCHAT_FLOW", "SKIP hidden: $messageId")
                continue
            }

            // Get memos for this transaction
            val memos = try {
                transactionRepository.getMemos(tx)
            } catch (e: Exception) {
                Log.w("ZCHAT_FLOW", "getMemos failed for $messageId: ${e.message}")
                emptyList()
            }
            if (memos.isEmpty()) {
                diagSkipNoMemo++
                Log.d("ZCHAT_FLOW", "SKIP no-memo: $messageId type=$txType")
                continue
            }

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
                    peerStatuses.update { it + (parsedStatus.senderAddress to UserStatus(parsedStatus.statusText)) }
                    zchatPreferences.setPeerStatus(parsedStatus.senderAddress, parsedStatus.statusText)
                }
                diagSkipStatus++
                Log.d("ZCHAT_FLOW", "SKIP status: $messageId")
                continue // Status messages don't appear in chat
            }

            // Skip reactions and read receipts from appearing in chat (they're metadata)
            if (ZMSGProtocol.isReaction(memoText) || ZMSGProtocol.isReadReceipt(memoText)) {
                diagSkipReaction++
                Log.d("ZCHAT_FLOW", "SKIP reaction/receipt: $messageId")
                continue
            }

            // Handle KEX (Key Exchange) messages - don't appear in chat
            // Also handle legacy E2E_INIT format for backward compatibility
            if (ZMSGProtocol.isKEXMessage(memoText) || ZMSGProtocol.isKEXAckMessage(memoText) ||
                memoText.contains("E2E_INIT:")) {
                if (tx is co.electriccoin.zcash.ui.common.repository.ReceiveTransaction) {
                    handleKEXMessage(memoText, userAddress)
                }
                diagSkipKEX++
                Log.d("ZCHAT_FLOW", "SKIP KEX: $messageId")
                continue
            }

            // Handle GROUP protocol messages (don't appear in regular chat)
            if (ZMSGGroupProtocol.isGroupMessage(memoText)) {
                processGroupMessage(memoText, tx.id, tx.timestamp)
                diagSkipGroup++
                Log.d("ZCHAT_FLOW", "SKIP group: $messageId")
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
                    // Track the unlock (atomic update)
                    unlockedMessages.update { it + (parsedUnlock.originalTxId to messageId) }
                }
                diagSkipUnlock++
                Log.d("ZCHAT_FLOW", "SKIP unlock: $messageId")
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

            // Note: Platform fee address filtering for outgoing transactions is handled
            // inside resolveOutgoingPeerAddress() (Strategy 1 & 2), not here, to avoid
            // incorrectly discarding multi-output transactions.

            // Determine peer address and direction
            val isOutgoing = tx is co.electriccoin.zcash.ui.common.repository.SendTransaction

            val peerAddress: String
            val displayMessage: String
            val unknownReason: UnknownReason?

            if (isOutgoing) {
                // For outgoing, resolve peer address with multi-layered fallbacks.
                val resolvedPeer = resolveOutgoingPeerAddress(
                    tx = tx,
                    memos = memos,
                    pendingMsgs = pendingMsgs
                )
                if (resolvedPeer == null) {
                    diagSkipPeerFail++
                    Log.w("ZCHAT_FLOW", "SKIP outgoing-peer-fail: $messageId memoLen=${memoText.length}")
                    continue
                }
                peerAddress = resolvedPeer

                Log.d("ZCHAT_THREADING", "Storing OUTGOING message: txId=${tx.id.txIdString()}, peerAddress=${peerAddress.redactAddress()}")

                val hasChunkedMemos = memos.any { ZMSGProtocol.isChunkedMemo(it) }

                val rawMessage = if (hasChunkedMemos) {
                    val reassembled = ZMSGProtocol.reassembleChunks(memos, addressCache)
                    reassembled?.message ?: extractMessageContent(memos.joinToString("\n"))
                } else {
                    extractMessageContent(memos.joinToString("\n").trim())
                }

                unknownReason = null
                addressCache.addConversationPartner(peerAddress)

                // CRITICAL: Extract and restore convId mapping from outgoing messages
                // Essential for wallet restore - mappings are rebuilt from blockchain txs.
                @Suppress("NAME_SHADOWING")
                val memoText = memos.joinToString("\n").trim()
                val extractedConvId = extractConvIdFromMemo(memoText)

                // E2E ratchet decrypt: if the extracted message is E2E1:-prefixed, decrypt it
                displayMessage = tryDecryptMessage(rawMessage, peerAddress, extractedConvId)
                if (extractedConvId != null) {
                    val existingPeer = zchatPreferences.getPeerByConversationId(extractedConvId)
                    if (existingPeer == null) {
                        Log.d("ZCHAT_RESTORE", "Restoring convId mapping from outgoing: ${extractedConvId.redactConvId()} -> ${peerAddress.redactAddress()}")
                        zchatPreferences.setConversationMapping(extractedConvId, peerAddress)
                    } else if (existingPeer != peerAddress) {
                        Log.w("ZCHAT_RESTORE", "ConvId ${extractedConvId.redactConvId()} already mapped to ${existingPeer.redactAddress()}, not overwriting with ${peerAddress.redactAddress()}")
                    }
                }
            } else {
                // For incoming, check if chunked or single memo
                val hasChunkedMemos = memos.any { ZMSGProtocol.isChunkedMemo(it) }

                val parsed = if (hasChunkedMemos) {
                    ZMSGProtocol.reassembleChunks(memos, addressCache)
                } else {
                    @Suppress("NAME_SHADOWING")
                    val memoText = memos.joinToString("\n").trim()
                    if (memoText.isNotBlank()) {
                        ZMSGProtocol.parseMemo(memoText, addressCache)
                    } else {
                        null
                    }
                }

                if (parsed == null) {
                    diagSkipParseFail++
                    Log.d("ZCHAT_FLOW", "SKIP incoming-parse-null: $messageId chunked=$hasChunkedMemos memoLen=${memoText.length} prefix=${memoText.take(20)}")
                    continue
                }

                // === SIMPLIFIED 3-TIER ROUTING ===
                // Tier 1: ConvID lookup (HIGH confidence)
                // Tier 2: Direct address match (MEDIUM confidence)
                // Tier 3: New conversation (SAFE fallback)
                val resolvedPeerAddress = run {
                    val senderAddr = parsed.senderAddress ?: "unknown"
                    val senderHash = parsed.senderHash
                    val convId = parsed.conversationId

                    Log.d("ZCHAT_V4", "=== Routing incoming message ===")
                    Log.d("ZCHAT_V4", "Sender hash: ${senderHash?.redactAddress()}, convId: ${convId?.redactConvId()}, msgLen: ${parsed.message.length}")

                    // ── TIER 1: ConvID lookup (highest confidence) ──
                    if (convId != null) {
                        // 1a: Direct convId → peer lookup
                        val peerFromConvId = zchatPreferences.getPeerByConversationId(convId)
                        if (peerFromConvId != null) {
                            Log.d("ZCHAT_V4", "TIER1: Matched via convID -> ${peerFromConvId.redactAddress()}")
                            // Always cache sender hash → resolved peer for future lookups.
                            // Use validated caching since convID is a high-confidence source.
                            if (senderHash != null) {
                                addressCache.cacheAddressValidated(senderHash, peerFromConvId)
                                addressCache.addConversationPartner(peerFromConvId)
                            }
                            return@run peerFromConvId
                        }

                        // 1b: Reverse lookup - check if any existing peer has this convId
                        val existingPeerForConvId = messagesByPeer.keys.find { peerAddr ->
                            zchatPreferences.getConversationId(peerAddr) == convId
                        }
                        if (existingPeerForConvId != null) {
                            Log.d("ZCHAT_V4", "TIER1: Matched via reverse peer->convId lookup -> ${existingPeerForConvId.redactAddress()}")
                            zchatPreferences.setConversationMapping(convId, existingPeerForConvId)
                            if (senderHash != null) {
                                addressCache.cacheAddressValidated(senderHash, existingPeerForConvId)
                            }
                            return@run existingPeerForConvId
                        }

                        // 1c: ConvId not found but we have sender address — new INIT from someone
                        if (senderAddr != "unknown") {
                            Log.d("ZCHAT_V4", "TIER1: New convID from ${senderAddr.redactAddress()} - storing mapping")
                            zchatPreferences.setConversationMapping(convId, senderAddr)
                            if (senderHash != null) {
                                addressCache.cacheAddressValidated(senderHash, senderAddr)
                            }
                            return@run senderAddr
                        }

                        // 1d: ConvId not found, no sender addr, try hash cache
                        if (senderHash != null) {
                            val cachedAddr = addressCache.getAddress(senderHash)
                            if (cachedAddr != null) {
                                Log.d("ZCHAT_V4", "TIER1: Matched via hash cache -> ${cachedAddr.redactAddress()}")
                                zchatPreferences.setConversationMapping(convId, cachedAddr)
                                return@run cachedAddr
                            }
                        }
                    }

                    // ── TIER 2: Direct address match (medium confidence) ──
                    if (senderAddr != "unknown") {
                        // 2a: Exact key match in existing conversations
                        if (messagesByPeer.containsKey(senderAddr)) {
                            Log.d("ZCHAT_V4", "TIER2: Matched via direct peer address -> ${senderAddr.redactAddress()}")
                            if (convId != null) {
                                zchatPreferences.setConversationMapping(convId, senderAddr)
                            }
                            return@run senderAddr
                        }

                        // 2b: Contact book match
                        if (contactBook.hasContact(senderAddr)) {
                            Log.d("ZCHAT_V4", "TIER2: Matched via contact book -> ${senderAddr.redactAddress()}")
                            if (convId != null) {
                                zchatPreferences.setConversationMapping(convId, senderAddr)
                            }
                            return@run senderAddr
                        }

                        // 2c: Hash match against existing conversations
                        val senderHashForMatch = senderHash ?: ZMSGProtocol.generateAddressHash(senderAddr)
                        val hashMatchConv = messagesByPeer.keys.find { existingPeerAddr ->
                            val existingHash = ZMSGProtocol.generateAddressHash(existingPeerAddr)
                            existingHash == senderHashForMatch
                        }
                        if (hashMatchConv != null) {
                            Log.d("ZCHAT_V4", "TIER2: Matched via hash comparison -> ${hashMatchConv.redactAddress()}")
                            addressCache.cacheAddress(senderHashForMatch, hashMatchConv)
                            if (convId != null) {
                                zchatPreferences.setConversationMapping(convId, hashMatchConv)
                            }
                            return@run hashMatchConv
                        }
                    }

                    // ── TIER 3: New conversation (safe fallback) ──
                    if (senderAddr != "unknown") {
                        Log.d("ZCHAT_V4", "TIER3: New conversation with sender -> ${senderAddr.redactAddress()}")
                        val hashToCache = senderHash ?: ZMSGProtocol.generateAddressHash(senderAddr)
                        addressCache.cacheAddress(hashToCache, senderAddr)
                        if (convId != null) {
                            zchatPreferences.setConversationMapping(convId, senderAddr)
                        }
                        senderAddr
                    } else if (senderHash != null) {
                        // Last resort: check partner match by hash
                        val partnerMatch = addressCache.findConversationPartnerByHash(senderHash)
                        if (partnerMatch != null) {
                            Log.d("ZCHAT_V4", "TIER3: Matched via conversation partner -> ${partnerMatch.redactAddress()}")
                            addressCache.cacheAddress(senderHash, partnerMatch)
                            if (convId != null) {
                                zchatPreferences.setConversationMapping(convId, partnerMatch)
                            }
                            return@run partnerMatch
                        }
                        Log.w("ZCHAT_V4", "TIER3: Unknown sender, using hash as key: $senderHash")
                        senderHash
                    } else if (convId != null) {
                        // No sender address or hash, but we have a convId.
                        // Use convId as conversation key to preserve grouping.
                        // When a future message with same convId arrives with sender info,
                        // Tier 1c will create the proper mapping.
                        Log.w("ZCHAT_V4", "TIER3: No sender info but has convId=${convId.redactConvId()}, using as conversation key")
                        convId
                    } else {
                        Log.w("ZCHAT_V4", "TIER3: No sender info at all, using 'unknown'")
                        "unknown"
                    }
                }

                Log.d("ZCHAT_THREADING", "=== Final resolved peer: ${resolvedPeerAddress.redactAddress()} ===")
                peerAddress = resolvedPeerAddress
                displayMessage = parsed.message
                unknownReason = parsed.reason
            }

            if (displayMessage.isBlank()) {
                diagSkipBlank++
                Log.w("ZCHAT_FLOW", "SKIP blank-message: $messageId type=$txType memoLen=${memoText.length} " +
                    "peer=${peerAddress.take(15)}... memo=${memoText.take(80)} " +
                    "unknownReason=$unknownReason")
                continue
            }

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
                txIndex = tx.overview.index?.toInt(),
                timeLock = timeLockInfo,
                paymentRequest = paymentRequestInfo
            )

            messagesByPeer.getOrPut(peerAddress) { mutableListOf() }.add(message)
            confirmedIds.add(messageId)
            diagAdded++
        }

        // === DIAGNOSTIC SUMMARY ===
        val diagSkipped = diagTotal - diagAdded
        Log.i("ZCHAT_DIAG", "=== convertToConversations SUMMARY ===")
        Log.i("ZCHAT_DIAG", "Total txs: $diagTotal (out=$diagOutgoing, in=$diagIncoming)")
        Log.i("ZCHAT_DIAG", "Added to chat: $diagAdded, Skipped: $diagSkipped")
        Log.i("ZCHAT_DIAG", "Skip reasons: hidden=$diagSkipHidden noMemo=$diagSkipNoMemo status=$diagSkipStatus " +
            "reaction=$diagSkipReaction KEX=$diagSkipKEX group=$diagSkipGroup unlock=$diagSkipUnlock " +
            "parseFail=$diagSkipParseFail peerFail=$diagSkipPeerFail blank=$diagSkipBlank")
        Log.i("ZCHAT_DIAG", "Conversations: ${messagesByPeer.size}, Pending msgs: ${pendingMsgs.size}")
        messagesByPeer.forEach { (peer, msgs) ->
            Log.d("ZCHAT_DIAG", "  Conv ${peer.redactAddress()}: ${msgs.size} msgs (out=${msgs.count { it.isOutgoing }}, in=${msgs.count { !it.isOutgoing }})")
        }

        // Track confirmed outgoing messages for deduplication with pending messages.
        // Key: peer address + full content hash, Value: list of confirmed messages.
        // Include unmined txs (txId != null) to suppress temporary duplicate rows.
        val confirmedOutgoingByContent = mutableMapOf<String, MutableList<ChatMessage>>()
        messagesByPeer.forEach { (peer, msgs) ->
            msgs.filter { it.isOutgoing && it.txId != null }.forEach { msg ->
                // Use SHA-256 hash of FULL content to avoid truncation collisions
                val contentHash = msg.text.toByteArray().let { bytes ->
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .take(16)
                        .joinToString("") { "%02x".format(it) }
                }
                val contentKey = "$peer|$contentHash"
                confirmedOutgoingByContent.getOrPut(contentKey) { mutableListOf() }.add(msg)
            }
        }

        // Add pending messages that haven't been confirmed yet
        // Use content-based matching for deduplication
        val pendingToRemove = mutableListOf<String>()
        var pendingSuppressedByUnmined = 0
        for (pendingMsg in pendingMsgs) {
            // Use same hash algorithm for pending messages
            val contentHash = pendingMsg.text.toByteArray().let { bytes ->
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
            }
            val contentKey = "${pendingMsg.peerAddress}|$contentHash"
            val matchingConfirmed = confirmedOutgoingByContent[contentKey]

            if (matchingConfirmed != null && matchingConfirmed.isNotEmpty()) {
                val matched = matchingConfirmed.removeAt(0)
                if (matched.minedHeight != null) {
                    // Mined confirmed match: remove pending from state + persistence.
                    pendingToRemove.add(pendingMsg.id)
                    Log.d("ZCHAT_DEDUP", "Pending ${pendingMsg.id} matched mined confirmed message for ${pendingMsg.peerAddress.redactAddress()}")
                } else {
                    // Unmined confirmed match: suppress duplicate in UI but keep pending persisted.
                    // This avoids temporary double rows while preventing premature permanent removal.
                    pendingSuppressedByUnmined++
                    Log.d("ZCHAT_DEDUP", "Pending ${pendingMsg.id} matched unmined tx, suppressing duplicate for ${pendingMsg.peerAddress.redactAddress()}")
                }
            } else if (pendingMsg.id !in confirmedIds) {
                // No matching confirmed message, show pending
                messagesByPeer.getOrPut(pendingMsg.peerAddress) { mutableListOf() }.add(pendingMsg)
            }
        }

        // Remove pending messages from persistence only when their match is mined.
        if (pendingToRemove.isNotEmpty()) {
            val removedFromPending = mutableSetOf<String>()
            pendingMessages.update { current ->
                val filtered = current.filter { it.id !in pendingToRemove }
                current.filter { it.id in pendingToRemove }.forEach { removedFromPending.add(it.id) }
                filtered
            }
            // Also remove from preferences to keep persistence in sync
            if (removedFromPending.isNotEmpty()) {
                zchatPreferences.removePendingMessages(removedFromPending)
            }
        }

        val pendingShown = pendingMsgs.size - pendingToRemove.size - pendingSuppressedByUnmined
        Log.i(
            "ZCHAT_DIAG",
            "Dedup: minedRemoved=${pendingToRemove.size} unminedSuppressed=$pendingSuppressedByUnmined pendingShown=$pendingShown"
        )

        // Convert to Conversation objects (only include conversations with visible messages)
        return messagesByPeer
            .filter { (_, messages) -> messages.isNotEmpty() }
            .map { (peerAddress, messages) ->
                // Sort messages: block height (primary), tx index within block (secondary),
                // timestamp (tertiary), then ID for deterministic stability.
                // Pending messages (null height) sort last via Long.MAX_VALUE.
                val sortedMessages = messages.sortedWith(
                    compareBy<ChatMessage> { it.minedHeight ?: Long.MAX_VALUE }
                        .thenBy { it.txIndex ?: Int.MAX_VALUE }
                        .thenBy { it.timestamp }
                        .thenBy { it.txId?.txIdString() ?: it.id }
                )
                // Look up nickname (from preferences) or contact name (from contact book)
                // Nickname takes priority over contact book name
                val nickname = zchatPreferences.getNickname(peerAddress)
                val contact = contactBook.getContact(peerAddress)
                val displayContactName = nickname ?: contact?.name
                Conversation(
                    peerAddress = peerAddress,
                    messages = sortedMessages,
                    lastMessage = messages.maxByOrNull { it.timestamp },
                    contactName = displayContactName
                )
            }.sortedByDescending { it.lastMessage?.timestamp }
    }

    /**
     * Extract just the message content from a ZMSG formatted memo.
     * For chunked messages, this extracts from a single chunk (use reassembleChunks for full message).
     */
    private fun extractMessageContent(memo: String): String {
        // Use constants from ZMSGConstants for consistency
        val prefixV4 = ZMSGConstants.Prefixes.V4       // "ZMSG|v4|"
        val prefixV4C = ZMSGConstants.Prefixes.V4C     // "ZMSG|v4c|"
        val prefixV3 = ZMSGConstants.Prefixes.V3       // "ZMSG|v3|"
        val prefixV3C = ZMSGConstants.Prefixes.V3C     // "ZMSG|v3c|"
        val prefixV2 = ZMSGConstants.Prefixes.V2       // "ZMSG|v2|"
        val initMarker = ZMSGConstants.Markers.INIT    // "INIT|"
        val contMarker = ZMSGConstants.Markers.CONT    // "CONT|"
        val refMarker = ZMSGConstants.Markers.REF      // "REF|"
        val replyMarker = ZMSGConstants.Markers.REPLY  // "RPL|"
        val hashLen = ZMSGConstants.HASH_LENGTH        // 12
        val hashLenNew = ZMSGConstants.HASH_LENGTH_NEW // 16

        // Helper for |INIT| marker (includes pipes)
        val initWithPipes = "|${initMarker.dropLast(1)}|"  // "|INIT|"
        val contWithPipes = "|${contMarker.dropLast(1)}|"  // "|CONT|"
        val initMarkerLen = initWithPipes.length           // 6
        val contMarkerLen = contWithPipes.length           // 6

        // v3 combined prefixes
        val prefixV3Rpl = prefixV3 + replyMarker       // "ZMSG|v3|RPL|"
        val prefixV3Init = prefixV3 + initMarker       // "ZMSG|v3|INIT|"
        val prefixV3Ref = prefixV3 + refMarker         // "ZMSG|v3|REF|"

        return when {
            // v4 INIT: ZMSG|v4|<convID>|INIT|<address>|<message>
            memo.startsWith(prefixV4) && !memo.startsWith(prefixV4C) && memo.contains(initWithPipes) -> {
                val initIndex = memo.indexOf(initWithPipes)
                val afterInit = memo.substring(initIndex + initMarkerLen)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // v4 reply: ZMSG|v4|<convID>|<hash>|<message> (new) or ZMSG|v4|<convID>|<message> (legacy)
            memo.startsWith(prefixV4) && !memo.startsWith(prefixV4C) -> {
                val parts = memo.split("|", limit = 5)
                when {
                    // New format with hash: check if parts[3] is 12 or 16-char hex (legacy/new hash)
                    parts.size >= 5 && (parts[3].length == hashLen || parts[3].length == hashLenNew) && parts[3].all { it in '0'..'9' || it in 'a'..'f' } -> parts[4]
                    // Legacy format without hash
                    parts.size >= 4 -> parts[3]
                    else -> memo
                }
            }
            // v4 Chunked INIT: ZMSG|v4c|1/N|<convID>|INIT|<address>|<message>
            memo.startsWith(prefixV4C) && memo.contains(initWithPipes) -> {
                val initIndex = memo.indexOf(initWithPipes)
                val afterInit = memo.substring(initIndex + initMarkerLen)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // v4 Chunked CONT: ZMSG|v4c|M/N|CONT|<message>
            memo.startsWith(prefixV4C) && memo.contains(contWithPipes) -> {
                val contIndex = memo.indexOf(contWithPipes)
                memo.substring(contIndex + contMarkerLen)
            }
            // v4 Chunked reply: ZMSG|v4c|1/N|<convID>|<hash>|<message> (new) or ZMSG|v4c|1/N|<convID>|<message> (legacy)
            memo.startsWith(prefixV4C) -> {
                val parts = memo.split("|", limit = 6)
                when {
                    // New format with hash: check if parts[4] is 12 or 16-char hex (legacy/new hash)
                    parts.size >= 6 && (parts[4].length == hashLen || parts[4].length == hashLenNew) && parts[4].all { it in '0'..'9' || it in 'a'..'f' } -> parts[5]
                    // Legacy format without hash
                    parts.size >= 5 -> parts[4]
                    else -> memo
                }
            }
            // Chunked INIT: ZMSG|v3c|1/N|INIT|<address>|<message>
            memo.startsWith(prefixV3C) && memo.contains(initWithPipes) -> {
                val initIndex = memo.indexOf(initWithPipes)
                val afterInit = memo.substring(initIndex + initMarkerLen)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // Chunked CONT: ZMSG|v3c|M/N|CONT|<message>
            memo.startsWith(prefixV3C) && memo.contains(contWithPipes) -> {
                val contIndex = memo.indexOf(contWithPipes)
                memo.substring(contIndex + contMarkerLen)
            }
            // Chunked reply first: ZMSG|v3c|1/N|<hash>|<message>
            memo.startsWith(prefixV3C) -> {
                val parts = memo.split("|", limit = 5)
                if (parts.size >= 5) parts[4] else memo
            }
            // RPL with INIT: ZMSG|v3|RPL|<txid>|INIT|<address>|<message>
            memo.startsWith(prefixV3Rpl) && memo.contains(initWithPipes) -> {
                val initIndex = memo.indexOf(initWithPipes)
                val afterInit = memo.substring(initIndex + initMarkerLen)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // RPL with hash: ZMSG|v3|RPL|<txid>|<hash>|<message>
            memo.startsWith(prefixV3Rpl) -> {
                val parts = memo.split("|", limit = 6)
                if (parts.size >= 6) parts[5] else memo
            }
            // Regular INIT: ZMSG|v3|INIT|<address>|<message>
            memo.startsWith(prefixV3Init) -> {
                val parts = memo.split("|", limit = 5)
                if (parts.size >= 5) parts[4] else memo
            }
            // REF with INIT: ZMSG|v3|REF|<txid>|INIT|<address>|<message>
            memo.startsWith(prefixV3Ref) && memo.contains(initWithPipes) -> {
                val initIndex = memo.indexOf(initWithPipes)
                val afterInit = memo.substring(initIndex + initMarkerLen)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex != -1) afterInit.substring(sepIndex + 1) else afterInit
            }
            // REF with hash: ZMSG|v3|REF|<txid>|<hash>|<message>
            memo.startsWith(prefixV3Ref) -> {
                val parts = memo.split("|", limit = 6)
                if (parts.size >= 6) parts[5] else memo
            }
            // Regular reply with hash: ZMSG|v3|<hash>|<message>
            memo.startsWith(prefixV3) -> {
                val parts = memo.split("|", limit = 4)
                if (parts.size >= 4) parts[3] else memo
            }
            // Legacy v2: ZMSG|v2|<address>|<message>
            memo.startsWith(prefixV2) -> {
                val parts = memo.split("|", limit = 4)
                if (parts.size >= 4) parts[3] else memo
            }
            else -> memo
        }
    }

    /**
     * Extract conversation ID from a memo.
     * Used to restore convId mappings from outgoing messages after wallet restore.
     * Returns null if no convId is found (v2/v3 messages, non-ZMSG memos).
     *
     * Handles both single and chunked memos. For chunked memos, checks each line
     * to find the first chunk (which contains the convId).
     */
    private fun extractConvIdFromMemo(memo: String): String? {
        // Handle multi-line memos (chunked messages joined with \n)
        // Check each line to find the convId
        val prefixV4 = ZMSGConstants.Prefixes.V4     // "ZMSG|v4|"
        val prefixV4C = ZMSGConstants.Prefixes.V4C   // "ZMSG|v4c|"
        val contMarker = ZMSGConstants.Markers.CONT  // "CONT"
        val convIdLength = ZMSGConstants.CONV_ID_LENGTH  // 8
        val convIdChars = ZMSGConstants.CONV_ID_CHARS    // "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        // Helper to validate convId format (length + character set)
        fun isValidConvId(convId: String): Boolean {
            return convId.length == convIdLength && convId.all { it in convIdChars }
        }

        for (line in memo.split("\n")) {
            val trimmedLine = line.trim()
            when {
                // v4 single memo: ZMSG|v4|<convID>|...
                trimmedLine.startsWith(prefixV4) && !trimmedLine.startsWith(prefixV4C) -> {
                    val parts = trimmedLine.split("|", limit = 4)
                    if (parts.size >= 3) {
                        val candidate = parts[2]
                        if (isValidConvId(candidate)) return candidate
                    }
                }
                // v4 chunked first chunk: ZMSG|v4c|1/N|<convID>|...
                // Skip CONT chunks (they don't have convId)
                trimmedLine.startsWith(prefixV4C) -> {
                    val parts = trimmedLine.split("|", limit = 5)
                    // parts[0]=ZMSG, parts[1]=v4c, parts[2]=M/N, parts[3]=convID or CONT
                    if (parts.size >= 4 && parts[3] != contMarker) {
                        val candidate = parts[3]
                        if (isValidConvId(candidate)) return candidate
                    }
                    // If this is a CONT chunk, continue checking other lines
                }
            }
        }
        // v3 and v2 don't have convId, or no valid convId found
        return null
    }

    /**
     * Resolve the real peer address for an outgoing transaction.
     *
     * Multi-layered fallback to prevent messages from silently disappearing:
     * 1. tx.recipient?.address (fast path - works when SDK has single/correct recipient)
     * 2. All recipients from SDK, filtering out the platform fee address
     * 3. Conversation ID from memo → peer address lookup in preferences
     * 4. Content-match against pending messages (last resort)
     *
     * Returns null only if ALL strategies fail (should be extremely rare).
     */
    private suspend fun resolveOutgoingPeerAddress(
        tx: Transaction,
        memos: List<String>,
        pendingMsgs: List<ChatMessage>
    ): String? {
        val platformFee = ZMSGConstants.PLATFORM_FEE_ADDRESS

        // Strategy 1: Direct recipient from SDK (most common case)
        val directRecipient = (tx as? co.electriccoin.zcash.ui.common.repository.SendTransaction)?.recipient?.address
        if (directRecipient != null && directRecipient != platformFee) {
            return directRecipient
        }

        // Strategy 2: Get ALL recipients and filter out the platform fee address
        try {
            val allRecipients = transactionRepository.getAllRecipientAddresses(tx)
            val nonFeeRecipient = allRecipients.firstOrNull { it != platformFee }
            if (nonFeeRecipient != null) {
                Log.d("ZCHAT_RESOLVE", "Resolved via all-recipients for tx ${tx.id.txIdString()}: ${nonFeeRecipient.redactAddress()}")
                return nonFeeRecipient
            }
        } catch (e: Exception) {
            Log.w("ZCHAT_RESOLVE", "getAllRecipientAddresses failed for tx ${tx.id.txIdString()}: ${e.message}")
        }

        // Strategy 3: Extract convId from memo → look up peer address
        val memoText = memos.joinToString("\n").trim()
        val convId = extractConvIdFromMemo(memoText)
        if (convId != null) {
            val peerFromConvId = zchatPreferences.getPeerByConversationId(convId)
            if (peerFromConvId != null) {
                Log.d("ZCHAT_RESOLVE", "Resolved via convId for tx ${tx.id.txIdString()}: ${peerFromConvId.redactAddress()}")
                return peerFromConvId
            }
        }

        // Strategy 4: Match against pending messages by content + uniqueness constraint
        // Only use this fallback if exactly ONE pending message matches (avoids misrouting
        // when multiple conversations have identical message content like "hi")
        val displayText = try {
            val hasChunked = memos.any { ZMSGProtocol.isChunkedMemo(it) }
            if (hasChunked) {
                ZMSGProtocol.reassembleChunks(memos, addressCache)?.message
                    ?: extractMessageContent(memoText)
            } else {
                extractMessageContent(memoText)
            }
        } catch (e: Exception) { null }

        if (displayText != null) {
            val contentHash = displayText.toByteArray().let { bytes ->
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
            }
            val allMatching = pendingMsgs.filter { pending ->
                val pendingHash = pending.text.toByteArray().let { bytes ->
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .take(16)
                        .joinToString("") { "%02x".format(it) }
                }
                pendingHash == contentHash
            }
            if (allMatching.size == 1) {
                // Unique match - safe to use
                Log.d("ZCHAT_RESOLVE", "Resolved via unique pending match for tx ${tx.id.txIdString()}: ${allMatching[0].peerAddress.redactAddress()}")
                return allMatching[0].peerAddress
            } else if (allMatching.size > 1) {
                // Ambiguous: multiple pending messages with same content across different peers
                // Check if they all point to the same peer (still safe)
                val distinctPeers = allMatching.map { it.peerAddress }.distinct()
                if (distinctPeers.size == 1) {
                    Log.d("ZCHAT_RESOLVE", "Resolved via pending match (${allMatching.size} matches, same peer): ${distinctPeers[0].redactAddress()}")
                    return distinctPeers[0]
                }
                Log.w("ZCHAT_RESOLVE", "AMBIGUOUS pending match for tx ${tx.id.txIdString()}: ${allMatching.size} matches across ${distinctPeers.size} peers, skipping")
            }
        }

        // All strategies exhausted
        Log.e("ZCHAT_RESOLVE", "FAILED to resolve peer for outgoing tx ${tx.id.txIdString()}, " +
            "directRecipient=${directRecipient?.redactAddress()}, memoLen=${memoText.length}, convId=$convId")
        return null
    }

    fun getConversation(peerAddress: String): Conversation? {
        return when (val state = _chatListState.value) {
            is ChatListState.Success -> state.conversations.find { it.peerAddress == peerAddress }
            else -> null
        }
    }

    // ==========================================
    // DRAFT MESSAGE FUNCTIONS
    // ==========================================

    /**
     * Get the draft for a peer address.
     */
    fun getDraft(peerAddress: String): String? {
        return zchatPreferences.getDraft(peerAddress)
    }

    /**
     * Save a draft for a peer address.
     * Called when message text changes in the chat detail view.
     */
    fun saveDraft(peerAddress: String, draft: String) {
        zchatPreferences.setDraft(peerAddress, draft)
    }

    /**
     * Clear the draft for a peer address.
     * Called when a message is successfully sent.
     */
    fun clearDraft(peerAddress: String) {
        zchatPreferences.clearDraft(peerAddress)
    }

    // ==========================================
    // E2E ENCRYPTION FUNCTIONS
    // ==========================================

    /**
     * Check if E2E encryption is enabled for a peer.
     */
    fun isE2EEnabled(peerAddress: String): Boolean {
        return zchatPreferences.isE2EEnabled(peerAddress)
    }

    /**
     * Enable or disable E2E encryption for a peer.
     * When enabling:
     * - Generates keys if not already present
     * - Sends KEX (Key Exchange) message to peer
     * New keys use HKDF (V2) for key derivation.
     */
    /**
     * Toggle mute/unmute for a conversation.
     * When muted, no notifications will be shown for messages from this peer.
     */
    fun toggleMuteConversation(peerAddress: String) {
        if (zchatPreferences.isConversationMuted(peerAddress)) {
            zchatPreferences.unmuteConversation(peerAddress)
        } else {
            zchatPreferences.muteConversation(peerAddress)
        }
        // Trigger conversation list refresh
        refresh()
    }

    fun setE2EEnabled(peerAddress: String, enabled: Boolean) {
        zchatPreferences.setE2EEnabled(peerAddress, enabled)
        if (enabled) {
            // Generate our key pair if not already present
            if (zchatPreferences.getE2EOurPublicKey(peerAddress) == null) {
                val keyPair = E2EEncryption.generateKeyPair()
                zchatPreferences.setE2EOurKeys(peerAddress, keyPair.publicKey, keyPair.privateKey)
                // Mark as V2 (HKDF) for new keys
                zchatPreferences.setE2EKeyVersion(peerAddress, E2EKeyVersion.V2.value)
            }

            // Send KEX message to initiate key exchange
            val ourAddress = _currentUserAddress.value
            if (ourAddress != null) {
                sendKEXMessage(peerAddress, ourAddress)
            } else {
                Log.w("ChatViewModel", "Cannot send KEX - user address not available yet")
            }
        }
    }

    /**
     * Get our public key for E2E encryption (to send to peer).
     */
    fun getE2EOurPublicKey(peerAddress: String): String? {
        return zchatPreferences.getE2EOurPublicKey(peerAddress)
    }

    /**
     * Store the peer's public key when received.
     */
    fun setE2EPeerPublicKey(peerAddress: String, publicKey: String) {
        zchatPreferences.setE2EPeerPublicKey(peerAddress, publicKey)
    }

    /**
     * Check if E2E key exchange is complete.
     */
    fun isE2EKeyExchangeComplete(peerAddress: String): Boolean {
        return zchatPreferences.isE2EKeyExchangeComplete(peerAddress)
    }

    /**
     * Derive the shared encryption key for a peer.
     * Uses the stored key version (V1 for legacy, V2 for HKDF).
     * @return The derived shared key, or null if key exchange is not complete.
     */
    fun getE2ESharedKey(peerAddress: String): ByteArray? {
        val ourPrivateKey = zchatPreferences.getE2EPrivateKey(peerAddress) ?: return null
        val peerPublicKey = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
        val keyVersionInt = zchatPreferences.getE2EKeyVersion(peerAddress)
        val keyVersion = E2EKeyVersion.fromValue(keyVersionInt)

        return try {
            E2EEncryption.deriveSharedSecret(ourPrivateKey, peerPublicKey, keyVersion)
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to derive shared key for ${peerAddress.redactAddress()}", e)
            null
        }
    }

    /**
     * Get the E2E key derivation version for a peer.
     * @return Key version (1 = legacy SHA-256, 2 = HKDF)
     */
    fun getE2EKeyVersion(peerAddress: String): E2EKeyVersion {
        return E2EKeyVersion.fromValue(zchatPreferences.getE2EKeyVersion(peerAddress))
    }

    /** True if the peer's E2E public key has changed since last acknowledged by the user. */
    fun isE2EKeyChanged(peerAddress: String): Boolean =
        zchatPreferences.isE2EKeyChanged(peerAddress)

    /** User dismissed the key-changed banner — clear the flag. */
    fun dismissE2EKeyChanged(peerAddress: String) {
        zchatPreferences.setE2EKeyChanged(peerAddress, false)
    }

    /**
     * Compute a human-readable safety number for visual key verification.
     * Returns 32 hex chars (16 bytes of SHA-256 of the peer's pubkey) or null if
     * E2E is not set up for this peer.
     *
     * Both parties compute the SAME safety number because they both hash the
     * SAME peer pubkey. Alice hashes Bob's key; Bob hashes Alice's key. So Alice
     * and Bob will see DIFFERENT numbers (each sees their own peer's hash). To
     * get a shared number both can compare, hash the SORTED pair of pubkeys:
     * SHA-256(min(ourPub, peerPub) || max(ourPub, peerPub)).take(16) → 32 hex.
     */
    fun computeSafetyNumber(peerAddress: String): String? {
        val ourPub = zchatPreferences.getE2EOurPublicKey(peerAddress) ?: return null
        val peerPub = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
        val sorted = if (ourPub <= peerPub) ourPub + peerPub else peerPub + ourPub
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(sorted.toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Get or create a ratcheted [E2EMessageProcessor] for a peer conversation. Returns null
     * if E2E is not enabled or keys are not yet exchanged. The processor is cached per
     * (peerAddress, convId) pair so HKDF root derivation runs only once per conversation.
     */
    private suspend fun getOrCreateMessageProcessor(
        peerAddress: String,
        convId: String,
    ): co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor? {
        val cacheKey = "$peerAddress:$convId"
        messageProcessors[cacheKey]?.let { return it }

        val sharedKey = getE2ESharedKey(peerAddress) ?: return null
        if (!zchatPreferences.isE2EEnabled(peerAddress)) return null

        val ourPub = zchatPreferences.getE2EOurPublicKey(peerAddress) ?: return null
        val peerPub = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
        val isLower = ourPub < peerPub // lexicographic comparison of Base64-encoded pubkeys

        // Root derivation: HKDF(shared_secret, salt, info). KEX txid context is omitted for
        // now (txids not stored during KEX) — root uniqueness is maintained by the shared
        // secret being unique per conversation. TODO: store KEX/KEXACK txids for full spec.
        val rootKey = co.electriccoin.zcash.ui.screen.chat.crypto.HKDF.deriveKey(
            ikm = sharedKey,
            salt = "ZCHAT_RATCHET_ROOT_V1".toByteArray(Charsets.UTF_8),
            info = "root".toByteArray(Charsets.UTF_8),
            length = 32,
        )

        val processor = co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor(
            rootKey = rootKey,
            convId = convId,
            isLower = isLower,
            store = ratchetStateStore,
        )
        messageProcessors[cacheKey] = processor
        return processor
    }

    /**
     * Try to decrypt an E2E-encrypted message content. If the content is not E2E-encrypted
     * (no E2E1: or E2E: prefix), returns it unchanged. Errors are logged and the raw content
     * is returned (fail-open for display — the user sees the encrypted blob, not a crash).
     */
    private suspend fun tryDecryptMessage(content: String, peerAddress: String, convId: String?): String {
        if (convId == null) return content
        if (!co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat.isRatcheted(content)) return content
        return try {
            getOrCreateMessageProcessor(peerAddress, convId)?.decryptIncoming(content) ?: content
        } catch (e: Exception) {
            Log.w("ZCHAT_E2E", "Ratchet decrypt failed for ${peerAddress.redactAddress()}: ${e.javaClass.simpleName}")
            content
        }
    }

    // ==========================================
    // KEX (Key Exchange) PROTOCOL
    // ==========================================

    /**
     * Handle incoming KEX or KEXACK messages.
     * - KEX: Verify signature, store peer pubkey, auto-enable E2E, send KEXACK
     * - KEXACK: Verify signature, store peer pubkey, mark exchange complete
     *
     * @param memoText The full memo containing the KEX message
     * @param ourAddress Our Zcash address (for sending KEXACK)
     */
    private fun handleKEXMessage(memoText: String, ourAddress: String) {
        viewModelScope.launch {
            try {
                when {
                    ZMSGProtocol.isKEXMessage(memoText) -> {
                        val parsed = ZMSGProtocol.parseKEXMessage(memoText) ?: return@launch
                        val (convId, kexPayload) = parsed

                        // Get sender address from conversation mapping or parsed message
                        val senderAddress = zchatPreferences.getPeerByConversationId(convId)
                        if (senderAddress == null) {
                            Log.w("KEX", "Cannot process KEX - unknown conversation ID: $convId")
                            return@launch
                        }

                        // Verify and extract public key
                        val peerPublicKey = E2EEncryption.parseKEXPayload(kexPayload, senderAddress)
                        if (peerPublicKey == null) {
                            Log.e("KEX", "KEX signature verification FAILED for ${senderAddress.redactAddress()}")
                            return@launch
                        }

                        Log.d("KEX", "KEX verified from ${senderAddress.redactAddress()} - storing pubkey")

                        // Detect key change: if peer already had a stored pubkey and the
                        // new one differs, flag it for the Key-Changed banner in ChatDetail.
                        val previousPubkey = zchatPreferences.getE2EPeerPublicKey(senderAddress)
                        if (previousPubkey != null && previousPubkey != peerPublicKey) {
                            Log.w("KEX", "PEER KEY CHANGED for ${senderAddress.redactAddress()} — possible reinstall or MITM")
                            zchatPreferences.setE2EKeyChanged(senderAddress, true)
                            // Invalidate cached message processor so a new one is built with the new key
                            messageProcessors.keys.removeAll { it.startsWith(senderAddress) }
                        }

                        // Store peer's public key
                        zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)

                        // Auto-enable E2E for this peer if not already
                        if (!zchatPreferences.isE2EEnabled(senderAddress)) {
                            // Generate our keys if needed
                            if (zchatPreferences.getE2EOurPublicKey(senderAddress) == null) {
                                val keyPair = E2EEncryption.generateKeyPair()
                                zchatPreferences.setE2EOurKeys(senderAddress, keyPair.publicKey, keyPair.privateKey)
                                zchatPreferences.setE2EKeyVersion(senderAddress, E2EKeyVersion.V2.value)
                            }
                            zchatPreferences.setE2EEnabled(senderAddress, true)
                        }

                        // Send KEXACK in response
                        sendKEXAckMessage(senderAddress, ourAddress, convId)
                    }

                    ZMSGProtocol.isKEXAckMessage(memoText) -> {
                        val parsed = ZMSGProtocol.parseKEXAckMessage(memoText) ?: return@launch
                        val (convId, kexAckPayload) = parsed

                        // Get sender address from conversation mapping
                        val senderAddress = zchatPreferences.getPeerByConversationId(convId)
                        if (senderAddress == null) {
                            Log.w("KEX", "Cannot process KEXACK - unknown conversation ID: $convId")
                            return@launch
                        }

                        // Verify and extract public key
                        val peerPublicKey = E2EEncryption.parseKEXAckPayload(kexAckPayload, senderAddress)
                        if (peerPublicKey == null) {
                            Log.e("KEX", "KEXACK signature verification FAILED for ${senderAddress.redactAddress()}")
                            return@launch
                        }

                        Log.d("KEX", "KEXACK verified from ${senderAddress.redactAddress()} - key exchange complete!")

                        // Detect key change (same logic as KEX path above)
                        val prevPub = zchatPreferences.getE2EPeerPublicKey(senderAddress)
                        if (prevPub != null && prevPub != peerPublicKey) {
                            Log.w("KEX", "PEER KEY CHANGED via KEXACK for ${senderAddress.redactAddress()}")
                            zchatPreferences.setE2EKeyChanged(senderAddress, true)
                            messageProcessors.keys.removeAll { it.startsWith(senderAddress) }
                        }

                        // Store peer's public key - key exchange is now complete
                        zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)

                        // Log completion
                        if (zchatPreferences.isE2EKeyExchangeComplete(senderAddress)) {
                            Log.d("KEX", "E2E key exchange COMPLETE with ${senderAddress.redactAddress()}")
                        }
                    }

                    // BACKWARD COMPATIBILITY: Handle legacy E2E_INIT format (unsigned)
                    memoText.contains("E2E_INIT:") -> {
                        Log.w("KEX", "Received legacy E2E_INIT (unsigned) - accepting for backward compat")

                        // Extract public key from E2E_INIT payload
                        val peerPublicKey = E2EEncryption.parseE2EInitPayload(memoText)
                        if (peerPublicKey == null) {
                            Log.e("KEX", "Failed to parse legacy E2E_INIT payload")
                            return@launch
                        }

                        // Try to determine sender from message context
                        // Parse the message to get sender info
                        val parsed = ZMSGProtocol.parseMemo(memoText, addressCache)
                        val senderAddress = parsed?.senderAddress
                        if (senderAddress == null) {
                            Log.w("KEX", "Cannot process E2E_INIT - no sender address in message")
                            return@launch
                        }

                        Log.d("KEX", "Legacy E2E_INIT from ${senderAddress.redactAddress()} - storing pubkey (UNSIGNED)")

                        // Store peer's public key (without signature verification - legacy)
                        zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)

                        // Auto-enable E2E
                        if (!zchatPreferences.isE2EEnabled(senderAddress)) {
                            if (zchatPreferences.getE2EOurPublicKey(senderAddress) == null) {
                                val keyPair = E2EEncryption.generateKeyPair()
                                zchatPreferences.setE2EOurKeys(senderAddress, keyPair.publicKey, keyPair.privateKey)
                                zchatPreferences.setE2EKeyVersion(senderAddress, E2EKeyVersion.V2.value)
                            }
                            zchatPreferences.setE2EEnabled(senderAddress, true)
                        }

                        // Note: Don't send KEXACK for legacy format - they wouldn't understand it
                        // The old format expected the other side to also send E2E_INIT
                    }
                }
            } catch (e: Exception) {
                Log.e("KEX", "Error handling KEX message", e)
            }
        }
    }

    /**
     * Send a KEX (Key Exchange) message to initiate E2E encryption.
     * Called when user enables E2E for a conversation.
     *
     * @param peerAddress The peer's Zcash address
     * @param ourAddress Our Zcash address
     */
    fun sendKEXMessage(peerAddress: String, ourAddress: String) {
        viewModelScope.launch {
            try {
                // Ensure we have keys
                var ourPublicKey = zchatPreferences.getE2EOurPublicKey(peerAddress)
                var ourPrivateKey = zchatPreferences.getE2EPrivateKey(peerAddress)

                if (ourPublicKey == null || ourPrivateKey == null) {
                    // Generate keys
                    val keyPair = E2EEncryption.generateKeyPair()
                    zchatPreferences.setE2EOurKeys(peerAddress, keyPair.publicKey, keyPair.privateKey)
                    zchatPreferences.setE2EKeyVersion(peerAddress, E2EKeyVersion.V2.value)
                    ourPublicKey = keyPair.publicKey
                    ourPrivateKey = keyPair.privateKey
                }

                // Get or create conversation ID - atomic at SharedPreferences level
                val (convId, _) = convIdMutex.withLock {
                    zchatPreferences.getOrCreateConversationId(peerAddress)
                }

                // Create signed KEX payload
                val kexPayload = E2EEncryption.createKEXPayload(ourAddress, ourPublicKey, ourPrivateKey)

                // Create full KEX message
                val kexMessage = ZMSGProtocol.createV4KEXMessage(convId, ourAddress, kexPayload)

                Log.d("KEX", "Sending KEX to ${peerAddress.redactAddress()} convId=${convId.redactConvId()}")

                // Send via transaction
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = ourAddress,
                    message = kexMessage,
                    isFirstMessage = false,
                    amountPerOutput = Zatoshi(1000L),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )

            } catch (e: Exception) {
                Log.e("KEX", "Failed to send KEX message", e)
            }
        }
    }

    /**
     * Send a KEXACK (Key Exchange Acknowledgment) in response to a KEX message.
     */
    private suspend fun sendKEXAckMessage(peerAddress: String, ourAddress: String, convId: String) {
        try {
            val ourPublicKey = zchatPreferences.getE2EOurPublicKey(peerAddress) ?: return
            val ourPrivateKey = zchatPreferences.getE2EPrivateKey(peerAddress) ?: return

            // Create signed KEXACK payload
            val kexAckPayload = E2EEncryption.createKEXAckPayload(ourAddress, ourPublicKey, ourPrivateKey)

            // Create full KEXACK message
            val kexAckMessage = ZMSGProtocol.createV4KEXAckMessage(convId, ourAddress, kexAckPayload)

            Log.d("KEX", "Sending KEXACK to ${peerAddress.redactAddress()} convId=${convId.redactConvId()}")

            // Send via transaction
            createChunkedMessageProposal(
                destinationAddress = peerAddress,
                senderAddress = ourAddress,
                message = kexAckMessage,
                isFirstMessage = false,
                amountPerOutput = Zatoshi(1000L),
                directSubmit = true,
                skipNavigation = true,
                rawMemo = true
            )

        } catch (e: Exception) {
            Log.e("KEX", "Failed to send KEXACK", e)
        }
    }

    /**
     * Get peer's E2E public key if available (from completed key exchange).
     * Used by GroupViewModel for ECIES encryption.
     */
    fun getPeerE2EPublicKey(peerAddress: String): String? {
        return zchatPreferences.getE2EPeerPublicKey(peerAddress)
    }

    /**
     * Check if we have completed key exchange with a peer.
     * Required for ECIES group key encryption.
     */
    fun hasCompletedKEX(peerAddress: String): Boolean {
        return zchatPreferences.isE2EKeyExchangeComplete(peerAddress)
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
                Log.i("ZCHAT_SYNC", "Manual refresh: starting...")
                synchronizer.refreshTransactions()
                synchronizer.refreshAllBalances()
                Log.i("ZCHAT_SYNC", "Manual refresh: completed")
            } catch (e: Exception) {
                // Log but don't fail - the sync will continue in the background
                Log.w("ZCHAT_SYNC", "Manual refresh failed: ${e.message}")
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
     * 3. Memo contains ZCHAT_DESTROY:<secret_phrase> where phrase hash matches stored hash
     */
    private fun checkForRemoteKill(amountZatoshi: Long, memo: String?, txId: String) {
        // Skip if remote kill not enabled (cheap checks first)
        if (!zchatPreferences.isRemoteKillEnabled()) return
        if (!zchatPreferences.hasRemoteKillPhrase()) return

        // Atomic check-and-add: add() returns false if already present
        // This eliminates the TOCTOU race of separate contains() + add()
        if (!processedKillCheckTxIds.add(txId)) return

        val killAmount = zchatPreferences.getRemoteKillAmount()

        // Check if amount matches
        if (amountZatoshi != killAmount) return

        // Check if memo matches kill signal format
        if (memo == null) return
        val trimmedMemo = memo.trim()
        if (!trimmedMemo.startsWith(ZMSGConstants.REMOTE_KILL_PREFIX)) return

        // Extract phrase from memo and verify against stored hash
        val phraseFromMemo = trimmedMemo.removePrefix(ZMSGConstants.REMOTE_KILL_PREFIX)
        if (zchatPreferences.verifyRemoteKillPhrase(phraseFromMemo)) {
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
            val syncStatus = synchronizer.status.value
            Log.d("ZCHAT_SYNC", "Auto-refresh: status=$syncStatus blockHeight=${_blockHeight.value}")
            synchronizer.refreshTransactions()
            synchronizer.refreshAllBalances()
            Log.d("ZCHAT_SYNC", "Auto-refresh completed: blockHeight=${_blockHeight.value}")
        } catch (e: Exception) {
            // Log but don't fail - the sync will continue in the background
            Log.w("ZCHAT_SYNC", "Auto-refresh failed: ${e.message}")
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
        // Check if funds are in Orchard pool (required for ZCHAT messaging)
        val currentState = _chatListState.value
        if (currentState is ChatListState.Success) {
            val privacyStatus = currentState.privacyStatus
            // Block messaging if no funds in Orchard
            if (privacyStatus.orchardBalance <= Zatoshi(0) &&
                (privacyStatus.saplingBalance > Zatoshi(0) || privacyStatus.transparentBalance > Zatoshi(0))) {
                _sendMessageState.value = SendMessageState.NeedsOrchardShielding(
                    saplingBalance = privacyStatus.saplingBalance,
                    transparentBalance = privacyStatus.transparentBalance
                )
                return
            }
        }

        // Check if user has acknowledged that messages cost ZEC
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            pendingMessage = PendingMessageParams(peerAddress, message, amountZatoshi = amountZatoshi)
            _showCostDisclaimer.value = true
            return
        }

        // If a send is in progress, queue the message — show it as pending immediately
        if (_sendMessageState.value is SendMessageState.Sending) {
            val pendingId = "pending_${System.nanoTime()}"
            val pendingChatMessage = ChatMessage(
                id = pendingId,
                txId = null,
                text = message,
                timestamp = Instant.now(),
                isOutgoing = true,
                peerAddress = peerAddress,
                isPending = true,
                status = MessageStatus.SENDING
            )
            pendingMessages.update { it + pendingChatMessage }
            zchatPreferences.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = pendingId,
                    text = message,
                    timestampMillis = pendingChatMessage.timestamp.toEpochMilli(),
                    peerAddress = peerAddress
                )
            )
            synchronized(messageQueue) {
                messageQueue.add(QueuedMessage(peerAddress, message, amountZatoshi, pendingId))
            }
            Log.d("ZCHAT_SEND", "Message queued (${messageQueue.size} in queue): [${message.length} chars]")
            return
        }

        // User has acknowledged, proceed with sending
        doSendMessage(peerAddress, message, amountZatoshi)
    }

    companion object {
        const val AUTO_REFRESH_INTERVAL_SECONDS = 60
        // Default amount per message output (1000 zatoshi = 0.00001 ZEC)
        const val DEFAULT_MESSAGE_AMOUNT = 1000L
        // Queue retry: wait for previous tx change notes to become spendable.
        // Uses block-height observation to retry only when new blocks are scanned.
        private const val MAX_QUEUE_RETRIES = 4
        private const val QUEUE_RETRY_TIMEOUT_MS = 300_000L // 5 min absolute timeout
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

        // Send the pending message, preserving reply/payment-request context
        pendingMessage?.let { params ->
            pendingMessage = null
            when {
                params.paymentRequestAmount != null ->
                    sendPaymentRequest(params.peerAddress, params.paymentRequestAmount, params.paymentRequestReason)
                params.replyToId != null ->
                    sendReply(params.peerAddress, params.message, params.replyToId, params.amountZatoshi)
                else ->
                    doSendMessage(params.peerAddress, params.message, params.amountZatoshi)
            }
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
     * Called when user dismisses the Orchard shielding warning.
     */
    fun dismissOrchardShieldingWarning() {
        if (_sendMessageState.value is SendMessageState.NeedsOrchardShielding) {
            _sendMessageState.value = SendMessageState.Idle
        }
    }

    /**
     * Internal function to actually send the message.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun doSendMessage(
        peerAddress: String,
        message: String,
        amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT,
        existingPendingId: String? = null,
        retryCount: Int = 0
    ) {
        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            // Hoist pendingId above try so catch block can reference it for cleanup
            val pendingId = existingPendingId ?: "pending_${System.nanoTime()}"
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Add pending message immediately for smooth UX
                // (skip if already created by the message queue)
                if (existingPendingId == null) {
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
                    pendingMessages.update { it + pendingChatMessage }

                    // Persist pending message so it survives navigation
                    zchatPreferences.addPendingMessage(
                        ZchatPreferences.PendingMessageData(
                            id = pendingId,
                            text = message,
                            timestampMillis = pendingChatMessage.timestamp.toEpochMilli(),
                            peerAddress = peerAddress
                        )
                    )
                }

                // ZMSG v4 Protocol: Use conversation IDs for reliable threading.
                // getOrCreateConversationId is atomic at the SharedPreferences level,
                // safe across all VMs/services. The mutex is still held for coordination
                // with validateAndRepairConvIdMappings.
                val (convId, isFirstMessage) = convIdMutex.withLock {
                    zchatPreferences.getOrCreateConversationId(peerAddress)
                }

                // DEBUG: Log send info including sender address for diagnosing threading issues
                Log.d("ZCHAT_V4", "=== Sending v4 message ===")
                Log.d("ZCHAT_V4", "From (senderAddress): ${userAddress.redactAddress()}")
                Log.d("ZCHAT_V4", "To: ${peerAddress.redactAddress()}")
                Log.d("ZCHAT_V4", "Message: [${message.length} chars]")
                Log.d("ZCHAT_V4", "convId: ${convId.redactConvId()}")
                Log.d("ZCHAT_V4", "isFirstMessage: $isFirstMessage")
                Log.d("ZCHAT_V4", "Format: v4 ${if (isFirstMessage) "INIT" else "REPLY"}")
                Log.d("ZCHAT_V4", "Sender hash: ${ZMSGProtocol.generateAddressHash(userAddress)}")

                // E2E ratchet encrypt: if E2E is enabled for this peer, encrypt the
                // message content before it goes into the ZMSG memo. The E2E1: prefix
                // signals the receiver to use the ratchet decrypt path.
                val outgoingMessage = try {
                    getOrCreateMessageProcessor(peerAddress, convId)
                        ?.encryptOutgoing(message)
                        ?: message
                } catch (e: Exception) {
                    Log.w("ZCHAT_E2E", "Ratchet encrypt failed, sending plaintext: ${e.javaClass.simpleName}")
                    message
                }

                // Run proof generation on Default dispatcher so Main stays free
                // for UI recomposition (pending message appears instantly).
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    // Use the chunked message proposal use case with direct submit
                    // skipNavigation = true keeps user on chat screen for smooth messaging flow
                    createChunkedMessageProposal(
                        destinationAddress = peerAddress,
                        senderAddress = userAddress,
                        message = outgoingMessage,
                        isFirstMessage = isFirstMessage,
                        amountPerOutput = Zatoshi(amountZatoshi),
                        directSubmit = true,
                        skipNavigation = true,
                        conversationId = convId  // Pass convID for v4 format
                    )
                }

                // Add to conversation partners for diversified address matching
                addressCache.addConversationPartner(peerAddress)

                _sendMessageState.value = SendMessageState.Success
                // Process next queued message if any
                processNextQueuedMessage()
            } catch (e: Exception) {
                val isInsufficientBalance = e.message?.contains("Insufficient balance") == true ||
                    e is InsufficientFundsException
                val isQueuedMessage = existingPendingId != null

                // If this was a queued message that failed because notes are locked
                // by a previous tx, re-queue it with a delay to retry after confirmation
                if (isInsufficientBalance && isQueuedMessage) {
                    Log.w("ZCHAT_SEND", "Queued message failed: notes locked by previous tx. Will retry after delay.")
                    val retried = synchronized(messageQueue) {
                        // Re-insert at front of queue for retry
                        val retryMsg = QueuedMessage(peerAddress, message, amountZatoshi, pendingId, retryCount = retryCount + 1)
                        if (retryMsg.retryCount <= MAX_QUEUE_RETRIES) {
                            messageQueue.add(0, retryMsg)
                            true
                        } else {
                            false
                        }
                    }
                    if (retried) {
                        _sendMessageState.value = SendMessageState.Success // Reset to allow next send
                        // Wait for a NEW block to be scanned — this ensures change notes
                        // from the previous tx are processed by the Rust backend.
                        val currentHeight = _blockHeight.value ?: 0L
                        Log.d("ZCHAT_SEND", "Waiting for new block (current: $currentHeight) before retry...")
                        viewModelScope.launch {
                            try {
                                kotlinx.coroutines.withTimeout(QUEUE_RETRY_TIMEOUT_MS) {
                                    _blockHeight.first { it != null && it > currentHeight }
                                }
                                Log.d("ZCHAT_SEND", "New block scanned (was $currentHeight, now ${_blockHeight.value}). Retrying queued message.")
                                processNextQueuedMessage()
                            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                                Log.e("ZCHAT_SEND", "Queue retry timeout (${QUEUE_RETRY_TIMEOUT_MS}ms). Marking message as failed.")
                                val queuedMsg = synchronized(messageQueue) {
                                    if (messageQueue.isNotEmpty()) messageQueue.removeAt(0) else null
                                }
                                if (queuedMsg != null) {
                                    pendingMessages.update { current ->
                                        current.map { msg ->
                                            if (msg.id == queuedMsg.pendingId) msg.copy(status = MessageStatus.FAILED, isPending = false, timestamp = java.time.Instant.now()) else msg
                                        }
                                    }
                                    zchatPreferences.removePendingMessages(setOf(queuedMsg.pendingId))
                                }
                                _sendMessageState.value = SendMessageState.Error("Message send timed out waiting for block confirmation")
                            }
                        }
                    } else {
                        // Max retries exceeded — mark as failed
                        Log.e("ZCHAT_SEND", "Queued message exceeded max retries ($MAX_QUEUE_RETRIES)")
                        pendingMessages.update { current ->
                            current.map { msg ->
                                if (msg.id == pendingId) msg.copy(status = MessageStatus.FAILED, isPending = false, timestamp = java.time.Instant.now()) else msg
                            }
                        }
                        zchatPreferences.removePendingMessages(setOf(pendingId))
                        _sendMessageState.value = SendMessageState.Error("Message failed after $MAX_QUEUE_RETRIES retries")
                        processNextQueuedMessage()
                    }
                } else {
                    // Normal failure — mark as FAILED with updated timestamp
                    pendingMessages.update { current ->
                        current.map { msg ->
                            if (msg.id == pendingId) {
                                msg.copy(status = MessageStatus.FAILED, isPending = false, timestamp = java.time.Instant.now())
                            } else {
                                msg
                            }
                        }
                    }
                    // Remove from persistence — failed messages should not survive restart
                    zchatPreferences.removePendingMessages(setOf(pendingId))
                    val errorMessage = when {
                        e.message.isNullOrBlank() && e is InsufficientFundsException ->
                            "Insufficient balance. Please add ZEC to your wallet to send messages."
                        else -> e.message ?: "Failed to send message"
                    }
                    _sendMessageState.value = SendMessageState.Error(errorMessage)
                    // Still process next queued message — one failure shouldn't block the queue
                    processNextQueuedMessage()
                }
            }
        }
    }

    /**
     * Process the next message in the queue, if any.
     * Called after each send completes (success or failure).
     */
    private fun processNextQueuedMessage() {
        val next = synchronized(messageQueue) {
            if (messageQueue.isNotEmpty()) messageQueue.removeAt(0) else null
        }
        if (next != null) {
            Log.d("ZCHAT_SEND", "Processing queued message (${messageQueue.size} remaining, retry=${next.retryCount}): [${next.message.length} chars]")
            doSendMessage(next.peerAddress, next.message, next.amountZatoshi, existingPendingId = next.pendingId, retryCount = next.retryCount)
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
        return ZMSGProtocol.getMaxChunkedMessageLength(isFirstMessageTo(peerAddress), maxChunks)
    }

    /**
     * Get the number of chunks that will be needed for a message.
     * Returns 1 for messages that fit in a single memo.
     */
    fun getChunkCount(message: String, peerAddress: String): Int {
        return ZMSGProtocol.calculateChunkCount(message, isFirstMessageTo(peerAddress))
    }

    /**
     * Get the total cost of sending a message (may include multiple outputs for chunked messages).
     */
    fun getMessageCost(message: String, peerAddress: String): Zatoshi {
        return createChunkedMessageProposal.getTotalCost(message, isFirstMessageTo(peerAddress))
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
            // Update reactive state (atomic)
            hiddenMessages.update { it + messageIds }
        }
    }

    /**
     * Hide a single message from a chat.
     * The message is not deleted from the blockchain, just hidden from the UI.
     */
    fun hideMessage(messageId: String) {
        // Persist to preferences
        zchatPreferences.hideMessage(messageId)
        // Update reactive state (atomic)
        hiddenMessages.update { it + messageId }
    }

    /**
     * Unhide a previously hidden message.
     */
    fun unhideMessage(messageId: String) {
        // Update preferences
        zchatPreferences.unhideMessage(messageId)
        // Update reactive state (atomic)
        hiddenMessages.update { it - messageId }
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

        // Check if user has acknowledged that messages cost ZEC (payment requests also cost a tx)
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            pendingMessage = PendingMessageParams(
                peerAddress = peerAddress,
                message = reason.ifEmpty { "Payment request" },
                paymentRequestAmount = amountZatoshi,
                paymentRequestReason = reason
            )
            _showCostDisclaimer.value = true
            return
        }

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            val pendingId = "pending_${System.nanoTime()}"
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

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
                pendingMessages.update { it + pendingChatMessage }

                // Persist pending message so it survives navigation
                zchatPreferences.addPendingMessage(
                    ZchatPreferences.PendingMessageData(
                        id = pendingId,
                        text = reason.ifEmpty { "Payment request" },
                        timestampMillis = pendingChatMessage.timestamp.toEpochMilli(),
                        peerAddress = peerAddress
                    )
                )

                // Create payment request memo
                val requestMemo = ZMSGProtocol.createPaymentRequest(amountZatoshi, userAddress, reason)

                // Send with minimal amount (just to deliver the request)
                // Note: isFirstMessage doesn't affect rawMemo output
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = requestMemo,
                    isFirstMessage = false,
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )

                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                // Mark pending message as FAILED and remove from persistence
                pendingMessages.update { current ->
                    current.map { msg ->
                        if (msg.id == pendingId) {
                            msg.copy(status = MessageStatus.FAILED, isPending = false, timestamp = java.time.Instant.now())
                        } else {
                            msg
                        }
                    }
                }
                zchatPreferences.removePendingMessages(setOf(pendingId))
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
            pendingMessage = PendingMessageParams(peerAddress, message, replyToId = replyToId, amountZatoshi = amountZatoshi)
            _showCostDisclaimer.value = true
            return
        }

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            val pendingId = "pending_${System.nanoTime()}"
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

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
                pendingMessages.update { it + pendingChatMessage }

                // Persist pending message so it survives navigation
                zchatPreferences.addPendingMessage(
                    ZchatPreferences.PendingMessageData(
                        id = pendingId,
                        text = message,
                        timestampMillis = pendingChatMessage.timestamp.toEpochMilli(),
                        peerAddress = peerAddress
                    )
                )

                // ZMSG v4: Use conversation IDs for reliable threading.
                // getOrCreateConversationId is atomic at the SharedPreferences level.
                val (convId, isFirstMessage) = convIdMutex.withLock {
                    zchatPreferences.getOrCreateConversationId(peerAddress)
                }

                // Use the chunked message proposal use case with v4 format
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = message,
                    isFirstMessage = isFirstMessage,
                    amountPerOutput = Zatoshi(amountZatoshi),
                    directSubmit = true,
                    skipNavigation = true,
                    conversationId = convId
                )

                _sendMessageState.value = SendMessageState.Success
            } catch (e: Exception) {
                // Mark pending message as FAILED and remove from persistence
                pendingMessages.update { current ->
                    current.map { msg ->
                        if (msg.id == pendingId) {
                            msg.copy(status = MessageStatus.FAILED, isPending = false, timestamp = java.time.Instant.now())
                        } else {
                            msg
                        }
                    }
                }
                zchatPreferences.removePendingMessages(setOf(pendingId))
                val errorMessage = when {
                    e.message.isNullOrBlank() && e is InsufficientFundsException ->
                        "Insufficient balance. Please add ZEC to your wallet to send messages."
                    else -> e.message ?: "Failed to send reply"
                }
                _sendMessageState.value = SendMessageState.Error(errorMessage)
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

    /**
     * Compute privacy status from wallet account balances.
     * Determines which pool(s) contain funds and if they're fully shielded.
     */
    private fun computePrivacyStatus(walletAccount: WalletAccount?): PrivacyStatus {
        if (walletAccount == null) {
            return PrivacyStatus.DEFAULT
        }

        val orchardBalance = walletAccount.unified.balance.total
        val saplingBalance = (walletAccount as? ZashiAccount)?.sapling?.balance?.total ?: Zatoshi(0)
        val transparentBalance = walletAccount.transparent.balance

        // Determine pool type based on where funds are
        val poolType = when {
            transparentBalance > Zatoshi(0) && orchardBalance == Zatoshi(0) && saplingBalance == Zatoshi(0) ->
                PoolType.TRANSPARENT
            saplingBalance > Zatoshi(0) && orchardBalance == Zatoshi(0) && transparentBalance == Zatoshi(0) ->
                PoolType.SAPLING
            orchardBalance > Zatoshi(0) && saplingBalance == Zatoshi(0) && transparentBalance == Zatoshi(0) ->
                PoolType.ORCHARD
            orchardBalance > Zatoshi(0) || saplingBalance > Zatoshi(0) || transparentBalance > Zatoshi(0) ->
                PoolType.MIXED
            else ->
                PoolType.ORCHARD // Default to Orchard when no funds
        }

        val isFullyShielded = transparentBalance == Zatoshi(0) &&
            (poolType == PoolType.ORCHARD || (poolType == PoolType.SAPLING && saplingBalance > Zatoshi(0)))

        return PrivacyStatus(
            poolType = poolType,
            orchardBalance = orchardBalance,
            saplingBalance = saplingBalance,
            transparentBalance = transparentBalance,
            isFullyShielded = isFullyShielded
        )
    }

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

    // ==========================================
    // CONTACT NICKNAMES
    // ==========================================

    /**
     * Set a nickname for a contact.
     * The nickname will be displayed instead of the truncated address.
     *
     * @param address The contact's Zcash address
     * @param nickname The nickname to set (empty to clear)
     */
    fun setNickname(address: String, nickname: String) {
        zchatPreferences.setNickname(address, nickname)
        // Trigger refresh to update the conversation list
        loadConversations()
    }

    /**
     * Get the nickname for a contact.
     */
    fun getNickname(address: String): String? {
        return zchatPreferences.getNickname(address)
    }

    /**
     * Get display name for an address (nickname if set, otherwise truncated).
     */
    fun getDisplayName(address: String): String {
        return zchatPreferences.getDisplayName(address)
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
        peerStatuses.update { it + (peerAddress to UserStatus(status)) }
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
                    isFirstMessage = false, // rawMemo ignores this
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
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
                    isFirstMessage = false, // rawMemo ignores this
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
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
                    isFirstMessage = false, // rawMemo ignores this
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
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
                    isFirstMessage = false, // rawMemo ignores this
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
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
                    isFirstMessage = false, // rawMemo ignores this
                    amountPerOutput = Zatoshi(amount),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )
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
                    isFirstMessage = false, // rawMemo ignores this
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true
                )

                // Locally unlock the message immediately (atomic update)
                unlockedMessages.update { it + (lockedMessageTxId to "local") }
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

    /**
     * Load all groups from preferences.
     */
    private fun loadAllGroups(): List<GroupInfo> {
        val groupIds = zchatPreferences.getAllGroupIds()
        return groupIds.mapNotNull { groupId ->
            try {
                val groupInfoJson = zchatPreferences.getGroupInfo(groupId) ?: return@mapNotNull null
                val json = org.json.JSONObject(groupInfoJson)
                GroupInfo(
                    groupId = json.getString("groupId"),
                    name = json.getString("name"),
                    creatorAddress = json.getString("creatorAddress"),
                    createdAt = Instant.ofEpochSecond(json.getLong("createdAt")),
                    adminPolicy = AdminPolicy.valueOf(json.optString("adminPolicy", "CREATOR_ONLY")),
                    currentEpoch = json.optInt("currentEpoch", 0),
                    groupKey = if (json.has("groupKey")) json.getString("groupKey").takeIf { it.isNotEmpty() } else null,
                    isActive = json.optBoolean("isActive", true)
                )
            } catch (e: Exception) {
                Log.e("ZCHAT_GROUP", "Failed to parse group info for $groupId", e)
                null
            }
        }
    }

    // ==========================================
    // GROUP MESSAGE PROCESSING
    // ==========================================

    /**
     * Process an incoming GROUP protocol message.
     * Handles GROUP_INVITE, GROUP_MSG, etc.
     */
    private fun processGroupMessage(memo: String, txId: TransactionId?, timestamp: Instant?) {
        val messageType = ZMSGGroupProtocol.parseMessageType(memo)
        val groupId = ZMSGGroupProtocol.parseGroupId(memo)
        val payload = ZMSGGroupProtocol.parsePayload(memo)

        if (groupId == null || payload == null) {
            Log.w("ZCHAT_GROUP", "Invalid GROUP message: missing groupId or payload")
            return
        }

        Log.d("ZCHAT_GROUP", "Processing GROUP message: type=$messageType, groupId=$groupId")

        when (messageType) {
            GroupMessageType.GROUP_INVITE -> {
                processGroupInvite(groupId, payload, txId, timestamp)
            }
            GroupMessageType.GROUP_MSG -> {
                processGroupMsg(groupId, payload, txId, timestamp)
            }
            GroupMessageType.GROUP_ACCEPT -> {
                processGroupAccept(groupId, payload, txId)
            }
            GroupMessageType.GROUP_LEAVE -> {
                processGroupLeave(groupId, payload, txId)
            }
            else -> {
                Log.d("ZCHAT_GROUP", "Unhandled GROUP message type: $messageType")
            }
        }
    }

    /**
     * Process a GROUP_INVITE message.
     * Creates the group locally and stores the group key.
     * Supports both plaintext group_key (legacy) and enc_key (ECIES encrypted).
     */
    private fun processGroupInvite(groupId: String, payload: String, txId: TransactionId?, timestamp: Instant?) {
        try {
            val json = org.json.JSONObject(payload)
            val groupName = json.getString("name")
            val inviterAddress = json.getString("inviter")

            // Parse member list
            val membersArray = json.getJSONArray("members")
            val memberAddresses = (0 until membersArray.length()).map { membersArray.getString(it) }

            Log.d("ZCHAT_GROUP", "Received GROUP_INVITE for '$groupName' from ${inviterAddress.redactAddress()}")

            // Check if we already have this group
            if (zchatPreferences.getGroupInfo(groupId) != null) {
                Log.d("ZCHAT_GROUP", "Group $groupId already exists, skipping")
                return
            }

            // Get our address to find our private key
            val userAddress = _currentUserAddress.value

            // Try to get the group key - either plaintext or ECIES encrypted
            var groupKeyBase64: String? = null

            if (json.has("enc_key")) {
                // ECIES encrypted group key - decrypt with our private key
                val encryptedKey = json.getString("enc_key")
                val ourPrivateKey = userAddress?.let { zchatPreferences.getE2EPrivateKey(inviterAddress) }
                    ?: zchatPreferences.getE2EPrivateKey(inviterAddress)

                if (ourPrivateKey != null) {
                    val decryptedKey = E2EEncryption.decryptGroupKeyFromInvite(ourPrivateKey, encryptedKey)
                    if (decryptedKey != null) {
                        groupKeyBase64 = android.util.Base64.encodeToString(decryptedKey, android.util.Base64.NO_WRAP)
                        Log.d("ZCHAT_GROUP", "Successfully decrypted ECIES group key")
                    } else {
                        Log.e("ZCHAT_GROUP", "Failed to decrypt ECIES group key")
                    }
                } else {
                    Log.w("ZCHAT_GROUP", "No E2E private key to decrypt group key - need KEX first")
                }
            } else if (json.has("group_key")) {
                // Legacy plaintext group key
                groupKeyBase64 = json.getString("group_key")
                Log.d("ZCHAT_GROUP", "Using plaintext group key (legacy format)")
            }

            // Store inviter's public key if provided (for future ECIES)
            if (json.has("inviter_pub")) {
                val inviterPubKey = json.getString("inviter_pub")
                zchatPreferences.setE2EPeerPublicKey(inviterAddress, inviterPubKey)
                Log.d("ZCHAT_GROUP", "Stored inviter's E2E public key")
            }

            // Create group info
            val groupInfo = GroupInfo(
                groupId = groupId,
                name = groupName,
                creatorAddress = inviterAddress,
                createdAt = timestamp ?: Instant.now(),
                adminPolicy = co.electriccoin.zcash.ui.screen.chat.model.AdminPolicy.CREATOR_ONLY,
                currentEpoch = 0,
                groupKey = groupKeyBase64,
                isActive = true
            )

            // Create member list
            val members = memberAddresses.map { address ->
                GroupMember(
                    address = address,
                    publicKey = null,
                    joinedAt = timestamp ?: Instant.now(),
                    status = if (address == userAddress) MemberStatus.ACTIVE else MemberStatus.INVITED,
                    isAdmin = address == inviterAddress,
                    nickname = contactBook.getContact(address)?.name
                )
            }

            // Save group info and members
            zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(groupInfo))
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(members))

            // Save group key if we got one
            if (groupKeyBase64 != null) {
                zchatPreferences.saveGroupKey(groupId, 0, groupKeyBase64)
                zchatPreferences.setGroupKeyEpoch(groupId, 0)
            } else {
                Log.w("ZCHAT_GROUP", "Group $groupId created without key - messages won't decrypt")
            }

            Log.d("ZCHAT_GROUP", "Group $groupId created from invite")

        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_INVITE", e)
        }
    }

    /**
     * Process a GROUP_MSG message.
     * Decrypts and stores the message.
     */
    private fun processGroupMsg(groupId: String, payload: String, txId: TransactionId?, timestamp: Instant?) {
        try {
            // Get group key for decryption
            val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
            val encodedKey = zchatPreferences.getGroupKey(groupId, keyEpoch)

            if (encodedKey == null) {
                Log.w("ZCHAT_GROUP", "No group key for $groupId - cannot decrypt message")
                return
            }

            val groupKey = ZMSGGroupProtocol.decodeGroupKey(encodedKey)

            // Parse the encrypted payload
            val parsedMsg = ZMSGGroupProtocol.parseGroupMsgPayload(payload)
            if (parsedMsg == null) {
                Log.w("ZCHAT_GROUP", "Failed to parse GROUP_MSG payload")
                return
            }

            // Decrypt the message
            val decrypted = ZMSGGroupProtocol.decryptMessage(
                parsedMsg.nonce,
                parsedMsg.ciphertext,
                groupKey
            )

            if (decrypted == null) {
                Log.w("ZCHAT_GROUP", "Failed to decrypt GROUP_MSG")
                return
            }

            Log.d("ZCHAT_GROUP", "Decrypted GROUP_MSG from ${parsedMsg.sender.redactAddress()} [${decrypted.length} chars]")

            // Store the decrypted message
            val txIdString = txId?.txIdString() ?: java.util.UUID.randomUUID().toString()
            val message = GroupMessage(
                id = txIdString,
                groupId = groupId,
                txId = txId,
                seq = parsedMsg.seq,
                epoch = parsedMsg.epoch,
                senderAddress = parsedMsg.sender,
                encryptedContent = parsedMsg.ciphertext,
                decryptedContent = decrypted,
                nonce = parsedMsg.nonce,
                timestamp = timestamp ?: Instant.now(),
                isPending = false
            )

            // Store message in preferences (simplified storage - just track message IDs per group)
            val existingMsgs = zchatPreferences.getGroupMessages(groupId)
            val msgJson = org.json.JSONObject().apply {
                put("id", message.id)
                put("txId", txIdString)
                put("seq", message.seq)
                put("epoch", message.epoch)
                put("sender", message.senderAddress)
                put("content", message.decryptedContent)
                put("timestamp", message.timestamp.epochSecond)
            }
            val updatedMsgs = if (existingMsgs != null) {
                val arr = org.json.JSONArray(existingMsgs)
                arr.put(msgJson)
                arr.toString()
            } else {
                org.json.JSONArray().put(msgJson).toString()
            }
            zchatPreferences.saveGroupMessages(groupId, updatedMsgs)

        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_MSG", e)
        }
    }

    /**
     * Process a GROUP_ACCEPT message.
     * Updates member status to ACTIVE.
     */
    private fun processGroupAccept(groupId: String, payload: String, txId: TransactionId?) {
        try {
            val json = org.json.JSONObject(payload)
            val accepterAddress = json.getString("accepter")

            Log.d("ZCHAT_GROUP", "Processing GROUP_ACCEPT from ${accepterAddress.redactAddress()}")

            // Update member status
            val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return
            val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
            val updated = members.map { member ->
                if (member.address == accepterAddress) {
                    member.copy(status = MemberStatus.ACTIVE)
                } else {
                    member
                }
            }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))

        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_ACCEPT", e)
        }
    }

    /**
     * Process a GROUP_LEAVE message.
     * Updates member status to LEFT.
     */
    private fun processGroupLeave(groupId: String, payload: String, txId: TransactionId?) {
        try {
            val json = org.json.JSONObject(payload)
            val leaverAddress = json.getString("leaver")

            Log.d("ZCHAT_GROUP", "Processing GROUP_LEAVE from ${leaverAddress.redactAddress()}")

            // Update member status
            val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return
            val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
            val updated = members.map { member ->
                if (member.address == leaverAddress) {
                    member.copy(status = MemberStatus.LEFT)
                } else {
                    member
                }
            }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))

        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_LEAVE", e)
        }
    }
}
