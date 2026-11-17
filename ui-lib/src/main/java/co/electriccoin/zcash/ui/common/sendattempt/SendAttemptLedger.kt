package co.electriccoin.zcash.ui.common.sendattempt

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * F8 (fix-all 2.13.0, train T6b; ZC-112, ZC-121, ZC-600): the send-attempt ledger of the senders that have no chat row:
 * the automatic handshakes (KEX, KEXACK, ZBOOT), the Join reply, the key grant, the paid timer and the locked messages.
 *
 * One entry per memo. It is written before the SDK is asked (LIVE), gets the stored transaction ids before the first
 * submit, and is then judged from the chain by the history pass: MINED until every id is deep, or NOT_SENT once F4's
 * proof says nothing of it can reach the chain. A NOT_SENT entry is the visible failed state; nothing is paid for it
 * again unless the user taps Retry (or Join) on exactly that state and confirms its price.
 *
 * [failClosed]: no transaction of the kind is handed to the SDK without its durable entry. The two note-only kinds
 * are fail-open: their entry only decides whether a note appears later and nothing is ever re-sent for them.
 */
enum class SendAttemptKind(
    val failClosed: Boolean = true,
) {
    KEX,
    KEXACK,
    ZBOOT,
    GROUP_ACCEPT,
    INVITE,
    GROUP_MESSAGE,
    KEY_MEMO,
    ANNOUNCE,
    TIMER(failClosed = false),
    TIME_LOCK(failClosed = false),
    KEY_GRANT,

    /** A local write-ahead record (the group side): never judged by the chain and never gated. */
    ROTATION,
}

enum class SendAttemptState {
    /** Begun; the ids may or may not be recorded; the chain has not decided. */
    LIVE,

    /** Every id mined, the highest at [SendAttemptEntry.minedHeight]; kept until every id is reorg-deep. */
    MINED,

    /** Proven never mined and committed: the visible failed state. Only the proof writes it. */
    NOT_SENT,
}

/**
 * One attempt. [startedAtMillis] is the attempt's marker (unique per attempt). [accountHash] is null only on a record
 * an earlier build wrote; [idsBeforeSubmit] is true on every entry this build begins: its ids, if it has none, were
 * never handed to the network. [minedHeight] is non-null exactly when [state] is MINED.
 */
data class SendAttemptEntry(
    val kind: SendAttemptKind,
    val subject: String,
    val identity: String,
    val startedAtMillis: Long,
    val attemptHeight: Long? = null,
    val processId: String? = null,
    val accountHash: String? = null,
    val txIds: List<String> = emptyList(),
    val expiries: Map<String, Long> = emptyMap(),
    val releaseAnchorHeight: Long? = null,
    val supersededTxIds: List<String> = emptyList(),
    val provenNotSentAtHeight: Long? = null,
    val state: SendAttemptState = SendAttemptState.LIVE,
    val minedHeight: Long? = null,
    val idsBeforeSubmit: Boolean = true,
    val payload: String? = null,
    val reason: String? = null,
) {
    val key: String get() = SendAttemptLedgerRules.key(kind, subject, identity)

    /** The same attempt as [other] in every field a pass judged (not the process id, which a restart changes). */
    fun sameAttemptAs(other: SendAttemptEntry): Boolean =
        key == other.key &&
            startedAtMillis == other.startedAtMillis &&
            txIds == other.txIds &&
            accountHash == other.accountHash &&
            state == other.state
}

/**
 * What a sender hands the funnel. [retryOf] is the [SendAttemptEntry.startedAtMillis] of the NOT_SENT entry this
 * attempt retries: the failed state the user's quote was shown for. Null for every other attempt.
 */
data class SendAttemptTag(
    val kind: SendAttemptKind,
    val subject: String,
    val identity: String,
    val retryOf: Long? = null,
    val reason: String? = null,
    val payload: String? = null,
) {
    val key: String get() = SendAttemptLedgerRules.key(kind, subject, identity)
}

/** What the notice of a chat is drawn from: the kind of its NOT_SENT entry and that entry's marker. */
data class HandshakeOffer(
    val kind: SendAttemptKind,
    val startedAt: Long,
)

/** The gate's answer. It only reads; an unreadable ledger throws instead. */
sealed interface SendAttemptGate {
    /** Go on. [seen] is the marker of the NOT_SENT entry this attempt retries, or null for a first attempt. */
    data class Proceed(
        val seen: Long?,
    ) : SendAttemptGate

    /** An earlier attempt of the key is unresolved, mined and not yet deep, or waiting for the user's tap. */
    data object Pending : SendAttemptGate
}

/** What [SendAttemptLedger.begin] returns: this attempt, in memory only. [replaces] is the NOT_SENT marker it retries. */
class SendAttemptClaim(
    val tag: SendAttemptTag,
    val startedAt: Long,
    val replaces: Long?,
    val attemptHeight: Long?,
    val processId: String,
    val accountHash: String,
) {
    val key: String get() = tag.key

    /** The id this attempt is entered under in [SendAttemptProcess]. */
    val flightId: String get() = SendAttemptLedgerRules.flightId(key, startedAt)
}

/** The ledger could not be written (or read): nothing of the attempt may be handed to the SDK. */
open class LedgerWriteFailedException(
    message: String = "send-attempt ledger not written",
) : IllegalStateException(message)

/** No ZCHAT account is known, so the attempt cannot be recorded against one: it is not begun. */
class LedgerAccountUnverifiedException : LedgerWriteFailedException("send-attempt ledger: no verified account")

/** Another attempt of the key appeared since the gate (or the failed state this one retries is gone). */
class AttemptAliveException : IllegalStateException("send attempt: another attempt of this key stands")

/**
 * The ledger. Each method is one short critical section on the ledger's own lock (a leaf: it takes no other lock).
 *
 * Payer methods ([gate], [begin], [recordStored], [discard], [finish], [settleDelivered]) are the only writers of an
 * attempt that is in flight in this process; the reconciler methods ([settleMined], [markNotSent], [reopen],
 * [setAnchor], [delete]) the only writers of everything else. Every write is a checked commit; a commit that returns
 * false and one that throws are the same failed write.
 */
interface SendAttemptLedger {
    /** Reads only. Throws when the ledger cannot be read. */
    fun gate(
        key: String,
        retryOf: Long?,
    ): SendAttemptGate

    /**
     * With no entry ([seen] null): writes LIVE in one checked commit. Over the NOT_SENT entry the gate saw: writes
     * nothing and only checks that it still stands. Throws [LedgerAccountUnverifiedException],
     * [LedgerWriteFailedException] or [AttemptAliveException].
     */
    fun begin(
        tag: SendAttemptTag,
        startedAt: Long,
        attemptHeight: Long?,
        processId: String,
        accountHash: String?,
        seen: Long?,
    ): SendAttemptClaim

    /**
     * The ids commit, before the first submit. A fresh attempt adds the ids to its own LIVE entry; a retry replaces
     * the NOT_SENT entry it retries by a new LIVE one in this one write. Throws [LedgerWriteFailedException] when the
     * write fails or the entry it rests on is gone.
     */
    fun recordStored(
        claim: SendAttemptClaim,
        txIds: List<String>,
        expiries: Map<String, Long>,
    )

    /** Deletes only a LIVE entry of this marker that holds no ids (a fresh attempt that provably sent nothing). */
    fun discard(
        key: String,
        startedAt: Long,
    ): Boolean

    /** The attempt's loop iteration ended (the [attemptRunning] count goes down). */
    fun finish(claim: SendAttemptClaim)

    /** A delivery by another route: deletes a NOT_SENT entry of the key. */
    fun settleDelivered(key: String): Boolean

    fun get(key: String): SendAttemptEntry?

    fun all(): List<SendAttemptEntry>

    /** The marker of the key's NOT_SENT entry, or null when it has none. */
    fun offerStartedAt(key: String): Long?

    /** Whether an attempt of the key is running in this process (never persisted). */
    fun attemptRunning(key: String): Boolean

    /** LIVE or MINED becomes MINED with the highest height seen, when [predicate] holds for the stored entry. */
    fun settleMined(
        key: String,
        startedAt: Long,
        height: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean

    /** LIVE becomes NOT_SENT at [tip], when [predicate] holds for the stored entry. */
    fun markNotSent(
        key: String,
        startedAt: Long,
        tip: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean

    /** An orphaned MINED entry goes back to LIVE (its heights and anchor cleared), when [predicate] holds. */
    fun reopen(
        key: String,
        startedAt: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean

    /** Writes the release anchor of a LIVE entry once, when [predicate] holds. */
    fun setAnchor(
        key: String,
        startedAt: Long,
        anchor: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean

    /** Deletes the entry of this marker. True when no such entry is left. */
    fun delete(
        key: String,
        startedAt: Long,
    ): Boolean

    /** Writes [entry] as it is (the group side's write-ahead record). True when it reached the store. */
    fun writeAhead(entry: SendAttemptEntry): Boolean
}

/**
 * The ledger's rules over any store of entries: [load], [loadAll] and [store] are the store; a [store] that returns
 * false or throws is one failed write, a [load] that throws is an unreadable ledger.
 */
abstract class StoredSendAttemptLedger : SendAttemptLedger {
    private val lock = ReentrantLock()
    private val running = HashMap<String, Int>()

    /** Whether the calling thread is inside a ledger method (for the lock-order tests). */
    fun isLockHeld(): Boolean = lock.isHeldByCurrentThread

    protected abstract fun load(key: String): SendAttemptEntry?

    protected abstract fun loadAll(): List<SendAttemptEntry>

    /** Writes [entry] under [key], or removes the key when it is null. */
    protected abstract fun store(
        key: String,
        entry: SendAttemptEntry?,
    ): Boolean

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun save(
        key: String,
        entry: SendAttemptEntry?,
    ): Boolean =
        try {
            store(key, entry)
        } catch (e: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun loadForWrite(key: String): SendAttemptEntry? =
        try {
            load(key)
        } catch (e: Exception) {
            throw LedgerWriteFailedException("send-attempt ledger not read")
        }

    override fun gate(
        key: String,
        retryOf: Long?,
    ): SendAttemptGate = lock.withLock { SendAttemptLedgerRules.gate(load(key), retryOf) }

    override fun begin(
        tag: SendAttemptTag,
        startedAt: Long,
        attemptHeight: Long?,
        processId: String,
        accountHash: String?,
        seen: Long?,
    ): SendAttemptClaim =
        lock.withLock {
            if (accountHash == null) throw LedgerAccountUnverifiedException()
            val key = tag.key
            val existing = loadForWrite(key)
            if (seen == null) {
                if (existing != null) throw AttemptAliveException()
                val entry =
                    SendAttemptEntry(
                        kind = tag.kind,
                        subject = tag.subject,
                        identity = tag.identity,
                        startedAtMillis = startedAt,
                        attemptHeight = attemptHeight,
                        processId = processId,
                        accountHash = accountHash,
                        payload = tag.payload,
                        reason = tag.reason,
                    )
                if (!save(key, entry)) throw LedgerWriteFailedException()
            } else if (existing == null || existing.state != SendAttemptState.NOT_SENT || existing.startedAtMillis != seen) {
                throw AttemptAliveException()
            }
            running[key] = (running[key] ?: 0) + 1
            SendAttemptClaim(tag, startedAt, seen, attemptHeight, processId, accountHash)
        }

    override fun recordStored(
        claim: SendAttemptClaim,
        txIds: List<String>,
        expiries: Map<String, Long>,
    ) {
        lock.withLock {
            val existing = loadForWrite(claim.key)
            val next =
                SendAttemptLedgerRules.afterIdsCommit(existing, claim, txIds, expiries)
                    ?: throw LedgerWriteFailedException("send-attempt ledger: the entry of this attempt is gone")
            if (!save(claim.key, next)) throw LedgerWriteFailedException()
        }
    }

    override fun discard(
        key: String,
        startedAt: Long,
    ): Boolean =
        lock.withLock {
            val existing = runCatching { load(key) }.getOrNull() ?: return@withLock false
            if (!SendAttemptLedgerRules.mayDiscard(existing, startedAt)) return@withLock false
            save(key, null)
        }

    override fun finish(claim: SendAttemptClaim) {
        lock.withLock {
            val left = (running[claim.key] ?: 0) - 1
            if (left > 0) running[claim.key] = left else running.remove(claim.key)
        }
    }

    override fun settleDelivered(key: String): Boolean =
        lock.withLock {
            val existing = runCatching { load(key) }.getOrNull() ?: return@withLock false
            if (existing.state != SendAttemptState.NOT_SENT) return@withLock false
            save(key, null)
        }

    override fun get(key: String): SendAttemptEntry? = lock.withLock { load(key) }

    override fun all(): List<SendAttemptEntry> = lock.withLock { loadAll() }

    override fun offerStartedAt(key: String): Long? =
        lock.withLock {
            runCatching { load(key) }.getOrNull()?.takeIf { it.state == SendAttemptState.NOT_SENT }?.startedAtMillis
        }

    override fun attemptRunning(key: String): Boolean = lock.withLock { (running[key] ?: 0) > 0 }

    private fun revise(
        key: String,
        startedAt: Long,
        predicate: (SendAttemptEntry) -> Boolean,
        change: (SendAttemptEntry) -> SendAttemptEntry?,
    ): Boolean =
        lock.withLock {
            val existing = runCatching { load(key) }.getOrNull() ?: return@withLock false
            if (existing.startedAtMillis != startedAt || !predicate(existing)) return@withLock false
            val next = change(existing) ?: return@withLock false
            save(key, next)
        }

    override fun settleMined(
        key: String,
        startedAt: Long,
        height: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean = revise(key, startedAt, predicate) { SendAttemptLedgerRules.mined(it, height) }

    override fun markNotSent(
        key: String,
        startedAt: Long,
        tip: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean = revise(key, startedAt, predicate) { SendAttemptLedgerRules.notSent(it, tip) }

    override fun reopen(
        key: String,
        startedAt: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean = revise(key, startedAt, predicate) { SendAttemptLedgerRules.reopened(it) }

    override fun setAnchor(
        key: String,
        startedAt: Long,
        anchor: Long,
        predicate: (SendAttemptEntry) -> Boolean,
    ): Boolean =
        revise(key, startedAt, predicate) {
            if (it.state == SendAttemptState.LIVE && it.releaseAnchorHeight == null) it.copy(releaseAnchorHeight = anchor) else null
        }

    override fun delete(
        key: String,
        startedAt: Long,
    ): Boolean =
        lock.withLock {
            val existing = runCatching { load(key) }.getOrElse { return@withLock false }
            if (existing == null || existing.startedAtMillis != startedAt) return@withLock true
            save(key, null)
        }

    override fun writeAhead(entry: SendAttemptEntry): Boolean = lock.withLock { save(entry.key, entry) }
}

/** The ledger in memory: the tests' twin of the stored one, with the same rules. */
class InMemorySendAttemptLedger : StoredSendAttemptLedger() {
    private val entries = LinkedHashMap<String, SendAttemptEntry>()

    override fun load(key: String): SendAttemptEntry? = synchronized(entries) { entries[key] }?.also(::checkShape)

    override fun loadAll(): List<SendAttemptEntry> = synchronized(entries) { entries.values.toList() }.onEach(::checkShape)

    override fun store(
        key: String,
        entry: SendAttemptEntry?,
    ): Boolean {
        synchronized(entries) {
            if (entry == null) entries.remove(key) else entries[key] = entry
        }
        return true
    }

    private fun checkShape(entry: SendAttemptEntry) {
        check((entry.state == SendAttemptState.MINED) == (entry.minedHeight != null)) {
            "a MINED entry carries its height, and only a MINED one"
        }
    }
}
