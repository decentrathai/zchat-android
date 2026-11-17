package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetConversationState
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * RED regression test pinning the ZchatPreferences.clearAll() wipe of the two sensitive stores added
 * at ZchatPreferencesImpl.clearAll() (ZchatPreferences.kt lines 2533-2534):
 *   - line 2533  ratchetPrefs.edit().clear().commit()   — per-message-key ratchet session state
 *                (send/recv counters + seen-sets = E2E session material)
 *   - line 2534  viewOncePrefs.edit().clear().commit()  — view-once consumed-file markers
 *
 * clearAll() is security-critical: a destroy/reset must not leave the previous identity's E2E
 * session material or consumed-file markers on disk for a fresh wallet to inherit.
 *
 * PASSES at HEAD; goes RED if either clear line is reverted:
 *   - drop line 2534 → isFileViewed / getAllViewedFiles still report the seeded marker after clearAll.
 *   - drop line 2533 → getRatchetStateStore().load(...) still returns the seeded state after clearAll.
 *
 * androidTest lane: ZchatPreferencesImpl wires real EncryptedSharedPreferences (Tink/keystore) and
 * needs an Android Context — mirrors the sibling ZchatPreferencesTest exactly.
 */
class RetestGap02ClearAllWipesRatchetAndViewOnceTest {

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

    @Test
    @SmallTest
    fun clearAll_wipesViewOnceMarkersAndRatchetSessionState() = runTest {
        // (1) Seed the view-once consumed-file store.
        prefs.markFileViewed("hashV1")
        assertThat(prefs.isFileViewed("hashV1"), equalTo(true))
        assertThat(prefs.getAllViewedFiles().isEmpty(), equalTo(false))

        // (2) Seed the ratchet session-state store with non-empty send/recv counters + seen-sets.
        val state =
            RatchetConversationState(
                convId = "CONV0001",
                nextCounterA2B = 5L,
                nextCounterB2A = 7L,
                seenCountersA2B = setOf(1L, 2L, 3L),
                seenCountersB2A = setOf(4L, 5L),
                maxSeenA2B = 3L,
                maxSeenB2A = 5L,
            )
        prefs.getRatchetStateStore().save(state)
        assertThat(prefs.getRatchetStateStore().load("CONV0001"), notNullValue())

        // (3) The wipe under test.
        prefs.clearAll()

        // (4) PINNING ASSERTIONS — RED if lines 2533-2534 are reverted.
        // view-once markers (line 2534):
        assertThat(prefs.isFileViewed("hashV1"), equalTo(false))
        assertThat(prefs.getAllViewedFiles().isEmpty(), equalTo(true))
        // ratchet session state (line 2533):
        assertThat(prefs.getRatchetStateStore().load("CONV0001"), nullValue())
    }
}
