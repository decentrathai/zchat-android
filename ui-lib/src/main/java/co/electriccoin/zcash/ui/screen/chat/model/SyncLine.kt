package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.datasource.WalletSyncClock

/** SY-2: what the chat list's status line says about the wallet. */
sealed interface SyncLineState {
    /** "HH:mm:ss · Ns · Synced": the wallet is synced; the time is its last full sync in this process. */
    data object Synced : SyncLineState

    /** "Catching up N%": the SDK is scanning, or the catch-up bar is up (then N is the bar's N). */
    data class CatchingUp(
        val percent: Int
    ) : SyncLineState

    /** "Offline": the SDK reports DISCONNECTED. */
    data object Offline : SyncLineState

    /** "Sync stopped": the SDK reports STOPPED. */
    data object Stopped : SyncLineState

    /** "Connecting…": no synchronizer yet, INITIALIZING, or a SYNCED not yet confirmed in this process. */
    data object Connecting : SyncLineState
}

/**
 * SY-2: the rule behind the chat list's status line. It said "Synced" whenever the 60-second local refresh was not
 * running, also while the wallet was catching up and while offline (Fold U1 and U2).
 */
object SyncLine {
    /** A SYNCING this soon after the last full sync is a routine block tick: the line keeps "Synced". */
    const val NO_FLICKER_MILLIS: Long = 5 * 60 * 1000L

    /** ZC-1266: only "Catching up N%" opens the explanation on a tap; the other lines say what they mean. */
    fun explainsOnTap(line: SyncLineState): Boolean = line is SyncLineState.CatchingUp

    /**
     * [progressPercent] is 0-100 as the banner reads it; [syncedAtMillis] is [WalletSyncClock]'s stamp (null until the
     * wallet became fully synced in this process).
     */
    fun state(
        sdkStatus: Synchronizer.Status?,
        progressPercent: Float,
        catchUpPending: Boolean,
        syncedAtMillis: Long?,
        nowMillis: Long,
    ): SyncLineState {
        val percent = progressPercent.toInt().coerceIn(0, 100)
        val nearlyDone = progressPercent >= WalletSyncClock.SYNCED_PERCENT
        return when {
            sdkStatus == Synchronizer.Status.DISCONNECTED -> SyncLineState.Offline
            sdkStatus == Synchronizer.Status.STOPPED -> SyncLineState.Stopped
            // The same N as the catch-up bar above (PostUpgradeRules.catchUpBannerPercent).
            catchUpPending -> SyncLineState.CatchingUp(percent)
            sdkStatus == Synchronizer.Status.SYNCING ->
                when {
                    nearlyDone -> SyncLineState.Synced
                    syncedAtMillis != null && nowMillis - syncedAtMillis < NO_FLICKER_MILLIS -> SyncLineState.Synced
                    else -> SyncLineState.CatchingUp(percent)
                }
            sdkStatus == Synchronizer.Status.SYNCED ->
                when {
                    !nearlyDone -> SyncLineState.CatchingUp(percent)
                    // A SYNCED left over from before this launch (an offline first start) is not "Synced".
                    syncedAtMillis == null -> SyncLineState.Connecting
                    else -> SyncLineState.Synced
                }
            else -> SyncLineState.Connecting
        }
    }
}
