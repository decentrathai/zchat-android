package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.common.sendattempt.NetworkUpgradeHeights
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * ZC-1792 (owner W-142, P-A): a receive-only reader for a message sealed under the ratchet root as it stood at the
 * message's own height.
 *
 * The root mixes every mined KEX and KEXACK txid of the chat, so it changes when an answer mines. A responder that sealed
 * a message before its answer was paid (the 2.13.0 Accept hold) used the root with an empty KEXACK set; once the answer
 * mines, the current root fails that message's AEAD tag and nothing tried the earlier root. On such a failure the caller
 * asks this object for at most [MAX_ATTEMPTS_PER_ROW] earlier roots ([candidates]) and opens the envelope with each one in
 * a throwaway [E2ERatchet] over a fresh [InMemoryRatchetStateStore] ([open]).
 *
 * What it never does: write the caller's ratchet store or any floor, send, or change a format. [E2ERatchet], the root
 * derivation ([E2ERatchet.deriveRatchetRoot], [E2ERatchet.canonicalTxidMaterial]) and every memo, envelope and
 * preference key are called as they are (compat-2124: a single-exchange chat's root is byte-identical and opens on the
 * first path, so this is never reached for it). A row this opens is display only: the caller sets no key evidence and no
 * timer or mode control from it; for the keys-out-of-sync banner it is an open like any other, counted by its height
 * (ZC-1828).
 *
 * State: the attempt count and the negative cache (row txid + [E2ERatchet.floorKeyFor] of a candidate root that failed)
 * are process-wide here, so a ChatViewModel built without its constructor needs no new field. What belongs to one history
 * pass (the height of every mined transaction, the ones still pending, the failed tries per peer) is a [Pass] the caller
 * makes per pass.
 */
object OldRootFallback {
    /** At most this many earlier roots are tried for one row in one pass (C1, C2, C3). */
    const val MAX_ATTEMPTS_PER_ROW = 3

    /** At most this many failed tries for one peer in one pass (a forged row costs one chain walk every pass already). */
    const val MAX_FAILED_ATTEMPTS_PER_PEER_PER_PASS = 64

    /** [E2ERatchet]'s send ceiling (MAX_SEND_COUNTER, private there): no sender ever emitted a counter at or above it. */
    private const val COUNTER_CEILING = 1_000_000L

    /** The negative cache is emptied when it grows past this (each pass is still bounded by the per-peer budget). */
    private const val NEGATIVE_CACHE_LIMIT = 4_096

    private const val DIRECTION_LOWER: Byte = 0x00
    private const val DIRECTION_HIGHER: Byte = 0x01

    private val attempts = AtomicLong()
    private val failedRoots: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * One history pass: the mined height of every transaction of the pass (ZC-1824: noted before its loop, as block
     * timestamps do not follow heights), and the failed tries per peer. ZC-1831: a stored exchange txid with no height
     * here is not mined or not in this wallet's history, and the row gets no candidate at all ([candidates]). ZC-1865: one
     * the wallet lists as pending ([notePending]) is not mined yet and is left out like an exchange above the row.
     */
    class Pass {
        private val minedHeights = ConcurrentHashMap<String, Long>()
        private val failedByPeer = ConcurrentHashMap<String, Int>()
        private val pendingTxids: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The transaction [txid] is mined at [height]. */
        fun noteMined(
            txid: String,
            height: Long,
        ) {
            minedHeights[txid] = height
        }

        /** ZC-1865: the wallet lists the transaction [txid] with no height yet (not mined). */
        fun notePending(txid: String) {
            pendingTxids += txid
        }

        internal fun heights(): Map<String, Long> = minedHeights

        internal fun pending(): Set<String> = pendingTxids

        internal fun failures(peer: String): Int = failedByPeer[peer] ?: 0

        internal fun fail(peer: String) {
            failedByPeer.merge(peer, 1, Int::plus)
        }
    }

    /**
     * One row the caller could not open with the current root: its [txid], its mined [height], and whether it is the
     * contact's ([fromContact]) or our own. [opened] is set when this object opened it.
     */
    class Row(
        val pass: Pass,
        val txid: String,
        val height: Long,
        val fromContact: Boolean,
    ) {
        @Volatile
        var opened: Boolean = false
            private set

        internal fun markOpened() {
            opened = true
        }
    }

    /** How many throwaway ratchets this process built since [resetProcessState]. */
    fun attemptCount(): Long = attempts.get()

    /** Zero the attempt count and empty the negative cache (process start; tests). */
    fun resetProcessState() {
        attempts.set(0L)
        failedRoots.clear()
    }

    /**
     * How many blocks below a row a key exchange can have been mined and still be unscanned by the row's sender: the
     * expiry delta at the row's height (ChainTiming.maxExpiryDelta: 40 before NU7, librustzcash builder.rs
     * DEFAULT_TX_EXPIRY_DELTA; 120 from NU7 on, ChainTiming.NU7_EXPIRY_DELTA).
     */
    fun expiryWindow(
        rowHeight: Long,
        upgrades: NetworkUpgradeHeights,
    ): Long = ChainTiming.maxExpiryDelta(rowHeight, upgrades)

    private class Exchange(
        val txid: String,
        val isAck: Boolean,
        val height: Long,
    )

    /**
     * The earlier roots to try for a row mined at [rowHeight], as (KEX material, KEXACK material) in the bytes
     * [E2ERatchet.canonicalTxidMaterial] gives (with no scalar: a scalar is one more txid of its set).
     *
     * The inputs are what the current root reads (the stored sets and legacy scalars). ZC-1831 (QA2 pa3): every one of them
     * must have a height in [minedHeights] or be [pending] (ZC-1865, QA2 pa4: listed by the wallet, not mined yet), or there
     * is nothing to try (fail closed: leaving an unknown one out would rebuild a root without it and skip the expiry check
     * below). A txid mined above the row, or pending, is in no candidate. C1 is
     * those at or below the row; C2 leaves out the newest exchange, C3 the two newest, each only when what it leaves out
     * was mined within [expiryWindow] below the row. Newest = highest height, then highest txid. A candidate equal to the
     * current root's material or to an earlier candidate is left out.
     */
    @JvmOverloads
    @Suppress("LongParameterList")
    fun candidates(
        kexTxids: Set<String>,
        kexScalar: String?,
        kexAckTxids: Set<String>,
        kexAckScalar: String?,
        minedHeights: Map<String, Long>,
        rowHeight: Long,
        upgrades: NetworkUpgradeHeights,
        pending: Set<String> = emptySet(),
    ): List<Pair<ByteArray, ByteArray>> {
        val current =
            E2ERatchet.canonicalTxidMaterial(kexTxids, kexScalar) to E2ERatchet.canonicalTxidMaterial(kexAckTxids, kexAckScalar)
        val kexIds = (kexTxids + listOfNotNull(kexScalar)).toSet()
        val ackIds = (kexAckTxids + listOfNotNull(kexAckScalar)).toSet()
        if ((kexIds + ackIds).any { it !in minedHeights && it !in pending }) return emptyList()
        val known =
            (kexIds.map { it to false } + ackIds.map { it to true })
                .mapNotNull { (txid, isAck) ->
                    minedHeights[txid]?.takeIf { it <= rowHeight }?.let { Exchange(txid, isAck, it) }
                }.sortedWith(compareByDescending<Exchange> { it.height }.thenByDescending { it.txid })
        val window = expiryWindow(rowHeight, upgrades)
        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        for (leftOut in 0 until MAX_ATTEMPTS_PER_ROW) {
            if (leftOut > known.size) break
            if (known.take(leftOut).any { rowHeight - it.height > window }) break
            val kept = known.drop(leftOut)
            val material =
                E2ERatchet.canonicalTxidMaterial(kept.filter { !it.isAck }.mapTo(HashSet()) { it.txid }, null) to
                    E2ERatchet.canonicalTxidMaterial(kept.filter { it.isAck }.mapTo(HashSet()) { it.txid }, null)
            val seen = listOf(current) + out
            if (seen.none { it.first.contentEquals(material.first) && it.second.contentEquals(material.second) }) out += material
        }
        return out
    }

    /**
     * Open [envelope] under the root derived from ([ecdhSharedSecret], [psk], [kexMaterial], [kexAckMaterial]) in a
     * throwaway ratchet (its receive window seeded with the envelope's counter) and discard it. Null on ANY failure, and at
     * once (no attempt counted) when the envelope's direction is not the contact's ([fromContact]) or our own (not
     * [fromContact]), or its counter is at or above the send ceiling. ZC-1833: internal, as it applies no window or budget
     * of its own; the app goes through [openRow].
     */
    internal suspend fun open(
        ecdhSharedSecret: ByteArray,
        psk: ByteArray?,
        kexMaterial: ByteArray,
        kexAckMaterial: ByteArray,
        headerConvId: String,
        isLower: Boolean,
        fromContact: Boolean,
        envelope: Ciphertext,
    ): ByteArray? {
        if (!isPlausible(envelope, isLower, fromContact)) return null
        val root = deriveOrNull(ecdhSharedSecret, psk, kexMaterial, kexAckMaterial) ?: return null
        return openUnder(root, headerConvId, isLower, fromContact, envelope)
    }

    /**
     * The row-level entry the history pass calls after the current root failed [envelope]'s AEAD tag: tries [candidates]
     * of the row within the budgets (per row, per peer and pass, and never a (row, root) that failed before in this
     * process). Null when nothing opened it.
     */
    @Suppress("LongParameterList")
    suspend fun openRow(
        row: Row,
        peer: String,
        envelope: Ciphertext,
        headerConvId: String,
        ecdhSharedSecret: ByteArray,
        psk: ByteArray?,
        isLower: Boolean,
        kexTxids: Set<String>,
        kexScalar: String?,
        kexAckTxids: Set<String>,
        kexAckScalar: String?,
        upgrades: NetworkUpgradeHeights,
    ): ByteArray? {
        if (row.height <= 0L || !isPlausible(envelope, isLower, row.fromContact)) return null
        val tried =
            candidates(kexTxids, kexScalar, kexAckTxids, kexAckScalar, row.pass.heights(), row.height, upgrades, row.pass.pending())
        var spent = 0
        for ((kexMaterial, kexAckMaterial) in tried) {
            if (spent >= MAX_ATTEMPTS_PER_ROW) break
            if (row.pass.failures(peer) >= MAX_FAILED_ATTEMPTS_PER_PEER_PER_PASS) break
            val root = deriveOrNull(ecdhSharedSecret, psk, kexMaterial, kexAckMaterial) ?: continue
            val negativeKey = row.txid + "|" + E2ERatchet.floorKeyFor(root)
            if (negativeKey in failedRoots) continue
            spent++
            val plaintext = openUnder(root, headerConvId, isLower, row.fromContact, envelope)
            if (plaintext != null) {
                row.markOpened()
                return plaintext
            }
            if (failedRoots.size >= NEGATIVE_CACHE_LIMIT) failedRoots.clear()
            failedRoots += negativeKey
            row.pass.fail(peer)
        }
        return null
    }

    private fun isPlausible(
        envelope: Ciphertext,
        isLower: Boolean,
        fromContact: Boolean,
    ): Boolean {
        val mine = if (isLower) DIRECTION_LOWER else DIRECTION_HIGHER
        val theirs = if (isLower) DIRECTION_HIGHER else DIRECTION_LOWER
        val expected = if (fromContact) theirs else mine
        return envelope.direction == expected && envelope.counter in 0 until COUNTER_CEILING
    }

    private fun deriveOrNull(
        ecdhSharedSecret: ByteArray,
        psk: ByteArray?,
        kexMaterial: ByteArray,
        kexAckMaterial: ByteArray,
    ): ByteArray? =
        try {
            E2ERatchet.deriveRatchetRoot(ecdhSharedSecret, psk, kexMaterial, kexAckMaterial)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun openUnder(
        root: ByteArray,
        headerConvId: String,
        isLower: Boolean,
        fromContact: Boolean,
        envelope: Ciphertext,
    ): ByteArray? {
        attempts.incrementAndGet()
        return try {
            val store = InMemoryRatchetStateStore()
            if (fromContact) {
                val lowerSends = envelope.direction == DIRECTION_LOWER
                store.save(
                    RatchetConversationState(
                        convId = headerConvId,
                        nextCounterA2B = 0L,
                        nextCounterB2A = 0L,
                        seenCountersA2B = emptySet(),
                        seenCountersB2A = emptySet(),
                        maxSeenA2B = if (lowerSends) envelope.counter else 0L,
                        maxSeenB2A = if (lowerSends) 0L else envelope.counter,
                    ),
                )
            }
            E2ERatchet(root, headerConvId, isLower, store).decrypt(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a wrong root, AAD or body: the tag does not verify
        }
    }
}
