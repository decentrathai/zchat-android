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
}
