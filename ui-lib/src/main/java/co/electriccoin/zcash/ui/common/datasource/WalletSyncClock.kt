package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SY-2: when the wallet last became fully synced in this process, for the chat list's status line.
 *
 * One Koin single, fed by [WalletSnapshotDataSourceImpl] with every wallet snapshot, so every ChatViewModel reads the
 * same stamp and a new one does not start without it. It lives in memory only: a new process starts with none.
 *
 * The stamp is set when the SDK reaches SYNCED at [SYNCED_PERCENT] or more **after a SYNCING seen in this process**,
 * once per SYNCING -> SYNCED; everything else keeps it. A SYNCED seen before any SYNCING is the SDK's state from
 * before this launch (an offline first start) and stamps nothing, as `PostUpgradeRules.isFullyScanned` treats it. A
 * repeated SYNCED (another field of the snapshot changed), or a SYNCED after DISCONNECTED with no SYNCING between,
 * proves no new sync and keeps the stamp, so offline the time stops advancing.
 */
class WalletSyncClock(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** A SYNCING was seen since the last stamp (or, before the first stamp, in this process). */
    private var syncingSinceStamp = false
    private val mutableSyncedAtMillis = MutableStateFlow<Long?>(null)

    val syncedAtMillis: StateFlow<Long?> = mutableSyncedAtMillis.asStateFlow()

    fun onSnapshot(
        status: Synchronizer.Status,
        progressPercent: Float,
    ) {
        synchronized(lock) {
            val fullySynced = status == Synchronizer.Status.SYNCED && progressPercent >= SYNCED_PERCENT
            if (status == Synchronizer.Status.SYNCING) {
                syncingSinceStamp = true
            } else if (fullySynced && syncingSinceStamp) {
                mutableSyncedAtMillis.value = now()
                syncingSinceStamp = false
            }
        }
    }

    /**
     * No synchronizer (no wallet yet, or it was closed or replaced): the stamp and the SYNCING seen belonged to it, so
     * the next wallet starts without them, as a new process does.
     */
    fun onNoWallet() {
        synchronized(lock) {
            syncingSinceStamp = false
            mutableSyncedAtMillis.value = null
        }
    }

    companion object {
        /** The banner's and the status line's "done" threshold (ChatListView). */
        const val SYNCED_PERCENT = 98f
    }
}
