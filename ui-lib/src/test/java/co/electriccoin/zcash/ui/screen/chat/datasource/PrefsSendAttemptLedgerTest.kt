package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.common.sendattempt.AttemptAliveException
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEntry
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptTag
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112): the stored ledger over a real preferences file (in memory): every field survives the round trip, the
 * ledger's lock is held only inside a ledger method, and the ids commit is a compare-and-set on the entry it rests on.
 * Where the entries live, a failed commit, the wipe and the wallet scope are pinned by
 * LedgerEntriesAreStoredEncryptedAndNoPlainFileHoldsThemTest.
 */
class PrefsSendAttemptLedgerTest {
    private val file = MemorySharedPreferences()
    private val ledger = PrefsSendAttemptLedger { file }
    private val tag =
        SendAttemptTag(
            SendAttemptKind.GROUP_ACCEPT,
            "synthetic-group",
            "synthetic-memo-hash",
            payload = "synthetic payload",
            reason = "reset",
        )

    @Test
    fun `every field of an entry survives the round trip`() {
        val full =
            SendAttemptEntry(
                kind = SendAttemptKind.KEY_GRANT,
                subject = "synthetic-group|usyntheticprefsledgerpeer001",
                identity = "epoch:3",
                startedAtMillis = 1_234L,
                attemptHeight = 3_000_000L,
                processId = "process",
                accountHash = "0123456789abcdef",
                txIds = listOf("a", "b"),
                expiries = mapOf("a" to 3_000_040L, "b" to 3_000_041L),
                releaseAnchorHeight = 3_000_002L,
                supersededTxIds = listOf("z"),
                provenNotSentAtHeight = null,
                state = SendAttemptState.MINED,
                minedHeight = 3_000_010L,
                idsBeforeSubmit = true,
                payload = "payload with \"quotes\" and | bars",
                reason = "reconnect",
            )
        assertEquals(full, PrefsSendAttemptLedger.decode(PrefsSendAttemptLedger.encode(full)))
        val bare =
            SendAttemptEntry(
                SendAttemptKind.KEX,
                "p",
                "k",
                5L,
                state = SendAttemptState.NOT_SENT,
                provenNotSentAtHeight = 9L,
                idsBeforeSubmit = false,
            )
        assertEquals(bare, PrefsSendAttemptLedger.decode(PrefsSendAttemptLedger.encode(bare)))

        assertTrue(ledger.writeAhead(full))
        assertEquals(full, ledger.get(full.key))
        assertEquals(listOf(full), ledger.all())
        assertEquals(setOf(PrefsSendAttemptLedger.PREFIX + full.key), file.snapshot().keys)
    }

    @Test
    fun `the ids commit is a compare-and-set, and the lock is held only inside a ledger method`() {
        assertFalse(ledger.isLockHeld())
        val claim = ledger.begin(tag, 100L, 3_000_000L, "process", "account", null)
        assertThrows(AttemptAliveException::class.java) { ledger.begin(tag, 101L, null, "process", "account", null) }
        ledger.recordStored(claim, listOf("a"), mapOf("a" to 3_000_040L))
        ledger.finish(claim)
        var heldInside = false
        assertTrue(
            ledger.markNotSent(tag.key, 100L, 3_000_060L) {
                heldInside = ledger.isLockHeld()
                true
            },
        )
        assertTrue("a predicate runs inside the ledger's critical section", heldInside)
        assertFalse(ledger.isLockHeld())
        assertEquals(100L, ledger.offerStartedAt(tag.key))

        val retry = ledger.begin(tag.copy(retryOf = 100L), 200L, 3_000_070L, "process", "account", seen = 100L)
        ledger.recordStored(retry, listOf("b"), emptyMap())
        val stored = PrefsSendAttemptLedger(prefs = { file }).get(tag.key)!!
        assertEquals("a second ledger over the same file reads the same entry", listOf("b"), stored.txIds)
        assertEquals(listOf("a"), stored.supersededTxIds)
        assertEquals("the payload is kept across the replace", "synthetic payload", stored.payload)
        assertNull(ledger.offerStartedAt(tag.key))
    }

    @Test
    fun `a record that cannot be decoded is an unreadable key, never no entry`() {
        file.edit().putString(PrefsSendAttemptLedger.PREFIX + tag.key, "not json").commit()

        assertTrue("the gate cannot read it", runCatching { ledger.gate(tag.key, null) }.isFailure)
        assertTrue("and nothing is begun over it", runCatching { ledger.begin(tag, 100L, null, "process", "account", null) }.isFailure)
        assertTrue("the pass skips what it cannot read", ledger.all().isEmpty())
    }
}
