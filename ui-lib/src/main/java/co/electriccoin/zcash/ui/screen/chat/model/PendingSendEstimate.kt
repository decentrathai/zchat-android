package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Pure (no Android / Compose deps) helper for the "Sending after confirmation" affordance shown on a
 * queued/pending outgoing message — one that is waiting for the previous transaction's change notes
 * to confirm on-chain before it can be spent.
 *
 * The estimate is intentionally coarse: a Zcash block is ~75s, and we cannot know the exact moment
 * the next block will land, so we report a single-block ETA and clamp progress to a near-but-never-
 * complete fraction so the bar keeps showing motion without ever implying "done".
 *
 * Kept side-effect-free and framework-free so it can be JVM-unit-tested without Robolectric.
 */
object PendingSendEstimate {
    /** Approximate Zcash target block interval, in seconds. */
    const val AVERAGE_BLOCK_SECONDS: Long = 75L

    /**
     * Progress is clamped to this ceiling while still waiting — a pending send never renders a full
     * bar, since "full" should only mean confirmed/sent.
     */
    const val MAX_PROGRESS: Float = 0.95f

    /**
     * Coarse 0..[MAX_PROGRESS] progress for a message that has been queued for [elapsedSeconds].
     *
     * Returns `null` when no usable elapsed time is available — the caller should fall back to an
     * indeterminate bar in that case. A single block (~75s) maps roughly linearly to the ceiling;
     * messages that have waited longer than one block stay pinned at the ceiling (the chain is just
     * taking longer than average) rather than wrapping or completing.
     */
    fun progressFor(elapsedSeconds: Long): Float? {
        if (elapsedSeconds < 0L) return null
        val raw = elapsedSeconds.toFloat() / AVERAGE_BLOCK_SECONDS.toFloat()
        return raw.coerceIn(0f, MAX_PROGRESS)
    }

    /**
     * Convenience overload that derives elapsed seconds from a queued-at epoch millis and a "now"
     * epoch millis. Returns `null` (→ indeterminate) when the inputs are non-sensical (now before
     * queued).
     */
    fun progressFor(queuedAtMillis: Long, nowMillis: Long): Float? {
        val elapsed = (nowMillis - queuedAtMillis) / 1000L
        return progressFor(elapsed)
    }

    /**
     * Short, human-readable label for the pending affordance, e.g. "Sending after confirmation
     * (~75s)" initially, then "Sending after confirmation (~45s left)" as time elapses. Never shows a
     * negative or zero countdown — once we are past the one-block estimate it falls back to a generic
     * "(any moment…)" so the user is not told a stale "~0s".
     */
    fun label(elapsedSeconds: Long): String {
        val remaining = AVERAGE_BLOCK_SECONDS - elapsedSeconds
        val suffix = when {
            elapsedSeconds <= 0L -> "~${AVERAGE_BLOCK_SECONDS}s"
            remaining > 0L -> "~${remaining}s left"
            else -> "any moment…"
        }
        return "Sending after confirmation ($suffix)"
    }
}
