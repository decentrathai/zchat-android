package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RetestGap13 — Core #205 self-address registry invariant (drift tolerance).
 *
 * Fix under test: ZchatPreferencesImpl.registerSelfAddress / isSelfAddress
 * (ZchatPreferences.kt ~2206/2225). A single wallet legitimately presents MULTIPLE own
 * unified-address representations over time (canonical vs diversified), so self-detection
 * cannot be a single-slot raw `==` compare. The fix maintains a hash MEMBERSHIP set
 * (SELF_ADDR_HASHES_KEY): registerSelfAddress adds generateAddressHash(address) to the set,
 * and isSelfAddress returns `set.contains(hash)`.
 *
 * RED-on-revert anchor: after registering TWO distinct own-wallet UA strings, BOTH must still
 * resolve to self. If the impl were reverted to storing/comparing a single self address
 * (raw `==`), the second registration overwrites the first and isSelfAddress(UA_CANON) flips
 * to false — reddening `assertTrue(isSelfAddress(UA_CANON))` below.
 *
 * Lane: androidTest — ZchatPreferencesImpl is backed by real SharedPreferences (its constructor
 * also opens EncryptedSharedPreferences via Android Keystore); no Robolectric is configured, so
 * the REAL impl is exercised on-device. Mirrors the ZchatPreferencesTest fixture in this dir.
 */
class RetestGap13SelfAddressRegistryTest {

    private lateinit var prefs: ZchatPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = ZchatPreferencesImpl(context)
        // clearAll() wipes `prefs` (which holds SELF_ADDR_HASHES_KEY) → clean registry per test.
        prefs.clearAll()
    }

    @After
    fun teardown() {
        prefs.clearAll()
    }

    @Test
    @SmallTest
    fun twoDistinctOwnAddressesBothResolveToSelf_unrelatedAndBlankDoNot() {
        val uaCanon = "u1selfcanonical"
        val uaDiversified = "u1selfdiversified" // a DIFFERENT string for the SAME wallet

        prefs.registerSelfAddress(uaCanon)
        prefs.registerSelfAddress(uaDiversified)

        // RED-on-revert anchor: a single-slot `==` self-check would have let uaDiversified
        // overwrite uaCanon, making this assertion fail.
        assertTrue("canonical own address must resolve to self", prefs.isSelfAddress(uaCanon))
        assertTrue("diversified own address must resolve to self", prefs.isSelfAddress(uaDiversified))

        assertFalse("an unrelated peer address must NOT resolve to self", prefs.isSelfAddress("u1peerother"))
        assertFalse("a blank address must NOT resolve to self", prefs.isSelfAddress(""))
    }

    @Test
    @SmallTest
    fun registryTracksEachRepresentationIndependently() {
        val uaCanon = "u1selfcanonical"
        val uaDiversified = "u1selfdiversified"

        // Before the diversified form is observed, only the canonical form is self.
        prefs.registerSelfAddress(uaCanon)
        assertTrue(prefs.isSelfAddress(uaCanon))
        assertFalse("diversified form not yet registered", prefs.isSelfAddress(uaDiversified))

        // Registering the diversified form adds it WITHOUT dropping the canonical membership.
        prefs.registerSelfAddress(uaDiversified)
        assertTrue("canonical membership must survive a second registration", prefs.isSelfAddress(uaCanon))
        assertTrue(prefs.isSelfAddress(uaDiversified))
    }
}
