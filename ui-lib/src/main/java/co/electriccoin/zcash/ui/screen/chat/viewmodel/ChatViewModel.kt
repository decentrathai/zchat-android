package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.SdkSynchronizer
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
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
import co.electriccoin.zcash.ui.common.util.redactUrl
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

    private val uploadProgressTracker = co.electriccoin.zcash.ui.screen.chat.filesharing.UploadProgressTracker()
    val uploadProgress: StateFlow<Float?> = uploadProgressTracker.progress

    // Disclaimer dialog state
    private val _showCostDisclaimer = MutableStateFlow(false)
    val showCostDisclaimer: StateFlow<Boolean> = _showCostDisclaimer.asStateFlow()

    // Pending message (stored when disclaimer needs to be shown).
    // Carries all send params so reply context (replyToId, amountZatoshi) isn't lost.
    private data class PendingMessageParams(
        val peerAddress: String,
        val message: String,
        val replyToId: String? = null,
        val replyPreview: String = "",
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

    // Per-conversation last-read markers (peerAddress -> epoch millis). Seeded from preferences and
    // updated by markConversationRead(); folded into the conversation flow so the unread badge
    // recomputes the moment a conversation is opened.
    private val readMarkers = MutableStateFlow<Map<String, Long>>(emptyMap())

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
    private val messageProcessors = java.util.concurrent.ConcurrentHashMap<String, co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor>()

    // File sharing: decrypted image cache + download tracking
    private var _fileCache: co.electriccoin.zcash.ui.screen.chat.filesharing.FileDownloadCache? = null
    private val fileDownloadsInProgress = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // Hard ceiling on a single ZFILE download. The `url` in a ZFILE memo is attacker-controlled
    // (a peer can point it at an arbitrarily large blob), and the body was previously buffered whole
    // into memory — a multi-GB response would OOM-kill the app. 25 MB comfortably covers compressed
    // images + short voice clips while bounding the worst case.
    private val maxFileDownloadBytes = 25L * 1024 * 1024

    // Per-hash download progress (0..1) for the receiver. UI reads via [fileDownloadProgress].
    // Map is replaced wholesale on each update so StateFlow emits and Compose recomposes.
    private val _fileDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileDownloadProgress: StateFlow<Map<String, Float>> = _fileDownloadProgress.asStateFlow()

    private fun updateFileProgress(hash: String, fraction: Float?) {
        // CAS-loop via .update so two concurrent downloads can't lose each other's writes.
        _fileDownloadProgress.update { current ->
            current.toMutableMap().apply {
                if (fraction == null) remove(hash) else put(hash, fraction.coerceIn(0f, 1f))
            }
        }
    }

    // Per-hash download FAILURE set for the receiver. Lives separately from progress because the
    // download's `finally` clears progress on every outcome; this lets the UI keep showing a
    // "Download failed — tap to retry" affordance after the bar disappears. UI reads via
    // [fileDownloadFailures]; tapping retry re-invokes downloadAndCacheFile, which clears the entry.
    private val _fileDownloadFailures = MutableStateFlow<Set<String>>(emptySet())
    val fileDownloadFailures: StateFlow<Set<String>> = _fileDownloadFailures.asStateFlow()

    private fun setFileDownloadFailed(hash: String, failed: Boolean) {
        _fileDownloadFailures.update { current ->
            if (failed) current + hash else current - hash
        }
    }

    private fun getFileCache(context: android.content.Context): co.electriccoin.zcash.ui.screen.chat.filesharing.FileDownloadCache {
        return _fileCache ?: co.electriccoin.zcash.ui.screen.chat.filesharing.FileDownloadCache(
            java.io.File(context.cacheDir, "zchat_files")
        ).also { _fileCache = it }
    }

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
        // Load conversation read markers from preferences (drives the unread badge)
        readMarkers.value = zchatPreferences.getAllLastReadTimestamps()
        // Load pending messages from preferences (persisted across navigation)
        loadPendingMessagesFromPrefs()
        // Load persisted call-log entries (incoming/outgoing/missed call history)
        loadCallLogsFromPrefs()
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
        observeNostrInbound()
        observeLocalMessages()
    }

    /**
     * Subscribe to inbound NIP-17 DMs surfaced by the foreground service. Each DM is
     * pushed into [pendingMessages] as a ChatMessage so it renders alongside on-chain
     * messages in the same conversation flow.
     */
    private fun observeNostrInbound() {
        viewModelScope.launch {
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.inbound.collect { chat ->
                // Drop ZBOOT handshakes here — they're handled by routeIncomingBoot below.
                if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(chat.plaintext)) {
                    routeIncomingBoot(chat.peerAddress, chat.plaintext)
                    return@collect
                }
                // Clamp a sender-asserted (possibly future-skewed) timestamp to "now" so a peer can't
                // reorder the thread by pinning their message to the bottom with a future date.
                val ts = minOf(Instant.ofEpochSecond(chat.createdAtSec), Instant.now())
                // An inbound ZREACT over NOSTR is metadata, not a chat row: attach it to the target
                // message's reactions (matched on ChatMessage.id == targetTxId) instead of rendering
                // the raw "ZREACT|…" memo as a text bubble. Mirrors the on-chain reactionsByTarget path.
                if (co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol.isReaction(chat.plaintext)) {
                    val parsedReaction = co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol.parseReaction(chat.plaintext, addressCache)
                    if (parsedReaction != null) {
                        val reaction = co.electriccoin.zcash.ui.screen.chat.model.MessageReaction(
                            emoji = parsedReaction.emoji,
                            senderAddress = parsedReaction.senderAddress ?: chat.peerAddress,
                            timestamp = ts,
                        )
                        // Dedup: relays REPLAY stored gift-wraps on every (re)subscribe and the same
                        // reaction is published to multiple relays, so without this guard a single
                        // "👍" would attach N times and the count badge would grow on every reconnect.
                        // Skip if the same sender already reacted with the same emoji to this target.
                        pendingMessages.update { current ->
                            current.map { m ->
                                if (m.id == parsedReaction.targetTxId &&
                                    m.reactions.none { it.emoji == reaction.emoji && it.senderAddress == reaction.senderAddress }
                                ) {
                                    m.copy(reactions = m.reactions + reaction)
                                } else {
                                    m
                                }
                            }
                        }
                        // #210: persist so the reaction survives the next pendingMessages reload (which
                        // overwrites the in-memory list from storage). The in-memory update above only
                        // matches a target still in pendingMessages; persisting unconditionally also
                        // covers a target that has already been re-persisted (re-applied on load).
                        zchatPreferences.addNostrReaction(
                            parsedReaction.targetTxId,
                            reaction.emoji,
                            reaction.senderAddress ?: chat.peerAddress,
                            ts.toEpochMilli(),
                        )
                    }
                    return@collect
                }
                // Strip an embedded reply-ref BEFORE any ZFILE/text parsing: the U+0001 marker prepends
                // the body (even a "ZFILE|…" body), so file detection and the visible bubble must run on
                // the stripped text — otherwise a reply-to-file arrives as raw "…RPL…ZFILE|…" text and
                // the reply threading + media render are both lost. replyToId threads the rendered bubble.
                val (incomingReplyTo, incomingReplyPreview, strippedBody) = untagReply(chat.plaintext)
                // Dedup on the unique gift-wrap event id (identical across relays for the SAME
                // message, distinct for different messages) — keying on content+timestamp would
                // collapse two distinct messages that share text within the same second.
                // #202: use the STABLE rumor id as the message id so a reaction/reply the SENDER attaches
                // to its own copy (also keyed on the rumor id) correlates to OUR copy here. Fall back to
                // the per-gift-wrap eventId for legacy peers that don't send a rumor id.
                val baseId = if (chat.rumorId.isNotEmpty()) "nmsg-${chat.rumorId}" else "nostr-${chat.eventId}"
                // Parse a ZFILE ref (image/file/voice sent over NOSTR) into a file bubble with the
                // file fields set, so the view auto-downloads + renders the media exactly like the
                // on-chain receive path. Without this, a file sent over NOSTR arrived as raw "ZFILE|…"
                // text and the image/file/voice never displayed or downloaded.
                val nostrFile = if (co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(strippedBody)) {
                    co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(strippedBody)
                } else {
                    null
                }
                val msg = if (nostrFile != null) {
                    ChatMessage(
                        id = baseId,
                        txId = null,
                        text = "📎 ${nostrFile.displayText}",
                        timestamp = ts,
                        isOutgoing = false,
                        peerAddress = chat.peerAddress,
                        isPending = false,
                        status = MessageStatus.SENT,
                        replyToId = incomingReplyTo,
                        replyToPreview = incomingReplyPreview,
                        fileHash = nostrFile.hash,
                        fileZfileContent = strippedBody,
                        fileBlurhash = nostrFile.blurhash.takeIf { it.isNotEmpty() },
                        fileType = nostrFile.type,
                        fileViewOnce = nostrFile.viewOnce,
                        fileViewed = nostrFile.viewOnce && zchatPreferences.isFileViewed(nostrFile.hash),
                    )
                } else {
                    ChatMessage(
                        id = baseId,
                        txId = null,
                        text = strippedBody,
                        timestamp = ts,
                        isOutgoing = false,
                        peerAddress = chat.peerAddress,
                        isPending = false,
                        status = MessageStatus.SENT,
                        replyToId = incomingReplyTo,
                        replyToPreview = incomingReplyPreview,
                    )
                }
                // Deduplicate — the same gift-wrap may arrive on multiple relays.
                var addedNew = false
                pendingMessages.update { current ->
                    if (current.any { it.id == msg.id }) {
                        current
                    } else {
                        addedNew = true
                        current + msg
                    }
                }
                // Persist the inbound NOSTR row so it survives process death / ChatViewModel recreation
                // (it has no ledger entry to rebuild from). Keyed on msg.id (the unique gift-wrap event id),
                // so a replayed gift-wrap upserts the same record rather than duplicating it.
                if (addedNew) {
                    zchatPreferences.addPendingMessage(
                        ZchatPreferences.PendingMessageData(
                            id = msg.id,
                            text = msg.text,
                            timestampMillis = msg.timestamp.toEpochMilli(),
                            peerAddress = msg.peerAddress,
                            isOutgoing = false,
                            isPending = false,
                            status = MessageStatus.SENT.name,
                            replyToId = msg.replyToId,
                            replyToPreview = msg.replyToPreview,
                            fileZfileContent = msg.fileZfileContent
                        )
                    )
                }
            }
        }
    }

    /**
     * A ZBOOT can arrive either via a shielded memo (handled in convertToConversations)
     * or via NOSTR if a peer published their boot through the relay. Either way: stash
     * the pubkey + relay so future routeOutgoing() calls pick NostrDirect.
     */
    /**
     * @return true ONLY if the ZBOOT verified and the peer's NOSTR pubkey was stored (the call/DM
     * channel is now actually established). false on every drop path (unverifiable, bad signature,
     * key-changed) — the caller MUST NOT claim "connection established" when this returns false, or
     * the UI lies (shows "established" while no pubkey is stored and calls can't route). This was the
     * bug: the on-chain ZBOOT handler printed the established system note unconditionally.
     */
    private fun routeIncomingBoot(peerAddress: String, raw: String): Boolean {
        // parse() accepts only the SIGNED v2 ZBOOT; unsigned/malformed are dropped.
        val boot = co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.parse(raw) ?: return false
        // AUTHENTICATE before trusting the NOSTR identity. Shielded receives hide the real sender and
        // the memo's address field is attacker-controlled plaintext, so we MUST verify the ZBOOT was
        // signed by the peer's KEX-verified E2E identity key — otherwise an attacker could inject a
        // ZBOOT claiming any NOSTR pubkey and MITM every NOSTR DM and voice/video call.
        val peerE2EPub = zchatPreferences.getE2EPeerPublicKey(peerAddress)
        if (peerE2EPub == null) {
            Log.w("ZCHAT_NOSTR", "ZBOOT from ${peerAddress.take(16)}… ignored: no verified E2E identity yet (complete key exchange first)")
            return false
        }
        val ok = runCatching {
            E2EEncryption.verify(peerE2EPub, boot.signedData(), boot.signature)
        }.getOrDefault(false)
        if (!ok) {
            Log.w("ZCHAT_NOSTR", "ZBOOT signature INVALID for ${peerAddress.take(16)}… — possible MITM, ignoring")
            return false
        }
        // Key-change guard: never silently overwrite an established NOSTR identity. A changed key on
        // an existing peer is a MITM/rotation signal — flag it (reuses the E2E key-changed banner) and
        // require explicit user re-confirmation instead of auto-accepting.
        val existing = zchatPreferences.getPeerNostrPubkey(peerAddress)
        if (existing != null && existing != boot.senderNostrPubkeyHex) {
            zchatPreferences.setE2EKeyChanged(peerAddress, true)
            // A peer-identity change invalidates any prior out-of-band verification.
            zchatPreferences.setE2EVerified(peerAddress, false)
            Log.w("ZCHAT_NOSTR", "Peer NOSTR key CHANGED for ${peerAddress.take(16)}… — flagged, NOT auto-accepted")
            return false
        }
        zchatPreferences.setPeerNostrPubkey(peerAddress, boot.senderNostrPubkeyHex)
        zchatPreferences.setPeerNostrRelay(peerAddress, boot.relayUrl)
        // Recipient-side mode sync: completing the bootstrap as the RESPONDER (we received the peer's
        // ZBOOT for a tunnel THEY initiated) means NOSTR is now available with this peer. If our side
        // is still the default VAULT — typical for a received first-contact where we never explicitly
        // picked a mode — upgrade to TUNNEL so OUR outbound also flows free/instant over NOSTR.
        // Without this the conversation is asymmetric: the initiator is on free NOSTR while we keep
        // charging on-chain (and showing the "shielded on-chain" banner) for every reply. This is a
        // strict upgrade — TUNNEL is end-to-end encrypted exactly like VAULT, just free + off-chain.
        if (zchatPreferences.getConversationMode(peerAddress) ==
            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT
        ) {
            zchatPreferences.setConversationMode(
                peerAddress,
                co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL,
            )
            Log.d("ZCHAT_NOSTR", "Responder bootstrap complete — upgraded conversation to TUNNEL for ${peerAddress.take(16)}…")
        }
        Log.d("ZCHAT_NOSTR", "Bootstrapped (verified) with ${peerAddress.take(16)}… on ${boot.relayUrl}")
        // Peer NOSTR key now known → flush any TUNNEL messages queued while it was unknown (these were
        // held off-chain, never charged) so they go out over NOSTR now instead of being lost.
        flushTunnelPendingPayloads(peerAddress, boot.senderNostrPubkeyHex)
        // Reply with OUR ZBOOT so the handshake completes BOTH ways — otherwise it's one-way and every
        // Call tap just re-requests. We reply with a ZBOOT (NOT a KEX): the KEX can't carry the NOSTR
        // pubkey on first contact (the address fills the 512-byte memo), and by now the peer holds our
        // E2E key (they verified this inbound ZBOOT's counterpart KEX) so they can authenticate ours.
        //
        // CRITICAL: gate on "have WE sent OUR identity?" (sendNostrBootHandshake's internal
        // getSentNostrBootPubkey guard), NOT on "do we already have THEIRS?" (`existing`). Those differ:
        // a peer who re-processed our OLD on-chain ZBOOT from chain history (reinstall + rescan, or a
        // half-finished prior handshake) ALREADY holds our pubkey (`existing != null`) yet has NEVER
        // sent us theirs — gating on `existing == null` would skip the reply forever and the tunnel
        // stays one-way (the exact stale-state deadlock seen in 2-device testing). Always calling
        // sendNostrBootHandshake fixes that; its idempotency (skip if we already sent THIS identity)
        // still prevents a ZBOOT ping-pong: once we've delivered ours, a later inbound ZBOOT is a no-op.
        sendNostrBootHandshake(peerAddress)
        return true
    }

    /**
     * Derive OUR NOSTR identity (x-only pubkey as 64-hex) + preferred relay from the wallet seed.
     * Same derivation [ensureNostrBootstrapSent] uses for the ZBOOT handshake. Returns null if the
     * wallet/seed isn't available yet. Used to piggyback our NOSTR keys onto the FIRST KEX/KEXACK
     * (BUG-4 one-tap calling) so a separate ZBOOT round-trip isn't required.
     */
    private suspend fun getOurNostrPubkey(): Pair<String, String>? {
        return try {
            val wallet = persistableWalletProvider.requirePersistableWallet()
            val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
            val identity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(seed, zchatPreferences.getNostrRotationIndex())
            val ourPubHex = identity.publicKey.joinToString("") { "%02x".format(it) }
            val relay = co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS.first()
            ourPubHex to relay
        } catch (e: Exception) {
            Log.w("ZCHAT_NOSTR", "Could not derive our NOSTR pubkey for KEX: ${e.message}")
            null
        }
    }

    /**
     * KEX one-tap calling (BUG-4): if a received KEX/KEXACK carried the peer's NOSTR pubkey + relay,
     * store them immediately so voice/video calls connect without waiting for a separate ZBOOT.
     *
     * Mirrors the trust model of [routeIncomingBoot]: the NOSTR identity is TOFU-bound to the
     * KEX-verified E2E key, and a CHANGED key on an existing peer is treated as a MITM/rotation
     * signal — we refuse to silently overwrite, flag the key-changed banner, and clear verification.
     * The KEX signature itself is verified by the caller before this runs.
     */
    private fun applyKEXNostr(peerAddress: String, nostrPubkeyHex: String?, relayUrl: String?) {
        if (nostrPubkeyHex == null || relayUrl == null) return // legacy KEX → fall back to ZBOOT
        val existing = zchatPreferences.getPeerNostrPubkey(peerAddress)
        if (existing != null && existing != nostrPubkeyHex) {
            zchatPreferences.setE2EKeyChanged(peerAddress, true)
            zchatPreferences.setE2EVerified(peerAddress, false)
            Log.w("ZCHAT_NOSTR", "Peer NOSTR key in KEX CHANGED for ${peerAddress.take(16)}… — flagged, NOT auto-accepted")
            return
        }
        zchatPreferences.setPeerNostrPubkey(peerAddress, nostrPubkeyHex)
        zchatPreferences.setPeerNostrRelay(peerAddress, relayUrl)
        Log.d("ZCHAT_NOSTR", "Stored peer NOSTR pubkey from KEX for ${peerAddress.take(16)}… (one-tap calling enabled)")
        // Peer NOSTR key learned from the handshake → flush any off-chain-queued TUNNEL messages.
        flushTunnelPendingPayloads(peerAddress, nostrPubkeyHex)
    }

    /**
     * Bootstrap a NOSTR tunnel with [peerAddress] by sending OUR signed KEX. This is leg ONE of the
     * Tunnel handshake: the KEX delivers our E2E identity key + our authenticated address, which lets
     * the peer encrypt to us AND verify us (and reply with a KEXACK). Leg TWO — delivering our NOSTR
     * pubkey so voice/video calls + NOSTR DMs can route to us — happens in [sendNostrBootHandshake],
     * sent SEPARATELY and AFTER the E2E exchange (a first-contact KEX has no room for the NOSTR fields
     * under the 512-byte memo cap, and a second simultaneous on-chain tx would race the KEX for the one
     * spendable note). Idempotent: skipped once the peer's NOSTR pubkey is known (handshake complete) or
     * once we've already sent our KEX (unless [force], used by a Call-tap to recover a stuck/one-way
     * handshake). Triggered on switch to Tunnel/Open and on a call attempt before keys are exchanged.
     */
    fun ensureNostrBootstrapSent(peerAddress: String, force: Boolean = false) {
        if (zchatPreferences.getPeerNostrPubkey(peerAddress) != null) return // exchange already complete
        viewModelScope.launch {
            try {
                val ourAddress = _currentUserAddress.value ?: return@launch
                // LEG ONE — our KEX (E2E identity + authenticated address). The peer stores it
                // (handleKEXMessage) and replies with a KEXACK; our NOSTR pubkey is delivered SEPARATELY
                // in leg two below. The bootSent flag stops re-spending the KEX on every trigger (a
                // Call-tap passes force=true to re-publish for recovery). sendKEXMessage has its own
                // try/catch and resets isOwnBootSent on failure.
                //
                // The old flow published the ZBOOT in the SAME pass as the KEX. Under the single-note
                // (one spend per block) constraint the two on-chain txs RACED for the one spendable note:
                // the ZBOOT grabbed it and the KEX failed with "No Orchard input found", so the peer never
                // received our E2E key and the tunnel deadlocked. Sending the KEX alone (when due) gives
                // it the note to itself; the ZBOOT below self-gates until the E2E exchange lands, so on a
                // first call the two never contend.
                if (force || !zchatPreferences.isOwnBootSent(peerAddress)) {
                    zchatPreferences.setOwnBootSent(peerAddress, true)
                    sendKEXMessage(peerAddress, ourAddress)
                    Log.d("ZCHAT_NOSTR", "Tunnel bootstrap: sent KEX (E2E identity + address) to ${peerAddress.take(16)}…")
                }
                // LEG TWO — (re)attempt the ZBOOT that delivers our NOSTR pubkey. sendNostrBootHandshake
                // SELF-GATES: it no-ops until we hold the peer's E2E key (so before the KEXACK lands it
                // bails — no contention with the KEX above for the single note) and once our pubkey is
                // delivered (idempotent). Calling it here — on EVERY bootstrap trigger, e.g. each chat
                // re-open via the AndroidChatDetail LaunchedEffect — is the fix for the single-note ZBOOT
                // deadlock: the KEX consumes the only note, so the sequenced ZBOOT must wait for the KEX's
                // CHANGE to mature (~10 confirmations). The per-send block-retry window is shorter, so the
                // first ZBOOT attempt (fired once on KEXACK-receipt) gives up with "Insufficient balance
                // (have 0)" while the change is still pending — and nothing ever retried it, leaving the
                // tunnel one-way forever (root-caused in 2-device testing). Re-attempting on chat-open
                // lets the ZBOOT land once the change is spendable, completing the handshake.
                sendNostrBootHandshake(peerAddress)
            } catch (e: Exception) {
                zchatPreferences.setOwnBootSent(peerAddress, false)
                Log.w("ZCHAT_NOSTR", "bootstrap send failed: ${e.message}")
            }
        }
    }

    /**
     * Deliver OUR NOSTR identity to [peerAddress] via a compact signed ZBOOT (~200 bytes), the
     * SECOND leg of the Tunnel handshake (the first being the KEX that delivers our E2E key + address).
     *
     * WHY a separate ZBOOT instead of piggybacking the NOSTR pubkey on the KEX: a first-contact KEX
     * already carries our full u1 address (~213 B) so a recipient with no convId mapping can recover +
     * verify us — that plus pubkey+signature is ~481 B, leaving NO room for the NOSTR pubkey+relay
     * (~95 B) under the 512-byte memo cap (measured: address+NOSTR = ~575 B → MemoTooLong → the whole
     * send fails). So [buildKEXWire] deliberately DROPS the NOSTR fields whenever the address is
     * present, and the NOSTR identity must travel in its own memo. The ZBOOT is convId-routed (no
     * address needed) so it fits easily and is verified against the peer's KEX-established E2E key.
     *
     * SEQUENCING (single-note rule — one shielded spend per block): this MUST NOT be sent in the same
     * pass as a KEX/KEXACK, or the two on-chain txs race for the one spendable Orchard note and one
     * fails ("No Orchard input found"). Callers invoke it only AFTER the E2E exchange is established —
     * the initiator on KEXACK-receipt, the responder via [routeIncomingBoot]'s auto-reply on receiving
     * the initiator's ZBOOT — so each handler emits exactly one tx.
     *
     * Idempotent per (peer, our-NOSTR-identity): we record the pubkey we sent and skip a resend of the
     * SAME identity (prevents on-chain drain + a ZBOOT ping-pong), but a rotated identity (different
     * pubkey) is re-delivered. A failed send re-arms by clearing the marker.
     */
    private fun sendNostrBootHandshake(peerAddress: String) {
        // Atomic claim: if a ZBOOT send to this peer is already in flight, bail instantly so the N
        // concurrent inbound-handler invocations don't each launch a duplicate on-chain send (which
        // would contend for the single note and storm transient failures). Cleared in finally.
        if (!nostrBootInFlight.add(peerAddress)) {
            Log.d("ZCHAT_NOSTR", "ZBOOT send already in flight for ${peerAddress.take(16)}… — skipping duplicate")
            return
        }
        viewModelScope.launch {
            try {
                // The peer must hold our E2E key already (so they can VERIFY this signed ZBOOT); if not,
                // there's nothing to authenticate against yet — bail and let the KEX round-trip finish.
                val ourPriv = zchatPreferences.getE2EPrivateKey(peerAddress) ?: return@launch
                if (zchatPreferences.getE2EPeerPublicKey(peerAddress) == null) return@launch
                val (ourNostrPub, ourRelay) = getOurNostrPubkey() ?: return@launch
                // Idempotency / ping-pong guard: already delivered THIS identity → nothing to do.
                if (zchatPreferences.getSentNostrBootPubkey(peerAddress) == ourNostrPub) return@launch
                val ourAddress = _currentUserAddress.value ?: return@launch
                val (convId, _) = convIdMutex.withLock { zchatPreferences.getOrCreateConversationId(peerAddress) }

                val signedData = co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.signedDataFor(convId, ourNostrPub, ourRelay)
                val sig = E2EEncryption.sign(ourPriv, signedData)
                val bootMemo = co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage(convId, ourNostrPub, ourRelay, sig).serialize()

                // Mark BEFORE the async send resolves so a rapid double-trigger can't double-charge;
                // a failed send clears it so a genuine failure re-arms the next trigger.
                zchatPreferences.setSentNostrBootPubkey(peerAddress, ourNostrPub)
                if (sendHandshakeMemoWithRetry(peerAddress, ourAddress, bootMemo)) {
                    Log.d("ZCHAT_NOSTR", "Sent ZBOOT (NOSTR identity) to ${peerAddress.take(16)}… on $ourRelay")
                } else {
                    zchatPreferences.setSentNostrBootPubkey(peerAddress, null) // re-arm on failure
                    Log.w("ZCHAT_NOSTR", "ZBOOT (NOSTR identity) send failed after retries for ${peerAddress.take(16)}…")
                }
            } catch (e: Exception) {
                zchatPreferences.setSentNostrBootPubkey(peerAddress, null) // re-arm on failure
                Log.w("ZCHAT_NOSTR", "ZBOOT (NOSTR identity) send failed: ${e.message}")
            } finally {
                nostrBootInFlight.remove(peerAddress)
            }
        }
    }

    /**
     * Submit a handshake memo (KEX / KEXACK / ZBOOT), retrying ONLY on the TRANSIENT
     * "funds still confirming" insufficient-funds error. The single-note rule (one shielded spend per
     * block) makes back-to-back sends — and crucially a send right after the PEER's inbound message
     * momentarily locks our note — fail with transient insufficient funds even when our confirmed
     * balance is ample. Left un-retried, that silently drops a KEXACK/ZBOOT and STALLS the whole
     * handshake (seen live: Honor's KEXACK failed the instant Seeker's KEX landed and locked the note,
     * nothing retried → Seeker never got Honor's E2E key → tunnel stuck). We wait for the next block
     * (the locking tx confirms / our change matures) and retry, up to [MAX_HANDSHAKE_RETRIES]. A GENUINE
     * shortfall ("add ZEC", no "confirm on-chain" marker) is NOT retried — waiting can't fix it.
     * @return true if the memo was submitted, false if it ultimately failed.
     */
    private suspend fun sendHandshakeMemoWithRetry(destination: String, sender: String, memo: String): Boolean {
        var attempt = 0
        while (true) {
            try {
                createChunkedMessageProposal(
                    destinationAddress = destination,
                    senderAddress = sender,
                    message = memo,
                    isFirstMessage = false,
                    amountPerOutput = Zatoshi(1000L),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true,
                )
                return true
            } catch (e: Exception) {
                if (isTransientInsufficientFunds(e) && attempt < MAX_HANDSHAKE_RETRIES) {
                    attempt++
                    Log.w("KEX", "Handshake memo hit transient insufficient funds (note locked by a just-landed tx) — waiting for next block, retry $attempt/$MAX_HANDSHAKE_RETRIES")
                    if (!waitForNextBlock()) {
                        Log.e("KEX", "Gave up waiting for a new block — handshake memo send failed")
                        return false
                    }
                } else {
                    Log.e("KEX", "Handshake memo send failed permanently", e)
                    return false
                }
            }
        }
    }

    /** True only for the TRANSIENT "funds still confirming" insufficient-funds (mapped to the
     *  "…confirm on-chain…" message by [CreateChunkedMessageProposalUseCase]) — resolves on the next
     *  block. A genuine shortfall maps to a different message and must NOT be retried. */
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

    /** Suspend until the network block height advances (the note-locking tx confirms / our change
     *  matures), or [timeoutMs] elapses. Returns true if a new block landed. */
    private suspend fun waitForNextBlock(timeoutMs: Long = HANDSHAKE_BLOCK_WAIT_TIMEOUT_MS): Boolean {
        return try {
            val synchronizer = synchronizerProvider.getSynchronizer()
            val startHeight = synchronizer.networkHeight.value?.value
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                synchronizer.networkHeight.first { h ->
                    h != null && (startHeight == null || h.value > startHeight)
                }
            } != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Dispatch through [routeOutgoing] when the conversation isn't VAULT. Returns true
     * if the message was handled (i.e. the caller should NOT continue with the existing
     * shielded path).
     *
     * Strategy:
     *   - VAULT  -> false, fall through to shielded.
     *   - OPEN   -> if peer NOSTR pubkey known, publish NIP-17 + insert "sent" bubble; else
     *               fall through to shielded as a safety net.
     *   - TUNNEL -> if both sides have boot-exchanged, publish NIP-17; if not, emit a ZBOOT
     *               via the shielded path AND queue the message until the peer ZBOOT lands.
     */
    // Reply linkage that survives E2E: the quoted txid is embedded INSIDE the message body (before
    // ratchet encryption) using a control-char sentinel that can never occur in user text or a ZMSG
    // envelope, so it rides the v4 envelope unchanged (convId preserved → ratchet still resolves on
    // receive). Format: <U+0001>RPL:<quoted_txid><U+0001><original message>. A naive switch to the v3 RPL
    // envelope would drop the convId and desync the ratchet, so the ref must live in the body.
    private val replyRefSentinel = '\u0001'
    private val replyRefPrefix = "${replyRefSentinel}RPL:"

    // 2nd separator between the quoted id and the quoted-message PREVIEW text in the marker:
    // <U+0001>RPL:<id><U+001F><previewEscaped><U+0001><body>. U+001F can never occur in a
    // ChatMessage.id ([A-Za-z0-9_-]) and is escaped out of the preview, so it's an unambiguous
    // delimiter. Carrying the preview lets a NOSTR receiver (whose local ids differ) render the quote.
    private val replyPreviewSep = '\u001f'

    private fun escapeReplyPreview(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\u0001", "\\a")
            .replace("\u001f", "\\b")
            .replace('\n', ' ')
            .replace('\r', ' ')

    private fun unescapeReplyPreview(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> out.append('\\')
                    'a' -> out.append('\u0001')
                    'b' -> out.append('\u001f')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Wrap a reply body with the quoted-id marker AND a short preview of the quoted message. */
    private fun tagReply(replyToId: String, body: String, preview: String): String {
        val previewSeg = if (preview.isNotEmpty()) "$replyPreviewSep${escapeReplyPreview(preview)}" else ""
        return "$replyRefPrefix$replyToId$previewSeg$replyRefSentinel$body"
    }

    /**
     * Strip an embedded reply-ref. Returns (quotedId?, quotedPreview?, cleanBody). Backward compatible
     * with the legacy id-only marker (<U+0001>RPL:<id><U+0001><body>) — preview is then null.
     */
    private fun untagReply(content: String): Triple<String?, String?, String> {
        if (!content.startsWith(replyRefPrefix)) return Triple(null, null, content)
        val end = content.indexOf(replyRefSentinel, replyRefPrefix.length)
        if (end == -1) return Triple(null, null, content)
        val ref = content.substring(replyRefPrefix.length, end)
        val sepIdx = ref.indexOf(replyPreviewSep)
        val txid = if (sepIdx == -1) ref else ref.substring(0, sepIdx)
        val preview = if (sepIdx == -1) null else unescapeReplyPreview(ref.substring(sepIdx + 1))
        // A real quoted id is a ChatMessage.id: an on-chain txid (hex), or a local id like
        // "nostr-<hex>" / "pending_<n>" / "nostr-out-<n>" — i.e. always [A-Za-z0-9_-]. If the
        // extracted token contains anything else (e.g. pasted binary that happens to begin with a
        // SOH control char), this wasn't our marker — return the content unchanged rather than
        // silently consuming its head. (Must NOT hex-only-validate: that would reject the very
        // common "nostr-…"/"pending_…" reply targets and leak the raw marker as text.)
        if (txid.isEmpty() || !txid.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return Triple(null, null, content)
        }
        return Triple(txid, preview, content.substring(end + 1))
    }

    /**
     * TOFU / MITM gate shared by every user-initiated send entrypoint. If the peer's identity key
     * changed since we last trusted it, surfaces a blocking error and returns true so the caller can
     * abort BEFORE encrypting/transmitting to a possibly attacker-substituted key. The user clears
     * the flag by acknowledging the "Security key changed" banner (dismissE2EKeyChanged). This must
     * run before transport routing so TUNNEL/OPEN (NOSTR) sends are gated too, not just VAULT.
     */
    private fun blockedByKeyChange(peerAddress: String): Boolean {
        if (zchatPreferences.isE2EKeyChanged(peerAddress)) {
            _sendMessageState.value = SendMessageState.Error(
                "This contact's security key changed. Verify it with them, then tap OK on the warning banner to continue.",
            )
            return true
        }
        return false
    }

    /**
     * MONEY-SAFETY guard for on-chain-only advanced features. Time-locked / block-locked / payment-locked
     * / conditional messages (and their unlocks) are on-chain primitives — they cannot be enforced over a
     * NOSTR relay (no block height, no payment escrow). In TUNNEL/OPEN conversations they MUST NOT silently
     * spend ZEC: only the one-time key exchange may go on-chain. If the conversation isn't VAULT, surface a
     * clear, non-charging notice and return true so the caller aborts BEFORE createChunkedMessageProposal.
     * Audit: these 6 entrypoints called createChunkedMessageProposal with no mode check (ZEC drain).
     */
    private fun blockOnChainFeatureIfNotVault(peerAddress: String, featureLabel: String): Boolean {
        val mode = zchatPreferences.getConversationMode(peerAddress)
        if (mode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) return false
        _sendMessageState.value = SendMessageState.Error(
            "$featureLabel works only in Vault (on-chain) chats. Your ${mode.name.lowercase()} conversation " +
                "stays free — nothing was sent or charged.",
        )
        Log.w("ZCHAT_MONEY", "Blocked on-chain feature '$featureLabel' in $mode mode for ${peerAddress.redactAddress()} (no charge)")
        return true
    }

    private fun handleNostrRouteIfApplicable(peerAddress: String, message: String): Boolean {
        val mode = zchatPreferences.getConversationMode(peerAddress)
        if (mode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) return false

        val peerPub = zchatPreferences.getPeerNostrPubkey(peerAddress)
        val peerRelay = zchatPreferences.getPeerNostrRelay(peerAddress)

        // NOSTR transport requires the foreground service to have started the inbox
        // (publisher hook registered). If the service hasn't booted yet, fall back to
        // shielded so the message isn't silently dropped.
        if (!co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()) {
            Log.w("ZCHAT_NOSTR", "outbound not ready; falling back to shielded")
            return false
        }

        when (mode) {
            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN -> {
                if (peerPub == null) {
                    Log.w("ZCHAT_NOSTR", "OPEN mode without peer pubkey — falling back to shielded")
                    return false
                }
                publishNostrAndRenderLocal(peerAddress, peerPub, message)
                return true
            }
            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL -> {
                if (peerPub != null) {
                    publishNostrAndRenderLocal(peerAddress, peerPub, message)
                    return true
                }
                // The ZBOOT handshake itself is the ONE on-chain memo TUNNEL is allowed to send
                // (it's how the peer learns our NOSTR identity). Let it fall through to the shielded
                // path. A user content payload must NOT — see below.
                if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(message)) {
                    return false
                }
                // Bootstrap not complete. A TUNNEL content message must NEVER be charged on-chain:
                // (a) kick the one-time ZBOOT handshake (on-chain) so the peer learns our NOSTR key,
                // (b) queue the payload, (c) render a "waiting for secure connection" pending bubble
                // (NOT an on-chain pending — no tx, no fee, no block-time timer), and (d) report
                // HANDLED (true) so sendMessage/sendReply stop before the shielded on-chain send.
                // flushTunnelPendingPayloads() publishes the queue over NOSTR once peerPub arrives.
                ensureNostrBootstrapSent(peerAddress)
                tunnelPendingPayloads.getOrPut(peerAddress) { mutableListOf() }.add(message)
                renderTunnelWaitingBubble(peerAddress, message)
                Log.d("ZCHAT_NOSTR", "TUNNEL pre-bootstrap — queued payload (no on-chain charge), awaiting peer NOSTR key")
                return true
            }
            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT -> return false
        }
    }

    private val tunnelPendingPayloads: MutableMap<String, MutableList<String>> = mutableMapOf()

    // MONEY-SAFETY: peer address -> peer pubkey we have ALREADY paid an on-chain KEXACK for. A KEXACK
    // costs ~1000 zatoshi, and the inbound handler used to fire one on EVERY received KEX — so a peer
    // re-sending KEX (or NOSTR re-delivering it) drained the wallet 1000 zatoshi per duplicate. We now
    // record success here and skip re-acking the same key. A FAILED KEXACK is NOT recorded (so it still
    // retries), and a genuinely changed peer key clears the entry (so the new key is re-acked once).
    private val kexAckedKeys: MutableMap<String, String> = mutableMapOf()

    // CONCURRENCY: the inbound handlers (handleKEXMessage, routeIncomingBoot) fire once PER received
    // memo, and a single KEX/ZBOOT often arrives as several memos or is re-scanned across sync passes →
    // multiple coroutines launch at once. Without an atomic in-flight guard they ALL pass the cheap
    // pref/idempotency check before any of them sets it, then submit DUPLICATE on-chain sends that
    // contend for the one spendable note → every duplicate fails with transient insufficient funds (the
    // failure STORM seen on-device: 4× concurrent KEXACK/ZBOOT sends). These sets make "claim + send"
    // atomic per peer: add() returns false if a send is already in flight, so duplicates bail instantly.
    private val kexAckInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val nostrBootInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Render a queued TUNNEL payload as a local "waiting for secure connection" bubble. This is a
     * NOSTR-pending state, NOT an on-chain pending: there is no tx, no fee, and no block-time/75s
     * timer. The bubble's id is the queued payload's identity so [flushTunnelPendingPayloads] can
     * drop it (the real "nostr-out-…" bubble from [publishNostrAndRenderLocal] replaces it) once the
     * peer's NOSTR key arrives. Mirrors [publishNostrAndRenderLocal]'s reply-ref / ZFILE handling so
     * the sender sees clean text / a file card, not the raw U+0001 marker or "ZFILE|…" memo.
     */
    private fun renderTunnelWaitingBubble(peerAddress: String, plaintext: String) {
        val now = Instant.now()
        val localId = "tunnel-wait-${System.nanoTime()}"
        val (localReplyTo, localReplyPreview, strippedPlaintext) = untagReply(plaintext)
        val localFile = if (co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(strippedPlaintext)) {
            co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(strippedPlaintext)
        } else {
            null
        }
        val localMsg = if (localFile != null) {
            ChatMessage(
                id = localId,
                txId = null,
                text = "📎 ${localFile.displayText}",
                timestamp = now,
                isOutgoing = true,
                peerAddress = peerAddress,
                isPending = true,
                status = MessageStatus.SENDING,
                replyToId = localReplyTo,
                replyToPreview = localReplyPreview,
                fileHash = localFile.hash,
                fileZfileContent = strippedPlaintext,
                fileBlurhash = localFile.blurhash.takeIf { it.isNotEmpty() },
                fileType = localFile.type,
                fileViewOnce = localFile.viewOnce,
                fileViewed = localFile.viewOnce && zchatPreferences.isFileViewed(localFile.hash),
            )
        } else {
            ChatMessage(
                id = localId,
                txId = null,
                text = strippedPlaintext,
                timestamp = now,
                isOutgoing = true,
                peerAddress = peerAddress,
                isPending = true,
                status = MessageStatus.SENDING,
                replyToId = localReplyTo,
                replyToPreview = localReplyPreview,
            )
        }
        pendingMessages.update { it + localMsg }
    }

    /**
     * Flush any TUNNEL payloads queued while the peer's NOSTR identity was still unknown. Called the
     * moment we learn [peerPubHex] (from an inbound ZBOOT in [routeIncomingBoot], or piggybacked on a
     * KEX/KEXACK in [applyKEXNostr]). Each queued payload is published over NOSTR + rendered as its
     * own local bubble via [publishNostrAndRenderLocal]; the optimistic "tunnel-wait-…" placeholders
     * are dropped so they don't duplicate. Without this, [tunnelPendingPayloads] was write-only and
     * every pre-bootstrap TUNNEL message was lost.
     */
    private fun flushTunnelPendingPayloads(peerAddress: String, peerPubHex: String) {
        val queued = tunnelPendingPayloads.remove(peerAddress) ?: return
        if (queued.isEmpty()) return
        // Drop the "waiting for secure connection" placeholders; publishNostrAndRenderLocal inserts
        // the real "nostr-out-…" bubbles in their place.
        pendingMessages.update { list -> list.filterNot { it.id.startsWith("tunnel-wait-") && it.peerAddress == peerAddress } }
        queued.forEach { payload -> publishNostrAndRenderLocal(peerAddress, peerPubHex, payload) }
        Log.d("ZCHAT_NOSTR", "Flushed ${queued.size} queued TUNNEL payload(s) over NOSTR for ${peerAddress.take(16)}…")
    }

    private fun publishNostrAndRenderLocal(peerAddress: String, peerPubHex: String, plaintext: String) {
        val now = Instant.now()
        val localId = "nostr-out-${System.nanoTime()}"
        // The reply-ref rides the wire (plaintext, published below) but must be stripped from the
        // sender's OWN local bubble: otherwise the sender sees the raw U+0001 marker (and, for a
        // reply-to-file, the "ZFILE|…" body never parses into a file card). replyToId threads it.
        val (localReplyTo, localReplyPreview, strippedPlaintext) = untagReply(plaintext)
        // Parse a ZFILE ref into the sender's OWN file bubble — mirrors the inbound NOSTR parse so
        // the sender sees a file card, not the raw "ZFILE|hash|…|base64key" memo (which would leak
        // the wrapped key into the visible bubble text and never render the image).
        val localFile = if (co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(strippedPlaintext)) {
            co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(strippedPlaintext)
        } else {
            null
        }
        val localMsg = if (localFile != null) {
            ChatMessage(
                id = localId,
                txId = null,
                text = "📎 ${localFile.displayText}",
                timestamp = now,
                isOutgoing = true,
                peerAddress = peerAddress,
                isPending = true,
                status = MessageStatus.SENDING,
                replyToId = localReplyTo,
                replyToPreview = localReplyPreview,
                fileHash = localFile.hash,
                fileZfileContent = strippedPlaintext,
                fileBlurhash = localFile.blurhash.takeIf { it.isNotEmpty() },
                fileType = localFile.type,
                fileViewOnce = localFile.viewOnce,
                fileViewed = localFile.viewOnce && zchatPreferences.isFileViewed(localFile.hash),
            )
        } else {
            ChatMessage(
                id = localId,
                txId = null,
                text = strippedPlaintext,
                timestamp = now,
                isOutgoing = true,
                peerAddress = peerAddress,
                isPending = true,
                status = MessageStatus.SENDING,
                replyToId = localReplyTo,
                replyToPreview = localReplyPreview,
            )
        }
        pendingMessages.update { it + localMsg }
        // Persist the outbound NOSTR row so it survives process death / ChatViewModel recreation.
        // Unlike on-chain sends, a NOSTR message has no ledger entry to rebuild from — without this
        // it lives only in the in-memory pendingMessages and vanishes on reload. txId stays null, so
        // the convertToConversations dedup never matches it against a confirmed tx (i.e. never removes it).
        zchatPreferences.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = localId,
                text = localMsg.text,
                timestampMillis = now.toEpochMilli(),
                peerAddress = peerAddress,
                isOutgoing = true,
                isPending = true,
                status = MessageStatus.SENDING.name,
                replyToId = localReplyTo,
                replyToPreview = localReplyPreview,
                fileZfileContent = localMsg.fileZfileContent
            )
        )
        viewModelScope.launch {
            val result = runCatching {
                co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(plaintext, peerPubHex)
            }.getOrNull()
            val acks = result?.acks ?: 0
            val finalStatus = if (acks > 0) MessageStatus.SENT else MessageStatus.FAILED
            // #202: re-key our optimistic bubble from the throwaway "nostr-out-…" id to the STABLE
            // shared rumor id ("nmsg-<rumorId>") that the RECIPIENT also assigns to this message — so a
            // reaction/reply (which targets the message's id) correlates across both devices. The user
            // can only react AFTER the send resolves, so the id is settled by then. Drop the old persisted
            // row and re-persist under the new id to keep storage consistent.
            val finalId = result?.messageId?.takeIf { it.isNotEmpty() }?.let { "nmsg-$it" } ?: localId
            pendingMessages.update { list ->
                list.map { m ->
                    if (m.id == localId) {
                        m.copy(id = finalId, isPending = false, status = finalStatus)
                    } else m
                }
            }
            // Update the persisted copy to its resolved state (under finalId) so a restart shows the
            // right status (SENT/FAILED) AND the same correlatable id instead of a perpetual "SENDING".
            if (finalId != localId) zchatPreferences.removePendingMessage(localId)
            zchatPreferences.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = finalId,
                    text = localMsg.text,
                    timestampMillis = now.toEpochMilli(),
                    peerAddress = peerAddress,
                    isOutgoing = true,
                    isPending = false,
                    status = finalStatus.name,
                    replyToId = localReplyTo,
                    replyToPreview = localReplyPreview,
                    fileZfileContent = localMsg.fileZfileContent
                )
            )
            Log.d("ZCHAT_NOSTR", "published to $acks relay(s) for ${peerAddress.take(16)}… (id=$finalId)")
        }
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
                // Re-derive the file/voice bubble fields from the persisted raw "ZFILE|…" memo, mirroring
                // the live NOSTR send/receive parse so a restored voice/image row renders as a media card
                // (not the raw memo) and still downloads via fileHash.
                val restoredFile = data.fileZfileContent
                    ?.takeIf { co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(it) }
                    ?.let { co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(it) }
                val restoredStatus = data.status
                    ?.let { runCatching { MessageStatus.valueOf(it) }.getOrNull() }
                    ?: MessageStatus.SENT
                // Reconcile ORPHANED in-flight sends. A message persisted as still-SENDING was being
                // driven by the in-memory messageQueue + a viewModelScope retry coroutine, NEITHER of
                // which survives process death or ViewModel clear (the prior scope is cancelled). On a
                // fresh ChatViewModel that send will never resume, leaving a perpetual "Sending after
                // confirmation" bubble that nothing completes. Surface it as FAILED so the user gets the
                // existing tap-to-retry affordance. We deliberately do NOT auto-resend: doSendMessage
                // persists the pending row BEFORE broadcasting, so a silent resend could double-send a tx
                // that actually went out (the real one, if broadcast, reappears via sync as a SENT bubble).
                // An OUTGOING row persisted as still-pending is in-flight: the data class documents that
                // outbound rows flip to not-pending once they reach SENT/FAILED, and the VAULT queue path
                // persists with status=null ("legacy on-chain pending"). So isPending — NOT the (often
                // null→SENT) status — is the reliable orphan signal.
                val isOrphanedInFlight = data.isOutgoing && data.isPending
                val effectiveStatus = if (isOrphanedInFlight) MessageStatus.FAILED else restoredStatus
                val effectivePending = if (isOrphanedInFlight) false else data.isPending
                ChatMessage(
                    id = data.id,
                    txId = null,
                    text = data.text,
                    timestamp = java.time.Instant.ofEpochMilli(data.timestampMillis),
                    isOutgoing = data.isOutgoing,
                    peerAddress = data.peerAddress,
                    isPending = effectivePending,
                    status = effectiveStatus,
                    replyToId = data.replyToId,
                    replyToPreview = data.replyToPreview,
                    fileHash = restoredFile?.hash,
                    fileZfileContent = data.fileZfileContent,
                    fileBlurhash = restoredFile?.blurhash?.takeIf { it.isNotEmpty() },
                    fileType = restoredFile?.type,
                    fileViewOnce = restoredFile?.viewOnce ?: false,
                    fileViewed = restoredFile?.let { it.viewOnce && zchatPreferences.isFileViewed(it.hash) } ?: false,
                    // #210: re-apply persisted NOSTR reactions. Without this they live only in the
                    // in-memory StateFlow and are wiped here on every reload.
                    reactions = zchatPreferences.getNostrReactions(data.id).map {
                        co.electriccoin.zcash.ui.screen.chat.model.MessageReaction(
                            emoji = it.emoji,
                            senderAddress = it.senderAddress,
                            timestamp = java.time.Instant.ofEpochMilli(it.timestampMillis),
                        )
                    }
                )
            }
            pendingMessages.value = chatMessages
            Log.d("ZCHAT_PENDING", "Loaded ${chatMessages.size} pending messages from preferences")
        }
    }

    /** Load persisted call-log entries (incoming/outgoing/missed) and merge into the message list. */
    private fun loadCallLogsFromPrefs() {
        val saved = zchatPreferences.getCallLogMessages()
        if (saved.isEmpty()) return
        val msgs = saved.map { d ->
            ChatMessage(
                id = d.id,
                txId = null,
                text = "",
                timestamp = java.time.Instant.ofEpochMilli(d.timestampMillis),
                isOutgoing = d.isOutgoing,
                peerAddress = d.peerAddress,
                isPending = false,
                status = MessageStatus.SENT,
                callLog = co.electriccoin.zcash.ui.screen.chat.model.CallLogInfo(
                    // Guard against a malformed/legacy persisted type — valueOf would throw and crash the
                    // whole call-log/message mapping. Fall back to MISSED rather than crash.
                    type = runCatching {
                        co.electriccoin.zcash.ui.screen.chat.model.CallLogType.valueOf(d.type)
                    }.getOrDefault(co.electriccoin.zcash.ui.screen.chat.model.CallLogType.MISSED),
                    isVideo = d.isVideo,
                    durationSec = d.durationSec,
                ),
            )
        }
        pendingMessages.update { current ->
            val have = current.mapTo(HashSet()) { it.id }
            current + msgs.filter { it.id !in have }
        }
        Log.d("ZCHAT_FLOW", "Loaded ${msgs.size} call-log entries from preferences")
    }

    /** Live channel for local, non-transmitted messages (call logs) injected by the service. */
    private fun observeLocalMessages() {
        viewModelScope.launch {
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.localMessages.collect { msg ->
                pendingMessages.update { current ->
                    if (current.any { it.id == msg.id }) current else current + msg
                }
            }
        }
    }

    /**
     * Check if this would be the first message to a peer using v4 conversation IDs.
     * Returns true if we haven't established a conversation ID with this peer yet.
     */
    private fun isFirstMessageTo(peerAddress: String): Boolean {
        return zchatPreferences.getConversationId(peerAddress) == null
    }

    /**
     * Returns true if [resolvedPeerAddress] is a real Zcash address (unified u1... or sapling
     * zs...), i.e. routing successfully resolved the message to a recognizable peer.
     *
     * When this is true the "Unknown sender" banner must be suppressed even if the protocol parser
     * flagged a reason in isolation (HASH_NOT_IN_CACHE on cold start, VERSION_MISMATCH on a legacy
     * prefix), because routing mapped it to an established peer via the authenticated convId/contact
     * mapping. When false (routing fell back to a hash, raw convId, or "unknown"), the banner stays.
     *
     * This mirrors the address-validity heuristic the chat view uses to gate the banner.
     */
    private fun isResolvedToKnownPeer(resolvedPeerAddress: String): Boolean {
        return (resolvedPeerAddress.startsWith("u1") && resolvedPeerAddress.length > 100) ||
            (resolvedPeerAddress.startsWith("zs") && resolvedPeerAddress.length > 70)
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

                // #205 — register every representation of OUR OWN address so self-identification is
                // drift-tolerant. The canonical diversifier-0 UA is what we KEX-sign and display on the
                // receive screen, but the account can also surface a different diversified unified
                // address; record both so a self-check (self-message filter, am-I-kicked) by hash
                // recognises us regardless of which representation an inbound message carries.
                zchatPreferences.registerSelfAddress(userAddress)
                runCatching {
                    getSelectedWalletAccount().unified.address.address
                }.getOrNull()?.let { zchatPreferences.registerSelfAddress(it) }

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
                    val txList = transactions
                    val receiveCount = txList.count { it is co.electriccoin.zcash.ui.common.repository.ReceiveTransaction }
                    val sendCount = txList.count { it is co.electriccoin.zcash.ui.common.repository.SendTransaction }
                    Log.d("ZCHAT_FLOW", "=== Transactions flow emitted: total=${txList.size} (rx=$receiveCount tx=$sendCount) pending=${pending.size} hidden=${hiddenMsgIds.size} ===")
                    // Run the expensive conversion (CPU-heavy sort/loop + synchronous SharedPreferences
                    // commits in the address/convId caches) OFF the main thread to avoid the StrictMode
                    // disk-write-on-main violation and UI jank during sync.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        convertToConversations(txList, userAddress, hiddenMsgIds, pending)
                    }
                }

                // Flow 2: Sync status (cheap, changes every second)
                val syncStatusFlow = combine(
                    _lastSyncTime,
                    _isRefreshing,
                    _secondsUntilNextSync,
                    _blockHeight,
                    _zecPriceUsd
                ) { lastSync, isRefreshing, secondsUntilNext, blockHeight, zecPrice ->
                    SyncStatus(lastSync, isRefreshing, secondsUntilNext, blockHeight, zecPrice)
                }

                val combinedSyncFlow = combine(syncStatusFlow, _walletSyncStatus) { sync, walletSync ->
                    sync to walletSync
                }

                // Combine conversations (cached) with sync metadata (fast-changing) and read markers.
                combine(
                    conversationsFlow,
                    accountDataSource.selectedAccount,
                    combinedSyncFlow,
                    readMarkers
                ) { conversations, walletAccount, syncPair, readMarkerMap ->
                    val (syncStatus, walletSyncStatus) = syncPair

                    // Captured ONCE per emission so the unread predicate is consistent across all
                    // conversations and doesn't shift mid-pass.
                    val nowMs = System.currentTimeMillis()

                    // Add peer statuses, drafts, and E2E status to conversations
                    val drafts = zchatPreferences.getAllDrafts()
                    val conversationsWithStatus = conversations.map { conversation ->
                        val peerStatus = peerStatuses.value[conversation.peerAddress]
                        val draft = drafts[conversation.peerAddress]
                        val e2eEnabled = zchatPreferences.isE2EEnabled(conversation.peerAddress)
                        val e2eKeyExchangeComplete = zchatPreferences.isE2EKeyExchangeComplete(conversation.peerAddress)
                        // Unread = PAST incoming chat messages newer than this conversation's last-read
                        // marker. Bounding to <= now (not just > lastRead) keeps a future-dated message
                        // (peer clock skew / not-yet-"arrived") from sticking the badge above the read
                        // marker forever — it only counts once the wall clock actually reaches it.
                        // Call-log and AI rows are local-only system entries, not unread mail.
                        val lastRead = readMarkerMap[conversation.peerAddress] ?: 0L
                        val unreadCount = conversation.messages.count {
                            !it.isOutgoing && !it.isCallLog && !it.isAiMessage &&
                                it.timestamp.toEpochMilli() in (lastRead + 1)..nowMs
                        }
                        conversation.copy(
                            peerStatus = peerStatus,
                            draft = draft,
                            e2eEnabled = e2eEnabled,
                            e2eKeyExchangeComplete = e2eKeyExchangeComplete,
                            isMuted = zchatPreferences.isConversationMuted(conversation.peerAddress),
                            unreadCount = unreadCount,
                            // Calls route over the peer's NOSTR identity (free relay), not the message
                            // transport — so they're placeable whenever we hold the peer's NOSTR pubkey,
                            // even in a VAULT chat. Mirrors startCall's own peerPub != null gate.
                            hasNostrCallChannel = zchatPreferences.getPeerNostrPubkey(conversation.peerAddress) != null,
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected when setNickname() / contact rename triggers a reload —
                // loadConversations() cancels the prior job, the resulting
                // CancellationException is not a user-facing error. Rethrow so
                // structured concurrency stays intact, but DO NOT flip state to Error
                // (that turns the whole chat list into "StandaloneCoroutine was cancelled").
                throw e
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

        // Reactions collected during the loop, keyed by the TARGET message's txid. Attached in a
        // post-pass after every message row is built, so the target exists no matter whether the
        // reaction tx was processed before or after the message it points at. Without this pass
        // incoming reactions were parsed and then dropped — they never reached the UI.
        val reactionsByTarget =
            mutableMapOf<String, MutableList<co.electriccoin.zcash.ui.screen.chat.model.MessageReaction>>()

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

            // Reactions are metadata, not chat rows — but instead of dropping them, collect them
            // against their target message's txid so the post-pass below can attach them. Read
            // receipts remain pure metadata and are dropped.
            if (ZMSGProtocol.isReaction(memoText)) {
                diagSkipReaction++
                val parsedReaction = ZMSGProtocol.parseReaction(memoText, addressCache)
                if (parsedReaction != null) {
                    reactionsByTarget.getOrPut(parsedReaction.targetTxId) { mutableListOf() }.add(
                        co.electriccoin.zcash.ui.screen.chat.model.MessageReaction(
                            emoji = parsedReaction.emoji,
                            senderAddress = parsedReaction.senderAddress,
                            timestamp = tx.timestamp ?: Instant.now(),
                        ),
                    )
                } else {
                    Log.d("ZCHAT_FLOW", "SKIP unparseable reaction: $messageId")
                }
                continue
            }
            if (ZMSGProtocol.isReadReceipt(memoText)) {
                diagSkipReaction++
                Log.d("ZCHAT_FLOW", "SKIP receipt: $messageId")
                continue
            }

            // Handle KEX (Key Exchange) messages - don't appear in chat
            // Also handle legacy E2E_INIT format for backward compatibility
            if (ZMSGProtocol.isKEXMessage(memoText) || ZMSGProtocol.isKEXAckMessage(memoText) ||
                memoText.contains("E2E_INIT:")) {
                val txIdStr = tx.id.txIdString()
                if (tx is co.electriccoin.zcash.ui.common.repository.ReceiveTransaction) {
                    handleKEXMessage(memoText, userAddress, txIdStr)
                } else {
                    // Outgoing KEX/KEXACK: capture our sent txid for root derivation.
                    // The KEX initiator's outgoing KEX txid = the conversation's kexTxId.
                    // The KEX responder's outgoing KEXACK txid = the conversation's kexAckTxId.
                    val convId = ZMSGProtocol.parseKEXMessage(memoText)?.first
                        ?: ZMSGProtocol.parseKEXAckMessage(memoText)?.first
                    val peer = convId?.let { zchatPreferences.getPeerByConversationId(it) }
                    if (peer != null) {
                        if (ZMSGProtocol.isKEXMessage(memoText)) {
                            zchatPreferences.setE2EKexTxId(peer, txIdStr)
                        } else if (ZMSGProtocol.isKEXAckMessage(memoText)) {
                            zchatPreferences.setE2EKexAckTxId(peer, txIdStr)
                        }
                    }
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
            var outgoingFileHash: String? = null
            var incomingReplyToId: String? = null
            var incomingReplyPreview: String? = null
            var incomingFileHash: String? = null
            var capturedZfileContent: String? = null
            var capturedBlurhash: String? = null
            var capturedFileType: co.electriccoin.zcash.ui.screen.chat.model.ZFILEType? = null
            var capturedViewOnce = false

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
                val decryptedOutgoing = tryDecryptMessage(rawMessage, peerAddress, extractedConvId)
                // Strip our OWN embedded reply-ref (U+0001 RPL:<txid>[U+001F preview] U+0001) so the
                // confirmed outgoing bubble shows clean text and keeps threading after the pending row
                // is gone. The embedded preview is a fallback when the quoted id can't be resolved locally.
                val (outgoingReplyTo, outgoingReplyPreview, decryptedContent) = untagReply(decryptedOutgoing)
                if (outgoingReplyTo != null) incomingReplyToId = outgoingReplyTo
                if (outgoingReplyPreview != null) incomingReplyPreview = outgoingReplyPreview

                // ZFILE detection: if content is a file message, show metadata instead of raw ZFILE| string
                displayMessage = if (co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(decryptedContent)) {
                    val fileMsg = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(decryptedContent)
                    if (fileMsg != null) {
                        outgoingFileHash = fileMsg.hash
                        capturedZfileContent = decryptedContent
                        capturedBlurhash = fileMsg.blurhash.takeIf { it.isNotEmpty() }
                        capturedFileType = fileMsg.type
                        capturedViewOnce = fileMsg.viewOnce
                        "\uD83D\uDCCE ${fileMsg.displayText}"
                    } else {
                        decryptedContent
                    }
                } else if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(decryptedContent)) {
                    // Our own outbound NOSTR handshake \u2014 show a note, not the raw "ZBOOT|v1|\u2026" memo.
                    "\uD83D\uDD10 Secure connection request sent"
                } else {
                    decryptedContent
                }
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
                            // convID is a RANDOM 8-char id (~41 bits), so two peers can collide on
                            // one id. Trust the convID→peer mapping ONLY when the message's sender
                            // hash actually matches that peer; otherwise this is a collision and
                            // returning peerFromConvId would misroute the message into the wrong
                            // thread AND poison the hash cache. On mismatch, fall through to TIER2.
                            val hashMatches = senderHash == null ||
                                ZMSGProtocol.generateAddressHash(peerFromConvId) == senderHash ||
                                ZMSGProtocol.generateLegacyAddressHash(peerFromConvId) == senderHash
                            if (hashMatches) {
                                Log.d("ZCHAT_V4", "TIER1: Matched via convID -> ${peerFromConvId.redactAddress()}")
                                // Always cache sender hash → resolved peer for future lookups.
                                // Use validated caching since convID is a high-confidence source.
                                if (senderHash != null) {
                                    addressCache.cacheAddressValidated(senderHash, peerFromConvId)
                                    addressCache.addConversationPartner(peerFromConvId)
                                }
                                return@run peerFromConvId
                            }
                            Log.w("ZCHAT_V4", "TIER1 COLLISION: convID=${convId.redactConvId()} maps to ${peerFromConvId.redactAddress()} but senderHash=${senderHash.redactAddress()} mismatches; falling through to TIER2")
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
                        Log.w("ZCHAT_V4", "TIER3: Unknown sender, using hash as key: ${senderHash.redactAddress()}")
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

                // E2E decrypt + ZFILE detection for incoming messages
                val incomingConvId = parsed.conversationId
                val decryptedContent = tryDecryptMessage(parsed.message, resolvedPeerAddress, incomingConvId)
                // Strip the embedded reply-ref (U+0001 RPL:<txid> U+0001) the sender wrapped inside
                // the body so replies thread on the receiver. Fall back to the v3 RPL envelope txid
                // for legacy on-chain replies. Both feed ChatMessage.replyToId below.
                val (embeddedReplyTo, embeddedReplyPreview, incomingContent) = untagReply(decryptedContent)
                incomingReplyToId = embeddedReplyTo ?: parsed.replyToTxId
                if (embeddedReplyPreview != null) incomingReplyPreview = embeddedReplyPreview
                displayMessage = if (co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(incomingContent)) {
                    val fileMsg = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(incomingContent)
                    if (fileMsg != null) {
                        incomingFileHash = fileMsg.hash
                        capturedZfileContent = incomingContent
                        capturedBlurhash = fileMsg.blurhash.takeIf { it.isNotEmpty() }
                        capturedFileType = fileMsg.type
                        capturedViewOnce = fileMsg.viewOnce
                        "\uD83D\uDCCE ${fileMsg.displayText}"
                    } else {
                        incomingContent
                    }
                } else if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(incomingContent)) {
                    // Peer's NOSTR handshake (shielded ZBOOT): stash their pubkey + relay so NOSTR
                    // DMs and voice/video calls to them work. Only treat it as a handshake (and show
                    // the system note) if it actually PARSES \u2014 a plain message that merely starts with
                    // "ZBOOT|" must not be swallowed + replaced with a forged "Secure connection
                    // established" note. routeIncomingBoot still re-verifies the signature internally.
                    if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.parse(incomingContent) != null) {
                        // Only claim "established" if routeIncomingBoot actually verified + stored the
                        // peer's NOSTR pubkey. When it can't yet (we don't hold the sender's E2E key \u2014
                        // the on-chain KEX hasn't landed), the call channel does NOT exist; saying it
                        // does is the bug that made users tap a dead call button. Show a truthful
                        // "in-progress" note instead; the real established note prints once it verifies.
                        if (routeIncomingBoot(resolvedPeerAddress, incomingContent)) {
                            "\uD83D\uDD10 Secure connection established \u2014 voice/video calls enabled"
                        } else {
                            "\uD83D\uDD13 Connection request received \u2014 finishing secure setup (waiting for key exchange)\u2026"
                        }
                    } else {
                        incomingContent
                    }
                } else {
                    incomingContent
                }
                // Decide whether to surface the "Unknown sender" banner.
                //
                // parsed.reason reflects ONLY what the protocol parser could determine in isolation
                // (e.g. HASH_NOT_IN_CACHE after a cold restart, or VERSION_MISMATCH on a legacy
                // prefix). Routing above may have since resolved the message to a real, already-known
                // peer via the EXISTING authenticated conversation mapping (convId -> peer) or via a
                // direct/contact/hash match. In that case the sender IS recognized and the banner must
                // NOT show — clearing the reason here is what suppresses it (the view gates the banner
                // on whether peerAddress is a real Zcash address).
                //
                // SECURITY: we clear the reason ONLY when routing produced a real Zcash address
                // (u1.../zs...). If routing fell back to a hash, a raw convId, or "unknown" (i.e. the
                // convId did NOT map to an established peer, or there was no recognized ZCHAT prefix),
                // resolvedPeerAddress is NOT a real address, so we keep parsed.reason and the banner
                // still shows. A bare convId string in plaintext can never suppress the banner on its
                // own — it must resolve to an authenticated peer.
                unknownReason = if (isResolvedToKnownPeer(resolvedPeerAddress)) null else parsed.reason
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
                paymentRequest = paymentRequestInfo,
                fileHash = outgoingFileHash ?: incomingFileHash,
                fileZfileContent = capturedZfileContent,
                fileBlurhash = capturedBlurhash,
                fileType = capturedFileType,
                fileViewOnce = capturedViewOnce,
                fileViewed = capturedViewOnce && (
                    (outgoingFileHash ?: incomingFileHash)?.let { zchatPreferences.isFileViewed(it) } == true
                ),
                replyToId = incomingReplyToId,
                replyToPreview = incomingReplyToId?.let { rid ->
                    // Use displayText (not raw text) so a quoted time-locked / payment-locked message
                    // shows "🔒 Locked message" instead of leaking its plaintext into the reply preview.
                    messagesByPeer[peerAddress]?.firstOrNull { it.id == rid }?.displayText?.take(50)
                } ?: incomingReplyPreview,
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
            // A locally-deleted pending message (NOSTR in/out, local call-log) must not reappear.
            // On-chain deletes are filtered in the tx loop above via hiddenMsgIds; pending messages
            // never reach that loop, so without this check Delete was a silent no-op for anything
            // sent/received over NOSTR. Purge from the pending store too so the delete is permanent
            // instead of being re-filtered on every reload.
            if (pendingMsg.id in hiddenMsgIds) {
                pendingToRemove.add(pendingMsg.id)
                continue
            }
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

        // Attach collected reactions to their target rows (matched by txid). Done here, after both
        // the tx loop and pending merge, so the target is present regardless of arrival order.
        if (reactionsByTarget.isNotEmpty()) {
            for (msgs in messagesByPeer.values) {
                for (i in msgs.indices) {
                    // The sender reacts against the target's message id (ChatMessage.id), which for an
                    // on-chain message IS its txid string — the same value both peers derive from the
                    // transaction — so matching on id threads the reaction to the right row.
                    val collected = reactionsByTarget[msgs[i].id]
                    if (collected != null) {
                        msgs[i] = msgs[i].copy(reactions = msgs[i].reactions + collected)
                    }
                }
            }
        }

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

    /**
     * Mark a conversation read up to now. Persists the last-read marker and updates the reactive
     * [readMarkers] map so the conversation list re-emits and the unread badge clears immediately.
     * Called when the user opens the conversation. Cheap: no SDK refresh.
     */
    fun markConversationRead(peerAddress: String) {
        // Mark read up to max(now, latest-message-time). Using ONLY the latest message timestamp is
        // fragile: this runs from a Compose LaunchedEffect that may fire before the newest message
        // has propagated into _chatListState, so the snapshot max can lag the real latest message and
        // leave it perpetually unread. now() is immune to that (every incoming timestamp is clamped to
        // <= now at receive, so "read up to now" clears all currently-visible mail), and the
        // message-max term still covers any outgoing/future-dated row beyond now. setLastReadTimestamp
        // is monotonic, so a genuinely newer message that arrives later still re-badges.
        val latestMsgMillis = (_chatListState.value as? ChatListState.Success)
            ?.conversations
            ?.firstOrNull { it.peerAddress == peerAddress }
            ?.messages
            ?.maxOfOrNull { it.timestamp.toEpochMilli() }
            ?: 0L
        val readUpTo = maxOf(System.currentTimeMillis(), latestMsgMillis)
        zchatPreferences.setLastReadTimestamp(peerAddress, readUpTo)
        // Re-read the persisted (monotonic-clamped) value so the flow matches storage exactly.
        val stored = zchatPreferences.getLastReadTimestamp(peerAddress)
        readMarkers.update { it + (peerAddress to stored) }
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
        // The user has now verified this change out-of-band (e.g. the peer rotated their NOSTR key —
        // #178 Part B). Clear the stale stored peer NOSTR pubkey so the NEXT signed KEX carrying the
        // peer's new key is ACCEPTED by applyKEXNostr (which otherwise refuses to overwrite a still-
        // stored differing key). Acceptance stays gated on this explicit user verification — it does NOT
        // auto-accept key changes. Then re-KEX (NOSTR chats only) so both sides converge on the new keys.
        if (zchatPreferences.getConversationMode(peerAddress) !=
            co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
            zchatPreferences.setPeerNostrPubkey(peerAddress, null)
            _currentUserAddress.value?.let { ourAddress -> sendKEXMessage(peerAddress, ourAddress) }
        }
    }

    /**
     * #178 Part B — rotate our account-wide NOSTR identity to a fresh key. Bumps the derivation index,
     * then re-KEXes every NOSTR-mode peer so they learn our new pubkey (and accept it after verifying the
     * key-change banner on their side — the same anti-MITM gate, reused). OUTBOUND uses the new key
     * immediately (every derivation reads the index). The running INBOUND inbox now HOT-SWAPS to the
     * new key too (#188: requestInboxRotation → service re-derives + re-subscribes), so no app restart
     * is needed to finish activation. Re-KEX costs the usual on-chain key-exchange fee per peer. Returns
     * the new index. NOTE: the index is local state; a seed-only restore returns to index 0 and peers
     * will see a key change to re-verify (documented limitation, no value at risk — the on-chain wallet
     * recovers from seed as always).
     */
    fun rotateNostrIdentity(): Int {
        val next = zchatPreferences.getNostrRotationIndex() + 1
        zchatPreferences.setNostrRotationIndex(next)
        Log.w("ZCHAT_NOSTR", "NOSTR identity rotated to index $next — re-KEXing NOSTR peers")
        // #188: hot-swap the live inbound inbox to the new key (the service re-derives + re-subscribes)
        // so INBOUND delivery follows the rotation immediately — no app restart needed anymore.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.requestInboxRotation()
        viewModelScope.launch {
            val ourAddress = _currentUserAddress.value ?: return@launch
            val peers = zchatPreferences.getAllPeerToConvIdMappings().keys.filter {
                zchatPreferences.getConversationMode(it) !=
                    co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT
            }
            peers.forEach { peer ->
                zchatPreferences.setOwnBootSent(peer, false) // allow re-bootstrap with the rotated key
                sendKEXMessage(peer, ourAddress)             // carries our new NOSTR pubkey via piggyback
            }
            Log.d("ZCHAT_NOSTR", "Re-KEXed ${peers.size} NOSTR peer(s) after rotation to index $next")
        }
        return next
    }

    /**
     * True once the user has confirmed this peer's safety number out-of-band. Lets the UI
     * distinguish a "verified" conversation from one that is merely TOFU-encrypted. Cleared
     * automatically when the peer's key changes (see the KEX/boot key-change handlers).
     */
    fun isE2EVerified(peerAddress: String): Boolean =
        zchatPreferences.isE2EVerified(peerAddress)

    /** User confirmed the safety number matches out-of-band — mark the conversation verified. */
    fun markE2EVerified(peerAddress: String) {
        zchatPreferences.setE2EVerified(peerAddress, true)
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
        val ourPubB64 = zchatPreferences.getE2EOurPublicKey(peerAddress) ?: return null
        val peerPubB64 = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
        // Hash raw pubkey bytes (not Base64 encoding) so the safety number is
        // stable across encoding format changes. Sort so both parties compute the same.
        val ourBytes = java.util.Base64.getDecoder().decode(ourPubB64)
        val peerBytes = java.util.Base64.getDecoder().decode(peerPubB64)
        // Order by the raw BYTES (unsigned lexicographic), not the Base64 strings. Base64 ordering can
        // differ from byte ordering, and we hash the bytes — so sorting by the string while hashing the
        // bytes contradicts this function's own spec. This does NOT change MITM detection (both parties
        // already sort the same key pair by the same rule and therefore agree); it aligns the code with
        // its documented algorithm and with any byte-sorting cross-platform client. Rollout note: it
        // changes the DISPLAYED number, so a previously-verified pair spanning old/new app versions
        // should re-compare out of band.
        var cmp = 0
        for (i in 0 until minOf(ourBytes.size, peerBytes.size)) {
            cmp = (ourBytes[i].toInt() and 0xff) - (peerBytes[i].toInt() and 0xff)
            if (cmp != 0) break
        }
        if (cmp == 0) cmp = ourBytes.size - peerBytes.size
        val (first, second) = if (cmp <= 0) ourBytes to peerBytes else peerBytes to ourBytes
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(first + second)
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
        // Synchronized to prevent TOCTOU race: two coroutines hitting a cache miss
        // simultaneously could create duplicate processors with desynchronized counters.
        synchronized(messageProcessors) {
            messageProcessors[cacheKey]?.let { return it }

            val sharedKey = getE2ESharedKey(peerAddress) ?: return null
            if (!zchatPreferences.isE2EEnabled(peerAddress)) return null

            val ourPub = zchatPreferences.getE2EOurPublicKey(peerAddress) ?: return null
            val peerPub = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
            val isLower = ourPub < peerPub

            // Root derivation using stored KEX/KEXACK txids for per-conversation uniqueness.
            // Falls back to empty txids for conversations where KEX happened before txid
            // storage was implemented — shared secret uniqueness still prevents collision.
            val kexTxId = zchatPreferences.getE2EKexTxId(peerAddress)
                ?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val kexAckTxId = zchatPreferences.getE2EKexAckTxId(peerAddress)
                ?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            // Quantum Shield PSK: mix into root if active for this conversation
            val pskBase64 = zchatPreferences.getQuantumShieldPSK(peerAddress)
            val psk = pskBase64?.let { java.util.Base64.getDecoder().decode(it) }

            val rootKey = co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet.deriveRatchetRoot(
                ecdhSharedSecret = sharedKey,
                psk = psk,
                kexTxid = kexTxId,
                kexAckTxid = kexAckTxId,
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
        } catch (e: co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.ReplayDetectedException) {
            Log.d("ZCHAT_E2E", "Replay of counter ${e.counter} for ${peerAddress.redactAddress()} — already decrypted this session")
            "\uD83D\uDD12 Encrypted message" // Lock emoji — replay of already-seen message
        } catch (e: Exception) {
            Log.w("ZCHAT_E2E", "Ratchet decrypt failed for ${peerAddress.redactAddress()}: ${e.javaClass.simpleName}")
            "\uD83D\uDD10 Encrypted message (unable to decrypt)" // Lock+key emoji
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
     * @param receivedTxId Transaction ID of the received KEX/KEXACK for root derivation
     */
    private fun handleKEXMessage(memoText: String, ourAddress: String, receivedTxId: String? = null) {
        viewModelScope.launch {
            try {
                // #201 anti-flap: a shielded wallet re-scans its ENTIRE history every sync. Without
                // per-txid dedup an OLD KEX tx (from a peer that has since rotated its key / reinstalled)
                // is re-handled on every pass → its now-superseded key differs from the stored one →
                // false "PEER KEY CHANGED" → the kexAckedKeys guard is cleared → a KEXACK is re-sent.
                // That churn perpetually LOCKS the single spendable note (every on-chain send then fails
                // "Insufficient balance (have 0)") and spams a false key-change alarm. Process each KEX/
                // KEXACK tx EXACTLY ONCE. Mark it consumed UP FRONT (atomic check-then-mark) so the N
                // concurrent re-scan invocations for one txid can't all slip past before any marks it,
                // and a slow KEXACK retry can't delay the mark. KEX/KEXACK handling is self-contained
                // (verify + store the key synchronously, before any send), so a thrown send is safely
                // best-effort (its own retry covers it; if the app dies the peer re-sends a NEW KEX with
                // a NEW txid). A genuine key rotation also arrives as a NEW tx → not skipped, still detected.
                if (receivedTxId != null) {
                    if (zchatPreferences.hasProcessedKexTx(receivedTxId)) return@launch
                    zchatPreferences.markKexTxProcessed(receivedTxId)
                }
                when {
                    ZMSGProtocol.isKEXMessage(memoText) -> {
                        val parsed = ZMSGProtocol.parseKEXMessage(memoText) ?: return@launch
                        val (convId, kexPayload) = parsed

                        // OPEN-INBOX first contact (TOFU): resolve via an existing convId→peer mapping when
                        // we initiated; otherwise recover the sender's claimed address from the KEX payload
                        // so anyone with our (public) address can start a conversation we can reply to.
                        // SECURITY MODEL: this is trust-on-first-use, NOT proof of address control. The
                        // claimed address is NOT verified — but replying is still safe because our reply is
                        // sent on-chain TO that claimed address (a forger who claimed someone else's address
                        // cannot receive the reply). The residual risk is inbound spoofing, mitigated by (a)
                        // the conversation being UNVERIFIED until the user checks the safety number, and (b)
                        // the key-change warning below firing if an established contact's key ever differs.
                        val mappedSender = zchatPreferences.getPeerByConversationId(convId)
                        val senderAddress: String =
                            if (mappedSender != null) {
                                mappedSender
                            } else {
                                val recovered = E2EEncryption.parseKEXPayloadFull(kexPayload, null)?.senderAddress
                                if (recovered == null) {
                                    Log.w("KEX", "Cannot process KEX - no convId mapping and no address in payload: $convId")
                                    return@launch
                                }
                                zchatPreferences.setConversationId(recovered, convId)
                                // New peer stays UNVERIFIED by default (isE2EVerified is only set via an
                                // explicit out-of-band markE2EVerified) — the UI surfaces that state.
                                Log.d("KEX", "Open-inbox first contact — recovered sender ${recovered.redactAddress()} (UNVERIFIED)")
                                recovered
                            }

                        // Verify and extract public key (+ optional NOSTR fields for one-tap calling).
                        // Verify against the convId-mapped address first; fall back to the SELF-SIGNED
                        // payload address if that fails — the peer may sign with a different valid
                        // representation of its own UA than we have stored (see the KEXACK path for the
                        // full rationale; key-change TOFU still guards against a real MITM).
                        val parsedKex = E2EEncryption.parseKEXPayloadFull(kexPayload, senderAddress)
                            ?: E2EEncryption.parseKEXPayloadFull(kexPayload, null)
                        if (parsedKex == null) {
                            Log.e("KEX", "KEX signature verification FAILED for ${senderAddress.redactAddress()}")
                            return@launch
                        }
                        val peerPublicKey = parsedKex.publicKey

                        Log.d("KEX", "KEX verified from ${senderAddress.redactAddress()} - storing pubkey")

                        // Detect key change: if peer already had a stored pubkey and the
                        // new one differs, flag it for the Key-Changed banner in ChatDetail.
                        val previousPubkey = zchatPreferences.getE2EPeerPublicKey(senderAddress)
                        if (previousPubkey != null && previousPubkey != peerPublicKey) {
                            Log.w("KEX", "PEER KEY CHANGED for ${senderAddress.redactAddress()} — possible reinstall or MITM")
                            zchatPreferences.setE2EKeyChanged(senderAddress, true)
                            // A key change invalidates any prior out-of-band verification.
                            zchatPreferences.setE2EVerified(senderAddress, false)
                            // Invalidate cached message processor so a new one is built with the new key
                            messageProcessors.keys.removeAll { it.startsWith(senderAddress) }
                            // A changed key is a genuinely new handshake — allow one fresh paid KEXACK.
                            kexAckedKeys.remove(senderAddress)
                        }

                        // Store peer's public key + KEX txid for root derivation
                        zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)
                        if (receivedTxId != null) {
                            zchatPreferences.setE2EKexTxId(senderAddress, receivedTxId)
                        }

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

                        // BUG-4 one-tap calling: if the KEX carried the peer's NOSTR pubkey + relay,
                        // store them now (TOFU-bound to the just-verified E2E key) so calls connect
                        // without a separate ZBOOT round-trip. Absent → existing ZBOOT path delivers it.
                        applyKEXNostr(senderAddress, parsedKex.nostrPubkeyHex, parsedKex.relayUrl)

                        // Send KEXACK in response — but only once per (peer, key). The peer (or NOSTR
                        // re-delivery) can re-send the same KEX repeatedly; re-acking each one used to
                        // burn ~1000 zatoshi per duplicate (a real drain). Skip if we already paid for
                        // an ack of THIS exact key; a failed ack isn't recorded, so it still retries.
                        // The in-flight guard makes "check + send" atomic so the N concurrent invocations
                        // from one re-scanned KEX can't each launch a duplicate KEXACK (note contention).
                        if (kexAckedKeys[senderAddress] == peerPublicKey) {
                            Log.d("KEX", "KEXACK already sent for current key of ${senderAddress.redactAddress()} — skipping (no re-charge)")
                        } else if (!kexAckInFlight.add(senderAddress)) {
                            Log.d("KEX", "KEXACK already in flight for ${senderAddress.redactAddress()} — skipping duplicate")
                        } else {
                            try {
                                if (sendKEXAckMessage(senderAddress, ourAddress, convId)) {
                                    kexAckedKeys[senderAddress] = peerPublicKey
                                }
                            } finally {
                                kexAckInFlight.remove(senderAddress)
                            }
                        }
                    }

                    ZMSGProtocol.isKEXAckMessage(memoText) -> {
                        val parsed = ZMSGProtocol.parseKEXAckMessage(memoText) ?: return@launch
                        val (convId, kexAckPayload) = parsed

                        // Resolve the sender AND verify the signature. With a known convId→peer mapping
                        // we verify against THAT address first (tightest binding). BUT a peer may sign its
                        // KEXACK with a DIFFERENT valid representation of its own unified address than the
                        // one we have stored for it (diversifier / derivation drift across reinstalls or
                        // SDK upgrades) — the signature binds (senderAddress||pubkey), so a representation
                        // mismatch makes the mapped-address verify FAIL even though the wire is perfectly
                        // self-consistent. That was a real first-contact-breaking bug: Honor's KEXACK was
                        // signed with its current self-address but Seeker had an older representation →
                        // "KEXACK signature verification FAILED" forever → the tunnel never completed.
                        // So fall back to verifying against the SELF-SIGNED payload address (pass null),
                        // exactly what the KEX path already does for first contact. Security is unchanged:
                        // the E2E key-change guard below flags a genuine key swap (MITM), and the key is
                        // TOFU-bound — address binding was never the actual protection.
                        val mappedSender = zchatPreferences.getPeerByConversationId(convId)
                        val parsedAck = E2EEncryption.parseKEXAckPayloadFull(kexAckPayload, mappedSender)
                            ?: E2EEncryption.parseKEXAckPayloadFull(kexAckPayload, null)
                        if (parsedAck == null) {
                            Log.e("KEX", "KEXACK signature verification FAILED (convId=$convId)")
                            return@launch
                        }
                        if (mappedSender != null && parsedAck.senderAddress != null && parsedAck.senderAddress != mappedSender) {
                            Log.d("KEX", "KEXACK signed with a different self-address representation than stored for convId=$convId — accepted via payload-address verify (key TOFU still applies)")
                        }
                        // Known mapping wins; otherwise use the address recovered+verified from the wire.
                        val senderAddress = mappedSender ?: parsedAck.senderAddress
                        if (senderAddress == null) {
                            Log.w("KEX", "Cannot process KEXACK - unknown conversation ID $convId and no recoverable signed address")
                            return@launch
                        }
                        if (mappedSender == null) {
                            // First-contact / lost-mapping recovery: re-establish convId→peer so the ack lands.
                            zchatPreferences.setConversationId(senderAddress, convId)
                            Log.d("KEX", "KEXACK first-contact recovery: mapped convId $convId → ${senderAddress.redactAddress()}")
                        }
                        val peerPublicKey = parsedAck.publicKey

                        Log.d("KEX", "KEXACK verified from ${senderAddress.redactAddress()} - key exchange complete!")

                        // Detect key change (same logic as KEX path above)
                        val prevPub = zchatPreferences.getE2EPeerPublicKey(senderAddress)
                        if (prevPub != null && prevPub != peerPublicKey) {
                            Log.w("KEX", "PEER KEY CHANGED via KEXACK for ${senderAddress.redactAddress()}")
                            zchatPreferences.setE2EKeyChanged(senderAddress, true)
                            // A key change invalidates any prior out-of-band verification.
                            zchatPreferences.setE2EVerified(senderAddress, false)
                            messageProcessors.keys.removeAll { it.startsWith(senderAddress) }
                        }

                        // Store peer's public key + KEXACK txid for root derivation
                        zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)
                        if (receivedTxId != null) {
                            zchatPreferences.setE2EKexAckTxId(senderAddress, receivedTxId)
                        }

                        // BUG-4 one-tap calling: store peer NOSTR pubkey + relay if the KEXACK carried
                        // them (TOFU-bound to the verified E2E key). On first contact the KEXACK can't
                        // fit them (the address fills the memo), so they're delivered by the ZBOOT below.
                        applyKEXNostr(senderAddress, parsedAck.nostrPubkeyHex, parsedAck.relayUrl)

                        // We (the KEX initiator) now hold the peer's E2E key → they can verify a ZBOOT
                        // from us. Deliver OUR NOSTR identity in its own memo NOW (sequenced AFTER the
                        // KEXACK so it doesn't race the KEX for the single spendable note). The peer's
                        // routeIncomingBoot will store it and reply with THEIR ZBOOT → both sides get
                        // each other's NOSTR pubkey → calls unlock. Idempotent (see sendNostrBootHandshake).
                        sendNostrBootHandshake(senderAddress)

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
                        val senderAddress = parsed.senderAddress
                        if (senderAddress == null) {
                            Log.w("KEX", "Cannot process E2E_INIT - no sender address in message")
                            return@launch
                        }

                        // SECURITY (downgrade/MITM defense): the legacy E2E_INIT is UNSIGNED, so it must
                        // NEVER be allowed to overwrite a key we already hold — otherwise an attacker who
                        // can inject a memo could replace a properly KEX-verified key with their own and
                        // MITM the conversation. We only accept an unsigned key for genuine first-contact
                        // (TOFU) when no key exists yet. A differing key for an existing peer is treated as
                        // a key-change event (flagged for the banner), not silently applied.
                        val existingPub = zchatPreferences.getE2EPeerPublicKey(senderAddress)
                        when {
                            existingPub == null -> {
                                Log.d("KEX", "Legacy E2E_INIT from ${senderAddress.redactAddress()} - first-contact TOFU store (UNSIGNED)")
                                zchatPreferences.setE2EPeerPublicKey(senderAddress, peerPublicKey)
                                // Auto-enable E2E only on first-contact bootstrap.
                                if (!zchatPreferences.isE2EEnabled(senderAddress)) {
                                    if (zchatPreferences.getE2EOurPublicKey(senderAddress) == null) {
                                        val keyPair = E2EEncryption.generateKeyPair()
                                        zchatPreferences.setE2EOurKeys(senderAddress, keyPair.publicKey, keyPair.privateKey)
                                        zchatPreferences.setE2EKeyVersion(senderAddress, E2EKeyVersion.V2.value)
                                    }
                                    zchatPreferences.setE2EEnabled(senderAddress, true)
                                }
                            }
                            existingPub == peerPublicKey -> {
                                Log.d("KEX", "Legacy E2E_INIT from ${senderAddress.redactAddress()} matches stored key - ignoring (no-op)")
                            }
                            else -> {
                                // Differs from an established key → refuse to overwrite via an unsigned path.
                                Log.w("KEX", "Legacy E2E_INIT from ${senderAddress.redactAddress()} would CHANGE an existing key via UNSIGNED path — rejected as possible downgrade/MITM, flagged")
                                zchatPreferences.setE2EKeyChanged(senderAddress, true)
                                // A key change invalidates any prior out-of-band verification.
                                zchatPreferences.setE2EVerified(senderAddress, false)
                            }
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

                // BUG-4 one-tap calling: piggyback OUR NOSTR pubkey + relay onto the FIRST KEX so the
                // peer can place calls immediately, without a separate ZBOOT round-trip. Null (e.g.
                // seed unavailable) → omitted, and the peer falls back to the existing ZBOOT flow.
                val (ourNostrPub, ourRelay) = getOurNostrPubkey() ?: (null to null)

                // Create signed KEX payload
                val kexPayload = E2EEncryption.createKEXPayload(
                    ourAddress, ourPublicKey, ourPrivateKey, ourNostrPub, ourRelay
                )

                // Create full KEX message
                val kexMessage = ZMSGProtocol.createV4KEXMessage(convId, ourAddress, kexPayload)

                Log.d("KEX", "Sending KEX to ${peerAddress.redactAddress()} convId=${convId.redactConvId()}")

                // Send via transaction, block-aware-retrying a TRANSIENT note lock (e.g. our prior tx's
                // change still maturing). A permanent failure clears isOwnBootSent below to re-arm.
                if (!sendHandshakeMemoWithRetry(peerAddress, ourAddress, kexMessage)) {
                    zchatPreferences.setOwnBootSent(peerAddress, false)
                }

            } catch (e: Exception) {
                Log.e("KEX", "Failed to send KEX message", e)
                // A KEX carries OUR E2E key (and piggybacks our NOSTR pubkey) to the peer; if it failed
                // (e.g. single-note serialization left no spendable Orchard input this block) the peer
                // never received our key, so the bootstrap is NOT actually complete. ensureNostrBootstrapSent
                // sets isOwnBootSent=true before this async send resolves — clear it here so the next
                // non-force trigger (mode re-apply, periodic retry) re-fires the bootstrap and retries the
                // KEX, instead of a failed KEX leaving a permanently stuck half-handshake (ZBOOT sent, key
                // never delivered → peer drops every ZBOOT as "no verified E2E identity").
                zchatPreferences.setOwnBootSent(peerAddress, false)
            }
        }
    }

    /**
     * Send a KEXACK (Key Exchange Acknowledgment) in response to a KEX message.
     */
    private suspend fun sendKEXAckMessage(peerAddress: String, ourAddress: String, convId: String): Boolean {
        try {
            val ourPublicKey = zchatPreferences.getE2EOurPublicKey(peerAddress) ?: return false
            val ourPrivateKey = zchatPreferences.getE2EPrivateKey(peerAddress) ?: return false

            // BUG-4 one-tap calling: piggyback OUR NOSTR pubkey + relay onto the KEXACK too, so the
            // initiator learns our NOSTR identity from the handshake reply (no separate ZBOOT).
            val (ourNostrPub, ourRelay) = getOurNostrPubkey() ?: (null to null)

            // Create signed KEXACK payload
            val kexAckPayload = E2EEncryption.createKEXAckPayload(
                ourAddress, ourPublicKey, ourPrivateKey, ourNostrPub, ourRelay
            )

            // Create full KEXACK message
            val kexAckMessage = ZMSGProtocol.createV4KEXAckMessage(convId, ourAddress, kexAckPayload)

            Log.d("KEX", "Sending KEXACK to ${peerAddress.redactAddress()} convId=${convId.redactConvId()}")

            // Send via transaction. Block-aware-retry a TRANSIENT note lock — the KEXACK fires the
            // instant the peer's KEX lands, which momentarily locks our spendable note; without this
            // the KEXACK is silently dropped and the peer never gets our E2E key (handshake stalls).
            return sendHandshakeMemoWithRetry(peerAddress, ourAddress, kexAckMessage)
        } catch (e: Exception) {
            Log.e("KEX", "Failed to send KEXACK", e)
            return false
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
    private suspend fun checkForRemoteKill(amountZatoshi: Long, memo: String?, txId: String) {
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
    // ==========================================
    // QUANTUM SHIELD
    // ==========================================

    /** Get the Quantum Shield status for a conversation. */
    fun getQuantumShieldStatus(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus {
        val psk = zchatPreferences.getQuantumShieldPSK(peerAddress)
        val ourSecret = zchatPreferences.getQuantumShieldOurSecret(peerAddress)
        return when {
            !psk.isNullOrEmpty() -> co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus.ACTIVE
            !ourSecret.isNullOrEmpty() -> co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus.PENDING
            else -> co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus.NONE
        }
    }

    /** Generate (or reuse) our Quantum Shield secret and return its QR payload string. */
    fun initiateQuantumShield(peerAddress: String): String {
        // IDEMPOTENT: if setup is already in progress (PENDING: our secret exists, no PSK yet), REUSE
        // the existing secret so re-opening the dialog shows the SAME QR. Regenerating a fresh secret
        // on every tap was a real bug — it invalidated any QR the peer had already scanned, so their
        // derived PSK no longer matched ours and the shield silently never activated. Only generate a
        // new secret when starting fresh (NONE) or re-initiating after a reset.
        val existing = zchatPreferences.getQuantumShieldOurSecret(peerAddress)
        val pskExists = !zchatPreferences.getQuantumShieldPSK(peerAddress).isNullOrEmpty()
        // Any time a secret already exists (PENDING or ACTIVE), REUSE it — never regenerate over a live
        // secret: in PENDING a fresh secret invalidates a QR the peer already scanned; in ACTIVE it would
        // orphan the agreed PSK on re-entry. Re-keying must go through resetQuantumShield() first.
        if (!existing.isNullOrEmpty()) {
            val secret = java.util.Base64.getDecoder().decode(existing)
            Log.d("ZCHAT_QS", "Quantum Shield re-using existing secret (active=$pskExists) for ${peerAddress.redactAddress()}")
            return co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield.toQRPayload(secret)
        }
        val secret = co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield.generateRandom()
        val b64 = java.util.Base64.getEncoder().encodeToString(secret)
        zchatPreferences.setQuantumShieldOurSecret(peerAddress, b64)
        // Invalidate cached processor so next message uses new root (once PSK is active)
        messageProcessors.keys.removeAll { it.startsWith(peerAddress) }
        Log.d("ZCHAT_QS", "Quantum Shield generated NEW secret for ${peerAddress.redactAddress()}")
        return co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield.toQRPayload(secret)
    }

    /** Process peer's scanned QR payload and activate the shield if our secret exists. */
    fun completeQuantumShield(peerAddress: String, qrPayload: String): Boolean {
        // Idempotent: if the shield is already ACTIVE, a second scan (user scans twice, or scans a
        // different/stale QR) must NOT silently re-derive and overwrite the agreed PSK — that would
        // desync us from the peer and break the shield. Treat a repeat completion as a no-op success.
        if (!zchatPreferences.getQuantumShieldPSK(peerAddress).isNullOrEmpty()) {
            Log.d("ZCHAT_QS", "Quantum Shield already ACTIVE for ${peerAddress.redactAddress()} — ignoring repeat scan")
            return true
        }
        val peerSecret = co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield.fromQRPayload(qrPayload) ?: return false
        val ourSecretB64 = zchatPreferences.getQuantumShieldOurSecret(peerAddress) ?: return false
        val ourSecret = java.util.Base64.getDecoder().decode(ourSecretB64)

        val psk = co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield.derivePSK(ourSecret, peerSecret)
        val pskB64 = java.util.Base64.getEncoder().encodeToString(psk)
        zchatPreferences.setQuantumShieldPSK(peerAddress, pskB64)

        // Invalidate cached processor — new root key includes PSK
        messageProcessors.keys.removeAll { it.startsWith(peerAddress) }
        Log.d("ZCHAT_QS", "Quantum Shield ACTIVE for ${peerAddress.redactAddress()}")
        return true
    }

    /** Reset Quantum Shield to NONE — clears PSK and secrets for this peer. */
    fun resetQuantumShield(peerAddress: String) {
        zchatPreferences.clearQuantumShieldPSK(peerAddress)
        // Clear our secret too so the UI returns to NONE
        zchatPreferences.setQuantumShieldOurSecret(peerAddress, "")
        messageProcessors.keys.removeAll { it.startsWith(peerAddress) }
        Log.d("ZCHAT_QS", "Quantum Shield RESET for ${peerAddress.redactAddress()}")
    }

    /**
     * Download, decrypt, and cache a file referenced by a ZFILE message.
     * Called in the background when a ZFILE message is detected.
     */
    fun downloadAndCacheFile(
        zfileContent: String,
        peerAddress: String,
        context: android.content.Context,
    ) {
        val parsed = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.parse(zfileContent) ?: return
        // SECURITY (view-once): markFileViewed() securely wipes + deletes the cache file, after
        // which the auto-download trigger sees `!cacheFile.exists()` and would re-fetch the file
        // from the relay — resurrecting content the user already burned. Refuse to re-download a
        // view-once file that has already been consumed.
        if (parsed.viewOnce && zchatPreferences.isFileViewed(parsed.hash)) return
        val cache = getFileCache(context)
        if (cache.has(parsed.hash)) return // Already cached
        // Reject before touching the network if the memo itself declares an oversized file.
        if (parsed.size > maxFileDownloadBytes) {
            Log.e("ZCHAT_FILE", "Refusing oversized ZFILE ${parsed.hash}: declared size=${parsed.size} > cap=$maxFileDownloadBytes")
            return
        }
        if (fileDownloadsInProgress.putIfAbsent(parsed.hash, true) != null) return // Already downloading
        // #211: run on Dispatchers.IO — this coroutine streams + decrypts the blob and writes it to the
        // disk cache (FileDownloadCache.put). On the default Main dispatcher that disk write trips
        // StrictMode (and risks jank/ANR for larger files). The progress/state updates below are
        // StateFlow writes, safe from any thread.

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Clear any prior failure marker so a manual retry repaints as "in progress".
                setFileDownloadFailed(parsed.hash, false)
                Log.d("ZCHAT_FILE", "Downloading file: ${parsed.url.redactUrl()}")
                updateFileProgress(parsed.hash, 0f)

                // Stream the encrypted file with byte-level progress, enforcing a hard size cap as we
                // read so a lying/absent Content-Length can't make us buffer an unbounded body into
                // memory (OOM). Reserve the last 5% of the bar for decrypt+cache write so the user
                // doesn't see "100%" while we're still working.
                val client = io.ktor.client.HttpClient()
                val encryptedBytes = try {
                    client.prepareGet(parsed.url) {
                        onDownload { bytesReceivedTotal, contentLength ->
                            val total = contentLength ?: parsed.size
                            if (total > 0) {
                                updateFileProgress(parsed.hash, (bytesReceivedTotal.toFloat() / total) * 0.95f)
                            }
                        }
                    }.execute { response ->
                        val declaredLen = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
                        if (declaredLen != null && declaredLen > maxFileDownloadBytes) {
                            throw java.io.IOException("Content-Length $declaredLen exceeds cap $maxFileDownloadBytes")
                        }
                        val channel = response.bodyAsChannel()
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read == -1) break
                            if (read == 0) continue
                            total += read
                            if (total > maxFileDownloadBytes) {
                                throw java.io.IOException("download exceeded cap $maxFileDownloadBytes mid-stream")
                            }
                            out.write(buf, 0, read)
                        }
                        out.toByteArray()
                    }
                } finally {
                    client.close()
                }

                Log.d("ZCHAT_FILE", "Downloaded ${encryptedBytes.size} bytes from ${parsed.url.redactUrl()}")

                // Verify integrity: size + SHA-256 of ciphertext must match ZFILE metadata
                if (!co.electriccoin.zcash.ui.screen.chat.filesharing.FileIntegrityCheck.verify(
                        encryptedBytes, parsed.hash, parsed.size
                    )
                ) {
                    Log.e("ZCHAT_FILE", "Integrity check FAILED for ${parsed.hash} — " +
                        "expected size=${parsed.size} got=${encryptedBytes.size}")
                    setFileDownloadFailed(parsed.hash, true)
                    return@launch
                }

                // Unwrap key using the E2E shared secret. If we have NO shared secret with this peer
                // we cannot decrypt — ABORT, don't silently unwrap with an all-zero key. A zero key is
                // predictable and identical across peers; falling back to it contradicts the app's
                // "fail closed, never weaken crypto" policy. Surface as a failed download instead.
                val sharedKey = getE2ESharedKey(peerAddress)
                if (sharedKey == null) {
                    Log.e("ZCHAT_FILE", "No E2E shared secret with ${peerAddress.redactAddress()} — cannot decrypt file ${parsed.hash} (need KEX first)")
                    setFileDownloadFailed(parsed.hash, true)
                    return@launch
                }
                val wrappedKeyBytes = java.util.Base64.getDecoder().decode(parsed.wrappedKey)
                val fileKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.unwrapFileKey(wrappedKeyBytes, sharedKey)
                // A corrupt or forged wrappedKey can unwrap to a wrong-sized key — reject before use.
                require(fileKey.size == 32) { "Unwrapped file key has wrong size: ${fileKey.size}" }

                // Decrypt
                val decryptedBytes = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.decryptFile(encryptedBytes, fileKey)
                Log.d("ZCHAT_FILE", "Decrypted ${decryptedBytes.size} bytes, caching as ${parsed.hash}")

                // Cache
                cache.put(parsed.hash, decryptedBytes)
                updateFileProgress(parsed.hash, 1f)
            } catch (e: Exception) {
                Log.w("ZCHAT_FILE", "File download/decrypt failed for ${parsed.hash}: ${e.message}")
                setFileDownloadFailed(parsed.hash, true)
            } finally {
                fileDownloadsInProgress.remove(parsed.hash)
                // Brief visible "100%" then clear so the bubble flips to the image. If we cleared
                // immediately the bar would flash off before the bitmap decode finishes.
                kotlinx.coroutines.delay(500)
                updateFileProgress(parsed.hash, null)
            }
        }
    }

    /**
     * Handle a picked image URI from the image picker. Compresses, encrypts, uploads
     * via NIP-96/Blossom, creates a ZFILE message, and sends it as a memo.
     */
    fun handlePickedImage(
        peerAddress: String,
        uri: android.net.Uri,
        context: android.content.Context,
    ) = handlePickedImage(peerAddress, uri, context, viewOnce = false)

    /**
     * View-once variant of [handlePickedImage]. Sets the viewOnce bit in the ZFILE memo and
     * also wipes the sender's own local cache after upload so neither party can re-view it.
     */
    fun handlePickedImageViewOnce(
        peerAddress: String,
        uri: android.net.Uri,
        context: android.content.Context,
    ) = handlePickedImage(peerAddress, uri, context, viewOnce = true)

    private fun handlePickedImage(
        peerAddress: String,
        uri: android.net.Uri,
        context: android.content.Context,
        viewOnce: Boolean,
    ) {
        // Atomically claim the upload slot. Rejects a second tap that races with the first,
        // not just a strictly-later one (read-then-launch was not atomic).
        if (!uploadProgressTracker.tryStart()) {
            Log.w("ZCHAT_FILE", "Ignoring image pick: upload already in progress")
            return
        }
        // Optimistic bubble id — the message renders BEFORE compression/encryption/upload
        // so the user sees instant feedback instead of 5–10s of nothing. We update it twice:
        // once to attach the local thumbnail (post-compress) and once on failure (FAILED state).
        // On success the bubble is removed right before sendMessage() inserts the final
        // pending entry; the renderer sees a seamless handoff because both reference the
        // same on-disk cache file (sha256 of the encrypted bytes).
        val optimisticId = "img-pending-${System.nanoTime()}"
        viewModelScope.launch {
            try {
                // 1) Insert a "preparing" placeholder so the chat reacts within one frame.
                pendingMessages.update {
                    it + ChatMessage(
                        id = optimisticId,
                        txId = null,
                        text = "📷 Preparing image…",
                        timestamp = Instant.now(),
                        isOutgoing = true,
                        peerAddress = peerAddress,
                        isPending = true,
                        status = MessageStatus.SENDING,
                    )
                }

                // Memory-bounded prep: never load the full source bytes for large images.
                // For sources > 500KB, the picker URI is opened twice — first for bounds, then
                // for the sampled decode — so a 50MP HEIC source no longer OOMs on readBytes().
                // Runs on Dispatchers.IO: prepare() does ContentResolver queries + openInputStream
                // + BitmapFactory stream decodes, all blocking disk I/O that must not touch the
                // main thread (StrictMode DiskReadViolation, #203).
                val compressed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    co.electriccoin.zcash.ui.screen.chat.filesharing.ImageUploadPrep
                        .prepare(context.contentResolver, uri)
                } ?: throw Exception("Cannot read or process image (too large, unsupported, or unreadable)")
                uploadProgressTracker.compressed()

                // Encrypt
                val fileKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.generateFileKey()
                val encrypted = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.encryptFile(compressed, fileKey)
                uploadProgressTracker.encrypted()

                // 2) Pre-cache the plaintext under the final ZFILE hash so the renderer can
                // show the image straight from disk while the upload runs. The renderer reads
                // from cacheDir/zchat_files/<hash> and is happy either with a downloaded blob
                // or — as here — our own plaintext pre-write.
                val sha256Hex = co.electriccoin.zcash.ui.nostr.FileUploadManager.sha256Hex(encrypted)
                val zfileHash = sha256Hex.take(32)
                val cacheDir = java.io.File(context.cacheDir, "zchat_files").apply { mkdirs() }
                val cacheFile = java.io.File(cacheDir, zfileHash)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { cacheFile.writeBytes(compressed) }
                        .onFailure { Log.w("ZCHAT_FILE", "Optimistic cache write failed: ${it.message}") }
                }
                pendingMessages.update { list ->
                    list.map { m ->
                        if (m.id == optimisticId) {
                            m.copy(
                                text = if (viewOnce) "🔒 View once photo" else "📷 Photo",
                                // fileHash present → MessageBubble renders from cache and shows
                                // uploadProgress overlay via isOutgoingInFlight (ChatDetailView:778).
                                fileHash = zfileHash,
                                fileType = co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.JPEG,
                                fileViewOnce = viewOnce,
                                // fileZfileContent stays null so the download-trigger
                                // LaunchedEffect (AndroidChat:506) skips this message.
                            )
                        } else m
                    }
                }

                // Wrap the file key with the E2E shared secret. ABORT if there's none: wrapping with
                // an all-zero key is NOT encryption — the key is public, so anyone who sees the memo
                // can unwrap it and decrypt the file. The app is fail-closed (never silently downgrade
                // crypto), so refuse to send rather than ship an effectively-plaintext file. The outer
                // catch resets the upload progress and surfaces the failure.
                val sharedKey = getE2ESharedKey(peerAddress)
                    ?: error("No E2E shared secret with ${peerAddress.redactAddress()} — refusing to send file unencrypted (need KEX first)")
                val wrappedKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.wrapFileKey(fileKey, sharedKey)
                val wrappedKeyB64 = java.util.Base64.getEncoder().encodeToString(wrappedKey)

                Log.d("ZCHAT_FILE", "Image: ${compressed.size}B compressed, ${encrypted.size}B encrypted, sha256=${sha256Hex.take(16)}...")

                // Derive NOSTR identity from BIP39 seed for NIP-96/Blossom auth
                val wallet = persistableWalletProvider.requirePersistableWallet()
                val bip39Seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                val nostrIdentity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(bip39Seed, zchatPreferences.getNostrRotationIndex())

                // Upload encrypted file
                val httpClientProvider = object : co.electriccoin.zcash.ui.common.provider.HttpClientProvider {
                    override suspend fun create() = io.ktor.client.HttpClient()
                }
                val uploadManager = co.electriccoin.zcash.ui.nostr.FileUploadManager(nostrIdentity, httpClientProvider)
                uploadProgressTracker.uploading(0.1f)
                // Servers (nostr.build NIP-96) whitelist media types and reject
                // application/octet-stream. The encrypted body is opaque either way; declaring
                // image/jpeg gets the blob accepted. handlePickedFile() overrides with the real
                // type for non-image documents.
                val uploadResult = uploadManager.upload(encrypted, "image/jpeg") { fraction ->
                    uploadProgressTracker.uploading(fraction)
                }
                uploadProgressTracker.uploaded()

                val fileUrl = when (uploadResult) {
                    is co.electriccoin.zcash.ui.nostr.UploadOutcome.Success -> {
                        Log.d("ZCHAT_FILE", "Upload success: ${uploadResult.url.redactUrl()}")
                        uploadResult.url
                    }
                    is co.electriccoin.zcash.ui.nostr.UploadOutcome.Failure -> {
                        Log.e("ZCHAT_FILE", "Upload failed: ${uploadResult.error} (${uploadResult.serverUrl})")
                        throw Exception("Upload failed: ${uploadResult.error}")
                    }
                }

                // Create ZFILE message
                val zfile = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage(
                    hash = zfileHash,
                    type = co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.JPEG,
                    size = encrypted.size.toLong(),
                    url = fileUrl,
                    wrappedKey = wrappedKeyB64,
                    blurhash = "",
                    viewOnce = viewOnce,
                )

                // 3) Remove the optimistic bubble RIGHT BEFORE sendMessage inserts its own pending
                // entry. Both reference the same fileHash → same on-disk cache file → no visual
                // jump for the user; just the message id changes underneath.
                pendingMessages.update { list -> list.filterNot { it.id == optimisticId } }
                sendMessage(peerAddress, zfile.serialize())
                uploadProgressTracker.sent()

                // View-once: wipe the sender's local cache so neither side has the plaintext
                // sitting on disk. The bubble will rebuild via convertToConversations as the
                // tx confirms, picking up fileViewOnce + (after we mark it) fileViewed.
                if (viewOnce) {
                    runCatching { cacheFile.delete() }
                    zchatPreferences.markFileViewed(zfileHash)
                }
                Log.d("ZCHAT_FILE", "ZFILE message sent for ${peerAddress.redactAddress()} viewOnce=$viewOnce")
                // Brief visible "Sent" state before clearing the bar. If cancelled, CancellationException
                // propagates out of delay; the finally below still clears the tracker.
                kotlinx.coroutines.delay(400)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Scope was cancelled (e.g. navigation away). Don't mark as failed; just let the
                // finally clear state, and rethrow so cancellation semantics are preserved.
                // The optimistic bubble stays in pendingMessages until the next loadConversations
                // pass rebuilds it from sources — acceptable since cancellation is rare.
                throw e
            } catch (e: Exception) {
                Log.e("ZCHAT_FILE", "Image send failed: ${e.message}", e)
                // Surface the failure on the optimistic bubble itself instead of as a toast-only
                // error — the bubble was the user's visible commitment, leaving it pending forever
                // would be misleading.
                pendingMessages.update { list ->
                    list.map { m ->
                        if (m.id == optimisticId) {
                            m.copy(
                                text = "📷 Photo (failed)",
                                isPending = false,
                                status = MessageStatus.FAILED,
                            )
                        } else m
                    }
                }
                _sendMessageState.value = co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error(
                    e.message ?: "Image send failed"
                )
            } finally {
                uploadProgressTracker.reset()
            }
        }
    }

    /**
     * Handle a picked arbitrary file URI (PDF, ZIP, TXT, image, etc.).
     * Reads bytes (capped at 25 MB to keep heap bounded), encrypts, uploads, and sends
     * a ZFILE message tagged with the resolved [ZFILEType]. Files outside the known
     * type set are rejected before any upload.
     */
    fun handlePickedFile(peerAddress: String, uri: android.net.Uri, context: android.content.Context) {
        if (!uploadProgressTracker.tryStart()) {
            Log.w("ZCHAT_FILE", "Ignoring file pick: upload already in progress")
            return
        }
        viewModelScope.launch {
            try {
                val cr = context.contentResolver
                val mimeFromCr = cr.getType(uri) ?: "application/octet-stream"
                val fileType = co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.fromMime(mimeFromCr)
                    ?: throw Exception("Unsupported file type: $mimeFromCr (supported: JPEG/PNG/GIF/WebP/PDF/ZIP/TXT)")

                // Hard cap to keep heap bounded — same envelope as the image flow.
                val maxBytes = 25L * 1024L * 1024L
                val size = cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else -1L
                } ?: -1L
                if (size in 1..maxBytes || size == -1L) {
                    // ok
                } else {
                    throw Exception("File too large (${size / (1024 * 1024)} MB; max 25 MB)")
                }

                val rawBytes = cr.openInputStream(uri)?.use { input ->
                    java.io.ByteArrayOutputStream().also { out ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw Exception("File too large (max 25 MB)")
                            out.write(buf, 0, n)
                        }
                    }.toByteArray()
                } ?: throw Exception("Cannot read file")

                uploadProgressTracker.compressed()

                val fileKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.generateFileKey()
                val encrypted = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.encryptFile(rawBytes, fileKey)
                uploadProgressTracker.encrypted()

                // Fail closed: no E2E shared secret → refuse to send. An all-zero wrap key is public,
                // so the file would be decryptable by anyone reading the memo (effectively plaintext).
                val sharedKey = getE2ESharedKey(peerAddress)
                    ?: error("No E2E shared secret with ${peerAddress.redactAddress()} — refusing to send file unencrypted (need KEX first)")
                val wrappedKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.wrapFileKey(fileKey, sharedKey)
                val wrappedKeyB64 = java.util.Base64.getEncoder().encodeToString(wrappedKey)

                val sha256 = co.electriccoin.zcash.ui.nostr.FileUploadManager.sha256Hex(encrypted)
                Log.d("ZCHAT_FILE", "File: ${rawBytes.size}B raw, ${encrypted.size}B encrypted, mime=$mimeFromCr, type=${fileType.code}")

                val wallet = persistableWalletProvider.requirePersistableWallet()
                val bip39Seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                val nostrIdentity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(bip39Seed, zchatPreferences.getNostrRotationIndex())
                val httpClientProvider = object : co.electriccoin.zcash.ui.common.provider.HttpClientProvider {
                    override suspend fun create() = io.ktor.client.HttpClient()
                }
                val uploadManager = co.electriccoin.zcash.ui.nostr.FileUploadManager(nostrIdentity, httpClientProvider)
                uploadProgressTracker.uploading(0.1f)
                // Declare image/jpeg even for documents — Blossom servers accept anything but
                // nostr.build's NIP-96 endpoint whitelists media types. The body is opaque
                // ciphertext either way; the receiver routes by ZFILEType, not server MIME.
                val uploadResult = uploadManager.upload(encrypted, "image/jpeg") { fraction ->
                    uploadProgressTracker.uploading(fraction)
                }
                uploadProgressTracker.uploaded()

                val fileUrl = when (uploadResult) {
                    is co.electriccoin.zcash.ui.nostr.UploadOutcome.Success -> uploadResult.url
                    is co.electriccoin.zcash.ui.nostr.UploadOutcome.Failure -> {
                        Log.e("ZCHAT_FILE", "Upload failed: ${uploadResult.error}")
                        throw Exception("Upload failed: ${uploadResult.error}")
                    }
                }

                val zfile = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage(
                    hash = sha256.take(32),
                    type = fileType,
                    size = encrypted.size.toLong(),
                    url = fileUrl,
                    wrappedKey = wrappedKeyB64,
                    blurhash = "",
                )
                sendMessage(peerAddress, zfile.serialize())
                uploadProgressTracker.sent()
                kotlinx.coroutines.delay(400)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ZCHAT_FILE", "File send failed: ${e.message}", e)
                _sendMessageState.value = co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error(
                    e.message ?: "File send failed"
                )
            } finally {
                uploadProgressTracker.reset()
            }
        }
    }

    /**
     * Upload + send a recorded voice message file (M4A). Shares the existing ZFILE
     * pipeline with handlePickedFile but skips the URI/MIME detour. The caller
     * (chat input mic button) supplies the already-finalized file path from
     * [AudioRecorder.stop]. The recording is deleted from disk on success or failure.
     */
    fun handleRecordedAudio(
        peerAddress: String,
        file: java.io.File,
        durationMs: Long,
        context: android.content.Context,
    ) = handleRecordedAudio(peerAddress, file, durationMs, context, viewOnce = false)

    fun handleRecordedAudioViewOnce(
        peerAddress: String,
        file: java.io.File,
        durationMs: Long,
        context: android.content.Context,
    ) = handleRecordedAudio(peerAddress, file, durationMs, context, viewOnce = true)

    private fun handleRecordedAudio(
        peerAddress: String,
        file: java.io.File,
        durationMs: Long,
        context: android.content.Context,
        viewOnce: Boolean,
    ) {
        if (!uploadProgressTracker.tryStart()) {
            Log.w("ZCHAT_FILE", "Ignoring audio: upload already in progress")
            return
        }
        val optimisticId = "audio-pending-${System.nanoTime()}"
        viewModelScope.launch {
            try {
                // Optimistic bubble — appears within one frame so the user sees the recording
                // commit instantly. Duration label lets them sanity-check the take.
                val durationSec = (durationMs / 1000).coerceAtLeast(1).toInt()
                pendingMessages.update {
                    it + ChatMessage(
                        id = optimisticId,
                        txId = null,
                        text = "🎙️ Voice message (${durationSec}s) — uploading…",
                        timestamp = Instant.now(),
                        isOutgoing = true,
                        peerAddress = peerAddress,
                        isPending = true,
                        status = MessageStatus.SENDING,
                    )
                }
                val rawBytes = file.readBytes()
                uploadProgressTracker.compressed()

                val fileKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.generateFileKey()
                val encrypted = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.encryptFile(rawBytes, fileKey)
                uploadProgressTracker.encrypted()

                // Fail closed: no E2E shared secret → refuse to send. An all-zero wrap key is public,
                // so the file would be decryptable by anyone reading the memo (effectively plaintext).
                val sharedKey = getE2ESharedKey(peerAddress)
                    ?: error("No E2E shared secret with ${peerAddress.redactAddress()} — refusing to send file unencrypted (need KEX first)")
                val wrappedKey = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.wrapFileKey(fileKey, sharedKey)
                val wrappedKeyB64 = java.util.Base64.getEncoder().encodeToString(wrappedKey)

                val sha256Hex = co.electriccoin.zcash.ui.nostr.FileUploadManager.sha256Hex(encrypted)
                val zfileHash = sha256Hex.take(32)

                // Pre-cache plaintext so our own outgoing bubble can play back without
                // a download round-trip — same trick as the image optimistic path.
                val cacheDir = java.io.File(context.cacheDir, "zchat_files").apply { mkdirs() }
                runCatching { java.io.File(cacheDir, zfileHash).writeBytes(rawBytes) }
                    .onFailure { Log.w("ZCHAT_FILE", "Audio cache write failed: ${it.message}") }
                pendingMessages.update { list ->
                    list.map { m ->
                        if (m.id == optimisticId) {
                            m.copy(
                                fileHash = zfileHash,
                                fileType = co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A,
                                fileDurationMs = durationMs,
                                fileViewOnce = viewOnce,
                            )
                        } else m
                    }
                }

                val wallet = persistableWalletProvider.requirePersistableWallet()
                val bip39Seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                val nostrIdentity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(bip39Seed, zchatPreferences.getNostrRotationIndex())
                val httpClientProvider = object : co.electriccoin.zcash.ui.common.provider.HttpClientProvider {
                    override suspend fun create() = io.ktor.client.HttpClient()
                }
                val uploadManager = co.electriccoin.zcash.ui.nostr.FileUploadManager(nostrIdentity, httpClientProvider)
                uploadProgressTracker.uploading(0.1f)
                // Declare image/jpeg to satisfy NIP-96 server allow-lists; body is opaque ciphertext.
                val uploadResult = uploadManager.upload(encrypted, "image/jpeg") { fraction ->
                    uploadProgressTracker.uploading(fraction)
                }
                uploadProgressTracker.uploaded()

                val fileUrl = when (uploadResult) {
                    is co.electriccoin.zcash.ui.nostr.UploadOutcome.Success -> uploadResult.url
                    is co.electriccoin.zcash.ui.nostr.UploadOutcome.Failure ->
                        throw Exception("Upload failed: ${uploadResult.error}")
                }

                val zfile = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage(
                    hash = zfileHash,
                    type = co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.M4A,
                    size = encrypted.size.toLong(),
                    url = fileUrl,
                    wrappedKey = wrappedKeyB64,
                    blurhash = "",
                    viewOnce = viewOnce,
                )
                pendingMessages.update { list -> list.filterNot { it.id == optimisticId } }
                sendMessage(peerAddress, zfile.serialize())
                uploadProgressTracker.sent()
                if (viewOnce) {
                    runCatching { java.io.File(cacheDir, zfileHash).delete() }
                    zchatPreferences.markFileViewed(zfileHash)
                }
                kotlinx.coroutines.delay(400)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ZCHAT_FILE", "Audio send failed: ${e.message}", e)
                pendingMessages.update { list ->
                    list.map { m ->
                        if (m.id == optimisticId) {
                            m.copy(
                                text = "🎙️ Voice message (failed)",
                                isPending = false,
                                status = MessageStatus.FAILED,
                            )
                        } else m
                    }
                }
                _sendMessageState.value = co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error(
                    e.message ?: "Voice message failed",
                )
            } finally {
                runCatching { file.delete() }
                uploadProgressTracker.reset()
            }
        }
    }

    /**
     * Handle a /ai slash command in chat. Renders the user's prompt + the AI's reply as
     * local-only messages (isAiMessage = true) — never sent on-chain, never ratcheted,
     * never debited against the user's ZEC balance. AI usage is charged against the
     * separate Venice credit balance (see AI tab).
     *
     * SECURITY: Reject sloppy variants ("/AI ", "/ai" without space) BEFORE this hits the
     * normal send path so the user can't accidentally leak their AI prompt to the peer
     * over the encrypted chat channel.
     */
    @Suppress("TooGenericExceptionCaught")
    fun handleAiCommand(peerAddress: String, prompt: String, context: android.content.Context) {
        if (prompt.isBlank()) return
        val now = java.time.Instant.now()
        val userMsgId = "ai-user-${System.nanoTime()}"
        val replyMsgId = "ai-reply-${System.nanoTime()}"

        // Insert user's prompt as a local outgoing message (renders immediately)
        val userMsg = ChatMessage(
            id = userMsgId,
            txId = null,
            text = prompt,
            timestamp = now,
            isOutgoing = true,
            peerAddress = peerAddress,
            isPending = false,
            status = MessageStatus.SENT,
            isAiMessage = true,
        )
        pendingMessages.update { it + userMsg }

        // Insert a placeholder "AI is thinking…" reply that we'll overwrite on success
        val thinkingMsg = ChatMessage(
            id = replyMsgId,
            txId = null,
            text = "🤖 Thinking…",
            timestamp = now.plusMillis(1),
            isOutgoing = false,
            peerAddress = peerAddress,
            isPending = true,
            status = MessageStatus.SENDING,
            isAiMessage = true,
        )
        pendingMessages.update { it + thinkingMsg }

        viewModelScope.launch {
            try {
                val prefs = co.electriccoin.zcash.ui.screen.ai.AiPreferences(context.applicationContext)
                val client = co.electriccoin.zcash.ui.screen.ai.AiApiClient()
                val token = prefs.getToken() ?: run {
                    // Auto-register binds to wallet pubkey so the chat-AI shortcut shares the
                    // same backend AiAccount as the AI tab (instead of minting a fresh $0.20
                    // trial per device). Same resolver as AndroidAiTab — see WalletPubkey.kt.
                    val walletPubkey = co.electriccoin.zcash.ui.screen.ai.WalletPubkey.deriveOrNull(getDefaultUnifiedAddress)
                    val r = client.register(walletPubkey)
                    if (r is co.electriccoin.zcash.ui.screen.ai.RegisterResult.Success) {
                        prefs.saveCredentials(r.token, r.userId)
                        r.token
                    } else {
                        replaceAiMessage(replyMsgId, peerAddress, "🤖 Could not initialize AI. Open AI tab first.", failed = true)
                        return@launch
                    }
                }
                // Use the user's selected model from the AI tab. Fall back to the cheap default
                // so we don't accidentally bill an expensive model when the user never opened
                // the AI tab. NOTE: this also respects free-tier gating server-side.
                val selectedModel = prefs.getSelectedChatModel() ?: "venice-uncensored-1-2"
                val r = client.chat(
                    token = token,
                    model = selectedModel,
                    history = listOf(
                        co.electriccoin.zcash.ui.screen.ai.AiChatTurn(
                            co.electriccoin.zcash.ui.screen.ai.AiChatTurn.ROLE_USER,
                            prompt,
                            System.currentTimeMillis(),
                        ),
                    ),
                    maxTokens = 1024,
                )
                when (r) {
                    is co.electriccoin.zcash.ui.screen.ai.ChatResult.Success -> {
                        replaceAiMessage(replyMsgId, peerAddress, "🤖 ${r.reply}", failed = false)
                    }
                    is co.electriccoin.zcash.ui.screen.ai.ChatResult.OutOfCredit -> {
                        replaceAiMessage(replyMsgId, peerAddress, "🤖 Out of AI credit. Top up in the AI tab.", failed = true)
                    }
                    is co.electriccoin.zcash.ui.screen.ai.ChatResult.Failure -> {
                        replaceAiMessage(replyMsgId, peerAddress, "🤖 ${r.error}", failed = true)
                    }
                }
            } catch (e: Exception) {
                Log.e("ZCHAT_AI", "AI command failed: ${e.message}", e)
                replaceAiMessage(replyMsgId, peerAddress, "🤖 AI request failed.", failed = true)
            }
        }
    }

    private fun replaceAiMessage(id: String, peerAddress: String, text: String, failed: Boolean) {
        pendingMessages.update { list ->
            list.map { m ->
                if (m.id == id) m.copy(
                    text = text,
                    isPending = false,
                    status = if (failed) MessageStatus.FAILED else MessageStatus.SENT,
                ) else m
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun sendMessage(peerAddress: String, message: String, amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT): Boolean {
        // Returns true when the send was ACCEPTED (broadcast, queued, or deferred behind the cost
        // disclaimer) and false when REJECTED before anything was queued (key changed / funds not in
        // the Orchard pool) — the caller keeps the user's typed text on false so it isn't lost
        // (B1-msg-lost-on-blocked-send).

        // SECURITY (TOFU / MITM): block a key-changed peer BEFORE transport routing so TUNNEL/OPEN
        // (NOSTR) sends are gated too, not just the VAULT path below. EXEMPT the ZBOOT handshake:
        // it is precisely how trust is RE-established after a key change, and ensureNostrBootstrapSent
        // routes it through here — gating it would DEADLOCK recovery (you could never re-handshake a
        // peer whose key rotated). Only content (text/file/etc.) stays gated.
        if (!co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(message) &&
            blockedByKeyChange(peerAddress)
        ) {
            return false
        }

        // Route via the conversation's transport mode. VAULT (default) falls through to
        // the existing shielded pipeline below; TUNNEL/OPEN send through NIP-17 instead.
        if (handleNostrRouteIfApplicable(peerAddress, message)) return true

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
                // Rejected before queue: nothing stored, so keep the user's typed text.
                return false
            }
        }

        // Check if user has acknowledged that messages cost ZEC
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            pendingMessage = PendingMessageParams(peerAddress, message, amountZatoshi = amountZatoshi)
            _showCostDisclaimer.value = true
            // Deferred, not rejected: the text is retained in pendingMessage and re-sent on
            // acknowledge, so treat as accepted and let the input clear.
            return true
        }

        // If the memo is a serialized ZFILE, render the pending bubble as a file placeholder
        // instead of the raw "ZFILE|hash|j|..." text. Otherwise the sender sees one long-text
        // bubble plus a separate file bubble after confirmation.
        val pendingZfile = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
            .takeIf { co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(message) }
            ?.parse(message)
        val pendingDisplayText = pendingZfile?.let { "📎 ${it.displayText}" }
            ?: if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(message)) "🔐 Secure connection request sent" else message
        val pendingFileHash = pendingZfile?.hash
        val pendingFileZfileContent = pendingZfile?.let { message }
        val pendingFileBlurhash = pendingZfile?.blurhash?.takeIf { it.isNotEmpty() }

        // If a send is in progress, queue the message — show it as pending immediately
        if (_sendMessageState.value is SendMessageState.Sending) {
            val pendingId = "pending_${System.nanoTime()}"
            val pendingChatMessage = ChatMessage(
                id = pendingId,
                txId = null,
                text = pendingDisplayText,
                timestamp = Instant.now(),
                isOutgoing = true,
                peerAddress = peerAddress,
                isPending = true,
                status = MessageStatus.SENDING,
                fileHash = pendingFileHash,
                fileZfileContent = pendingFileZfileContent,
                fileBlurhash = pendingFileBlurhash,
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
            return true
        }

        // User has acknowledged, proceed with sending
        doSendMessage(peerAddress, message, amountZatoshi)
        return true
    }

    /**
     * Re-send a previously FAILED outgoing message (Bug 8b retry affordance).
     *
     * Looks up the failed bubble in [pendingMessages] by its id, removes the stale FAILED entry
     * (and any persisted record), then re-enqueues the original payload through the normal
     * [sendMessage] path so it goes through the same queue/retry machinery as a fresh send.
     *
     * Raw-payload recovery: file messages carry their serialized "ZFILE|…" in [ChatMessage.fileZfileContent];
     * plain text messages store the raw text in [ChatMessage.text]. ZBOOT/locked/request bubbles do not
     * retain their raw memo here, so retry is a no-op for those (returns false) rather than re-sending the
     * decoded placeholder text.
     *
     * @return true if a re-send was enqueued, false if the message was not found or not retryable.
     */
    fun retryMessage(peerAddress: String, messageId: String): Boolean {
        val failed = pendingMessages.value.firstOrNull { it.id == messageId }
            ?: return false
        if (failed.status != MessageStatus.FAILED) return false

        // Recover the raw on-chain payload (pure, unit-tested in ChatMessage). Null = not retryable.
        val rawMessage = failed.recoverRawSendPayload() ?: return false

        // Drop the stale failed bubble so the re-send creates a fresh pending entry instead of
        // colliding on id; clear any persisted trace of it as well.
        pendingMessages.update { current -> current.filterNot { it.id == messageId } }
        zchatPreferences.removePendingMessages(setOf(messageId))

        Log.d("ZCHAT_SEND", "Retrying failed message [${rawMessage.length} chars] to ${peerAddress.redactAddress()}")
        sendMessage(peerAddress, rawMessage)
        return true
    }

    companion object {
        const val AUTO_REFRESH_INTERVAL_SECONDS = 60
        // Default amount per message output (1000 zatoshi = 0.00001 ZEC)
        const val DEFAULT_MESSAGE_AMOUNT = 1000L
        // Queue retry: wait for previous tx change notes to become spendable.
        // Uses block-height observation to retry only when new blocks are scanned.
        private const val MAX_QUEUE_RETRIES = 4
        private const val QUEUE_RETRY_TIMEOUT_MS = 300_000L // 5 min absolute timeout
        // Block-aware retry for handshake legs (KEX/KEXACK/ZBOOT) that hit a TRANSIENT note lock
        // (single-note rule: the peer's inbound tx or our prior send momentarily locks the note).
        private const val MAX_HANDSHAKE_RETRIES = 4
        private const val HANDSHAKE_BLOCK_WAIT_TIMEOUT_MS = 180_000L // ~2 blocks
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
                    sendReply(params.peerAddress, params.message, params.replyToId, params.replyPreview, params.amountZatoshi)
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
            // Re-derive the ZFILE display fields here — doSendMessage may be invoked directly
            // without going through sendMessage(), so the outer scope's pendingDisplayText is
            // not always available.
            val zfile = co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
                .takeIf { co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage.isFileMessage(message) }
                ?.parse(message)
            // Render protocol memos as friendly placeholders, never the raw "ZFILE|…"/"ZBOOT|…" text.
            val displayText = zfile?.let { "📎 ${it.displayText}" }
                ?: if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(message)) "🔐 Secure connection request sent" else message
            val zfileHash = zfile?.hash
            val zfileContent = zfile?.let { message }
            val zfileBlurhash = zfile?.blurhash?.takeIf { it.isNotEmpty() }
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // OPEN-INBOX first contact: on a brand-new peer, send our KEX (it self-generates keys)
                // so the recipient learns our identity and can reply. Carries our address in the payload;
                // the recipient treats it as TOFU/UNVERIFIED (see the SECURITY MODEL note in
                // handleKEXMessage). Once per new peer; ZBOOT/KEX/KEXACK control memos are exempt; does not
                // flip E2E-encryption so this first message stays readable until the peer's KEXACK arrives.
                if (zchatPreferences.getE2EOurPublicKey(peerAddress) == null &&
                    !co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(message) &&
                    !ZMSGProtocol.isKEXMessage(message) &&
                    !ZMSGProtocol.isKEXAckMessage(message)
                ) {
                    sendKEXMessage(peerAddress, userAddress)
                }

                // Add pending message immediately for smooth UX
                // (skip if already created by the message queue)
                if (existingPendingId == null) {
                    val pendingChatMessage = ChatMessage(
                        id = pendingId,
                        txId = null, // No tx yet for pending messages
                        text = displayText,
                        timestamp = Instant.now(),
                        isOutgoing = true,
                        peerAddress = peerAddress,
                        isPending = true,
                        status = MessageStatus.SENDING,
                        fileHash = zfileHash,
                        fileZfileContent = zfileContent,
                        fileBlurhash = zfileBlurhash,
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
                //
                // SECURITY: if encryption fails, ABORT the send rather than falling
                // back to plaintext. Silent plaintext fallback is a confidentiality
                // failure — the user expects E2E and doesn't know it was bypassed.
                val processor = getOrCreateMessageProcessor(peerAddress, convId)
                val outgoingMessage = if (processor != null) {
                    processor.encryptOutgoing(message)
                    // throws on failure — caught by the outer try/catch, shows error to user
                } else {
                    message // No E2E for this peer — send plaintext (expected)
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
                // The proposal use case throws this specific message when the ONLY reason the spend
                // is blocked is unconfirmed change from OUR previous message — not a real shortfall.
                // That is auto-retryable, so queue it and retry on the next block even on a fresh
                // (not-yet-queued) direct send, instead of dead-ending the user with an error toast.
                val isPendingPreviousTx = e is InsufficientFundsException &&
                    e.message?.contains("previous message", ignoreCase = true) == true

                // Re-queue (or first-queue) and retry after the next block when the failure is just
                // notes locked by a previous tx; show a hard error only for a genuine shortfall.
                if (isInsufficientBalance && (isQueuedMessage || isPendingPreviousTx)) {
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
                                Log.e("ZCHAT_SEND", "Queue retry timeout (${QUEUE_RETRY_TIMEOUT_MS}ms). Marking message $pendingId as failed.")
                                // Remove THIS waiter's own message by pendingId — not whatever is at
                                // index 0. With BUG-8a queuing fresh sends, multiple pending-blocked
                                // sends can have concurrent waiters; a blind removeAt(0) could fail a
                                // different (possibly in-flight, about-to-succeed) message's bubble.
                                synchronized(messageQueue) {
                                    messageQueue.removeAll { it.pendingId == pendingId }
                                }
                                pendingMessages.update { current ->
                                    current.map { msg ->
                                        if (msg.id == pendingId) msg.copy(status = MessageStatus.FAILED, isPending = false, timestamp = java.time.Instant.now()) else msg
                                    }
                                }
                                zchatPreferences.removePendingMessages(setOf(pendingId))
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
        // SECURITY (TOFU / MITM): don't transfer ZEC to a peer whose identity key changed until the
        // user re-verifies — the peer identity itself is in question after a key-change signal.
        if (blockedByKeyChange(peerAddress)) return
        if (_sendMessageState.value is SendMessageState.Sending) return

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                // Convert ZEC to zatoshi via BigDecimal (DECIMAL128) — NOT Double*1e8.toLong(), which
                // loses precision so the amount sent on-chain can differ from what the UI showed. Uses
                // the SDK's canonical converter (same as the main send screen). valueOf() takes the
                // Double's clean decimal string; an invalid amount throws and is caught by the try.
                val amountZatoshi = java.math.BigDecimal.valueOf(amountZec).convertZecToZatoshi()

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
        if (blockedByKeyChange(peerAddress)) return // TOFU/MITM gate, like the other send entrypoints
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

                // TUNNEL/OPEN: a payment REQUEST is a message — it must NEVER create an on-chain tx
                // (charge ZEC) in a conversation the UI advertises as free. Publish it over NOSTR when
                // ready; if not ready, mark it failed (the user can resend once connected) rather than
                // silently charging. Only VAULT, whose transport IS the chain, sends it on-chain.
                val prMode = zchatPreferences.getConversationMode(peerAddress)
                if (prMode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
                    val peerPub = zchatPreferences.getPeerNostrPubkey(peerAddress)
                    val acks = if (peerPub != null && co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()) {
                        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(requestMemo, peerPub) }.getOrNull()?.acks ?: 0
                    } else {
                        0
                    }
                    pendingMessages.update { list ->
                        list.map { m ->
                            if (m.id == pendingId) {
                                m.copy(isPending = false, status = if (acks > 0) MessageStatus.SENT else MessageStatus.FAILED)
                            } else {
                                m
                            }
                        }
                    }
                    zchatPreferences.removePendingMessages(setOf(pendingId))
                    _sendMessageState.value = SendMessageState.Success
                    return@launch
                }

                // Send with minimal amount (just to deliver the request) — VAULT only (on-chain)
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
    fun sendReply(
        peerAddress: String,
        message: String,
        replyToId: String,
        replyPreviewArg: String = "",
        amountZatoshi: Long = DEFAULT_MESSAGE_AMOUNT
    ): Boolean {
        // SECURITY (TOFU / MITM): a reply to a key-changed peer must NOT be silently encrypted and
        // sent to the substituted key — gate it like sendMessage. Returns false so the input is kept.
        if (blockedByKeyChange(peerAddress)) return false
        // Rejected before queue while another send is in flight: keep the user's typed reply
        // instead of dropping it silently (B1-msg-lost-on-blocked-send).
        if (_sendMessageState.value is SendMessageState.Sending) return false

        // Check if user has acknowledged that messages cost ZEC
        if (!zchatPreferences.hasAcknowledgedMessageCost()) {
            pendingMessage = PendingMessageParams(peerAddress, message, replyToId = replyToId, replyPreview = replyPreviewArg, amountZatoshi = amountZatoshi)
            _showCostDisclaimer.value = true
            // Deferred (re-sent on acknowledge): treat as accepted so the input clears.
            return true
        }

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            val pendingId = "pending_${System.nanoTime()}"
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Prefer the preview the UI captured from the actually-tapped message — it is the
                // authoritative source and never suffers id-space drift (pending→confirmed id changes,
                // NOSTR per-device ids). Only fall back to a local id lookup when the UI didn't supply one
                // (e.g. the deferred cost-disclaimer re-send path). displayText (not raw text) keeps a
                // locked message's plaintext out of the preview (C3-locked-reply-preview-leak). This empty
                // preview was the root cause of replies rendering with no quote (R1-reply-quote-not-showing).
                val replyPreview = replyPreviewArg.ifBlank { findMessageById(replyToId)?.displayText?.take(50) ?: "" }

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

                // Persist pending message so it survives navigation. Carry the reply linkage so a
                // restart-while-pending still shows the quote (R1-reply-quote-not-showing).
                zchatPreferences.addPendingMessage(
                    ZchatPreferences.PendingMessageData(
                        id = pendingId,
                        text = message,
                        timestampMillis = pendingChatMessage.timestamp.toEpochMilli(),
                        peerAddress = peerAddress,
                        replyToId = replyToId,
                        replyToPreview = replyPreview,
                        // Persist outgoing/pending/status so a restart-while-sending restores the bubble
                        // in the correct state (was defaulting to a received/SENT row) — persistence audit.
                        isOutgoing = true,
                        isPending = true,
                        status = MessageStatus.SENDING.name
                    )
                )

                // ZMSG v4: Use conversation IDs for reliable threading.
                // getOrCreateConversationId is atomic at the SharedPreferences level.
                val (convId, isFirstMessage) = convIdMutex.withLock {
                    zchatPreferences.getOrCreateConversationId(peerAddress)
                }

                // Embed the quoted-id AND a short preview INSIDE the body so the linkage survives E2E
                // and rides the v4 envelope (convId preserved → ratchet still resolves on the receiver).
                // The preview lets a NOSTR receiver render the quote even though it can't resolve the
                // sender's local id (different per device). The receive paths (untagReply) strip the
                // marker and thread the reply. replyPreview was computed above from the quoted displayText.
                val taggedReply = tagReply(replyToId, message, replyPreview)

                // TUNNEL/OPEN: route the reply over NOSTR like normal text instead of forcing it
                // on-chain (which costs ZEC and leaks a tx). handleNostrRouteIfApplicable publishes the
                // ref-tagged body + renders its OWN local bubble (reply-ref stripped, replyToId set), so
                // drop the optimistic on-chain pending bubble to avoid a duplicate, and skip the on-chain
                // path. Falls through (returns false) for VAULT / not-yet-bootstrapped peers.
                if (handleNostrRouteIfApplicable(peerAddress, taggedReply)) {
                    pendingMessages.update { current -> current.filterNot { it.id == pendingId } }
                    zchatPreferences.removePendingMessages(setOf(pendingId))
                    _sendMessageState.value = SendMessageState.Success
                    return@launch
                }

                // Encrypt the reply body (now ref-tagged) with the E2E ratchet exactly like
                // doSendMessage — without this, replies were sent in PLAINTEXT on-chain even in an
                // E2E conversation.
                val replyProcessor = getOrCreateMessageProcessor(peerAddress, convId)
                val outgoingReply = if (replyProcessor != null) replyProcessor.encryptOutgoing(taggedReply) else taggedReply

                // Use the chunked message proposal use case with v4 format
                createChunkedMessageProposal(
                    destinationAddress = peerAddress,
                    senderAddress = userAddress,
                    message = outgoingReply,
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
        // Accepted: a pending bubble was created inside the launch and the reply is in flight.
        return true
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
        // SECURITY (TOFU / MITM): don't encrypt+send a reaction (which leaks message-targeting
        // metadata + activity) to a peer whose identity key changed. Surfaces the same blocking error.
        if (blockedByKeyChange(peerAddress)) return
        if (_sendMessageState.value is SendMessageState.Sending) return

        viewModelScope.launch {
            _sendMessageState.value = SendMessageState.Sending
            try {
                val userAddress = _currentUserAddress.value
                    ?: throw IllegalStateException("User address not available")

                // Create reaction memo
                val reactionMemo = ZMSGProtocol.createReaction(messageId, emoji, userAddress)

                // TUNNEL/OPEN: publish the ZREACT over NOSTR like normal text instead of forcing it
                // on-chain (which costs ZEC and leaks a tx). Mirrors handleNostrRouteIfApplicable's gate:
                // non-VAULT + outbound ready + peer pubkey known. Unlike a text send there is NO on-chain
                // tx to reconcile, so attach the reaction OPTIMISTICALLY to the target row (by
                // ChatMessage.id == messageId) here — the inbound peer attaches the mirror on their side.
                val mode = zchatPreferences.getConversationMode(peerAddress)
                val peerPub = zchatPreferences.getPeerNostrPubkey(peerAddress)
                val viaNostr = mode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT &&
                    peerPub != null &&
                    co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()
                if (viaNostr) {
                    val acks = runCatching {
                        co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(reactionMemo, peerPub)
                    }.getOrNull()?.acks ?: 0
                    if (acks > 0) {
                        val reaction = co.electriccoin.zcash.ui.screen.chat.model.MessageReaction(
                            emoji = emoji,
                            senderAddress = userAddress,
                            timestamp = Instant.now(),
                        )
                        pendingMessages.update { current ->
                            current.map { m ->
                                if (m.id == messageId) m.copy(reactions = m.reactions + reaction) else m
                            }
                        }
                        // #210: persist our own reaction too, so it survives a reload (else our optimistic
                        // attach is wiped when pendingMessages is reloaded from storage).
                        zchatPreferences.addNostrReaction(
                            messageId, emoji, userAddress, reaction.timestamp.toEpochMilli(),
                        )
                    }
                    _sendMessageState.value = if (acks > 0) SendMessageState.Success else SendMessageState.Error("Failed to send reaction")
                    return@launch
                }

                // TUNNEL must NEVER charge a reaction on-chain. If NOSTR couldn't carry it (peer key
                // not yet known — rare for a reaction, since the target message arrived over NOSTR),
                // drop it silently rather than billing a tx. OPEN intentionally keeps the on-chain
                // fallback below for interop.
                if (mode == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL) {
                    Log.d("ZCHAT_NOSTR", "TUNNEL reaction not sent on-chain (peer NOSTR key unknown) — dropped, no charge")
                    _sendMessageState.value = SendMessageState.Success
                    return@launch
                }

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

                // SECURITY (TOFU / MITM): never auto-confirm read/online status to a key-changed
                // (possibly attacker-substituted) peer. Silently skip — this is a background
                // auto-send, not a user action, so no error is surfaced.
                if (zchatPreferences.isE2EKeyChanged(peerAddress)) return@launch

                // Create read receipt memo
                val receiptMemo = ZMSGProtocol.createReadReceipt(messageId, userAddress)

                // TUNNEL/OPEN: a read receipt is a background metadata signal — it must NEVER
                // create an on-chain tx (which costs ZEC) in a conversation the UI advertises as
                // free. Publish the ZRCPT over NOSTR when possible; if NOSTR isn't ready, SKIP it
                // (receipts are non-critical) rather than falling through to a shielded send.
                // Only VAULT, whose transport IS the chain, continues to createChunkedMessageProposal.
                val mode = zchatPreferences.getConversationMode(peerAddress)
                if (mode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
                    val peerPub = zchatPreferences.getPeerNostrPubkey(peerAddress)
                    if (peerPub != null && co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()) {
                        runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(receiptMemo, peerPub) }
                    }
                    // NOSTR not ready (no peer pubkey / inbox down) → drop the receipt silently.
                    // Never charge ZEC for a read receipt in a free conversation.
                    return@launch
                }

                // Send minimal amount with receipt memo (VAULT only)
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
        // Search pending messages first: a user can reply to a message they JUST sent (still SENDING),
        // which lives in pendingMessages and is NOT yet in _chatListState. Missing this was why reply
        // previews came back empty for recently-sent / NOSTR messages (R1-reply-quote-not-showing).
        pendingMessages.value.firstOrNull { it.id == messageId }?.let { return it }

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
     * Mark a view-once file as consumed and wipe its local cache. Idempotent — if the
     * hash is already viewed or the file doesn't exist this is a no-op. Triggers a
     * conversation refresh so the bubble collapses to a "Viewed" placeholder.
     */
    fun markFileViewed(fileHash: String, context: android.content.Context) {
        if (zchatPreferences.isFileViewed(fileHash)) return
        zchatPreferences.markFileViewed(fileHash)
        // Flip any in-memory pending rows (NOSTR in/out, local) for this file. On-chain rows
        // recompute fileViewed from prefs on the loadConversations() below, but pending rows are
        // rendered as-is — without this a view-once image over NOSTR never collapsed to the
        // "Viewed" placeholder until app restart.
        pendingMessages.update { list ->
            list.map { m -> if (m.fileHash == fileHash) m.copy(fileViewed = true) else m }
        }
        val cacheFile = java.io.File(context.cacheDir, "zchat_files/$fileHash")
        // #212: the secure wipe (RandomAccessFile overwrite + fsync) and delete are disk I/O. This is
        // invoked from a Compose coroutine on the main thread (ViewOnceRevealBubble), so do the wipe on
        // Dispatchers.IO to avoid a StrictMode DiskWriteViolation / frame jank on reveal. The "viewed"
        // state was already committed synchronously above (markFileViewed pref + pendingMessages flip),
        // so the bubble collapses regardless of when the wipe finishes.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
            if (cacheFile.exists()) {
                // Best-effort secure wipe — overwrite once with random bytes before unlinking.
                // ext4 / F2FS don't guarantee in-place rewrite, but on devices where the page
                // mapping is stable this raises the bar against forensic recovery.
                runCatching {
                    java.io.RandomAccessFile(cacheFile, "rw").use { raf ->
                        val len = raf.length()
                        if (len > 0) {
                            val buf = ByteArray(8192)
                            java.security.SecureRandom().nextBytes(buf)
                            var written = 0L
                            raf.seek(0)
                            while (written < len) {
                                val toWrite = minOf(buf.size.toLong(), len - written).toInt()
                                raf.write(buf, 0, toWrite)
                                written += toWrite
                            }
                            raf.fd.sync()
                        }
                    }
                }
                cacheFile.delete()
            }
            }.onFailure { Log.w("ZCHAT_FILE", "View-once wipe failed for $fileHash: ${it.message}") }
        }
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
                    // TUNNEL/OPEN: a status update is a non-critical metadata signal — it must NEVER
                    // create an on-chain tx (which costs ZEC) per-contact in a conversation the UI
                    // advertises as free. Publish the status over NOSTR when possible; if NOSTR isn't
                    // ready, SKIP this contact rather than falling through to a shielded send.
                    // Only VAULT, whose transport IS the chain, continues to createChunkedMessageProposal.
                    val mode = zchatPreferences.getConversationMode(peerAddress)
                    if (mode != co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT) {
                        val peerPub = zchatPreferences.getPeerNostrPubkey(peerAddress)
                        if (peerPub != null && co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()) {
                            runCatching { co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(statusMemo, peerPub) }
                        }
                        // NOSTR not ready → drop the status for this peer. Never charge ZEC for a
                        // status broadcast in a free conversation.
                        continue
                    }
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
        if (blockedByKeyChange(peerAddress)) return // TOFU/MITM gate, consistent with the other senders
        if (blockOnChainFeatureIfNotVault(peerAddress, "Scheduled messages")) return
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
        if (blockedByKeyChange(peerAddress)) return // TOFU/MITM gate
        if (blockOnChainFeatureIfNotVault(peerAddress, "Block-locked messages")) return
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
        // SECURITY (TOFU / MITM): gate this message-send like the others on a changed peer key.
        if (blockedByKeyChange(peerAddress)) return
        if (blockOnChainFeatureIfNotVault(peerAddress, "Payment-locked messages")) return
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
        if (blockedByKeyChange(peerAddress)) return // TOFU/MITM gate
        if (blockOnChainFeatureIfNotVault(peerAddress, "Conditional messages")) return
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
        if (blockedByKeyChange(senderAddress)) return // TOFU/MITM gate — don't pay a substituted key
        if (blockOnChainFeatureIfNotVault(senderAddress, "Unlocking payment-locked messages")) return
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
        // TOFU/MITM gate — don't send the unlock answer to a substituted key.
        if (blockedByKeyChange(senderAddress)) return false
        // MONEY-SAFETY: conditional unlock sends an on-chain memo. Block it in TUNNEL/OPEN ("free") chats.
        if (blockOnChainFeatureIfNotVault(senderAddress, "Unlocking conditional messages")) return false

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
            val groupInfoJson = zchatPreferences.getGroupInfo(groupId) ?: return@mapNotNull null
            // Use the CANONICAL deserializer (snake_case schema, epoch-MILLIS) like every other group
            // load/save site. The previous inline parse here used camelCase keys ("groupId",
            // "creatorAddress", …) and epoch-SECONDS, which NEVER matched serializeGroupInfo's output
            // ("group_id"/"creator"/epoch-millis) → "No value for groupId" JSONException on every group
            // load (confirmed on-device). deserializeGroupInfo already logs + returns null on failure.
            ZMSGGroupProtocol.deserializeGroupInfo(groupInfoJson)
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
            // GROUP_KICK / GROUP_KEY mutate the roster / group key, so they're acted on ONLY through the
            // #187 signed-auth gate: the payload now carries the admin's signature, and processGroupKick/
            // processGroupKey AUTHENTICATE it against the signer's KEX'd E2E key AND AUTHORIZE that the
            // signer is the group's admin before mutating anything (+ an epoch-monotonicity replay guard).
            // An unsigned or unverifiable one is dropped — restoring the old "don't act on a forgeable
            // kick/key" safety while now letting a genuine admin action through.
            GroupMessageType.GROUP_KICK -> {
                processGroupKick(groupId, payload, txId)
            }
            GroupMessageType.GROUP_KEY -> {
                processGroupKey(groupId, payload, txId)
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
            // Honor the invite's key epoch: if the group already rotated keys, saving the invited key at
            // epoch 0 would leave the invitee unable to decrypt any post-rotation message.
            val keyEpoch = json.optInt("key_epoch", 0)

            // Parse member list. Compact invites (#194) omit the roster; tolerate its absence and
            // synthesize a minimal one below (inviter + self), letting peers fill in lazily.
            val membersArray = json.optJSONArray("members")
            val memberAddresses =
                membersArray?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

            Log.d("ZCHAT_GROUP", "Received GROUP_INVITE for '$groupName' from ${inviterAddress.redactAddress()}")

            // Check if we already have this group
            if (zchatPreferences.getGroupInfo(groupId) != null) {
                Log.d("ZCHAT_GROUP", "Group $groupId already exists, skipping")
                return
            }

            // Get our address to find our private key. Guard against a null address (invite arriving
            // before _currentUserAddress is initialized): without it we'd mark ourselves INVITED
            // instead of ACTIVE below AND skip sending GROUP_ACCEPT, silently joining a group we can
            // neither send to nor receive from. The invite tx is on-chain and re-scanned once the
            // address loads, so returning here defers processing rather than dropping it.
            val userAddress = _currentUserAddress.value ?: run {
                Log.w("ZCHAT_GROUP", "Cannot process GROUP_INVITE for $groupId without our address yet — will retry on next sync")
                return
            }

            // Try to get the group key - session-wrapped (compact), ECIES (legacy), or plaintext.
            var groupKeyBase64: String? = null

            if (json.has("k2")) {
                // Compact invite (#194): group key wrapped under the authenticated KEX session shared
                // with the inviter (symmetric with the creator's deriveSessionKey). Requires a prior
                // completed KEX — which also means we already hold the inviter's authentic E2E key, so
                // no unsigned key bootstrapping happens here.
                val k2 = json.getString("k2")
                var decoded = getE2ESharedKey(inviterAddress)?.let { E2EEncryption.decrypt(k2, it) }
                if (decoded == null) {
                    // #197 FALLBACK: the inviter may have embedded a DIFFERENT valid representation of
                    // its own unified address than the one WE stored its KEX session under — the SAME
                    // self-address-representation drift that broke KEXACK verification (diversifier /
                    // derivation drift across reinstalls/SDK upgrades). The session key is bound to the
                    // E2E KEY, not the address STRING, so try every peer we hold a completed KEX with and
                    // keep the one whose session key actually decrypts k2 — that peer IS the inviter.
                    // Without this, a perfectly valid compact invite yields "created without key —
                    // messages won't decrypt" whenever the inviter's address representation differs (the
                    // exact on-device failure seen for the Honor↔Seeker group). This is safe: only a peer
                    // we already KEX-authenticated can produce a session key that decrypts k2.
                    val match = zchatPreferences.getAllPeerToConvIdMappings().keys.asSequence()
                        .filter { it != inviterAddress && it.startsWith("u1") }
                        .mapNotNull { peer -> getE2ESharedKey(peer)?.let { sk -> E2EEncryption.decrypt(k2, sk) } }
                        .firstOrNull()
                    if (match != null) {
                        decoded = match
                        Log.d("ZCHAT_GROUP", "Compact invite k2 decrypted via a KEX peer (inviter's embedded address representation differed from stored)")
                    }
                }
                if (decoded != null) {
                    groupKeyBase64 = decoded
                    Log.d("ZCHAT_GROUP", "Decrypted session-wrapped group key (compact invite)")
                } else {
                    Log.e("ZCHAT_GROUP", "Failed to decrypt compact invite key (k2) — no KEX session matched; need KEX with inviter first")
                }
            } else if (json.has("enc_key")) {
                // ECIES encrypted group key - decrypt with our private key
                val encryptedKey = json.getString("enc_key")
                val ourPrivateKey = zchatPreferences.getE2EPrivateKey(inviterAddress)

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

            // Store inviter's public key if provided (for future ECIES). A group invite is an
            // UNSIGNED, attacker-influenceable path, so it must never mutate trust state for an
            // already-established peer: only store on genuine first contact. If it carries a
            // DIFFERENT key for an existing peer, ignore it — do NOT overwrite, and do NOT flag
            // key-changed / clear verification (either would be a remote MITM or a verification-
            // stripping + banner-spam griefing primitive driven by unauthenticated input).
            // Legitimate key rotation arrives via the signed KEX path, which detects changes itself.
            if (json.has("inviter_pub")) {
                val inviterPubKey = json.getString("inviter_pub")
                val existingPub = zchatPreferences.getE2EPeerPublicKey(inviterAddress)
                when {
                    existingPub == null -> {
                        zchatPreferences.setE2EPeerPublicKey(inviterAddress, inviterPubKey)
                        Log.d("ZCHAT_GROUP", "Stored inviter's E2E public key (first contact)")
                    }
                    existingPub != inviterPubKey ->
                        Log.w("ZCHAT_GROUP", "Group invite carries a DIFFERENT E2E key for ${inviterAddress.redactAddress()} — ignored (unsigned path cannot change an established key)")
                    else -> Unit // same key re-sent — no-op
                }
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

            // Create member list. Compact invites carry no roster, so seed it with the inviter
            // (admin) + ourselves, both ACTIVE; remaining peers are learned lazily as they post
            // (addOrActivateGroupMember on each inbound GROUP_MSG). With a roster (legacy invite),
            // use it directly: self ACTIVE, others INVITED until they accept.
            val members = if (memberAddresses.isEmpty()) {
                listOf(
                    GroupMember(
                        address = inviterAddress,
                        publicKey = null,
                        joinedAt = timestamp ?: Instant.now(),
                        status = MemberStatus.ACTIVE,
                        isAdmin = true,
                        nickname = contactBook.getContact(inviterAddress)?.name
                    ),
                    GroupMember(
                        address = userAddress,
                        publicKey = null,
                        joinedAt = timestamp ?: Instant.now(),
                        status = MemberStatus.ACTIVE,
                        isAdmin = false,
                        nickname = null
                    )
                ).distinctBy { it.address }
            } else {
                memberAddresses.map { address ->
                    GroupMember(
                        address = address,
                        publicKey = null,
                        joinedAt = timestamp ?: Instant.now(),
                        status = if (address == userAddress) MemberStatus.ACTIVE else MemberStatus.INVITED,
                        isAdmin = address == inviterAddress,
                        nickname = contactBook.getContact(address)?.name
                    )
                }
            }

            // Save group info and members
            zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(groupInfo))
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(members))

            // Save group key if we got one — at the invite's epoch, not hardcoded 0.
            if (groupKeyBase64 != null) {
                zchatPreferences.saveGroupKey(groupId, keyEpoch, groupKeyBase64)
                zchatPreferences.setGroupKeyEpoch(groupId, keyEpoch)
            } else {
                Log.w("ZCHAT_GROUP", "Group $groupId created without key - messages won't decrypt")
            }

            Log.d("ZCHAT_GROUP", "Group $groupId created from invite")

            // Send GROUP_ACCEPT back to the inviter so they flip our status INVITED -> ACTIVE on
            // their side. Senders only transmit to ACTIVE members (GroupViewModel filters on it), so
            // without this acknowledgement we silently never receive any group messages. Only ack if
            // we genuinely joined (have the group key) and know our own address. accepter_pub is our
            // E2E pubkey with the inviter when available; empty is tolerated downstream (legacy / no
            // prior KEX). See B3-group-accept-never-sent. createGroupAcceptMessage was defined but
            // had zero callers.
            val accepterAddress = userAddress
            // userAddress is non-null here (guarded at the top of processGroupInvite); only the group
            // key gating remains — we must actually hold the key before announcing acceptance.
            if (groupKeyBase64 != null) {
                val accepterPublicKey = zchatPreferences.getE2EOurPublicKey(inviterAddress) ?: ""
                val acceptMemo = ZMSGGroupProtocol.createGroupAcceptMessage(
                    groupId = groupId,
                    accepterAddress = accepterAddress,
                    accepterPublicKey = accepterPublicKey,
                )
                viewModelScope.launch {
                    // #213: send via the block-aware retry path (same fix as the KEX/ZBOOT #208 and the
                    // group-invite #199). A freshly-synced or single-note wallet frequently has its only
                    // note still maturing when the invite is processed, so a one-shot send fails with
                    // "Insufficient balance (have 0)" and the inviter never flips us INVITED -> ACTIVE —
                    // meaning we silently receive NO group messages. Retrying across blocks lets the
                    // GROUP_ACCEPT land once the change matures. (Lazy-roster #194 is the secondary
                    // recovery: our first outgoing group message also makes the inviter learn us.)
                    Log.d("ZCHAT_GROUP", "Sending GROUP_ACCEPT for $groupId to ${inviterAddress.redactAddress()}")
                    if (!sendHandshakeMemoWithRetry(inviterAddress, accepterAddress, acceptMemo)) {
                        Log.e("ZCHAT_GROUP", "GROUP_ACCEPT send failed after retries for $groupId")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_INVITE", e)
        }
    }

    /**
     * Lazily learn a group peer. Compact invites (#194) don't ship the full roster, so each member
     * discovers the others as they post. On a GROUP_MSG from [address], ensure it's in the local
     * roster as ACTIVE so future fan-out (GroupViewModel filters to ACTIVE) reaches them. Idempotent:
     * no write when the member is already present and ACTIVE.
     */
    private fun addOrActivateGroupMember(groupId: String, rawAddress: String) {
        try {
            // Canonicalize across the sender's UA representations (#205/#214). A peer posts under an
            // address rep that may differ from the one we invited/activated them as; without this we'd
            // insert a DUPLICATE roster member for the same person → inflated member count + fan-out
            // double-sends (every message delivered twice). The alias is learned only from a verified
            // GROUP_ACCEPT E2E-key match (see processGroupAccept), so it never merges distinct peers.
            val address = zchatPreferences.resolvePeerAddress(rawAddress)
            val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return
            val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
            val existing = members.find { it.address == address }
            val updated =
                when {
                    existing == null ->
                        members +
                            GroupMember(
                                address = address,
                                publicKey = null,
                                joinedAt = Instant.now(),
                                status = MemberStatus.ACTIVE,
                                isAdmin = false,
                                nickname = contactBook.getContact(address)?.name
                            )
                    existing.status != MemberStatus.ACTIVE ->
                        members.map { if (it.address == address) it.copy(status = MemberStatus.ACTIVE) else it }
                    else -> return // already present and ACTIVE — nothing to persist
                }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
            Log.d("ZCHAT_GROUP", "Roster: learned/activated ${address.redactAddress()} in $groupId")
        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to update roster for $groupId", e)
        }
    }

    /**
     * Process a GROUP_MSG message.
     * Decrypts and stores the message.
     */
    private fun processGroupMsg(groupId: String, payload: String, txId: TransactionId?, timestamp: Instant?) {
        try {
            // Parse the encrypted payload FIRST: a GROUP_MSG carries its OWN key epoch, and a message
            // encrypted at epoch N must be decrypted with the epoch-N key — NOT the device's current
            // epoch. After a key rotation (kick / GROUP_KEY) the current epoch advances, so decrypting
            // a historical message — or one from a peer who hasn't yet adopted the rotation — with the
            // current key yields AEADBadTagException → a permanent "[Unable to decrypt]". The previous
            // code looked the key up under getGroupKeyEpoch() (current) unconditionally, so any
            // cross-epoch message failed silently. Select by the message's epoch, then fall back across
            // every epoch we hold so a single stale/ahead key never blocks decryption.
            val parsedMsg = ZMSGGroupProtocol.parseGroupMsgPayload(payload)
            if (parsedMsg == null) {
                Log.w("ZCHAT_GROUP", "Failed to parse GROUP_MSG payload")
                return
            }

            val currentEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
            // Priority order: the message's own epoch, the device's current epoch, then every epoch
            // 0..currentEpoch (rotations we've adopted). Deduped, order-preserving — distinct() keeps
            // first occurrence so the most-likely key is tried first.
            val candidateEpochs = (listOf(parsedMsg.epoch, currentEpoch) + (0..currentEpoch)).distinct()

            var decrypted: String? = null
            var triedAnyKey = false
            for (epoch in candidateEpochs) {
                val encodedKey = zchatPreferences.getGroupKey(groupId, epoch) ?: continue
                triedAnyKey = true
                val groupKey = ZMSGGroupProtocol.decodeGroupKey(encodedKey)
                decrypted = ZMSGGroupProtocol.decryptMessage(parsedMsg.nonce, parsedMsg.ciphertext, groupKey)
                if (decrypted != null) break
            }

            if (!triedAnyKey) {
                Log.w("ZCHAT_GROUP", "No group key for $groupId - cannot decrypt message")
                return
            }

            if (decrypted == null) {
                // We hold key(s) but none match — the recoverable "[Unable to decrypt]" state (#197):
                // the "Tap to sync group keys" UI re-requests the current key from the admin.
                Log.w("ZCHAT_GROUP", "Failed to decrypt GROUP_MSG for $groupId (msg epoch ${parsedMsg.epoch}, tried $candidateEpochs)")
                return
            }

            Log.d("ZCHAT_GROUP", "Decrypted GROUP_MSG from ${parsedMsg.sender.redactAddress()} [${decrypted.length} chars]")

            // Lazy roster (#194): compact invites don't ship the full member list, so learn the
            // sender here and mark them ACTIVE — future fan-out (GroupViewModel filters to ACTIVE)
            // then reaches them. Skip ourselves — hash-tolerant self-check (#205) so a drifted
            // representation of our own address isn't mistakenly added to the roster.
            if (!zchatPreferences.isSelfAddress(parsedMsg.sender)) {
                addOrActivateGroupMember(groupId, parsedMsg.sender)
            }

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
                put("groupId", message.groupId)
                put("txId", txIdString)
                put("seq", message.seq)
                put("epoch", message.epoch)
                put("sender", message.senderAddress)
                // Key MUST be "decrypted" (not "content") and timestamp MUST be epoch-MILLIS to match
                // the canonical reader GroupViewModel.parseStoredGroupMessages — otherwise every inbound
                // group message is silently dropped (missing groupId throws) or shows "[Unable to
                // decrypt]" with a 1970 timestamp. See B2-group-stored-schema-dedup-seq.
                put("decrypted", message.decryptedContent)
                put("timestamp", message.timestamp.toEpochMilli())
            }
            val updatedMsgs = if (existingMsgs != null) {
                val arr = org.json.JSONArray(existingMsgs)
                // Dedup by id: convertToConversations re-runs on every full-snapshot transaction
                // emission, so the same group tx is re-processed repeatedly. Without this guard the
                // stored array grows unbounded with exact duplicates of every message.
                val alreadyStored = (0 until arr.length()).any {
                    arr.getJSONObject(it).optString("id") == message.id
                }
                if (!alreadyStored) arr.put(msgJson)
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
            // The joiner's E2E public key in OUR shared session — a STABLE cryptographic identity that
            // does NOT drift across the joiner's UA representations the way the address string does.
            val accepterPub = json.optString("accepter_pub", "")

            Log.d("ZCHAT_GROUP", "Processing GROUP_ACCEPT from ${accepterAddress.redactAddress()}")

            // Update member status
            val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return
            val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)

            // Resolve WHICH invited member is the accepter. Matching by address STRING alone is broken:
            // a wallet emits multiple valid UA representations (#205), so a joiner frequently accepts
            // under a different rep than the one we invited them as → exact match misses → they stay
            // INVITED forever → sendGroupMessage finds 0 ACTIVE recipients and the send silently hangs
            // (#214). Match instead by the joiner's E2E identity: accepter_pub is their public key in our
            // shared session, which we ALREADY hold (the KEX that let us session-wrap their invite) under
            // whatever address we invited them as. So getE2EPeerPublicKey(invitedAddress) == accepter_pub
            // identifies the right roster entry regardless of which address string the accept carried.
            // Only adopt the accepter's declared address if it's a plausible UA (defensive — the accept
            // is an unsigned control message).
            val accepterAddrValid = accepterAddress.startsWith("u1") && accepterAddress.length > 20
            var matchedOldAddress: String? = null
            var adopted = false
            val updated = members.map { member ->
                val isAccepter =
                    member.address == accepterAddress ||
                        (accepterPub.isNotEmpty() &&
                            zchatPreferences.getE2EPeerPublicKey(member.address) == accepterPub)
                if (isAccepter) {
                    matchedOldAddress = member.address
                    // ADOPT the accepter's DECLARED current receive address. The rep we invited them as
                    // (from the group ContactBook) can be a STALE/different UA representation that this
                    // peer's wallet no longer scans for incoming notes — so an on-chain group message
                    // fanned out to it dead-ends and is NEVER received (confirmed on-device: Seeker
                    // receives at u10k8u… but was invited as u1rlaezl…, so its synced wallet detected
                    // ZERO of Honor's confirmed group txs). The accept declares the address the joiner
                    // actually receives at; switching the roster to it makes fan-out reach them. Safe-ish:
                    // gated on the E2E-key match (accepter_pub == the key we hold for this invited member)
                    // — an attacker would need the victim's E2E pubkey, and group-key encryption still
                    // protects content. A SIGNED accept (#187-style) would fully authenticate this.
                    val newAddr = if (accepterAddrValid && member.address != accepterAddress) {
                        adopted = true
                        accepterAddress
                    } else {
                        member.address
                    }
                    member.copy(address = newAddr, status = MemberStatus.ACTIVE)
                } else {
                    member
                }
            }

            val matchedOld = matchedOldAddress
            if (matchedOld == null) {
                Log.w(
                    "ZCHAT_GROUP",
                    "GROUP_ACCEPT from ${accepterAddress.redactAddress()} matched no invited member " +
                        "(have_pub=${accepterPub.isNotEmpty()}) — roster unchanged"
                )
            } else if (adopted) {
                // Map the stale invited rep → the live receive rep so any lingering reference (stored
                // messages, a peer who posts under the old rep) canonicalizes forward to the address we
                // now fan out to, rather than inserting a duplicate roster member.
                zchatPreferences.setPeerAddressAlias(matchedOld, accepterAddress)
                Log.d(
                    "ZCHAT_GROUP",
                    "GROUP_ACCEPT: activated + adopted live receive address ${accepterAddress.redactAddress()} " +
                        "for invited rep ${matchedOld.redactAddress()} (E2E-key match)"
                )
            } else {
                Log.d("ZCHAT_GROUP", "GROUP_ACCEPT: activated ${matchedOld.redactAddress()}")
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

    /**
     * #187 — return the group's admin address IF [signerAddress] both authenticates (their #187
     * signature over [signedData] verifies against their KEX'd E2E pubkey) AND is authorized (they are
     * the group's creator/admin). Any failure → null, and the control message MUST be ignored. This is
     * the single gate that makes GROUP_KICK / GROUP_KEY safe to act on; without it they were forgeable.
     */
    private fun verifyGroupAdminControl(
        groupId: String,
        signerAddress: String,
        signedData: String,
        signature: String?
    ): Boolean {
        if (signature.isNullOrEmpty()) {
            Log.w("ZCHAT_GROUP", "Group control msg from ${signerAddress.redactAddress()} is UNSIGNED — ignored (#187)")
            return false
        }
        // AUTHENTICATE: the signature must verify against the signer's KEX-established E2E key.
        val signerPub = zchatPreferences.getE2EPeerPublicKey(signerAddress)
        if (signerPub == null) {
            Log.w("ZCHAT_GROUP", "No E2E key for group-control signer ${signerAddress.redactAddress()} — ignored (#187)")
            return false
        }
        val authentic = runCatching { E2EEncryption.verify(signerPub, signedData, signature) }.getOrDefault(false)
        if (!authentic) {
            Log.w("ZCHAT_GROUP", "Group control signature INVALID for ${signerAddress.redactAddress()} — possible forgery, ignored (#187)")
            return false
        }
        // AUTHORIZE: the (authenticated) signer must actually be the group's admin/creator.
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
        if (info == null || info.creatorAddress != signerAddress) {
            Log.w("ZCHAT_GROUP", "Group control signer ${signerAddress.redactAddress()} is NOT the group admin — ignored (#187)")
            return false
        }
        return true
    }

    /**
     * #187 — process a SIGNED GROUP_KICK. Authenticates + authorizes via [verifyGroupAdminControl],
     * rejects stale epochs (replay), then removes the kicked member and adopts the rotated key that the
     * admin wrapped for us. If WE were kicked, the group is marked left locally.
     */
    private fun processGroupKick(groupId: String, payload: String, @Suppress("UNUSED_PARAMETER") txId: TransactionId?) {
        try {
            val kick = ZMSGGroupProtocol.parseGroupKickPayload(payload)?.copy(groupId = groupId) ?: return
            val signedData = ZMSGGroupProtocol.groupKickSignedData(
                groupId, kick.kicked, kick.kicker, kick.newEpoch, kick.encryptedGroupKey
            )
            if (!verifyGroupAdminControl(groupId, kick.kicker, signedData, kick.signature)) return
            // REPLAY GUARD: a kick only ever advances the epoch; refuse a stale/replayed one.
            if (kick.newEpoch <= zchatPreferences.getGroupKeyEpoch(groupId)) {
                Log.w("ZCHAT_GROUP", "GROUP_KICK epoch ${kick.newEpoch} not newer than current — ignored (replay?)")
                return
            }
            Log.d("ZCHAT_GROUP", "Acting on verified GROUP_KICK of ${kick.kicked.redactAddress()} by admin")
            // Remove the kicked member from the roster.
            zchatPreferences.getGroupMembers(groupId)?.let { membersJson ->
                val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
                val updated = members.map { if (it.address == kick.kicked) it.copy(status = MemberStatus.LEFT) else it }
                zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
            }
            // If we were the one kicked, we won't get the new key — mark the group left and stop.
            // Hash-tolerant self-check (#205): the kick payload carries whatever representation of
            // our address the admin had stored, which may differ from our live one.
            if (zchatPreferences.isSelfAddress(kick.kicked)) {
                Log.w("ZCHAT_GROUP", "We were kicked from $groupId — marking group left locally")
                return
            }
            adoptRotatedGroupKey(groupId, kick.kicker, kick.newEpoch, kick.encryptedGroupKey)
        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_KICK", e)
        }
    }

    /**
     * #187 — process a SIGNED GROUP_KEY rotation. Authenticates + authorizes via
     * [verifyGroupAdminControl], rejects stale epochs, then adopts the new key.
     */
    private fun processGroupKey(groupId: String, payload: String, @Suppress("UNUSED_PARAMETER") txId: TransactionId?) {
        try {
            val rot = ZMSGGroupProtocol.parseGroupKeyPayload(groupId, payload) ?: return
            val signedData = ZMSGGroupProtocol.groupKeySignedData(
                groupId, rot.signer, rot.epoch, rot.encryptedGroupKey, rot.reason
            )
            if (!verifyGroupAdminControl(groupId, rot.signer, signedData, rot.signature)) return
            if (rot.epoch <= zchatPreferences.getGroupKeyEpoch(groupId)) {
                Log.w("ZCHAT_GROUP", "GROUP_KEY epoch ${rot.epoch} not newer than current — ignored (replay?)")
                return
            }
            Log.d("ZCHAT_GROUP", "Acting on verified GROUP_KEY rotation to epoch ${rot.epoch}")
            adoptRotatedGroupKey(groupId, rot.signer, rot.epoch, rot.encryptedGroupKey)
        } catch (e: Exception) {
            Log.e("ZCHAT_GROUP", "Failed to process GROUP_KEY", e)
        }
    }

    /**
     * Decrypt the new group key the admin wrapped under our authenticated KEX session with them and
     * store it at [newEpoch], advancing the active epoch. No-op if we can't derive the session or the
     * wrapped key is absent/undecryptable (we'll fall back to the existing "sync group keys" recovery).
     */
    private fun adoptRotatedGroupKey(groupId: String, adminAddress: String, newEpoch: Int, encryptedKey: String?) {
        if (encryptedKey == null) {
            Log.w("ZCHAT_GROUP", "Rotation for $groupId carried no key for us — keeping current key")
            return
        }
        val sessionKey = getE2ESharedKey(adminAddress)
        if (sessionKey == null) {
            Log.w("ZCHAT_GROUP", "No KEX session with admin — can't adopt rotated key for $groupId")
            return
        }
        val newKeyBase64 = runCatching { E2EEncryption.decrypt(encryptedKey, sessionKey) }.getOrNull()
        if (newKeyBase64 == null) {
            Log.e("ZCHAT_GROUP", "Failed to decrypt rotated group key for $groupId")
            return
        }
        zchatPreferences.saveGroupKey(groupId, newEpoch, newKeyBase64)
        zchatPreferences.setGroupKeyEpoch(groupId, newEpoch)
        Log.d("ZCHAT_GROUP", "Adopted rotated group key for $groupId at epoch $newEpoch")
    }
}
