package co.electriccoin.zcash.ui.service

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for the HIGH crash: [SyncForegroundService.promoteToForeground] calls
 * ServiceCompat.startForeground(...FOREGROUND_SERVICE_TYPE_DATA_SYNC) synchronously from
 * onStartCommand. On Android 14 an exhausted DATA_SYNC FGS runtime quota (or a lapsed background
 * -start grace) makes startForeground throw ForegroundServiceStartNotAllowedException; if that
 * escapes onStartCommand it is uncaught and crash-loops the whole app.
 *
 * The real Service can't be instantiated in a plain JVM unit test (no Robolectric in this module,
 * heavy Koin/Android wiring), so the defensive guard is extracted into the pure top-level
 * [guardForegroundPromotion], which promoteToForeground routes its startForeground call through.
 * These tests pin that guard: it must swallow the throw (never rethrow) yet still report it, and
 * report success on the happy path. Reverting the guard (removing the runCatching so the block's
 * throw propagates) makes the first test RED again — the same rethrow that crash-loops the app.
 */
class SyncForegroundPromotionGuardTest {

    /**
     * Stand-in for android.app.ForegroundServiceStartNotAllowedException, which is itself an
     * IllegalStateException subtype and cannot be constructed in a plain JVM unit test. The guard
     * swallows any Throwable, so a representative IllegalStateException faithfully exercises it.
     */
    private class FgsStartNotAllowed :
        IllegalStateException(
            "ForegroundServiceStartNotAllowedException: startForeground() blocked (FGS quota/grace)",
        )

    @Test
    fun `guard does not rethrow when startForeground throws — onStartCommand must never crash`() {
        // On the unguarded code this rethrows out of promoteToForeground -> onStartCommand ->
        // uncaught -> whole-app crash-loop. The guard must swallow it and report the failure.
        val result = guardForegroundPromotion { throw FgsStartNotAllowed() }

        assertTrue("guard must swallow the startForeground throw, not rethrow", result.isFailure)
        assertTrue(
            "failure must carry the original throwable so the caller can log it",
            result.exceptionOrNull() is FgsStartNotAllowed,
        )
    }

    @Test
    fun `guard runs the block and reports success on the happy path`() {
        var ran = false

        val result = guardForegroundPromotion { ran = true }

        assertTrue("guarded block must actually run", ran)
        assertTrue(
            "successful promotion must report success so the caller marks foreground",
            result.isSuccess,
        )
    }
}
