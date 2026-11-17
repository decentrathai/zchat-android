package co.electriccoin.zcash.ui.nostr

import android.util.Log
import co.electriccoin.zcash.ui.call.TeamCallBlock
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val RELAY_COPY_TAG = "RelayCopy"

    /** ZC-420 / ZC-658: a mark this old skips its kept wrap and is rebuilt (see [repairRelayCopy]). */
    const val LATE_KEPT_WRAP_MS = 60L * 60 * 1000

    /** ZC-420: message marks kept at most; the oldest goes for a new one. */
    const val MAX_MESSAGE_MARKS = 1_024

    /** ZC-420: identity, key and invite marks kept at most, together; one more is not written. */
    const val MAX_OTHER_MARKS = 256

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
        // Round 8 MA3 (MEMBER_ANNOUNCE_DESIGN.md v2 §2.4): the kind-14 rumor's tags, as the sender wrote them (e.g.
        // ["zchat_cap","gp1"]: the sender reads group member announces). Empty when unknown.
        val rumorTags: List<List<String>> = emptyList(),
        // KI-24: the outer gift-wrap's created_at (seconds; InboundDm.wrapCreatedAtSec), for the seen list's floor.
        val wrapCreatedAtSec: Long = createdAtSec,
        // ZC-1337: from the relay key a held ZBOOT names. No screen shows it and nothing marks it seen while the hold stands.
        val heldConnection: HeldConnection? = null,
    )

    /** ZC-1349: the relay key of a message kept for after a hold, and when that message first arrived (seconds, survives a restart). */
    data class HeldConnection(
        val senderPubkeyHex: String,
        val arrivalSec: Long,
    )

    // 256 (was 64) — headroom for a relay (re)subscribe replaying a backlog burst without dropping
    // live DMs. Overflow is still logged at the dispatch site rather than silently swallowed.
    private val _inbound = MutableSharedFlow<InboundChat>(replay = 0, extraBufferCapacity = 256)
    val inbound: SharedFlow<InboundChat> = _inbound.asSharedFlow()

    // T1b-2 (T1 findings 3 and 6) — HOLD, DON'T DROP. The only reader of [inbound] is a screen-scoped ChatViewModel, and
    // [inbound] keeps nothing (replay 0). A relay message that arrived on the Wallet tab, in the background or at a cold
    // start (the relay sockets open, and replay what they hold, before any chat screen exists) used to be left for a
    // relay replay that our own relay never sent on a live socket: the Open DMs "left unmarked for redelivery" and the
    // four group memos to the cold-started release app in the T1 run were lost. Such a message is now held here, in
    // memory, in arrival order, and handed over as soon as a chat screen starts reading. Marking seen stays with the
    // reader, so if the process dies first the event was never marked seen and the next fresh socket replays it (and it
    // is held again). Bounded: [HELD_CAP] entries and [HELD_MAX_CHARS] characters, oldest dropped first (with a WARN and
    // the redelivery flag); deduplicated by event id. No store, no plaintext at rest.
    private class HeldChat(
        val chat: InboundChat,
        val alreadySeen: (String) -> Boolean,
    )

    private val held = ArrayDeque<HeldChat>() // guarded by itself
    private var heldChars = 0L // guarded by [held]
    private val heldVersion = MutableStateFlow(0L)
    private val handOverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Hand the held list over whenever a reader is there: when the first chat screen subscribes, and when something
        // new is held while one reads (its buffer was full). A full buffer is retried until it drains or the reader goes.
        handOverScope.launch {
            combine(_inbound.subscriptionCount, heldVersion) { readers, _ -> readers }.collectLatest { readers ->
                if (readers > 0) {
                    while (handOverHeld()) delay(HAND_OVER_RETRY_MS)
                }
            }
        }
    }

    private fun hasHeld(): Boolean = synchronized(held) { held.isNotEmpty() }

    /** Holds [chat] until a chat screen reads; [alreadySeen] skips it at hand-over if a reader handled it meanwhile. */
    private fun hold(
        chat: InboundChat,
        alreadySeen: (String) -> Boolean,
    ) {
        val dropped = mutableListOf<String>()
        synchronized(held) {
            if (held.any { it.chat.eventId == chat.eventId }) return
            held.addLast(HeldChat(chat, alreadySeen))
            heldChars += chat.plaintext.length
            while (held.size > HELD_CAP || (heldChars > HELD_MAX_CHARS && held.size > 1)) {
                val oldest = held.removeFirst()
                heldChars -= oldest.chat.plaintext.length
                dropped += oldest.chat.eventId
            }
        }
        if (dropped.isNotEmpty()) {
            // Not marked seen, so a relay replay can still bring them; the watchdog forces one once a reader is alive.
            pendingRedelivery.set(true)
            Log.w(TAG, "held list full — dropped the ${dropped.size} oldest held message(s), left for relay redelivery")
        }
        heldVersion.value += 1
    }

    /**
     * Hands held messages to the reader, oldest first, while one is subscribed. Returns true when some are left because
     * the reader's buffer is full (the caller retries), false when the list is empty or no reader is there.
     */
    private fun handOverHeld(): Boolean =
        synchronized(held) {
            while (held.isNotEmpty()) {
                if (_inbound.subscriptionCount.value == 0) return@synchronized false
                val next = held.first()
                val seen = runCatching { next.alreadySeen(next.chat.eventId) }.getOrDefault(false)
                if (!seen) {
                    trackHandedOver(next.chat)
                    if (!_inbound.tryEmit(next.chat)) return@synchronized true
                }
                held.removeFirst()
                heldChars -= next.chat.plaintext.length
                if (!seen) Log.d(TAG, "handed held message ${next.chat.eventId.take(8)}… to the chat reader")
            }
            false
        }

    /** A message goes straight to the reader only if one is there and nothing older is still held (arrival order). */
    private fun emitNow(chat: InboundChat): Boolean {
        if (_inbound.subscriptionCount.value == 0 || hasHeld()) return false
        // G-KI24-4: tracked before the emit, so a reader can never finish it before it is tracked.
        trackHandedOver(chat)
        return _inbound.tryEmit(chat)
    }

    // Set when [dispatch] received a REAL DM/reaction but there was NO live ChatViewModel collector, so the
    // event was left un-persisted-seen for relay redelivery. The pool's cross-relay dedup would swallow that
    // replay, and the cheap periodic watchdog (NostrRelayPool.resubscribeLive) does NOT clear it — so the
    // missed event could previously only recover on a foreground-resume reconnectIfStale (dedup-clearing) or a
    // process restart. This flag lets the watchdog force ONE dedup-clearing backlog replay the moment a
    // collector is alive again (see [consumePendingRedelivery]). Consume-once + the collector-liveness gate
    // bound the full-backlog re-decrypt to at most once per missed-event episode — never every idle tick.
    private val pendingRedelivery = AtomicBoolean(false)

    /**
     * Watchdog hook (called from NostrInboxManager's periodic liveness timer). Returns true — and atomically
     * clears the flag — iff a DM/reaction was missed for lack of a live collector AND a ChatViewModel collector
     * is alive NOW ([_inbound.subscriptionCount] > 0, the same liveness signal [dispatch] uses to decide
     * mark-seen). The caller then forces a dedup-clearing backlog replay ([NostrRelayPool.redeliverBacklog]) so
     * the missed event finally reaches the live collector. Returns false WITHOUT clearing while no collector is
     * alive, so the (costly) dedup clear is deferred until it can actually deliver rather than churning a full
     * backlog re-decrypt into a screen with nothing to receive it.
     */
    fun consumePendingRedelivery(): Boolean {
        // Gate first (see shouldConsumeRedelivery): while no collector is alive, defer WITHOUT clearing so
        // the miss survives to a later tick. getAndSet(false) then consumes-once. The watchdog is the only
        // caller (single-threaded loop) and dispatch only ever SETS the flag, so no concurrent clear races.
        noteEndedHeldConnections()
        if (!shouldConsumeRedelivery(pendingRedelivery.get(), _inbound.subscriptionCount.value)) return false
        return pendingRedelivery.getAndSet(false)
    }

    /**
     * #8 redelivery gate (pure, unit-tested). The watchdog should force ONE dedup-clearing backlog replay
     * only when BOTH a DM/reaction was missed ([pendingRedeliverySet]) AND a ChatViewModel collector is
     * alive now ([subscriberCount] > 0). Otherwise defer: a costly full-backlog re-decrypt with no live
     * collector would land on a screen with nothing to receive it, and the flag must stay set for a later
     * tick. Extracted so the defer-vs-consume decision is testable without driving the whole dispatch path.
     */
    internal fun shouldConsumeRedelivery(pendingRedeliverySet: Boolean, subscriberCount: Int): Boolean =
        pendingRedeliverySet && subscriberCount > 0

    /**
     * #8/#9 redelivery-flag gate (pure, unit-tested), sibling of [shouldConsumeRedelivery]. dispatch()'s
     * post-emit branch must flag a DM for a later dedup-clearing replay ([consumePendingRedelivery] →
     * NostrRelayPool.redeliverBacklog) whenever it did NOT reach a live collector — BOTH the buffer-overflow
     * drop ([emitted] == false, #8) AND the buffered-but-no-collector case ([emitted] && [subscriberCount] ==
     * 0, #9). Only a genuine hand-off to a live collector ([emitted] && [subscriberCount] > 0) must NOT trigger
     * the costly full-backlog re-decrypt. Extracted so the flag decision is testable without the dispatch path.
     */
    internal fun shouldFlagRedelivery(emitted: Boolean, subscriberCount: Int): Boolean =
        !(emitted && subscriberCount > 0)

    /**
     * KI-24 (T1b-2): true when no relay message waits here: none held for a chat screen, none dropped and waiting for a
     * redelivery. Only then may the relay `since` line move (RelaySinceLine): a message held or dropped here was never
     * marked seen, and the relay's replay is its only other copy, so a `since` past it would lose it after a restart.
     */
    fun isRelayBacklogSettled(): Boolean {
        dropEndedHeldConnections(System.currentTimeMillis() / 1000)
        return !hasHeld() && !pendingRedelivery.get()
    }

    // Fixup G-KI24-4 — HANDED OVER, NOT YET HANDLED. [inbound] keeps nothing (replay 0): a message put in a reader's
    // buffer is gone if that reader is cancelled before it reads it (the chat screen closed, a rotation), and it was never
    // marked seen. Some messages a reader does handle stay unmarked on purpose (an unattributed ZBOOT, a group invite
    // unmarked to handle later). For all of them the relays' replay is the only copy, so the relay `since` line must stay
    // at or below their wrap time ([lowestUnhandledWrapSec], read by RelaySinceLine). An entry leaves once a reader has
    // handled that event to the end ([onChatHandled]) and it is marked seen, or once its wrap is too old for any relay
    // to keep it (then dispatch's own 30-day window drops a replay anyway). In memory only: after a restart the relays
    // replay what the line never passed, and it is tracked again when handed over.
    private class Unhandled(
        val wrapCreatedAtSec: Long,
        var readerFinished: Boolean = false,
        val heldConnection: HeldConnection? = null,
    )

    private val unhandled = LinkedHashMap<String, Unhandled>() // guarded by itself

    // Entries dropped for the bound below (still unhandled) keep only their wrap time, in ranges low -> high (seconds):
    // each dropped wrap lies in one range, low <= wrap <= high, and the line stays at or below the lowest low. G-FX-1: a
    // range leaves only once its high is past the age limit, so each dropped wrap holds the line until it is itself too
    // old (one sticky minimum forgot every younger wrap when it aged out). A new range covers one hour; past
    // [DROPPED_RANGES_CAP] ranges the two lowest merge (the merged range lasts until its later high: the safe side).
    private val droppedUnhandled = java.util.TreeMap<Long, Long>() // guarded by [unhandled]

    private fun rememberDroppedUnhandled(wrapSec: Long) {
        val low = Math.floorDiv(wrapSec, DROPPED_RANGE_SEC) * DROPPED_RANGE_SEC
        droppedUnhandled[low] = maxOf(droppedUnhandled[low] ?: wrapSec, wrapSec)
        while (droppedUnhandled.size > DROPPED_RANGES_CAP) {
            val lowestTwo = droppedUnhandled.keys.take(2)
            val merged = maxOf(droppedUnhandled.getValue(lowestTwo[0]), droppedUnhandled.getValue(lowestTwo[1]))
            droppedUnhandled.remove(lowestTwo[1])
            droppedUnhandled[lowestTwo[0]] = merged
        }
    }

    @Volatile private var unhandledPrefs: ZchatPreferences? = null

    private fun trackHandedOver(chat: InboundChat) {
        synchronized(unhandled) {
            unhandled.remove(chat.eventId)
            unhandled[chat.eventId] = Unhandled(chat.wrapCreatedAtSec, heldConnection = chat.heldConnection)
            while (unhandled.size > UNHANDLED_CAP) {
                val oldest = unhandled.entries.iterator().next()
                unhandled.remove(oldest.key)
                // ZC-1349: a kept message never earns the month a range lasts.
                if (oldest.value.heldConnection == null) rememberDroppedUnhandled(oldest.value.wrapCreatedAtSec)
            }
        }
    }

    /**
     * G-KI24-4: the chat screen's reader of [inbound]. [collect] reports each event its handler returned from (also by
     * an early return, or after the handler caught its own failure) to [onChatHandled]; an event whose handling was
     * cancelled midway is not reported, so it keeps the relay `since` line below its wrap.
     */
    class InboundReader internal constructor() {
        suspend fun collect(handle: suspend (InboundChat) -> Unit) {
            inbound.collect { chat ->
                // ZC-1337: not handled, so the relay line stays below it until the hold ends and its copy comes back.
                if (chat.heldConnection != null) return@collect
                handle(chat)
                onChatHandled(chat.eventId)
            }
        }
    }

    val inboundReader: InboundReader = InboundReader()

    /** The chat screen's reader handled [eventId] to the end (it was not cancelled midway). */
    fun onChatHandled(eventId: String) {
        synchronized(unhandled) { unhandled[eventId]?.readerFinished = true }
    }

    /**
     * The lowest outer created_at (seconds) of a message handed to a chat reader that no reader has handled to the end
     * and marked seen, or null for none. Entries a reader finished and the seen list holds, and entries whose wrap is
     * older than [UNHANDLED_MAX_AGE_SEC] before [nowSec], are dropped here; a message dropped from the bounded list
     * counts until its own wrap is that old (the value may then be up to an hour, or a merged range, below it). ZC-1349: a
     * message kept for a held connection leaves earlier, see [heldConnectionEnded].
     */
    fun lowestUnhandledWrapSec(nowSec: Long): Long? {
        dropEndedHeldConnections(nowSec)
        val prefs = unhandledPrefs
        val tooOld = nowSec - UNHANDLED_MAX_AGE_SEC
        val finished =
            synchronized(unhandled) {
                unhandled.entries.filter { it.value.readerFinished }.map { it.key }
            }
        // Read the seen list outside this lock (the store takes its own).
        val handled = finished.filter { id -> prefs != null && runCatching { prefs.hasSeenNostrEvent(id) }.getOrDefault(false) }
        return synchronized(unhandled) {
            handled.forEach { id -> if (unhandled[id]?.readerFinished == true) unhandled.remove(id) }
            unhandled.entries.removeAll { it.value.wrapCreatedAtSec < tooOld }
            droppedUnhandled.entries.removeAll { it.value < tooOld }
            val lowest = unhandled.values.minOfOrNull { it.wrapCreatedAtSec }
            listOfNotNull(lowest, droppedUnhandled.keys.firstOrNull()).minOrNull()
        }
    }

    /** KI-24: a relay message was lost before it reached [dispatch] (NostrInboxManager's buffer was full). */
    fun flagRedelivery() {
        pendingRedelivery.set(true)
    }

    // #251 — sentinel [InboundChat.peerAddress] for a ZBOOT that arrived from a NOSTR pubkey we do NOT
    // yet hold (a peer's NEW key after rotation, or an idx-pre-swapped seal), so it can't be attributed
    // by sender pubkey. The chat layer (ChatViewModel.attributeBoot) attributes it by the
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

    /**
     * Result of [publish]: relay ack count + the STABLE rumor id of the sent message (for reaction / reply correlation;
     * both sender and recipient derive the identical id from the rumor).
     *
     * ZC-393: [acks] counts the relays every ZChat version reads (ZChat's relay, nos.lol) that took it: `acks > 0` is
     * "delivered". [readableAcks] counts any relay that took it and serves us (primal or a fallback too): the five
     * free-first relay copies with an on-chain fallback decide on it, so the stricter count never starts a spend. A
     * result built as `PublishResult(acks, id)` means both.
     */
    data class PublishResult(
        val acks: Int,
        val messageId: String?,
        val readableAcks: Int = acks,
    )

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
     * ZC-393: the kept-wrap resend hook the foreground service installs beside the publisher (NostrInboxManager.resendKept):
     * a Retry of a message only a public relay took re-sends the wrap the relay outbox keeps (same event id, same rumor id)
     * instead of making a second one. It answers null when nothing is kept (a restart, dropped, older than 24 h).
     */
    @Volatile private var keptResender: (suspend (rumorId: String, recipientPubkeyHex: String) -> PublishResult?)? = null

    fun registerKeptResender(fn: suspend (rumorId: String, recipientPubkeyHex: String) -> PublishResult?) {
        keptResender = fn
    }

    fun unregisterKeptResender() {
        keptResender = null
    }

    /** ZC-393: re-send the kept wrap of [rumorId] to [recipientPubkeyHex]; null when nothing is kept or no hook is installed. */
    suspend fun resendKept(
        rumorId: String,
        recipientPubkeyHex: String,
    ): PublishResult? {
        val fn = keptResender ?: return null
        return fn(rumorId, recipientPubkeyHex)
    }

    // ── ZC-421: the same message is the same rumor ───────────────────────────────────────────────────────────────────
    //
    // The foreground service installs these beside the publisher (NostrInboxManager.sendRumorAt and rumorIdAt): a relay
    // row keeps the second and the id of its rumor, and a Retry with nothing kept makes that rumor again (same id) in a new
    // wrap, so every receiver, 2.13 and 2.12.4 alike, files both copies under one id and shows one.

    @Volatile private var rumorPublisher:
        (suspend (plaintext: String, recipientPubkeyHex: String, rumorAtSec: Long, expectedRumorId: String?) -> PublishResult?)? = null

    @Volatile private var rumorIdOf: ((plaintext: String, recipientPubkeyHex: String, rumorAtSec: Long) -> String?)? = null

    /** ZC-421: installs the rumor publisher and, in the same call, the pure id function of the rumor it would publish. */
    fun registerRumorPublisher(
        publish: suspend (plaintext: String, recipientPubkeyHex: String, rumorAtSec: Long, expectedRumorId: String?) -> PublishResult?,
        rumorIdAt: (plaintext: String, recipientPubkeyHex: String, rumorAtSec: Long) -> String?,
    ) {
        rumorIdOf = rumorIdAt
        rumorPublisher = publish
    }

    fun unregisterRumorPublisher() {
        rumorPublisher = null
        rumorIdOf = null
    }

    /** ZC-421: true while the rumor publisher is installed. */
    fun canPublishRumorAt(): Boolean = rumorPublisher != null && rumorIdOf != null

    /**
     * ZC-421: publish [plaintext] as a rumor made at [rumorAtSec] in a new wrap. Null when no hook is installed, or when
     * [expectedRumorId] is given and the rumor would have another id (then nothing is published).
     */
    suspend fun publishRumorAt(
        plaintext: String,
        recipientPubkeyHex: String,
        rumorAtSec: Long,
        expectedRumorId: String? = null,
    ): PublishResult? {
        val fn = rumorPublisher ?: return null
        return fn(plaintext, recipientPubkeyHex, rumorAtSec, expectedRumorId)
    }

    /** ZC-421: the id the rumor of [publishRumorAt] would have; nothing is published. Null when no hook is installed. */
    fun rumorIdAt(
        plaintext: String,
        recipientPubkeyHex: String,
        rumorAtSec: Long,
    ): String? = rumorIdOf?.invoke(plaintext, recipientPubkeyHex, rumorAtSec)

    /** ZC-421: a readable relay, shared or public, took [result] (so a copy of that rumor may reach the contact). */
    fun relayTookIt(result: PublishResult?): Boolean = result != null && (result.acks > 0 || result.readableAcks > 0)

    // ── ZC-420: marks of free-first relay copies only a public relay took ─────────────────────────────────────────────
    //
    // The five free-first relay copies (ZBOOT, rotation announce, group memos) keep counting any readable relay as
    // delivered, so the stricter count never starts a payment (ZC-393 R-1). A copy that a readable relay took and neither
    // ZChat's relay nor nos.lol took is remembered here, durably, and delivered again over the relays for free
    // ([repairRelayCopy]). A mark never gates a send or a payment; no hook (the service is down, or a test) means no marks.

    /** The mark store the foreground service installs (ZchatPreferences' encrypted held-reply store). */
    @Volatile private var relayCopyMarks: RelayCopyMarks? = null

    // Every read-compare-write of a mark runs under this lock (the store has no compare-and-set).
    private val relayCopyLock = Any()

    // The slots a repair is running for (one repair per slot at a time, process-wide).
    private val relayCopyRepairs: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    fun registerRelayCopyMarks(marks: RelayCopyMarks) {
        relayCopyMarks = marks
    }

    fun unregisterRelayCopyMarks() {
        relayCopyMarks = null
    }

    /** Whether a mark store is installed (a caller with nothing else to do returns before it lists anything). */
    fun relayCopyMarksReady(): Boolean = relayCopyMarks != null

    /**
     * The mark BEFORE a publish of [slot] (no rumor id yet; it replaces an older mark of the slot): a kill during the
     * publish, or during an on-chain wait after it, leaves a mark the repair finds. Never throws; a mark that could not be
     * written is logged (that copy then has today's behaviour).
     */
    fun beginRelayCopy(
        slot: String,
        extra: String = "",
    ) {
        val marks = relayCopyMarks ?: return
        runCatching {
            synchronized(relayCopyLock) { putRelayCopyMarkLocked(marks, slot, RelayCopyMark("", System.currentTimeMillis(), extra)) }
        }.onFailure { Log.w(RELAY_COPY_TAG, "${RelayCopySlots.kindOf(slot)} mark not written: ${it.javaClass.simpleName}") }
    }

    /**
     * What the publish of the copy [extra] of [slot] answered: a relay every version reads took it, so the mark of THIS
     * copy goes (another copy's mark, or none, is left as it is); only a public relay took it, so the mark names its rumor
     * id (not over a newer copy's mark); no relay, or no result, changes nothing. Never throws.
     */
    fun noteRelayCopy(
        slot: String,
        result: PublishResult?,
        extra: String = "",
    ) {
        if (result == null) return
        val marks = relayCopyMarks ?: return
        runCatching {
            synchronized(relayCopyLock) {
                val current = RelayCopyMark.decode(marks.get(slot))
                val rumorId = result.messageId
                when {
                    result.acks > 0 -> if (current != null && current.extra == extra) marks.remove(slot)
                    result.readableAcks > 0 && !rumorId.isNullOrEmpty() && (current == null || current.extra == extra) -> {
                        putRelayCopyMarkLocked(marks, slot, RelayCopyMark(rumorId, System.currentTimeMillis(), extra))
                        Log.i(RELAY_COPY_TAG, "${RelayCopySlots.kindOf(slot)} copy ${rumorId.take(8)} marked: only a public relay took it")
                    }
                    else -> Unit
                }
            }
        }.onFailure { Log.w(RELAY_COPY_TAG, "${RelayCopySlots.kindOf(slot)} mark not noted: ${it.javaClass.simpleName}") }
    }

    /** The marked slots that start with [prefix], oldest mark first; empty with no mark store. */
    fun unconfirmedRelayCopies(prefix: String): List<String> {
        val marks = relayCopyMarks ?: return emptyList()
        return runCatching {
            marks
                .slots()
                .filter { it.startsWith(prefix) }
                .mapNotNull { slot -> RelayCopyMark.decode(marks.get(slot))?.let { slot to it.markedAtMs } }
                .sortedBy { it.second }
                .map { it.first }
        }.getOrElse {
            Log.w(RELAY_COPY_TAG, "marks not listed: ${it.javaClass.simpleName}")
            emptyList()
        }
    }

    /** The extra of [slot]'s mark, or null when it is not marked. */
    fun relayCopyExtra(slot: String): String? = readRelayCopyMark(slot)?.extra

    /** The mark of [slot] as stored, or null (no store, no mark, or a read that failed). */
    private fun readRelayCopyMark(slot: String): RelayCopyMark? {
        val marks = relayCopyMarks ?: return null
        return runCatching { RelayCopyMark.decode(marks.get(slot)) }.getOrNull()
    }

    /** Whether [slot]'s mark was written before its publish less than [withinMs] ago (that publish may still be running). */
    fun relayCopyJustBegun(
        slot: String,
        withinMs: Long,
    ): Boolean {
        val mark = readRelayCopyMark(slot) ?: return false
        return mark.rumorId.isEmpty() && System.currentTimeMillis() - mark.markedAtMs < withinMs
    }

    fun forgetRelayCopy(slot: String) {
        val marks = relayCopyMarks ?: return
        runCatching { synchronized(relayCopyLock) { marks.remove(slot) } }
            .onFailure { Log.w(RELAY_COPY_TAG, "${RelayCopySlots.kindOf(slot)} mark not removed: ${it.javaClass.simpleName}") }
    }

    /**
     * Confirm the copy [slot]'s mark stands for, or deliver it again over the relays to [recipientPubkeyHex]; never
     * on-chain. With a rumor id the kept wrap is asked first ([resendKept]): a relay every version reads holds it, the
     * mark goes; it is kept but not there yet, nothing new is made (the outbox keeps trying). With nothing kept (or no
     * rumor id: the mark was written before a publish that never answered), [rebuild] makes the copy again and it is
     * published once; its answer is noted as a send's. A null [rebuild] keeps the mark. The mark is removed or replaced
     * only while the slot still holds the value read at the start; otherwise a newer copy owns the slot and this answer
     * is dropped.
     *
     * ZC-658: a kept wrap that reaches ZChat's relay or nos.lol more than [LATE_KEPT_WRAP_MS] after it was made can lie
     * below a 2.13 receiver's `since` line (its time is back-dated by up to 48 h; RelaySinceLine keeps 49 h), and that
     * receiver drops it, so a mark that old is rebuilt instead. Never for an invite: a rebuilt invite is a new invitation.
     */
    suspend fun repairRelayCopy(
        slot: String,
        recipientPubkeyHex: String,
        rebuild: suspend () -> String?,
    ): RelayRepair {
        val marks = relayCopyMarks ?: return RelayRepair.NOT_MARKED
        val read = runCatching { marks.get(slot) }.getOrNull() ?: return RelayRepair.NOT_MARKED
        val mark = RelayCopyMark.decode(read)
        if (mark == null) {
            forgetRelayCopy(slot)
            return RelayRepair.NOT_MARKED
        }
        if (!relayCopyRepairs.add(slot)) return RelayRepair.KEPT_UNCONFIRMED
        val kind = RelayCopySlots.kindOf(slot)
        try {
            val late = kind != RelayCopySlots.GROUP_INVITE && System.currentTimeMillis() - mark.markedAtMs >= LATE_KEPT_WRAP_MS
            val kept = if (mark.rumorId.isEmpty() || late) null else resendKept(mark.rumorId, recipientPubkeyHex)
            if (kept != null) {
                if (kept.acks == 0) return RelayRepair.KEPT_UNCONFIRMED
                val removed = replaceRelayCopyIfUnchanged(marks, slot, read, null)
                if (removed) Log.i(RELAY_COPY_TAG, "$kind copy ${mark.rumorId.take(8)} confirmed from its kept wrap")
                return if (removed) RelayRepair.CONFIRMED else RelayRepair.KEPT_UNCONFIRMED
            }
            val memo = rebuild() ?: return RelayRepair.NOT_SENT
            val sent = publish(memo, recipientPubkeyHex)
            val rumorId = sent.messageId
            return when {
                sent.acks > 0 ->
                    if (replaceRelayCopyIfUnchanged(marks, slot, read, null)) {
                        Log.i(RELAY_COPY_TAG, "$kind copy re-sent as ${rumorId.orEmpty().take(8)} (${sent.acks} shared)")
                        RelayRepair.CONFIRMED
                    } else {
                        RelayRepair.KEPT_UNCONFIRMED
                    }
                sent.readableAcks > 0 && !rumorId.isNullOrEmpty() ->
                    if (replaceRelayCopyIfUnchanged(marks, slot, read, RelayCopyMark(rumorId, System.currentTimeMillis(), mark.extra))) {
                        Log.i(RELAY_COPY_TAG, "$kind copy re-sent as ${rumorId.take(8)}: only a public relay took it again")
                        RelayRepair.RESENT_UNCONFIRMED
                    } else {
                        RelayRepair.KEPT_UNCONFIRMED
                    }
                else -> RelayRepair.NOT_SENT
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(RELAY_COPY_TAG, "$kind copy not repaired: ${e.javaClass.simpleName}")
            return RelayRepair.NOT_SENT
        } finally {
            relayCopyRepairs.remove(slot)
        }
    }

    /** Under the lock: [slot] still holds [read], so the mark is removed ([next] null) or replaced. False when it does not. */
    private fun replaceRelayCopyIfUnchanged(
        marks: RelayCopyMarks,
        slot: String,
        read: String,
        next: RelayCopyMark?,
    ): Boolean =
        runCatching {
            synchronized(relayCopyLock) {
                if (marks.get(slot) != read) return@synchronized false
                if (next == null) {
                    marks.remove(slot)
                    true
                } else {
                    putRelayCopyMarkLocked(marks, slot, next)
                }
            }
        }.getOrDefault(false)

    /**
     * Under [relayCopyLock]: writes [mark] to [slot] within the limits. A new message mark ("gm") at [MAX_MESSAGE_MARKS]
     * pushes out the oldest message mark; identity, key and invite marks are counted apart, at most [MAX_OTHER_MARKS]
     * together, never pushed out: one more is not written. Both are logged. A replacement of an existing mark is always
     * written. False when nothing reached the disk.
     */
    private fun putRelayCopyMarkLocked(
        marks: RelayCopyMarks,
        slot: String,
        mark: RelayCopyMark,
    ): Boolean {
        val kind = RelayCopySlots.kindOf(slot)
        if (marks.get(slot) == null) {
            val all = marks.slots()
            if (kind == RelayCopySlots.GROUP_MESSAGE) {
                val messages = all.filter { RelayCopySlots.kindOf(it) == RelayCopySlots.GROUP_MESSAGE }
                if (messages.size >= MAX_MESSAGE_MARKS) {
                    val oldest = messages.minByOrNull { RelayCopyMark.decode(marks.get(it))?.markedAtMs ?: Long.MIN_VALUE }
                    if (oldest != null) {
                        marks.remove(oldest)
                        Log.w(RELAY_COPY_TAG, "gm marks at $MAX_MESSAGE_MARKS: the oldest is dropped for a new one")
                    }
                }
            } else if (all.count { RelayCopySlots.kindOf(it) != RelayCopySlots.GROUP_MESSAGE } >= MAX_OTHER_MARKS) {
                Log.w(RELAY_COPY_TAG, "$kind mark not written: $MAX_OTHER_MARKS identity, key and invite marks stand")
                return false
            }
        }
        val written = marks.put(slot, mark.encode())
        if (!written) Log.w(RELAY_COPY_TAG, "$kind mark did not reach the disk")
        return written
    }

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

    fun registerRequestNotifier(fn: (ZchatPreferences.MessageRequest) -> Unit) {
        synchronized(requestNotifyTimesMs) { requestNotifyTimesMs.clear() }
        requestNotifier = fn
    }

    // ZC-1404: the monotonic times of the request alerts raised in the current window.
    private val requestNotifyTimesMs = ArrayDeque<Long>()
    private const val REQUEST_NOTIFY_BURST = 10
    private const val REQUEST_NOTIFY_WINDOW_MS = 60_000L

    private fun takeRequestNotifySlot(): Boolean =
        synchronized(requestNotifyTimesMs) {
            val now = System.nanoTime() / 1_000_000L
            while (requestNotifyTimesMs.isNotEmpty() && now - requestNotifyTimesMs.first() >= REQUEST_NOTIFY_WINDOW_MS) {
                requestNotifyTimesMs.removeFirst()
            }
            if (requestNotifyTimesMs.size >= REQUEST_NOTIFY_BURST) {
                false
            } else {
                requestNotifyTimesMs.addLast(now)
                true
            }
        }
    fun unregisterRequestNotifier() { requestNotifier = null }

    // R9-6 (REVIEW_R8 F6) — a relay message from a mapped contact fires this so the foreground service can raise the
    // chat alert an on-chain message gets (RelayMessageNotifier: privacy level, mute, foreground banner, replay dedup).
    // dispatch calls it after its replay and freshness checks and BEFORE the hand-off to the chat collector, so the
    // collector's mark-seen can never race it; a failing notifier never blocks delivery.
    @Volatile private var chatNotifier: ((InboundChat) -> Unit)? = null

    fun registerChatNotifier(fn: (InboundChat) -> Unit) {
        chatNotifier = fn
    }

    fun unregisterChatNotifier() {
        chatNotifier = null
    }

    // B8 — armed one-shot "open the Requests sheet" signal (survives cold-start nav); the chat-list screen
    // consumes it once its request list has seeded. Set by a notification/in-app-banner tap.
    private val _openRequestsSheetArmed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val openRequestsSheetArmed: kotlinx.coroutines.flow.StateFlow<Boolean> = _openRequestsSheetArmed.asStateFlow()
    fun armOpenRequestsSheet() { _openRequestsSheetArmed.value = true }
    fun clearOpenRequestsSheetArm() { _openRequestsSheetArmed.value = false }

    /**
     * Phone finding of train T6b (relays after background): starts the relay inbox (the sync service) when it is not
     * running. Registered by the app while a wallet is ready ([registerInboxStarter]); Android may stop the service in
     * the background (its onDestroy unregisters [inboxRefresher]), and nothing else started it again.
     */
    @Volatile private var inboxStarter: (() -> Unit)? = null

    fun registerInboxStarter(fn: () -> Unit) {
        inboxStarter = fn
    }

    fun unregisterInboxStarter() {
        inboxStarter = null
    }

    /**
     * Kick the running inbox to reconnect if its relay subscriptions look stale. With no inbox running (the service is
     * down) the registered starter starts it; with neither, nothing happens.
     */
    fun refreshInbox() {
        val refresher = inboxRefresher
        if (refresher != null) refresher() else inboxStarter?.invoke()
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
        unhandledPrefs = prefs
        // Resolve the sender's peer address FIRST — both chat DMs and call signals must
        // come from a contact whose relay key we hold (any mode). Anything else is an unsolicited gift
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
                val boot =
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
                        rumorTags = dm.rumorTags,
                        wrapCreatedAtSec = dm.wrapCreatedAtSec,
                    )
                if (emitNow(boot)) {
                    Log.d(TAG, "#251 routing unknown-sender ZBOOT for authenticated rotation attribution")
                } else {
                    // T1b-2: no reader (or its buffer is full): hold it for the next chat screen instead of losing it.
                    // Never marked seen (routeIncomingBoot dedups on the signature), so nothing is skipped at hand-over.
                    hold(boot) { false }
                    Log.d(TAG, "#251 holding unknown-sender ZBOOT until a chat screen reads")
                }
                return
            }
            // #224 OPEN-from-message-#1: an unknown pubkey sending a ZMSG v4 INIT is a first-contact
            // request. We do NOT auto-trust it — the gift wrap authenticates only the NOSTR key, NOT that
            // it owns the CLAIMED Zcash address in the INIT body — so we hold it in a Message Requests
            // inbox for the user to manually accept (TOFU gate). A non-INIT DM from an unknown pubkey is
            // still dropped silently (ordinary spam is the steady state).
            // ZC-1337: except from the relay key a held ZBOOT names: that one is kept for after the hold.
            if (keepForHeldConnection(dm, prefs)) return
            routeUnknownPubkeyAsRequest(dm, prefs)
            return
        }
        // PERSISTENT REPLAY DROP (#188): relays replay stored gift-wraps on every (re)subscribe and the
        // same event lands on multiple relays. Drop anything whose unique event id we've already handled
        // — for BOTH chat DMs and call signals, and across process restarts. This is the dedicated
        // replay defense the freshness windows below merely backstop; we mark an id seen only AFTER it's
        // been handled (below), so a buffer-full drop isn't permanently suppressed and can still redeliver.
        // KI-24: also a wrap older than the seen list's floor (its id may have left the list; never above now − 49 h).
        if (prefs.hasSeenNostrEvent(dm.eventId, dm.wrapCreatedAtSec)) {
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
            // ZC-1883 (owner W-146): a call from the ZChat Team contact (its address, an alias of it, or its configured
            // relay key) never rings, in any mode. Marked seen so a relay replay of the same wrap is not judged again.
            if (TeamCallBlock.isTeamSender(peer, prefs.resolvePeerAddress(peer), dm.senderPubkeyHex)) {
                Log.w(TAG, "DROPPED ZCALL from the ZChat Team contact (calls with the Team are off)")
                prefs.markNostrEventSeen(dm.eventId, dm.wrapCreatedAtSec)
                return
            }
            // ZC-1880: the same rule the caller's call buttons use (call-capable mode OR the peer's relay key is held).
            // A call is free in every mode: it travels over the relays, never on the chain. The sender was resolved
            // above by its relay key, so a Shielded (VAULT) chat with that key rings like Open instead of dropping.
            val mode = prefs.getConversationMode(peer)
            if (mode.supportsCalls || prefs.getPeerNostrPubkey(peer) != null) {
                deliverCallSignal(dm.senderPubkeyHex, callEnv)
                // Mark handled only after delivering, so a replay of THIS signal can't double-ring.
                prefs.markNostrEventSeen(dm.eventId, dm.wrapCreatedAtSec)
            } else {
                Log.w(TAG, "DROPPED ZCALL — mode=$mode and no relay key held for the contact")
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
        // Two-sided: shed both absurdly-OLD replays and absurdly-FUTURE (clock-forged) created_at. A crafted
        // far-future timestamp would otherwise pass an old-only check and reach the chat layer, where an
        // unclamped Instant.ofEpochSecond used to THROW and cancel the whole inbound collector (dead messaging
        // until restart). The chat layer also clamps defensively; this sheds the bogus event at the door.
        if (chatAgeSec > CHAT_MSG_MAX_AGE_SEC || chatAgeSec < -CHAT_MSG_MAX_AGE_SEC) {
            Log.w(TAG, "ignoring out-of-window chat DM from $peer (age=${chatAgeSec}s, |age| > ${CHAT_MSG_MAX_AGE_SEC}s)")
            return
        }
        val chat =
            InboundChat(
                peerAddress = peer,
                plaintext = dm.content,
                createdAtSec = dm.createdAtSec,
                eventId = dm.eventId,
                rumorId = dm.rumorId,
                recipientPubkeyHex = dm.recipientPubkeyHex,
                rumorTags = dm.rumorTags,
                wrapCreatedAtSec = dm.wrapCreatedAtSec,
            )
        // R9-6: alert the user (the notifier skips control payloads, replays, muted chats and the Silent level).
        chatNotifier?.let { notify ->
            runCatching { notify(chat) }.onFailure { Log.w(TAG, "chat notifier failed for ${dm.eventId.take(8)}…", it) }
        }
        // T1b-2: straight to a live reader, else HELD (see [hold]) until a chat screen reads. At 00089d8d9 a message
        // with no reader was emitted into nothing (replay 0) and one that met a full buffer was dropped.
        val readers = _inbound.subscriptionCount.value
        val emitted = emitNow(chat)
        if (!emitted) hold(chat) { prefs.hasSeenNostrEvent(it) }
        // #8 + #9: a DM that did NOT reach a live collector — whether the inbound buffer overflowed
        // (!emitted, #8) OR the value was buffered with NO live collector (emitted && subscriptionCount == 0,
        // #9) — must be flagged for a later dedup-clearing replay. Previously the #8 overflow drop was logged
        // but left UN-flagged, so only a foreground reconnect could ever recover it (apparent message loss);
        // shouldFlagRedelivery now covers both misses. The pool's cross-relay dedup still holds this event id,
        // so a plain periodic re-subscribe (resubscribeLive) would swallow the relay replay — only a dedup
        // CLEAR redelivers it, which the liveness watchdog forces exactly once ([consumePendingRedelivery] →
        // NostrRelayPool.redeliverBacklog) the moment a collector is alive again.
        //
        // #9: dispatch NO LONGER marks a DM seen here. Marking from a subscriptionCount SNAPSHOT at emit time
        // was wrong — `_inbound` is replay=0, so a value buffered for a collector that CANCELS before draining
        // it is silently discarded yet was already persisted as seen, permanently suppressing the relay replay
        // = PERMANENT DM loss. Mark-seen moved to the ChatViewModel consumer (observeNostrInbound), which marks
        // only once it has actually RECEIVED the value. The unknown-sender call/request paths above keep their
        // own mark-after-handling; only the known-peer DM hand-off is delegated to the consumer.
        // T1b-2: the flag stays as a safety net behind the held list (a hand-over the reader cancels before draining, or
        // one dropped from a full list): the relays' copy, never marked seen, is replayed once a reader is alive.
        if (shouldFlagRedelivery(emitted, readers)) {
            pendingRedelivery.set(true)
            if (readers > 0) {
                Log.w(TAG, "inbound buffer full — holding chat DM from $peer (collector paused or burst > buffer)")
            } else {
                Log.w(TAG, "no active chat collector — holding $peer DM ${dm.eventId.take(8)}… until a chat screen reads")
            }
        }
    }

    /**
     * ZC-1337 — a relay message from the key a held ZBOOT names ("Connection held back"). It grants nothing and shows nothing:
     * it is not marked seen, it holds the relay line (held here, or handed to a reader that leaves it unhandled), and once the
     * key is bound ([noteEndedHeldConnections]) one redelivery brings its relay copy through the normal path. A relay copy is
     * the store: no plaintext at rest, and after a restart the relays replay it from below the line.
     * @return whether the message was taken here (kept, or a replay or out-of-window copy that nothing would show).
     */
    private fun keepForHeldConnection(
        dm: NostrInboxManager.InboundDm,
        prefs: ZchatPreferences,
    ): Boolean {
        val peer = runCatching { prefs.findPeerByHeldBootNostrPubkey(dm.senderPubkeyHex) }.getOrNull() ?: return false
        // An INIT stays a Message Request, as from any unknown key; a blocked key stays dropped.
        if (co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
                .parseV4Init(dm.content) != null ||
            prefs.isNostrPubkeyBlocked(dm.senderPubkeyHex)
        ) {
            return false
        }
        val ageSec = (System.currentTimeMillis() / 1000) - dm.createdAtSec
        if (prefs.hasSeenNostrEvent(dm.eventId, dm.wrapCreatedAtSec) || ageSec > CHAT_MSG_MAX_AGE_SEC || ageSec < -CHAT_MSG_MAX_AGE_SEC) {
            return true
        }
        // ZC-1349: the hours count from the first arrival, also for the copy the relays replay after a restart.
        val nowSec = System.currentTimeMillis() / 1000
        val arrivalSec =
            runCatching { prefs.getHeldConnectionArrivalSec(dm.eventId) }.getOrNull()
                ?: nowSec.also { runCatching { prefs.setHeldConnectionArrivalSec(dm.eventId, it) } }
        if (nowSec - arrivalSec >= HELD_CONNECTION_MAX_HOLD_SEC) return true
        heldConnectionKeys += dm.senderPubkeyHex
        val chat =
            InboundChat(
                peerAddress = peer,
                plaintext = dm.content,
                createdAtSec = dm.createdAtSec,
                eventId = dm.eventId,
                rumorId = dm.rumorId,
                recipientPubkeyHex = dm.recipientPubkeyHex,
                rumorTags = dm.rumorTags,
                wrapCreatedAtSec = dm.wrapCreatedAtSec,
                heldConnection = HeldConnection(dm.senderPubkeyHex, arrivalSec),
            )
        if (!emitNow(chat)) hold(chat) { prefs.hasSeenNostrEvent(it) }
        Log.d(TAG, "kept relay message ${dm.eventId.take(8)}… from a key whose ZBOOT is held")
        return true
    }

    // ZC-1337: the relay keys whose messages were kept for after a hold, in this process. A restart needs none: the line stayed.
    private val heldConnectionKeys: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    /**
     * ZC-1349 — a kept message stops holding the relay line [HELD_CONNECTION_MAX_HOLD_SEC] after its arrival, or at once when
     * its key is blocked or nothing names that key any more (the held ZBOOT is gone: delete, block, the hold ended unbound).
     * A key now bound stays until its redelivered copy replaces the entry. A store that cannot be read ends nothing.
     */
    private fun heldConnectionEnded(
        kept: HeldConnection,
        nowSec: Long,
    ): Boolean {
        if (nowSec - kept.arrivalSec >= HELD_CONNECTION_MAX_HOLD_SEC) return true
        val prefs = unhandledPrefs ?: return false
        return runCatching {
            prefs.isNostrPubkeyBlocked(kept.senderPubkeyHex) ||
                (
                    prefs.findPeerByHeldBootNostrPubkey(kept.senderPubkeyHex) == null &&
                        prefs.findPeerByNostrPubkey(kept.senderPubkeyHex) == null
                )
        }.getOrDefault(false)
    }

    /** ZC-1349: both places a kept message can hold the line. The store is read outside these locks. */
    private fun dropEndedHeldConnections(nowSec: Long) {
        val inList = synchronized(unhandled) { unhandled.values.mapNotNull { it.heldConnection } }
        val inHeld = synchronized(held) { held.mapNotNull { it.chat.heldConnection } }
        if (inList.isEmpty() && inHeld.isEmpty()) return
        val ended = (inList + inHeld).distinct().filter { heldConnectionEnded(it, nowSec) }.toSet()
        if (ended.isEmpty()) return
        synchronized(unhandled) { unhandled.values.removeAll { it.heldConnection in ended } }
        synchronized(held) {
            val gone = held.filter { it.chat.heldConnection in ended }
            held.removeAll(gone)
            gone.forEach { heldChars -= it.chat.plaintext.length }
        }
    }

    /** ZC-1337: a kept key that is now bound to a contact asks for one redelivery, which brings its messages through [dispatch]. */
    private fun noteEndedHeldConnections() {
        if (heldConnectionKeys.isEmpty()) return
        val prefs = unhandledPrefs ?: return
        val ended = heldConnectionKeys.filter { key -> runCatching { prefs.findPeerByNostrPubkey(key) != null }.getOrDefault(false) }
        if (ended.isNotEmpty()) {
            heldConnectionKeys.removeAll(ended.toSet())
            pendingRedelivery.set(true)
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
        // KI-24: also a wrap older than the seen list's floor, so a handled request whose id left the list stays gone.
        if (prefs.hasSeenNostrEvent(dm.eventId, dm.wrapCreatedAtSec)) {
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
            prefs.markNostrEventSeen(dm.eventId, dm.wrapCreatedAtSec)
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
        try {
            prefs.addMessageRequest(request)
        } catch (_: ZchatPreferences.MessageRequestStoreFullException) {
            // ZC-1404: the store keeps its older requests; this one is not stored and its wrap stays unseen, so a
            // relay replay offers it again once the user has handled some requests.
            Log.w(TAG, "request store full — not storing the request from ${dm.senderPubkeyHex.take(8)}…")
            return
        }
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
        prefs.markNostrEventSeen(dm.eventId, dm.wrapCreatedAtSec)
        val emitted = _messageRequests.tryEmit(request)
        // B8 — raise the global alert. ZC-1404: at most REQUEST_NOTIFY_BURST alerts per REQUEST_NOTIFY_WINDOW_MS, so a
        // flood from throwaway keys cannot raise one notification per request (every request is still stored and listed).
        if (takeRequestNotifySlot()) requestNotifier?.invoke(request)
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

    // T1b-2: bounds of the held list (INVESTIGATION.md §3/6 fix item 1). 512 messages; and ~4M characters, so held
    // unattributed ZBOOTs from strangers can't grow it past a few MB.
    private const val HELD_CAP = 512
    private const val HELD_MAX_CHARS = 4_000_000L

    // T1b-2: how soon a hand-over that met a full reader buffer is tried again.
    private const val HAND_OVER_RETRY_MS = 50L

    // G-KI24-4: how many handed-over, unhandled messages are kept by id (past it the oldest keep only their wrap time).
    private const val UNHANDLED_CAP = 4_096

    // G-FX-1: the wrap-time ranges kept for messages dropped from that list: one hour each when new, at most 1,024
    // (a few tens of KB; the 30-day relay window spans about 770 hours).
    private const val DROPPED_RANGE_SEC = 60L * 60
    private const val DROPPED_RANGES_CAP = 1_024

    // G-KI24-4: a wrap older than this cannot come back: relays keep a gift-wrap about 30 days, and a rumor is at most
    // 48 h younger than its wrap, so dispatch's 30-day window drops such a replay anyway.
    private const val UNHANDLED_MAX_AGE_SEC = CHAT_MSG_MAX_AGE_SEC + 49L * 60 * 60

    // ZC-1349: how long, from its arrival, a message from a held ZBOOT's relay key may keep the relay `since` line down.
    private const val HELD_CONNECTION_MAX_HOLD_SEC = 24L * 60 * 60
}
