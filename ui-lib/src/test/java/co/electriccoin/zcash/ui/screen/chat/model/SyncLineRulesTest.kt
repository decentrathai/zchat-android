package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.Synchronizer
import org.junit.Test
import kotlin.test.assertEquals

/**
 * SY-2 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §3.2 with §9 row 7): the chat list's status line said
 * "Synced" whenever the 60-second local refresh was not running, also while the wallet was catching up and while
 * offline (Fold U1 and U2). The line's words come from `SyncLine.state(sdkStatus, progressPercent, catchUpPending,
 * syncedAtMillis, nowMillis)`, a pure rule:
 *
 * - DISCONNECTED is Offline and STOPPED is "Sync stopped", also with the catch-up bar up;
 * - with the catch-up bar up the line says "Catching up N%", the bar's N;
 * - no synchronizer yet, or INITIALIZING, is Connecting;
 * - SYNCING at 98 % or more is Synced (the banner's own threshold); below that it is Catching up, unless the wallet
 *   was fully synced less than 5 minutes ago (a routine block tick must not flicker "Catching up");
 * - SYNCED at 98 % or more is Synced only with a stamp from this process; without one it is the SDK's state from
 *   before this launch (an offline first start, U2) and is Connecting; SYNCED below 98 % is Catching up.
 *
 * Read by reflection so this RED test compiles before `SyncLine` exists.
 */
class SyncLineRulesTest {
    private val syncLine: Any by lazy {
        Class.forName("co.electriccoin.zcash.ui.screen.chat.model.SyncLine").getField("INSTANCE").get(null)!!
    }

    private val now = 10_000_000_000L
    private val minute = 60_000L

    private fun state(
        status: Synchronizer.Status?,
        progress: Float,
        catchUp: Boolean = false,
        stamp: Long? = null,
    ): String {
        val m =
            syncLine.javaClass.getMethod(
                "state",
                Synchronizer.Status::class.java,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Long::class.javaObjectType,
                Long::class.javaPrimitiveType,
            )
        val r = m.invoke(syncLine, status, progress, catchUp, stamp, now)!!
        val name = r.javaClass.simpleName
        return if (name == "CatchingUp") "CatchingUp(${r.javaClass.getMethod("getPercent").invoke(r)})" else name
    }

    @Test
    fun the_catch_up_bar_wins_except_offline_and_stopped() {
        assertEquals("CatchingUp(100)", state(Synchronizer.Status.SYNCED, 100f, catchUp = true, stamp = now - minute))
        assertEquals("CatchingUp(40)", state(Synchronizer.Status.SYNCING, 40.7f, catchUp = true, stamp = now - minute))
        assertEquals("CatchingUp(0)", state(null, 0f, catchUp = true))
        assertEquals("Offline", state(Synchronizer.Status.DISCONNECTED, 55f, catchUp = true))
        assertEquals("Stopped", state(Synchronizer.Status.STOPPED, 55f, catchUp = true))
    }

    @Test
    fun offline_stopped_and_connecting() {
        assertEquals("Offline", state(Synchronizer.Status.DISCONNECTED, 100f, stamp = now - minute))
        assertEquals("Stopped", state(Synchronizer.Status.STOPPED, 100f, stamp = now - minute))
        assertEquals("Connecting", state(null, 0f))
        assertEquals("Connecting", state(Synchronizer.Status.INITIALIZING, 0f))
    }

    @Test
    fun syncing_is_catching_up_unless_nearly_done_or_synced_less_than_five_minutes_ago() {
        assertEquals("CatchingUp(40)", state(Synchronizer.Status.SYNCING, 40f))
        assertEquals("CatchingUp(40)", state(Synchronizer.Status.SYNCING, 40f, stamp = now - 5 * minute - 1))
        // A routine block tick: the last full sync is fresh, so the line keeps "Synced" (no flicker).
        assertEquals("Synced", state(Synchronizer.Status.SYNCING, 0f, stamp = now - 4 * minute))
        assertEquals("Synced", state(Synchronizer.Status.SYNCING, 97f, stamp = now - minute))
        assertEquals("Synced", state(Synchronizer.Status.SYNCING, 99f))
        assertEquals("Synced", state(Synchronizer.Status.SYNCING, 98f))
    }

    @Test
    fun synced_needs_a_stamp_from_this_process_and_98_percent() {
        assertEquals("Synced", state(Synchronizer.Status.SYNCED, 100f, stamp = now - minute))
        assertEquals("Synced", state(Synchronizer.Status.SYNCED, 100f, stamp = now - 60 * minute))
        // The SDK's SYNCED from before this launch (offline first start, Fold U2): not "Synced".
        assertEquals("Connecting", state(Synchronizer.Status.SYNCED, 100f, stamp = null))
        assertEquals("CatchingUp(97)", state(Synchronizer.Status.SYNCED, 97f, stamp = now - minute))
    }

    @Test
    fun the_percent_is_the_bars_percent() {
        assertEquals("CatchingUp(55)", state(Synchronizer.Status.SYNCING, 55.9f))
        assertEquals("CatchingUp(0)", state(Synchronizer.Status.SYNCING, -3f))
        assertEquals("CatchingUp(100)", state(Synchronizer.Status.SYNCED, 140f, catchUp = true, stamp = now))
    }
}
