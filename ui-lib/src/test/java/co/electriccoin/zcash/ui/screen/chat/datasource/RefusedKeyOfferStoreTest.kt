package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.KeyAcceptApi
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3A.5 row 11; glue G2, G4; rules R1, R3) — the one remembered
 * refused key per contact ("keyoffer:<peer>" in the E2E key file, next to the key-changed flag).
 *
 * Must hold: the first offer is the record and sets the flag in the same commit; the same key again replaces memo and
 * txid and keeps first seen; a different key is only counted; clearing the flag does nothing while a record exists; a
 * record whose flag is down (a 2.12.4 install cleared it) is dropped when read and a new episode starts a new record;
 * take is compare-and-remove on the WHOLE key and clears the flag in the same commit; dismiss removes both;
 * clearE2EKeys and clearAll remove the record; clearE2EPeerState keeps our key pair, its provenance and version, the
 * flag and the record and removes everything else clearE2EKeys removes; a commit that fails changes nothing. Uses the
 * real ZchatPreferencesImpl over in-memory files ([realPreferences]).
 */
class RefusedKeyOfferStoreTest {
    @Test
    fun `the first offer is the record and raises the flag in the same commit`() {
        val (impl, files) = realPreferences()
        val commits = CountingPrefs.install(impl, files)

        assertNull(KeyAcceptApi.record(impl, PEER))
        assertFalse(KeyAcceptApi.has(impl, PEER))
        commits.writes = 0
        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))

        assertEquals("the record and the flag must be one write", 1, commits.writes)
        assertTrue("creating a record must raise the flag", impl.isE2EKeyChanged(PEER))
        assertTrue(KeyAcceptApi.has(impl, PEER))
        val record = KeyAcceptApi.record(impl, PEER)!!
        assertEquals(KEY_A, KeyAcceptApi.get(record, "publicKey"))
        assertEquals("memo-a", KeyAcceptApi.get(record, "memo"))
        assertEquals("tx-a", KeyAcceptApi.get(record, "txId"))
        assertEquals(1_000L, KeyAcceptApi.get(record, "firstSeenMs"))
        assertEquals(0, KeyAcceptApi.get(record, "laterOffers"))
        assertNull("another contact has no record", KeyAcceptApi.record(impl, OTHER))
    }

    @Test
    fun `the same key refreshes memo and txid and a different key is only counted`() {
        val (impl, _) = realPreferences()
        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))

        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_B, "memo-b", "tx-b", 2_000L))
        var record = KeyAcceptApi.record(impl, PEER)!!
        assertEquals("a different key replaced the first offer", KEY_A, KeyAcceptApi.get(record, "publicKey"))
        assertEquals("memo-a", KeyAcceptApi.get(record, "memo"))
        assertEquals(1, KeyAcceptApi.get(record, "laterOffers"))

        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a2", null, 3_000L))
        record = KeyAcceptApi.record(impl, PEER)!!
        assertEquals("memo-a2", KeyAcceptApi.get(record, "memo"))
        assertNull(KeyAcceptApi.get(record, "txId"))
        assertEquals("first seen is kept", 1_000L, KeyAcceptApi.get(record, "firstSeenMs"))
        assertEquals(1, KeyAcceptApi.get(record, "laterOffers"))
    }

    @Test
    fun `clearing the flag does nothing while a record exists and a record without its flag is dropped`() {
        val (impl, files) = realPreferences()
        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))

        impl.setE2EKeyChanged(PEER, false)
        assertTrue("a flag clear took the banner down while a key waits", impl.isE2EKeyChanged(PEER))
        assertNotNull(KeyAcceptApi.record(impl, PEER))

        // A 2.12.4 install (after a downgrade) cleared the flag: it knows nothing of the record.
        val e2e = files.getValue("e2ePrefs")
        val raised = e2e.snapshot().filterValues { it == true }
        val flagKey = raised.keys.single { it.endsWith(PEER) && "changed" in it }
        e2e.edit().putBoolean(flagKey, false).commit()

        assertNull("a record whose flag is down must not be offered", KeyAcceptApi.record(impl, PEER))
        assertFalse(KeyAcceptApi.has(impl, PEER))
        assertTrue("a new episode starts a new record", KeyAcceptApi.remember(impl, PEER, KEY_B, "memo-b", "tx-b", 9_000L))
        val record = KeyAcceptApi.record(impl, PEER)!!
        assertEquals(KEY_B, KeyAcceptApi.get(record, "publicKey"))
        assertEquals(9_000L, KeyAcceptApi.get(record, "firstSeenMs"))
        assertEquals(0, KeyAcceptApi.get(record, "laterOffers"))
    }

    @Test
    fun `take is compare-and-remove on the whole key and clears the flag in the same commit`() {
        val (impl, files) = realPreferences()
        val commits = CountingPrefs.install(impl, files)
        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))

        assertNull("another key took the record", KeyAcceptApi.take(impl, PEER, KEY_B))
        assertNull("a prefix took the record", KeyAcceptApi.take(impl, PEER, KEY_A.dropLast(1)))
        assertNotNull(KeyAcceptApi.record(impl, PEER))
        assertTrue(impl.isE2EKeyChanged(PEER))

        commits.writes = 0
        val taken = KeyAcceptApi.take(impl, PEER, KEY_A)
        assertNotNull("the whole key must take the record", taken)
        assertEquals("memo-a", KeyAcceptApi.get(taken!!, "memo"))
        assertEquals("the record and the flag must go in one write", 1, commits.writes)
        assertNull(KeyAcceptApi.record(impl, PEER))
        assertFalse(impl.isE2EKeyChanged(PEER))
        assertNull("a second take found the record again", KeyAcceptApi.take(impl, PEER, KEY_A))
    }

    @Test
    fun `dismiss, clearE2EKeys and clearAll remove the record`() {
        val (impl, _) = realPreferences()
        assertFalse("nothing to dismiss", KeyAcceptApi.dismissInStore(impl, PEER))

        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))
        assertTrue(KeyAcceptApi.dismissInStore(impl, PEER))
        assertNull(KeyAcceptApi.record(impl, PEER))
        assertFalse("dismiss clears the flag with the record", impl.isE2EKeyChanged(PEER))

        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))
        impl.clearE2EKeys(PEER)
        assertNull("a Reset left the record", KeyAcceptApi.record(impl, PEER))
        assertFalse(impl.isE2EKeyChanged(PEER))

        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))
        impl.clearAll()
        assertNull("clearAll left the record", KeyAcceptApi.record(impl, PEER))
    }

    @Test
    fun `clearE2EPeerState keeps our key pair, the flag and the record and removes what clearE2EKeys removes`() {
        val (kept, keptFiles) = seeded()
        val (cleared, clearedFiles) = seeded()

        KeyAcceptApi.clearPeerState(kept, PEER)
        cleared.clearE2EKeys(PEER)

        assertEquals("our-public", kept.getE2EOurPublicKey(PEER))
        assertEquals("our-private", kept.getE2EPrivateKey(PEER))
        assertEquals(2, kept.getE2EKeyVersion(PEER))
        assertEquals("provenance", kept.getE2EKeyProvenance(PEER))
        assertTrue("the flag must stay", kept.isE2EKeyChanged(PEER))
        assertEquals("the record must stay", KEY_A, KeyAcceptApi.get(KeyAcceptApi.record(kept, PEER)!!, "publicKey"))
        assertNull("the contact's key must go", kept.getE2EPeerPublicKey(PEER))
        assertFalse("verified must go", kept.isE2EVerified(PEER))
        assertNull(kept.getE2EKexTxId(PEER))
        val afterKept = keptFiles.getValue("e2ePrefs").snapshot().filterKeys { PEER in it }
        val afterCleared = clearedFiles.getValue("e2ePrefs").snapshot().filterKeys { PEER in it }
        val onlyCleared = afterCleared.keys - afterKept.keys
        val onlyKept = afterKept.keys - afterCleared.keys
        assertTrue("clearE2EKeys left $onlyCleared that the peer half removed", onlyCleared.isEmpty())
        assertEquals("our public and private key, version, provenance, the flag and the record", 6, onlyKept.size)
    }

    @Test
    fun `a commit that fails changes nothing`() {
        val (impl, files) = realPreferences()
        val commits = CountingPrefs.install(impl, files)
        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))

        commits.failCommits = true
        assertNull("a take that did not reach the disk reported success", KeyAcceptApi.take(impl, PEER, KEY_A))
        assertFalse("a dismiss that did not reach the disk reported success", KeyAcceptApi.dismissInStore(impl, PEER))
        val remembered = KeyAcceptApi.remember(impl, PEER, KEY_B, "memo-b", "tx-b", 2_000L)
        assertFalse("a remember that did not reach the disk reported success", remembered)
        commits.failCommits = false

        val record = KeyAcceptApi.record(impl, PEER)
        assertNotNull("a failed commit removed the record", record)
        assertEquals(KEY_A, KeyAcceptApi.get(record!!, "publicKey"))
        assertEquals("a failed commit counted an offer", 0, KeyAcceptApi.get(record, "laterOffers"))
        assertTrue("a failed commit cleared the flag", impl.isE2EKeyChanged(PEER))
    }

    private fun seeded(): Pair<ZchatPreferencesImpl, Map<String, MemorySharedPreferences>> {
        val (impl, files) = realPreferences()
        impl.setE2EOurKeys(PEER, "our-public", "our-private")
        impl.setE2EKeyVersion(PEER, 2)
        impl.setE2EKeyProvenance(PEER, "provenance")
        impl.setE2EPeerPublicKey(PEER, "their-old-key")
        impl.setE2EEnabled(PEER, true)
        impl.setE2EVerified(PEER, true)
        impl.setE2EKexTxId(PEER, "kex-txid")
        impl.setE2EKexAckTxId(PEER, "kexack-txid")
        assertTrue(KeyAcceptApi.remember(impl, PEER, KEY_A, "memo-a", "tx-a", 1_000L))
        return impl to files
    }

    /**
     * The E2E key file with its writes counted ([writes]: every commit and apply) and, with [failCommits], commits
     * that report "not saved" after changing memory, as Android's SharedPreferences does when the disk write fails.
     */
    private class CountingPrefs(
        private val inner: MemorySharedPreferences,
    ) : SharedPreferences by inner {
        @Volatile var writes = 0

        @Volatile var failCommits = false

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }

                override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }

                override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }

                override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }

                override fun remove(key: String) = apply { editor.remove(key) }

                override fun clear() = apply { editor.clear() }

                override fun commit(): Boolean {
                    writes++
                    editor.commit()
                    return !failCommits
                }

                override fun apply() {
                    writes++
                    editor.apply()
                }
            }
        }

        companion object {
            fun install(
                impl: ZchatPreferencesImpl,
                files: Map<String, MemorySharedPreferences>,
            ): CountingPrefs {
                val counting = CountingPrefs(files.getValue("e2ePrefs"))
                ZchatPreferencesImpl::class.java
                    .getDeclaredField("e2ePrefs")
                    .apply { isAccessible = true }
                    .set(impl, counting)
                return counting
            }
        }
    }

    private companion object {
        const val PEER = "usyntheticzc545offerstore001"
        const val OTHER = "usyntheticzc545offerstore002"
        const val KEY_A = "c3ludGhldGljLXJlZnVzZWQta2V5LUE="
        const val KEY_B = "c3ludGhldGljLXJlZnVzZWQta2V5LUI="
    }
}
