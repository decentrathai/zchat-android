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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
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
        // #199 invite retry through single-note serialization. Bounded so a genuinely-out-of-funds
        // wallet doesn't loop forever; the wait per attempt is one block (mirrors ChatViewModel's queue).
        private const val MAX_INVITE_RETRIES = 4
        private const val INVITE_BLOCK_WAIT_TIMEOUT_MS = 300_000L
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
            // #205 — record our canonical address so hash-tolerant self-checks (isAdmin / isCreator)
            // recognise us even when a group's stored creatorAddress is a different representation.
            zchatPreferences.registerSelfAddress(address)
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
            // Per-message epoch decrypt: each message is decrypted with the key for ITS OWN epoch (looked
            // up inside parseAndDecryptGroupMessage), not a single current-epoch key — otherwise every
            // message from before a key rotation fails to decrypt.

            val messagesFromHistory = mutableListOf<GroupMessage>()

            for (tx in transactions) {
                val memos = try {
                    transactionRepository.getMemos(tx)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "getMemos failed: ${e.message}")
                    emptyList()
                }
                for (memo: String in memos) {
                    if (!ZMSGGroupProtocol.isGroupMessage(memo)) continue

                    val parsedGroupId = ZMSGGroupProtocol.parseGroupId(memo)
                    if (parsedGroupId != groupId) continue

                    val message = parseAndDecryptGroupMessage(memo, tx)
                    if (message != null) {
                        messagesFromHistory.add(message)
                    }
                }
            }

            // Sort by timestamp and deduplicate by id (txId+seq), NOT by seq alone: seq is a
            // per-DEVICE counter, so different senders reuse the same seq numbers. Grouping by seq
            // collapsed distinct cross-sender messages into one and silently dropped them. id is
            // globally unique per transaction, so true re-parses of the same tx still collapse.
            val uniqueMessages = messagesFromHistory
                .groupBy { it.id }
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
    ): GroupMessage? {
        return try {
            val msgType = ZMSGGroupProtocol.parseMessageType(memo)
            if (msgType != GroupMessageType.GROUP_MSG) return null

            val groupId = ZMSGGroupProtocol.parseGroupId(memo) ?: return null
            val payload = ZMSGGroupProtocol.parsePayload(memo) ?: return null
            val msgPayload = ZMSGGroupProtocol.parseGroupMsgPayload(payload) ?: return null

            // Look up the key for THIS message's epoch (not a single current-epoch key) so messages from
            // before a key rotation still decrypt.
            val encodedKey = zchatPreferences.getGroupKey(groupId, msgPayload.epoch) ?: run {
                Log.w(TAG, "No group key for $groupId epoch ${msgPayload.epoch}")
                return null
            }
            val groupKey = try {
                ZMSGGroupProtocol.decodeGroupKey(encodedKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode group key for epoch ${msgPayload.epoch}", e)
                return null
            }

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
                // Robust to self-address drift (#205): trust the persisted creator flag first, fall back
                // to a hash-tolerant self-check for groups created before the flag existed (the stored
                // creatorAddress may be a different representation of our own address).
                val isCreator = zchatPreferences.isGroupSelfCreated(groupId) ||
                    zchatPreferences.isSelfAddress(group.groupInfo.creatorAddress)

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

    // ==========================================
    // CREATE GROUP FLOW
    // ==========================================

    /**
     * Get available contacts for adding to a group.
     */
    fun loadAvailableContacts() {
        viewModelScope.launch {
            val saved = contactBook.getAllContacts()
            val savedAddrs = saved.map { it.address }.toSet()
            // #196: ALSO surface KEX'd CONVERSATION peers, not just Address Book entries. A peer you've
            // completed a key exchange with already has the E2E identity key a group needs (each member's
            // group key is ECIES-wrapped to that key), so they are addable even if you never saved them
            // as a contact. Without this the picker showed only seeded/saved contacts — you literally
            // could not add the person you were already chatting with, which blocked all 2-device group
            // testing (the Address Book and the conversation store are SEPARATE). Merge + de-dupe by
            // address; saved contacts win (they carry the user's chosen nickname).
            val kexPeers = zchatPreferences.getAllPeerToConvIdMappings().keys
                .filter { it.startsWith("u1") && it !in savedAddrs && zchatPreferences.getE2EPeerPublicKey(it) != null }
                .map { addr ->
                    co.electriccoin.zcash.ui.screen.chat.model.Contact(
                        address = addr,
                        name = zchatPreferences.getDisplayName(addr),
                    )
                }
            _createGroupState.value = _createGroupState.value.copy(
                availableContacts = saved + kexPeers
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
                    // #195 bound the unbounded name at the source so the stored name matches what goes
                    // on-chain in the invite (also defensively capped in createGroupInviteCompact).
                    name = ZMSGGroupProtocol.boundGroupName(state.groupName),
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
                // Persist that WE are the creator/admin so the role survives a self-address drift (the
                // creatorAddress string can later differ from our live address across reinstalls).
                zchatPreferences.setGroupSelfCreated(groupId, true)

                Log.d(TAG, "Group created: $groupId with ${members.size} members")

                // Send GROUP_INVITE to all invited members. Each invite is a separate shielded tx, so
                // the single-note constraint serializes them: invite #2 can't spend until #1's change
                // confirms (~a block later). The old loop's fixed 500 ms gap was FAR shorter than block
                // time, so every invite after the first hit transient InsufficientFunds and was SILENTLY
                // dropped (#199) — multi-member groups never actually invited anyone but the first.
                // sendInviteWithRetry waits for the next block on that transient case and retries;
                // genuinely-failed members are collected + surfaced instead of vanishing.
                val failedInvites = mutableListOf<String>()
                for (memberAddress in state.selectedMembers) {
                    Log.d(TAG, "Sending GROUP_INVITE to ${memberAddress.redactAddress()}")

                    // Build a COMPACT invite that fits Zcash's 512-byte memo. The legacy invite
                    // embedded the full roster + a fat ECIES blob and overflowed for ANY group
                    // size (#194), so groups never formed. Prefer wrapping the group key under the
                    // existing authenticated KEX session (small + authenticated); fall back to a
                    // plaintext key only when no KEX exists. The roster is NOT shipped — peers are
                    // discovered lazily as they post (see ChatViewModel.addOrActivateGroupMember).
                    val sessionKey = deriveSessionKey(memberAddress)

                    val inviteMemo = if (sessionKey != null) {
                        val wrappedKey = E2EEncryption.encrypt(encodedGroupKey, sessionKey)
                        Log.d(TAG, "Compact invite (session-wrapped key) for ${memberAddress.redactAddress()}")
                        ZMSGGroupProtocol.createGroupInviteCompact(
                            groupId = groupId,
                            groupName = state.groupName,
                            inviterAddress = creatorAddress,
                            keyEpoch = 0,
                            encryptedGroupKey = wrappedKey,
                            isSessionEncrypted = true
                        )
                    } else {
                        // Fallback: plaintext group key (backward compat, no prior KEX)
                        Log.w(TAG, "No KEX with ${memberAddress.redactAddress()} - compact invite with plaintext key")
                        ZMSGGroupProtocol.createGroupInviteCompact(
                            groupId = groupId,
                            groupName = state.groupName,
                            inviterAddress = creatorAddress,
                            keyEpoch = 0,
                            encryptedGroupKey = encodedGroupKey,
                            isSessionEncrypted = false
                        )
                    }

                    val sent = sendGroupMemoWithRetry(memberAddress, inviteMemo, creatorAddress)
                    if (!sent) failedInvites.add(memberAddress)
                }

                _createGroupState.value = CreateGroupState(
                    createdGroupId = groupId,
                    failedInvites = failedInvites
                )

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
     * Derive the symmetric key shared with [peerAddress] from a completed KEX, mirroring
     * ChatViewModel.getE2ESharedKey. Used to wrap the group key in a compact invite. Returns null
     * when no key exchange has completed (caller then falls back to a plaintext-key invite).
     */
    private fun deriveSessionKey(peerAddress: String): ByteArray? {
        val ourPrivateKey = zchatPreferences.getE2EPrivateKey(peerAddress) ?: return null
        val peerPublicKey = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
        val keyVersion = E2EKeyVersion.fromValue(zchatPreferences.getE2EKeyVersion(peerAddress))
        return try {
            E2EEncryption.deriveSharedSecret(ourPrivateKey, peerPublicKey, keyVersion)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive session key for ${peerAddress.redactAddress()}", e)
            null
        }
    }

    /**
     * Send one per-member group memo (invite / signed kick / signed key-rotation), retrying through
     * the single-note serialization that makes back-to-back sends fail with TRANSIENT insufficient
     * funds (the previous send's change hasn't confirmed yet). On that case we wait for the next block
     * (the change to mature) and retry, up to [MAX_INVITE_RETRIES]. A GENUINE shortfall ("add ZEC") is
     * NOT retried — waiting can't fix it.
     * @return true if the memo was submitted, false if it ultimately failed.
     */
    private suspend fun sendGroupMemoWithRetry(
        memberAddress: String,
        inviteMemo: String,
        creatorAddress: String
    ): Boolean {
        var attempt = 0
        while (true) {
            try {
                createChunkedMessageProposal(
                    destinationAddress = memberAddress,
                    senderAddress = creatorAddress,
                    message = inviteMemo,
                    isFirstMessage = false,
                    amountPerOutput = Zatoshi(DEFAULT_MESSAGE_AMOUNT),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true // Pre-formatted GROUP message
                )
                return true
            } catch (e: Exception) {
                if (isTransientInsufficientFunds(e) && attempt < MAX_INVITE_RETRIES) {
                    attempt++
                    Log.w(
                        TAG,
                        "Invite to ${memberAddress.redactAddress()} hit transient insufficient funds " +
                            "(previous invite's change pending) — waiting for next block, retry $attempt/$MAX_INVITE_RETRIES"
                    )
                    if (!waitForNextBlock()) {
                        Log.e(TAG, "Gave up waiting for a new block — invite to ${memberAddress.redactAddress()} failed")
                        return false
                    }
                } else {
                    Log.e(TAG, "Invite to ${memberAddress.redactAddress()} failed permanently", e)
                    return false
                }
            }
        }
    }

    /**
     * True only for the TRANSIENT "funds still confirming" form of insufficient funds — the use case
     * maps that to a "…confirm on-chain…" message (our change maturing OR ZEC we just received), which
     * resolves on the next block. A genuine shortfall maps to a different message and must NOT retry.
     */
    private fun isTransientInsufficientFunds(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is InsufficientFundsException) {
                return current.message?.contains("confirm on-chain", ignoreCase = true) == true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Suspend until the network block height advances past its current value (the previous invite's
     * change confirms), or [timeoutMs] elapses. Returns true if a new block landed.
     */
    private suspend fun waitForNextBlock(timeoutMs: Long = INVITE_BLOCK_WAIT_TIMEOUT_MS): Boolean {
        return try {
            val synchronizer = synchronizerProvider.getSynchronizer()
            val startHeight = synchronizer.networkHeight.value?.value
            withTimeoutOrNull(timeoutMs) {
                synchronizer.networkHeight.first { h ->
                    h != null && (startHeight == null || h.value > startHeight)
                }
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "waitForNextBlock failed", e)
            false
        }
    }

    /**
     * #187 — ADMIN-ONLY: kick [kickedAddress] from the group. Rotates to a fresh group key so the
     * removed member can't read future messages, and notifies every remaining member with a per-member
     * SIGNED GROUP_KICK (signed with our per-peer KEX key, which they verify against ours). See
     * [rotateAndNotify]. No-op (logged) if we aren't the admin.
     */
    fun kickMember(groupId: String, kickedAddress: String) {
        viewModelScope.launch {
            val failed = rotateAndNotify(groupId, kickedAddress)
            if (failed.isNotEmpty()) {
                Log.w(TAG, "Kick: couldn't notify ${failed.size} member(s) (no KEX session / send failed)")
            }
            loadGroupDetail(groupId)
            loadGroupSettings(groupId) // refresh the settings screen the action was triggered from
        }
    }

    /**
     * #187 — ADMIN-ONLY: rotate the group key (e.g. periodic hygiene) and push a per-member SIGNED
     * GROUP_KEY to every member. No member is removed. No-op (logged) if we aren't the admin.
     */
    fun rotateGroupKey(groupId: String) {
        viewModelScope.launch {
            rotateAndNotify(groupId, null)
            loadGroupDetail(groupId)
            loadGroupSettings(groupId) // refresh the settings screen the action was triggered from
        }
    }

    /**
     * Shared admin path for kick + rotate: gen a fresh group key at epoch+1, wrap it per-member under
     * our authenticated KEX session with them, SIGN the canonical control payload with our per-peer
     * key, and send each member their own verifiable GROUP_KICK (when [kickedAddress] != null) or
     * GROUP_KEY. Then update our local roster + adopt the new key. Members we have no KEX session with
     * can't be sent a verifiable control msg — they're returned as "failed" (they keep the old key until
     * a KEX + re-sync). @return addresses we couldn't notify.
     */
    private suspend fun rotateAndNotify(groupId: String, kickedAddress: String?): List<String> {
        val adminAddress = _currentUserAddress.value ?: return emptyList()
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
        // Authorize via the persisted self-created flag (robust to self-address drift), falling back to
        // the address comparison for legacy groups. The control messages we send are still SIGNED with
        // our per-peer KEX key, so recipients independently verify authenticity (#187) regardless.
        val amAdmin = zchatPreferences.isGroupSelfCreated(groupId) ||
            (info?.creatorAddress?.let { zchatPreferences.isSelfAddress(it) } ?: false)
        if (info == null || !amAdmin) {
            Log.w(TAG, "rotateAndNotify: only the group admin may kick/rotate — aborting")
            return emptyList()
        }
        val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return emptyList()
        val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
        val newEpoch = zchatPreferences.getGroupKeyEpoch(groupId) + 1
        val newKeyBase64 = ZMSGGroupProtocol.encodeGroupKey(ZMSGGroupProtocol.generateGroupKey())

        val recipients = members.filter {
            it.status == MemberStatus.ACTIVE && it.address != adminAddress && it.address != kickedAddress
        }
        val failed = mutableListOf<String>()
        for (member in recipients) {
            val sessionKey = deriveSessionKey(member.address)
            val ourPriv = zchatPreferences.getE2EPrivateKey(member.address)
            if (sessionKey == null || ourPriv == null) {
                Log.w(TAG, "No KEX session with ${member.address.redactAddress()} — can't send verifiable control msg")
                failed.add(member.address)
                continue
            }
            // Wrap the new key for THIS member, then sign the canonical payload (which includes that
            // per-member wrapped key) with our per-peer key so only the real admin's copy verifies.
            val wrapped = E2EEncryption.encrypt(newKeyBase64, sessionKey)
            val memo = if (kickedAddress != null) {
                val signedData = ZMSGGroupProtocol.groupKickSignedData(groupId, kickedAddress, adminAddress, newEpoch, wrapped)
                val sig = E2EEncryption.sign(ourPriv, signedData)
                ZMSGGroupProtocol.createGroupKickMessage(groupId, kickedAddress, adminAddress, newEpoch, wrapped, sig)
            } else {
                val signedData = ZMSGGroupProtocol.groupKeySignedData(groupId, adminAddress, newEpoch, wrapped, "rotation")
                val sig = E2EEncryption.sign(ourPriv, signedData)
                ZMSGGroupProtocol.createGroupKeyMessage(groupId, adminAddress, newEpoch, wrapped, sig)
            }
            if (!sendGroupMemoWithRetry(member.address, memo, adminAddress)) failed.add(member.address)
        }

        // Update our own state: drop the kicked member from the roster, adopt the new key + epoch so
        // our subsequent sends use it.
        if (kickedAddress != null) {
            val updated = members.map {
                if (it.address == kickedAddress) it.copy(status = MemberStatus.LEFT) else it
            }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
        }
        zchatPreferences.saveGroupKey(groupId, newEpoch, newKeyBase64)
        zchatPreferences.setGroupKeyEpoch(groupId, newEpoch)
        return failed
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

                // Active members other than ourselves. Use the #205 hash-tolerant self-check (a drifted
                // rep of OUR OWN address must not be treated as a recipient — we'd pay to message
                // ourselves), and canonicalize + dedup across each peer's UA representations so a roster
                // that still holds duplicate reps of one member (legacy rows from before the #214 alias
                // fix) fans out only ONCE instead of delivering every message twice.
                val recipients = members
                    .filter { it.status == MemberStatus.ACTIVE && !zchatPreferences.isSelfAddress(it.address) }
                    .map { it.copy(address = zchatPreferences.resolvePeerAddress(it.address)) }
                    .distinctBy { it.address }

                if (recipients.isEmpty()) {
                    // The send went NOWHERE — surface it instead of leaving a forever-"pending" message
                    // that looks delivered. A member becomes ACTIVE on their GROUP_ACCEPT (matched by
                    // E2E identity now, #214) or their first post; group messages do not auto-retry.
                    Log.w(TAG, "Group $groupId has no ACTIVE recipients — message NOT transmitted")
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
    fun leaveGroup(groupId: String, onComplete: () -> Unit = {}) {
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
                onComplete()
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
        if (zchatPreferences.isGroupSelfCreated(groupId)) return true
        val infoJson = zchatPreferences.getGroupInfo(groupId) ?: return false
        val info = ZMSGGroupProtocol.deserializeGroupInfo(infoJson) ?: return false
        // #205 hash-tolerant: the stored creatorAddress may be a different representation of our
        // own address than the one currently loaded; persisted self-created flag is the primary
        // signal, this is the fallback.
        return zchatPreferences.isSelfAddress(info.creatorAddress)
    }
}
