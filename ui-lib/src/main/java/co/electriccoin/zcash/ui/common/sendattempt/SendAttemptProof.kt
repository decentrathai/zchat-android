package co.electriccoin.zcash.ui.common.sendattempt

/**
 * F4 (SPECS §1.2 "Proof"): a NOT_SENT or PARTIAL verdict is acted on only after two qualifying passes of the same
 * attempt, the second at a STRICTLY higher scanned tip than the first, each with its own fresh read of the wallet.
 *
 * Process-wide and in memory: the chat list and the open chat run their own passes, often at the same tip within a
 * second, so "non-decreasing" (F3's rule for TEX) would count those two as two. Any other verdict of the attempt clears
 * what was seen, so a rewind (a resync, a reorg) or one pass that could not decide starts the proof again.
 */
object SendAttemptProof {
    private data class Sighting(
        val verdict: SendAttemptVerdict,
        val tip: Long,
    )

    private val seen = HashMap<String, Sighting>()

    /** The key of one attempt: the row and its submit marker (a 2.12.4 row has none). */
    fun key(
        rowId: String,
        marker: Long?,
    ): String = rowId + "|" + (marker?.toString() ?: "legacy")

    /**
     * Records [verdict] at [tip] for the attempt [key]; true when it is NOT_SENT or PARTIAL and an earlier pass saw the
     * same verdict at a lower tip.
     */
    fun observe(
        key: String,
        verdict: SendAttemptVerdict,
        tip: Long?,
    ): Boolean =
        synchronized(seen) {
            val qualifies = verdict == SendAttemptVerdict.NotSent || verdict == SendAttemptVerdict.Partial
            if (!qualifies || tip == null) {
                seen.remove(key)
                return@synchronized false
            }
            val first = seen[key]
            when {
                first == null || first.verdict != verdict -> {
                    seen[key] = Sighting(verdict, tip)
                    false
                }
                // The same tip twice (two screens) or a lower one (a rewind) proves nothing more.
                tip > first.tip -> true
                else -> false
            }
        }

    /** Forgets the attempt (its verdict was applied, or the row moved on). */
    fun clear(key: String) {
        synchronized(seen) { seen.remove(key) }
    }

    /** Forgets every attempt: a rescan or a restore is running, so nothing seen before it proves anything after it. */
    fun clearAll() {
        synchronized(seen) {
            seen.clear()
            epoch++
        }
    }

    /** F8: counts every [clearAll]. A commit that rests on a proof captures it before its read of the chain. */
    private var epoch = 0L

    /** F8: the proof epoch now ([clearAll] moves it on). */
    fun epoch(): Long = synchronized(seen) { epoch }

    /**
     * F8: runs [block] under the proof's own lock, and only while the epoch is still [expected]: a commit never lands on a
     * proof that a rescan or a restore cleared meanwhile. Null when the epoch moved on (nothing ran).
     */
    fun <T> ifEpoch(
        expected: Long,
        block: () -> T,
    ): T? = synchronized(seen) { if (epoch == expected) block() else null }
}
