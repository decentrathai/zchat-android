package co.electriccoin.zcash.ui.screen.chat.datasource

/**
 * KI-24 part A — the processed on-chain KEX/KEXACK txids (#201), with the height each one was mined at and a height
 * floor. Pure (no Android), so the rules are unit-tested; ZchatPreferencesImpl keeps one instance under its lock and
 * persists [serialize] and [floorHeight] in the "zchat_kex_seen" file.
 *
 * The wallet re-reads its whole history on every pass, so a KEX whose txid leaves this bounded set would be handled
 * again: a false "key changed" warning for a contact whose key changed since, or, after a Reset, an old key stored as
 * the contact's and a KEXACK paid for it. So when entries are evicted, [floorHeight] rises to the highest mined height
 * among them, but never above the fully scanned height at that moment (the SDK scans recent blocks first: below that
 * height an earlier pass has seen every transaction). A KEX mined at or below the floor counts as processed. A KEX
 * with no mined height (mempool) is never floored, and a KEX mined above the floor is handled as before.
 *
 * The floor also never passes [ceilingHeight] (fixup G-KI24-2). The history pass puts off inbound key offers (KEX)
 * during a restore or a first sync and does not mark them, so a later pass must still handle them; a floor above one of
 * them would take it for processed, and the contact's key would never be stored or answered. So each pass reports
 * ([updateCeiling]) the lowest mined offer this set has not recorded, and the ceiling stays below it; only a complete,
 * stable pass may raise the ceiling, and never above the highest mined height that pass read. An entry mined above the
 * ceiling or above the fully scanned height (T4c fixup KI24-FX2-1: a complete pass does not need the scan to reach the
 * chain tip, so the ceiling can sit above it), with no mined height yet (fixup G-FX-2), or any entry while no scanned
 * height is known, is not evicted (the floor could not cover it, and it would be handled again): the set may grow past
 * the cap until the ceiling and the scan rise or the heights are filled, up to [HARD_CAP_FACTOR] times the cap, where
 * the oldest go anyway.
 *
 * ZC-074: an entry the hard cap removes that the floor cannot cover (mined above the ceiling or the fully scanned height,
 * with no height, or any while no scanned height is known) moves to the overflow: an ordered map from the txid's first
 * [OVERFLOW_PREFIX_CHARS] hex characters to its height. [isProcessed] also answers true for a txid in the overflow, so a
 * KEX already handled is not handled again because of the set's size; [record] of such a txid only fills its missing
 * height and never puts it back into the set (re-adding it would push the next uncovered entry out: churn). A line leaves
 * the overflow when the floor rises to its height. The overflow holds at most [OVERFLOW_FACTOR] times the cap lines; past
 * that the oldest goes, uncovered (the declared residual: more than 4 + 8 times the cap KEX/KEXACK transactions above a
 * ceiling that never rises). [contains] and [heightOf] answer for the set alone.
 *
 * Stored format: one line per txid, "txid" or "txid:height". A line with no height (written before KI-24) stays
 * valid; [record] fills its height when the pass sees it mined again, so its later eviction can raise the floor. The
 * overflow is stored the same way ([serializeOverflow]), one "prefix" or "prefix:height" line each; none stored is empty.
 */
internal class KexSeenLedger(
    private val cap: () -> Long,
) {
    // Insertion order = processing order; the head is the oldest.
    private val entries = LinkedHashMap<String, Long?>()

    // ZC-074: what the hard cap removed uncovered, by txid prefix ([overflowKey]); insertion order, the head is the oldest.
    private val overflow = LinkedHashMap<String, Long?>()

    /** The height at or below which every mined KEX counts as processed; 0 = none. Never decreases. */
    var floorHeight: Long = 0L
        private set

    /**
     * The height the floor may not pass, set by the history pass ([updateCeiling]); 0 = the floor may not rise yet.
     * Null only on a bare ledger (a unit test of the eviction rules): no pass limit. ZchatPreferencesImpl always loads
     * a stored value, 0 when none is stored, so on a new or restored wallet the floor waits for the first complete pass.
     */
    var ceilingHeight: Long? = null
        private set

    val size: Int get() = entries.size

    /** ZC-074: the lines the overflow holds. */
    val overflowSize: Int get() = overflow.size

    fun load(
        stored: String?,
        storedFloor: Long,
        storedCeiling: Long? = null,
        storedOverflow: String? = null,
    ) {
        entries.clear()
        overflow.clear()
        parseLines(stored) { txId, height -> entries[txId] = height }
        floorHeight = storedFloor.coerceAtLeast(0L)
        ceilingHeight = storedCeiling?.coerceAtLeast(0L)
        parseLines(storedOverflow) { key, height -> if (height == null || height > floorHeight) overflow[key] = height }
    }

    fun clear() {
        entries.clear()
        overflow.clear()
        floorHeight = 0L
        ceilingHeight = 0L
    }

    fun contains(txId: String): Boolean = entries.containsKey(txId)

    fun heightOf(txId: String): Long? = entries[txId]

    /**
     * In the set, in the overflow (ZC-074), or mined at or below the floor. An unmined KEX ([minedHeight] null) is
     * decided by the set and the overflow alone.
     */
    fun isProcessed(
        txId: String,
        minedHeight: Long?,
    ): Boolean =
        entries.containsKey(txId) ||
            overflow.containsKey(overflowKey(txId)) ||
            (minedHeight != null && minedHeight > 0 && minedHeight <= floorHeight)

    /**
     * What [record] changed, so the store knows what to write and whether the floor moved. [OVERFLOWED] (ZC-074): the
     * hard cap moved entries to the overflow, which alone stands for them now.
     */
    enum class Change { NONE, ADDED, HEIGHT_FILLED, FLOOR_RAISED, OVERFLOWED }

    /**
     * Records [txId] as processed. A txid already in the set only gets its missing height filled; one the floor
     * already covers is not added again (no churn). Eviction past the cap raises the floor to the highest evicted
     * mined height, clamped to [scannedTip] and to [ceilingHeight]; with no tip known, the floor does not move. Past the
     * cap, only entries mined at or below both the ceiling and [scannedTip] are evicted, and none with no tip known (oldest
     * first, never the one just added, never one with no height): exactly the entries the floor then covers. Past
     * [HARD_CAP_FACTOR] times the cap the oldest go in insertion order; ZC-074: each of them the floor will not cover
     * moves to the overflow. A txid already in the overflow only gets its missing height filled and is not added again.
     */
    fun record(
        txId: String,
        minedHeight: Long?,
        scannedTip: Long?,
    ): Change {
        val height = minedHeight?.takeIf { it > 0 }
        if (entries.containsKey(txId)) {
            if (height != null && entries[txId] == null) {
                entries[txId] = height
                return Change.HEIGHT_FILLED
            }
            return Change.NONE
        }
        val key = overflowKey(txId)
        if (overflow.containsKey(key)) {
            // ZC-074: already handled and removed by the hard cap; putting it back would push the next one out (churn).
            if (height != null && overflow[key] == null) {
                overflow[key] = height
                return Change.HEIGHT_FILLED
            }
            return Change.NONE
        }
        if (height != null && height <= floorHeight) return Change.NONE
        entries[txId] = height
        var highestEvicted: Long? = null
        var overflowed = false
        val limit = cap().coerceAtLeast(1L)
        val ceiling = ceilingHeight
        val tip = scannedTip?.takeIf { it > 0 }
        if (entries.size > limit) {
            // G-KI24-2 / KI24-FX2-1: past the cap, evict only what the floor then covers: an entry mined at or below both
            // the ceiling (no bound on a bare ledger) and the fully scanned height, the two the floor is clamped to;
            // with no scanned height known the floor does not move, so nothing is evicted here. G-FX-2: never an entry
            // with no height (a KEX handled from the mempool, or a 2.12.x line): no floor covers it. Such entries leave
            // only through the hard cap below, in insertion order.
            val evictable = tip?.let { minOf(it, ceiling ?: Long.MAX_VALUE) }
            val oldestFirst = entries.entries.iterator()
            while (evictable != null && entries.size > limit && oldestFirst.hasNext()) {
                val entry = oldestFirst.next()
                val h = entry.value
                if (entry.key != txId && h != null && h <= evictable) {
                    highestEvicted = maxOf(highestEvicted ?: h, h)
                    oldestFirst.remove()
                }
            }
            // A bound on memory while the ceiling waits: the oldest go anyway (the floor still stops at the ceiling).
            // ZC-074: one the floor will not cover (see coveredBelow) moves to the overflow, so it stays processed.
            val hardLimit = limit * HARD_CAP_FACTOR
            val coveredBelow = tip?.let { minOf(it, ceiling ?: Long.MAX_VALUE) }
            while (entries.size > hardLimit) {
                val oldest = entries.entries.iterator().next()
                val h = oldest.value
                h?.let { highestEvicted = maxOf(highestEvicted ?: it, it) }
                if (h == null || coveredBelow == null || h > coveredBelow) {
                    overflow[overflowKey(oldest.key)] = h
                    overflowed = true
                }
                entries.remove(oldest.key)
            }
            // The bound on the overflow: past it the oldest line goes, uncovered (the declared residual, see the KDoc).
            val overflowLimit = limit * OVERFLOW_FACTOR
            while (overflow.size > overflowLimit) overflow.remove(overflow.keys.iterator().next())
        }
        val evicted = highestEvicted
        if (evicted != null && tip != null) {
            val candidate = minOf(evicted, tip, ceiling ?: Long.MAX_VALUE)
            if (candidate > floorHeight) {
                floorHeight = candidate
                pruneOverflow()
                return Change.FLOOR_RAISED
            }
        }
        return if (overflowed) Change.OVERFLOWED else Change.ADDED
    }

    // ZC-074: a line the floor now covers leaves the overflow (isProcessed answers for it through the floor).
    private fun pruneOverflow() {
        val lines = overflow.entries.iterator()
        while (lines.hasNext()) {
            val h = lines.next().value
            if (h != null && h <= floorHeight) lines.remove()
        }
    }

    /**
     * The history pass's report (fixup G-KI24-2): [lowestUnrecordedOfferHeight] is the lowest mined height of an inbound
     * key offer this pass read that the set has not recorded (put off, or handed to the handler but not marked yet),
     * null for none. [passComplete] is true only for a pass that read the whole history, the same transactions as the
     * pass before (ZC-074: an inbound memo it could not read is reported here as an offer at its mined height);
     * [passTopHeight] is the highest mined height that pass read.
     *
     * The ceiling drops below any such offer at once. It rises only on a complete pass, to [passTopHeight] (a KEX mined
     * above it is not in that pass and is handled when a pass reads it), or just below the lowest such offer.
     * @return whether the ceiling changed.
     */
    fun updateCeiling(
        passComplete: Boolean,
        passTopHeight: Long?,
        lowestUnrecordedOfferHeight: Long?,
    ): Boolean {
        val offerLimit = lowestUnrecordedOfferHeight?.takeIf { it > 0 }?.let { it - 1 }
        val current = ceilingHeight
        val top = passTopHeight?.takeIf { it > 0 }
        val next =
            if (passComplete && top != null) {
                minOf(top, offerLimit ?: Long.MAX_VALUE)
            } else if (offerLimit != null) {
                minOf(current ?: Long.MAX_VALUE, offerLimit)
            } else {
                current
            }
        if (next == current) return false
        ceilingHeight = next?.coerceAtLeast(0L)
        return true
    }

    fun serialize(): String = joinLines(entries)

    /** ZC-074: the overflow in its stored form; empty when it holds nothing (the store then writes no key). */
    fun serializeOverflow(): String = joinLines(overflow)

    companion object {
        /** How far past the cap the set may grow while the ceiling keeps entries from being evicted uncovered. */
        const val HARD_CAP_FACTOR = 4L

        /** ZC-074: how many times the cap the overflow may hold (8 x 500 = 4,000 lines, about 100 KB). */
        const val OVERFLOW_FACTOR = 8L

        /** ZC-074: the overflow keeps a txid's first 16 hex characters (64 bits). */
        const val OVERFLOW_PREFIX_CHARS = 16

        private val TXID_HEX = Regex("[0-9a-fA-F]{64}")

        /**
         * The overflow's key for [txId]: the first [OVERFLOW_PREFIX_CHARS] characters of a 64-hex-character txid (a false
         * match on a new KEX has a probability of about lines / 2^64); any other id is kept whole.
         */
        internal fun overflowKey(txId: String): String =
            if (TXID_HEX.matches(txId)) txId.substring(0, OVERFLOW_PREFIX_CHARS) else txId

        private fun parseLines(
            stored: String?,
            put: (String, Long?) -> Unit,
        ) {
            stored
                ?.split('\n')
                ?.filter { it.isNotEmpty() }
                ?.forEach { line ->
                    val colon = line.lastIndexOf(':')
                    val height = if (colon > 0) line.substring(colon + 1).toLongOrNull() else null
                    val id = if (height != null) line.substring(0, colon) else line
                    if (id.isNotEmpty()) put(id, height)
                }
        }

        private fun joinLines(lines: Map<String, Long?>): String =
            lines.entries.joinToString("\n") { (id, height) -> if (height != null) "$id:$height" else id }
    }
}
