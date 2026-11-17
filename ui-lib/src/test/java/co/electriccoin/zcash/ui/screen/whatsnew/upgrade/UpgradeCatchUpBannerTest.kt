package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import cash.z.ecc.android.sdk.Synchronizer.Status
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bridge 2 (FINAL_PLAN 2.2 "Catch-up bar", COPY_DECK CB1/CB2):
 *
 * - Flag set: at a launch where a wallet exists and the stored last-seen version is missing or below 21300 (the
 *   What's New condition), so a new wallet's second cold start never sets it, and a routine sync never does.
 * - Flag cleared: when the SDK first reports the wallet fully scanned (SYNCED with scan progress complete),
 *   never on a timer. A SYNCED 100% before this launch has synced at all is the SDK's stale start state, not
 *   the signal. A restart mid-catch-up keeps the flag.
 * - Text: "Catching up after the Zcash network upgrade: 42%. Your balance may look low until this finishes."
 *   (ES required), on the chat list and the wallet screen.
 */
class UpgradeCatchUpBannerTest {
    @Test
    fun an_upgrade_from_2_12_4_sets_the_flag() {
        val store = InMemoryPostUpgradeStore()
        val notice = store.coldStart(walletExists = true)
        assertTrue(notice.catchUpPending.value)
        assertTrue(store.state.catchUpPending, "stored, so a kill keeps it")
    }

    @Test
    fun a_new_wallets_second_cold_start_never_sets_it() {
        val store = InMemoryPostUpgradeStore()
        assertFalse(store.coldStart(walletExists = false).catchUpPending.value)
        assertFalse(store.coldStart(walletExists = true).catchUpPending.value)
        assertFalse(store.state.catchUpPending)
    }

    @Test
    fun a_routine_sync_never_sets_it() {
        val store = InMemoryPostUpgradeStore(PostUpgradeState(lastSeenVersionCode = V2130))
        val notice = store.coldStart(walletExists = true)
        notice.onWalletSnapshot(Status.SYNCING, 0.2f)
        notice.onWalletSnapshot(Status.SYNCING, 0.9f)
        notice.onWalletSnapshot(Status.SYNCED, 1f)
        assertFalse(notice.catchUpPending.value)
        assertFalse(store.state.catchUpPending)
    }

    @Test
    fun only_the_fully_scanned_signal_clears_it() {
        val store = InMemoryPostUpgradeStore()
        val notice = store.coldStart(walletExists = true)

        notice.onWalletSnapshot(Status.SYNCING, 0.42f)
        assertTrue(notice.catchUpPending.value)
        notice.onWalletSnapshot(Status.SYNCING, 1f)
        assertTrue(notice.catchUpPending.value, "100% while still SYNCING is not the signal")
        notice.onWalletSnapshot(Status.SYNCED, 0.99f)
        assertTrue(notice.catchUpPending.value, "SYNCED below 100% is not the signal")
        notice.onWalletSnapshot(Status.DISCONNECTED, 1f)
        notice.onWalletSnapshot(Status.STOPPED, 1f)
        notice.onWalletSnapshot(Status.INITIALIZING, 1f)
        assertTrue(notice.catchUpPending.value)
        assertTrue(store.state.catchUpPending)

        notice.onWalletSnapshot(Status.SYNCED, 1f)
        assertFalse(notice.catchUpPending.value, "SYNCED at 100% after a sync in this launch clears it")
        assertFalse(store.state.catchUpPending)
        assertFalse(store.coldStart(walletExists = true).catchUpPending.value, "and it stays cleared")
    }

    @Test
    fun a_synced_100_percent_before_any_sync_in_this_launch_does_not_clear_it() {
        val store = InMemoryPostUpgradeStore()
        val notice = store.coldStart(walletExists = true)
        notice.onWalletSnapshot(Status.SYNCED, 1f) // the SDK's state from before the upgrade
        assertTrue(notice.catchUpPending.value)
        assertTrue(store.state.catchUpPending)
    }

    @Test
    fun a_restart_mid_catch_up_keeps_the_flag() {
        val store = InMemoryPostUpgradeStore()
        store.coldStart(walletExists = true).onWalletSnapshot(Status.SYNCING, 0.5f)
        // killed here

        val second = store.coldStart(walletExists = true)
        assertTrue(second.catchUpPending.value, "kept although the version is now stamped")
        second.onWalletSnapshot(Status.SYNCED, 1f)
        assertTrue(second.catchUpPending.value, "the second launch must sync too before it clears")
        second.onWalletSnapshot(Status.SYNCING, 0.97f)
        second.onWalletSnapshot(Status.SYNCED, 1f)
        assertFalse(second.catchUpPending.value)
    }

    @Test
    fun whats_new_closed_does_not_clear_the_catch_up_flag() {
        val store = InMemoryPostUpgradeStore()
        val notice = store.coldStart(walletExists = true)
        notice.onWhatsNewClosed()
        assertTrue(notice.catchUpPending.value)
        assertTrue(store.state.catchUpPending)
    }

    @Test
    fun a_failed_clear_write_keeps_the_stored_flag_for_the_next_launch() {
        val store = InMemoryPostUpgradeStore()
        val notice = store.coldStart(walletExists = true)
        store.failWrites = true
        notice.onWalletSnapshot(Status.SYNCING, 0.9f)
        notice.onWalletSnapshot(Status.SYNCED, 1f)
        assertTrue(store.state.catchUpPending, "nothing stored: the next launch shows the bar again")
    }

    @Test
    fun the_bar_shows_the_scan_percentage_only_while_the_flag_is_set() {
        assertEquals(42, PostUpgradeRules.catchUpBannerPercent(catchUpPending = true, progressPercent = 42.7f))
        assertEquals(0, PostUpgradeRules.catchUpBannerPercent(catchUpPending = true, progressPercent = 0f))
        assertEquals(100, PostUpgradeRules.catchUpBannerPercent(catchUpPending = true, progressPercent = 130f))
        assertEquals(0, PostUpgradeRules.catchUpBannerPercent(catchUpPending = true, progressPercent = -3f))
        assertNull(PostUpgradeRules.catchUpBannerPercent(catchUpPending = false, progressPercent = 42f))
    }

    @Test
    fun the_bar_text_is_the_copy_deck_text_in_english_and_spanish() {
        val dir = "ui-lib/src/main/res/ui/whats_new"
        val en = resourceStrings(File(repoRoot, "$dir/values/strings.xml").readText())
        val es = resourceStrings(File(repoRoot, "$dir/values-es/strings.xml").readText())
        assertEquals("Catching up after the Zcash network upgrade: %1\$d%%.", en["upgrade_catch_up_title"])
        assertEquals("Your balance may look low until this finishes.", en["upgrade_catch_up_subtitle"])
        assertEquals("Sincronizando tras la actualización de la red de Zcash: %1\$d %%.", es["upgrade_catch_up_title"])
        assertEquals("Puede que tu saldo se vea más bajo hasta que termine.", es["upgrade_catch_up_subtitle"])
    }

    @Test
    fun the_bar_is_on_the_chat_list_and_the_wallet_screen() {
        fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()
        val chatList = src("screen/chat/view/ChatListView.kt")
        assertTrue("catchUpBannerPercent(" in chatList || "catchUpBannerPercent(" in src("screen/chat/AndroidChat.kt"))
        assertTrue("R.string.upgrade_catch_up_title" in chatList, "the chat list bar uses the translated text")
        assertTrue("R.string.upgrade_catch_up_title" in src("screen/home/syncing/WalletSyncingMessage.kt"))
        assertTrue("catchUpPending" in src("common/usecase/GetHomeMessageUseCase.kt"), "the wallet screen follows the flag")
    }
}
