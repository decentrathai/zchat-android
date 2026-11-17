package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bridge 2 (FINAL_PLAN 2.2 "What's New opens once", Stage 1 item 6):
 *
 * - 2.13.0 stores the last-seen version on every launch.
 * - After unlock, if a wallet exists and the stored version is missing or below 21300, What's New opens once.
 * - It opens again if the app is killed while showing it.
 * - Fresh installs, whether the wallet is created or restored, never see it.
 */
class PostUpgradeNoticeTest {
    @Test
    fun an_upgrade_from_2_12_4_with_a_wallet_opens_whats_new_once() {
        // 2.12.4 never stored a version: the store is empty, and the wallet is there.
        val store = InMemoryPostUpgradeStore()

        val first = store.coldStart(walletExists = true)
        assertTrue(first.whatsNewPending.value)
        assertTrue(first.takeWhatsNewOpenRequest(), "What's New opens after the upgrade")
        assertFalse(first.takeWhatsNewOpenRequest(), "and only once in this launch")
        first.onWhatsNewClosed()
        assertFalse(first.whatsNewPending.value)

        val second = store.coldStart(walletExists = true)
        assertFalse(second.takeWhatsNewOpenRequest(), "never again after it was closed")
        assertEquals(V2130, store.state.lastSeenVersionCode)
    }

    @Test
    fun the_version_is_stamped_on_every_launch() {
        val store = InMemoryPostUpgradeStore()
        store.coldStart(walletExists = false)
        assertEquals(V2130, store.state.lastSeenVersionCode, "stamped even with no wallet")

        store.coldStart(walletExists = true, versionCode = 21301L)
        assertEquals(21301L, store.state.lastSeenVersionCode, "a later version replaces the stamp")
    }

    @Test
    fun a_kill_while_whats_new_is_showing_opens_it_again_at_the_next_launch() {
        val store = InMemoryPostUpgradeStore()

        val first = store.coldStart(walletExists = true)
        assertTrue(first.takeWhatsNewOpenRequest())
        // killed here: never closed

        val second = store.coldStart(walletExists = true)
        assertTrue(second.takeWhatsNewOpenRequest(), "shown again after the kill, although the version is stamped")
        second.onWhatsNewClosed()

        val third = store.coldStart(walletExists = true)
        assertFalse(third.takeWhatsNewOpenRequest())
    }

    @Test
    fun a_kill_before_unlock_keeps_whats_new_for_the_next_launch() {
        val store = InMemoryPostUpgradeStore()
        store.coldStart(walletExists = true) // killed on the lock screen: never opened

        assertTrue(store.coldStart(walletExists = true).takeWhatsNewOpenRequest())
    }

    @Test
    fun a_fresh_install_that_creates_a_wallet_never_sees_it() {
        val store = InMemoryPostUpgradeStore()

        val first = store.coldStart(walletExists = false) // first launch: onboarding, no wallet yet
        assertFalse(first.takeWhatsNewOpenRequest())
        // the wallet is created in this same process; no new launch decision is made

        val second = store.coldStart(walletExists = true) // second cold start, wallet now exists
        assertFalse(second.takeWhatsNewOpenRequest(), "a new wallet's second cold start never opens it")
        assertFalse(store.state.whatsNewPending)
    }

    @Test
    fun a_fresh_install_that_restores_a_wallet_never_sees_it() {
        val store = InMemoryPostUpgradeStore()
        store.coldStart(walletExists = false) // restore screen
        assertFalse(store.coldStart(walletExists = true).takeWhatsNewOpenRequest())
        assertFalse(store.coldStart(walletExists = true).takeWhatsNewOpenRequest())
    }

    @Test
    fun a_2_12_4_install_that_never_made_a_wallet_does_not_see_it() {
        val store = InMemoryPostUpgradeStore()
        assertFalse(store.coldStart(walletExists = false).takeWhatsNewOpenRequest())
        assertFalse(store.coldStart(walletExists = true).takeWhatsNewOpenRequest())
    }

    @Test
    fun a_stamp_below_21300_with_a_wallet_opens_it() {
        val store = InMemoryPostUpgradeStore(PostUpgradeState(lastSeenVersionCode = 21204L))
        assertTrue(store.coldStart(walletExists = true).takeWhatsNewOpenRequest())
    }

    @Test
    fun an_update_from_2_13_0_to_a_later_version_does_not_open_it() {
        val store = InMemoryPostUpgradeStore(PostUpgradeState(lastSeenVersionCode = V2130))
        assertFalse(store.coldStart(walletExists = true, versionCode = 21301L).takeWhatsNewOpenRequest())
    }

    @Test
    fun a_failed_write_still_shows_it_now_and_decides_again_next_launch() {
        val store = InMemoryPostUpgradeStore()
        store.failWrites = true

        val first = store.coldStart(walletExists = true)
        assertTrue(first.takeWhatsNewOpenRequest(), "shown in this launch even though nothing could be stored")
        assertEquals(PostUpgradeState(), store.state, "the old values are untouched")

        store.failWrites = false
        assertTrue(store.coldStart(walletExists = true).takeWhatsNewOpenRequest(), "nothing was lost")
    }

    @Test
    fun a_second_launch_call_in_the_same_process_changes_nothing() {
        val store = InMemoryPostUpgradeStore()
        val notice = store.coldStart(walletExists = false)
        notice.onLaunch(walletExists = true) // e.g. the activity is recreated after the wallet was created
        assertFalse(notice.takeWhatsNewOpenRequest())
        assertFalse(store.state.whatsNewPending)
    }

    @Test
    fun closing_it_when_nothing_is_pending_writes_nothing() {
        val store = InMemoryPostUpgradeStore(PostUpgradeState(lastSeenVersionCode = V2130))
        val notice = store.coldStart(walletExists = true)
        val writes = store.writes
        notice.onWhatsNewClosed() // opened from More -> What's New
        assertEquals(writes, store.writes)
    }

    @Test
    fun the_app_wires_the_launch_the_open_and_the_close() {
        fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()
        assertTrue("onLaunch(" in src("MainActivity.kt"), "MainActivity makes the launch decision")
        assertTrue("onWalletSnapshot(" in src("MainActivity.kt"), "MainActivity feeds wallet snapshots")
        val root = src("RootNavGraph.kt")
        assertTrue("takeWhatsNewOpenRequest()" in root && "NavigationTargets.WHATS_NEW" in root, "RootNavGraph opens it")
        assertTrue(
            "onWhatsNewClosed()" in src("screen/whatsnew/viewmodel/WhatsNewViewModel.kt"),
            "closing What's New clears it"
        )
    }
}
