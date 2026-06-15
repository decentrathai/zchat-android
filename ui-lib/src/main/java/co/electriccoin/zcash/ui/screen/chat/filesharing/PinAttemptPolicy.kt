package co.electriccoin.zcash.ui.screen.chat.filesharing

/**
 * Pure logic for PIN attempt rate-limiting.
 *
 * Policy:
 *   - Allow [MAX_ATTEMPTS] consecutive wrong attempts.
 *   - When the limit is reached, lock out for [lockoutMillisFor] (exponential by violation count,
 *     capped at [MAX_LOCKOUT_MS]).
 *   - The lockout deadline is anchored to BOTH `elapsedRealtime` (monotonic, survives clock drift)
 *     and wall clock (`System.currentTimeMillis`, survives reboot). [remainingLockoutMillis]
 *     returns the MAX of both remaining values — neither anchor alone is bypassable.
 *   - Successful verify clears the counter.
 *
 * This object holds no state. State is persisted by the caller in SharedPreferences.
 */
object PinAttemptPolicy {

    const val MAX_ATTEMPTS: Int = 5
    const val MAX_LOCKOUT_MS: Long = 60L * 60_000L      // 1 hour upper bound

    private val LOCKOUT_LADDER_MS: LongArray = longArrayOf(
        60_000L,       //  1 min  (first violation)
        5L * 60_000L,  //  5 min
        15L * 60_000L, // 15 min
        30L * 60_000L, // 30 min
        MAX_LOCKOUT_MS,
    )

    data class State(
        val failedAttempts: Int = 0,
        val violations: Int = 0,             // how many times we've crossed MAX_ATTEMPTS
        val lockoutUntilElapsed: Long = 0L,  // monotonic anchor (SystemClock.elapsedRealtime)
        val lockoutUntilWall: Long = 0L,     // wall-clock anchor (System.currentTimeMillis)
    )

    /**
     * Returns the lockout duration to apply when the [violationCount]-th violation occurs.
     * Clamped to [MAX_LOCKOUT_MS].
     */
    fun lockoutMillisFor(violationCount: Int): Long {
        if (violationCount <= 0) return 0L
        val idx = (violationCount - 1).coerceIn(0, LOCKOUT_LADDER_MS.size - 1)
        return LOCKOUT_LADDER_MS[idx]
    }

    /**
     * Compute remaining lockout in ms given the current monotonic + wall clocks. Returns 0
     * once both anchors have passed. Returns the MAX so an attacker who can shift one clock
     * still cannot accelerate the lockout below the other anchor's remaining time.
     */
    fun remainingLockoutMillis(
        state: State,
        nowElapsed: Long,
        nowWall: Long,
    ): Long {
        val elapsedRemain = (state.lockoutUntilElapsed - nowElapsed).coerceAtLeast(0L)
        val wallRemain = (state.lockoutUntilWall - nowWall).coerceAtLeast(0L)
        return maxOf(elapsedRemain, wallRemain)
    }

    /** Convenience: true when the user is currently locked out. */
    fun isLockedOut(state: State, nowElapsed: Long, nowWall: Long): Boolean =
        remainingLockoutMillis(state, nowElapsed, nowWall) > 0L

    /**
     * Transition on a failed attempt. If [failedAttempts]+1 reaches [MAX_ATTEMPTS], a new
     * lockout window starts (violation count incremented, deadline = now + ladder lookup).
     * Otherwise just the counter advances and the lockout deadlines are preserved.
     */
    fun onFailure(state: State, nowElapsed: Long, nowWall: Long): State {
        val nextFails = state.failedAttempts + 1
        if (nextFails < MAX_ATTEMPTS) {
            return state.copy(failedAttempts = nextFails)
        }
        val nextViolations = state.violations + 1
        val lockoutMs = lockoutMillisFor(nextViolations)
        return State(
            failedAttempts = 0,  // reset count, next violation starts a fresh counter
            violations = nextViolations,
            lockoutUntilElapsed = nowElapsed + lockoutMs,
            lockoutUntilWall = nowWall + lockoutMs,
        )
    }

    /** Transition on a successful verify — wipe all rate-limit state. */
    fun onSuccess(): State = State()
}
