package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R1 (ZC-112) — an automatic setup message has a record of its attempt before the SDK is asked, and its
 * transaction ids before the first submit.
 *
 * Today the key exchange, the key reply, the relay-identity message and an owed key exchange leave only a marker that
 * says "sent". Nothing records which transaction carried them, so nothing can later tell from the chain whether they
 * went out.
 *
 * Wanted: when the submit is reached, exactly one LIVE entry of that kind and key exists; after it the entry carries the
 * stored transaction's id and expiry. A refusal before anything was stored leaves no entry. A second attempt of the
 * same key while the first is unresolved builds nothing.
 *
 * Every address, key and transaction id is synthetic.
 */
class AutomaticSendRecordsItsIdsBeforeSubmitTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun LedgerWorld.recordStateAtSubmit(kind: String): () -> String? {
        var seen: String? = null
        seam.outcome = { _, ids ->
            seen = ledger.one(kind)?.let { it.state + ":" + it.txIds.size }
            SubmitResult.Success(ids)
        }
        return { seen }
    }

    private fun LedgerWorld.assertRecorded(
        kind: String,
        identity: String?,
        atSubmit: String?,
    ) {
        assertEquals(
            "when the $kind's submit was reached there was no LIVE entry carrying its stored transaction's id: the " +
                "attempt would reach the network with no durable record of what carried it",
            "LIVE:1",
            atSubmit,
        )
        val entry = ledger.one(kind)!!
        assertEquals(peer, entry.subject)
        if (identity != null) assertEquals(identity, entry.identity)
        assertEquals("the stored transaction's id", listOf(seam.stored.last()), entry.txIds)
        assertEquals("and its expiry", mapOf(seam.stored.last() to seam.expiry), entry.expiries)
        assertEquals("the account that made it", accountHash(), entry.accountHash)
    }

    @Test
    fun `the bootstrap key exchange is recorded with its ids before the submit`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val atSubmit = world.recordStateAtSubmit("KEX")

            world.chatOpen(vm)

            assertEquals("fixture sanity: one KEX was paid", 1, world.seam.submits.get())
            world.assertRecorded("KEX", world.ourKeys.publicKey, atSubmit())
        }

    @Test
    fun `an owed key exchange is recorded under the same key as every key exchange of the contact`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.prefs.kexOwed += world.peer
            val atSubmit = world.recordStateAtSubmit("KEX")

            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture sanity: the owed KEX was paid", 1, world.seam.submits.get())
            world.assertRecorded("KEX", world.ourKeys.publicKey, atSubmit())
        }

    @Test
    fun `the key reply is recorded with its ids before the submit`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val theirs = world.asResponder()
            val atSubmit = world.recordStateAtSubmit("KEXACK")

            vm.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture sanity: one key reply was paid", 1, world.seam.submits.get())
            world.assertRecorded("KEXACK", theirs, atSubmit())
        }

    @Test
    fun `the on-chain relay-identity message is recorded with its ids before the submit`() =
        runBlocking {
            val world = LedgerWorld(exchangeComplete = true)
            val vm = world.openVm()
            val atSubmit = world.recordStateAtSubmit("ZBOOT")

            world.sendZboot(vm)

            assertEquals("fixture sanity: one ZBOOT was paid", 1, world.seam.submits.get())
            world.assertRecorded("ZBOOT", null, atSubmit())
        }

    @Test
    fun `a refusal before anything was stored leaves no entry, and a second attempt of an unresolved key builds nothing`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.seam.failNextProposalWith = IllegalStateException("synthetic: refused before anything was stored")
            world.chatOpen(vm)
            assertEquals("fixture sanity: nothing was stored", 0, world.seam.stored.size)
            assertTrue("an attempt that provably sent nothing leaves no entry: ${world.ledger.all()}", world.ledger.all().isEmpty())

            world.storedNeverMined()
            world.chatOpen(vm)
            assertEquals("fixture sanity: the second open paid the KEX", 1, world.seam.submits.get())
            assertEquals("one entry", 1, world.ledger.of("KEX").size)
            world.agePaidRecords(minutes = 61)
            world.chatOpen(vm, force = true)
            assertEquals("a second attempt while the first is unresolved builds no proposal", 2, world.seam.proposals.get())
            assertEquals("still one entry, the first attempt's", 1, world.ledger.of("KEX").size)
        }
}
