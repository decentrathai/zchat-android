package co.electriccoin.zcash.ui.screen.chat.filesharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinAttemptPolicyTest {

    private val START_ELAPSED = 10_000L
    private val START_WALL = 1_700_000_000_000L

    @Test
    fun `initial state has no lockout`() {
        val s = PinAttemptPolicy.State()
        assertFalse(PinAttemptPolicy.isLockedOut(s, START_ELAPSED, START_WALL))
        assertEquals(0L, PinAttemptPolicy.remainingLockoutMillis(s, START_ELAPSED, START_WALL))
    }

    @Test
    fun `four failures stay below the lockout threshold`() {
        var s = PinAttemptPolicy.State()
        repeat(4) {
            s = PinAttemptPolicy.onFailure(s, START_ELAPSED, START_WALL)
        }
        assertFalse(PinAttemptPolicy.isLockedOut(s, START_ELAPSED, START_WALL))
        assertEquals(4, s.failedAttempts)
        assertEquals(0, s.violations)
    }

    @Test
    fun `fifth failure triggers a 60-second lockout`() {
        var s = PinAttemptPolicy.State()
        repeat(5) {
            s = PinAttemptPolicy.onFailure(s, START_ELAPSED, START_WALL)
        }
        assertTrue(PinAttemptPolicy.isLockedOut(s, START_ELAPSED, START_WALL))
        assertEquals(60_000L, PinAttemptPolicy.remainingLockoutMillis(s, START_ELAPSED, START_WALL))
        assertEquals(1, s.violations)
        assertEquals(0, s.failedAttempts)  // counter resets, ready for the next window
    }

    @Test
    fun `lockout ladder escalates by violation count`() {
        assertEquals(60_000L, PinAttemptPolicy.lockoutMillisFor(1))
        assertEquals(5 * 60_000L, PinAttemptPolicy.lockoutMillisFor(2))
        assertEquals(15 * 60_000L, PinAttemptPolicy.lockoutMillisFor(3))
        assertEquals(30 * 60_000L, PinAttemptPolicy.lockoutMillisFor(4))
        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, PinAttemptPolicy.lockoutMillisFor(5))
        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, PinAttemptPolicy.lockoutMillisFor(99))
    }

    @Test
    fun `remaining lockout takes MAX of elapsed and wall anchors`() {
        // Attacker rolls wall clock forward by 10 minutes but elapsedRealtime still shows fresh lockout.
        val s = PinAttemptPolicy.State(
            failedAttempts = 0,
            violations = 1,
            lockoutUntilElapsed = START_ELAPSED + 60_000L,
            lockoutUntilWall = START_WALL + 60_000L,
        )
        val tamperedWallNow = START_WALL + 10L * 60_000L  // user-shifted clock
        val freshElapsedNow = START_ELAPSED + 30_000L     // monotonic — actually 30s elapsed
        val remain = PinAttemptPolicy.remainingLockoutMillis(s, freshElapsedNow, tamperedWallNow)
        // Wall anchor would say 0 (past); elapsed anchor still says 30s left. Take MAX = 30s.
        assertEquals(30_000L, remain)
    }

    @Test
    fun `lockout clears once both anchors have passed`() {
        val s = PinAttemptPolicy.State(
            failedAttempts = 0,
            violations = 1,
            lockoutUntilElapsed = START_ELAPSED + 60_000L,
            lockoutUntilWall = START_WALL + 60_000L,
        )
        val pastBoth = PinAttemptPolicy.remainingLockoutMillis(
            s,
            nowElapsed = START_ELAPSED + 90_000L,
            nowWall = START_WALL + 90_000L,
        )
        assertEquals(0L, pastBoth)
        assertFalse(PinAttemptPolicy.isLockedOut(s, START_ELAPSED + 90_000L, START_WALL + 90_000L))
    }

    @Test
    fun `onSuccess wipes counter and violations`() {
        val s = PinAttemptPolicy.State(failedAttempts = 4, violations = 2)
        val cleared = PinAttemptPolicy.onSuccess()
        assertEquals(0, cleared.failedAttempts)
        assertEquals(0, cleared.violations)
        assertEquals(0L, cleared.lockoutUntilElapsed)
        assertEquals(0L, cleared.lockoutUntilWall)
    }

    // ---- added coverage: escalation via onFailure, clamp, backward-clock tamper -----------------

    @Test
    fun `lockoutMillisFor returns zero for non-positive violation counts`() {
        assertEquals(0L, PinAttemptPolicy.lockoutMillisFor(0))
        assertEquals(0L, PinAttemptPolicy.lockoutMillisFor(-1))
    }

    @Test
    fun `a second full round of failures escalates the lockout to five minutes`() {
        // First window: 5 failures → 1st violation → 60s. Simulate that lockout elapsing, then another
        // 5 failures → 2nd violation → the ladder climbs to 5 minutes.
        var s = PinAttemptPolicy.State()
        repeat(5) { s = PinAttemptPolicy.onFailure(s, START_ELAPSED, START_WALL) }
        assertEquals(1, s.violations)

        val laterElapsed = START_ELAPSED + 60_000L
        val laterWall = START_WALL + 60_000L
        repeat(5) { s = PinAttemptPolicy.onFailure(s, laterElapsed, laterWall) }
        assertEquals(2, s.violations)
        assertEquals(0, s.failedAttempts)
        assertEquals(5 * 60_000L, PinAttemptPolicy.remainingLockoutMillis(s, laterElapsed, laterWall))
    }

    @Test
    fun `escalation clamps at the one-hour maximum after many violations`() {
        var s = PinAttemptPolicy.State(violations = 4) // next violation is the 5th (top of ladder)
        s = triggerOneViolation(s)
        assertEquals(5, s.violations)
        assertEquals(
            PinAttemptPolicy.MAX_LOCKOUT_MS,
            PinAttemptPolicy.remainingLockoutMillis(s, START_ELAPSED, START_WALL)
        )
        // A 6th violation stays clamped at the max — never grows unbounded.
        val afterFirst = s
        s = triggerOneViolation(s.copy())
        assertEquals(6, s.violations)
        assertEquals(
            PinAttemptPolicy.MAX_LOCKOUT_MS,
            PinAttemptPolicy.remainingLockoutMillis(s, START_ELAPSED, START_WALL)
        )
        // sanity: the previous window was also clamped
        assertEquals(
            PinAttemptPolicy.MAX_LOCKOUT_MS,
            PinAttemptPolicy.remainingLockoutMillis(afterFirst, START_ELAPSED, START_WALL)
        )
    }

    @Test
    fun `setting the wall clock BACKWARD cannot bypass the monotonic anchor`() {
        // Fresh 60s lockout.
        var s = PinAttemptPolicy.State()
        repeat(5) { s = PinAttemptPolicy.onFailure(s, START_ELAPSED, START_WALL) }
        // Attacker rolls the wall clock far into the past to try to make the deadline look expired.
        val wallRolledBack = START_WALL - 10L * 60_000L
        // Monotonic elapsedRealtime can't be rolled back; only 10s have really passed.
        val elapsedNow = START_ELAPSED + 10_000L
        val remain = PinAttemptPolicy.remainingLockoutMillis(s, elapsedNow, wallRolledBack)
        // Elapsed anchor still says 50s left; wall anchor says way more (deadline far in the "future"
        // of the rolled-back clock). MAX keeps the user locked — no bypass.
        assertTrue(remain >= 50_000L, "still locked out despite backward wall clock")
        assertTrue(PinAttemptPolicy.isLockedOut(s, elapsedNow, wallRolledBack))
    }

    @Test
    fun `onFailure below the threshold preserves an existing lockout deadline`() {
        // A pre-existing lockout window with one non-locking failure recorded afterward must not clear
        // or shorten the deadlines.
        val locked = PinAttemptPolicy.State(
            failedAttempts = 0,
            violations = 1,
            lockoutUntilElapsed = START_ELAPSED + 60_000L,
            lockoutUntilWall = START_WALL + 60_000L,
        )
        val next = PinAttemptPolicy.onFailure(locked, START_ELAPSED, START_WALL)
        assertEquals(1, next.failedAttempts)
        assertEquals(1, next.violations)
        assertEquals(locked.lockoutUntilElapsed, next.lockoutUntilElapsed)
        assertEquals(locked.lockoutUntilWall, next.lockoutUntilWall)
    }

    private fun triggerOneViolation(from: PinAttemptPolicy.State): PinAttemptPolicy.State {
        var s = from
        repeat(5) { s = PinAttemptPolicy.onFailure(s, START_ELAPSED, START_WALL) }
        return s
    }
}
