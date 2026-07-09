package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.update
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.common.util.redactConvId
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol

/**
 * Result of a rate-limited destroy-PIN verification.
 */
sealed class DestroyPinVerifyResult {
    /** Correct PIN. Counter is cleared. */
    data object Success : DestroyPinVerifyResult()

    /** Wrong PIN. Caller may try again immediately. */
    data class Failed(val attemptsRemaining: Int) : DestroyPinVerifyResult()

    /** Rate-limited. Caller must wait [remainingMillis] before retrying. */
    data class LockedOut(val remainingMillis: Long) : DestroyPinVerifyResult()
}

/**
 * Notification privacy levels for ZCHAT.
 * Controls how much information is shown in notifications.
 */
enum class NotificationPrivacy {
    /** Shows sender name and message content. "Alice: Hey, how are you?" */
    FULL_PREVIEW,

    /** Shows only the sender name. "New message from Alice" */
    SENDER_ONLY,

    /** Shows a generic notification. "New ZCHAT message" */
    NEW_MESSAGE,

    /** No notifications are shown. User must check app manually. */
    SILENT
}

/**
 * Interface for ZCHAT preferences.
 */
interface ZchatPreferences {
    /**
     * Check if user has acknowledged that messages cost ZEC.
     */
    fun hasAcknowledgedMessageCost(): Boolean

    /**
     * Mark that user has acknowledged message cost.
     */
    fun setAcknowledgedMessageCost()

    /**
     * Get the set of hidden message IDs.
     */
    fun getHiddenMessageIds(): Set<String>

    /**
     * Add a message ID to the hidden set.
     */
    fun hideMessage(messageId: String)

    /**
     * IDs of inbound payment REQUESTS the user has already fulfilled (paid). Durable so the "Pay"
     * affordance stays hidden across reloads — prevents accidentally paying the same request twice.
     */
    fun getPaidRequestIds(): Set<String>

    /**
     * Mark an inbound payment request (by its message id) as paid.
     */
    fun markRequestPaid(requestMessageId: String)

    /**
     * Add multiple message IDs to the hidden set.
     */
    fun hideMessages(messageIds: Set<String>)

    /**
     * Remove a message ID from the hidden set (unhide).
     */
    fun unhideMessage(messageId: String)

    // ==========================================
    // USER STATUS
    // ==========================================

    /**
     * Get the user's current status text.
     */
    fun getUserStatus(): String

    /**
     * Set the user's status text.
     */
    fun setUserStatus(status: String)

    /**
     * Get the timestamp when status was last updated.
     */
    fun getUserStatusTimestamp(): Long

    /**
     * Get status for a specific peer address.
     */
    fun getPeerStatus(peerAddress: String): String?

    /**
     * Set status for a peer (received from their messages).
     */
    fun setPeerStatus(peerAddress: String, status: String)

    /**
     * Get all stored peer statuses.
     */
    fun getAllPeerStatuses(): Map<String, String>

    // ==========================================
    // MEMO TEMPLATES
    // ==========================================

    /**
     * Get all custom memo templates as JSON strings.
     */
    fun getCustomMemoTemplates(): List<String>

    /**
     * Save a custom memo template (as JSON string).
     */
    fun saveCustomMemoTemplate(templateJson: String)

    /**
     * Remove a custom memo template by ID.
     */
    fun removeCustomMemoTemplate(templateId: String)

    /**
     * Get all custom template JSON strings.
     */
    fun getAllCustomTemplateJson(): Set<String>

    // ==========================================
    // FONT SIZE
    // ==========================================

    /**
     * Get the font size scale (1.0 = normal, 1.1 = 10% bigger, etc.)
     * Default is 1.0
     */
    fun getFontSizeScale(): Float

    /**
     * Set the font size scale.
     */
    fun setFontSizeScale(scale: Float)

    // ==========================================
    // CONTACT NICKNAMES
    // ==========================================

    /**
     * Get nickname for a contact address.
     * @return The nickname, or null if not set
     */
    fun getNickname(address: String): String?

    /**
     * Set nickname for a contact address.
     * @param address The contact's Zcash address
     * @param nickname The nickname to set (empty string to clear)
     */
    fun setNickname(address: String, nickname: String)

    /**
     * Get display name for an address (nickname if set, otherwise truncated address).
     * @return Nickname if set, otherwise first 8 + last 6 chars of address
     */
    fun getDisplayName(address: String): String

    /**
     * Get all stored nicknames.
     * @return Map of address -> nickname
     */
    fun getAllNicknames(): Map<String, String>

    // ==========================================
    // VIEW-ONCE FILE STATE
    // ==========================================

    /**
     * Mark a view-once file (image or audio) as consumed on this device. After this
     * call the renderer collapses the bubble to a "Viewed" placeholder and the cache
     * file is deleted by the caller. Idempotent.
     */
    fun markFileViewed(fileHash: String)

    /** True iff the file has already been consumed via markFileViewed. */
    fun isFileViewed(fileHash: String): Boolean

    /** Snapshot of every viewed-file hash — used by convertToConversations on rebuild. */
    fun getAllViewedFiles(): Set<String>

    // ==========================================
    // CONVERSATION MODE (Vault / Tunnel / Open)
    // ==========================================

    /** Returns the stored mode for a peer, or [ConversationMode.DEFAULT] if unset. */
    fun getConversationMode(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode

    /**
     * Returns the EXPLICITLY-stored mode for a peer, or null if the user never picked one. Unlike
     * [getConversationMode] this does NOT collapse "unset" into the VAULT default, so the new-chat
     * composer can apply its own smart default (Tunnel) for fresh peers while still respecting any
     * mode the user previously chose for an existing peer.
     */
    fun getConversationModeOrNull(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?

    /** Persist the mode for a peer. Pass null to clear (revert to DEFAULT). */
    fun setConversationMode(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?)

    /**
     * True iff the USER explicitly picked a mode for this chat (mode-picker), as opposed to an
     * auto-upgrade (responder ZBOOT) or an auto-adopt (peer ZMODE control). Gates ZMODE auto-adopt:
     * a peer's mode change must never override a deliberate local choice. Cleared together with the
     * mode itself (setConversationMode(peer, null)).
     */
    fun isConversationModeExplicit(peerAddress: String): Boolean

    /** Pin the current mode as the user's explicit choice (see [isConversationModeExplicit]). */
    fun setConversationModeExplicit(peerAddress: String)

    /**
     * MONOTONIC last-writer-wins guard for inbound ZMODE mode-change controls (mirrors
     * [setDisappearingTtl]): returns false + writes nothing when [sinceMillis] is not newer than the
     * stored watermark, making relay-replay / chain-rescan adoption idempotent.
     */
    fun advancePeerModeChangeSince(peerAddress: String, sinceMillis: Long): Boolean

    /** Per-conversation disappearing-messages TTL (B17). effectiveSinceMillis = last-writer-wins ordering
     *  key + non-retroactivity boundary; synced to the peer via an authenticated control. */
    data class DisappearingTtl(val ttlSeconds: Long, val effectiveSinceMillis: Long)
    /** #226 process-wide singleton flow so list VM + detail VM observe the same source (like readMarkers). */
    val disappearingTtls: kotlinx.coroutines.flow.StateFlow<Map<String, DisappearingTtl>>
    fun getDisappearingTtl(peerAddress: String): DisappearingTtl?
    /** MONOTONIC on effectiveSinceMillis — returns false + writes nothing if the incoming since is not newer
     *  (makes chain-rescan / relay-replay adoption idempotent). */
    fun setDisappearingTtl(peerAddress: String, ttlSeconds: Long, effectiveSinceMillis: Long): Boolean
    /** First-observed anchor for a message's expiry clock (e.g. a PAY/CND lock's unlock time). */
    fun getOrPutMessageExpiryAnchorMillis(messageId: String, nowMillis: Long): Long
    fun clearMessageExpiryAnchor(messageId: String)

    /** Peer's published NOSTR pubkey (32-byte hex), set after a successful ZBOOT handshake. */
    fun getPeerNostrPubkey(peerAddress: String): String?

    fun setPeerNostrPubkey(peerAddress: String, pubkeyHex: String?)

    /** Reverse lookup — find the peer Zcash address that registered the given NOSTR pubkey. */
    fun findPeerByNostrPubkey(pubkeyHex: String): String?

    /** All peers whose conversation mode is TUNNEL. Used by the block-driven ZBOOT maturation
     *  retry so a handshake stuck on an immature KEX change self-heals without a manual chat-open. */
    fun getTunnelModePeers(): List<String>

    /**
     * Persistent replay defense for inbound NOSTR gift-wraps (#188). Relays REPLAY stored gift-wraps
     * on every (re)subscribe, and the same event lands on multiple relays — so the receive path must
     * drop any gift-wrap whose unique event id it has already handled. This is a DEDICATED bounded set
     * that survives process death, independent of the message store: the old dedup keyed on the
     * pending-message list, so deleting a message or pruning the list resurrected replays, and call
     * signals had NO event-id dedup at all (only a freshness window).
     */
    fun hasSeenNostrEvent(eventId: String): Boolean

    /** Record [eventId] as handled. Bounded (LRU); the oldest ids fall out once the cap is reached. */
    fun markNostrEventSeen(eventId: String)

    /**
     * Un-record [eventId] so a relay REPLAY can redeliver it. Used when handling was DEFERRED rather than
     * completed (e.g. a NOSTR GROUP_INVITE that arrived before our address loaded): the in-memory defer
     * queue does not survive process death, so un-seeing the gift-wrap lets the relay re-deliver it on the
     * next (re)subscribe — closing the "stranded forever after a cold-start crash" window. No-op if absent.
     */
    fun unmarkNostrEventSeen(eventId: String)

    /**
     * A pending inbound OPEN ("free NOSTR from message #1") contact request (#224). When a NIP-17 DM
     * arrives from a NOSTR pubkey we don't recognise AND it is a ZMSG v4 INIT, the sender is claiming a
     * Zcash identity we've never met. We do NOT auto-trust it — the gift-wrap proves only which NOSTR
     * key sent it, NOT that that key owns [senderAddress] — so we hold it here for the user to accept
     * or reject (manual TOFU gate). All fields except [senderNostrPubkeyHex]/[eventId] are
     * attacker-controlled and must be treated as untrusted until the user accepts.
     */
    data class MessageRequest(
        val senderNostrPubkeyHex: String, // authenticated by the gift wrap — who actually sent it
        val senderAddress: String,        // CLAIMED Zcash address (from the INIT body — UNVERIFIED)
        val relayUrl: String,             // relay to reply on once accepted
        val firstMessage: String,         // the INIT's inner plaintext (shown as a preview)
        val timestampMillis: Long,
        val eventId: String,              // gift-wrap event id (for completeness/audit)
        // STABLE cross-device rumor id of the first message — used as its ChatMessage id on accept so a
        // reaction/reply the sender attaches correlates across both devices (matches observeNostrInbound).
        val rumorId: String = "",
    )

    /** Persist an inbound OPEN contact [request], keyed by sender NOSTR pubkey (one pending per pubkey;
     *  a newer INIT from the same pubkey replaces the older). Bounded (LRU). */
    fun addMessageRequest(request: MessageRequest)

    /** All pending inbound OPEN contact requests, newest first. */
    fun getMessageRequests(): List<MessageRequest>

    /** Drop the pending request from [senderNostrPubkeyHex] (on accept or reject). */
    fun removeMessageRequest(senderNostrPubkeyHex: String)

    /** True iff the user previously REJECTED+blocked this NOSTR pubkey — its future INITs are dropped
     *  silently instead of resurfacing as a request (anti-nag / anti-spam). */
    fun isNostrPubkeyBlocked(pubkeyHex: String): Boolean

    /** Block [pubkeyHex] so its future unsolicited INITs are dropped. Bounded (LRU). */
    fun blockNostrPubkey(pubkeyHex: String)

    /**
     * #201 anti-flap: has this on-chain KEX/KEXACK transaction ALREADY been processed? A wallet
     * re-scans its whole shielded history on every sync, so without per-txid dedup an OLD KEX (from a
     * peer that has since rotated its key / reinstalled) is re-handled forever — each pass sees a key
     * that differs from the currently-stored one, fires a false "PEER KEY CHANGED", clears the
     * KEXACK-paid guard, and re-sends a KEXACK. That churn perpetually locks the single spendable note
     * (every on-chain send then fails "Insufficient balance (have 0)") and spams a false key-change
     * warning. Deduping by txid makes each KEX tx processed exactly once → the churn becomes one-time.
     */
    fun hasProcessedKexTx(txId: String): Boolean

    /** Record KEX/KEXACK [txId] as processed. Bounded (LRU). */
    fun markKexTxProcessed(txId: String)

    /**
     * #205 — record [address] as a representation of OUR OWN wallet address. A single wallet can
     * present several valid unified-address strings; registering each one we observe (canonical
     * diversifier-0 UA, account's current unified address) lets [isSelfAddress] recognise all of
     * them. Idempotent, bounded (LRU), persisted across process death. No-op on blank input.
     */
    fun registerSelfAddress(address: String)

    /**
     * #205 — true if [address] is any known representation of our own wallet address. Compares by
     * address hash so it tolerates the diversifier/derivation drift that breaks raw `==` self
     * checks (self-message filtering, am-I-kicked, group creator). Returns false for blank input.
     */
    fun isSelfAddress(address: String): Boolean

    /** Peer's preferred NOSTR relay (from ZBOOT). */
    fun getPeerNostrRelay(peerAddress: String): String?

    fun setPeerNostrRelay(peerAddress: String, relayUrl: String?)

    /**
     * Highest ZBOOT rotation epoch we've already ADOPTED for this peer (#225 hardening). A ZBOOT whose
     * signed epoch is strictly LOWER than this is a stale/replayed handshake (e.g. an older on-chain
     * ZBOOT re-scanned from history) and MUST NOT be re-adopted — otherwise it could downgrade the live
     * pubkey back to a dead one. Defaults to 0 (also the epoch carried by legacy v2 ZBOOTs).
     */
    fun getPeerBootEpoch(peerAddress: String): Long

    fun setPeerBootEpoch(peerAddress: String, epoch: Long)

    /** True iff we've already published our own ZBOOT (NOSTR pubkey + relay) to this peer. */
    fun isOwnBootSent(peerAddress: String): Boolean

    fun setOwnBootSent(peerAddress: String, sent: Boolean)

    /**
     * The NOSTR pubkey we last delivered to [peerAddress] via a signed ZBOOT (the sequenced
     * NOSTR-identity handshake that follows KEX/KEXACK). Used for idempotency: skip re-sending the
     * same identity (avoids on-chain drain + a ZBOOT ping-pong), but DO re-send if our identity
     * rotates (pubkey differs). Null = never sent.
     */
    fun getSentNostrBootPubkey(peerAddress: String): String?

    fun setSentNostrBootPubkey(peerAddress: String, pubkeyHex: String?)

    /**
     * The peer E2E pubkey for which WE received an on-chain KEX from this peer — i.e. we are the
     * RESPONDER for that handshake. Set ONLY in the received-KEX path (never on our own outgoing KEX),
     * so it is the one reliable durable "we are the responder" signal (the kexTxId/kexAckTxId markers
     * are set on BOTH directions and so can't distinguish role). Null = we never received a KEX (we
     * either initiated or there's no chat). Used to gate the responder-side KEXACK retry.
     */
    fun getReceivedKexPubkey(peerAddress: String): String?

    fun setReceivedKexPubkey(peerAddress: String, pubkeyHex: String?)

    /**
     * The peer E2E pubkey for which we have SUCCESSFULLY sent a KEXACK. Durable de-dupe so a KEXACK
     * (a ~1000-zatoshi on-chain spend) is paid for at most ONCE per key and is NOT re-sent on every
     * cold app start — the prior in-memory-only guard re-drained on process death. A FAILED ack is not
     * recorded, so it still retries (Tunnel deadlock recovery preserved). Cleared on key change.
     */
    fun getSentKexAckPubkey(peerAddress: String): String?

    fun setSentKexAckPubkey(peerAddress: String, pubkeyHex: String?)

    /** True the first time the user opens the app and we should show the 3-mode onboarding. */
    fun hasSeenModeIntro(): Boolean
    fun setHasSeenModeIntro(seen: Boolean)

    /** True once the user has seen the security note for [mode] on [peerAddress]. One-shot per (peer, mode). */
    fun hasSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode): Boolean
    fun setSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode)

    // ==========================================
    // DESTROY / REMOTE KILL SETTINGS
    // ==========================================

    /**
     * Set the destroy PIN (stored as hash for security). suspend — PBKDF2 runs on Dispatchers.Default.
     */
    suspend fun setDestroyPin(pin: String)

    /**
     * Verify the destroy PIN by comparing hash. suspend — PBKDF2 runs on Dispatchers.Default.
     * @return true if the provided PIN matches the stored hash
     */
    suspend fun verifyDestroyPin(pin: String): Boolean

    /**
     * Verify the destroy PIN with rate-limit-aware result. suspend — PBKDF2 runs on Dispatchers.Default.
     * Callers should prefer this over the boolean variant when they need to surface a lockout.
     */
    suspend fun verifyDestroyPinWithLockout(pin: String): DestroyPinVerifyResult

    /**
     * Check if destroy PIN is set.
     */
    fun hasDestroyPin(): Boolean

    /**
     * Clear the destroy PIN + its lockout counters (recovery path for a forgotten/mis-set PIN).
     * After this, [hasDestroyPin] is false so the next destroy attempt routes to fresh setup.
     * MUST be gated behind device-credential re-auth at the call site — it removes the
     * anti-accidental-wipe PIN, so it deserves the same factor that already protects app access
     * and Send Funds. Without this, a forgotten destroy PIN permanently bricks the wipe feature.
     */
    fun clearDestroyPin()

    /**
     * Check if remote kill is enabled.
     */
    fun isRemoteKillEnabled(): Boolean

    /**
     * Enable/disable remote kill.
     */
    fun setRemoteKillEnabled(enabled: Boolean)

    /**
     * Verify the remote kill secret phrase by comparing hash. suspend — PBKDF2 runs on
     * Dispatchers.Default.
     * @param phrase The phrase to verify (will be hashed and compared)
     * @return true if the provided phrase matches the stored hash
     */
    suspend fun verifyRemoteKillPhrase(phrase: String): Boolean

    /**
     * Set the remote kill secret phrase. suspend — PBKDF2 runs on Dispatchers.Default.
     * NOTE: The phrase cannot be recovered after setting. User must remember it.
     */
    suspend fun setRemoteKillPhrase(phrase: String)

    /**
     * Check if remote kill phrase is set.
     */
    fun hasRemoteKillPhrase(): Boolean

    /**
     * Get the remote kill amount in Zatoshi.
     * Default is a unique amount like 1337 zatoshi.
     */
    fun getRemoteKillAmount(): Long

    /**
     * Set the remote kill amount.
     */
    fun setRemoteKillAmount(amountZatoshi: Long)

    /**
     * Decrypted-plaintext cache for forward-secret (ratcheted) E2E messages, keyed by a hash of the
     * ciphertext. The double-ratchet can decrypt a given message EXACTLY ONCE (it deletes the message
     * key after use), but ZCHAT re-derives conversations from the on-chain history on every sync, so a
     * message would be "decrypted" repeatedly — the 2nd+ attempt fails and the UI showed a confusing
     * "Encrypted message" placeholder. Rule: decrypt once, persist the plaintext here, and NEVER
     * re-run the ratchet on an already-seen ciphertext. Stored in EncryptedSharedPreferences (same
     * AES-256-GCM protection as the E2E keys). [getDecryptedText] returns null if never decrypted.
     */
    fun getDecryptedText(ciphertextHash: String): String?
    fun putDecryptedText(ciphertextHash: String, plaintext: String)

    /** Remove one cached decrypted value — used to scrub a poisoned entry (a raw "E2E1:" blob that an
     *  older build persisted as if it were plaintext) so it is never served back to the UI. */
    fun removeDecryptedText(ciphertextHash: String)

    /**
     * B3: stable first-seen timestamp (epoch millis) for an un-mined tx, anchoring the send countdown.
     * Persisted + first-writer-wins so it survives leaving/re-entering the chat (which recreates the
     * per-screen ChatViewModel) AND process death — the countdown no longer restarts from ~75s on re-entry.
     */
    fun getOrPutPendingTxFirstSeenMillis(txId: String, nowMillis: Long): Long
    fun clearPendingTxFirstSeen(txId: String)

    /**
     * Clear all preferences (used during destruction).
     */
    fun clearAll()

    // ==========================================
    // CONVERSATION IDs (ZMSG v4)
    // ==========================================

    /**
     * Get the conversation ID for a peer address.
     * Returns null if no conversation ID is stored for this peer.
     */
    fun getConversationId(peerAddress: String): String?

    /**
     * Set the conversation ID for a peer address.
     * This is called when we initiate a new conversation.
     */
    fun setConversationId(peerAddress: String, convId: String)

    /**
     * Atomically get existing or create new conversation ID for a peer.
     * Thread-safe across all callers (VMs, services). Prevents race conditions
     * where two callers both see null and generate different IDs.
     * @return Pair of (convId, isNew) where isNew=true if a new ID was generated.
     */
    fun getOrCreateConversationId(peerAddress: String): Pair<String, Boolean>

    /**
     * Get the peer address for a conversation ID.
     * Returns null if no peer is associated with this conversation ID.
     */
    fun getPeerByConversationId(convId: String): String?

    /**
     * Store a mapping from conversation ID to peer address.
     * This is called when we receive a new conversation INIT from someone else.
     */
    fun setConversationMapping(convId: String, peerAddress: String)

    /**
     * Get all conversation mappings (convId -> peerAddress).
     */
    fun getAllConversationMappings(): Map<String, String>

    /**
     * Get all peer to convId mappings (peerAddress -> convId).
     * Used for validation and repair of bidirectional mappings.
     */
    fun getAllPeerToConvIdMappings(): Map<String, String>

    /**
     * Remove a conversation mapping by convId (deletes conv: key).
     * Used by repair logic to clean up orphaned entries with blank peers.
     */
    fun removeConversationMapping(convId: String)

    /**
     * Record that [repAddress] is an alternate UA representation of the SAME peer we canonically
     * track as [canonicalAddress]. Learned ONLY from a cryptographically-verified signal (e.g. a
     * GROUP_ACCEPT whose accepter_pub matched the E2E key we already hold for [canonicalAddress]),
     * so it never merges distinct peers. Fixes the #205 peer-side address-drift (#214): a wallet
     * emits multiple valid UA strings, and group roster/fan-out logic matches by exact string.
     */
    fun setPeerAddressAlias(repAddress: String, canonicalAddress: String)

    /**
     * Resolve [address] to its canonical peer representation if an alias was learned (see
     * [setPeerAddressAlias]); otherwise returns [address] unchanged. Self-mapping safe + idempotent.
     */
    fun resolvePeerAddress(address: String): String

    /**
     * Assert [address] is itself canonical (a live receive address): drop any alias mapping it
     * elsewhere. Used when a peer re-declares an address we already hold so it never canonicalizes away.
     */
    fun clearPeerAddressAlias(address: String)

    // ==========================================
    // PENDING MESSAGES (Persist across navigation)
    // ==========================================

    /**
     * Data class representing a pending message for persistence.
     * Only stores essential fields needed for display.
     */
    data class PendingMessageData(
        val id: String,
        val text: String,
        val timestampMillis: Long,
        val peerAddress: String,
        // Direction: false for inbound NOSTR rows (legacy on-chain pending rows are always outgoing).
        val isOutgoing: Boolean = true,
        // Still in flight? Outbound NOSTR rows persist as not-pending once SENT/FAILED; inbound = false.
        val isPending: Boolean = true,
        // MessageStatus enum name (e.g. "SENDING"/"SENT"/"FAILED"). Null = legacy on-chain pending.
        val status: String? = null,
        // Reply threading marker so a restored NOSTR reply still quotes its target.
        val replyToId: String? = null,
        // Cross-device quote preview text so a restored NOSTR reply renders its quote even when the
        // quoted message's local id can't be resolved on this device (R1-reply-quote-not-showing).
        val replyToPreview: String? = null,
        // Raw "ZFILE|…" memo for file/voice rows — lets the loader re-derive the file bubble fields
        // (hash/type/blurhash/viewOnce) via ZFILEMessage.parse instead of persisting each separately.
        val fileZfileContent: String? = null,
        // Inbound NOSTR payment-request (ZREQ) fields. A non-null amount marks the row as a payment
        // request so the loader can rebuild PaymentRequestInfo (amount + "Pay" affordance) after a
        // restart — without these it degraded to a plain text bubble. isPaid is derived from
        // paidRequestIds at load time (same as the live inbound builder), so it isn't persisted here.
        val paymentRequestAmountZatoshi: Long? = null,
        val paymentRequestReason: String? = null
    )

    /**
     * Get all pending messages that haven't been confirmed yet.
     * These are messages sent by the user that are waiting for blockchain confirmation.
     */
    fun getPendingMessages(): List<PendingMessageData>

    /**
     * Add a pending message.
     * Called when user sends a message before it's confirmed on blockchain.
     */
    fun addPendingMessage(message: PendingMessageData)

    /**
     * Remove a pending message by ID.
     * Called when the message is confirmed on blockchain.
     */
    fun removePendingMessage(messageId: String)

    /**
     * Remove multiple pending messages by their IDs.
     * Called during deduplication when messages are confirmed.
     */
    fun removePendingMessages(messageIds: Set<String>)

    /** A persisted NOSTR reaction (emoji + who reacted + when), keyed by the target message id. */
    data class PersistedReaction(val emoji: String, val senderAddress: String, val timestampMillis: Long)

    /**
     * Persist a NOSTR reaction against [targetId]. Idempotent per (emoji, senderAddress) so relay
     * replays / multi-relay publishes don't inflate the count. Bounded per target. Without this a
     * reaction applied only to the in-memory pendingMessages StateFlow is lost on the next reload.
     */
    fun addNostrReaction(targetId: String, emoji: String, senderAddress: String, timestampMillis: Long)

    /** Persisted NOSTR reactions for [targetId] (empty if none). Re-applied on message load. */
    fun getNostrReactions(targetId: String): List<PersistedReaction>

    /**
     * Clear all pending messages.
     * For cleanup purposes.
     */
    fun clearPendingMessages()

    // ----- Call log: local-only call-history entries (incoming/outgoing/missed/declined) -----
    data class CallLogMessageData(
        val id: String,
        val peerAddress: String,
        val timestampMillis: Long,
        val type: String, // ChatMessage CallLogType.name
        val isVideo: Boolean,
        val durationSec: Long?, // null for missed / declined / no-answer
        val isOutgoing: Boolean,
    )

    fun getCallLogMessages(): List<CallLogMessageData>

    fun addCallLogMessage(message: CallLogMessageData)

    fun removeCallLogMessage(id: String)

    fun clearCallLogMessages()

    // ==========================================
    // NOTIFICATION PRIVACY
    // ==========================================

    /**
     * Get the current notification privacy level.
     * @return The notification privacy level, default is FULL_PREVIEW
     */
    fun getNotificationPrivacy(): NotificationPrivacy

    /**
     * Set the notification privacy level.
     * @param level The privacy level to set
     */
    fun setNotificationPrivacy(level: NotificationPrivacy)

    // ==========================================
    // MESSAGE DRAFTS (Auto-Save)
    // ==========================================

    /**
     * Get the draft message for a peer address.
     * @param peerAddress The peer's Zcash address
     * @return The draft text, or null if no draft exists
     */
    fun getDraft(peerAddress: String): String?

    /**
     * Save a draft message for a peer address.
     * @param peerAddress The peer's Zcash address
     * @param draft The draft text (empty string to clear)
     */
    fun setDraft(peerAddress: String, draft: String)

    /**
     * Clear the draft for a peer address.
     * Called when a message is successfully sent.
     */
    fun clearDraft(peerAddress: String)

    /**
     * Get all drafts (for showing "Draft" indicator in conversation list).
     * @return Map of peerAddress -> draft text
     */
    fun getAllDrafts(): Map<String, String>

    /**
     * Check if a draft exists for a peer address.
     */
    fun hasDraft(peerAddress: String): Boolean

    // ==========================================
    // E2E ENCRYPTION
    // ==========================================

    /**
     * Check if E2E encryption is enabled for a conversation.
     */
    fun isE2EEnabled(peerAddress: String): Boolean

    /**
     * Enable/disable E2E encryption for a conversation.
     */
    fun setE2EEnabled(peerAddress: String, enabled: Boolean)

    /**
     * Get our private key for E2E encryption with a peer.
     * @return Base64 encoded private key, or null if not set
     */
    fun getE2EPrivateKey(peerAddress: String): String?

    /**
     * Get the peer's public key for E2E encryption.
     * @return Base64 encoded public key, or null if not received
     */
    fun getE2EPeerPublicKey(peerAddress: String): String?

    /**
     * Get our public key for E2E encryption with a peer.
     * @return Base64 encoded public key, or null if not generated
     */
    fun getE2EOurPublicKey(peerAddress: String): String?

    /**
     * Store E2E keys for a conversation.
     * @param ourPublicKey Our public key (Base64)
     * @param ourPrivateKey Our private key (Base64)
     */
    fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String)

    /**
     * Store the peer's public key for E2E encryption.
     * @param peerPublicKey Peer's public key (Base64)
     */
    fun setE2EPeerPublicKey(peerAddress: String, peerPublicKey: String)

    /**
     * True if the peer's E2E public key has changed since last acknowledged by the user.
     * Set during KEX handling when the incoming pubkey differs from the stored one.
     * Cleared when the user dismisses the key-changed banner.
     */
    fun isE2EKeyChanged(peerAddress: String): Boolean

    fun setE2EKeyChanged(peerAddress: String, changed: Boolean)

    /**
     * True once the user has confirmed the peer's safety number out-of-band. Distinguishes a
     * "verified" conversation from one that is merely TOFU-encrypted (first-contact trust).
     * Cleared by callers at every peer-key-change site (each [setE2EKeyChanged] `true` call)
     * and by [clearE2EKeys] — NOT inside [setE2EKeyChanged] itself — because a key change
     * invalidates any prior out-of-band verification.
     */
    fun isE2EVerified(peerAddress: String): Boolean

    fun setE2EVerified(peerAddress: String, verified: Boolean)

    /**
     * Get the persistent ratchet state store for E2E forward secrecy.
     * Backed by EncryptedSharedPreferences — survives app restart.
     */
    /** Store the KEX transaction ID for root key derivation context. */
    fun setE2EKexTxId(peerAddress: String, txId: String)
    fun getE2EKexTxId(peerAddress: String): String?

    /** Store the KEXACK transaction ID for root key derivation context. */
    fun setE2EKexAckTxId(peerAddress: String, txId: String)
    fun getE2EKexAckTxId(peerAddress: String): String?

    /**
     * Convergent KEX/KEXACK txid SETS for ratchet-root derivation (B1/B2 fix). Both devices observe the
     * SAME mined KEX/KEXACK txs for a conversation (own via SendTransaction scan, peer's via
     * ReceiveTransaction scan), so a SORTED set yields byte-identical root material on both sides — fixing
     * the last-writer-wins scalar divergence that broke OPEN→VAULT decrypt (dual-KEX pairs). The sets are
     * RECOMPUTED (replaced) from the chain scan each pass — never incrementally accumulated behind a
     * once-ever guard — so they stay a pure function of shared on-chain state: symmetric, rescan-idempotent,
     * reorg-self-healing, and they retroactively heal pre-update / dual-KEX / multi-KEX history.
     */
    fun setKexTxIds(peerAddress: String, txIds: Set<String>)
    fun getKexTxIds(peerAddress: String): Set<String>
    fun setKexAckTxIds(peerAddress: String, txIds: Set<String>)
    fun getKexAckTxIds(peerAddress: String): Set<String>

    /** Clear both convergent txid sets for a peer (e.g. on full E2E reset). The per-scan recompute
     *  repopulates them from chain state, so this is only a transient/clean-slate helper. */
    fun clearKexTxIds(peerAddress: String)

    /** Store the Quantum Shield PSK for a conversation (Base64-encoded 32 bytes). */
    fun setQuantumShieldPSK(peerAddress: String, pskBase64: String)
    fun getQuantumShieldPSK(peerAddress: String): String?
    fun clearQuantumShieldPSK(peerAddress: String)

    /** Store our Quantum Shield secret for a conversation (Base64-encoded, for QR display). */
    fun setQuantumShieldOurSecret(peerAddress: String, secretBase64: String)
    fun getQuantumShieldOurSecret(peerAddress: String): String?

    fun getRatchetStateStore(): co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore

    /**
     * Check if E2E key exchange is complete (both keys available).
     */
    fun isE2EKeyExchangeComplete(peerAddress: String): Boolean

    /**
     * Clear E2E keys for a conversation.
     */
    fun clearE2EKeys(peerAddress: String)

    /**
     * Get the E2E key derivation version for a peer.
     * @return Key version (1 = legacy SHA-256, 2 = HKDF), defaults to 1 for backwards compatibility
     */
    fun getE2EKeyVersion(peerAddress: String): Int

    /**
     * Set the E2E key derivation version for a peer.
     * Should be called when establishing new keys with HKDF (version 2).
     */
    fun setE2EKeyVersion(peerAddress: String, version: Int)

    // ==========================================
    // GROUP CHAT
    // ==========================================

    /**
     * Save group info (as JSON string).
     * @param groupId The unique group identifier
     * @param groupInfoJson JSON representation of GroupInfo
     */
    fun saveGroupInfo(groupId: String, groupInfoJson: String)

    /**
     * Get group info by ID.
     * @return JSON string of GroupInfo, or null if not found
     */
    fun getGroupInfo(groupId: String): String?

    /**
     * Get all group IDs.
     */
    fun getAllGroupIds(): Set<String>

    /**
     * Delete a group.
     */
    fun deleteGroup(groupId: String)

    /**
     * Save group members (as JSON string).
     * @param groupId The group ID
     * @param membersJson JSON array of GroupMember objects
     */
    fun saveGroupMembers(groupId: String, membersJson: String)

    /**
     * Get group members.
     * @return JSON array string of GroupMember objects
     */
    fun getGroupMembers(groupId: String): String?

    /**
     * Save group key for encryption.
     * @param groupId The group ID
     * @param keyEpoch The key epoch
     * @param encryptedKey Base64 encoded encrypted group key
     */
    fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String)

    /**
     * Get group key for a specific epoch.
     * @return Base64 encoded encrypted group key
     */
    fun getGroupKey(groupId: String, keyEpoch: Int): String?

    /**
     * Get the current key epoch for a group.
     */
    fun getGroupKeyEpoch(groupId: String): Int

    /**
     * Set the current key epoch for a group.
     */
    fun setGroupKeyEpoch(groupId: String, epoch: Int)

    /**
     * Did WE create this group (vs. being invited to it)? Persisted at creation so the admin/creator
     * role survives a self-address-representation change (the creatorAddress string stored in GroupInfo
     * can drift from our live address across reinstalls/SDK upgrades, which would otherwise make the
     * "is this me?" address comparison wrongly false and hide the admin kick/rotate UI).
     */
    fun isGroupSelfCreated(groupId: String): Boolean

    fun setGroupSelfCreated(groupId: String, created: Boolean)

    /**
     * Get draft for a group conversation.
     */
    fun getGroupDraft(groupId: String): String?

    /**
     * Set draft for a group conversation.
     */
    fun setGroupDraft(groupId: String, draft: String)

    /**
     * Clear draft for a group conversation.
     */
    fun clearGroupDraft(groupId: String)

    /**
     * Get all group drafts.
     */
    fun getAllGroupDrafts(): Map<String, String>

    /**
     * Get the sequence number for sending group messages.
     */
    fun getGroupMessageSequence(groupId: String): Long

    /**
     * Increment and return the next sequence number for group messages.
     */
    fun incrementGroupMessageSequence(groupId: String): Long

    /**
     * Get stored messages for a group (JSON array string).
     */
    fun getGroupMessages(groupId: String): String?

    /**
     * Save messages for a group (JSON array string).
     */
    fun saveGroupMessages(groupId: String, messagesJson: String)

    // ==========================================
    // UNROUTABLE MESSAGES
    // ==========================================

    /**
     * Data class for messages that couldn't be confidently routed to a conversation.
     */
    data class UnroutableMessageData(
        val txId: String,
        val memoPreview: String,
        val timestamp: Long,
        val senderHash: String?,
        val convId: String?
    )

    /**
     * Store an unroutable message for later manual assignment.
     */
    fun addUnroutableMessage(message: UnroutableMessageData)

    /**
     * Get all unroutable messages.
     */
    fun getUnroutableMessages(): List<UnroutableMessageData>

    /**
     * Remove an unroutable message (after user assigns it or dismisses it).
     */
    fun removeUnroutableMessage(txId: String)

    /**
     * Get count of unroutable messages (for badge display).
     */
    fun getUnroutableMessageCount(): Int

    // ==========================================
    // IDENTITY MANAGEMENT
    // ==========================================

    /**
     * Get all contact addresses from the address book (nicknames storage).
     * @return Set of all addresses that have nicknames set
     */
    fun getAllContactAddresses(): Set<String>

    /**
     * Get all peer addresses from conversation mappings.
     * @return Set of all peer addresses that have conversations
     */
    fun getAllConversationPeerAddresses(): Set<String>

    // ==========================================
    // NOTIFICATION SETTINGS
    // ==========================================

    /**
     * Check if notification sound is enabled.
     * @return true if sound is enabled (default: true)
     */
    fun isNotificationSoundEnabled(): Boolean

    /**
     * Enable/disable notification sound.
     */
    fun setNotificationSoundEnabled(enabled: Boolean)

    /**
     * Check if notification vibration is enabled.
     * @return true if vibration is enabled (default: true)
     */
    fun isNotificationVibrationEnabled(): Boolean

    /**
     * Enable/disable notification vibration.
     */
    fun setNotificationVibrationEnabled(enabled: Boolean)

    /**
     * Get the set of muted conversation addresses.
     */
    fun getMutedConversations(): Set<String>

    /**
     * Mute a conversation by address.
     */
    fun muteConversation(address: String)

    /**
     * Unmute a conversation by address.
     */
    fun unmuteConversation(address: String)

    /**
     * Check if a conversation is muted.
     */
    fun isConversationMuted(address: String): Boolean

    // ==========================================
    // CONVERSATION READ STATE (unread badge)
    // ==========================================

    /**
     * Last-read marker for a conversation, as epoch milliseconds. Incoming messages with a
     * timestamp newer than this are counted as unread. Returns 0 if the conversation has never
     * been opened (so all incoming history counts as unread until first open).
     */
    fun getLastReadTimestamp(peerAddress: String): Long

    /**
     * Mark a conversation read up to [millis] (epoch milliseconds). Monotonic: an earlier value
     * never overwrites a later one, so a stale call can't resurrect already-read messages.
     */
    fun setLastReadTimestamp(peerAddress: String, millis: Long)

    /**
     * Snapshot of every stored last-read marker (peerAddress -> epoch millis). Used to compute
     * unread counts for the whole conversation list in one pass.
     */
    fun getAllLastReadTimestamps(): Map<String, Long>

    /**
     * PROCESS-WIDE reactive view of all last-read markers (#226). ZchatPreferences is a DI singleton,
     * so this single flow is shared across every ChatViewModel instance — including the separate
     * instances that back the chat-LIST and chat-DETAIL screens (distinct NavBackStackEntry stores).
     * [setLastReadTimestamp] emits the updated map here, so a read advanced on the detail screen is
     * observed by the list screen's unread recompute immediately, instead of the list holding a stale
     * per-instance copy seeded once at init (the bug where the badge never cleared after opening).
     */
    val readMarkers: kotlinx.coroutines.flow.StateFlow<Map<String, Long>>

    /** #257: bumped whenever a handshake-relevant E2E marker changes (KEXACK sent, received-KEX, boot-sent,
     *  E2E enabled, peer key, key wipe). Lets the chat-list/detail E2E status recompute immediately after a
     *  prefs-only write (e.g. retryKexAckIfResponder success) instead of waiting for the next chain scan. */
    val e2eHandshakeTicks: kotlinx.coroutines.flow.StateFlow<Long>

    // ==========================================
    // WORKER SYNC TIMESTAMP
    // ==========================================

    /**
     * Get the last timestamp when SyncWorker completed a sync.
     */
    fun getLastWorkerSyncTimestamp(): Long

    /**
     * Set the last timestamp when SyncWorker completed a sync.
     */
    fun setLastWorkerSyncTimestamp(millis: Long)

    // ==========================================
    // SEED BACKUP REMINDER
    // ==========================================

    fun hasBackedUpSeed(): Boolean
    fun setHasBackedUpSeed(backed: Boolean)
    fun getFirstOutgoingMessageTimestamp(): Long
    fun setFirstOutgoingMessageTimestamp(millis: Long)
    fun getLastBackupReminderTimestamp(): Long
    fun setLastBackupReminderTimestamp(millis: Long)
    fun getBackupReminderCount(): Int
    fun incrementBackupReminderCount()

    // ==========================================
    // NOSTR KEY ROTATION (#178 Part B)
    // ==========================================
    /** Account-wide NOSTR derivation index. 0 = original identity; bumped by user key rotation. */
    fun getNostrRotationIndex(): Int
    fun setNostrRotationIndex(index: Int)

    /**
     * The rotation index of OUR NOSTR key that [peerAddress] currently knows/has adopted (#250). A v4
     * rotation ZBOOT to an OPEN peer is signed with the key at THIS index (the one they still hold) — not
     * blindly index-1 — so a missed/undelivered rotation can't permanently strand them. -1 = unknown
     * (treated as the original index 0). Advanced only when the peer is known to have received our new key.
     */
    fun getPeerKnownOurRotationIndex(peerAddress: String): Int
    fun setPeerKnownOurRotationIndex(peerAddress: String, index: Int)
    /** Epoch millis of the last time we showed the "rotate your key" reminder (0 = never). */
    fun getLastRotationReminderAt(): Long
    fun setLastRotationReminderAt(millis: Long)
}

/**
 * SharedPreferences-based implementation of ZchatPreferences.
 *
 * SECURITY: Sensitive data (E2E keys, group keys) uses EncryptedSharedPreferences
 * with AES256-GCM encryption backed by Android Keystore.
 * Non-sensitive data (drafts, nicknames) uses regular SharedPreferences.
 */
class ZchatPreferencesImpl(context: Context) : ZchatPreferences {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Separate prefs file for peer statuses (can grow large)
    private val peerStatusPrefs: SharedPreferences = context.getSharedPreferences(
        PEER_STATUS_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Unified conversation mapping prefs: uses prefixed keys for atomicity
    // "peer:<address>" -> convId  and  "conv:<convId>" -> address
    // This replaces the old separate convIdPrefs and peerToConvIdPrefs files
    // to ensure both directions are written in a single atomic commit().
    private val convMappingPrefs: SharedPreferences = context.getSharedPreferences(
        CONV_MAPPING_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    init {
        // Migrate from old separate prefs files to unified file
        migrateConvIdPrefs(context)
    }

    /**
     * Migration: copy entries from old separate convIdPrefs/peerToConvIdPrefs into
     * the unified convMappingPrefs file, then clear the old files.
     * Safe to run multiple times (idempotent).
     */
    private fun migrateConvIdPrefs(context: Context) {
        val oldConvIdPrefs = context.getSharedPreferences(CONV_ID_PREFS_NAME_OLD, Context.MODE_PRIVATE)
        val oldPeerToConvIdPrefs = context.getSharedPreferences(PEER_TO_CONV_ID_PREFS_NAME_OLD, Context.MODE_PRIVATE)

        val oldConvEntries = oldConvIdPrefs.all
        val oldPeerEntries = oldPeerToConvIdPrefs.all

        if (oldConvEntries.isEmpty() && oldPeerEntries.isEmpty()) return

        Log.d("ZCHAT_MIGRATE", "Migrating ConvID prefs: ${oldConvEntries.size} conv entries, ${oldPeerEntries.size} peer entries")

        val editor = convMappingPrefs.edit()

        // Track which keys have been written OR already exist in target.
        // This prevents re-migration from overwriting newer data if the process was
        // killed between the migration commit and the old-file clear.
        // Also prevents flip-flop when old files disagree (first-writer-wins).
        val writtenKeys = mutableSetOf<String>()
        // Seed with existing keys in target so re-migration doesn't clobber them
        for ((key, _) in convMappingPrefs.all) {
            writtenKeys.add(key)
        }

        // Migrate convId -> peerAddress (old convIdPrefs) — first pass, authoritative
        // Both keys are guarded by writtenKeys to prevent re-migration from overwriting
        // newer data if old files weren't cleared (process killed between commit and clear).
        for ((convId, value) in oldConvEntries) {
            if (value is String && value.isNotBlank()) {
                val convKey = "conv:$convId"
                val peerKey = "peer:$value"
                if (convKey !in writtenKeys) {
                    editor.putString(convKey, value)
                    writtenKeys.add(convKey)
                }
                if (peerKey !in writtenKeys) {
                    editor.putString(peerKey, convId)
                    writtenKeys.add(peerKey)
                }
            }
        }

        // Migrate peerAddress -> convId (old peerToConvIdPrefs) — fills gaps only
        for ((peerAddress, value) in oldPeerEntries) {
            if (value is String && value.isNotBlank()) {
                val peerKey = "peer:$peerAddress"
                val convKey = "conv:$value"
                if (peerKey !in writtenKeys) {
                    editor.putString(peerKey, value)
                    writtenKeys.add(peerKey)
                }
                if (convKey !in writtenKeys) {
                    editor.putString(convKey, peerAddress)
                    writtenKeys.add(convKey)
                }
            }
        }

        val migrationSuccess = editor.commit()

        if (!migrationSuccess) {
            Log.e("ZCHAT_MIGRATE", "Migration commit FAILED - keeping old files for retry on next launch")
            return
        }

        // Clear old files only after successful migration
        oldConvIdPrefs.edit().clear().commit()
        oldPeerToConvIdPrefs.edit().clear().commit()

        Log.d("ZCHAT_MIGRATE", "ConvID migration complete")
    }

    /**
     * One-time migration of DECRYPTED message plaintext out of the old UNENCRYPTED SharedPreferences files
     * (zchat_group_messages / zchat_pending_messages) into the now-encrypted stores, then CLEAR the plain
     * files so no decrypted content remains at rest. Idempotent (no-op once the old files are empty).
     */
    private fun migrateMessagePlaintextPrefs(context: Context) {
        migratePlainToEncrypted(context.getSharedPreferences(GROUP_MSG_PREFS_NAME, Context.MODE_PRIVATE), groupMsgPrefs)
        migratePlainToEncrypted(context.getSharedPreferences(PENDING_MSG_PREFS_NAME, Context.MODE_PRIVATE), pendingMsgPrefs)
    }

    private fun migratePlainToEncrypted(old: SharedPreferences, enc: SharedPreferences) {
        val entries = old.all
        if (entries.isEmpty()) return
        // Don't clobber NEWER encrypted data if a prior migration committed but the process died before the
        // old-file clear — only fill keys the encrypted store doesn't already hold.
        val existing = enc.all.keys
        val editor = enc.edit()
        var moved = 0
        for ((k, v) in entries) {
            if (v is String && k !in existing) {
                editor.putString(k, v)
                moved++
            }
        }
        if (editor.commit()) {
            // Remove the at-rest plaintext ONLY after a durable encrypted write.
            old.edit().clear().commit()
            Log.d("ZCHAT_MIGRATE", "Migrated $moved plaintext message record(s) to encrypted store")
        } else {
            Log.e("ZCHAT_MIGRATE", "Encrypted-migration commit FAILED — keeping old plain file for retry")
        }
    }

    // Contact nicknames: address -> nickname
    private val nicknamePrefs: SharedPreferences = context.getSharedPreferences(
        NICKNAME_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Message drafts: peerAddress -> draft text
    private val draftPrefs: SharedPreferences = context.getSharedPreferences(
        DRAFT_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // View-once consumption marker: fileHash -> "1" once consumed.
    // Stored unencrypted because the hash is opaque on its own — the cache wipe is what
    // actually protects the bytes.
    private val viewOncePrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_view_once",
        Context.MODE_PRIVATE,
    )

    // Per-conversation mode (VAULT/TUNNEL/OPEN) + the peer's NOSTR pubkey + relay.
    // Tunnel needs all three after the bootstrap completes; Open needs pubkey + relay
    // exchanged out of band.
    private val modePrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_conversation_mode",
        Context.MODE_PRIVATE,
    )

    // #188 dedicated, bounded, persistent set of handled inbound NOSTR gift-wrap event ids. Its own
    // file so it can't collide with mode/key data and so clearing it never touches conversation state.
    private val nostrSeenPrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_nostr_seen",
        Context.MODE_PRIVATE,
    )
    private val nostrSeenLock = Any()
    // Insertion-ordered in-memory mirror for O(1) contains + LRU eviction; loaded once from disk.
    private val nostrSeenIds: LinkedHashSet<String> by lazy {
        synchronized(nostrSeenLock) {
            val stored = nostrSeenPrefs.getString(NOSTR_SEEN_KEY, null)
            LinkedHashSet(stored?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList())
        }
    }

    // #201 anti-flap: dedicated bounded persistent set of processed on-chain KEX/KEXACK txids.
    private val kexSeenPrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_kex_seen",
        Context.MODE_PRIVATE,
    )
    private val kexSeenLock = Any()
    private val kexSeenIds: LinkedHashSet<String> by lazy {
        synchronized(kexSeenLock) {
            val stored = kexSeenPrefs.getString(KEX_SEEN_KEY, null)
            LinkedHashSet(stored?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList())
        }
    }

    // #205 — self-address representation registry. A single wallet presents multiple valid
    // unified-address strings (diversifier/derivation/receiver-subset differences), so "is this
    // address me?" cannot be a raw string compare. We record the hash of every representation of
    // OUR OWN address we observe (canonical diversifier-0 UA used for KEX-sign + receive display,
    // plus the account's current unified address) and match by hash. Backed by `prefs` (a stored
    // string-set) so it survives process death and is shared across ChatViewModel/GroupViewModel.
    private val selfAddrLock = Any()

    // SECURITY: E2E encryption keys stored in EncryptedSharedPreferences
    // Keys are encrypted with AES256-GCM, master key stored in Android Keystore
    private val e2ePrefs: SharedPreferences = createEncryptedPrefs(context, E2E_PREFS_NAME)

    // Group chat storage
    private val groupInfoPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_INFO_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val groupMembersPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_MEMBERS_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // SECURITY: Group encryption keys stored in EncryptedSharedPreferences
    private val groupKeysPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_KEYS_PREFS_NAME)

    // SECURITY: decrypted message plaintext (see getDecryptedText doc) — encrypted at rest.
    private val decryptedTextPrefs: SharedPreferences = createEncryptedPrefs(context, DECRYPTED_TEXT_PREFS_NAME)

    // SECURITY (#224): inbound OPEN contact requests hold a claimed Zcash address + the first-message
    // plaintext, so they are encrypted at rest. Per-pubkey JSON entry ("req:<pubkey>"); a blocked-pubkey
    // set lives under BLOCKED_PUBKEYS_KEY.
    private val messageRequestPrefs: SharedPreferences = createEncryptedPrefs(context, MESSAGE_REQUEST_PREFS_NAME)
    private val messageRequestLock = Any()

    // SECURITY: Ratchet state (counters, seen-counter sets) stored encrypted
    private val ratchetPrefs: SharedPreferences = createEncryptedPrefs(context, "zchat_ratchet_state")
    private val ratchetStore = co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.EncryptedPrefsRatchetStateStore(ratchetPrefs)

    private val groupDraftPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_DRAFT_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val groupSeqPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_SEQ_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // #audit (plaintext-at-rest): holds DECRYPTED group message plaintext → AES256-GCM encrypted, like
    // decryptedTextPrefs. Distinct "_encrypted" file so we can migrate the old plain file out (below).
    private val groupMsgPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_MSG_PREFS_NAME_ENC)

    // Pending messages: messageId -> PendingMessageData JSON. Holds DECRYPTED NOSTR message plaintext +
    // peer addresses → encrypted at rest for the same reason.
    private val pendingMsgPrefs: SharedPreferences = createEncryptedPrefs(context, PENDING_MSG_PREFS_NAME_ENC)

    init {
        // Second init block — runs AFTER the two encrypted stores above are initialized (init blocks and
        // property initializers execute in source order). Move any pre-existing decrypted plaintext out of
        // the old UNENCRYPTED files into the encrypted stores and clear the plain files so nothing lingers.
        migrateMessagePlaintextPrefs(context)
    }

    // NOSTR-reaction persistence. Reactions sent/received over NOSTR are NOT on-chain memos (so they
    // aren't re-derived by the on-chain reactionsByTarget pass) and PendingMessageData has no reactions
    // field — so a reaction applied only to the in-memory pendingMessages StateFlow is WIPED on the next
    // reload (loadPendingMessagesFromPrefs overwrites pendingMessages from storage). Persist them here,
    // keyed by target message id, and re-apply on load. Lines: "emojisenderAddrtsMillis".
    private val reactionPrefs: SharedPreferences = context.getSharedPreferences(
        NOSTR_REACTION_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val reactionLock = Any()

    // Local call-log entries (not secret — plaintext SharedPreferences, like pendingMsgPrefs).
    private val callLogPrefs: SharedPreferences = context.getSharedPreferences(
        CALL_LOG_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Unroutable messages: txId -> UnroutableMessageData JSON
    private val unroutableMsgPrefs: SharedPreferences = context.getSharedPreferences(
        UNROUTABLE_MSG_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Create EncryptedSharedPreferences for secure storage of sensitive data.
     *
     * SECURITY: Uses AES256-GCM encryption with master key stored in Android Keystore.
     * The master key is hardware-backed on devices with secure hardware (TEE/StrongBox).
     *
     * CRITICAL: Never falls back to plaintext. If encryption fails, the app cannot
     * safely store E2E keys, so we crash with a clear error rather than silently
     * storing private keys in plaintext.
     */
    private fun createEncryptedPrefs(context: Context, name: String): SharedPreferences {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Self-heal the RECOVERABLE corruption modes: a bare KeyStoreException (Keystore key loss) is
            // only ONE of them — after a cloud-backup restore the prefs XML comes back but the Keystore
            // master key does not, so Tink surfaces "could not decrypt keyset" as a GeneralSecurityException
            // (KeyStoreException's own supertype) or an InvalidProtocolBufferException (IOException). Those
            // previously hit the generic branch and crashed the app on EVERY launch with no recovery. Clear
            // the corrupt keyset+data for THIS named store and recreate it once. Still NEVER falls back to
            // plaintext — the affected store's data is lost, which is strictly better than a crash loop.
            val recoverable = e is java.security.GeneralSecurityException || e is java.io.IOException
            if (!recoverable) {
                throw IllegalStateException(
                    "CRITICAL: Cannot create encrypted storage for $name. " +
                        "E2E keys cannot be stored safely. Device may not support Android Keystore.",
                    e
                )
            }
            Log.e("ZchatPreferences", "Encrypted store $name corrupt (${e.javaClass.simpleName}), clearing and retrying", e)
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "zchat_preferences"
        private const val PEER_STATUS_PREFS_NAME = "zchat_peer_statuses"
        private const val CONV_MAPPING_PREFS_NAME = "zchat_conv_mapping"   // unified: "peer:<addr>"->convId, "conv:<id>"->addr
        private const val CONV_ID_PREFS_NAME_OLD = "zchat_conv_ids"          // OLD: convId -> peerAddress (migration source)
        private const val PEER_TO_CONV_ID_PREFS_NAME_OLD = "zchat_peer_conv_ids"  // OLD: peerAddress -> convId (migration source)
        private const val NICKNAME_PREFS_NAME = "zchat_nicknames"        // address -> nickname
        private const val DRAFT_PREFS_NAME = "zchat_drafts"            // peerAddress -> draft text
        private const val E2E_PREFS_NAME = "zchat_e2e_keys_encrypted"  // E2E encryption keys (AES256-GCM encrypted)
        private const val DECRYPTED_TEXT_PREFS_NAME = "zchat_decrypted_text_encrypted"  // ratcheted-msg plaintext cache (AES256-GCM)
        private const val MESSAGE_REQUEST_PREFS_NAME = "zchat_message_requests_encrypted"  // #224 inbound OPEN requests (AES256-GCM)
        private const val BLOCKED_PUBKEYS_KEY = "__blocked_nostr_pubkeys__"  // newline-joined, within messageRequestPrefs
        private const val REQ_KEY_PREFIX = "req:"  // per-request entry key prefix
        private const val MAX_MESSAGE_REQUESTS = 100  // LRU cap on pending inbound OPEN requests
        private const val MAX_BLOCKED_PUBKEYS = 500   // LRU cap on rejected/blocked NOSTR pubkeys
        private const val NOSTR_SEEN_KEY = "ids"                       // newline-joined handled gift-wrap event ids
        // Bound on the persistent seen-event LRU. ~2000 × 65 bytes ≈ 130 KB — ample headroom over any
        // realistic relay replay backlog while keeping the prefs blob small.
        private const val MAX_SEEN_NOSTR_EVENTS = 2000
        // #201 anti-flap: processed on-chain KEX/KEXACK txid LRU (newline-joined). A conversation sees
        // only a handful of KEX txs over its life, so a small cap is plenty.
        private const val KEX_SEEN_KEY = "ids"
        private const val MAX_SEEN_KEX_TXS = 500
        // #205 self-address representation registry (set of our own address hashes)
        private const val SELF_ADDR_HASHES_KEY = "self_addr_hashes"
        private const val MAX_SELF_ADDR_HASHES = 16
        // Group chat prefs
        private const val GROUP_INFO_PREFS_NAME = "zchat_group_info"     // groupId -> GroupInfo JSON
        private const val GROUP_MEMBERS_PREFS_NAME = "zchat_group_members" // groupId -> members JSON array
        private const val GROUP_KEYS_PREFS_NAME = "zchat_group_keys_encrypted"  // groupId_epoch -> group key (AES256-GCM encrypted)
        private const val GROUP_DRAFT_PREFS_NAME = "zchat_group_drafts"  // groupId -> draft text
        private const val GROUP_SEQ_PREFS_NAME = "zchat_group_seq"       // groupId -> sequence number
        // OLD plain files — kept ONLY as the migration source (see migrateMessagePlaintextPrefs); no longer written.
        private const val GROUP_MSG_PREFS_NAME = "zchat_group_messages" // groupId -> messages JSON array
        private const val PENDING_MSG_PREFS_NAME = "zchat_pending_messages" // messageId -> PendingMessageData JSON
        // Encrypted stores (AES256-GCM) — hold DECRYPTED message plaintext, so never at rest in the clear.
        private const val GROUP_MSG_PREFS_NAME_ENC = "zchat_group_messages_encrypted"
        private const val PENDING_MSG_PREFS_NAME_ENC = "zchat_pending_messages_encrypted"
        private const val NOSTR_REACTION_PREFS_NAME = "zchat_nostr_reactions" // targetMsgId -> reactions
        private const val MAX_REACTIONS_PER_TARGET = 64

        // LRU cap on the number of DISTINCT reaction target ids persisted. addNostrReaction writes one
        // prefs key per targetId, and a malicious peer can send ZREACTs for unbounded FABRICATED
        // targetIds — unbounded prefs growth = storage exhaustion / ANR. Evict the targets whose newest
        // reaction is oldest once at/over cap.
        private const val MAX_REACTION_TARGETS = 1000
        private const val CALL_LOG_PREFS_NAME = "zchat_call_log" // id -> CallLogMessageData JSON
        private const val UNROUTABLE_MSG_PREFS_NAME = "zchat_unroutable_messages" // txId -> UnroutableMessageData JSON
        private const val GROUP_IDS_KEY = "group_ids"                    // Set of all group IDs
        private const val GROUP_EPOCH_PREFIX = "epoch_"                  // Prefix for epoch storage
        private const val GROUP_SELF_CREATED_PREFIX = "grpcreator_"      // Prefix: did WE create this group
        // E2E key prefixes
        private const val E2E_ENABLED_PREFIX = "e2e_enabled_"
        private const val E2E_OUR_PUBLIC_PREFIX = "e2e_our_pub_"
        private const val E2E_OUR_PRIVATE_PREFIX = "e2e_our_priv_"
        private const val E2E_PEER_PUBLIC_PREFIX = "e2e_peer_pub_"
        private const val E2E_KEY_VERSION_PREFIX = "e2e_key_ver_"
        private const val KEY_ACKNOWLEDGED_MESSAGE_COST = "acknowledged_message_cost"
        private const val KEY_HIDDEN_MESSAGES = "hidden_message_ids"
        private const val KEY_PAID_REQUESTS = "paid_payment_request_ids"
        private const val KEY_USER_STATUS = "user_status"
        private const val KEY_USER_STATUS_TIMESTAMP = "user_status_timestamp"
        private const val KEY_CUSTOM_MEMO_TEMPLATES = "custom_memo_templates"
        private const val KEY_FONT_SIZE_SCALE = "font_size_scale"
        // Destroy / Remote Kill keys
        private const val KEY_DESTROY_PIN = "destroy_pin"
        private const val KEY_DESTROY_PIN_FORMAT_V2 = "destroy_pin_format_v2"
        private const val KEY_REMOTE_KILL_PHRASE_FORMAT_V2 = "remote_kill_phrase_format_v2"
        private const val KEY_PIN_FAIL_COUNT = "destroy_pin_fail_count"
        private const val KEY_PIN_VIOLATIONS = "destroy_pin_violations"
        private const val KEY_PIN_LOCKOUT_ELAPSED = "destroy_pin_lockout_elapsed"
        private const val KEY_PIN_LOCKOUT_WALL = "destroy_pin_lockout_wall"
        // One-shot marker — once true, all subsequent reads/writes for PIN + kill-phrase
        // go through e2ePrefs (encrypted at rest). Persisted in e2ePrefs itself so a tampered
        // plain prefs cannot reset it.
        private const val KEY_DESTROY_STORAGE_V3 = "destroy_storage_v3_done"
        private const val KEY_REMOTE_KILL_ENABLED = "remote_kill_enabled"
        private const val KEY_REMOTE_KILL_PHRASE_HASH = "remote_kill_phrase_hash"  // SHA-256 hash, not plaintext
        private const val KEY_REMOTE_KILL_AMOUNT = "remote_kill_amount"
        private const val DEFAULT_REMOTE_KILL_AMOUNT = 1337L // 0.00001337 ZEC - unique amount
        // Notification Privacy
        private const val KEY_NOTIFICATION_PRIVACY = "notification_privacy"
        // Notification Settings
        private const val KEY_NOTIFICATION_SOUND = "notification_sound_enabled"
        private const val KEY_NOTIFICATION_VIBRATION = "notification_vibration_enabled"
        private const val KEY_MUTED_CONVERSATIONS = "muted_conversations"
        // Conversation read state: "lastread:<peerAddress>" -> epoch millis (unread-badge support)
        private const val LAST_READ_PREFIX = "lastread:"
        private const val DISAPPEAR_TTL_PREFIX = "disappear_ttl:"
        private const val DISAPPEAR_SINCE_PREFIX = "disappear_since:"
        private const val EXPIRY_ANCHOR_PREFIX = "expiry_anchor:"
        // Worker Sync
        private const val KEY_LAST_WORKER_SYNC_TIMESTAMP = "last_worker_sync_timestamp"
        // Seed Backup Reminder
        private const val KEY_HAS_BACKED_UP_SEED = "has_backed_up_seed"
        private const val KEY_FIRST_OUTGOING_MSG_TS = "first_outgoing_msg_timestamp"
        private const val KEY_LAST_BACKUP_REMINDER_TS = "last_backup_reminder_timestamp"
        private const val KEY_NOSTR_ROTATION_INDEX = "nostr_rotation_index"
        private const val KEY_LAST_ROTATION_REMINDER_TS = "last_rotation_reminder_timestamp"
        private const val KEY_BACKUP_REMINDER_COUNT = "backup_reminder_count"
    }

    override fun hasAcknowledgedMessageCost(): Boolean {
        return prefs.getBoolean(KEY_ACKNOWLEDGED_MESSAGE_COST, false)
    }

    override fun setAcknowledgedMessageCost() {
        prefs.edit().putBoolean(KEY_ACKNOWLEDGED_MESSAGE_COST, true).apply()
    }

    override fun getHiddenMessageIds(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet()) ?: emptySet()
    }

    override fun hideMessage(messageId: String) {
        val current = getHiddenMessageIds().toMutableSet()
        current.add(messageId)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    override fun getPaidRequestIds(): Set<String> {
        return prefs.getStringSet(KEY_PAID_REQUESTS, emptySet()) ?: emptySet()
    }

    override fun markRequestPaid(requestMessageId: String) {
        val current = getPaidRequestIds().toMutableSet()
        current.add(requestMessageId)
        prefs.edit().putStringSet(KEY_PAID_REQUESTS, current).apply()
    }

    override fun hideMessages(messageIds: Set<String>) {
        val current = getHiddenMessageIds().toMutableSet()
        current.addAll(messageIds)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    override fun unhideMessage(messageId: String) {
        val current = getHiddenMessageIds().toMutableSet()
        current.remove(messageId)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    // ==========================================
    // USER STATUS IMPLEMENTATION
    // ==========================================

    override fun getUserStatus(): String {
        return prefs.getString(KEY_USER_STATUS, "") ?: ""
    }

    override fun setUserStatus(status: String) {
        prefs.edit()
            .putString(KEY_USER_STATUS, status)
            .putLong(KEY_USER_STATUS_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    override fun getUserStatusTimestamp(): Long {
        return prefs.getLong(KEY_USER_STATUS_TIMESTAMP, 0L)
    }

    override fun getPeerStatus(peerAddress: String): String? {
        return peerStatusPrefs.getString(peerAddress, null)
    }

    override fun setPeerStatus(peerAddress: String, status: String) {
        peerStatusPrefs.edit().putString(peerAddress, status).apply()
    }

    override fun getAllPeerStatuses(): Map<String, String> {
        return peerStatusPrefs.all
            .filterValues { it is String }
            .mapValues { it.value as String }
    }

    // ==========================================
    // MEMO TEMPLATES IMPLEMENTATION
    // ==========================================

    override fun getCustomMemoTemplates(): List<String> {
        return getAllCustomTemplateJson().toList()
    }

    override fun saveCustomMemoTemplate(templateJson: String) {
        val current = getAllCustomTemplateJson().toMutableSet()
        // Remove any existing template with same ID (update)
        val templateId = extractTemplateId(templateJson)
        if (templateId != null) {
            current.removeAll { extractTemplateId(it) == templateId }
        }
        current.add(templateJson)
        prefs.edit().putStringSet(KEY_CUSTOM_MEMO_TEMPLATES, current).apply()
    }

    override fun removeCustomMemoTemplate(templateId: String) {
        val current = getAllCustomTemplateJson().toMutableSet()
        current.removeAll { extractTemplateId(it) == templateId }
        prefs.edit().putStringSet(KEY_CUSTOM_MEMO_TEMPLATES, current).apply()
    }

    override fun getAllCustomTemplateJson(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_MEMO_TEMPLATES, emptySet()) ?: emptySet()
    }

    /**
     * Extract template ID from JSON string (simple parsing).
     * Format expected: {"id":"...", ...}
     */
    private fun extractTemplateId(json: String): String? {
        val regex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    // ==========================================
    // FONT SIZE IMPLEMENTATION
    // ==========================================

    override fun getFontSizeScale(): Float {
        return prefs.getFloat(KEY_FONT_SIZE_SCALE, 1.0f)
    }

    override fun setFontSizeScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE_SCALE, scale).apply()
    }

    // ==========================================
    // CONTACT NICKNAMES IMPLEMENTATION
    // ==========================================

    override fun getNickname(address: String): String? {
        return nicknamePrefs.getString(address, null)
    }

    override fun setNickname(address: String, nickname: String) {
        if (nickname.isBlank()) {
            // Clear nickname if empty
            nicknamePrefs.edit().remove(address).apply()
        } else {
            nicknamePrefs.edit().putString(address, nickname.trim()).apply()
        }
    }

    override fun getDisplayName(address: String): String {
        // Return nickname if set, otherwise truncate address
        val nickname = getNickname(address)
        if (!nickname.isNullOrBlank()) {
            return nickname
        }
        // Truncate: first 8 chars + "..." + last 6 chars
        return if (address.length > 20) {
            "${address.take(8)}...${address.takeLast(6)}"
        } else {
            address
        }
    }

    override fun getAllNicknames(): Map<String, String> {
        return nicknamePrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun markFileViewed(fileHash: String) {
        viewOncePrefs.edit().putString(fileHash, "1").apply()
    }

    override fun isFileViewed(fileHash: String): Boolean =
        viewOncePrefs.contains(fileHash)

    override fun getAllViewedFiles(): Set<String> = viewOncePrefs.all.keys

    // --- Conversation mode ---

    private fun modeKey(peer: String) = "mode:$peer"
    private fun modeExplicitKey(peer: String) = "modeexplicit:$peer"
    private fun modeSinceKey(peer: String) = "modesince:$peer"
    private fun pubkeyKey(peer: String) = "pubkey:$peer"
    private fun relayKey(peer: String) = "relay:$peer"
    private fun bootSentKey(peer: String) = "bootsent:$peer"
    private val keyHasSeenModeIntro = "hasSeenModeIntro"

    override fun getConversationMode(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode {
        val name = modePrefs.getString(modeKey(peerAddress), null) ?: return co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.DEFAULT
        return runCatching { co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.valueOf(name) }
            .getOrDefault(co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.DEFAULT)
    }

    override fun getConversationModeOrNull(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode? {
        val name = modePrefs.getString(modeKey(peerAddress), null) ?: return null
        return runCatching { co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.valueOf(name) }.getOrNull()
    }

    override fun setConversationMode(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?) {
        modePrefs.edit().apply {
            if (mode == null) {
                remove(modeKey(peerAddress))
                // Clearing the mode also clears the "user explicitly chose this" pin — a cleared chat
                // reverts to full default behavior (smart defaults, auto-upgrade, ZMODE auto-adopt).
                remove(modeExplicitKey(peerAddress))
            } else {
                putString(modeKey(peerAddress), mode.name)
            }
        }.apply()
    }

    override fun isConversationModeExplicit(peerAddress: String): Boolean =
        modePrefs.getBoolean(modeExplicitKey(peerAddress), false)

    override fun setConversationModeExplicit(peerAddress: String) {
        modePrefs.edit().putBoolean(modeExplicitKey(peerAddress), true).apply()
    }

    @Synchronized
    override fun advancePeerModeChangeSince(peerAddress: String, sinceMillis: Long): Boolean {
        // Monotonic: reject a non-newer since → last-writer-wins + idempotent under relay-replay/rescan.
        if (sinceMillis <= modePrefs.getLong(modeSinceKey(peerAddress), 0L)) return false
        modePrefs.edit().putLong(modeSinceKey(peerAddress), sinceMillis).apply()
        return true
    }

    private fun modeNoteKey(peer: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode) = "modenote:$peer:${mode.name}"
    override fun hasSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode): Boolean =
        modePrefs.getBoolean(modeNoteKey(peerAddress, mode), false)
    override fun setSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode) {
        modePrefs.edit().putBoolean(modeNoteKey(peerAddress, mode), true).apply()
    }

    override fun getPeerNostrPubkey(peerAddress: String): String? = modePrefs.getString(pubkeyKey(peerAddress), null)
    override fun setPeerNostrPubkey(peerAddress: String, pubkeyHex: String?) {
        modePrefs.edit().apply {
            if (pubkeyHex == null) remove(pubkeyKey(peerAddress)) else putString(pubkeyKey(peerAddress), pubkeyHex)
        }.apply()
    }

    override fun findPeerByNostrPubkey(pubkeyHex: String): String? {
        // Scan modePrefs.all for "pubkey:<peer>" entries; typically O(small).
        val target = pubkeyHex.lowercase()
        for ((key, value) in modePrefs.all) {
            if (key.startsWith("pubkey:") && (value as? String)?.lowercase() == target) {
                return key.removePrefix("pubkey:")
            }
        }
        return null
    }

    override fun getTunnelModePeers(): List<String> =
        modePrefs.all.mapNotNull { (key, value) ->
            key.takeIf {
                it.startsWith("mode:") &&
                    value == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL.name
            }?.removePrefix("mode:")
        }

    override fun hasSeenNostrEvent(eventId: String): Boolean =
        synchronized(nostrSeenLock) { nostrSeenIds.contains(eventId) }

    override fun markNostrEventSeen(eventId: String) {
        synchronized(nostrSeenLock) {
            // Re-insert moves nothing if already present; we only persist on a genuine first sighting.
            if (!nostrSeenIds.add(eventId)) return
            // LRU eviction: LinkedHashSet keeps insertion order, so the iterator head is the oldest.
            while (nostrSeenIds.size > MAX_SEEN_NOSTR_EVENTS) {
                val oldest = nostrSeenIds.iterator().next()
                nostrSeenIds.remove(oldest)
            }
            nostrSeenPrefs.edit().putString(NOSTR_SEEN_KEY, nostrSeenIds.joinToString("\n")).apply()
        }
    }

    override fun unmarkNostrEventSeen(eventId: String) {
        synchronized(nostrSeenLock) {
            if (nostrSeenIds.remove(eventId)) {
                nostrSeenPrefs.edit().putString(NOSTR_SEEN_KEY, nostrSeenIds.joinToString("\n")).apply()
            }
        }
    }

    override fun hasProcessedKexTx(txId: String): Boolean =
        synchronized(kexSeenLock) { kexSeenIds.contains(txId) }

    override fun markKexTxProcessed(txId: String) {
        synchronized(kexSeenLock) {
            if (!kexSeenIds.add(txId)) return
            while (kexSeenIds.size > MAX_SEEN_KEX_TXS) {
                val oldest = kexSeenIds.iterator().next()
                kexSeenIds.remove(oldest)
            }
            kexSeenPrefs.edit().putString(KEX_SEEN_KEY, kexSeenIds.joinToString("\n")).apply()
        }
    }

    // ----- #224 inbound OPEN contact requests (+ blocked-pubkey set) -----

    override fun addMessageRequest(request: ZchatPreferences.MessageRequest) {
        if (request.senderNostrPubkeyHex.isBlank()) return
        synchronized(messageRequestLock) {
            val json = org.json.JSONObject().apply {
                put("pubkey", request.senderNostrPubkeyHex)
                put("address", request.senderAddress)
                put("relay", request.relayUrl)
                put("message", request.firstMessage)
                put("timestampMillis", request.timestampMillis)
                put("eventId", request.eventId)
                put("rumorId", request.rumorId)
            }
            val editor = messageRequestPrefs.edit()
            editor.putString(REQ_KEY_PREFIX + request.senderNostrPubkeyHex, json.toString())
            // LRU eviction: if over cap, drop the oldest request(s) by timestamp. Use the EFFECTIVE
            // post-write count — when this pubkey already has a pending request the put REPLACES it, so
            // the count doesn't grow and no eviction is needed. `all.size + 1` over-counted that case and
            // evicted an unrelated user's oldest pending request that should have stayed.
            val all = loadMessageRequestsUnlocked()
            val effective = all.size + if (all.any { it.senderNostrPubkeyHex == request.senderNostrPubkeyHex }) 0 else 1
            if (effective > MAX_MESSAGE_REQUESTS) {
                all.sortedBy { it.timestampMillis }
                    .take(effective - MAX_MESSAGE_REQUESTS)
                    .forEach { if (it.senderNostrPubkeyHex != request.senderNostrPubkeyHex) editor.remove(REQ_KEY_PREFIX + it.senderNostrPubkeyHex) }
            }
            editor.apply()
        }
    }

    override fun getMessageRequests(): List<ZchatPreferences.MessageRequest> =
        synchronized(messageRequestLock) { loadMessageRequestsUnlocked().sortedByDescending { it.timestampMillis } }

    private fun loadMessageRequestsUnlocked(): List<ZchatPreferences.MessageRequest> {
        val result = mutableListOf<ZchatPreferences.MessageRequest>()
        for ((key, value) in messageRequestPrefs.all) {
            if (!key.startsWith(REQ_KEY_PREFIX) || value !is String) continue
            try {
                val json = org.json.JSONObject(value)
                result.add(
                    ZchatPreferences.MessageRequest(
                        senderNostrPubkeyHex = json.getString("pubkey"),
                        senderAddress = json.getString("address"),
                        relayUrl = json.optString("relay", ""),
                        firstMessage = json.optString("message", ""),
                        timestampMillis = json.optLong("timestampMillis", 0L),
                        eventId = json.optString("eventId", ""),
                        rumorId = json.optString("rumorId", ""),
                    )
                )
            } catch (e: Exception) {
                Log.w("ZchatPreferences", "Failed to parse message request: $key", e)
            }
        }
        return result
    }

    override fun removeMessageRequest(senderNostrPubkeyHex: String) {
        synchronized(messageRequestLock) {
            messageRequestPrefs.edit().remove(REQ_KEY_PREFIX + senderNostrPubkeyHex).apply()
        }
    }

    override fun isNostrPubkeyBlocked(pubkeyHex: String): Boolean {
        if (pubkeyHex.isBlank()) return false
        synchronized(messageRequestLock) {
            val raw = messageRequestPrefs.getString(BLOCKED_PUBKEYS_KEY, null) ?: return false
            return raw.split("\n").contains(pubkeyHex.lowercase())
        }
    }

    override fun blockNostrPubkey(pubkeyHex: String) {
        if (pubkeyHex.isBlank()) return
        synchronized(messageRequestLock) {
            val raw = messageRequestPrefs.getString(BLOCKED_PUBKEYS_KEY, null)
            val set = LinkedHashSet(raw?.split("\n")?.filter { it.isNotBlank() } ?: emptyList())
            if (!set.add(pubkeyHex.lowercase())) return
            while (set.size > MAX_BLOCKED_PUBKEYS) {
                val it = set.iterator(); it.next(); it.remove()
            }
            messageRequestPrefs.edit().putString(BLOCKED_PUBKEYS_KEY, set.joinToString("\n")).apply()
        }
    }

    override fun registerSelfAddress(address: String) {
        if (address.isBlank()) return
        val hash = ZMSGProtocol.generateAddressHash(address)
        synchronized(selfAddrLock) {
            val current = prefs.getStringSet(SELF_ADDR_HASHES_KEY, emptySet()) ?: emptySet()
            if (current.contains(hash)) return
            val updated = LinkedHashSet(current)
            updated.add(hash)
            // Bounded: a single wallet only ever presents a few representations; cap defends against
            // unbounded growth if some path keeps feeding fresh diversified addresses.
            while (updated.size > MAX_SELF_ADDR_HASHES) {
                val iterator = updated.iterator()
                iterator.next()
                iterator.remove()
            }
            prefs.edit().putStringSet(SELF_ADDR_HASHES_KEY, updated).apply()
        }
    }

    override fun isSelfAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val hash = ZMSGProtocol.generateAddressHash(address)
        val current = prefs.getStringSet(SELF_ADDR_HASHES_KEY, emptySet()) ?: emptySet()
        return current.contains(hash)
    }

    override fun getPeerNostrRelay(peerAddress: String): String? = modePrefs.getString(relayKey(peerAddress), null)
    override fun setPeerNostrRelay(peerAddress: String, relayUrl: String?) {
        modePrefs.edit().apply {
            if (relayUrl == null) remove(relayKey(peerAddress)) else putString(relayKey(peerAddress), relayUrl)
        }.apply()
    }

    private fun bootEpochKey(peer: String) = "bootepoch:$peer"
    override fun getPeerBootEpoch(peerAddress: String): Long = modePrefs.getLong(bootEpochKey(peerAddress), 0L)
    override fun setPeerBootEpoch(peerAddress: String, epoch: Long) {
        modePrefs.edit().putLong(bootEpochKey(peerAddress), epoch).apply()
    }

    override fun isOwnBootSent(peerAddress: String): Boolean = modePrefs.getBoolean(bootSentKey(peerAddress), false)
    override fun setOwnBootSent(peerAddress: String, sent: Boolean) {
        modePrefs.edit().putBoolean(bootSentKey(peerAddress), sent).apply()
        bumpE2EHandshakeTick()
    }

    private fun peerKnownOurRotKey(peer: String) = "peerknownourrot:$peer"
    override fun getPeerKnownOurRotationIndex(peerAddress: String): Int =
        modePrefs.getInt(peerKnownOurRotKey(peerAddress), -1)
    override fun setPeerKnownOurRotationIndex(peerAddress: String, index: Int) {
        modePrefs.edit().putInt(peerKnownOurRotKey(peerAddress), index).apply()
    }

    private fun sentNostrBootKey(peer: String) = "sentnostrboot:$peer"
    override fun getSentNostrBootPubkey(peerAddress: String): String? =
        modePrefs.getString(sentNostrBootKey(peerAddress), null)
    override fun setSentNostrBootPubkey(peerAddress: String, pubkeyHex: String?) {
        modePrefs.edit().apply {
            if (pubkeyHex == null) remove(sentNostrBootKey(peerAddress)) else putString(sentNostrBootKey(peerAddress), pubkeyHex)
        }.apply()
    }

    private fun receivedKexKey(peer: String) = "receivedkex:$peer"
    override fun getReceivedKexPubkey(peerAddress: String): String? =
        modePrefs.getString(receivedKexKey(peerAddress), null)
    override fun setReceivedKexPubkey(peerAddress: String, pubkeyHex: String?) {
        modePrefs.edit().apply {
            if (pubkeyHex == null) remove(receivedKexKey(peerAddress)) else putString(receivedKexKey(peerAddress), pubkeyHex)
        }.apply()
        bumpE2EHandshakeTick()
    }

    private fun sentKexAckKey(peer: String) = "sentkexack:$peer"
    override fun getSentKexAckPubkey(peerAddress: String): String? =
        modePrefs.getString(sentKexAckKey(peerAddress), null)
    override fun setSentKexAckPubkey(peerAddress: String, pubkeyHex: String?) {
        modePrefs.edit().apply {
            if (pubkeyHex == null) remove(sentKexAckKey(peerAddress)) else putString(sentKexAckKey(peerAddress), pubkeyHex)
        }.apply()
        bumpE2EHandshakeTick()
    }

    override fun hasSeenModeIntro(): Boolean = modePrefs.getBoolean(keyHasSeenModeIntro, false)
    override fun setHasSeenModeIntro(seen: Boolean) {
        modePrefs.edit().putBoolean(keyHasSeenModeIntro, seen).apply()
    }

    // ==========================================
    // DESTROY / REMOTE KILL IMPLEMENTATION
    // ==========================================
    //
    // Storage:
    //   PIN hash, kill-phrase hash, format-v2 flags, and rate-limit counters live in
    //   `e2ePrefs` (EncryptedSharedPreferences, AES-256-GCM via Android Keystore). The app
    //   already requires Keystore for the E2E ratchet state in this same store, so making
    //   PIN/phrase keys depend on Keystore introduces no new failure mode.
    //
    //   Pre-v3 installs stored PIN + flags in plain `prefs`. On first access after upgrade,
    //   [migrateDestroyStorageIfNeeded] copies everything into `e2ePrefs` and sets a marker
    //   key (also in `e2ePrefs`) so the migration runs exactly once. Old plain keys are NOT
    //   immediately deleted — they remain readable as a downgrade-safety net until the next
    //   successful write replaces them with empty values.
    private val destroyStore get(): SharedPreferences {
        migrateDestroyStorageIfNeeded()
        return e2ePrefs
    }

    private fun migrateDestroyStorageIfNeeded() {
        if (e2ePrefs.getBoolean(KEY_DESTROY_STORAGE_V3, false)) return
        val editor = e2ePrefs.edit()
        prefs.getString(KEY_DESTROY_PIN, null)?.let { editor.putString(KEY_DESTROY_PIN, it) }
        if (prefs.contains(KEY_DESTROY_PIN_FORMAT_V2)) {
            editor.putBoolean(KEY_DESTROY_PIN_FORMAT_V2, prefs.getBoolean(KEY_DESTROY_PIN_FORMAT_V2, false))
        }
        prefs.getString(KEY_REMOTE_KILL_PHRASE_HASH, null)?.let { editor.putString(KEY_REMOTE_KILL_PHRASE_HASH, it) }
        if (prefs.contains(KEY_REMOTE_KILL_PHRASE_FORMAT_V2)) {
            editor.putBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, prefs.getBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, false))
        }
        if (prefs.contains(KEY_PIN_FAIL_COUNT)) editor.putInt(KEY_PIN_FAIL_COUNT, prefs.getInt(KEY_PIN_FAIL_COUNT, 0))
        if (prefs.contains(KEY_PIN_VIOLATIONS)) editor.putInt(KEY_PIN_VIOLATIONS, prefs.getInt(KEY_PIN_VIOLATIONS, 0))
        if (prefs.contains(KEY_PIN_LOCKOUT_ELAPSED)) editor.putLong(KEY_PIN_LOCKOUT_ELAPSED, prefs.getLong(KEY_PIN_LOCKOUT_ELAPSED, 0L))
        if (prefs.contains(KEY_PIN_LOCKOUT_WALL)) editor.putLong(KEY_PIN_LOCKOUT_WALL, prefs.getLong(KEY_PIN_LOCKOUT_WALL, 0L))
        // commit() — synchronous so the marker is durable before any subsequent verify can race.
        editor.putBoolean(KEY_DESTROY_STORAGE_V3, true).commit()
    }

    override suspend fun setDestroyPin(pin: String) {
        // Store PBKDF2 hash in encrypted prefs. Set v2 flag atomically.
        val hashed = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.hashAsync(pin)
        destroyStore.edit()
            .putString(KEY_DESTROY_PIN, hashed)
            .putBoolean(KEY_DESTROY_PIN_FORMAT_V2, true)
            .apply()
        // Wipe any legacy plain-prefs copy left over from pre-v3 installs so the only PIN hash
        // on disk is the encrypted one.
        if (prefs.contains(KEY_DESTROY_PIN)) {
            prefs.edit().remove(KEY_DESTROY_PIN).remove(KEY_DESTROY_PIN_FORMAT_V2).apply()
        }
    }

    override suspend fun verifyDestroyPin(pin: String): Boolean {
        // Boolean shim — delegates to the lockout-aware variant. Lockouts surface as `false`
        // here so existing call sites that ignore lockouts still get a fail-safe response.
        return verifyDestroyPinWithLockout(pin) is DestroyPinVerifyResult.Success
    }

    override suspend fun verifyDestroyPinWithLockout(pin: String): DestroyPinVerifyResult {
        val store = destroyStore
        val storedHash = store.getString(KEY_DESTROY_PIN, null)
            ?: return DestroyPinVerifyResult.Failed(attemptsRemaining = 0)
        val v2 = store.getBoolean(KEY_DESTROY_PIN_FORMAT_V2, false)
        val isLegacy = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.isLegacyFormat(storedHash)
        // Downgrade defense: once v2 is set, refuse legacy format outright.
        if (v2 && isLegacy) return DestroyPinVerifyResult.Failed(attemptsRemaining = 0)

        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val state = readPinAttemptState()
        val remaining = co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy
            .remainingLockoutMillis(state, nowElapsed, nowWall)
        if (remaining > 0L) return DestroyPinVerifyResult.LockedOut(remainingMillis = remaining)

        val matches = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.verifyAsync(pin, storedHash)
        if (matches) {
            // Clear counter on success.
            writePinAttemptState(co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.onSuccess())
            // Auto-upgrade legacy hash on the first successful verify (sets v2 atomically).
            if (isLegacy) setDestroyPin(pin)
            return DestroyPinVerifyResult.Success
        }
        val nextState = co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy
            .onFailure(state, nowElapsed, nowWall)
        writePinAttemptState(nextState)
        val newRemaining = co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy
            .remainingLockoutMillis(nextState, nowElapsed, nowWall)
        return if (newRemaining > 0L) {
            DestroyPinVerifyResult.LockedOut(remainingMillis = newRemaining)
        } else {
            val remainAttempts = (co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.MAX_ATTEMPTS - nextState.failedAttempts)
                .coerceAtLeast(0)
            DestroyPinVerifyResult.Failed(attemptsRemaining = remainAttempts)
        }
    }

    private fun readPinAttemptState(): co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.State {
        val store = destroyStore
        return co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.State(
            failedAttempts = store.getInt(KEY_PIN_FAIL_COUNT, 0),
            violations = store.getInt(KEY_PIN_VIOLATIONS, 0),
            lockoutUntilElapsed = store.getLong(KEY_PIN_LOCKOUT_ELAPSED, 0L),
            lockoutUntilWall = store.getLong(KEY_PIN_LOCKOUT_WALL, 0L),
        )
    }

    private fun writePinAttemptState(state: co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.State) {
        destroyStore.edit()
            .putInt(KEY_PIN_FAIL_COUNT, state.failedAttempts)
            .putInt(KEY_PIN_VIOLATIONS, state.violations)
            .putLong(KEY_PIN_LOCKOUT_ELAPSED, state.lockoutUntilElapsed)
            .putLong(KEY_PIN_LOCKOUT_WALL, state.lockoutUntilWall)
            .apply()
    }

    override fun hasDestroyPin(): Boolean {
        return destroyStore.getString(KEY_DESTROY_PIN, null) != null
    }

    override fun clearDestroyPin() {
        // Remove the PIN hash + format flag AND reset the attempt/lockout counters so a fresh setup
        // starts clean. destroyStore = e2ePrefs (migration runs via the getter).
        destroyStore.edit()
            .remove(KEY_DESTROY_PIN)
            .remove(KEY_DESTROY_PIN_FORMAT_V2)
            .remove(KEY_PIN_FAIL_COUNT)
            .remove(KEY_PIN_VIOLATIONS)
            .remove(KEY_PIN_LOCKOUT_ELAPSED)
            .remove(KEY_PIN_LOCKOUT_WALL)
            .apply()
        // Scrub any leftover legacy plain-prefs copy too, so no stale hash can resurrect via migration.
        if (prefs.contains(KEY_DESTROY_PIN)) {
            prefs.edit().remove(KEY_DESTROY_PIN).remove(KEY_DESTROY_PIN_FORMAT_V2).apply()
        }
    }

    override fun isRemoteKillEnabled(): Boolean {
        return prefs.getBoolean(KEY_REMOTE_KILL_ENABLED, false)
    }

    override fun setRemoteKillEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMOTE_KILL_ENABLED, enabled).apply()
    }

    override suspend fun verifyRemoteKillPhrase(phrase: String): Boolean {
        val store = destroyStore
        val storedHash = store.getString(KEY_REMOTE_KILL_PHRASE_HASH, null) ?: return false
        val v2 = store.getBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, false)
        val isLegacy = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.isLegacyFormat(storedHash)
        // Downgrade defense: once v2 is set, refuse legacy format outright. Verify-only path
        // — kill path should destroy, not re-hash.
        if (v2 && isLegacy) return false
        return co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.verifyAsync(phrase, storedHash)
    }

    override suspend fun setRemoteKillPhrase(phrase: String) {
        // Store PBKDF2 hash in encrypted prefs. Set v2 flag atomically.
        val hashed = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.hashAsync(phrase)
        destroyStore.edit()
            .putString(KEY_REMOTE_KILL_PHRASE_HASH, hashed)
            .putBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, true)
            .apply()
        // Wipe legacy plain copy.
        if (prefs.contains(KEY_REMOTE_KILL_PHRASE_HASH)) {
            prefs.edit().remove(KEY_REMOTE_KILL_PHRASE_HASH).remove(KEY_REMOTE_KILL_PHRASE_FORMAT_V2).apply()
        }
    }

    override fun hasRemoteKillPhrase(): Boolean {
        return destroyStore.getString(KEY_REMOTE_KILL_PHRASE_HASH, null) != null
    }

    override fun getRemoteKillAmount(): Long {
        return prefs.getLong(KEY_REMOTE_KILL_AMOUNT, DEFAULT_REMOTE_KILL_AMOUNT)
    }

    override fun setRemoteKillAmount(amountZatoshi: Long) {
        prefs.edit().putLong(KEY_REMOTE_KILL_AMOUNT, amountZatoshi).apply()
    }

    override fun getDecryptedText(ciphertextHash: String): String? =
        decryptedTextPrefs.getString(ciphertextHash, null)

    override fun putDecryptedText(ciphertextHash: String, plaintext: String) {
        decryptedTextPrefs.edit().putString(ciphertextHash, plaintext).apply()
    }

    override fun removeDecryptedText(ciphertextHash: String) {
        decryptedTextPrefs.edit().remove(ciphertextHash).apply()
    }

    @Synchronized
    override fun getOrPutPendingTxFirstSeenMillis(txId: String, nowMillis: Long): Long {
        val key = "pending_tx_first_seen_$txId"
        val existing = prefs.getLong(key, -1L)
        if (existing > 0L) return existing
        prefs.edit().putLong(key, nowMillis).apply()
        return nowMillis
    }
    override fun clearPendingTxFirstSeen(txId: String) {
        prefs.edit().remove("pending_tx_first_seen_$txId").apply()
    }

    override fun clearAll() {
        // Use commit() instead of apply() for security-critical clear operations.
        // If the app is killed before async apply() completes, sensitive data persists.
        prefs.edit().clear().commit()
        // #226: reset the shared read-marker flow to match the wiped store. The disappearing-TTL flow is
        // the same kind of process-wide in-memory mirror backed by `prefs` (just cleared above) — reset it
        // too, otherwise it keeps serving the previous identity's TTLs after a reset.
        _readMarkers.value = emptyMap()
        _disappearingTtls.value = emptyMap()
        decryptedTextPrefs.edit().clear().commit()
        peerStatusPrefs.edit().clear().commit()
        convMappingPrefs.edit().clear().commit()
        nicknamePrefs.edit().clear().commit()
        draftPrefs.edit().clear().commit()
        e2ePrefs.edit().clear().commit()
        // Group chat prefs
        groupInfoPrefs.edit().clear().commit()
        groupMembersPrefs.edit().clear().commit()
        groupKeysPrefs.edit().clear().commit()
        groupDraftPrefs.edit().clear().commit()
        groupSeqPrefs.edit().clear().commit()
        groupMsgPrefs.edit().clear().commit()
        // Pending messages
        pendingMsgPrefs.edit().clear().commit()
        callLogPrefs.edit().clear().commit()
        // Unroutable messages
        unroutableMsgPrefs.edit().clear().commit()
        // Conversation modes AND the NOSTR identity keys (pubkey/relay/bootsent) live here —
        // both are sensitive and must be wiped on destroy/reset, not just by the destroy
        // file-nuke backstop.
        modePrefs.edit().clear().commit()
        // CRITICAL: the Double-Ratchet session state (root/chain keys = E2E forward-secrecy
        // material) lives in ratchetPrefs. It MUST be wiped on destroy/reset, not left to the
        // best-effort file-nuke. Also clear the view-once consumed-file markers.
        ratchetPrefs.edit().clear().commit()
        viewOncePrefs.edit().clear().commit()
        // #188 persistent NOSTR replay-dedup set — not sensitive, but it must not survive a wipe or a
        // fresh wallet would carry the previous identity's seen-event history. Same destroy/reset
        // principle as the rest: clear it here so the targeted clear is complete, not file-nuke-only.
        nostrSeenPrefs.edit().clear().commit()
        // #201 processed-KEX-txid dedup set — same wipe-on-destroy principle as nostrSeenPrefs.
        kexSeenPrefs.edit().clear().commit()
        // The two seen-sets above also have in-memory LinkedHashSet mirrors. Clearing only the disk file
        // leaves the mirrors populated, so the next markNostrEventSeen/markKexTxProcessed re-serializes the
        // ENTIRE stale set straight back to disk — resurrecting the previous identity's seen-event history
        // that #188 says must not survive. Clear the mirrors under their locks too.
        synchronized(nostrSeenLock) { nostrSeenIds.clear() }
        synchronized(kexSeenLock) { kexSeenIds.clear() }
        // #210 NOSTR reactions hold peer sender addresses + target message-id links — conversation-graph
        // metadata that must not survive a destroy/reset. (Was the only store missing from this wipe.)
        reactionPrefs.edit().clear().commit()
        // #224 inbound OPEN contact requests hold a claimed address + first-message plaintext, plus the
        // blocked-pubkey set — sensitive, must be wiped on destroy/reset.
        messageRequestPrefs.edit().clear().commit()
    }

    // ==========================================
    // CONVERSATION IDs IMPLEMENTATION
    // ==========================================

    override fun getConversationId(peerAddress: String): String? {
        val result = convMappingPrefs.getString("peer:$peerAddress", null)
        android.util.Log.d("ZCHAT_CONVID", "getConversationId(${peerAddress.redactAddress()}) = ${result?.redactConvId()}")
        return result
    }

    override fun setConversationId(peerAddress: String, convId: String) {
        // Validate inputs to prevent storage corruption
        if (peerAddress.isBlank()) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationId: REJECTED blank peerAddress")
            return
        }
        if (convId.length != 8 || !convId.all { it in 'A'..'Z' || it in '0'..'9' }) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationId: REJECTED invalid convId format: ${convId.redactConvId()}")
            return
        }
        android.util.Log.d("ZCHAT_CONVID", "setConversationId: peer=${peerAddress.redactAddress()}, convId=${convId.redactConvId()}")
        // Write both directions. Do NOT delete old conv:X entries — they may be
        // the remote device's convId which is still needed for routing incoming messages.
        synchronized(this) {
            val editor = convMappingPrefs.edit()
                .putString("peer:$peerAddress", convId)
                .putString("conv:$convId", peerAddress)
            val success = editor.commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "FAILED to write convId mapping!")
            }
        }
    }

    override fun getOrCreateConversationId(peerAddress: String): Pair<String, Boolean> {
        synchronized(this) {
            val existing = convMappingPrefs.getString("peer:$peerAddress", null)
            if (existing != null) {
                android.util.Log.d("ZCHAT_CONVID", "getOrCreateConversationId(${peerAddress.redactAddress()}) = existing ${existing.redactConvId()}")
                return existing to false
            }
            val newId = ZMSGProtocol.generateConversationId()
            val editor = convMappingPrefs.edit()
                .putString("peer:$peerAddress", newId)
                .putString("conv:$newId", peerAddress)
            val success = editor.commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "getOrCreateConversationId: FAILED to write!")
            }
            android.util.Log.d("ZCHAT_CONVID", "getOrCreateConversationId(${peerAddress.redactAddress()}) = new ${newId.redactConvId()}")
            return newId to true
        }
    }

    override fun getPeerByConversationId(convId: String): String? {
        val result = convMappingPrefs.getString("conv:$convId", null)
        android.util.Log.d("ZCHAT_CONVID", "getPeerByConversationId(${convId.redactConvId()}) = ${result?.redactAddress()}")

        // Log bidirectional inconsistency but do NOT auto-repair here.
        // Auto-repair in a read path is destructive: it can clobber newer mappings
        // written by setConversationId (e.g., after convId renegotiation).
        // The validateAndRepairConvIdMappings() function handles repair at startup.
        if (result != null) {
            val reverseConvId = convMappingPrefs.getString("peer:$result", null)
            if (reverseConvId != convId) {
                android.util.Log.w("ZCHAT_CONVID", "Inconsistent mapping detected (read-only, not repairing): convId=${convId.redactConvId()} peer=${result.redactAddress()} reverseConvId=${reverseConvId?.redactConvId()}")
            }
        }
        return result
    }

    override fun setConversationMapping(convId: String, peerAddress: String) {
        // Validate inputs to prevent storage corruption
        if (convId.length != 8 || !convId.all { it in 'A'..'Z' || it in '0'..'9' }) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationMapping: REJECTED invalid convId format: ${convId.redactConvId()}")
            return
        }
        if (peerAddress.isBlank()) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationMapping: REJECTED blank peerAddress")
            return
        }
        android.util.Log.d("ZCHAT_CONVID", "setConversationMapping: convId=${convId.redactConvId()}, peer=${peerAddress.redactAddress()}")
        // Write ONLY the conv→peer direction. A peer can have multiple convIds
        // (one generated locally for sending, one received from the remote device).
        // The peer→convId direction is managed exclusively by setConversationId()
        // and getOrCreateConversationId() for OUR outgoing convId.
        // NEVER delete old conv:X entries here — they may belong to the remote side.
        synchronized(this) {
            val editor = convMappingPrefs.edit()
                .putString("conv:$convId", peerAddress)
            // Only set peer→convId if no mapping exists yet (don't overwrite our own convId)
            val existingConvId = convMappingPrefs.getString("peer:$peerAddress", null)
            if (existingConvId == null) {
                editor.putString("peer:$peerAddress", convId)
            }
            val success = editor.commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "FAILED to write convId mapping!")
            }
        }
    }

    override fun getAllConversationMappings(): Map<String, String> {
        return convMappingPrefs.all
            .filterKeys { it.startsWith("conv:") }
            .filterValues { it is String }
            .mapKeys { it.key.removePrefix("conv:") }
            .mapValues { it.value as String }
    }

    override fun getAllPeerToConvIdMappings(): Map<String, String> {
        return convMappingPrefs.all
            .filterKeys { it.startsWith("peer:") }
            .filterValues { it is String }
            .mapKeys { it.key.removePrefix("peer:") }
            .mapValues { it.value as String }
    }

    override fun removeConversationMapping(convId: String) {
        synchronized(this) {
            convMappingPrefs.edit()
                .remove("conv:$convId")
                .commit()
        }
    }

    override fun setPeerAddressAlias(repAddress: String, canonicalAddress: String) {
        if (repAddress.isBlank() || canonicalAddress.isBlank() || repAddress == canonicalAddress) return
        // FIXED-POINT INVARIANT: the canonical (live receive) address must resolve to ITSELF, so it can
        // never itself be an alias key. Removing alias:$canonicalAddress here breaks the circular pair
        // that an earlier, wrong-direction alias could leave (live->stale AND stale->live) — which made
        // resolvePeerAddress non-deterministic and could canonicalize a peer's LIVE address back to a
        // stale rep its wallet no longer scans (#218). Stored in the conv-mapping file under "alias:".
        synchronized(this) {
            convMappingPrefs.edit()
                .remove("alias:$canonicalAddress")
                .putString("alias:$repAddress", canonicalAddress)
                .commit()
        }
    }

    override fun clearPeerAddressAlias(address: String) {
        if (address.isBlank()) return
        // Assert [address] is canonical (a fixed point): drop any alias that would map it elsewhere.
        synchronized(this) {
            convMappingPrefs.edit().remove("alias:$address").commit()
        }
    }

    override fun resolvePeerAddress(address: String): String {
        if (address.isBlank()) return address
        val target = convMappingPrefs.getString("alias:$address", null) ?: return address
        // One hop only, but guard the degenerate self-cycle so we never loop or return a known-stale rep.
        return if (target == address) address else target
    }

    // ==========================================
    // PENDING MESSAGES IMPLEMENTATION
    // ==========================================

    override fun getPendingMessages(): List<ZchatPreferences.PendingMessageData> {
        val result = mutableListOf<ZchatPreferences.PendingMessageData>()
        for ((key, value) in pendingMsgPrefs.all) {
            if (value is String) {
                try {
                    // Parse JSON: {"id":"...","text":"...","timestampMillis":123,"peerAddress":"..."}
                    val json = org.json.JSONObject(value)
                    result.add(
                        ZchatPreferences.PendingMessageData(
                            id = json.getString("id"),
                            text = json.getString("text"),
                            timestampMillis = json.getLong("timestampMillis"),
                            peerAddress = json.getString("peerAddress"),
                            // Legacy on-chain pending rows lack these keys → default to the
                            // outgoing/pending shape they were written with.
                            isOutgoing = json.optBoolean("isOutgoing", true),
                            isPending = json.optBoolean("isPending", true),
                            status = if (json.isNull("status")) null else json.getString("status"),
                            replyToId = if (json.isNull("replyToId")) null else json.getString("replyToId"),
                            replyToPreview = if (json.has("replyToPreview") && !json.isNull("replyToPreview")) json.getString("replyToPreview") else null,
                            fileZfileContent = if (json.isNull("fileZfileContent")) null else json.getString("fileZfileContent"),
                            paymentRequestAmountZatoshi = if (json.has("paymentRequestAmountZatoshi") && !json.isNull("paymentRequestAmountZatoshi")) json.getLong("paymentRequestAmountZatoshi") else null,
                            paymentRequestReason = if (json.has("paymentRequestReason") && !json.isNull("paymentRequestReason")) json.getString("paymentRequestReason") else null
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ZchatPreferences", "Failed to parse pending message: $key", e)
                }
            }
        }
        return result.sortedBy { it.timestampMillis }
    }

    override fun addPendingMessage(message: ZchatPreferences.PendingMessageData) {
        val json = org.json.JSONObject().apply {
            put("id", message.id)
            put("text", message.text)
            put("timestampMillis", message.timestampMillis)
            put("peerAddress", message.peerAddress)
            put("isOutgoing", message.isOutgoing)
            put("isPending", message.isPending)
            put("status", message.status)
            put("replyToId", message.replyToId)
            put("replyToPreview", message.replyToPreview)
            put("fileZfileContent", message.fileZfileContent)
            put("paymentRequestAmountZatoshi", message.paymentRequestAmountZatoshi)
            put("paymentRequestReason", message.paymentRequestReason)
        }
        pendingMsgPrefs.edit().putString(message.id, json.toString()).apply()
        Log.d("ZCHAT_PENDING", "Added pending message: ${message.id.take(8)}... to ${message.peerAddress.redactAddress()}")
    }

    override fun removePendingMessage(messageId: String) {
        pendingMsgPrefs.edit().remove(messageId).apply()
        Log.d("ZCHAT_PENDING", "Removed pending message: ${messageId.take(8)}...")
    }

    override fun removePendingMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        val editor = pendingMsgPrefs.edit()
        for (id in messageIds) {
            editor.remove(id)
        }
        editor.apply()
        Log.d("ZCHAT_PENDING", "Removed ${messageIds.size} pending messages")
    }

    // #210 NOSTR-reaction persistence. One newline-joined block per target id; each line is
    // emoji + U+001F + senderAddress + U+001F + timestampMillis. The unit separator never appears
    // in an emoji or a u1 address, so it is a safe field delimiter.
    override fun addNostrReaction(targetId: String, emoji: String, senderAddress: String, timestampMillis: Long) {
        if (targetId.isBlank() || emoji.isBlank()) return
        // The on-disk format is newline-joined "emoji<US>sender<US>ts" lines (US = U+001F). A peer-supplied emoji (or
        // sender) that embeds '\n' or U+001F would FORGE extra well-formed rows on the next parse — e.g. a
        // reaction attributed to a fabricated sender that bypasses the (emoji, sender) idempotency dedup.
        // Reject any value carrying a structural delimiter.
        if (emoji.any { it == '\n' || it == '\u001F' } || senderAddress.any { it == '\n' || it == '\u001F' }) return
        synchronized(reactionLock) {
            val existing = getNostrReactions(targetId)
            // Idempotent per (emoji, sender) so relay replays / multi-relay publishes do not stack.
            if (existing.any { it.emoji == emoji && it.senderAddress == senderAddress }) return
            val updated = (existing + ZchatPreferences.PersistedReaction(emoji, senderAddress, timestampMillis))
                .takeLast(MAX_REACTIONS_PER_TARGET)
            val serialized = updated.joinToString("\n") { "${it.emoji}\u001F${it.senderAddress}\u001F${it.timestampMillis}" }
            val editor = reactionPrefs.edit().putString(targetId, serialized)
            // Bound the number of distinct target keys so a peer flooding ZREACTs with fabricated
            // targetIds can't grow the prefs file without limit. Only runs when at/over cap AND this is
            // a NEW target; evicts the targets whose NEWEST reaction is oldest.
            val keys = reactionPrefs.all.keys
            if (targetId !in keys && keys.size >= MAX_REACTION_TARGETS) {
                keys.asSequence()
                    .filter { it != targetId }
                    .map { it to (getNostrReactions(it).maxOfOrNull { r -> r.timestampMillis } ?: 0L) }
                    .sortedBy { it.second }
                    .take(keys.size - MAX_REACTION_TARGETS + 1)
                    .forEach { editor.remove(it.first) }
            }
            editor.apply()
        }
    }

    override fun getNostrReactions(targetId: String): List<ZchatPreferences.PersistedReaction> {
        val raw = reactionPrefs.getString(targetId, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\u001F")
            if (parts.size != 3) return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            ZchatPreferences.PersistedReaction(parts[0], parts[1], ts)
        }
    }

    override fun clearPendingMessages() {
        pendingMsgPrefs.edit().clear().apply()
        Log.d("ZCHAT_PENDING", "Cleared all pending messages")
    }

    override fun getCallLogMessages(): List<ZchatPreferences.CallLogMessageData> {
        val result = mutableListOf<ZchatPreferences.CallLogMessageData>()
        for ((key, value) in callLogPrefs.all) {
            if (value is String) {
                try {
                    val json = org.json.JSONObject(value)
                    result.add(
                        ZchatPreferences.CallLogMessageData(
                            id = json.getString("id"),
                            peerAddress = json.getString("peerAddress"),
                            timestampMillis = json.getLong("timestampMillis"),
                            type = json.getString("type"),
                            isVideo = json.getBoolean("isVideo"),
                            durationSec = if (json.isNull("durationSec")) null else json.getLong("durationSec"),
                            isOutgoing = json.getBoolean("isOutgoing"),
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ZchatPreferences", "Failed to parse call log: $key", e)
                }
            }
        }
        return result.sortedBy { it.timestampMillis }
    }

    override fun addCallLogMessage(message: ZchatPreferences.CallLogMessageData) {
        val json = org.json.JSONObject().apply {
            put("id", message.id)
            put("peerAddress", message.peerAddress)
            put("timestampMillis", message.timestampMillis)
            put("type", message.type)
            put("isVideo", message.isVideo)
            put("durationSec", message.durationSec ?: org.json.JSONObject.NULL)
            put("isOutgoing", message.isOutgoing)
        }
        callLogPrefs.edit().putString(message.id, json.toString()).apply()
    }

    override fun removeCallLogMessage(id: String) {
        callLogPrefs.edit().remove(id).apply()
    }

    override fun clearCallLogMessages() {
        callLogPrefs.edit().clear().apply()
    }

    // ==========================================
    // NOTIFICATION PRIVACY IMPLEMENTATION
    // ==========================================

    override fun getNotificationPrivacy(): NotificationPrivacy {
        val value = prefs.getString(KEY_NOTIFICATION_PRIVACY, null)
        return if (value != null) {
            try {
                NotificationPrivacy.valueOf(value)
            } catch (e: IllegalArgumentException) {
                NotificationPrivacy.FULL_PREVIEW
            }
        } else {
            NotificationPrivacy.FULL_PREVIEW
        }
    }

    override fun setNotificationPrivacy(level: NotificationPrivacy) {
        prefs.edit().putString(KEY_NOTIFICATION_PRIVACY, level.name).apply()
    }

    // ==========================================
    // MESSAGE DRAFTS IMPLEMENTATION
    // ==========================================

    override fun getDraft(peerAddress: String): String? {
        return draftPrefs.getString(peerAddress, null)
    }

    override fun setDraft(peerAddress: String, draft: String) {
        if (draft.isBlank()) {
            // Clear draft if empty
            draftPrefs.edit().remove(peerAddress).apply()
        } else {
            draftPrefs.edit().putString(peerAddress, draft).apply()
        }
    }

    override fun clearDraft(peerAddress: String) {
        draftPrefs.edit().remove(peerAddress).apply()
    }

    override fun getAllDrafts(): Map<String, String> {
        return draftPrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun hasDraft(peerAddress: String): Boolean {
        val draft = getDraft(peerAddress)
        return !draft.isNullOrBlank()
    }

    // ==========================================
    // E2E ENCRYPTION IMPLEMENTATION
    // ==========================================

    override fun isE2EEnabled(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("$E2E_ENABLED_PREFIX$peerAddress", false)
    }

    override fun setE2EEnabled(peerAddress: String, enabled: Boolean) {
        e2ePrefs.edit().putBoolean("$E2E_ENABLED_PREFIX$peerAddress", enabled).apply()
        bumpE2EHandshakeTick()
    }

    override fun getE2EPrivateKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", null)
    }

    override fun getE2EPeerPublicKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", null)
    }

    override fun getE2EOurPublicKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", null)
    }

    override fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String) {
        e2ePrefs.edit()
            .putString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", ourPublicKey)
            .putString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", ourPrivateKey)
            .apply()
    }

    override fun setE2EPeerPublicKey(peerAddress: String, peerPublicKey: String) {
        e2ePrefs.edit()
            .putString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", peerPublicKey)
            .apply()
        bumpE2EHandshakeTick()
    }

    override fun isE2EKeyChanged(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("e2e_key_changed_$peerAddress", false)
    }

    override fun setE2EKeyChanged(peerAddress: String, changed: Boolean) {
        e2ePrefs.edit().putBoolean("e2e_key_changed_$peerAddress", changed).apply()
    }

    override fun isE2EVerified(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("e2e_verified_$peerAddress", false)
    }

    override fun setE2EVerified(peerAddress: String, verified: Boolean) {
        e2ePrefs.edit().putBoolean("e2e_verified_$peerAddress", verified).apply()
    }

    override fun setE2EKexTxId(peerAddress: String, txId: String) {
        e2ePrefs.edit().putString("e2e_kex_txid_$peerAddress", txId).apply()
    }
    override fun getE2EKexTxId(peerAddress: String): String? =
        e2ePrefs.getString("e2e_kex_txid_$peerAddress", null)

    override fun setE2EKexAckTxId(peerAddress: String, txId: String) {
        e2ePrefs.edit().putString("e2e_kexack_txid_$peerAddress", txId).apply()
    }
    override fun getE2EKexAckTxId(peerAddress: String): String? =
        e2ePrefs.getString("e2e_kexack_txid_$peerAddress", null)

    override fun setKexTxIds(peerAddress: String, txIds: Set<String>) {
        // Store a COPY (never the caller's live set) under a fresh set instance.
        e2ePrefs.edit().putStringSet("kex_txids_$peerAddress", HashSet(txIds)).apply()
    }
    override fun getKexTxIds(peerAddress: String): Set<String> =
        // Return a COPY — SharedPreferences.getStringSet hands back its internal instance, which must not
        // be mutated or leak into caller state.
        (e2ePrefs.getStringSet("kex_txids_$peerAddress", emptySet()) ?: emptySet()).toSet()

    override fun setKexAckTxIds(peerAddress: String, txIds: Set<String>) {
        e2ePrefs.edit().putStringSet("kexack_txids_$peerAddress", HashSet(txIds)).apply()
    }
    override fun getKexAckTxIds(peerAddress: String): Set<String> =
        (e2ePrefs.getStringSet("kexack_txids_$peerAddress", emptySet()) ?: emptySet()).toSet()

    override fun clearKexTxIds(peerAddress: String) {
        e2ePrefs.edit()
            .remove("kex_txids_$peerAddress")
            .remove("kexack_txids_$peerAddress")
            .apply()
    }

    override fun setQuantumShieldPSK(peerAddress: String, pskBase64: String) {
        e2ePrefs.edit().putString("qs_psk_$peerAddress", pskBase64).commit()
    }
    override fun getQuantumShieldPSK(peerAddress: String): String? =
        e2ePrefs.getString("qs_psk_$peerAddress", null)
    override fun clearQuantumShieldPSK(peerAddress: String) {
        e2ePrefs.edit().remove("qs_psk_$peerAddress").commit()
    }
    override fun setQuantumShieldOurSecret(peerAddress: String, secretBase64: String) {
        e2ePrefs.edit().putString("qs_our_secret_$peerAddress", secretBase64).commit()
    }
    override fun getQuantumShieldOurSecret(peerAddress: String): String? =
        e2ePrefs.getString("qs_our_secret_$peerAddress", null)

    override fun getRatchetStateStore(): co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore = ratchetStore

    override fun isE2EKeyExchangeComplete(peerAddress: String): Boolean {
        val ourPrivate = getE2EPrivateKey(peerAddress)
        val peerPublic = getE2EPeerPublicKey(peerAddress)
        return ourPrivate != null && peerPublic != null
    }

    override fun clearE2EKeys(peerAddress: String) {
        e2ePrefs.edit()
            .remove("$E2E_ENABLED_PREFIX$peerAddress")
            .remove("$E2E_OUR_PUBLIC_PREFIX$peerAddress")
            .remove("$E2E_OUR_PRIVATE_PREFIX$peerAddress")
            .remove("$E2E_PEER_PUBLIC_PREFIX$peerAddress")
            .remove("$E2E_KEY_VERSION_PREFIX$peerAddress")
            // Clear trust state too, so a later re-establish starts from un-flagged + unverified
            // rather than inheriting a stale "verified"/"key-changed" marker for a new key.
            .remove("e2e_key_changed_$peerAddress")
            .remove("e2e_verified_$peerAddress")
            // The KEX/KEXACK txids feed the ratchet root derivation and are tied to the keys we're
            // clearing; leaving them would mix a stale txid into the root after a re-KEX, desyncing the
            // root from the peer (who, on a mutual reset, no longer has them either).
            .remove("e2e_kex_txid_$peerAddress")
            .remove("e2e_kexack_txid_$peerAddress")
            // Convergent txid SETS are per key-generation too — a stale old-generation txid would poison
            // the post-reset root and re-desync from the peer (who cleared theirs on their reset).
            .remove("kex_txids_$peerAddress")
            .remove("kexack_txids_$peerAddress")
            .apply()
        bumpE2EHandshakeTick()
    }

    override fun getE2EKeyVersion(peerAddress: String): Int {
        // Default to version 1 (legacy) for backwards compatibility with existing keys
        return e2ePrefs.getInt("$E2E_KEY_VERSION_PREFIX$peerAddress", 1)
    }

    override fun setE2EKeyVersion(peerAddress: String, version: Int) {
        e2ePrefs.edit()
            .putInt("$E2E_KEY_VERSION_PREFIX$peerAddress", version)
            .apply()
    }

    // ==========================================
    // GROUP CHAT IMPLEMENTATION
    // ==========================================

    override fun saveGroupInfo(groupId: String, groupInfoJson: String) {
        // Save group info
        groupInfoPrefs.edit().putString(groupId, groupInfoJson).apply()
        // Add to group IDs set
        val groupIds = getAllGroupIds().toMutableSet()
        groupIds.add(groupId)
        prefs.edit().putStringSet(GROUP_IDS_KEY, groupIds).apply()
    }

    override fun getGroupInfo(groupId: String): String? {
        return groupInfoPrefs.getString(groupId, null)
    }

    override fun getAllGroupIds(): Set<String> {
        return prefs.getStringSet(GROUP_IDS_KEY, emptySet()) ?: emptySet()
    }

    override fun deleteGroup(groupId: String) {
        // Remove group info
        groupInfoPrefs.edit().remove(groupId).apply()
        // Remove members
        groupMembersPrefs.edit().remove(groupId).apply()
        // Remove draft
        groupDraftPrefs.edit().remove(groupId).apply()
        // Remove sequence
        groupSeqPrefs.edit().remove(groupId).apply()
        // Remove from group IDs set
        val groupIds = getAllGroupIds().toMutableSet()
        groupIds.remove(groupId)
        prefs.edit().putStringSet(GROUP_IDS_KEY, groupIds).apply()
        // Remove all keys for this group
        val keysToRemove = groupKeysPrefs.all.keys.filter { it.startsWith("${groupId}_") }
        val keysEditor = groupKeysPrefs.edit()
        keysToRemove.forEach { keysEditor.remove(it) }
        keysEditor.apply()
    }

    override fun saveGroupMembers(groupId: String, membersJson: String) {
        groupMembersPrefs.edit().putString(groupId, membersJson).apply()
    }

    override fun getGroupMembers(groupId: String): String? {
        return groupMembersPrefs.getString(groupId, null)
    }

    override fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String) {
        groupKeysPrefs.edit().putString("${groupId}_$keyEpoch", encryptedKey).apply()
    }

    override fun getGroupKey(groupId: String, keyEpoch: Int): String? {
        return groupKeysPrefs.getString("${groupId}_$keyEpoch", null)
    }

    override fun getGroupKeyEpoch(groupId: String): Int {
        return prefs.getInt("$GROUP_EPOCH_PREFIX$groupId", 0)
    }

    override fun setGroupKeyEpoch(groupId: String, epoch: Int) {
        prefs.edit().putInt("$GROUP_EPOCH_PREFIX$groupId", epoch).apply()
    }

    override fun isGroupSelfCreated(groupId: String): Boolean {
        return prefs.getBoolean("$GROUP_SELF_CREATED_PREFIX$groupId", false)
    }

    override fun setGroupSelfCreated(groupId: String, created: Boolean) {
        prefs.edit().putBoolean("$GROUP_SELF_CREATED_PREFIX$groupId", created).apply()
    }

    override fun getGroupDraft(groupId: String): String? {
        return groupDraftPrefs.getString(groupId, null)
    }

    override fun setGroupDraft(groupId: String, draft: String) {
        if (draft.isBlank()) {
            groupDraftPrefs.edit().remove(groupId).apply()
        } else {
            groupDraftPrefs.edit().putString(groupId, draft).apply()
        }
    }

    override fun clearGroupDraft(groupId: String) {
        groupDraftPrefs.edit().remove(groupId).apply()
    }

    override fun getAllGroupDrafts(): Map<String, String> {
        return groupDraftPrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun getGroupMessageSequence(groupId: String): Long {
        return groupSeqPrefs.getLong(groupId, 0L)
    }

    @Synchronized
    override fun incrementGroupMessageSequence(groupId: String): Long {
        // @Synchronized (matching advancePeerModeChangeSince / setDisappearingTtl / getOrPutPendingTx…):
        // this is a read-modify-write, so two concurrent group sends (e.g. share fan-out + a user send)
        // could otherwise both read seq=N and both return N+1, handing out a DUPLICATE sequence number
        // that peer-side ordering/dedup may drop as a replay.
        val current = getGroupMessageSequence(groupId)
        val next = current + 1
        groupSeqPrefs.edit().putLong(groupId, next).apply()
        return next
    }

    override fun getGroupMessages(groupId: String): String? {
        return groupMsgPrefs.getString(groupId, null)
    }

    override fun saveGroupMessages(groupId: String, messagesJson: String) {
        groupMsgPrefs.edit().putString(groupId, messagesJson).apply()
    }

    // ==========================================
    // UNROUTABLE MESSAGES IMPLEMENTATION
    // ==========================================

    override fun addUnroutableMessage(message: ZchatPreferences.UnroutableMessageData) {
        val json = org.json.JSONObject().apply {
            put("txId", message.txId)
            put("memoPreview", message.memoPreview)
            put("timestamp", message.timestamp)
            put("senderHash", message.senderHash ?: "")
            put("convId", message.convId ?: "")
        }
        unroutableMsgPrefs.edit().putString(message.txId, json.toString()).apply()
        Log.d("ZCHAT_UNROUTABLE", "Stored unroutable message: ${message.txId.take(12)}...")
    }

    override fun getUnroutableMessages(): List<ZchatPreferences.UnroutableMessageData> {
        val result = mutableListOf<ZchatPreferences.UnroutableMessageData>()
        for ((_, value) in unroutableMsgPrefs.all) {
            if (value is String) {
                try {
                    val json = org.json.JSONObject(value)
                    result.add(
                        ZchatPreferences.UnroutableMessageData(
                            txId = json.getString("txId"),
                            memoPreview = json.getString("memoPreview"),
                            timestamp = json.getLong("timestamp"),
                            senderHash = json.optString("senderHash").ifBlank { null },
                            convId = json.optString("convId").ifBlank { null }
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ZchatPreferences", "Failed to parse unroutable message", e)
                }
            }
        }
        return result.sortedByDescending { it.timestamp }
    }

    override fun removeUnroutableMessage(txId: String) {
        unroutableMsgPrefs.edit().remove(txId).apply()
    }

    override fun getUnroutableMessageCount(): Int {
        return unroutableMsgPrefs.all.size
    }

    // ==========================================
    // IDENTITY MANAGEMENT IMPLEMENTATION
    // ==========================================

    override fun getAllContactAddresses(): Set<String> {
        // Return all addresses that have nicknames (address book contacts)
        return nicknamePrefs.all.keys
    }

    override fun getAllConversationPeerAddresses(): Set<String> {
        // Return all peer addresses from conversation mappings
        return convMappingPrefs.all.keys
            .filter { it.startsWith("peer:") }
            .map { it.removePrefix("peer:") }
            .toSet()
    }

    // ==========================================
    // NOTIFICATION SETTINGS IMPLEMENTATION
    // ==========================================

    override fun isNotificationSoundEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_SOUND, true)
    }

    override fun setNotificationSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND, enabled).apply()
    }

    override fun isNotificationVibrationEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_VIBRATION, true)
    }

    override fun setNotificationVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_VIBRATION, enabled).apply()
    }

    override fun getMutedConversations(): Set<String> {
        return prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()) ?: emptySet()
    }

    override fun muteConversation(address: String) {
        val current = getMutedConversations().toMutableSet()
        current.add(address)
        prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, current).apply()
    }

    override fun unmuteConversation(address: String) {
        val current = getMutedConversations().toMutableSet()
        current.remove(address)
        prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, current).apply()
    }

    override fun isConversationMuted(address: String): Boolean {
        return getMutedConversations().contains(address)
    }

    // ==========================================
    // CONVERSATION READ STATE IMPLEMENTATION
    // ==========================================

    private fun lastReadKey(peerAddress: String) = "$LAST_READ_PREFIX$peerAddress"

    // #226 shared reactive read-marker map. Seeded once from prefs; every monotonic write emits the new
    // map so all ChatViewModel instances (list + detail) recompute unread off the SAME source.
    private val _readMarkers: kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>> =
        kotlinx.coroutines.flow.MutableStateFlow(loadAllLastReadTimestampsRaw())
    override val readMarkers: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _readMarkers

    // #257 handshake-state tick (see interface). Atomic bump; consumers re-read the actual markers.
    private val _e2eHandshakeTicks = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val e2eHandshakeTicks: kotlinx.coroutines.flow.StateFlow<Long> = _e2eHandshakeTicks
    private fun bumpE2EHandshakeTick() { _e2eHandshakeTicks.update { it + 1 } }

    // B17 disappearing-messages TTL — #226 process-wide singleton flow (mirror of _readMarkers).
    private fun loadAllDisappearingTtlsRaw(): Map<String, ZchatPreferences.DisappearingTtl> =
        prefs.all.keys.filter { it.startsWith(DISAPPEAR_TTL_PREFIX) }.mapNotNull { key ->
            val peer = key.removePrefix(DISAPPEAR_TTL_PREFIX)
            val ttl = prefs.getLong(key, -1L)
            if (ttl < 0) null else peer to ZchatPreferences.DisappearingTtl(ttl, prefs.getLong("$DISAPPEAR_SINCE_PREFIX$peer", 0L))
        }.toMap()
    private val _disappearingTtls = kotlinx.coroutines.flow.MutableStateFlow(loadAllDisappearingTtlsRaw())
    override val disappearingTtls: kotlinx.coroutines.flow.StateFlow<Map<String, ZchatPreferences.DisappearingTtl>> = _disappearingTtls
    override fun getDisappearingTtl(peerAddress: String): ZchatPreferences.DisappearingTtl? {
        val ttl = prefs.getLong("$DISAPPEAR_TTL_PREFIX$peerAddress", -1L)
        return if (ttl < 0) null else ZchatPreferences.DisappearingTtl(ttl, prefs.getLong("$DISAPPEAR_SINCE_PREFIX$peerAddress", 0L))
    }
    @Synchronized
    override fun setDisappearingTtl(peerAddress: String, ttlSeconds: Long, effectiveSinceMillis: Long): Boolean {
        // Monotonic: reject a non-newer since → idempotent under chain-rescan / relay-replay.
        if (effectiveSinceMillis <= prefs.getLong("$DISAPPEAR_SINCE_PREFIX$peerAddress", 0L)) return false
        prefs.edit()
            .putLong("$DISAPPEAR_TTL_PREFIX$peerAddress", ttlSeconds)
            .putLong("$DISAPPEAR_SINCE_PREFIX$peerAddress", effectiveSinceMillis)
            .apply()
        _disappearingTtls.update { it + (peerAddress to ZchatPreferences.DisappearingTtl(ttlSeconds, effectiveSinceMillis)) }
        return true
    }
    override fun getOrPutMessageExpiryAnchorMillis(messageId: String, nowMillis: Long): Long {
        val k = "$EXPIRY_ANCHOR_PREFIX$messageId"
        val v = prefs.getLong(k, 0L)
        return if (v > 0) v else nowMillis.also { prefs.edit().putLong(k, it).apply() }
    }
    override fun clearMessageExpiryAnchor(messageId: String) {
        prefs.edit().remove("$EXPIRY_ANCHOR_PREFIX$messageId").apply()
    }

    override fun getLastReadTimestamp(peerAddress: String): Long {
        return prefs.getLong(lastReadKey(peerAddress), 0L)
    }

    override fun setLastReadTimestamp(peerAddress: String, millis: Long) {
        // Monotonic: never move the marker backwards.
        if (millis <= getLastReadTimestamp(peerAddress)) return
        prefs.edit().putLong(lastReadKey(peerAddress), millis).apply()
        // Publish to the shared flow so other ChatViewModel instances (e.g. the chat-list screen) see
        // the advance immediately and clear the unread badge without waiting for a VM recreate. Use the
        // atomic update {} (compare-and-set) — the flow is a process-wide singleton, so a plain
        // value=value+(..) read-modify-write could lose a concurrent advance for a different peer.
        _readMarkers.update { current ->
            val existing = current[peerAddress] ?: 0L
            if (millis <= existing) current else current + (peerAddress to millis)
        }
    }

    override fun getAllLastReadTimestamps(): Map<String, Long> = _readMarkers.value

    private fun loadAllLastReadTimestampsRaw(): Map<String, Long> {
        return prefs.all
            .filterKeys { it.startsWith(LAST_READ_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? Long)?.let { key.removePrefix(LAST_READ_PREFIX) to it }
            }
            .toMap()
    }

    // ==========================================
    // WORKER SYNC TIMESTAMP IMPLEMENTATION
    // ==========================================

    override fun getLastWorkerSyncTimestamp(): Long {
        return prefs.getLong(KEY_LAST_WORKER_SYNC_TIMESTAMP, 0L)
    }

    override fun setLastWorkerSyncTimestamp(millis: Long) {
        prefs.edit().putLong(KEY_LAST_WORKER_SYNC_TIMESTAMP, millis).apply()
    }

    // ==========================================
    // SEED BACKUP REMINDER IMPLEMENTATION
    // ==========================================

    override fun hasBackedUpSeed(): Boolean {
        return prefs.getBoolean(KEY_HAS_BACKED_UP_SEED, false)
    }

    override fun setHasBackedUpSeed(backed: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_BACKED_UP_SEED, backed).apply()
    }

    override fun getFirstOutgoingMessageTimestamp(): Long {
        return prefs.getLong(KEY_FIRST_OUTGOING_MSG_TS, 0L)
    }

    override fun setFirstOutgoingMessageTimestamp(millis: Long) {
        prefs.edit().putLong(KEY_FIRST_OUTGOING_MSG_TS, millis).apply()
    }

    override fun getNostrRotationIndex(): Int = prefs.getInt(KEY_NOSTR_ROTATION_INDEX, 0)
    override fun setNostrRotationIndex(index: Int) {
        prefs.edit().putInt(KEY_NOSTR_ROTATION_INDEX, index).apply()
    }

    override fun getLastRotationReminderAt(): Long = prefs.getLong(KEY_LAST_ROTATION_REMINDER_TS, 0L)
    override fun setLastRotationReminderAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_ROTATION_REMINDER_TS, millis).apply()
    }

    override fun getLastBackupReminderTimestamp(): Long {
        return prefs.getLong(KEY_LAST_BACKUP_REMINDER_TS, 0L)
    }

    override fun setLastBackupReminderTimestamp(millis: Long) {
        prefs.edit().putLong(KEY_LAST_BACKUP_REMINDER_TS, millis).apply()
    }

    override fun getBackupReminderCount(): Int {
        return prefs.getInt(KEY_BACKUP_REMINDER_COUNT, 0)
    }

    override fun incrementBackupReminderCount() {
        val count = getBackupReminderCount()
        prefs.edit().putInt(KEY_BACKUP_REMINDER_COUNT, count + 1).apply()
    }
}
