package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AdminPolicy
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.CreateGroupState
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ViewModel for Group Chat functionality.
 * Manages group creation, messaging, and member management.
 */
class GroupViewModel(
    private val transactionRepository: TransactionRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val accountDataSource: AccountDataSource,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val zchatPreferences: ZchatPreferences,
    private val synchronizerProvider: SynchronizerProvider,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val contactBook: ContactBook
) : ViewModel() {

    companion object {
        private const val TAG = "GroupViewModel"
        // Default amount per group message output (1000 zatoshi = 0.00001 ZEC)
        const val DEFAULT_MESSAGE_AMOUNT = 1000L
    }

    // Current user address
    private val _currentUserAddress = MutableStateFlow<String?>(null)
    val currentUserAddress: StateFlow<String?> = _currentUserAddress.asStateFlow()

    // Group list state
    private val _groupConversations = MutableStateFlow<List<GroupConversation>>(emptyList())
    val groupConversations: StateFlow<List<GroupConversation>> = _groupConversations.asStateFlow()

    // Create group state
    private val _createGroupState = MutableStateFlow(CreateGroupState())
    val createGroupState: StateFlow<CreateGroupState> = _createGroupState.asStateFlow()

    // Group detail state (for viewing a specific group)
    private val _groupDetailState = MutableStateFlow<GroupDetailState>(GroupDetailState.Loading)
    val groupDetailState: StateFlow<GroupDetailState> = _groupDetailState.asStateFlow()

    // Send message state
    private val _isSendingMessage = MutableStateFlow(false)
    val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()

    // ZEC price
    private val _zecPriceUsd = MutableStateFlow<Double?>(null)
    val zecPriceUsd: StateFlow<Double?> = _zecPriceUsd.asStateFlow()

    // Group settings state
    private val _groupSettingsState = MutableStateFlow<GroupSettingsState>(GroupSettingsState.Loading)
    val groupSettingsState: StateFlow<GroupSettingsState> = _groupSettingsState.asStateFlow()

    // Pending group messages (not yet on chain)
    private val pendingGroupMessages = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())

    init {
        loadCurrentUserAddress()
        loadGroups()
        observeExchangeRate()
    }

    private fun loadCurrentUserAddress() {
        viewModelScope.launch {
            val address = getDefaultUnifiedAddress()
            _currentUserAddress.value = address
        }
    }

    private fun observeExchangeRate() {
        viewModelScope.launch {
            exchangeRateRepository.state.collectLatest { state ->
                if (state is ExchangeRateState.Data) {
                    _zecPriceUsd.value = state.currencyConversion?.priceOfZec
                }
            }
        }
    }

    /**
     * Load all groups from preferences.
     */
    fun loadGroups() {
        viewModelScope.launch {
            try {
                val groupIds = zchatPreferences.getAllGroupIds()
                val groups = groupIds.mapNotNull { groupId ->
                    loadGroup(groupId)
                }
                _groupConversations.value = groups.sortedByDescending {
                    it.lastMessage?.timestamp ?: it.groupInfo.createdAt
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load groups", e)
            }
        }
    }

    /**
     * Load a single group conversation.
     */
    private fun loadGroup(groupId: String): GroupConversation? {
        val infoJson = zchatPreferences.getGroupInfo(groupId) ?: return null
        val groupInfo = ZMSGGroupProtocol.deserializeGroupInfo(infoJson) ?: return null

        val membersJson = zchatPreferences.getGroupMembers(groupId)
        val members = if (membersJson != null) {
            ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
        } else {
            emptyList()
        }

        val draft = zchatPreferences.getGroupDraft(groupId)
        val pending = pendingGroupMessages.value[groupId] ?: emptyList()

        // Load messages from stored history (loaded asynchronously via loadGroupMessagesFromHistory)
        val storedMessagesJson = zchatPreferences.getGroupMessages(groupId)
        val storedMessages = if (storedMessagesJson != null) {
            parseStoredGroupMessages(storedMessagesJson)
        } else {
            emptyList()
        }

        // Merge stored messages with pending, removing duplicates by seq number
        val pendingSeqs = pending.map { it.seq }.toSet()
        val uniqueStored = storedMessages.filter { it.seq !in pendingSeqs }
        val messages = (uniqueStored + pending).sortedBy { it.timestamp }

        return GroupConversation(
            groupInfo = groupInfo,
            members = members,
            messages = messages,
            lastMessage = messages.lastOrNull(),
            unreadCount = 0,
            draft = draft
        )
    }

    /**
     * Parse stored group messages from JSON.
     */
    private fun parseStoredGroupMessages(jsonString: String): List<GroupMessage> {
        return try {
            val jsonArray = org.json.JSONArray(jsonString)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val obj = jsonArray.getJSONObject(i)
                    GroupMessage(
                        id = obj.getString("id"),
                        groupId = obj.getString("groupId"),
                        txId = null, // TransactionId can't be reconstructed from string
                        seq = obj.getLong("seq"),
                        epoch = obj.getInt("epoch"),
                        senderAddress = obj.getString("sender"),
                        encryptedContent = if (obj.has("encrypted")) obj.getString("encrypted") else null,
                        decryptedContent = if (obj.has("decrypted")) obj.getString("decrypted") else null,
                        nonce = if (obj.has("nonce")) obj.getString("nonce") else null,
                        timestamp = Instant.ofEpochMilli(obj.getLong("timestamp")),
                        blockHeight = if (obj.has("blockHeight")) obj.getLong("blockHeight") else null,
                        txIndex = if (obj.has("txIndex")) obj.getInt("txIndex") else null,
                        isPending = false,
                        isFailed = false
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse stored message at index $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored group messages JSON", e)
            emptyList()
        }
    }

    /**
     * Serialize group messages to JSON for storage.
     */
    private fun serializeGroupMessages(messages: List<GroupMessage>): String {
        val jsonArray = org.json.JSONArray()
        messages.forEach { msg ->
            val obj = org.json.JSONObject().apply {
                put("id", msg.id)
                put("groupId", msg.groupId)
                msg.txId?.let { put("txId", it.txIdString()) }
                put("seq", msg.seq)
                put("epoch", msg.epoch)
                put("sender", msg.senderAddress)
                msg.encryptedContent?.let { put("encrypted", it) }
                msg.decryptedContent?.let { put("decrypted", it) }
                msg.nonce?.let { put("nonce", it) }
                put("timestamp", msg.timestamp.toEpochMilli())
                msg.blockHeight?.let { put("blockHeight", it) }
                msg.txIndex?.let { put("txIndex", it) }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    /**
     * Load group messages from blockchain transaction history.
     * This scans all transactions for messages belonging to the specified group.
     */
    suspend fun loadGroupMessagesFromHistory(groupId: String) {
        try {
            val transactions = transactionRepository.getTransactions()
            if (transactions.isEmpty()) return
            val groupKey = getGroupKeyForDecryption(groupId) ?: run {
                Log.w(TAG, "No group key available for $groupId")
                return
            }

            val messagesFromHistory = mutableListOf<GroupMessage>()

            for (tx in transactions) {
                val memos = transactionRepository.getMemos(tx)
                for (memo: String in memos) {
                    if (!ZMSGGroupProtocol.isGroupMessage(memo)) continue

                    val parsedGroupId = ZMSGGroupProtocol.parseGroupId(memo)
                    if (parsedGroupId != groupId) continue

                    val message = parseAndDecryptGroupMessage(memo, tx, groupKey)
                    if (message != null) {
                        messagesFromHistory.add(message)
                    }
                }
            }

            // Sort by timestamp and deduplicate by seq
            val uniqueMessages = messagesFromHistory
                .groupBy { it.seq }
                .mapValues { (_, msgs) -> msgs.maxByOrNull { it.timestamp } }
                .values
                .filterNotNull()
                .sortedBy { it.timestamp }

            // Save to preferences for persistence
            if (uniqueMessages.isNotEmpty()) {
                zchatPreferences.saveGroupMessages(groupId, serializeGroupMessages(uniqueMessages))
            }

            // Refresh the group display
            loadGroups()
            Log.d(TAG, "Loaded ${uniqueMessages.size} messages from history for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load group messages from history for $groupId", e)
        }
    }

    /**
     * Parse and decrypt a group message from a memo.
     */
    private fun parseAndDecryptGroupMessage(
        memo: String,
        tx: Transaction,
        groupKey: ByteArray
    ): GroupMessage? {
        return try {
            val msgType = ZMSGGroupProtocol.parseMessageType(memo)
            if (msgType != GroupMessageType.GROUP_MSG) return null

            val groupId = ZMSGGroupProtocol.parseGroupId(memo) ?: return null
            val payload = ZMSGGroupProtocol.parsePayload(memo) ?: return null
            val msgPayload = ZMSGGroupProtocol.parseGroupMsgPayload(payload) ?: return null

            // Decrypt the message content
            val decrypted = ZMSGGroupProtocol.decryptMessage(
                msgPayload.nonce,
                msgPayload.ciphertext,
                groupKey
            )

            GroupMessage(
                id = "${tx.id.txIdString()}_${msgPayload.seq}",
                groupId = groupId,
                txId = tx.id,
                seq = msgPayload.seq,
                epoch = msgPayload.epoch,
                senderAddress = msgPayload.sender,
                encryptedContent = msgPayload.ciphertext,
                decryptedContent = decrypted,
                nonce = msgPayload.nonce,
                timestamp = Instant.ofEpochMilli(msgPayload.timestamp),
                blockHeight = tx.overview.minedHeight?.value,
                txIndex = tx.overview.index?.toInt(),
                isPending = false,
                isFailed = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse group message from memo", e)
            null
        }
    }

    /**
     * Get the group key for decryption, checking available epochs.
     */
    private fun getGroupKeyForDecryption(groupId: String): ByteArray? {
        val currentEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
        for (epoch in currentEpoch downTo 0) {
            val encodedKey = zchatPreferences.getGroupKey(groupId, epoch)
            if (encodedKey != null) {
                return try {
                    ZMSGGroupProtocol.decodeGroupKey(encodedKey)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode group key for epoch $epoch", e)
                    null
                }
            }
        }
        return null
    }

    /**
     * Load group detail for viewing.
     * Also triggers loading of message history from blockchain.
     */
    fun loadGroupDetail(groupId: String) {
        viewModelScope.launch {
            _groupDetailState.value = GroupDetailState.Loading

            // First load from stored data (fast)
            val group = loadGroup(groupId)
            if (group != null) {
                _groupDetailState.value = GroupDetailState.Success(
                    conversation = group,
                    currentUserAddress = _currentUserAddress.value ?: "",
                    zecPriceUsd = _zecPriceUsd.value
                )

                // Then load history from blockchain (may update messages)
                loadGroupMessagesFromHistory(groupId)

                // Refresh the UI with any new messages found
                val updatedGroup = loadGroup(groupId)
                if (updatedGroup != null) {
                    _groupDetailState.value = GroupDetailState.Success(
                        conversation = updatedGroup,
                        currentUserAddress = _currentUserAddress.value ?: "",
                        zecPriceUsd = _zecPriceUsd.value
                    )
                }
            } else {
                _groupDetailState.value = GroupDetailState.Error("Group not found")
            }
        }
    }

    /**
     * Load group settings for a specific group.
     */
    fun loadGroupSettings(groupId: String) {
        viewModelScope.launch {
            _groupSettingsState.value = GroupSettingsState.Loading

            val group = loadGroup(groupId)
            if (group != null) {
                val currentAddress = _currentUserAddress.value ?: ""
                val isCreator = group.groupInfo.creatorAddress == currentAddress

                _groupSettingsState.value = GroupSettingsState.Success(
                    groupInfo = group.groupInfo,
                    members = group.members,
                    currentUserAddress = currentAddress,
                    isCreator = isCreator
                )
            } else {
                _groupSettingsState.value = GroupSettingsState.Error("Group not found")
            }
        }
    }

    /**
     * Leave a group.
     */
    fun leaveGroup(groupId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                // TODO: Send GROUP_LEAVE message to all members
                Log.d(TAG, "Leaving group: $groupId")

                // For now, just remove from local storage
                zchatPreferences.deleteGroup(groupId)
                loadGroups()

                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to leave group: $groupId", e)
            }
        }
    }

    // ==========================================
    // CREATE GROUP FLOW
    // ==========================================

    /**
     * Get available contacts for adding to a group.
     */
    fun loadAvailableContacts() {
        viewModelScope.launch {
            val contacts = contactBook.getAllContacts()
            _createGroupState.value = _createGroupState.value.copy(
                availableContacts = contacts
            )
        }
    }

    /**
     * Update group name in create flow.
     */
    fun setGroupName(name: String) {
        _createGroupState.value = _createGroupState.value.copy(
            groupName = name,
            error = null
        )
    }

    /**
     * Toggle member selection in create flow.
     */
    fun toggleMemberSelection(address: String) {
        val current = _createGroupState.value.selectedMembers.toMutableList()
        if (current.contains(address)) {
            current.remove(address)
        } else {
            // Limit to 9 members (+ creator = 10 total)
            if (current.size < 9) {
                current.add(address)
            }
        }
        _createGroupState.value = _createGroupState.value.copy(
            selectedMembers = current,
            error = null
        )
    }

    /**
     * Create a new group.
     */
    fun createGroup() {
        val state = _createGroupState.value
        if (!state.isValid) {
            _createGroupState.value = state.copy(error = "Please enter a group name and select members")
            return
        }

        val creatorAddress = _currentUserAddress.value
        if (creatorAddress == null) {
            _createGroupState.value = state.copy(error = "Wallet not ready")
            return
        }

        viewModelScope.launch {
            _createGroupState.value = state.copy(isCreating = true, error = null)

            try {
                // Generate group ID
                val groupId = GroupInfo.generateGroupId(creatorAddress)

                // Generate group key
                val groupKey = ZMSGGroupProtocol.generateGroupKey()
                val encodedGroupKey = ZMSGGroupProtocol.encodeGroupKey(groupKey)

                // Create group info
                val groupInfo = GroupInfo(
                    groupId = groupId,
                    name = state.groupName,
                    creatorAddress = creatorAddress,
                    createdAt = Instant.now(),
                    adminPolicy = AdminPolicy.CREATOR_ONLY,
                    currentEpoch = 0,
                    groupKey = encodedGroupKey,
                    isActive = true
                )

                // Create member list (creator + selected members)
                val allMemberAddresses = listOf(creatorAddress) + state.selectedMembers
                val members = allMemberAddresses.mapIndexed { index, address ->
                    GroupMember(
                        address = address,
                        publicKey = if (address == creatorAddress) {
                            // Generate E2E key for creator with V2 (HKDF) key derivation
                            val keyPair = E2EEncryption.generateKeyPair()
                            zchatPreferences.setE2EOurKeys(groupId, keyPair.publicKey, keyPair.privateKey)
                            zchatPreferences.setE2EKeyVersion(groupId, E2EKeyVersion.V2.value)
                            keyPair.publicKey
                        } else null,
                        joinedAt = Instant.now(),
                        status = if (address == creatorAddress) MemberStatus.ACTIVE else MemberStatus.INVITED,
                        isAdmin = address == creatorAddress,
                        nickname = contactBook.getContact(address)?.name
                    )
                }

                // Save group info and members
                zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(groupInfo))
                zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(members))
                zchatPreferences.saveGroupKey(groupId, 0, encodedGroupKey)
                zchatPreferences.setGroupKeyEpoch(groupId, 0)

                Log.d(TAG, "Group created: $groupId with ${members.size} members")

                // Send GROUP_INVITE to all invited members
                // Get our E2E public key for including in invites
                val ourE2EPublicKey = zchatPreferences.getE2EOurPublicKey(creatorAddress)

                for (memberAddress in state.selectedMembers) {
                    try {
                        Log.d(TAG, "Sending GROUP_INVITE to ${memberAddress.redactAddress()}")

                        // Check if we have the member's E2E public key (from prior KEX)
                        val memberPublicKey = zchatPreferences.getE2EPeerPublicKey(memberAddress)

                        val inviteMemo = if (memberPublicKey != null && ourE2EPublicKey != null) {
                            // ECIES encryption: encrypt group key for this specific member
                            val encryptedGroupKey = E2EEncryption.encryptGroupKeyForMember(
                                memberPublicKey = memberPublicKey,
                                groupKey = groupKey
                            )
                            Log.d(TAG, "Using ECIES encryption for ${memberAddress.redactAddress()}")

                            ZMSGGroupProtocol.createGroupInviteMessage(
                                groupId = groupId,
                                groupName = state.groupName,
                                inviterAddress = creatorAddress,
                                inviterPublicKey = ourE2EPublicKey,
                                allMembers = allMemberAddresses,
                                keyEpoch = 0,
                                encryptedGroupKey = encryptedGroupKey
                            )
                        } else {
                            // Fallback: plaintext group key (backward compat, no prior KEX)
                            Log.w(TAG, "No KEX with ${memberAddress.redactAddress()} - using plaintext group key")

                            ZMSGGroupProtocol.createGroupInviteMessage(
                                groupId = groupId,
                                groupName = state.groupName,
                                inviterAddress = creatorAddress,
                                inviteeAddress = memberAddress,
                                groupKey = groupKey,
                                memberAddresses = allMemberAddresses
                            )
                        }

                        // Send the invite transaction
                        createChunkedMessageProposal(
                            destinationAddress = memberAddress,
                            senderAddress = creatorAddress,
                            message = inviteMemo,
                            isFirstMessage = false,
                            amountPerOutput = Zatoshi(DEFAULT_MESSAGE_AMOUNT),
                            directSubmit = true,
                            skipNavigation = true,
                            rawMemo = true  // Pre-formatted GROUP message
                        )

                        // Small delay between sends
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send invite to ${memberAddress.redactAddress()}", e)
                        // Continue with other invites even if one fails
                    }
                }

                _createGroupState.value = CreateGroupState(createdGroupId = groupId)

                // Refresh groups list
                loadGroups()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create group", e)
                _createGroupState.value = state.copy(
                    isCreating = false,
                    error = "Failed to create group: ${e.message}"
                )
            }
        }
    }

    /**
     * Reset create group state.
     */
    fun resetCreateGroupState() {
        _createGroupState.value = CreateGroupState()
    }

    // ==========================================
    // MESSAGING
    // ==========================================

    /**
     * Send a message to a group.
     */
    fun sendGroupMessage(groupId: String, message: String) {
        if (message.isBlank()) return

        val senderAddress = _currentUserAddress.value ?: return

        viewModelScope.launch {
            _isSendingMessage.value = true

            try {
                // Get group key
                val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                val encodedKey = zchatPreferences.getGroupKey(groupId, keyEpoch)
                if (encodedKey == null) {
                    Log.e(TAG, "No group key found for $groupId")
                    _isSendingMessage.value = false
                    return@launch
                }

                val groupKey = ZMSGGroupProtocol.decodeGroupKey(encodedKey)

                // Get next sequence number
                val seq = zchatPreferences.incrementGroupMessageSequence(groupId)

                // Create encrypted message
                val memo = ZMSGGroupProtocol.createGroupMsgMessage(
                    groupId = groupId,
                    seq = seq,
                    epoch = keyEpoch,
                    senderAddress = senderAddress,
                    plaintext = message,
                    groupKey = groupKey
                )

                // Create pending message for immediate display
                val pendingMessage = GroupMessage(
                    id = "pending_${System.currentTimeMillis()}",
                    groupId = groupId,
                    txId = null,
                    seq = seq,
                    epoch = keyEpoch,
                    senderAddress = senderAddress,
                    encryptedContent = null,
                    decryptedContent = message,
                    nonce = null,
                    timestamp = Instant.now(),
                    isPending = true
                )

                // Add to pending messages
                val currentPending = pendingGroupMessages.value.toMutableMap()
                val groupPending = currentPending[groupId]?.toMutableList() ?: mutableListOf()
                groupPending.add(pendingMessage)
                currentPending[groupId] = groupPending
                pendingGroupMessages.value = currentPending

                // Get group members to send to
                val membersJson = zchatPreferences.getGroupMembers(groupId)
                val members = if (membersJson != null) {
                    ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
                } else {
                    emptyList()
                }

                // Filter to active members, excluding self
                val recipients = members.filter {
                    it.status == MemberStatus.ACTIVE && it.address != senderAddress
                }

                Log.d(TAG, "Sending group message to ${recipients.size} members")
                Log.d(TAG, "Memo: $memo")

                // Send to each recipient
                for (recipient in recipients) {
                    try {
                        Log.d(TAG, "Sending group message to ${recipient.address.redactAddress()}")
                        createChunkedMessageProposal(
                            destinationAddress = recipient.address,
                            senderAddress = senderAddress,
                            message = memo,  // Pre-formatted GROUP message
                            isFirstMessage = false,  // Not relevant for raw memos
                            amountPerOutput = Zatoshi(DEFAULT_MESSAGE_AMOUNT),
                            directSubmit = true,
                            skipNavigation = true,
                            rawMemo = true  // Use memo as-is (already GROUP formatted)
                        )
                        // Small delay between sends to avoid overwhelming the wallet
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send to ${recipient.address.redactAddress()}", e)
                        // Continue to next recipient even if one fails
                    }
                }

                // Clear draft
                zchatPreferences.clearGroupDraft(groupId)

                // Refresh group detail
                loadGroupDetail(groupId)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group message", e)
            } finally {
                _isSendingMessage.value = false
            }
        }
    }

    // ==========================================
    // DRAFT MANAGEMENT
    // ==========================================

    /**
     * Save draft for a group.
     */
    fun saveGroupDraft(groupId: String, draft: String) {
        zchatPreferences.setGroupDraft(groupId, draft)
    }

    /**
     * Get draft for a group.
     */
    fun getGroupDraft(groupId: String): String? {
        return zchatPreferences.getGroupDraft(groupId)
    }

    /**
     * Clear draft for a group.
     */
    fun clearGroupDraft(groupId: String) {
        zchatPreferences.clearGroupDraft(groupId)
    }

    // ==========================================
    // MEMBER MANAGEMENT
    // ==========================================

    /**
     * Leave a group.
     */
    fun leaveGroup(groupId: String) {
        val userAddress = _currentUserAddress.value ?: return

        viewModelScope.launch {
            try {
                // Update local status
                val membersJson = zchatPreferences.getGroupMembers(groupId)
                if (membersJson != null) {
                    val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
                    val updated = members.map { member ->
                        if (member.address == userAddress) {
                            member.copy(status = MemberStatus.LEFT)
                        } else {
                            member
                        }
                    }
                    zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
                }

                // Mark group as inactive locally
                val infoJson = zchatPreferences.getGroupInfo(groupId)
                if (infoJson != null) {
                    val info = ZMSGGroupProtocol.deserializeGroupInfo(infoJson)
                    if (info != null) {
                        val updated = info.copy(isActive = false)
                        zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(updated))
                    }
                }

                // Send GROUP_LEAVE message to other active members
                val membersForBroadcast = zchatPreferences.getGroupMembers(groupId)?.let {
                    ZMSGGroupProtocol.deserializeGroupMembers(it)
                } ?: emptyList()

                val recipients = membersForBroadcast.filter {
                    it.status == MemberStatus.ACTIVE && it.address != userAddress
                }

                if (recipients.isNotEmpty()) {
                    val leaveMemo = ZMSGGroupProtocol.createGroupLeaveMessage(
                        groupId = groupId,
                        leaverAddress = userAddress
                    )

                    Log.d(TAG, "Broadcasting GROUP_LEAVE to ${recipients.size} members")

                    for (recipient in recipients) {
                        try {
                            Log.d(TAG, "Sending GROUP_LEAVE to ${recipient.address.redactAddress()}")
                            createChunkedMessageProposal(
                                destinationAddress = recipient.address,
                                senderAddress = userAddress,
                                message = leaveMemo,
                                isFirstMessage = false,
                                amountPerOutput = Zatoshi(DEFAULT_MESSAGE_AMOUNT),
                                directSubmit = true,
                                skipNavigation = true,
                                rawMemo = true
                            )
                            // Small delay between sends
                            delay(500)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send GROUP_LEAVE to ${recipient.address.redactAddress()}", e)
                            // Continue to next recipient - best effort broadcast
                        }
                    }
                }

                // Refresh groups
                loadGroups()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to leave group", e)
            }
        }
    }

    /**
     * Delete a group (admin only).
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            zchatPreferences.deleteGroup(groupId)
            loadGroups()
        }
    }

    /**
     * Check if user is admin of a group.
     */
    fun isAdmin(groupId: String): Boolean {
        val userAddress = _currentUserAddress.value ?: return false
        val infoJson = zchatPreferences.getGroupInfo(groupId) ?: return false
        val info = ZMSGGroupProtocol.deserializeGroupInfo(infoJson) ?: return false
        return info.creatorAddress == userAddress
    }
}
