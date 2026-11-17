package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * RetestGap09 — regression pin for the #188 NOSTR gift-wrap replay-dedup fix.
 *
 * The fix (ZchatPreferences.kt:2056 markNostrEventSeen) persists handled inbound gift-wrap event ids
 * to a DEDICATED, bounded, persistent SharedPreferences store ("zchat_nostr_seen") with LRU-2000
 * eviction — independent of the pending-message store. The pre-fix behaviour keyed dedup off the
 * pending message list, so clearing pending messages (or a process recreation) resurrected replays.
 *
 * These assertions pass at HEAD and RED on revert of the dedicated-store / LRU behaviour. Instrumented
 * (androidTest) because the impl needs real SharedPreferences — the JVM lane returns default values.
 */
class RetestGap09NostrSeenDedupTest {

    private lateinit var prefs: ZchatPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = ZchatPreferencesImpl(context)
        prefs.clearAll()
    }

    @After
    fun teardown() {
        prefs.clearAll()
    }

    // (1) PERSISTENCE ACROSS RECREATION — a fresh impl (simulating process death) still sees the id.
    @Test
    @SmallTest
    fun markNostrEventSeen_survivesInstanceRecreation() {
        prefs.markNostrEventSeen("evt-A")
        assertThat(prefs.hasSeenNostrEvent("evt-A"), equalTo(true))

        // Fresh impl over the same Context = same cached SharedPreferences file loaded from disk.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs2 = ZchatPreferencesImpl(context)
        assertThat(prefs2.hasSeenNostrEvent("evt-A"), equalTo(true))
    }

    // (2) DECOUPLED FROM THE MESSAGE STORE — the core #188 regression. Clearing pending messages must
    // NOT drop the seen-event record; the pre-fix pending-list-keyed dedup would return false here.
    @Test
    @SmallTest
    fun markNostrEventSeen_decoupledFromPendingMessages() {
        prefs.addPendingMessage(
            ZchatPreferences.PendingMessageData("msg-1", "hello", 1000L, "u1peer")
        )
        prefs.markNostrEventSeen("evt-A")

        prefs.clearPendingMessages()

        assertThat(prefs.getPendingMessages().isEmpty(), equalTo(true))
        assertThat(prefs.hasSeenNostrEvent("evt-A"), equalTo(true))
    }

    // (3) LRU-2000 BOUND — the load-bearing eviction loop at ZchatPreferences.kt:2061. Mark 2001 distinct
    // ids; the oldest is evicted, the newest kept. Without the eviction, evt-0 would still be present.
    @Test
    @SmallTest
    fun markNostrEventSeen_evictsOldestBeyond2000() {
        for (i in 0..2000) {
            prefs.markNostrEventSeen("evt-$i")
        }

        assertThat(prefs.hasSeenNostrEvent("evt-0"), equalTo(false))
        assertThat(prefs.hasSeenNostrEvent("evt-2000"), equalTo(true))
    }

    // (4) UNMARK — an explicit un-see removes the id.
    @Test
    @SmallTest
    fun unmarkNostrEventSeen_clearsTheId() {
        prefs.markNostrEventSeen("evt-A")
        assertThat(prefs.hasSeenNostrEvent("evt-A"), equalTo(true))

        prefs.unmarkNostrEventSeen("evt-A")
        assertThat(prefs.hasSeenNostrEvent("evt-A"), equalTo(false))
    }
}
