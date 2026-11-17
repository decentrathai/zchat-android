package co.electriccoin.zcash.ui.screen.chat.datasource

/**
 * KI-24 part B — the handled inbound gift-wrap event ids (#188), kept by time. Pure (no Android), so the rules are
 * unit-tested; ZchatPreferencesImpl keeps one instance under its lock and persists [serialize] and [floorSec] in the
 * "zchat_nostr_seen" file, next to the per-relay `since` values.
 *
 * The relays keep a gift-wrap for about 30 days and replay it on every fresh subscription, so an id may only leave this
 * list once no relay will serve its event again. Each entry keeps the wrap's outer created_at (seconds). An entry
 * older than the lowest per-relay `since` can never come back (every REQ carries that `since`, and a wrap below it is
 * dropped on arrival), so [record] drops such entries, a batch at a time. A safety cap (20,000 in release) bounds a
 * flood; entries it forces out go oldest first.
 *
 * Whatever leaves the list raises [floorSec] to the newest time among the evicted entries, but never to or above the
 * lowest per-relay `since` (a relay still serves every wrap at or after its `since`), never while some relay has no
 * `since` (it serves everything it holds), and never above now − [FLOOR_MARGIN_SEC] (the 48 h NIP-59 jitter plus one
 * hour), so a wrap made just now, back-dated by up to 48 h, is never taken for an old one. A wrap created at or before
 * the floor counts as seen ([isSeen]). So the floor only covers wraps no relay can serve again: an entry the cap forces
 * out above it can come back as a duplicate, never as a loss (fixup G-KI24-1).
 *
 * Stored format: one line per id, "id:createdAt". A line with no time (written before KI-24) loads with the load time,
 * so nothing seen becomes unseen; it simply stays until the `since` line passes that time.
 */
internal class NostrSeenLedger(
    private val cap: () -> Long,
) {
    private val entries = LinkedHashMap<String, Long>()
    private var sizeAtLastPrune = 0

    /** Every wrap created at or before this time (seconds) counts as seen; 0 = none. Never decreases. */
    var floorSec: Long = 0L
        private set

    val size: Int get() = entries.size

    fun load(
        stored: String?,
        storedFloor: Long,
        loadTimeSec: Long,
    ) {
        entries.clear()
        stored
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            ?.forEach { line ->
                val colon = line.lastIndexOf(':')
                val time = if (colon > 0) line.substring(colon + 1).toLongOrNull() else null
                val id = if (time != null) line.substring(0, colon) else line
                if (id.isNotEmpty()) entries[id] = time ?: loadTimeSec
            }
        floorSec = storedFloor.coerceAtLeast(0L)
        sizeAtLastPrune = entries.size
    }

    fun clear() {
        entries.clear()
        floorSec = 0L
        sizeAtLastPrune = 0
    }

    fun contains(eventId: String): Boolean = entries.containsKey(eventId)

    fun timeOf(eventId: String): Long? = entries[eventId]

    /** In the list, or a wrap created at or before the floor. With no wrap time known, the list alone decides. */
    fun isSeen(
        eventId: String,
        wrapCreatedAtSec: Long?,
    ): Boolean = entries.containsKey(eventId) || (wrapCreatedAtSec != null && floorSec > 0 && wrapCreatedAtSec <= floorSec)

    /** What [record] did: whether anything changed, and the newest time the safety cap forced out (for a log). */
    data class Outcome(
        val changed: Boolean,
        val capForcedOutNewestSec: Long? = null,
    )

    /**
     * Records [eventId] as handled with its wrap time. Already listed: nothing changes. Then, when the list has grown
     * by [PRUNE_STEP] since the last pass or is over the cap, entries older than [lowestSinceSec] (null: some relay
     * has no `since` yet, so nothing is old enough) are dropped; and if the list is still over the cap, the oldest
     * go until it is a tenth under it. The floor they raise stays below [lowestSinceSec], and does not move while it
     * is null.
     */
    fun record(
        eventId: String,
        wrapCreatedAtSec: Long,
        lowestSinceSec: Long?,
        nowSec: Long,
    ): Outcome {
        if (entries.containsKey(eventId)) return Outcome(changed = false)
        entries[eventId] = wrapCreatedAtSec
        val limit = cap().coerceAtLeast(1L)
        val overCap = entries.size > limit
        if (lowestSinceSec != null && (overCap || entries.size >= sizeAtLastPrune + PRUNE_STEP)) {
            val old = entries.entries.filter { it.value < lowestSinceSec }
            old.forEach { entries.remove(it.key) }
            old.maxOfOrNull { it.value }?.let { raiseFloor(it, nowSec) }
            sizeAtLastPrune = entries.size
        }
        var capForcedOut: Long? = null
        if (entries.size > limit) {
            val keep = (limit - limit / 10).coerceAtLeast(1L)
            val byAge = entries.entries.sortedBy { it.value }
            val drop = byAge.take((entries.size - keep).toInt().coerceAtLeast(0))
            drop.forEach { entries.remove(it.key) }
            capForcedOut = drop.maxOfOrNull { it.value }
            // G-KI24-1: a relay still serves every wrap at or after its since, so the floor stays below the lowest since
            // (and does not move while a relay has none): a message never handled must not count as seen.
            if (capForcedOut != null && lowestSinceSec != null) raiseFloor(minOf(capForcedOut, lowestSinceSec - 1), nowSec)
            sizeAtLastPrune = entries.size
        }
        return Outcome(changed = true, capForcedOutNewestSec = capForcedOut)
    }

    /** Takes [eventId] out again (handling was deferred, so a relay replay may bring it back). */
    fun remove(eventId: String): Boolean = entries.remove(eventId) != null

    fun serialize(): String = entries.entries.joinToString("\n") { (id, time) -> "$id:$time" }

    private fun raiseFloor(
        evictedNewestSec: Long,
        nowSec: Long,
    ) {
        val candidate = minOf(evictedNewestSec, nowSec - FLOOR_MARGIN_SEC)
        if (candidate > floorSec) floorSec = candidate
    }

    companion object {
        /** The 48 h NIP-59 jitter (Nip17.JITTER_WINDOW_SEC) plus one hour for clock skew and relay delay. */
        const val FLOOR_MARGIN_SEC = 49L * 60 * 60

        /** The time-based prune runs once the list has grown by this many entries (amortised; normal use stays small). */
        const val PRUNE_STEP = 256
    }
}
