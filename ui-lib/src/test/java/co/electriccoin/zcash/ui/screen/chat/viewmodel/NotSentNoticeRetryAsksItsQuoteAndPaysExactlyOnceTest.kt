package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R3 (ZC-112) — the way out of a key exchange that never reached the chain is a Retry that asks its price first
 * and pays exactly once.
 *
 * Today there is no Retry: the user's only tap is Reconnect, which pays under the standing consent with no price.
 *
 * Wanted, from the "Key exchange not sent" notice: Retry raises the paid question for ONE on-chain message. Before
 * Confirm nothing is built, stamped or written. Cancel pays nothing and the notice stays. Confirm pays exactly one
 * KEX, whose ids are recorded before the submit in the one write that replaces the failed entry (the old ids kept as
 * superseded). The quote stands only for the failed attempt it was shown for. A Retry that is refused leaves that
 * failed attempt, and the notice, exactly as they were. Reconnect opens the same question.
 *
 * Every address, key and transaction id is synthetic.
 */
class NotSentNoticeRetryAsksItsQuoteAndPaysExactlyOnceTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `Retry asks for one on-chain message, and before Confirm nothing is built or written`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            val ledgerWrites = world.ledger.writes().size
            val mark = world.prefs.mark()

            assertTrue("the notice has a Retry", world.tapRetry(vm, offer))
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Retry asks its price first", "HANDSHAKE_RETRY", world.question(vm))
            assertEquals("no proposal before Confirm", 1, world.seam.proposals.get())
            assertEquals("no ledger write before Confirm", ledgerWrites, world.ledger.writes().size)
            assertEquals("no store write before Confirm", emptyList<String>(), world.prefs.writesSince(mark).map { it.name })

            vm.dismissPaidAction()
            HandshakeHarness.awaitIdle(vm)
            assertEquals("Cancel pays nothing", 1, world.seam.proposals.get())
            assertEquals("the notice stays after Cancel", offer, world.notice(vm)[world.peer])
        }

    @Test
    fun `Confirm pays exactly one key exchange, in one commit that replaces the failed entry`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            val failed = world.ledger.one("KEX")!!
            val writesBefore = world.ledger.writes().size
            var stateAtIdsCommit: String? = null
            var noticeAtIdsCommit: Map<String, Long>? = null
            world.ledger.beforeMethod("recordStored") {
                stateAtIdsCommit = world.ledger.one("KEX")?.state
                noticeAtIdsCommit = world.notice(vm)
            }
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            vm.confirmPaidAction()
            // A double tap on Confirm.
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("exactly one new proposal", 2, world.seam.proposals.get())
            assertEquals("exactly one new submit", 2, world.seam.submits.get())
            assertEquals("the failed entry was untouched until the ids commit", "NOT_SENT", stateAtIdsCommit)
            assertEquals("the notice is left out while the Retry runs", emptyMap<String, Long>(), noticeAtIdsCommit)
            val now = world.ledger.one("KEX")!!
            assertEquals("LIVE", now.state)
            assertEquals("the new transaction's id is recorded", listOf(world.seam.stored[1]), now.txIds)
            assertEquals("the failed attempt's id is kept as superseded", failed.txIds, now.superseded)
            assertEquals(
                "one ledger write between the confirm and the submit: the ids commit that replaces the failed entry",
                listOf("recordStored"),
                world.ledger.writes().drop(writesBefore),
            )
            assertTrue("no notice after the Retry went out", world.notice(vm).isEmpty())
            assertEquals("the bootstrap marker is as it was", true, world.base.ownBootSent[world.peer])

            // Another screen's automatic trigger after it: the new attempt is unresolved, so nothing more is paid.
            val list = world.openVm()
            PaymentHarness.setField(list, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            world.chatOpen(list, force = true)
            assertEquals("still one Retry payment", 2, world.seam.submits.get())
        }

    @Test
    fun `Retry pays although the paid record of the failed attempt is still on disk`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            assertFalse("fixture sanity: the failed attempt's paid record is on disk", world.base.paidRecords.isEmpty())
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals(
                "the paid record of the attempt that never reached the chain made the confirmed Retry read \"already " +
                    "paid\": the user confirmed a price and nothing was sent",
                2,
                world.seam.submits.get(),
            )
        }

    @Test
    fun `Reconnect with the notice up opens the same question and pays nothing under the standing consent`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            world.kexProvenNotSent(vm)
            world.agePaidRecords(minutes = 61)

            vm.reSendOurIdentity(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Reconnect asks the Retry's price", "HANDSHAKE_RETRY", world.question(vm))
            assertEquals("nothing is paid before the answer", 1, world.seam.proposals.get())
        }

    @Test
    fun `the quote stands only for the failed attempt it was shown for`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val offer = world.kexProvenNotSent(vm)
            val failed = world.ledger.one("KEX")!!

            // Between the tap and Confirm the failed attempt is replaced by another one (a new marker).
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            world.ledger.put(failed.with("startedAtMillis" to failed.startedAt + 5))
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)
            assertEquals("a quote shown for one failed attempt never pays for another", 1, world.seam.proposals.get())

            // A stale notice (the marker of an attempt that is gone) asks nothing.
            assertTrue(world.tapRetry(vm, offer))
            HandshakeHarness.awaitIdle(vm)
            assertNull("a Retry for an attempt that is gone asks nothing", world.question(vm))
            assertEquals(1, world.seam.proposals.get())
        }

    @Test
    fun `a refused Retry leaves the failed attempt and the notice exactly as they were`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            val failed = world.ledger.one("KEX")!!
            world.seam.failNextProposalWith = IllegalStateException("synthetic: refused before anything was stored")

            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture sanity: the Retry reached the wallet once", 2, world.seam.proposals.get())
            assertEquals("nothing was submitted", 1, world.seam.submits.get())
            val now = world.ledger.one("KEX")!!
            assertEquals("the failed entry is unchanged", "NOT_SENT", now.state)
            assertEquals(failed.startedAt, now.startedAt)
            assertEquals(failed.txIds, now.txIds)
            assertTrue("no Retry is left running", LedgerProcess.retryInFlight().isEmpty())
            assertEquals("the notice is back", offer, world.notice(vm)[world.peer])
            assertEquals("no marker is re-armed by a failed Retry", true, world.base.ownBootSent[world.peer])
        }
}
