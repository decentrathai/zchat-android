package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-703, ZC-661 — an own mined ZBOOT of an earlier relay identity cannot discharge the CURRENT identity's owed ZBOOT.
 * Starts at the synthetic durable prestate of a confirmed quote that already provably sent nothing and gave up:
 * allowance and owed mark saved, delivered marker empty, Shielded mode, no standing consent or relay route. This test
 * does not claim to exercise rotation or the earlier quote/failure lifecycle. Every history case drives a complete
 * real conversion pass, then the real per-block retry/use case/repository settlement seam where its oracle requires.
 * Missing-identity coverage additionally drives the complete real history conversion; one unrelated complete pass
 * must not reopen payment for an own mined ZBOOT whose identity classification was deferred.
 * Same-identity history keeps the old restore rule: recorded as delivered, no owed retry, no second payment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AMinedZbootOfAnEarlierRelayIdentityDoesNotCancelTheOwedOneTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        // These fixtures already have their E2E state; this suite classifies only the relay-identity history.
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootWorkIfPresent()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(ownHistoryReadBefore)
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootWorkIfPresent()
    }

    @Test
    fun `an earlier mined relay identity leaves the current quoted ZBOOT owed and the next block pays it once`() =
        runBlocking {
            val chat = owedChat()
            val vm = chat.viewModel()
            val current = currentRelayIdentity(vm)
            assertNotEquals("fixture sanity: history must name a different identity", current.first, EARLIER_RELAY_KEY)
            val recorder = SendStateRecorder(vm)

            fullHistoryPass(vm, boot(EARLIER_RELAY_KEY))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

            assertTrue("history of an earlier identity cancelled the current quoted ZBOOT", PEER in chat.zbootOwed)
            assertTrue("history spent or withdrew the current identity's allowance", chat.store.isZbootQuoted(PEER))
            assertEquals(
                "the old restore rule still records earlier deliveries",
                EARLIER_RELAY_KEY,
                chat.store.getSentNostrBootPubkey(PEER),
            )
            assertEquals("history itself must never propose a payment", 0, chat.seam.zbootProposals.get())

            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("the next block must propose the owed ZBOOT once", 1, chat.seam.zbootProposals.get())
            assertEquals("the next block must submit the owed ZBOOT once", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the paid delivery must name our CURRENT relay identity", current.first, chat.store.getSentNostrBootPubkey(PEER))
            assertFalse("the payment did not consume its allowance", chat.store.isZbootQuoted(PEER))
            assertFalse("the owed mark outlived its allowance", PEER in chat.zbootOwed)

            chat.tick(vm)
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("another block paid the same quote twice", 1, chat.seam.zbootProposals.get())
            assertEquals("only one on-chain transaction was quoted in this prestate", 1, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("the owed retry asked for a standing consent")
            assertEquals("a one-off quote must not become standing consent", 0, chat.consentWrites.get())
            recorder.stop()
            killProcesses(vm)
        }

    @Test
    fun `a mined ZBOOT of the current identity is recorded delivered and clears the mark without paying`() =
        runBlocking {
            val chat = owedChat()
            val vm = chat.viewModel()
            val current = currentRelayIdentity(vm)

            fullHistoryPass(vm, boot(current.first))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

            assertEquals(current.first, chat.store.getSentNostrBootPubkey(PEER))
            assertFalse("a mined delivery of this identity leaves no owed ZBOOT", PEER in chat.zbootOwed)
            chat.tick(vm)
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("a mined delivery was paid again", 0, chat.seam.zbootProposals.get())
            assertEquals(0, chat.seam.submitted.size)
            killProcesses(vm)
        }

    @Test
    fun `an unmined ZBOOT proves no delivery and leaves the allowance and owed mark standing`() =
        runBlocking {
            val chat = owedChat()
            val vm = chat.viewModel()
            fullHistoryPass(vm, boot(currentRelayIdentity(vm).first), mined = false)
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

            assertEquals(null, chat.store.getSentNostrBootPubkey(PEER))
            assertTrue(chat.store.isZbootQuoted(PEER))
            assertTrue(PEER in chat.zbootOwed)
            assertEquals(0, chat.seam.zbootProposals.get())
            killProcesses(vm)
        }

    @Test
    fun `an unreadable current identity during a full pass cannot let an unrelated pass repay its mined ZBOOT`() =
        runBlocking {
            val chat = owedChat()
            val first = chat.viewModel()
            val current = currentRelayIdentity(first)
            val unavailable =
                object : PersistableWalletProvider by FixtureSeedWalletProvider {
                    override suspend fun requirePersistableWallet(): PersistableWallet =
                        error("synthetic wallet identity unavailable during this history pass")
                }
            AutoSpendVm.setField(first, "persistableWalletProvider", unavailable)
            // Own E2E history is already read; only this ZBOOT's relay-identity classification is undecidable.
            SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
            fullHistoryPass(first, boot(current.first))
            assertTrue("unreadable identity cannot discharge an unclassified owed quote", PEER in chat.zbootOwed)
            assertTrue(chat.store.isZbootQuoted(PEER))

            AutoSpendVm.setField(first, "persistableWalletProvider", FixtureSeedWalletProvider)
            val otherScreen = chat.viewModel()
            fullHistoryPass(otherScreen, memo = null)
            AutoSpendVm.setField(first, "synchronizerProvider", chat.heights.provider)
            AutoSpendVm.setField(otherScreen, "synchronizerProvider", chat.heights.provider)
            chat.tick(otherScreen)
            awaitIdle(otherScreen)
            assertEquals(
                "an unrelated complete pass reopened payment before the mined identity was classified",
                0,
                chat.seam.zbootProposals.get(),
            )

            fullHistoryPass(first, boot(current.first))
            AutoSpendVm.setField(first, "synchronizerProvider", chat.heights.provider)
            assertFalse("the recovered identity must let the later pass discharge the mined delivery", PEER in chat.zbootOwed)
            assertEquals(current.first, chat.store.getSentNostrBootPubkey(PEER))
            chat.tick(first)
            awaitIdle(first)
            assertEquals("the mined current identity was paid again after identity recovery", 0, chat.seam.zbootProposals.get())
            assertEquals(0, chat.seam.submitted.size)
            killProcesses(first, otherScreen)
        }

    @Test
    fun `an older then current mined ZBOOT in one complete history pass leaves no owed payment`() =
        runBlocking {
            val chat = owedChat()
            val vm = chat.viewModel()
            val current = currentRelayIdentity(vm)
            SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)

            fullHistoryPass(vm, listOf(boot(EARLIER_RELAY_KEY), boot(current.first)))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)

            assertFalse(
                "the later mined CURRENT identity must discharge the quote even after an earlier marker was restored",
                PEER in chat.zbootOwed,
            )
            assertEquals("the mined CURRENT identity must end as the delivered one", current.first, chat.store.getSentNostrBootPubkey(PEER))
            chat.tick(vm)
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("a later mined CURRENT identity in the same pass was paid again", 0, chat.seam.zbootProposals.get())
            assertEquals(0, chat.seam.submitted.size)
            killProcesses(vm)
        }

    @Test
    fun `a block between older and current mined rows cannot pay even if another screen completes a pass`() =
        runBlocking {
            val chat = owedChat()
            val first = chat.viewModel()
            val current = currentRelayIdentity(first)
            val otherScreen = chat.viewModel()
            val beforeCurrentRow = CompletableDeferred<Unit>()
            val releaseCurrentRow = CompletableDeferred<Unit>()
            SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
            val scan =
                launch {
                    fullHistoryPass(first, listOf(boot(EARLIER_RELAY_KEY), boot(current.first))) { index ->
                        if (index == 1) {
                            beforeCurrentRow.complete(Unit)
                            releaseCurrentRow.await()
                        }
                    }
                }
            try {
                withTimeout(HandshakeHarness.TIMEOUT_MS) { beforeCurrentRow.await() }
                fullHistoryPass(otherScreen, memo = null)
                AutoSpendVm.setField(otherScreen, "synchronizerProvider", chat.heights.provider)
                chat.tick(otherScreen)
                awaitIdle(otherScreen)
                assertEquals(
                    "a block paid before the complete history classified its later CURRENT identity",
                    0,
                    chat.seam.zbootProposals.get(),
                )
            } finally {
                releaseCurrentRow.complete(Unit)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { scan.join() }
            }
            assertFalse("a complete pass containing the mined CURRENT identity leaves no owed payment", PEER in chat.zbootOwed)
            AutoSpendVm.setField(first, "synchronizerProvider", chat.heights.provider)
            chat.tick(first)
            awaitIdle(first)
            assertEquals("the already mined CURRENT identity was paid after the pass finished", 0, chat.seam.zbootProposals.get())
            killProcesses(first, otherScreen)
        }

    @Test
    fun `without an owed quote an unavailable identity still permits the existing mined restore marker`() =
        runBlocking {
            val chat = owedChat()
            chat.store.setZbootQuoted(PEER, false)
            val vm = chat.viewModel()
            AutoSpendVm.setField(
                vm,
                "persistableWalletProvider",
                object : PersistableWalletProvider by FixtureSeedWalletProvider {
                    override suspend fun requirePersistableWallet(): PersistableWallet =
                        error("synthetic wallet identity unavailable during restore")
                },
            )
            fullHistoryPass(vm, boot(EARLIER_RELAY_KEY))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            assertEquals(
                "the P2b restore rule needs no identity comparison without an owed quote",
                EARLIER_RELAY_KEY,
                chat.store.getSentNostrBootPubkey(PEER),
            )
            assertFalse(chat.store.isZbootQuoted(PEER))
            assertFalse(PEER in chat.zbootOwed)
            assertEquals(0, chat.seam.zbootProposals.get())
            killProcesses(vm)
        }

    @Test
    fun `an owed Tunnel sender parked before its quote is stamped cannot pay after history mines its current identity`() =
        runBlocking { parkedOwedSenderCannotChangeItsAuthorization(consent = true) }

    @Test
    fun `an owed Tunnel sender parked before its quote is stamped cannot ask for standing consent after history settles it`() =
        runBlocking { parkedOwedSenderCannotChangeItsAuthorization(consent = false) }

    @Test
    fun `a fresh confirmed reconnect after a mined current identity still pays exactly its new quote`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)
            try {
                chat.owed.store.setSentNostrBootPubkey(PEER, null)
                assertTrue(chat.owed.store.setZbootQuoted(PEER, true))
                assertTrue(chat.owed.store.setZbootOwed(PEER, true))
                val current = currentRelayIdentity(vm)
                SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
                fullHistoryPass(vm, boot(current.first))
                AutoSpendVm.setField(vm, "synchronizerProvider", chat.owed.heights.provider)
                assertFalse("fixture sanity: CURRENT history must settle the previous owed ZBOOT", PEER in chat.owed.zbootOwed)
                assertEquals("fixture sanity: history cannot pay", 0, chat.seam.submitted.size)

                vm.dismissE2EKeyChanged(PEER)
                awaitIdle(vm)
                chat.assertAskedFirst(vm)
                vm.confirmPaidAction()
                awaitReconnectPayment(vm, chat)

                assertEquals("the fresh question quoted one KEX", 1, chat.seam.submittedOf("KEX"))
                assertEquals(
                    "an earlier mined delivery must not ban a fresh confirmed reconnect's ZBOOT",
                    1,
                    chat.seam.submittedOf("ZBOOT"),
                )
                assertEquals("the fresh reconnect must pay exactly its new two-transaction quote", 2, chat.seam.submitted.size)
                assertFalse(chat.owed.store.isZbootQuoted(PEER))
                assertFalse(PEER in chat.owed.zbootOwed)
                recorder.assertNoHandshakeConsentQuestion("a new explicit quote must remain a one-off authorization")
                assertEquals(0, chat.owed.consentWrites.get())
                chat.owed.tick(vm)
                awaitIdle(vm)
                assertEquals("a later block paid the fresh quote again", 2, chat.seam.submitted.size)
            } finally {
                recorder.stop()
                killProcesses(vm)
            }
        }

    @Test
    fun `a fresh reconnect confirmed during an older history pass keeps its new relay identity payment authorized`() =
        runBlocking {
            val chat =
                KeyChangeReconnectChat(
                    PEER,
                    consent = false,
                    exchangeComplete = true,
                    failFirstKexWithStillConfirming = true,
                )
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)
            val beforeCurrentRow = CompletableDeferred<Unit>()
            val releaseCurrentRow = CompletableDeferred<Unit>()
            var scan: kotlinx.coroutines.Job? = null
            try {
                chat.owed.store.setSentNostrBootPubkey(PEER, null)
                assertTrue(chat.owed.store.setZbootQuoted(PEER, true))
                assertTrue(chat.owed.store.setZbootOwed(PEER, true))
                val current = currentRelayIdentity(vm)
                SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
                val ownedScan =
                    launch {
                        fullHistoryPass(vm, listOf(boot(current.first))) { index ->
                            if (index == 0) {
                                beforeCurrentRow.complete(Unit)
                                releaseCurrentRow.await()
                            }
                        }
                    }
                scan = ownedScan
                withTimeout(HandshakeHarness.TIMEOUT_MS) { beforeCurrentRow.await() }
                AutoSpendVm.setField(vm, "synchronizerProvider", chat.owed.heights.provider)
                vm.dismissE2EKeyChanged(PEER)
                awaitIdle(vm)
                chat.assertAskedFirst(vm)
                vm.confirmPaidAction()
                // The observed acceptance proves this new quote was committed before the older row is released.
                awaitTrue { !chat.keyChanged }
                assertEquals("fixture sanity: confirming the new reconnect accepted its key", false, chat.keyChanged)
                assertTrue("fixture sanity: the new reconnect recorded its ZBOOT allowance", chat.owed.store.isZbootQuoted(PEER))
                releaseCurrentRow.complete(Unit)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { ownedScan.join() }

                assertTrue("an older history pass withdrew the newly confirmed reconnect's allowance", chat.owed.store.isZbootQuoted(PEER))
                // If sending is held by the pass, let the KEX reach its controlled first shortfall after release.
                awaitTrue { chat.owed.heights.isWaiting() }
                assertEquals("fixture sanity: the new KEX's controlled first shortfall paid nothing", 0, chat.seam.submitted.size)
                chat.owed.heights.advance()
                awaitReconnectPayment(vm, chat)
                assertEquals("the confirmed reconnect's KEX must still be paid once", 1, chat.seam.submittedOf("KEX"))
                assertEquals("the newly confirmed ZBOOT must survive the older CURRENT history pass", 1, chat.seam.submittedOf("ZBOOT"))
                assertEquals("the reconnect must pay exactly its two quoted transactions", 2, chat.seam.submitted.size)
                assertFalse(chat.owed.store.isZbootQuoted(PEER))
                assertFalse(PEER in chat.owed.zbootOwed)
                recorder.assertNoHandshakeConsentQuestion("the confirmed reconnect cannot lose its quote to a standing-consent question")
                assertEquals(0, chat.owed.consentWrites.get())
                chat.owed.tick(vm)
                awaitIdle(vm)
                assertEquals("a later block duplicated the reconnect confirmed during history", 2, chat.seam.submitted.size)
            } finally {
                releaseCurrentRow.complete(Unit)
                try {
                    scan?.let { withTimeout(HandshakeHarness.TIMEOUT_MS) { it.join() } }
                } finally {
                    recorder.stop()
                    killProcesses(vm)
                }
            }
        }

    @Test
    fun `CURRENT history after quote consumption but before SDK handoff settles the active sender without restoring or submitting`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = true, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)
            var callbackCompleted = false
            try {
                chat.store.setSentNostrBootPubkey(PEER, null)
                assertTrue(chat.store.setZbootQuoted(PEER, true))
                assertTrue(chat.store.setZbootOwed(PEER, true))
                chat.tunnelPeers += PEER
                val current = currentRelayIdentity(vm)
                val paidSlot = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)
                lateinit var seam: HandshakeSeam
                seam =
                    HandshakeSeam(
                        PEER,
                        onFirstAttempt = {
                            // Proposal creation is after the checked quote consumption and before SDK handoff.
                            assertFalse(
                                "fixture sanity: the allowance was consumed before the proposal callback",
                                chat.store.isZbootQuoted(PEER),
                            )
                            assertFalse("fixture sanity: consuming the allowance removed owed before this proof", PEER in chat.zbootOwed)
                            assertEquals(
                                "fixture sanity: exactly one proposal reached the pre-submit proof",
                                1,
                                seam.proposalRequests.get(),
                            )
                            val paidRecord = chat.paidRecords[paidSlot]
                            assertTrue("fixture sanity: this proposal attempt has a ZBOOT paid record", paidRecord != null)
                            assertTrue(
                                "fixture sanity: the fresh paid record names this attempt's current relay identity",
                                HandshakePaidRecord.remainingCooldown(paidRecord, current.first, System.currentTimeMillis()) > 0L,
                            )
                            runBlocking {
                                withTimeout(HandshakeHarness.TIMEOUT_MS) { fullHistoryPass(vm, boot(current.first)) }
                            }
                            callbackCompleted = true
                        },
                    )
                installExistingProposalSeam(vm, seam)
                chat.tick(vm)
                awaitIdle(vm)

                assertTrue("fixture sanity: the complete CURRENT history pass ran inside the first proposal", callbackCompleted)
                assertEquals("fixture sanity: one proposal was already built when CURRENT proof arrived", 1, seam.proposalRequests.get())
                assertEquals("an active consumed quote was submitted despite CURRENT proof before SDK handoff", 0, seam.submits.get())
                assertNull(
                    "an aborted pre-submit attempt must not leave its matching paid stamp suppressing a later fresh quote",
                    chat.paidRecords[paidSlot],
                )
                assertFalse("CURRENT proof cannot restore the allowance consumed by the settled frame", chat.store.isZbootQuoted(PEER))
                assertFalse("CURRENT proof cannot restore owed for the settled frame", PEER in chat.zbootOwed)
                assertEquals("late proof must survive the old frame's cleanup", current.first, chat.store.getSentNostrBootPubkey(PEER))
                AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
                repeat(3) {
                    chat.tick(vm)
                    awaitIdle(vm)
                }
                assertEquals("a later Tunnel tick reproposed the identity proven mined before handoff", 1, seam.proposalRequests.get())
                assertEquals("standing consent repaid a mined active frame after its late-proof cleanup", 0, seam.submits.get())
                assertEquals(current.first, chat.store.getSentNostrBootPubkey(PEER))
                recorder.assertNoHandshakeConsentQuestion("late proof must finish delivered without asking a new consent")
                assertEquals(0, chat.consentWrites.get())
            } finally {
                recorder.stop()
                killProcesses(vm)
            }
        }

    @Test
    fun `a successful stale OLD-only subset pass must classify CURRENT evidence retained by an unknown overlapping pass`() =
        runBlocking {
            val chat = owedChat()
            val older = chat.viewModel()
            val unknown = chat.viewModel()
            val beforeOldRow = CompletableDeferred<Unit>()
            val releaseOldRow = CompletableDeferred<Unit>()
            var scan: kotlinx.coroutines.Job? = null
            try {
                val current = currentRelayIdentity(older)
                val ownedScan =
                    launch {
                        fullHistoryPass(older, listOf(boot(EARLIER_RELAY_KEY))) { index ->
                            if (index == 0) {
                                beforeOldRow.complete(Unit)
                                releaseOldRow.await()
                            }
                        }
                    }
                scan = ownedScan
                withTimeout(HandshakeHarness.TIMEOUT_MS) { beforeOldRow.await() }
                AutoSpendVm.setField(
                    unknown,
                    "persistableWalletProvider",
                    object : PersistableWalletProvider by FixtureSeedWalletProvider {
                        override suspend fun requirePersistableWallet(): PersistableWallet =
                            error("synthetic identity unavailable while retaining CURRENT evidence")
                    },
                )
                fullHistoryPass(unknown, boot(current.first))
                assertTrue("unknown CURRENT evidence must retain its owed quote until classification", PEER in chat.zbootOwed)
                assertTrue(chat.store.isZbootQuoted(PEER))
                // The known pass started earlier and does NOT read CURRENT; it must classify the retained union.
                releaseOldRow.complete(Unit)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { ownedScan.join() }

                assertFalse("the stale OLD-only pass forgot retained CURRENT proof when it completed", PEER in chat.zbootOwed)
                assertFalse("retained CURRENT proof must settle the allowance with owed", chat.store.isZbootQuoted(PEER))
                AutoSpendVm.setField(older, "synchronizerProvider", chat.heights.provider)
                repeat(3) {
                    chat.tick(older)
                    awaitIdle(older)
                }
                assertEquals(
                    "the successful stale OLD subset reopened a quote proven CURRENT in its union",
                    0,
                    chat.seam.zbootProposals.get(),
                )
                assertEquals(0, chat.seam.submitted.size)
            } finally {
                releaseOldRow.complete(Unit)
                try {
                    scan?.let { withTimeout(HandshakeHarness.TIMEOUT_MS) { it.join() } }
                } finally {
                    killProcesses(older, unknown)
                }
            }
        }

    @Test
    fun `a fresh confirmed Reset with a new E2E key still pays its same relay identity despite old CURRENT proof before submit`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)
            var callbackCompleted = false
            try {
                val previousE2E = chat.store.getE2EOurPublicKey(PEER)
                assertTrue("fixture sanity: the prior session has our E2E public key", previousE2E != null)
                val current = currentRelayIdentity(vm)
                // A synthetic already-mined memo from before this new Reset; no prior paid lifecycle is claimed.
                val oldCurrentMemo = boot(current.first)
                vm.resetSecureSession(PEER)
                awaitIdle(vm)
                chat.reset.assertAskedFirst(vm)
                vm.confirmPaidAction()
                awaitIdle(vm)
                assertEquals("fixture sanity: the newly confirmed Reset paid its KEX", 1, chat.seam.submittedOf("KEX"))
                val newE2E = chat.store.getE2EOurPublicKey(PEER)
                assertTrue("fixture sanity: the confirmed Reset installed our new E2E public key", newE2E != null)
                assertNotEquals("fixture sanity: this is a new E2E session, not a retry of the old quote", previousE2E, newE2E)
                assertEquals("fixture sanity: Reset keeps the same account relay identity", current.first, currentRelayIdentity(vm).first)
                lateinit var seam: HandshakeSeam
                seam =
                    HandshakeSeam(
                        PEER,
                        onFirstAttempt = {
                            assertFalse(
                                "fixture sanity: the fresh Reset allowance was consumed before proof",
                                chat.store.isZbootQuoted(PEER),
                            )
                            assertFalse("fixture sanity: consuming the fresh Reset allowance removed owed", PEER in chat.zbootOwed)
                            assertEquals(
                                "fixture sanity: only the Reset ZBOOT reached this proposal callback",
                                1,
                                seam.proposalRequests.get(),
                            )
                            runBlocking {
                                withTimeout(HandshakeHarness.TIMEOUT_MS) { fullHistoryPass(vm, oldCurrentMemo) }
                            }
                            callbackCompleted = true
                        },
                    )
                installExistingProposalSeam(vm, seam)
                chat.kexAckFromContact(
                    vm,
                    co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
                        .generateKeyPair(),
                    txId = "synthetic-zc703-fresh-reset-key-reply",
                )
                withTimeout(HandshakeHarness.TIMEOUT_MS) {
                    var seen = -1
                    while (seen != seam.proposalRequests.get()) {
                        seen = seam.proposalRequests.get()
                        awaitIdle(vm)
                        awaitIdle(vm)
                    }
                }

                assertTrue("fixture sanity: the old CURRENT scan completed inside the fresh Reset proposal", callbackCompleted)
                assertEquals("the fresh Reset must propose its one quoted ZBOOT", 1, seam.proposalRequests.get())
                assertEquals("old CURRENT history cancelled a newly confirmed Reset's ZBOOT under its NEW E2E key", 1, seam.submits.get())
                assertEquals("the confirmed Reset paid exactly its KEX and ZBOOT quote", 2, chat.seam.submitted.size + seam.submits.get())
                assertFalse("the paid fresh Reset leaves no ZBOOT allowance", chat.store.isZbootQuoted(PEER))
                assertFalse("the paid fresh Reset leaves no owed ZBOOT", PEER in chat.zbootOwed)
                assertEquals(current.first, chat.store.getSentNostrBootPubkey(PEER))
                AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
                chat.tunnelPeers += PEER
                repeat(3) {
                    chat.tick(vm)
                    awaitIdle(vm)
                }
                assertEquals("later Tunnel ticks proposed the fresh Reset ZBOOT twice", 1, seam.proposalRequests.get())
                assertEquals("later Tunnel ticks submitted the fresh Reset ZBOOT twice", 1, seam.submits.get())
                recorder.assertNoHandshakeConsentQuestion("the actual Reset quote must remain its one-off authorization")
                assertEquals(0, chat.consentWrites.get())
            } finally {
                recorder.stop()
                killProcesses(vm)
            }
        }

    /** Wire the EXISTING HandshakeSeam through the real use case/repository; ResetKexSeam has no callback. */
    private fun installExistingProposalSeam(
        vm: ChatViewModel,
        seam: HandshakeSeam,
    ) {
        val accounts =
            ChatViewModel::class.java
                .getDeclaredField("accountDataSource")
                .apply { isAccessible = true }
                .get(vm) as co.electriccoin.zcash.ui.common.datasource.AccountDataSource
        val repository =
            co.electriccoin.zcash.ui.common.repository
                .ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = seam,
                    zashiSpendingKeyDataSource =
                        co.electriccoin.zcash.ui.common.usecase
                            .Zc101FakeZashiSpendingKeyDataSource(),
                ).also { SendHarnessScopes.current().own(it) }
        AutoSpendVm.setField(
            vm,
            "createChunkedMessageProposal",
            co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository =
                    co.electriccoin.zcash.ui.common.usecase
                        .Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository = repository,
                accountDataSource = accounts,
                navigationRouter =
                    co.electriccoin.zcash.ui.common.usecase
                        .Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            ),
        )
    }

    /** Join the actual proposal chain until stable; the following assertions decide whether the quote was fulfilled. */
    private suspend fun awaitReconnectPayment(
        vm: ChatViewModel,
        chat: KeyChangeReconnectChat,
    ) {
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            var seen = -1
            while (seen != chat.seam.proposals()) {
                seen = chat.seam.proposals()
                awaitIdle(vm)
                awaitIdle(vm)
            }
        }
    }

    /**
     * Park the REAL owed retry at its existing conversation-ID mutex, after the sender has selected the quoted route
     * and read resumeOwed. Complete CURRENT history while it waits. This is a real continuation of the same sender,
     * not a new trigger after history; it must retain its original quote authorization or stop, never reinterpret the
     * withdrawn quote as permission to spend/ask under standing consent. No product-only fault seam is required.
     */
    private suspend fun parkedOwedSenderCannotChangeItsAuthorization(consent: Boolean) {
        val chat = ResetOwedChat(PEER, consent = consent, mode = ConversationMode.TUNNEL)
        val vm = chat.viewModel()
        val recorder = SendStateRecorder(vm)
        val held = Mutex(locked = true)
        var released = false
        try {
            chat.store.setSentNostrBootPubkey(PEER, null)
            assertTrue(chat.store.setZbootQuoted(PEER, true))
            assertTrue(chat.store.setZbootOwed(PEER, true))
            chat.tunnelPeers += PEER
            val current = currentRelayIdentity(vm)
            val reached = CompletableDeferred<Unit>()
            val observed =
                object : Mutex by held {
                    override suspend fun lock(owner: Any?) {
                        reached.complete(Unit)
                        held.lock(owner)
                    }
                }
            AutoSpendVm.setField(vm, "convIdMutex", observed)
            chat.tick(vm)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { reached.await() }
            assertEquals("fixture sanity: the owed sender parked before any proposal", 0, chat.seam.zbootProposals.get())
            assertTrue("fixture sanity: the old quote still stood when the sender parked", chat.store.isZbootQuoted(PEER))
            SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
            fullHistoryPass(vm, boot(current.first))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            assertFalse("CURRENT history must discharge this owed ZBOOT", PEER in chat.zbootOwed)
            held.unlock()
            released = true
            awaitIdle(vm)

            assertEquals("the parked quoted sender paid an identity that history already mined", 0, chat.seam.zbootProposals.get())
            assertEquals(0, chat.seam.submitted.size)
            assertEquals(
                "the parked sender's cleanup erased the mined CURRENT marker",
                current.first,
                chat.store.getSentNostrBootPubkey(PEER),
            )
            // These are real Tunnel-list ticks AFTER the parked frame and its cleanup completed.
            repeat(3) {
                chat.tick(vm)
                awaitIdle(vm)
                assertEquals("a later Tunnel tick erased the mined CURRENT marker", current.first, chat.store.getSentNostrBootPubkey(PEER))
                assertEquals("standing consent repaid the mined CURRENT identity after stale cleanup", 0, chat.seam.zbootProposals.get())
            }
            assertEquals(0, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("a parked quoted attempt fell back to standing consent after history settled it")
            assertEquals("the parked attempt cannot write standing consent", 0, chat.consentWrites.get())
        } finally {
            if (!released) held.unlock()
            try {
                awaitIdle(vm)
            } finally {
                recorder.stop()
                killProcesses(vm)
            }
        }
    }

    /**
     * Reuses only PendingReconcileHarness's exact synthetic transaction/repository installer. It does not use that
     * harness's payment preference adapter: this pass must read the real owed-chat markers and allowance. Empty SDK
     * recipients plus a bare ZBOOT select the unresolved-outgoing branch (ConvIdResolver reads only ZMSG envelopes),
     * whose dedicated own-ZBOOT writer attributes the memo by its parsed conversation ID. No UI rows need rendering.
     */
    private suspend fun fullHistoryPass(
        vm: ChatViewModel,
        memo: String?,
        mined: Boolean = true,
    ) = fullHistoryPass(vm, listOfNotNull(memo), mined = mined)

    @Suppress("UNCHECKED_CAST")
    private suspend fun fullHistoryPass(
        vm: ChatViewModel,
        memos: List<String>,
        mined: Boolean = true,
        beforeMemo: suspend (Int) -> Unit = {},
    ) {
        AutoSpendVm.setField(vm, "restoreRecheck", MutableStateFlow(0))
        val txs =
            memos.mapIndexed { index, memo ->
                PendingReconcileHarness.SentTx(
                    number = 703 + index,
                    recipient = PEER,
                    memo = memo,
                    minedHeight = if (mined) 3_000_100L + index else null,
                    blockTime = if (mined) Instant.ofEpochSecond(1_700_000_000L + index) else null,
                )
            }
        val installer =
            PendingReconcileHarness::class.java
                .getDeclaredMethod("install", ChatViewModel::class.java, List::class.java)
                .apply { isAccessible = true }
        val transactions = invokeUnwrapped { installer.invoke(PendingReconcileHarness, vm, txs) } as List<Transaction>
        val repositoryField = ChatViewModel::class.java.getDeclaredField("transactionRepository").apply { isAccessible = true }
        val repository = repositoryField.get(vm) as TransactionRepository
        val reads = AtomicInteger(0)
        val noRecipient =
            object : TransactionRepository by repository {
                override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()

                override suspend fun getMemos(transaction: Transaction): List<String> {
                    beforeMemo(reads.getAndIncrement())
                    return repository.getMemos(transaction)
                }
            }
        AutoSpendVm.setField(vm, "transactionRepository", noRecipient)
        val conversion =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            invokeUnwrapped {
                conversion.invoke(
                    vm,
                    transactions,
                    HandshakeHarness.SELF,
                    emptySet<String>(),
                    emptyList<Any>(),
                    emptySet<String>(),
                    true,
                    continuation,
                )
            }
        }
    }

    private fun owedChat(): ResetOwedChat =
        ResetOwedChat(PEER, consent = false).also { chat ->
            chat.store.setSentNostrBootPubkey(PEER, null)
            assertTrue(chat.store.setZbootQuoted(PEER, true))
            assertTrue(chat.store.setZbootOwed(PEER, true))
            assertTrue("fixture sanity: Shielded contacts are absent from the Tunnel list", chat.tunnelPeers.isEmpty())
            assertEquals(null, chat.reset.peerRelayKey)
        }

    private fun boot(identity: String): String =
        ZBootMessage(
            ResetFixture.CONV_ID,
            identity,
            "wss://relay.synthetic.example",
            "synthetic-own-history-signature",
            epoch = 0L,
        ).serialize()

    /** Independently derive only the synthetic fixture key; never register it or call a product history helper. */
    private suspend fun currentRelayIdentity(vm: ChatViewModel): Pair<String, String> {
        val preferences =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(vm) as co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
        val wallet = FixtureSeedWalletProvider.requirePersistableWallet()
        val seed =
            cash.z.ecc.android.bip39.Mnemonics
                .MnemonicCode(wallet.seedPhrase.joinToString())
                .toSeed()
        try {
            val identity =
                co.electriccoin.zcash.ui.nostr.NOSTRIdentity
                    .fromSeed(seed, preferences.getNostrRotationIndex())
            val publicKey = identity.publicKey.joinToString("") { "%02x".format(it) }
            return publicKey to
                co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS
                    .first()
        } finally {
            seed.fill(0)
        }
    }

    private fun invokeUnwrapped(call: () -> Any?): Any? =
        try {
            call()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /** Optional product process bookkeeping; absent on the baseline. Call only after owned sends are joined. */
    private fun resetZbootWorkIfPresent() {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "zbootWorkByScope" } ?: return
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    private suspend fun killProcesses(vararg vms: ChatViewModel) {
        ResetOwedChat.kill(*vms)
        resetZbootWorkIfPresent()
    }

    private companion object {
        const val PEER = "usyntheticzc703earlierrelay1"
        const val EARLIER_RELAY_KEY = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
    }
}
