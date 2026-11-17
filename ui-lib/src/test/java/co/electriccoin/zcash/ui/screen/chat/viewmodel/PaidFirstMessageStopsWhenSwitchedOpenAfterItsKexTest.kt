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
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
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
 * ZC-1039 (high, money; T7aF cross-family review, GPT F2) — a paid first message still goes out after the chat is
 * switched to Open once its key exchange is paid.
 *
 * A Shielded first message is confirmed (its question counts the KEX and the message). The KEX is submitted; while its
 * submit is still running the user switches the chat to Open. The KEX then completes (SENT), payOwedKex answers Done,
 * and nothing reads the mode again before the message's proposal is built: the message is paid in an Open chat (W-57:
 * an Open chat never pays). ZC-1027 covered only the switch made while the send waited for the owed-KEX claim.
 *
 * Wanted: once the chat is Open, the message is not built or paid (the KEX already out stays out), and its row is not
 * reported SENT. Every address, key and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidFirstMessageStopsWhenSwitchedOpenAfterItsKexTest {
    private val scopes = SendHarnessScopes()
    private val peer = "usyntheticzc1039switchpeer01"

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        scopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun relayKey(): String =
        NOSTRIdentity
            .fromSeed(ByteArray(64) { (it + 23).toByte() })
            .publicKey
            .joinToString("") { "%02x".format(it) }

    private fun withKnownRelay(store: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
            if (method.name == "getPeerNostrPubkey" && args?.getOrNull(0) == peer) {
                relayKey()
            } else {
                try {
                    method.invoke(store, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    @Test
    fun `a confirmed Shielded first message whose KEX completes after a switch to Open builds no message`() =
        runBlocking {
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            // The KEX's submit is held so the switch lands while it runs.
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
            PaymentHarness.setField(vm, "zchatPreferences", withKnownRelay(chat.prefs))
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-zc1039-ack") }

            assertTrue("the Shielded send was refused before its question", vm.sendMessage(peer, "synthetic zc1039 first message"))
            awaitIdle(vm)
            val question = HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
            assertNotNull("fixture: the first-message question was not asked", question)
            assertEquals("FIRST_MESSAGE", question!!.confirmation.action.name)
            vm.confirmPaidAction()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            assertTrue("fixture: the first submit is not the KEX", ZMSGProtocol.isKEXMessage(firstMemo(seam.proposalUris.first())))

            vm.switchConversationMode(peer, ConversationMode.OPEN)
            awaitTrue { chat.fixture.mode == ConversationMode.OPEN }
            seam.release.complete(Unit)
            awaitIdle(vm)

            val messages =
                seam.proposalUris.toList().map { firstMemo(it) }.count { memo ->
                    ZMSGProtocol.isV4Message(memo) && !ZMSGProtocol.isKEXMessage(memo)
                }
            assertEquals("the paid first message was built after its chat became Open", 0, messages)
            assertFalse(
                "the on-chain message was reported SENT in an Open chat",
                PaymentHarness.pendingMessages(vm).any { it.status == MessageStatus.SENT },
            )
        }
}
