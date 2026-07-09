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
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
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
 * P0.1 — user-visible outcome of a group send / invite, replacing the old silent Log.w-only paths.
 * Emitted on [GroupViewModel.groupSendEvent] and rendered by the group screens as a banner.
 */
sealed interface GroupSendResult {
    /** Which group produced this event. The event flow is process-wide (companion), so screens MUST
     *  compare this against the group they're showing and ignore a mismatch — otherwise a pending
     *  event from group A can surface in / clear the composer of group B. */
    val groupId: String

    /** No roster member is ACTIVE yet (invitees haven't accepted) — nothing was transmitted. */
    data class NoActiveRecipients(override val groupId: String) : GroupSendResult

    /** Delivered to [sent] of [total] ACTIVE members; the rest failed after retries. */
    data class PartialDelivery(override val groupId: String, val sent: Int, val total: Int) : GroupSendResult

    /** Every recipient failed after retries — nothing was transmitted; the draft is kept. */
    data class AllFailed(override val groupId: String) : GroupSendResult

    /** GROUP_INVITEs to [addresses] couldn't be sent — repairable via Resend in group settings. */
    data class InviteFailed(override val groupId: String, val addresses: List<String>) : GroupSendResult
}

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

        // Bound the backward epoch search when a held key's epoch number disagrees with the
        // message's claimed epoch (key-rotation / lagging-peer drift). Mirrors ChatViewModel.
        private const val MAX_GROUP_EPOCH_LOOKBACK = 64

        // P0.1 — backing flow for [groupSendEvent]. Process-wide (companion) ON PURPOSE: each nav
        // destination gets its OWN GroupViewModel instance (viewModelOf), so an InviteFailed emitted
        // by the create-group screen's instance must survive into the detail screen the user lands
        // on. Same cross-instance pattern as the #226 shared read-marker flow.
        private val _groupSendEvent = MutableStateFlow<GroupSendResult?>(null)
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

    // Candidate contacts the admin can add to an EXISTING group (Add-member picker). Populated by
    // loadAddMemberCandidates(groupId): saved contacts + KEX'd peers, minus those already in the group.
    private val _addMemberCandidates = MutableStateFlow<List<Contact>>(emptyList())
    val addMemberCandidates: StateFlow<List<Contact>> = _addMemberCandidates.asStateFlow()

    // Pending group messages (not yet on chain)
    private val pendingGroupMessages = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())

    // SF-3 — per-(groupId,memberAddress) resend-in-flight guard. A double-tap on Resend would
    // otherwise fire two on-chain invite txs. Accessed only from the main-dispatched viewModelScope,
    // but kept synchronized for safety.
    private val resendInvitesInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // P0.1 — latest visible group-send outcome; consumed (nulled) by the screen that rendered it.
    val groupSendEvent: StateFlow<GroupSendResult?> = _groupSendEvent.asStateFlow()

    /** Clear the current [groupSendEvent] once the UI has surfaced it. */
    fun consumeGroupSendEvent() {
        _groupSendEvent.value = null
    }

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

        // Reconcile optimistic pending messages against their mined copies. A pending message is one
        // WE sent from this device; it's superseded once a stored (mined + chain-decrypted) message
        // with the SAME (sender, seq, epoch) identity appears. Keep ALL stored messages and drop only
        // the pending entries that have mined — never filter stored by seq alone: seq is per-sender, so
        // two different senders can share a seq and a seq-only filter would (a) hide another member's
        // message and (b) leave OUR own mined message perpetually showing as "pending".
        fun identity(m: GroupMessage) = Triple(m.senderAddress, m.seq, m.epoch)
        // #426: the two writers of the group-message store use DIFFERENT id schemes for the same on-chain
        // message — the history scan writes "<txid>_<seq>" while ChatViewModel's live path writes plain
        // "<txid>" — so id-dedup fails across them and one message is stored twice. (sender, seq, epoch) is
        // globally unique per message (seq is per-sender monotonic; it's the SAME identity the pending
        // reconciliation below already trusts), so collapse duplicates on it, preferring a decrypted copy.
        val dedupedStored = storedMessages
            .groupBy { identity(it) }
            .values
            .map { dup -> dup.firstOrNull { it.decryptedContent != null } ?: dup.first() }
        val storedIdentities = dedupedStored.map { identity(it) }.toSet()
        val unreconciledPending = pending.filterNot { identity(it) in storedIdentities }
        val messages = (dedupedStored + unreconciledPending).sortedBy { it.timestamp }

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
                // MERGE, don't overwrite: this scan only re-derives ON-CHAIN messages, but ChatViewModel
                // delivers FREE-NOSTR group messages (#11, id "grpn-…") into the SAME store and those are
                // NOT re-derivable from chain — a wholesale overwrite here permanently deleted every one of
                // them. Carry the NOSTR-delivered rows forward alongside the chain-derived set. (An on-chain
                // double-write across the two id schemes is collapsed at display time by loadGroup, #426.)
                val preservedNostr = zchatPreferences.getGroupMessages(groupId)
                    ?.let { parseStoredGroupMessages(it) }
                    ?.filter { it.id.startsWith("grpn-") }
                    ?: emptyList()
                val merged = (uniqueMessages + preservedNostr).sortedBy { it.timestamp }
                zchatPreferences.saveGroupMessages(groupId, serializeGroupMessages(merged))

                // Prune in-memory optimistic pending entries that have now mined (same sender, seq,
                // epoch), so the pending list doesn't grow unbounded across a session. loadGroup's merge
                // already hides them from display; this reclaims the memory and avoids re-merging them.
                val minedIdentities = uniqueMessages.map { Triple(it.senderAddress, it.seq, it.epoch) }.toSet()
                val curPending = pendingGroupMessages.value
                val groupPending = curPending[groupId]
                if (groupPending != null) {
                    val remaining = groupPending.filterNot {
                        Triple(it.senderAddress, it.seq, it.epoch) in minedIdentities
                    }
                    if (remaining.size != groupPending.size) {
                        pendingGroupMessages.value = curPending.toMutableMap().apply { put(groupId, remaining) }
                    }
                }
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

            // Try the message's own epoch first, then fall back across a bounded window of held
            // keys so messages survive key-rotation / lagging-peer epoch-number drift. Mirrors the
            // live processGroupMsg path in ChatViewModel (#215).
            val currentEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
            val windowStart = maxOf(0, currentEpoch - MAX_GROUP_EPOCH_LOOKBACK)
            val candidateEpochs =
                (listOf(msgPayload.epoch, currentEpoch) + (windowStart..currentEpoch))
                    .filter { it >= 0 }
                    .distinct()
            var decrypted: String? = null
            var triedAnyKey = false
            for (epoch in candidateEpochs) {
                val encodedKey = zchatPreferences.getGroupKey(groupId, epoch) ?: continue
                triedAnyKey = true
                decrypted = runCatching {
                    ZMSGGroupProtocol.decryptMessage(
                        msgPayload.nonce,
                        msgPayload.ciphertext,
                        ZMSGGroupProtocol.decodeGroupKey(encodedKey)
                    )
                }.getOrNull()
                if (decrypted != null) break
            }
            if (decrypted == null) {
                if (!triedAnyKey) {
                    Log.w(TAG, "No group key for $groupId epoch ${msgPayload.epoch}")
                } else {
                    Log.w(TAG, "Failed to decrypt group message for $groupId epoch ${msgPayload.epoch}")
                }
                return null
            }

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
        // SF-2 — the send-event flow is process-wide, so a pending event from ANOTHER group must not
        // survive into this one (it would surface in / clear this group's composer). Drop a stale
        // cross-group event on entry. An event for THIS group (just emitted by our own send path,
        // which then calls loadGroupDetail) is preserved.
        _groupSendEvent.value?.let { pending ->
            if (pending.groupId != groupId) {
                _groupSendEvent.value = null
            }
        }
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
            // #196: exclude SELF from the saved side too — you are added as creator, so offering your own
            // saved address as an invitee is nonsensical and (pre-fix) fed a self-invite. The invitee set is
            // independently sanitized in createGroup(), but keeping it out of the picker is the clean UX.
            val saved = contactBook.getAllContacts().filterNot { zchatPreferences.isSelfAddress(it.address) }
            // #196: canonicalize saved addresses so a KEX peer that differs only by UA representation
            // (case / unified-address drift) is recognized as already-saved and not surfaced twice.
            val savedCanon = saved.map { it.address.trim().lowercase() }.toSet()
            // #196: ALSO surface KEX'd CONVERSATION peers, not just Address Book entries. A peer you've
            // completed a key exchange with already has the E2E identity key a group needs (each member's
            // group key is ECIES-wrapped to that key), so they are addable even if you never saved them
            // as a contact. Without this the picker showed only seeded/saved contacts — you literally
            // could not add the person you were already chatting with, which blocked all 2-device group
            // testing (the Address Book and the conversation store are SEPARATE). Merge + de-dupe by
            // canonical address; saved contacts win (they carry the user's chosen nickname). Exclude SELF
            // (you are added as creator below — listing yourself as an invitee is nonsensical and would
            // double-add) and resolve UA drift to the canonical peer address before filtering.
            val kexPeers = zchatPreferences.getAllPeerToConvIdMappings().keys
                .map { zchatPreferences.resolvePeerAddress(it) }
                .filter {
                    it.startsWith("u1") &&
                        it.trim().lowercase() !in savedCanon &&
                        !zchatPreferences.isSelfAddress(it) &&
                        zchatPreferences.getE2EPeerPublicKey(it) != null
                }
                .distinct()
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

                // Create member list (creator + selected members).
                // #196 defense-in-depth: resolve any UA-drifted selection to its canonical peer address
                // and strip self / the creator from the invitee set so the creator can never be added
                // twice (once as creator, once as a stale-cased invitee) and a duplicate member row
                // cannot poison the group roster / per-member key wrapping.
                val allMemberAddresses = (
                    listOf(creatorAddress) +
                        state.selectedMembers
                            .map { zchatPreferences.resolvePeerAddress(it) }
                            .filterNot { zchatPreferences.isSelfAddress(it) || it == creatorAddress }
                ).distinct()
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
                        nickname = contactBook.getContact(address)?.name,
                        // P1.4: flips to SENT/FAILED as each GROUP_INVITE resolves in the loop below.
                        inviteStatus = if (address == creatorAddress) null else InviteStatus.INVITE_PENDING
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
                // #196: invite the SANITIZED roster (allMemberAddresses, minus the creator) — NOT the raw
                // state.selectedMembers. Iterating the raw selection would (a) send a real shielded invite tx
                // to OURSELVES if our own address slipped into the selection (it is not a roster member), and
                // (b) send DUPLICATE invites — and duplicate on-chain txs / retries — for two selections that
                // canonicalize to the same peer under UA-representation drift. Both waste ZEC. The roster
                // (`members`) was already built from allMemberAddresses, so this keeps invites and roster in
                // lockstep.
                val inviteeAddresses = allMemberAddresses.filterNot { it == creatorAddress }
                val failedInvites = mutableListOf<String>()
                for (memberAddress in inviteeAddresses) {
                    Log.d(TAG, "Sending GROUP_INVITE to ${memberAddress.redactAddress()}")

                    val inviteMemo = buildCompactInviteMemo(
                        groupId = groupId,
                        groupName = state.groupName,
                        inviterAddress = creatorAddress,
                        keyEpoch = 0,
                        encodedGroupKey = encodedGroupKey,
                        memberAddress = memberAddress
                    )

                    val sent = sendGroupMemoWithRetry(memberAddress, inviteMemo, creatorAddress)
                    // P1.4: persist the per-member outcome so group settings can show it + offer Resend.
                    setMemberInviteStatus(
                        groupId,
                        memberAddress,
                        if (sent) InviteStatus.SENT else InviteStatus.FAILED
                    )
                    if (!sent) failedInvites.add(memberAddress)
                }

                if (failedInvites.isNotEmpty()) {
                    // P0.1: surfaced as a banner on the group screen the user lands on (the old
                    // Toast-only surfacing died with this screen).
                    _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, failedInvites.toList())
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
     * Build the COMPACT GROUP_INVITE memo for [memberAddress] — fits Zcash's 512-byte memo. The
     * legacy invite embedded the full roster + a fat ECIES blob and overflowed for ANY group size
     * (#194), so groups never formed. Prefer wrapping the group key under the existing authenticated
     * KEX session (small + authenticated); fall back to a plaintext key only when no KEX exists. The
     * roster is NOT shipped — peers are discovered lazily as they post (see
     * ChatViewModel.addOrActivateGroupMember). Shared by the create-group loop and the P1.4
     * single-member Resend path.
     */
    private fun buildCompactInviteMemo(
        groupId: String,
        groupName: String,
        inviterAddress: String,
        keyEpoch: Int,
        encodedGroupKey: String,
        memberAddress: String
    ): String {
        val sessionKey = deriveSessionKey(memberAddress)
        return if (sessionKey != null) {
            val wrappedKey = E2EEncryption.encrypt(encodedGroupKey, sessionKey)
            Log.d(TAG, "Compact invite (session-wrapped key) for ${memberAddress.redactAddress()}")
            ZMSGGroupProtocol.createGroupInviteCompact(
                groupId = groupId,
                groupName = groupName,
                inviterAddress = inviterAddress,
                keyEpoch = keyEpoch,
                encryptedGroupKey = wrappedKey,
                isSessionEncrypted = true
            )
        } else {
            // Fallback: plaintext group key (backward compat, no prior KEX)
            Log.w(TAG, "No KEX with ${memberAddress.redactAddress()} - compact invite with plaintext key")
            ZMSGGroupProtocol.createGroupInviteCompact(
                groupId = groupId,
                groupName = groupName,
                inviterAddress = inviterAddress,
                keyEpoch = keyEpoch,
                encryptedGroupKey = encodedGroupKey,
                isSessionEncrypted = false
            )
        }
    }

    /**
     * P1.4 — re-run the single-member invite path for a member whose invite FAILED (from group
     * settings). Sends a fresh compact GROUP_INVITE carrying the CURRENT epoch key (a late joiner
     * needs the newest key, not the one from group creation) and updates the member's persisted
     * [InviteStatus] so the settings badge tracks the outcome.
     */
    fun resendInvite(groupId: String, memberAddress: String) {
        val inviterAddress = _currentUserAddress.value ?: return
        // SF-3 — early-return if a resend for THIS member is already in flight, so a double-tap can't
        // fire two on-chain invite txs. Add returns false if the key was already present.
        val inFlightKey = "$groupId|$memberAddress"
        if (!resendInvitesInFlight.add(inFlightKey)) {
            Log.d(TAG, "resendInvite: already in flight for ${memberAddress.redactAddress()} — ignoring")
            return
        }
        viewModelScope.launch {
            try {
                val info = zchatPreferences.getGroupInfo(groupId)
                    ?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                if (info == null) {
                    Log.e(TAG, "resendInvite: group $groupId not found")
                    return@launch
                }
                val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                val encodedGroupKey = zchatPreferences.getGroupKey(groupId, keyEpoch)
                if (encodedGroupKey == null) {
                    Log.e(TAG, "resendInvite: no group key for $groupId epoch $keyEpoch")
                    _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, listOf(memberAddress))
                    return@launch
                }
                setMemberInviteStatus(groupId, memberAddress, InviteStatus.INVITE_PENDING)
                loadGroupSettings(groupId) // show the in-flight badge immediately
                val inviteMemo = buildCompactInviteMemo(
                    groupId = groupId,
                    groupName = info.name,
                    inviterAddress = inviterAddress,
                    keyEpoch = keyEpoch,
                    encodedGroupKey = encodedGroupKey,
                    memberAddress = memberAddress
                )
                val sent = sendGroupMemoWithRetry(memberAddress, inviteMemo, inviterAddress)
                setMemberInviteStatus(
                    groupId,
                    memberAddress,
                    if (sent) InviteStatus.SENT else InviteStatus.FAILED
                )
                if (!sent) {
                    _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, listOf(memberAddress))
                }
                loadGroupSettings(groupId)
            } finally {
                resendInvitesInFlight.remove(inFlightKey)
            }
        }
    }

    /** P1.4 — persist [status] for one roster member (zchat_group_members JSON). */
    private fun setMemberInviteStatus(groupId: String, memberAddress: String, status: InviteStatus) {
        val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return
        val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
        val updated = members.map {
            if (it.address == memberAddress) it.copy(inviteStatus = status) else it
        }
        zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
    }

    /** True iff we are the admin (creator) of [groupId] — mirrors the kick/rotate gate (#204). */
    private fun isGroupAdmin(groupId: String): Boolean =
        zchatPreferences.isGroupSelfCreated(groupId) ||
            (
                zchatPreferences.getGroupInfo(groupId)
                    ?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                    ?.creatorAddress
                    ?.let { zchatPreferences.isSelfAddress(it) } ?: false
            )

    /**
     * Populate [addMemberCandidates] for the Add-member picker on an EXISTING group: saved contacts +
     * KEX'd conversation peers (same set the create-group picker uses), MINUS anyone already ACTIVE or
     * INVITED in this group. A previously-removed (LEFT) member is NOT excluded — they can be re-added.
     */
    fun loadAddMemberCandidates(groupId: String) {
        // Clear stale candidates so re-opening the picker (or a different group) never flashes a
        // previous group's list before the fresh query resolves.
        _addMemberCandidates.value = emptyList()
        viewModelScope.launch {
            val saved = contactBook.getAllContacts().filterNot { zchatPreferences.isSelfAddress(it.address) }
            val savedCanon = saved.map { it.address.trim().lowercase() }.toSet()
            val kexPeers = zchatPreferences.getAllPeerToConvIdMappings().keys
                .map { zchatPreferences.resolvePeerAddress(it) }
                .filter {
                    it.startsWith("u1") &&
                        it.trim().lowercase() !in savedCanon &&
                        !zchatPreferences.isSelfAddress(it) &&
                        zchatPreferences.getE2EPeerPublicKey(it) != null
                }
                .distinct()
                .map { Contact(address = it, name = zchatPreferences.getDisplayName(it)) }

            val members = zchatPreferences.getGroupMembers(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) } ?: emptyList()
            // Block anyone already in the group as ACTIVE or INVITED (LEFT members are re-addable).
            val blockedCanon = members
                .filter { it.status == MemberStatus.ACTIVE || it.status == MemberStatus.INVITED }
                .map { zchatPreferences.resolvePeerAddress(it.address) }
                .toSet()

            _addMemberCandidates.value = (saved + kexPeers)
                .filterNot { zchatPreferences.resolvePeerAddress(it.address) in blockedCanon }
        }
    }

    /**
     * ADMIN-ONLY: add [memberAddress] to an EXISTING group. Adds them to the roster as INVITED and sends
     * a compact GROUP_INVITE carrying the CURRENT epoch key (free over NOSTR when they're a known peer,
     * on-chain fallback otherwise — see sendGroupMemoWithRetry). No-op if we aren't the admin or the
     * member is already ACTIVE/INVITED. The invite badge tracks the outcome (Inviting… → Invited/Failed).
     */
    fun addMemberToGroup(groupId: String, memberAddress: String) {
        val inviterAddress = _currentUserAddress.value ?: return
        viewModelScope.launch {
            if (!isGroupAdmin(groupId)) {
                Log.w(TAG, "addMemberToGroup: only the group admin may add members — aborting")
                return@launch
            }
            val info = zchatPreferences.getGroupInfo(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return@launch
            val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
            val encodedGroupKey = zchatPreferences.getGroupKey(groupId, keyEpoch) ?: run {
                Log.e(TAG, "addMemberToGroup: no group key for $groupId epoch $keyEpoch")
                return@launch
            }

            val members = zchatPreferences.getGroupMembers(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) } ?: emptyList()
            val canon = zchatPreferences.resolvePeerAddress(memberAddress)
            if (members.any {
                    zchatPreferences.resolvePeerAddress(it.address) == canon &&
                        (it.status == MemberStatus.ACTIVE || it.status == MemberStatus.INVITED)
                }
            ) {
                Log.d(TAG, "addMemberToGroup: ${memberAddress.redactAddress()} already active/invited")
                loadGroupSettings(groupId)
                return@launch
            }

            // Add (or re-activate a LEFT row) as INVITED so the roster shows them immediately as pending.
            val existing = members.find { zchatPreferences.resolvePeerAddress(it.address) == canon }
            val updatedMembers = if (existing != null) {
                members.map {
                    if (it.address == existing.address) {
                        it.copy(status = MemberStatus.INVITED, inviteStatus = InviteStatus.INVITE_PENDING)
                    } else {
                        it
                    }
                }
            } else {
                members + GroupMember(
                    address = memberAddress,
                    publicKey = null,
                    joinedAt = Instant.now(),
                    status = MemberStatus.INVITED,
                    isAdmin = false,
                    nickname = contactBook.getContact(memberAddress)?.name,
                    inviteStatus = InviteStatus.INVITE_PENDING,
                )
            }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updatedMembers))
            loadGroupSettings(groupId)

            val inviteMemo = buildCompactInviteMemo(
                groupId = groupId,
                groupName = info.name,
                inviterAddress = inviterAddress,
                keyEpoch = keyEpoch,
                encodedGroupKey = encodedGroupKey,
                memberAddress = memberAddress,
            )
            val sent = sendGroupMemoWithRetry(memberAddress, inviteMemo, inviterAddress)
            // Write the terminal badge on the ACTUAL stored roster row. For a re-added LEFT member the row
            // is keyed on the canonicalized `existing.address`, which can differ from the raw picked
            // address — using the raw address would match nothing and leave the badge stuck on "Inviting…".
            setMemberInviteStatus(
                groupId,
                existing?.address ?: memberAddress,
                if (sent) InviteStatus.SENT else InviteStatus.FAILED,
            )
            if (!sent) {
                _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, listOf(memberAddress))
            }
            loadGroupSettings(groupId)
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
        // #11 — prefer a FREE NOSTR delivery. Group invites/messages/signed-control were carried in an
        // on-chain (Vault) memo UNCONDITIONALLY, so a near-zero-balance wallet could not form or message
        // a group even when every member was already reachable over NOSTR (they DM for free). When the
        // member is a known NOSTR peer and the outbound publisher is ready, deliver over NIP-17 (no ZEC,
        // no spendable note needed) exactly like a 1:1 Tunnel/Open message; the recipient authenticates
        // the seal pubkey and runs the SAME per-type trust gates as the on-chain path (compact-invite k2
        // requires the authenticated KEX session; #187 signed kick/key). Falls through to the on-chain
        // path below when the peer has no NOSTR key or the publisher isn't ready.
        if (trySendGroupMemoOverNostr(memberAddress, inviteMemo)) return true

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
                // Coroutine cancellation (user backs out of the minutes-long "Creating…" screen, or
                // process death) is NOT a permanent send failure — let it propagate instead of being
                // swallowed as `return false`, which silently loses every remaining group invite/memo.
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isTransientInsufficientFunds(e) && attempt < MAX_INVITE_RETRIES) {
                    attempt++
                    Log.w(
                        TAG,
                        "Group memo to ${memberAddress.redactAddress()} hit transient insufficient funds " +
                            "(previous send's change pending) — waiting for next block, retry $attempt/$MAX_INVITE_RETRIES"
                    )
                    if (!waitForNextBlock()) {
                        Log.e(TAG, "Gave up waiting for a new block — group memo to ${memberAddress.redactAddress()} failed")
                        return false
                    }
                } else {
                    Log.e(TAG, "Group memo to ${memberAddress.redactAddress()} failed permanently", e)
                    return false
                }
            }
        }
    }

    /**
     * #11 — try to deliver a group protocol memo (invite / message / signed control) over NOSTR for
     * FREE, mirroring the 1:1 Tunnel/Open channel. Returns true only if it was published to at least one
     * relay. Returns false (→ caller falls back to on-chain) when the member isn't a known NOSTR peer,
     * the publisher isn't ready, or publishing yields zero acks. The recipient routes inbound NOSTR
     * group memos through the SAME processGroupMessage as on-chain, so the trust gates are identical.
     * We try the member's stored representation and its canonical resolution (#205/#214 address drift)
     * before giving up, so a drifted UA rep doesn't needlessly force an on-chain (paid) send.
     */
    private suspend fun trySendGroupMemoOverNostr(memberAddress: String, memo: String): Boolean {
        val peerPub = findPeerNostrPubkeyAnyRep(memberAddress) ?: return false
        if (!co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()) return false
        return try {
            val result = co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(memo, peerPub)
            val delivered = result.acks > 0
            if (delivered) {
                Log.d(
                    TAG,
                    "Group memo to ${memberAddress.redactAddress()} sent FREE over NOSTR (${result.acks} relay ack)"
                )
            }
            delivered
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "NOSTR group memo to ${memberAddress.redactAddress()} failed — falling back to on-chain", e)
            false
        }
    }

    /**
     * Rep-tolerant NOSTR-pubkey lookup (#205/#214 address drift). A peer's NOSTR key may be stored
     * under a DIFFERENT unified-address representation than the (resolved) group-roster address — e.g.
     * the group fan-out canonicalizes a member to one rep while the KEX/first-contact stored the key
     * under another. A rep-sensitive lookup then wrongly falls back to a paid on-chain send. Try the
     * given address, its canonical, then EVERY known peer rep that canonicalizes to the same peer.
     */
    private fun findPeerNostrPubkeyAnyRep(address: String): String? {
        zchatPreferences.getPeerNostrPubkey(address)?.let { return it }
        val canonical = zchatPreferences.resolvePeerAddress(address)
        if (canonical != address) zchatPreferences.getPeerNostrPubkey(canonical)?.let { return it }
        return zchatPreferences.getAllConversationPeerAddresses().asSequence()
            .filter { it != address && zchatPreferences.resolvePeerAddress(it) == canonical }
            .mapNotNull { zchatPreferences.getPeerNostrPubkey(it) }
            .firstOrNull()
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

        // Canonicalize the kicked-member and self comparisons (#205/#214 address drift). A roster that
        // still holds the kicked peer under a SECOND unified-address representation must NOT keep an ACTIVE
        // row that then receives the freshly-rotated key — that would let the kicked member keep decrypting
        // every post-kick message. Match by resolved (canonical) address, and use the hash-tolerant
        // self-check for the admin exclusion (same pattern as sendGroupMessage).
        val kickedCanonical = kickedAddress?.let { zchatPreferences.resolvePeerAddress(it) }
        val recipients = members.filter {
            it.status == MemberStatus.ACTIVE &&
                !zchatPreferences.isSelfAddress(it.address) &&
                (kickedCanonical == null || zchatPreferences.resolvePeerAddress(it.address) != kickedCanonical)
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
            // Mark EVERY roster row that canonicalizes to the kicked peer as LEFT (not just the exact rep
            // passed in), so fan-out stops paying to message any drifted representation of them.
            val updated = members.map {
                if (zchatPreferences.resolvePeerAddress(it.address) == kickedCanonical) it.copy(status = MemberStatus.LEFT) else it
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
                    // P0.1: nothing was transmitted — tell the user instead of failing silently
                    // (the composer keeps the typed message, see GroupDetailView).
                    _groupSendEvent.value = GroupSendResult.AllFailed(groupId)
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

                // Removes the optimistic pending bubble added above — used when the send reaches NO
                // recipient, so it never lingers as a permanent "[Sending…]" that looks delivered.
                fun dropOptimisticPending() {
                    val cur = pendingGroupMessages.value.toMutableMap()
                    cur[groupId] = cur[groupId].orEmpty().filterNot { it.id == pendingMessage.id }
                    pendingGroupMessages.value = cur
                }

                if (recipients.isEmpty()) {
                    // The send reached NO ONE. Don't leave a forever-"pending" bubble that looks
                    // delivered, and don't discard the user's text: drop the optimistic message and KEEP
                    // the draft so they can retry once a member becomes ACTIVE (their GROUP_ACCEPT, #214,
                    // or their first post).
                    Log.w(TAG, "Group $groupId has no ACTIVE recipients — message NOT transmitted; draft kept")
                    // P0.1: was a silent Log.w — a fresh group's invitees are still INVITED until they
                    // GROUP_ACCEPT, so the creator's first message reached nobody with no explanation.
                    _groupSendEvent.value = GroupSendResult.NoActiveRecipients(groupId)
                    dropOptimisticPending()
                    loadGroupDetail(groupId)
                    return@launch
                }

                Log.d(TAG, "Sending group message to ${recipients.size} members")

                // Send to each recipient through the BLOCK-AWARE RETRY path. A group message is a
                // shielded tx like any other, so on a single-note wallet the first message right after
                // the invite/accept (which just consumed the only note) fails with TRANSIENT insufficient
                // funds. sendGroupMemoWithRetry waits for the change to confirm and retries (#217).
                var sentCount = 0
                for (recipient in recipients) {
                    Log.d(TAG, "Sending group message to ${recipient.address.redactAddress()}")
                    if (sendGroupMemoWithRetry(recipient.address, memo, senderAddress)) {
                        sentCount++
                    } else {
                        Log.e(TAG, "Group message to ${recipient.address.redactAddress()} failed after retries")
                    }
                    // Small delay between sends to avoid overwhelming the wallet
                    delay(500)
                }

                if (sentCount == 0) {
                    // Delivered to nobody (every recipient failed after retries). Same as the empty case:
                    // drop the false pending bubble and preserve the draft for retry.
                    Log.w(TAG, "Group $groupId: delivered to 0/${recipients.size} members — draft kept for retry")
                    _groupSendEvent.value = GroupSendResult.AllFailed(groupId) // P0.1: visible, not just a log line
                    dropOptimisticPending()
                    loadGroupDetail(groupId)
                    return@launch
                }
                if (sentCount < recipients.size) {
                    Log.w(TAG, "Group $groupId: partial delivery to $sentCount/${recipients.size} members")
                    // P0.1: visible, not just a log line
                    _groupSendEvent.value = GroupSendResult.PartialDelivery(groupId, sentCount, recipients.size)
                }

                // Delivered to at least one member — clear the draft and refresh.
                zchatPreferences.clearGroupDraft(groupId)
                loadGroupDetail(groupId)

            } catch (e: Exception) {
                // Cancellation (screen closed / process death) is not a send failure — propagate.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to send group message", e)
                // P0.1: an unexpected error is still a failed send — make it visible so the composer
                // keeps the typed message instead of clearing as if it had been delivered.
                _groupSendEvent.value = GroupSendResult.AllFailed(groupId)
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
                            // #199/#217: route through the block-aware, NOSTR-free-preferring path instead of
                            // a bare createChunkedMessageProposal in a fixed-500ms loop. The old loop made the
                            // 2nd+ GROUP_LEAVE silently fail with transient insufficient funds on a single-note
                            // wallet (change from send #1 not yet confirmed), so those members never learned we
                            // left and kept paying to fan out to us; it also never used the free NOSTR path.
                            sendGroupMemoWithRetry(recipient.address, leaveMemo, userAddress)
                            // Small delay between sends
                            delay(500)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
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
