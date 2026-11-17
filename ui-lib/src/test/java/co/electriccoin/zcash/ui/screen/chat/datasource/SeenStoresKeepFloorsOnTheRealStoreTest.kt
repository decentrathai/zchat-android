package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.nostr.NostrRelayPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 (fix-all 2.13.0, train T4; SPECS §2.3 steps 1-5 and 7-11, §2.2 B6): the seen stores on the real
 * ZchatPreferencesImpl code, over in-memory SharedPreferences files ([realPreferences]): what is written to the
 * "zchat_kex_seen" and "zchat_nostr_seen" files, what a reload reads back (the wallet-scope reload, and the old
 * one-entry-per-line form of 2.12.x), the relay `since` line, and the destroy/reset wipe. The ledgers use the release
 * caps (500 and 20,000). Every id and url is synthetic.
 */
class SeenStoresKeepFloorsOnTheRealStoreTest {
    private companion object {
        const val FIRST = 3_000_000L
        const val TIP = 3_100_000L
        const val HOUR = 3_600L
        const val MARGIN = 49 * HOUR
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    @Test
    fun `an evicted KEX stays processed at its height, across a reload, and the wipe clears the floor with the set`() {
        val (prefs, files) = realPreferences()
        for (i in 0..500) prefs.markKexTxProcessed("synthetic-kex-%04d".format(i), FIRST + i, TIP)

        assertFalse(prefs.hasProcessedKexTx("synthetic-kex-0000"))
        assertTrue(prefs.hasProcessedKexTx("synthetic-kex-0000", FIRST))
        assertEquals("the floor is written in the same file as the set", FIRST, files.getValue("kexSeenPrefs").getLong("floor_height", 0L))

        prefs.reloadWalletScopedStores()
        assertTrue("the floor is read back", prefs.hasProcessedKexTx("synthetic-kex-0000", FIRST))
        assertTrue("the heights are read back", prefs.hasProcessedKexTx("synthetic-kex-0500", FIRST + 500))
        assertFalse(prefs.hasProcessedKexTx("synthetic-kex-new", FIRST + 1))

        prefs.clearAll()
        assertFalse("a reset handles history again, as before KI-24", prefs.hasProcessedKexTx("synthetic-kex-0000", FIRST))
    }

    @Test
    fun `a KEX set written before KI-24 loads, and each entry gets its height when a pass sees it mined`() {
        val (prefs, files) = realPreferences()
        files
            .getValue("kexSeenPrefs")
            .edit()
            .putString("ids", "synthetic-legacy-1\nsynthetic-legacy-2")
            .commit()
        prefs.reloadWalletScopedStores()

        assertTrue(prefs.hasProcessedKexTx("synthetic-legacy-1"))
        prefs.markKexTxProcessed("synthetic-legacy-1", FIRST, TIP)
        assertEquals(
            "synthetic-legacy-1:$FIRST\nsynthetic-legacy-2",
            files.getValue("kexSeenPrefs").getString("ids", null),
        )
    }

    @Test
    fun `the relay seen list written before KI-24 loads with nothing seen becoming unseen (B6)`() {
        val (prefs, files) = realPreferences()
        files
            .getValue("nostrSeenPrefs")
            .edit()
            .putString("ids", "synthetic-old-evt-1\nsynthetic-old-evt-2")
            .commit()
        prefs.reloadWalletScopedStores()

        assertTrue(prefs.hasSeenNostrEvent("synthetic-old-evt-1"))
        assertTrue(prefs.hasSeenNostrEvent("synthetic-old-evt-2", 1L))
        assertFalse(prefs.hasSeenNostrEvent("synthetic-never", nowSec() - 30 * 24 * HOUR))
    }

    @Test
    fun `2001 relay events within a day are all still seen (the 2000 cap is gone)`() {
        val (prefs, _) = realPreferences()
        val now = nowSec()
        for (i in 0..2000) prefs.markNostrEventSeen("synthetic-evt-%05d".format(i), now - HOUR)

        assertTrue(prefs.hasSeenNostrEvent("synthetic-evt-00000"))
        assertTrue(prefs.hasSeenNostrEvent("synthetic-evt-02000"))
    }

    @Test
    fun `entries older than every relay's since leave the list, and their wraps still count as seen`() {
        val (prefs, _) = realPreferences()
        val now = nowSec()
        prefs.markNostrEventSeen("synthetic-ancient", now - 10 * 24 * HOUR)
        for (relay in NostrRelayPool.DEFAULT_RELAYS) assertTrue(prefs.advanceNostrRelaySince(relay, now - 3 * 24 * HOUR))
        for (i in 0 until NostrSeenLedger.PRUNE_STEP) prefs.markNostrEventSeen("synthetic-recent-$i", now - HOUR)

        assertFalse("pruned by time", prefs.hasSeenNostrEvent("synthetic-ancient"))
        assertTrue("its wrap is under the floor", prefs.hasSeenNostrEvent("synthetic-ancient", now - 10 * 24 * HOUR))
        assertFalse("a fresh jittered wrap is never under the floor", prefs.hasSeenNostrEvent("synthetic-new", now - 47 * HOUR))
    }

    @Test
    fun `a relay's since only rises, is never above now - 49 h, and the wipe clears it`() {
        val (prefs, files) = realPreferences()
        val relay = NostrRelayPool.DEFAULT_RELAYS.first()
        val now = nowSec()
        assertNull(prefs.getNostrRelaySince(relay))

        assertTrue(prefs.advanceNostrRelaySince(relay, now))
        val first = prefs.getNostrRelaySince(relay)!!
        assertTrue(first <= now - MARGIN + 1)
        assertFalse(prefs.advanceNostrRelaySince(relay, first - 100))
        assertEquals(first, prefs.getNostrRelaySince(relay))
        assertTrue(
            "kept in the seen list's file, so a wallet move takes both",
            files
                .getValue("nostrSeenPrefs")
                .all.keys
                .any { it.startsWith("nostr_since:") },
        )

        prefs.clearAll()
        assertNull(prefs.getNostrRelaySince(relay))
    }
}
