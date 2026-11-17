package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SY-2 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §3.3 step 1 with §9 row 7): the time the chat list's status
 * line shows is when the wallet last became fully synced in this process. `WalletSyncClock` (a Koin single next to
 * `WalletSnapshotDataSource`, so every ChatViewModel reads the same stamp) stamps it when the SDK reaches SYNCED at
 * 98 % or more **after a SYNCING seen in this process**, and keeps the stamp otherwise:
 *
 * - a SYNCED 100 % before any SYNCING is the SDK's state from before this launch (an offline first start, Fold U2)
 *   and stamps nothing, as `PostUpgradeRules.isFullyScanned` already treats it;
 * - each SYNCING → SYNCED stamps the time again; a repeated SYNCED (another field of the snapshot changed) keeps the
 *   first time, so offline the time stops advancing;
 * - SYNCING, DISCONNECTED and STOPPED keep the last stamp.
 *
 * Read by reflection so this RED test compiles before `WalletSyncClock` exists.
 */
class WalletSyncClockTest {
    private var now = 0L

    private val nowSupplier: () -> Long = { now }

    private val clock: Any by lazy {
        Class
            .forName("co.electriccoin.zcash.ui.common.datasource.WalletSyncClock")
            .getConstructor(Function0::class.java)
            .newInstance(nowSupplier)
    }

    private fun see(
        status: Synchronizer.Status,
        percent: Float,
        at: Long,
    ) {
        now = at
        clock.javaClass
            .getMethod("onSnapshot", Synchronizer.Status::class.java, Float::class.javaPrimitiveType)
            .invoke(clock, status, percent)
    }

    private fun stamp(): Long? = (clock.javaClass.getMethod("getSyncedAtMillis").invoke(clock) as StateFlow<*>).value as Long?

    @Test
    fun a_synced_left_over_from_before_the_launch_stamps_nothing() {
        see(Synchronizer.Status.SYNCED, 100f, 1_000)
        assertNull(stamp(), "SYNCED 100 before any SYNCING in this process is the SDK's old state")
        see(Synchronizer.Status.DISCONNECTED, 100f, 2_000)
        see(Synchronizer.Status.SYNCED, 100f, 3_000)
        assertNull(stamp(), "still no SYNCING seen")
    }

    @Test
    fun syncing_then_synced_stamps_and_each_new_sync_stamps_again() {
        see(Synchronizer.Status.SYNCING, 30f, 1_000)
        assertNull(stamp())
        see(Synchronizer.Status.SYNCED, 100f, 2_000)
        assertEquals(2_000L, stamp())
        see(Synchronizer.Status.SYNCED, 100f, 3_000)
        assertEquals(2_000L, stamp(), "a repeated SYNCED keeps the time it became synced")
        see(Synchronizer.Status.SYNCING, 0f, 4_000)
        assertEquals(2_000L, stamp(), "SYNCING keeps the last stamp")
        see(Synchronizer.Status.SYNCED, 100f, 5_000)
        assertEquals(5_000L, stamp())
        see(Synchronizer.Status.DISCONNECTED, 100f, 6_000)
        see(Synchronizer.Status.STOPPED, 100f, 7_000)
        assertEquals(5_000L, stamp(), "offline and stopped keep the last stamp")
    }

    @Test
    fun synced_below_98_percent_does_not_stamp() {
        see(Synchronizer.Status.SYNCING, 10f, 1_000)
        see(Synchronizer.Status.SYNCED, 97f, 2_000)
        assertNull(stamp())
        see(Synchronizer.Status.SYNCED, 98f, 3_000)
        assertEquals(3_000L, stamp())
    }

    @Test
    fun no_wallet_clears_the_stamp_so_the_next_wallet_starts_without_it() {
        see(Synchronizer.Status.SYNCING, 30f, 1_000)
        see(Synchronizer.Status.SYNCED, 100f, 2_000)
        assertEquals(2_000L, stamp())
        clock.javaClass.getMethod("onNoWallet").invoke(clock)
        assertNull(stamp(), "the old wallet's stamp outlives it")
        see(Synchronizer.Status.SYNCED, 100f, 3_000)
        assertNull(stamp(), "the next wallet's SYNCED before its own SYNCING stamps nothing")
        see(Synchronizer.Status.SYNCING, 50f, 4_000)
        see(Synchronizer.Status.SYNCED, 100f, 5_000)
        assertEquals(5_000L, stamp())
    }
}
