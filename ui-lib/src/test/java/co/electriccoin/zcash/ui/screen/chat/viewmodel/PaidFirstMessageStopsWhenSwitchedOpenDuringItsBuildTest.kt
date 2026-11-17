package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.WalletAccount
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1039-R1 (P2, money; T7aF review of 6efce1643, finding 2) — ZC-1039 reads the mode before the message's proposal is
 * built, but the build runs on another dispatcher: a switch to Open while the proposal is being built is still
 * submitted (paid) in an Open chat (W-57).
 *
 * A confirmed Shielded first message pays its KEX; while the message's proposal is being built the chat becomes Open.
 * Wanted: the message is not submitted (only the KEX went), and its row is not reported SENT. Every address, key and
 * transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidFirstMessageStopsWhenSwitchedOpenDuringItsBuildTest {
    private val scopes = SendHarnessScopes()
    private val peer = "usyntheticzc1039r1buildpeer1"

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
            .fromSeed(ByteArray(64) { (it + 29).toByte() })
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

    /** [seam], with [onBuild] run while the n-th proposal is being built. */
    private class BuildHook(
        private val seam: HandshakeSeam,
        private val onBuild: (Int) -> Unit,
    ) : ProposalDataSource by seam {
        private val builds = AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            val proposal = seam.createZip321Proposal(account, zip321Uri, allowMultiPayment)
            onBuild(builds.incrementAndGet())
            return proposal
        }
    }

    @Test
    fun `a switch to Open while the paid message's proposal is built submits no message`() =
        runBlocking {
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            val seam =
                HandshakeSeam(peer).apply {
                    feeProbe = chat.fixture.seam.feeProbe
                    proposalFee = chat.fixture.seam.proposalFee
                }
            // The second proposal is the message (the KEX goes first): the chat becomes Open while it is built.
            val hooked = BuildHook(seam) { n -> if (n == 2) chat.fixture.mode = ConversationMode.OPEN }
            val base = syntheticZashiAccount()
            val ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))
            val funded = Zc101FakeAccountDataSource(base.copy(unified = base.unified.copy(ironwood = ironwood)))
            val useCase =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = funded,
                            proposalDataSource = hooked,
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also(scopes::own),
                    accountDataSource = funded,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                )
            PaymentHarness.setField(vm, "accountDataSource", funded)
            PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
            PaymentHarness.setField(vm, "zchatPreferences", withKnownRelay(chat.prefs))

            assertTrue("the Shielded send was refused before its question", vm.sendMessage(peer, "synthetic zc1039r1 message"))
            awaitIdle(vm)
            val question = HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
            assertNotNull("fixture: the first-message question was not asked", question)
            vm.confirmPaidAction()
            awaitIdle(vm)

            val memos = seam.proposalUris.toList().map { firstMemo(it) }
            assertTrue("fixture: the first proposal is not the KEX", memos.isNotEmpty() && ZMSGProtocol.isKEXMessage(memos.first()))
            assertEquals("fixture: the message's proposal was never built", 2, memos.size)
            assertEquals("the message was submitted (paid) after its chat became Open during the build", 1, seam.submits.get())
            assertFalse(
                "the on-chain message was reported SENT in an Open chat",
                PaymentHarness.pendingMessages(vm).any { it.status == MessageStatus.SENT },
            )
        }
}
