package co.electriccoin.zcash.ui.nostr

import android.util.Log
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plumbing between [NostrInboxManager] and the chat layer. The foreground service is
 * the only place that derives the NOSTR identity (it owns the wallet seed), so it
 * pushes inbound DMs through here. [ChatViewModel] subscribes to [inbound] and
 * funnels them into the conversation flow.
 *
 * Singleton because there's only ever one wallet seed at a time and the ViewModel
 * can outlive the Service.
 */
object NostrChatBridge {

    data class InboundChat(
        val peerAddress: String,
        val plaintext: String,
        val createdAtSec: Long,
        // Unique per gift-wrap; the ViewModel dedups on this instead of content+timestamp.
        val eventId: String,
        // STABLE cross-device rumor id — used as the message id so reactions/replies correlate across
        // the two devices (the sender derives the identical id). See Nip17.ReceivedMessage.rumorId.
        val rumorId: String = "",
        // #252: forwarded from InboundDm — the OUR-side pubkey (x-only hex) this wrap decrypted under, i.e.
        // the rotation key the sender holds for us. ChatViewModel maps it to our index to advance per-peer
        // rotation bookkeeping to exactly what the sender proved they hold. Blank = unknown (don't advance).
        val recipientPubkeyHex: String = "",
    )

    // 256 (was 64) — headroom for a relay (re)subscribe replaying a backlog burst without dropping
    // live DMs. Overflow is still logged at the dispatch site rather than silently swallowed.
    private val _inbound = MutableSharedFlow<InboundChat>(replay = 0, extraBufferCapacity = 256)
    val inbound: SharedFlow<InboundChat> = _inbound.asSharedFlow()

    // #251 — sentinel [InboundChat.peerAddress] for a ZBOOT that arrived from a NOSTR pubkey we do NOT
    // yet hold (a peer's NEW key after rotation, or an idx-pre-swapped seal), so it can't be attributed
    // by sender pubkey. The chat layer (ChatViewModel.routeUnattributedBoot) attributes it by the
    // ZBOOT's own convId / signature and lets routeIncomingBoot authenticate it. A real peer address is
    // a Zcash UA ("u1…"), never empty, so this can't collide with a genuinely-attributed message.
    const val UNATTRIBUTED_BOOT = ""

    // #224 — inbound OPEN ("free NOSTR from message #1") contact requests. A first-contact INIT from an
    // unknown NOSTR pubkey is held here (and persisted) for the user to accept/reject, rather than
    // dropped. The ViewModel collects this for a live "Requests" badge; persistence covers the
    // ViewModel-not-running case (it reloads from ZchatPreferences on start).
    private val _messageRequests =
        MutableSharedFlow<ZchatPreferences.MessageRequest>(replay = 0, extraBufferCapacity = 64)
    val messageRequests: SharedFlow<ZchatPreferences.MessageRequest> = _messageRequests.asSharedFlow()

    // Local, non-transmitted messages (call logs etc.) the service injects into the active
    // conversation. Mirrors [inbound] but bypasses the NOSTR/relay path entirely; persistence
    // (ZchatPreferences call-log store) covers the "ViewModel not running" case.
    private val _localMessages =
        MutableSharedFlow<co.electriccoin.zcash.ui.screen.chat.model.ChatMessage>(replay = 0, extraBufferCapacity = 32)
    val localMessages: SharedFlow<co.electriccoin.zcash.ui.screen.chat.model.ChatMessage> = _localMessages.asSharedFlow()

    fun emitLocalMessage(msg: co.electriccoin.zcash.ui.screen.chat.model.ChatMessage) {
        _localMessages.tryEmit(msg)
    }

    /** Result of [publish]: relay ack count + the STABLE rumor id of the sent message (for reaction /
     *  reply correlation; both sender and recipient derive the identical id from the rumor). */
    data class PublishResult(val acks: Int, val messageId: String?)

    /**
     * Outbound publish hook installed by the foreground service. Returns the relay-ack count + the
     * sent message's stable rumor id. Null if the inbox isn't started yet (e.g. service died).
     */
    @Volatile private var publisher: (suspend (plaintext: String, recipientPubkeyHex: String) -> PublishResult)? = null

    // Observable mirror of [isOutboundReady] so UI can react when the publisher registers/unregisters
    // (e.g. flip a Tunnel chat's compose bar from a ZEC cost to "Free" the instant the inbox is ready).
    // Without this the cost label could read "Free" during the cold-launch window before the publisher
    // is installed, while a send in that window actually falls back to a charged on-chain memo.
    private val _outboundReady = MutableStateFlow(false)
    val outboundReady: StateFlow<Boolean> = _outboundReady.asStateFlow()

    fun registerPublisher(fn: suspend (plaintext: String, recipientPubkeyHex: String) -> PublishResult) {
        publisher = fn
        _outboundReady.value = true
    }

    fun unregisterPublisher() {
        publisher = null
        _outboundReady.value = false
    }

    /** Returns true iff the outbound side is ready. */
    fun isOutboundReady(): Boolean = publisher != null

    /**
     * Inbox rotation hook (#188). The foreground service installs a callback that re-derives the
     * NOSTR identity from the (already-bumped) rotation index and hot-swaps the live inbox to it. The
     * chat layer triggers it via [requestInboxRotation] right after rotating, so inbound delivery
     * follows the new key WITHOUT an app restart. No-op if the service isn't running.
     */
    @Volatile private var inboxRotater: (() -> Unit)? = null

    fun registerInboxRotater(fn: () -> Unit) {
        inboxRotater = fn
    }

    fun unregisterInboxRotater() {
        inboxRotater = null
    }

    /** Ask the running inbox to re-subscribe under the rotated key. No-op if the service is down. */
    fun requestInboxRotation() {
        inboxRotater?.invoke()
    }

    /**
     * Inbox foreground-liveness hook. The foreground service installs a callback that reconnects the relay
     * pool when a subscription has gone silently quiet (no CLOSED frame, e.g. after background/Doze). The app
     * triggers it on return-to-foreground via [refreshInbox], so inbound DMs/reactions resume WITHOUT an app
     * restart. No-op if the service isn't running; throttled + staleness-guarded inside the inbox/pool.
     */
    @Volatile private var inboxRefresher: (() -> Unit)? = null

    fun registerInboxRefresher(fn: () -> Unit) {
        inboxRefresher = fn
    }

    fun unregisterInboxRefresher() {
        inboxRefresher = null
    }

    // B8 — a new inbound contact request fires this so the foreground service can raise a system/in-app
    // alert (the request was previously invisible unless you happened to be on the chat list).
    @Volatile private var requestNotifier: ((ZchatPreferences.MessageRequest) -> Unit)? = null
    fun registerRequestNotifier(fn: (ZchatPreferences.MessageRequest) -> Unit) { requestNotifier = fn }
    fun unregisterRequestNotifier() { requestNotifier = null }

    // B8 — armed one-shot "open the Requests sheet" signal (survives cold-start nav); the chat-list screen
    // consumes it once its request list has seeded. Set by a notification/in-app-banner tap.
    private val _openRequestsSheetArmed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val openRequestsSheetArmed: kotlinx.coroutines.flow.StateFlow<Boolean> = _openRequestsSheetArmed.asStateFlow()
    fun armOpenRequestsSheet() { _openRequestsSheetArmed.value = true }
    fun clearOpenRequestsSheetArm() { _openRequestsSheetArmed.value = false }

    /** Kick the running inbox to reconnect if its relay subscriptions look stale. No-op if the service is down. */
    fun refreshInbox() {
        inboxRefresher?.invoke()
    }

    /** Publish a NIP-17 DM. Returns relay-ack count + stable rumor id; acks=0 if no publisher. */
    suspend fun publish(plaintext: String, recipientPubkeyHex: String): PublishResult {
        val fn = publisher ?: return PublishResult(0, null)
        return fn(plaintext, recipientPubkeyHex)
    }

    // -------- Call signalling subchannel --------

    @Volatile private var callSignalHandler: ((senderPubkeyHex: String, envelope: co.electriccoin.zcash.ui.call.CallSignalEnvelope) -> Unit)? = null

    fun registerCallSignalHandler(fn: (String, co.electriccoin.zcash.ui.call.CallSignalEnvelope) -> Unit) {
        callSignalHandler = fn
    }

    fun unregisterCallSignalHandler() {
        callSignalHandler = null
    }

    fun deliverCallSignal(senderPubkeyHex: String, envelope: co.electriccoin.zcash.ui.call.CallSignalEnvelope) {
        val handler = callSignalHandler
        if (handler == null) {
            // Don't silently swallow RING/OFFER/ANSWER/ICE/HANGUP — log so a dropped incoming call
            // (call subsystem not yet registered) is diagnosable instead of vanishing.
            Log.w("NostrChatBridge", "Dropping call signal from ${senderPubkeyHex.take(12)}… — no call handler registered")
            return
        }
        handler.invoke(senderPubkeyHex, envelope)
    }

    /**
     * Look up the Zcash peer address by NOSTR pubkey and emit the chat message. Drops
     * the DM if we don't recognize the sender — defends against unsolicited gift-wraps
     * from random pubkeys (NOSTR has no rate-limit and no authentication of the
     * envelope itself).
     */
    fun dispatch(dm: NostrInboxManager.InboundDm, prefs: ZchatPreferences) {
        // Resolve the sender's peer address FIRST — both chat DMs and call signals must
        // come from a known Tunnel/Open contact. Anything else is an unsolicited gift
        // wrap and gets dropped here so it never reaches the chat UI or the call state
        // machine. Defends against ring-bomb / covert hot-mic / SDP-injection vectors
        // from random NOSTR pubkeys.
        val isCallSignal = co.electriccoin.zcash.ui.call.CallSignalEnvelope.isSignal(dm.content)
        val peer = prefs.findPeerByNostrPubkey(dm.senderPubkeyHex)
        if (peer == null) {
            // A ZCALL from an unmapped pubkey is a DROPPED CALL (a real call that can't ring because
            // key exchange hasn't completed) — surface it at WARN so it's not invisible.
            if (isCallSignal) {
                Log.w(TAG, "DROPPED ZCALL from UNKNOWN NOSTR pubkey ${dm.senderPubkeyHex.take(8)}… — peer not mapped (finish KEX/handshake first)")
                return
            }
            // #251 — AUTHENTICATED ROTATION RECOVERY. A rotation announce (ZBOOT) by definition arrives
            // from a NOSTR key the recipient does NOT yet hold (the peer's NEW key after rotation, or an
            // idx-pre-swapped seal), so it can't be attributed by sender pubkey and would otherwise be
            // dropped here — permanently stranding the rotated peer (the #251 root cause). Hand it to the
            // chat layer UNATTRIBUTED so it can map it to the right conversation by the ZBOOT's own convId
            // and, failing that, by finding the established peer whose stored key VERIFIES the signature.
            // Trust is UNCHANGED: routeIncomingBoot's signature verify (E2E v3 / Schnorr-vs-known-NOSTR-key
            // v4) + epoch monotonicity remain the sole gate — convId/attribution are ROUTING ONLY, so a
            // forged ZBOOT resolves to no peer (or fails verify) and is dropped exactly as an unknown DM is
            // today. NOT marked-seen here (it never reaches the line-below dedup): an un-adopted announce
            // can redeliver, and routeIncomingBoot dedups on the ZBOOT signature so re-emit is idempotent.
            if (co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.isBootMessage(dm.content)) {
                val emitted = _inbound.tryEmit(
                    InboundChat(
                        peerAddress = UNATTRIBUTED_BOOT,
                        plaintext = dm.content,
                        createdAtSec = dm.createdAtSec,
                        eventId = dm.eventId,
                        rumorId = dm.rumorId,
                        // #252: thread the decrypting pubkey here too. Harmless today (the adoption advance
                        // in observeNostrInbound is gated on a non-empty peerAddress, and this is
                        // UNATTRIBUTED_BOOT=""), but keeps the field populated on every inbound path so a
                        // future attributed reprocessing of this ZBOOT has the key available.
                        recipientPubkeyHex = dm.recipientPubkeyHex,
                    ),
                )
                if (emitted) {
                    Log.d(TAG, "#251 routing unknown-sender ZBOOT for authenticated rotation attribution")
                } else {
                    Log.w(TAG, "inbound buffer full — dropped unknown-sender ZBOOT (will redeliver on replay)")
                }
                return
            }
            // #224 OPEN-from-message-#1: an unknown pubkey sending a ZMSG v4 INIT is a first-contact
            // request. We do NOT auto-trust it — the gift wrap authenticates only the NOSTR key, NOT that
            // it owns the CLAIMED Zcash address in the INIT body — so we hold it in a Message Requests
            // inbox for the user to manually accept (TOFU gate). A non-INIT DM from an unknown pubkey is
            // still dropped silently (ordinary spam is the steady state).
            routeUnknownPubkeyAsRequest(dm, prefs)
            return
        }
        // PERSISTENT REPLAY DROP (#188): relays replay stored gift-wraps on every (re)subscribe and the
        // same event lands on multiple relays. Drop anything whose unique event id we've already handled
        // — for BOTH chat DMs and call signals, and across process restarts. This is the dedicated
        // replay defense the freshness windows below merely backstop; we mark an id seen only AFTER it's
        // been handled (below), so a buffer-full drop isn't permanently suppressed and can still redeliver.
        if (prefs.hasSeenNostrEvent(dm.eventId)) {
            Log.d(TAG, "ignoring already-handled gift-wrap ${dm.eventId.take(8)}… from $peer (replay)")
            return
        }
        // Only treat the DM as a call signal if it actually PARSES — a loose "ZCALL|v1|" prefix
        // match alone must NOT swallow (or let a peer spoof a call via) a plain user message that
        // merely starts that way. A prefixed-but-unparseable body falls through to the chat path.
        val callEnv = if (isCallSignal) co.electriccoin.zcash.ui.call.CallSignalEnvelope.parse(dm.content) else null
        if (callEnv != null) {
            // FRESHNESS GUARD: relays REPLAY stored gift-wraps on every (re)subscribe; dm.createdAtSec
            // is the rumor's REAL send time, so drop anything older than the window as replayed
            // backlog instead of ringing the user for a call that already ended (phantom-call fix).
            val ageSec = (System.currentTimeMillis() / 1000) - dm.createdAtSec
            // Two-sided window: reject STALE (replayed backlog, age too large) AND FUTURE-dated
            // signals (age negative). A forged or clock-skewed future created_at makes ageSec negative;
            // the old `ageSec > MAX` check treats negatives as fresh, so an attacker could set
            // created_at far in the future to defeat the staleness guard and ring the user forever.
            // The symmetric ±window also tolerates benign clock skew in either direction.
            if (ageSec > CALL_SIGNAL_MAX_AGE_SEC || ageSec < -CALL_SIGNAL_MAX_AGE_SEC) {
                Log.w(TAG, "ignoring out-of-window ZCALL from $peer (age=${ageSec}s, window ±${CALL_SIGNAL_MAX_AGE_SEC}s — replayed/forged)")
                return
            }
            val mode = prefs.getConversationMode(peer)
            if (mode.supportsCalls) {
                deliverCallSignal(dm.senderPubkeyHex, callEnv)
                // Mark handled only after delivering, so a replay of THIS signal can't double-ring.
                prefs.markNostrEventSeen(dm.eventId)
            } else {
                Log.w(TAG, "DROPPED ZCALL from $peer — mode=$mode not calls-capable (need TUNNEL/OPEN)")
            }
            return
        }
        // Not a valid call signal → fall through to the normal chat-message path below.
        // FRESHNESS GUARD (chat): relays replay stored gift-wraps, so a very old DM is replayed
        // backlog. Unlike a call (useless once stale), a delayed chat message is STILL a real message
        // the user wants — someone offline for days should still receive what was sent meanwhile — so
        // the window is deliberately GENEROUS (30 days), only shedding absurdly-old replays rather
        // than realistic offline delivery. The persistent eventId dedup is the primary replay defense;
        // this just bounds the worst case where that dedup was reset.
        val chatAgeSec = (System.currentTimeMillis() / 1000) - dm.createdAtSec
        if (chatAgeSec > CHAT_MSG_MAX_AGE_SEC) {
            Log.w(TAG, "ignoring STALE chat DM from $peer (age=${chatAgeSec}s > ${CHAT_MSG_MAX_AGE_SEC}s — replayed backlog)")
            return
        }
        // tryEmit returns false when the replay-0 buffer is full (collector paused / burst). Don't
        // silently lose a received message — log it so the drop is diagnosable.
        val emitted = _inbound.tryEmit(
            InboundChat(
                peerAddress = peer,
                plaintext = dm.content,
                createdAtSec = dm.createdAtSec,
                eventId = dm.eventId,
                rumorId = dm.rumorId,
                recipientPubkeyHex = dm.recipientPubkeyHex,
            ),
        )
        if (!emitted) {
            Log.w(TAG, "inbound buffer full — DROPPED chat DM from $peer (collector paused or burst > buffer)")
        } else if (_inbound.subscriptionCount.value > 0) {
            // Mark handled only on a REAL hand-off to a live collector. A buffer-full drop above stays
            // UNmarked so a relay replay can redeliver it — the persistent dedup must not turn a transient
            // overflow into permanent message loss.
            prefs.markNostrEventSeen(dm.eventId)
        } else {
            // CRITICAL fix: `_inbound` is a replay=0 SharedFlow. With NO active ChatViewModel collector
            // (app on a non-chat screen / VM cleared / backgrounded) tryEmit still returns true but the
            // value is DISCARDED — yet the old code marked the event seen, so the persistent dedup
            // dropped it forever on every relay replay = PERMANENT message loss. Leaving it UNmarked lets
            // the relay redeliver it on the next (re)subscribe — e.g. the foreground-liveness reconnect
            // when the user reopens the app — so it lands once a collector is alive.
            Log.w(TAG, "no active chat collector — leaving $peer DM ${dm.eventId.take(8)}… unmarked for redelivery")
        }
    }

    /**
     * #224 — handle a DM from a NOSTR pubkey we don't recognise. If it's a well-formed ZMSG v4 INIT it
     * becomes a Message Request (held for manual accept); otherwise it's dropped. Trust gating:
     *  - blocked pubkeys are dropped silently (anti-nag after a reject),
     *  - already-handled gift-wraps are dropped (persistent replay defense, same as the known-peer path),
     *  - absurdly old replays are dropped (freshness window),
     *  - an INIT claiming OUR OWN Zcash address is dropped as a spoof.
     * Nothing here grants trust — accept is a deliberate user action in the UI.
     */
    private fun routeUnknownPubkeyAsRequest(dm: NostrInboxManager.InboundDm, prefs: ZchatPreferences) {
        val init = co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol.parseV4Init(dm.content)
        if (init == null) {
            Log.d(TAG, "ignoring DM from unknown NOSTR pubkey ${dm.senderPubkeyHex.take(8)}… (not an INIT)")
            return
        }
        if (prefs.isNostrPubkeyBlocked(dm.senderPubkeyHex)) {
            Log.d(TAG, "ignoring INIT from BLOCKED pubkey ${dm.senderPubkeyHex.take(8)}…")
            return
        }
        if (prefs.hasSeenNostrEvent(dm.eventId)) {
            Log.d(TAG, "ignoring already-handled request gift-wrap ${dm.eventId.take(8)}… (replay)")
            return
        }
        val ageSec = (System.currentTimeMillis() / 1000) - dm.createdAtSec
        // Two-sided window: drop replayed backlog (too old) AND clearly future-dated INITs (negative age
        // from a forged/clock-skewed created_at), mirroring the call-signal guard. The stored timestamp
        // is clamped to now below, but rejecting future-dated INITs is defense-in-depth.
        if (ageSec > CHAT_MSG_MAX_AGE_SEC || ageSec < -CALL_SIGNAL_MAX_AGE_SEC) {
            Log.w(TAG, "ignoring out-of-window INIT request (age=${ageSec}s — replayed/forged)")
            return
        }
        val (claimedAddress, firstMessage) = init
        if (prefs.isSelfAddress(claimedAddress)) {
            // An INIT claiming our own address is a spoof — never surface it. Mark handled so it isn't
            // reconsidered on every relay replay.
            Log.w(TAG, "ignoring INIT request claiming OUR OWN address (spoof) from ${dm.senderPubkeyHex.take(8)}…")
            prefs.markNostrEventSeen(dm.eventId)
            return
        }
        val tsSec = minOf(dm.createdAtSec, System.currentTimeMillis() / 1000)
        val request = ZchatPreferences.MessageRequest(
            senderNostrPubkeyHex = dm.senderPubkeyHex,
            senderAddress = claimedAddress,
            relayUrl = NostrRelayPool.DEFAULT_RELAYS.first(),
            firstMessage = firstMessage,
            timestampMillis = tsSec * 1000,
            eventId = dm.eventId,
            rumorId = dm.rumorId,
        )
        prefs.addMessageRequest(request)
        // B8 (part 2) — if the INIT claims an address we ALREADY have a chat with (they may have reinstalled
        // or reset), surface a neutral pill INSIDE that existing chat so the user notices something changed.
        // #187 ABSOLUTE RULE: claimedAddress is an unauthenticated attacker-controlled field — do NOT mutate
        // any trust state (no setE2EKeyChanged/setE2EVerified) here; trust changes stay in acceptMessageRequest.
        if (prefs.getConversationId(claimedAddress) != null) {
            val noteId = co.electriccoin.zcash.ui.screen.chat.model.SysNotes.requestNoteId(dm.senderPubkeyHex, claimedAddress)
            val now = System.currentTimeMillis()
            val noteText = "📨 A new contact request claims this contact's address — they may have reinstalled " +
                "or reset ZCHAT. Review it in Requests (chat list) before trusting. This does NOT verify their identity."
            prefs.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = noteId, text = noteText, timestampMillis = now, peerAddress = claimedAddress,
                    isOutgoing = false, isPending = false, status = "SENT",
                )
            )
            emitLocalMessage(
                co.electriccoin.zcash.ui.screen.chat.model.ChatMessage(
                    id = noteId, txId = null, text = noteText, timestamp = java.time.Instant.ofEpochMilli(now),
                    isOutgoing = false, peerAddress = claimedAddress, isPending = false,
                    status = co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENT, isSystemNote = true,
                )
            )
        }
        // Mark handled AFTER persisting so a replay can't duplicate, but a crash mid-store can still
        // redeliver (we only suppress once the request is durably saved).
        prefs.markNostrEventSeen(dm.eventId)
        val emitted = _messageRequests.tryEmit(request)
        requestNotifier?.invoke(request) // B8 — raise the global alert
        Log.d(TAG, "stored OPEN contact request from ${dm.senderPubkeyHex.take(8)}… claiming ${claimedAddress.take(12)}… (live=$emitted)")
    }

    private const val TAG = "NostrChatBridge"

    // Call signals whose rumor created_at is older than this are treated as replayed relay backlog
    // and dropped — prevents a phantom incoming-call screen from stored RINGs on (re)subscribe. A
    // live call's signals are seconds old; 120s tolerates clock skew + relay delay.
    private const val CALL_SIGNAL_MAX_AGE_SEC = 120L

    // Chat DMs whose rumor created_at is older than this are dropped as replayed relay backlog.
    // Deliberately generous (30 days) so legitimately delayed / offline-queued delivery still lands —
    // the persistent eventId dedup, not this window, is the primary replay defense.
    private const val CHAT_MSG_MAX_AGE_SEC = 30L * 24 * 60 * 60
}
