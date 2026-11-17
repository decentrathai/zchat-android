package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-785 (c) (review of 12 Oct, finding 3c; verification of the same day, section 3) — a ZBOOT sender whose action was
 * replaced by a confirmed Reset or key-change reconnect still undoes what it wrote itself when its attempt provably
 * sent nothing: its paid record, and the delivered-identity marker.
 *
 * At 9dc8d5fbf both undos run only while the sender still owns the contact's ZBOOT work, so a superseded sender skips
 * them whole:
 * - its paid record stays, and the Reset's own ZBOOT then reads "paid within the hour", sends nothing and withdraws
 *   the allowance the Reset's question had just written (quoted 2, paid 1, the contact never gets our relay identity
 *   under the new key until Reconnect);
 * - its marker stays, when the reconnect could not fire its ZBOOT because the old sender held the in-flight claim
 *   (fireReconnectZbootIfDue returns): no block retry lists the contact any more, so the reconnect's quoted ZBOOT is
 *   never sent, and the next chat open withdraws its allowance as "already delivered".
 *
 * Money: every case counts proposals against submits on the real use case and repository. The confirmed action pays
 * exactly what its question quoted (one KEX, one ZBOOT); the superseded sender itself never pays; and after a submit
 * with an unknown outcome nothing is undone and nothing is paid on top (control).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ASupersededZbootSenderUndoesItsOwnStampAndMarkerTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(ownHistoryReadBefore)
        Dispatchers.resetMain()
        ZbootSetup.resetProcess()
    }

    @Test
    fun `a Reset confirmed while a ZBOOT is being built still pays its own ZBOOT once`() =
        runBlocking {
            val chat = tunnelChat()
            val vm = chat.viewModel()
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val seam =
                HookedSetupSeam(PEER).apply {
                    beforeProposal = { kind, n ->
                        if (kind == "ZBOOT" && n == 1) {
                            // The old sender has stamped its paid record; its proposal then finds the funds still confirming.
                            parked.complete(Unit)
                            release.await()
                            throw HandshakeHarness.stillConfirming()
                        }
                    }
                }
            ZbootSetup.installSeam(vm, seam)
            val current = ZbootSetup.currentIdentity(vm)
            try {
                chat.tick(vm)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { parked.await() }
                assertTrue(
                    "fixture sanity: the old sender stamped its paid record before its proposal",
                    HandshakePaidRecord.names(chat.paidRecords[SLOT], current),
                )

                vm.resetSecureSession(PEER)
                awaitTrue { HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation }
                chat.reset.assertAskedFirst(vm)
                vm.confirmPaidAction()
                awaitTrue { seam.submittedOf("KEX") == 1 }
            } finally {
                release.complete(Unit)
            }
            // The old sender's attempt provably sent nothing. It waits for a block and then stops: its key was replaced.
            awaitTrue { chat.heights.isWaiting() }
            chat.heights.advance()
            awaitIdle(vm)
            assertEquals("fixture sanity: the superseded sender paid nothing", 0, seam.submittedOf("ZBOOT"))

            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc785-reset-key-reply")
            awaitSettled(vm, seam)

            assertEquals(
                "the Reset's ZBOOT was dropped as 'paid within the hour' on the stamp of a sender that sent nothing",
                1,
                seam.submittedOf("ZBOOT"),
            )
            assertEquals("the Reset's KEX is paid once", 1, seam.submittedOf("KEX"))
            assertEquals("the confirmed Reset pays exactly the two transactions it quoted", 2, seam.submitted.size)
            assertFalse("the paid ZBOOT must use up its allowance", chat.store.isZbootQuoted(PEER))
            assertFalse(PEER in chat.zbootOwed)

            repeat(3) {
                chat.tick(vm)
                awaitIdle(vm)
            }
            assertEquals("a later block paid on top of the Reset's quote", 2, seam.submitted.size)
            assertEquals(1, seam.proposalsOf("KEX"))
            assertEquals("only the refused proposal and the Reset's own ZBOOT may be built", 2, seam.proposalsOf("ZBOOT"))
            ZbootSetup.kill(vm)
        }

    @Test
    fun `a key-change reconnect confirmed while an old ZBOOT waits for a block still sends its quoted ZBOOT once`() =
        runBlocking {
            val chat =
                KeyChangeReconnectChat(
                    PEER,
                    consent = true,
                    mode = ConversationMode.TUNNEL,
                    failFirstZbootWithStillConfirming = true,
                    exchangeComplete = true,
                )
            chat.owed.store.setSentNostrBootPubkey(PEER, null)
            chat.owed.tunnelPeers += PEER
            // ZC-739: no sender starts while the contact's new key waits, so the old ZBOOT started before the warning rose.
            chat.keyChanged = false
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)

            chat.owed.tick(vm)
            awaitTrue { chat.owed.heights.isWaiting() }
            assertEquals("fixture sanity: the old sender's ZBOOT found the funds still confirming", 1, chat.seam.zbootProposals.get())
            assertEquals(
                "fixture sanity: the old sender's marker stays while it waits",
                current,
                chat.owed.store.getSentNostrBootPubkey(PEER),
            )
            assertTrue("fixture sanity: the old sender holds the in-flight claim", ZbootSetup.zbootInFlight(PEER))
            // The contact's new key arrives while the old sender waits for its block.
            chat.keyChanged = true

            vm.dismissE2EKeyChanged(PEER)
            awaitTrue { HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation }
            chat.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.seam.submittedOf("KEX") == 1 }
            awaitTrue { !chat.keyChanged }
            // fireReconnectZbootIfDue returned: a ZBOOT to this contact is in flight.
            assertTrue("fixture sanity: the old sender is still in flight after the reconnect's KEX", ZbootSetup.zbootInFlight(PEER))
            assertTrue("fixture sanity: the reconnect's ZBOOT allowance stands", chat.owed.store.isZbootQuoted(PEER))
            assertEquals(0, chat.seam.submittedOf("ZBOOT"))

            repeat(4) {
                chat.owed.tick(vm)
                awaitIdle(vm)
            }

            assertEquals(
                "the reconnect's quoted ZBOOT was never sent: the superseded sender left its marker behind",
                1,
                chat.seam.submittedOf("ZBOOT"),
            )
            assertEquals("the reconnect's KEX is paid once", 1, chat.seam.submittedOf("KEX"))
            assertEquals("the confirmed reconnect pays exactly the two transactions it quoted", 2, chat.seam.submitted.size)
            assertFalse("the paid ZBOOT must use up its allowance", chat.owed.store.isZbootQuoted(PEER))
            assertFalse(PEER in chat.owed.zbootOwed)
            assertEquals(current, chat.owed.store.getSentNostrBootPubkey(PEER))

            repeat(3) {
                chat.owed.tick(vm)
                awaitIdle(vm)
            }
            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)
            assertEquals("a later block or chat open paid on top of the reconnect's quote", 2, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - a superseded sender whose submit has an unknown outcome keeps its stamp and nothing is paid on top`() =
        runBlocking {
            val chat = tunnelChat()
            val vm = chat.viewModel()
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val seam =
                HookedSetupSeam(PEER).apply {
                    submitOutcome = { kind, n ->
                        if (kind == "ZBOOT" && n == 1) {
                            // The old sender's ZBOOT is at the SDK; it is stored and then reported as refused.
                            parked.complete(Unit)
                            release.await()
                            HookedSetupSeam.storedThenRejected()
                        } else {
                            null
                        }
                    }
                }
            ZbootSetup.installSeam(vm, seam)
            val current = ZbootSetup.currentIdentity(vm)
            try {
                chat.tick(vm)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { parked.await() }
                vm.resetSecureSession(PEER)
                awaitTrue { HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation }
                vm.confirmPaidAction()
                awaitTrue { seam.submittedOf("KEX") == 1 }
            } finally {
                release.complete(Unit)
            }
            awaitIdle(vm)
            assertTrue(
                "the paid record of a ZBOOT that may still be broadcast was withdrawn",
                HandshakePaidRecord.names(chat.paidRecords[SLOT], current),
            )

            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc785-unknown-key-reply")
            awaitSettled(vm, seam)
            repeat(3) {
                chat.tick(vm)
                awaitIdle(vm)
            }

            assertEquals(
                "a second ZBOOT of the same identity was paid inside the hour of one that may be broadcast",
                1,
                seam.submittedOf("ZBOOT"),
            )
            assertEquals(1, seam.proposalsOf("ZBOOT"))
            assertEquals(1, seam.submittedOf("KEX"))
            assertEquals(2, seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - a sender nobody replaced withdraws its stamp, waits for a block and pays once`() =
        runBlocking {
            val chat = tunnelChat()
            val vm = chat.viewModel()
            val seam =
                HookedSetupSeam(PEER).apply {
                    beforeProposal = { kind, n -> if (kind == "ZBOOT" && n == 1) throw HandshakeHarness.stillConfirming() }
                }
            ZbootSetup.installSeam(vm, seam)
            val current = ZbootSetup.currentIdentity(vm)

            chat.tick(vm)
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("the stamp of an attempt that sent nothing must be withdrawn", null, chat.paidRecords[SLOT])
            assertEquals(0, seam.submitted.size)

            chat.heights.advance()
            awaitIdle(vm)
            assertEquals(1, seam.submittedOf("ZBOOT"))
            assertEquals(2, seam.proposalsOf("ZBOOT"))
            assertEquals(current, chat.store.getSentNostrBootPubkey(PEER))
            assertTrue(HandshakePaidRecord.names(chat.paidRecords[SLOT], current))

            chat.tick(vm)
            awaitIdle(vm)
            assertEquals(1, seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    /** Join the sends the contact's key reply started until no new proposal appears. */
    private suspend fun awaitSettled(
        vm: ChatViewModel,
        seam: HookedSetupSeam,
    ) {
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            var seen = -1
            while (seen != seam.proposalsOf("ZBOOT") + seam.submitted.size) {
                seen = seam.proposalsOf("ZBOOT") + seam.submitted.size
                awaitIdle(vm)
                awaitIdle(vm)
            }
        }
    }

    /** A Tunnel chat with keys both ways, no relay key, the standing consent given, our relay identity never delivered. */
    private fun tunnelChat(): ResetOwedChat =
        ResetOwedChat(PEER, consent = true, mode = ConversationMode.TUNNEL).also { chat ->
            chat.store.setSentNostrBootPubkey(PEER, null)
            chat.tunnelPeers += PEER
        }

    private companion object {
        const val PEER = "usyntheticzc785superseded001"
        val SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)
    }
}
