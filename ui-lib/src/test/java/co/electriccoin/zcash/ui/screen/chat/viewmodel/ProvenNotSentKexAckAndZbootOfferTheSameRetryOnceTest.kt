package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R4 (ZC-112) — a key reply (KEXACK) and a relay-identity message (ZBOOT) that never reached the chain get the same
 * notice and the same quoted Retry as a key exchange.
 *
 * Today their markers say "sent" for good: the in-memory "acked" map and `sentKexAckPubkey` stop every later key reply,
 * and `sentNostrBootPubkey` stops every later ZBOOT.
 *
 * Wanted: after the proof each shows the notice; Retry asks one on-chain message and pays once. The key reply's Retry is
 * not stopped by "already acked" and uses no automatic budget. The ZBOOT's Retry is paid on its own quote: it neither
 * uses up the per-contact allowance nor puts it back as owed. A failed Retry re-arms no marker. A Retry while the
 * history is not read pays nothing and says so. A ZBOOT whose identity the same pass reads as mined is delivered: no
 * notice, no Retry.
 *
 * Every address, key and transaction id is synthetic.
 */
class ProvenNotSentKexAckAndZbootOfferTheSameRetryOnceTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a key reply that never reached the chain shows the notice and its Retry pays exactly once`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexAckProvenNotSent(vm)
            val marker = world.prefs.sentKexAck[world.peer]
            val budget = world.prefs.budgetUsed.get()
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            // A plain automatic retry while the failed entry stands pays nothing.
            vm.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals(1, world.seam.proposals.get())

            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("HANDSHAKE_RETRY", world.question(vm))
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("the Retry pays one key reply (\"already acked\" does not stop it)", 2, world.seam.submits.get())
            assertEquals("the marker is as it was", marker, world.prefs.sentKexAck[world.peer])
            assertEquals("no automatic budget is used", budget, world.prefs.budgetUsed.get())
            assertEquals("LIVE", world.ledger.one("KEXACK")?.state)
            assertTrue("no notice after it went out", world.notice(vm).isEmpty())
        }

    @Test
    fun `a failed key-reply Retry re-arms nothing, and the offer goes once the Tunnel handshake completed`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val offer = world.kexAckProvenNotSent(vm)
            val marker = world.prefs.sentKexAck[world.peer]
            world.seam.failNextProposalWith = IllegalStateException("synthetic: refused before anything was stored")

            world.retryAndConfirm(vm, offer)

            assertEquals("nothing was submitted", 1, world.seam.submits.get())
            assertEquals("the marker is not cleared by a failed Retry", marker, world.prefs.sentKexAck[world.peer])
            assertEquals("the notice is back", offer, world.notice(vm)[world.peer])

            // The contact's relay key arrived: the Tunnel handshake is complete, nothing is left to retry.
            world.prefs.peerNostr[world.peer] = "ab".repeat(32)
            world.pass(vm, world.wallet.scanned + 1)
            assertTrue("no notice once the handshake completed", world.notice(vm).isEmpty())
        }

    @Test
    fun `a relay-identity message that never reached the chain is retried on its own quote, not on the allowance`() =
        runBlocking {
            val world = LedgerWorld(exchangeComplete = true)
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.zbootProvenNotSent(vm)
            val marker = world.base.sentNostrBoot[world.peer]

            // Every automatic trigger while the failed entry stands pays nothing.
            world.sendZboot(vm)
            world.chatOpen(vm)
            assertEquals(1, world.seam.proposals.get())

            // A confirmed question leaves an allowance for this contact.
            world.prefs.zbootQuoted += world.peer

            // A Retry that fails: nothing is re-armed and the allowance is neither used nor put back as owed.
            world.seam.failNextProposalWith = IllegalStateException("synthetic: refused before anything was stored")
            val mark = world.prefs.mark()
            world.retryAndConfirm(vm, offer)
            assertEquals("nothing was submitted", 1, world.seam.submits.get())
            assertEquals("the delivered-identity marker is not cleared by a failed Retry", marker, world.base.sentNostrBoot[world.peer])
            val writes = world.prefs.writesSince(mark)
            assertTrue("the allowance is not put back as owed", writes.none { it.name == "restoreQuotedZbootAsOwed" })
            assertTrue("the allowance is not used up by a Retry", world.peer in world.prefs.zbootQuoted)
            assertEquals("the notice is back", offer, world.notice(vm)[world.peer])

            // A Retry that goes out pays exactly once.
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.retryAndConfirm(vm, offer)
            assertEquals("the Retry pays one ZBOOT", 2, world.seam.submits.get())
            assertEquals("LIVE", world.ledger.one("ZBOOT")?.state)
            assertTrue("the ZBOOT is out: nothing is left owed", world.prefs.zbootOwed.isEmpty())
            assertTrue("and no allowance is left for a ZBOOT that needs no payment any more", world.peer !in world.prefs.zbootQuoted)
        }

    @Test
    fun `a Retry while the history is not read pays nothing and shows the Reconnect error`() =
        runBlocking {
            val world = LedgerWorld(exchangeComplete = true)
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.zbootProvenNotSent(vm)
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            AutoSpendVm.setHistoryRead(false)

            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a deferred Retry builds nothing", 1, world.seam.proposals.get())
            assertEquals("the failed entry is left", "NOT_SENT", world.ledger.one("ZBOOT")?.state)
            assertEquals(
                co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error(
                    co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.RECONNECT_FAILED,
                ),
                HandshakeHarness.sendMessageState(vm),
            )
        }

    @Test
    fun `a relay identity the same pass reads as mined is delivered - no notice and no Retry`() =
        runBlocking {
            val world = LedgerWorld(exchangeComplete = true)
            val vm = world.openVm()
            val offer = world.zbootProvenNotSent(vm)
            val failed = world.ledger.one("ZBOOT")!!
            // Another transaction of ours carrying this same identity to this contact is mined (another phone of this
            // wallet, or an earlier install): the pass reads it.
            val memo =
                co.electriccoin.zcash.ui.screen.chat.model
                    .ZBootMessage(
                        world.base.convIdFor(world.peer),
                        failed.identity,
                        "wss://relay.synthetic.invalid",
                        "00".repeat(64),
                        epoch = 0L,
                    ).serialize()
            world.wallet.txs =
                world.wallet.txs + SendAttemptHarness.ChainTx(8_501, world.peer, memo, world.wallet.scanned, null, amount = 1_000L)

            world.pass(vm, world.wallet.scanned + 1)

            assertTrue("delivered by another route: no notice", world.notice(vm).isEmpty())
            assertTrue("and its failed entry is gone: ${world.ledger.all()}", world.ledger.of("ZBOOT").isEmpty())
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("a stale Retry asks nothing", null, world.question(vm))
            assertEquals(1, world.seam.proposals.get())
        }
}
