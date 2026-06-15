package co.electriccoin.zcash.ui.nostr

import android.util.Log
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
    )

    // 256 (was 64) — headroom for a relay (re)subscribe replaying a backlog burst without dropping
    // live DMs. Overflow is still logged at the dispatch site rather than silently swallowed.
    private val _inbound = MutableSharedFlow<InboundChat>(replay = 0, extraBufferCapacity = 256)
    val inbound: SharedFlow<InboundChat> = _inbound.asSharedFlow()

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

    fun registerPublisher(fn: suspend (plaintext: String, recipientPubkeyHex: String) -> PublishResult) {
        publisher = fn
    }

    fun unregisterPublisher() {
        publisher = null
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
            // key exchange hasn't completed) — surface it at WARN so it's not invisible. Ordinary
            // unsolicited DMs stay quiet (spam is the steady state).
            if (isCallSignal) {
                Log.w(TAG, "DROPPED ZCALL from UNKNOWN NOSTR pubkey ${dm.senderPubkeyHex.take(8)}… — peer not mapped (finish KEX/handshake first)")
            } else {
                Log.d(TAG, "ignoring DM from unknown NOSTR pubkey ${dm.senderPubkeyHex.take(8)}…")
            }
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
            ),
        )
        if (!emitted) {
            Log.w(TAG, "inbound buffer full — DROPPED chat DM from $peer (collector paused or burst > buffer)")
        } else {
            // Mark handled only on a successful hand-off. A buffer-full drop above stays UNmarked so a
            // relay replay can redeliver it — the persistent dedup must not turn a transient overflow
            // into permanent message loss.
            prefs.markNostrEventSeen(dm.eventId)
        }
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
