package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112): the pure rules of the send-attempt ledger, and the in-memory ledger that applies them: the gate's
 * answers, what each write turns an entry into, and what the history pass does with a chain verdict.
 */
class SendAttemptLedgerRulesTest {
    private val tag = SendAttemptTag(SendAttemptKind.KEX, "usyntheticrulespeer000000001", "synthetic-key")
    private val key = tag.key

    private fun ledgerWithLive(ids: List<String> = emptyList()): Pair<InMemorySendAttemptLedger, SendAttemptClaim> {
        val ledger = InMemorySendAttemptLedger()
        val claim = ledger.begin(tag, 100L, 3_000_000L, "process", "account", null)
        if (ids.isNotEmpty()) ledger.recordStored(claim, ids, ids.associateWith { 3_000_040L })
        return ledger to claim
    }

    private fun tx(
        id: String,
        mined: Long?,
        expiry: Long? = 3_000_040L,
    ) = OwnTransaction(
        id,
        isSent = true,
        minedHeight = mined,
        expiryHeight = expiry,
        valueZatoshi = 16_000L,
        recipients = null,
        readableText = null,
        blockTimeMillis = null,
    )

    @Test
    fun `the gate lets a first attempt through, and an entry only for the retry of exactly that failed state`() {
        val live = SendAttemptEntry(SendAttemptKind.KEX, "p", "k", startedAtMillis = 7L)
        val mined = live.copy(state = SendAttemptState.MINED, minedHeight = 10L)
        val notSent = live.copy(state = SendAttemptState.NOT_SENT, provenNotSentAtHeight = 20L)

        assertEquals(SendAttemptGate.Proceed(null), SendAttemptLedgerRules.gate(null, null))
        assertEquals("a retry whose entry is gone", SendAttemptGate.Pending, SendAttemptLedgerRules.gate(null, 7L))
        for (entry in listOf(live, mined, notSent)) {
            assertEquals("an automatic attempt over ${entry.state}", SendAttemptGate.Pending, SendAttemptLedgerRules.gate(entry, null))
        }
        assertEquals(SendAttemptGate.Pending, SendAttemptLedgerRules.gate(live, 7L))
        assertEquals(SendAttemptGate.Pending, SendAttemptLedgerRules.gate(mined, 7L))
        assertEquals("another failed state's quote", SendAttemptGate.Pending, SendAttemptLedgerRules.gate(notSent, 8L))
        assertEquals(SendAttemptGate.Proceed(7L), SendAttemptLedgerRules.gate(notSent, 7L))
    }

    @Test
    fun `begin writes a live entry for a first attempt and refuses a second or an accountless one`() {
        val (ledger, _) = ledgerWithLive()
        val entry = ledger.get(key)!!
        assertEquals(SendAttemptState.LIVE, entry.state)
        assertTrue(entry.idsBeforeSubmit)
        assertEquals("account", entry.accountHash)
        assertTrue(ledger.attemptRunning(key))

        assertThrows(AttemptAliveException::class.java) { ledger.begin(tag, 101L, null, "process", "account", null) }
        assertThrows(LedgerAccountUnverifiedException::class.java) {
            InMemorySendAttemptLedger().begin(tag, 100L, null, "process", null, null)
        }
    }

    @Test
    fun `begin over a failed entry writes nothing, and the ids commit replaces it in one write`() {
        val (ledger, first) = ledgerWithLive(listOf("a"))
        ledger.finish(first)
        assertTrue(ledger.markNotSent(key, 100L, 3_000_060L) { true })
        val failed = ledger.get(key)!!

        val claim = ledger.begin(tag.copy(retryOf = 100L), 200L, 3_000_070L, "process-2", "account", seen = 100L)
        assertEquals("begin over NOT_SENT writes nothing", failed, ledger.get(key))

        ledger.recordStored(claim, listOf("b"), mapOf("b" to 3_000_110L))
        val now = ledger.get(key)!!
        assertEquals(SendAttemptState.LIVE, now.state)
        assertEquals(200L, now.startedAtMillis)
        assertEquals(listOf("b"), now.txIds)
        assertEquals(listOf("a"), now.supersededTxIds)
        assertEquals("process-2", now.processId)
        assertNull(now.provenNotSentAtHeight)
    }

    @Test
    fun `a retry whose failed entry is gone or replaced commits nothing`() {
        val (ledger, first) = ledgerWithLive(listOf("a"))
        ledger.finish(first)
        ledger.markNotSent(key, 100L, 3_000_060L) { true }
        val claim = ledger.begin(tag.copy(retryOf = 100L), 200L, null, "process", "account", seen = 100L)
        // Another screen's Retry replaced it first.
        val other = ledger.begin(tag.copy(retryOf = 100L), 300L, null, "process", "account", seen = 100L)
        ledger.recordStored(other, listOf("c"), emptyMap())

        assertThrows(LedgerWriteFailedException::class.java) { ledger.recordStored(claim, listOf("b"), emptyMap()) }
        assertEquals("the winner's entry is untouched", listOf("c"), ledger.get(key)!!.txIds)
        assertThrows(AttemptAliveException::class.java) {
            ledger.begin(tag.copy(retryOf = 100L), 400L, null, "process", "account", seen = 100L)
        }
    }

    @Test
    fun `discard removes only a fresh entry that holds no ids`() {
        val (fresh, claim) = ledgerWithLive()
        assertFalse("another attempt's marker", fresh.discard(key, 999L))
        assertTrue(fresh.discard(key, claim.startedAt))
        assertNull(fresh.get(key))

        val (withIds, withIdsClaim) = ledgerWithLive(listOf("a"))
        assertFalse("an entry that holds ids is never discarded", withIds.discard(key, withIdsClaim.startedAt))
        withIds.markNotSent(key, 100L, 3_000_060L) { true }
        assertFalse("a failed entry is never discarded", withIds.discard(key, 100L))
        assertTrue("a delivery by another route removes a failed entry", withIds.settleDelivered(key))
    }

    @Test
    fun `the running count goes up at begin and down at finish`() {
        val (ledger, claim) = ledgerWithLive()
        assertTrue(ledger.attemptRunning(key))
        ledger.finish(claim)
        assertFalse(ledger.attemptRunning(key))
        ledger.finish(claim)
        assertFalse("never below zero", ledger.attemptRunning(key))
    }

    @Test
    fun `a reconciler write lands only while its predicate holds, and a mined entry always carries its height`() {
        val (ledger, claim) = ledgerWithLive(listOf("a"))
        ledger.finish(claim)
        assertFalse(ledger.markNotSent(key, 100L, 5L) { false })
        assertFalse("another attempt's marker", ledger.settleMined(key, 101L, 5L) { true })
        assertTrue(ledger.settleMined(key, 100L, 3_000_010L) { true })
        assertEquals(3_000_010L, ledger.get(key)!!.minedHeight)
        assertTrue("the highest height seen is kept", ledger.settleMined(key, 100L, 3_000_005L) { true })
        assertEquals(3_000_010L, ledger.get(key)!!.minedHeight)
        assertFalse("a mined entry is never marked not sent", ledger.markNotSent(key, 100L, 9L) { true })
        assertTrue(ledger.reopen(key, 100L) { true })
        val reopened = ledger.get(key)!!
        assertEquals(SendAttemptState.LIVE, reopened.state)
        assertNull(reopened.minedHeight)
        assertTrue(ledger.delete(key, 100L))
        assertTrue("deleting what is gone is done", ledger.delete(key, 100L))
    }

    @Test
    fun `a mined entry is kept until every id is reorg-deep and reopened when one is unmined or unlisted`() {
        val entry =
            SendAttemptEntry(
                SendAttemptKind.KEX,
                "p",
                "k",
                1L,
                txIds = listOf("a", "b"),
                state = SendAttemptState.MINED,
                minedHeight = 100L,
            )

        fun step(
            tip: Long,
            vararg txs: OwnTransaction,
        ) = SendAttemptLedgerRules.minedStep(entry, ChainView(tip, txs.toList()))

        assertEquals(SendAttemptLedgerRules.Step.Mined(108L), step(110L, tx("a", 100L), tx("b", 108L)))
        assertEquals(
            SendAttemptLedgerRules.Step.Mined(108L),
            step(108L + SendAttemptRules.REORG_MARGIN - 1, tx("a", 100L), tx("b", 108L)),
        )
        assertEquals(
            SendAttemptLedgerRules.Step.DeleteDeep,
            step(108L + SendAttemptRules.REORG_MARGIN, tx("a", 100L), tx("b", 108L)),
        )
        assertEquals("a reorg took one back", SendAttemptLedgerRules.Step.Reopen, step(110L, tx("a", 100L), tx("b", null)))
        assertEquals("one is no longer listed", SendAttemptLedgerRules.Step.Reopen, step(110L, tx("a", 100L)))
        assertEquals(
            "no tip: nothing is decided",
            SendAttemptLedgerRules.Step.Keep,
            SendAttemptLedgerRules.minedStep(entry, ChainView(null, emptyList())),
        )
    }

    @Test
    fun `a live entry settles as mined, is committed only on a complete proof, and is kept on every doubt`() {
        val entry = SendAttemptEntry(SendAttemptKind.KEX, "p", "k", 1L, txIds = listOf("a", "b"))
        val chain = ChainView(200L, listOf(tx("a", 100L), tx("b", 108L)))

        assertEquals(SendAttemptLedgerRules.Step.Mined(108L), SendAttemptLedgerRules.liveStep(entry, SendAttemptVerdict.Sent, false, chain))
        assertEquals(SendAttemptLedgerRules.Step.Keep, SendAttemptLedgerRules.liveStep(entry, SendAttemptVerdict.NotSent, false, chain))
        assertEquals(SendAttemptLedgerRules.Step.NotSent, SendAttemptLedgerRules.liveStep(entry, SendAttemptVerdict.NotSent, true, chain))
        for (verdict in listOf(
            SendAttemptVerdict.Partial,
            SendAttemptVerdict.MinedUnattributed,
            SendAttemptVerdict.Unknown,
            SendAttemptVerdict.Waiting(null, 200L),
        )) {
            assertEquals(
                "$verdict, even with a proof",
                SendAttemptLedgerRules.Step.Keep,
                SendAttemptLedgerRules.liveStep(entry, verdict, true, chain),
            )
        }
    }

    @Test
    fun `an entry is judged only when the wallet lists its ids, or when this build began it with none`() {
        val chain = ChainView(200L, listOf(tx("a", null)))
        val withIds = SendAttemptEntry(SendAttemptKind.KEX, "p", "k", 1L, accountHash = "account", txIds = listOf("a"))
        assertTrue(SendAttemptLedgerRules.judgeable(withIds, chain))
        assertFalse(
            "an id the wallet does not list is never proof",
            SendAttemptLedgerRules.judgeable(withIds.copy(txIds = listOf("a", "zz")), chain),
        )
        assertTrue(
            "a record with no account is judged by its ids",
            SendAttemptLedgerRules.judgeable(withIds.copy(accountHash = null), chain),
        )

        val noIds = withIds.copy(txIds = emptyList())
        assertTrue("the no-id rule of this build", SendAttemptLedgerRules.judgeable(noIds, chain))
        assertFalse("an earlier build's record with no ids", SendAttemptLedgerRules.judgeable(noIds.copy(idsBeforeSubmit = false), chain))
        assertFalse("no account", SendAttemptLedgerRules.judgeable(noIds.copy(accountHash = null), chain))
    }

    @Test
    fun `a no-id entry of this build is never stuck on a mined payment, and still waits for an unmined candidate`() {
        val entry =
            SendAttemptEntry(
                SendAttemptKind.KEX,
                "peer",
                "k",
                1L,
                attemptHeight = 100L,
                accountHash = "account",
                releaseAnchorHeight = 100L,
            )
        val mined = tx("m", 105L).copy(recipients = listOf("peer"))
        val unmined = tx("u", null, expiry = 150L).copy(recipients = listOf("peer"))

        fun verdict(
            tip: Long,
            vararg txs: OwnTransaction,
        ): SendAttemptVerdict {
            val chain = ChainView(tip, txs.toList())
            val view = ChainView(tip, txs.toList(), SendAttemptLedgerRules.claimedForNoIdEntry(entry, chain))
            return SendAttemptResolver.resolve(SendAttemptLedgerRules.attemptOf(entry, "peer", "this-process"), view)
        }

        val past = 100L + SendAttemptRules.window(100L, legacy = false)
        assertEquals("a mined unrelated payment is not its transaction", SendAttemptVerdict.NotSent, verdict(past, mined))
        assertTrue("an unmined candidate could be it", verdict(140L, mined, unmined) is SendAttemptVerdict.Waiting)
        assertEquals(SendAttemptVerdict.NotSent, verdict(maxOf(past, 150L + SendAttemptRules.REORG_MARGIN), mined, unmined))
        assertTrue(
            "an earlier build's record keeps the doubt",
            SendAttemptLedgerRules.claimedForNoIdEntry(entry.copy(idsBeforeSubmit = false), ChainView(past, listOf(mined))).isEmpty(),
        )
    }

    @Test
    fun `the note-only kinds are the only ones that are not fail-closed`() {
        assertEquals(
            setOf(SendAttemptKind.TIMER, SendAttemptKind.TIME_LOCK),
            SendAttemptKind.entries.filter { !it.failClosed }.toSet(),
        )
    }
}
