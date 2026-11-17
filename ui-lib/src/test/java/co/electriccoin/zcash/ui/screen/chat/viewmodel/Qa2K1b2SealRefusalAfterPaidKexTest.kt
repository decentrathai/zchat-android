package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * QA2 lane k1b2, ZC-1843 (Fable review of k1b, 13 Nov; money-path rule 1, app-texts rule 2) — a confirmed Shielded first
 * message pays its key exchange; while that KEX's submit runs, the contact's own key exchange lands (the live handler
 * stores the key, sets the received-KEX mark and the Accept hold). The message then meets the Accept-first backstop at the
 * seal (ZC-1792, ZC-1818) and is not sent. Its refusal said "Not sent, and nothing was paid" although this run paid the
 * KEX. Wanted: only the KEX is paid (the message is never built) and the row's reason never says nothing was paid.
 *
 * Built like PaidFirstMessageStopsWhenSwitchedOpenAfterItsKexTest (ZC-1039). Every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa2K1b2SealRefusalAfterPaidKexTest {
    private val scopes = SendHarnessScopes()
    private val peer = "usyntheticzc1843landingkex01"
    private val contactKey = E2EEncryption.generateKeyPair().publicKey

    /** Set while the KEX's submit runs: from then on the store holds the contact's key exchange, held for Accept. */
    @Volatile private var landed = false

    /** ZC-1861: from the Retry on, the user has acknowledged the message cost (the Retry path refuses before it otherwise). */
    @Volatile private var costAcknowledged = false

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        scopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    private fun relayKey(): String =
        NOSTRIdentity
            .fromSeed(ByteArray(64) { (it + 43).toByte() })
            .publicKey
            .joinToString("") { "%02x".format(it) }

    private fun withLandingKex(store: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
            val forPeer = args?.getOrNull(0) == peer
            when {
                forPeer && method.name == "getPeerNostrPubkey" -> relayKey()
                costAcknowledged && method.name == "hasAcknowledgedMessageCost" -> true
                forPeer && landed && method.name in LANDED_KEY_READS -> contactKey
                forPeer && landed && method.name == "getSentKexAckPubkey" -> null
                else ->
                    try {
                        method.invoke(store, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    @Test
    fun `ZC-1843 - a first message refused at the seal after its KEX was paid never says nothing was paid`() =
        runBlocking {
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            val seam =
                HandshakeSeam(peer, holdFirstSubmit = true).apply {
                    feeProbe = chat.fixture.seam.feeProbe
                    proposalFee = chat.fixture.seam.proposalFee
                }
            val base = syntheticZashiAccount()
            val funded =
                Zc101FakeAccountDataSource(
                    base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0)))),
                )
            val useCase =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = funded,
                            proposalDataSource = seam,
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also(scopes::own),
                    accountDataSource = funded,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                )
            PaymentHarness.setField(vm, "accountDataSource", funded)
            PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
            PaymentHarness.setField(vm, "zchatPreferences", withLandingKex(chat.prefs))
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-zc1843-ack") }

            assertTrue("the Shielded send was refused before its question", vm.sendMessage(peer, "synthetic zc1843 first message"))
            awaitIdle(vm)
            val question = HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
            assertNotNull("fixture: the first-message question was not asked", question)
            vm.confirmPaidAction()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            assertTrue("fixture: the first submit is not the KEX", ZMSGProtocol.isKEXMessage(firstMemo(seam.proposalUris.first())))

            // The contact's key exchange lands while our KEX's submit runs.
            landed = true
            seam.release.complete(Unit)
            awaitIdle(vm)

            val messages =
                seam.proposalUris.toList().map { firstMemo(it) }.count { memo ->
                    ZMSGProtocol.isV4Message(memo) && !ZMSGProtocol.isKEXMessage(memo)
                }
            assertEquals("fixture: the message was built after the contact's key exchange landed", 0, messages)
            val row = PaymentHarness.pendingMessages(vm).singleOrNull()
            assertNotNull("fixture: the first message's row is not kept", row)
            assertEquals("fixture: the row is not FAILED", MessageStatus.FAILED, row!!.status)
            val reason = row.failureReason.orEmpty()
            assertFalse(
                "the row says nothing was paid although this run paid its key exchange: '$reason'",
                reason == ChatSendCopy.ACCEPT_FIRST_NOT_SENT || "nothing was paid" in reason.lowercase(),
            )
            assertEquals(
                "the refusal after a paid KEX is not the setup-paid Accept-first text",
                FirstMessagePlanChat.copy("ACCEPT_FIRST_NOT_SENT_SETUP_PAID"),
                reason,
            )
            assertEquals(
                "the screen's notice is not the row's reason",
                SendMessageState.Error(reason),
                HandshakeHarness.sendMessageState(vm),
            )
        }

    /**
     * ZC-1861 (QA2 pa3, Fable re-review of k1b2): the rows whose run paid their key exchange are process memory. After a
     * restart, a Retry of that row meets the Accept-first gate again and said "Not sent, and nothing was paid" although
     * the row's KEX went out. Wanted: the row keeps its setup-paid reason and the Retry's notice says the same.
     */
    @Test
    fun `ZC-1861 - after a restart a Retry of the row keeps its setup-paid reason`() =
        runBlocking {
            val (vm, seam) = refusedAfterPaidKex()
            val row = PaymentHarness.pendingMessages(vm).single()
            val setupPaid = FirstMessagePlanChat.copy("ACCEPT_FIRST_NOT_SENT_SETUP_PAID")
            assertEquals("fixture: the row carries the setup-paid reason", setupPaid, row.failureReason)

            // The restart: the process memory of which rows paid their key exchange is gone.
            forgetRowsWithKexPaid()
            costAcknowledged = true
            // The Accept still waits: sendMessage's Accept-first gate (ZC-1792 F1) refuses the dispatch and the row is put
            // back as it was. Its notice must not say nothing was paid for this row, whose setup went out.
            assertFalse("fixture: the Retry was dispatched although the Accept still waits", vm.retryMessage(peer, row.id))
            awaitIdle(vm)

            val after = PaymentHarness.pendingMessages(vm).single { it.id == row.id }
            assertEquals("fixture: the row stays FAILED", MessageStatus.FAILED, after.status)
            assertEquals("the row lost its setup-paid reason", setupPaid, after.failureReason)
            val notice = (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message.orEmpty()
            assertFalse(
                "the Retry after a restart says nothing was paid although the setup of this row went out: '$notice'",
                notice == ChatSendCopy.ACCEPT_FIRST_NOT_SENT || "nothing was paid" in notice.lowercase(),
            )
            assertEquals("the Retry's notice is not the row's setup-paid reason", setupPaid, notice)
            assertEquals("fixture: the Retry paid nothing more", 1, seam.proposalUris.size)
        }

    private fun forgetRowsWithKexPaid() {
        val field = ChatViewModel::class.java.getDeclaredField("rowsWithKexPaid").apply { isAccessible = true }
        (field.get(null) as MutableSet<*>).clear()
    }

    /** The ZC-1843 run: the first message's KEX paid, the contact's key exchange landed, the message refused at the seal. */
    private suspend fun refusedAfterPaidKex(): Pair<ChatViewModel, HandshakeSeam> {
        val chat = FirstMessagePlanChat(peer)
        val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
        val seam =
            HandshakeSeam(peer, holdFirstSubmit = true).apply {
                feeProbe = chat.fixture.seam.feeProbe
                proposalFee = chat.fixture.seam.proposalFee
            }
        val base = syntheticZashiAccount()
        val funded =
            Zc101FakeAccountDataSource(
                base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0)))),
            )
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = funded,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(scopes::own),
                accountDataSource = funded,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )
        PaymentHarness.setField(vm, "accountDataSource", funded)
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
        PaymentHarness.setField(vm, "zchatPreferences", withLandingKex(chat.prefs))
        NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-zc1861-ack") }
        assertTrue(vm.sendMessage(peer, "synthetic zc1861 first message"))
        awaitIdle(vm)
        assertNotNull(HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation)
        vm.confirmPaidAction()
        withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
        landed = true
        seam.release.complete(Unit)
        awaitIdle(vm)
        return vm to seam
    }

    private companion object {
        val LANDED_KEY_READS = setOf("getE2EPeerPublicKey", "getReceivedKexPubkey", "getInboundKexAwaitingAccept")
    }
}
