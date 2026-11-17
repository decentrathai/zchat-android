package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What 2.13.0 remembers about the version the user last opened (Bridge 2, FINAL_PLAN 2.2 "After updating").
 *
 * - [lastSeenVersionCode]: the versionCode of the last launch; 2.12.4 and older never stored it.
 * - [whatsNewPending]: What's New still has to open once after an upgrade.
 * - [catchUpPending]: the "catching up after the Zcash network upgrade" bar is shown.
 */
data class PostUpgradeState(
    val lastSeenVersionCode: Long? = null,
    val whatsNewPending: Boolean = false,
    val catchUpPending: Boolean = false,
)

/** Where [PostUpgradeState] lives. [write] stores all three values at once and returns false if it failed. */
interface PostUpgradeStore {
    fun read(): PostUpgradeState

    fun write(state: PostUpgradeState): Boolean
}

object PostUpgradeRules {
    /** 2.13.0: the first version that stamps the last-seen version. */
    const val NOTICE_VERSION_CODE: Long = 21300L

    /**
     * The state to store at a launch: the version is stamped, and if a wallet exists and the stored version is
     * missing or below 2.13.0, What's New and the catch-up bar are set. Flags already set stay set, so a kill
     * before they were cleared shows them again. A fresh install has no wallet at its first launch, so it is
     * stamped before any wallet is created or restored and never matches.
     */
    fun atLaunch(
        stored: PostUpgradeState,
        walletExists: Boolean,
        currentVersionCode: Long,
    ): PostUpgradeState {
        val lastSeen = stored.lastSeenVersionCode
        val upgradedFromOlder = walletExists && (lastSeen == null || lastSeen < NOTICE_VERSION_CODE)
        return PostUpgradeState(
            lastSeenVersionCode = currentVersionCode,
            whatsNewPending = stored.whatsNewPending || upgradedFromOlder,
            catchUpPending = stored.catchUpPending || upgradedFromOlder,
        )
    }

    /**
     * The SDK's fully-scanned signal: SYNCED with the scan progress complete. The SDK passes through SYNCING on
     * every sync, so a SYNCED 100% seen before any SYNCING in this launch is its state from before the upgrade
     * and does not count.
     */
    fun isFullyScanned(
        status: Synchronizer.Status,
        progressFraction: Float,
        sawSyncingThisLaunch: Boolean,
    ): Boolean = sawSyncingThisLaunch && status == Synchronizer.Status.SYNCED && progressFraction >= 1f

    /** The percentage the catch-up bar shows, or null when it is not shown. */
    @Suppress("MagicNumber")
    fun catchUpBannerPercent(
        catchUpPending: Boolean,
        progressPercent: Float,
    ): Int? =
        if (catchUpPending) {
            progressPercent.toInt().coerceIn(0, 100)
        } else {
            null
        }

    /**
     * SY-1: the catch-up percentage from a wallet snapshot, as the chat list computes it (ChatViewModel's progress is
     * the snapshot's fraction times 100; no snapshot yet counts as 0), so the Wallet tab shows the same N.
     */
    @Suppress("MagicNumber")
    fun catchUpBannerPercentOf(
        catchUpPending: Boolean,
        snapshot: WalletSnapshot?,
    ): Int? = catchUpBannerPercent(catchUpPending, (snapshot?.progress?.decimal ?: 0f) * 100f)
}

/**
 * The version stamp, What's New once, and the catch-up flag, for one app process.
 *
 * [onLaunch] runs once per process, after the start-up storage check, off the main thread. If a write fails, the
 * decision still holds for this launch and the stored values are left as they were, so the next launch decides
 * again: What's New and the bar can show twice, never zero times.
 */
class PostUpgradeNotice(
    private val store: PostUpgradeStore,
    private val currentVersionCode: Long,
) {
    private val lock = Any()
    private var launched = false
    private var whatsNewRequested = false
    private var sawSyncing = false

    private val mutableWhatsNewPending = MutableStateFlow(false)
    private val mutableCatchUpPending = MutableStateFlow(false)

    val whatsNewPending: StateFlow<Boolean> = mutableWhatsNewPending.asStateFlow()

    val catchUpPending: StateFlow<Boolean> = mutableCatchUpPending.asStateFlow()

    /** Called once the start-up storage check has passed and it is known whether a wallet exists. */
    fun onLaunch(walletExists: Boolean) {
        synchronized(lock) {
            if (launched) return
            launched = true
            val stored = store.read()
            val next = PostUpgradeRules.atLaunch(stored, walletExists, currentVersionCode)
            if (next != stored) store.write(next)
            mutableWhatsNewPending.value = next.whatsNewPending
            mutableCatchUpPending.value = next.catchUpPending
        }
    }

    /** True once per process when What's New should be opened now. */
    fun takeWhatsNewOpenRequest(): Boolean =
        synchronized(lock) {
            if (!mutableWhatsNewPending.value || whatsNewRequested) {
                false
            } else {
                whatsNewRequested = true
                true
            }
        }

    /** The user closed What's New (also when it was opened from More, where nothing is pending). */
    fun onWhatsNewClosed() {
        synchronized(lock) {
            if (!mutableWhatsNewPending.value) return
            mutableWhatsNewPending.value = false
            store.write(store.read().copy(whatsNewPending = false))
        }
    }

    /** A wallet snapshot from the SDK; the first fully-scanned signal clears the catch-up flag. */
    fun onWalletSnapshot(
        status: Synchronizer.Status,
        progressFraction: Float,
    ) {
        synchronized(lock) {
            if (status == Synchronizer.Status.SYNCING) sawSyncing = true
            if (!mutableCatchUpPending.value) return
            if (!PostUpgradeRules.isFullyScanned(status, progressFraction, sawSyncing)) return
            mutableCatchUpPending.value = false
            store.write(store.read().copy(catchUpPending = false))
        }
    }
}

/** [PostUpgradeStore] in its own SharedPreferences file (not in any backup rule), written with commit(). */
class SharedPreferencesPostUpgradeStore(
    private val prefs: SharedPreferences,
) : PostUpgradeStore {
    override fun read(): PostUpgradeState =
        PostUpgradeState(
            lastSeenVersionCode = if (prefs.contains(KEY_LAST_SEEN)) prefs.getLong(KEY_LAST_SEEN, 0L) else null,
            whatsNewPending = prefs.getBoolean(KEY_WHATS_NEW, false),
            catchUpPending = prefs.getBoolean(KEY_CATCH_UP, false),
        )

    override fun write(state: PostUpgradeState): Boolean =
        prefs
            .edit()
            .apply {
                val lastSeen = state.lastSeenVersionCode
                if (lastSeen == null) remove(KEY_LAST_SEEN) else putLong(KEY_LAST_SEEN, lastSeen)
            }.putBoolean(KEY_WHATS_NEW, state.whatsNewPending)
            .putBoolean(KEY_CATCH_UP, state.catchUpPending)
            .commit()

    companion object {
        const val PREFS_NAME = "zchat_post_upgrade"
        private const val KEY_LAST_SEEN = "last_seen_version_code"
        private const val KEY_WHATS_NEW = "whats_new_pending"
        private const val KEY_CATCH_UP = "catch_up_pending"
    }
}
