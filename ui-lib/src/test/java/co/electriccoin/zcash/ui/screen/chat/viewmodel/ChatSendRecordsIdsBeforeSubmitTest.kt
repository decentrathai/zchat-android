package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.F2SubmitPath
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.AMOUNT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent design 3, review point 2; SPECS §1.3 R11, §1.4 step 4): a chat message's
 * transaction id and expiry height are on the row BEFORE anything is submitted.
 *
 * F2 built the step (the one submit path hands every stored id and expiry to `onTransactionsStored` before the first
 * submit, and submits nothing if that record fails), and left it to F4 to wire for the 1:1 senders. At HEAD
 * ChatViewModel passes no callback, so the row learns its id only from the submit's result: a send killed or failing
 * mid-submit leaves a row with a marker and no id, which can only wait for the release anchor.
 *
 * This drives the real sendMessage, the real use case and repository, and the real createStoreAndSubmit over a
 * recording broadcaster. At the moment the broadcaster is asked to submit, the row on disk must already hold the txid
 * and its expiry height; after the send, the id and expiry are still there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendRecordsIdsBeforeSubmitTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4recordbefore0001"
        const val EXPIRY = H + 40
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the txid and its expiry are on the row before the first submit`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = StoringSeam(PEER, prefs)
            val vm = PaymentHarness.paymentViewModel(prefs, PaymentSeam(PEER))
            PaymentHarness.setField(vm, "createChunkedMessageProposal", seam.useCase())
            PaymentHarness.setBlockHeight(vm, H)

            assertTrue(vm.sendMessage(PEER, "synthetic recorded hello", AMOUNT))
            awaitIdle(vm)

            assertEquals("the message was not submitted once", listOf(seam.legId), seam.broadcaster.submitted())
            val atSubmit = seam.rowAtSubmit.singleOrNull()
            assertNotNull("the row was not on disk at the submit", atSubmit)
            assertEquals("the txid was not on the row before the submit", listOf(seam.legId), atSubmit!!.submittedTxIds)
            assertEquals(
                "the expiry height was not on the row before the submit",
                mapOf(seam.legId to EXPIRY),
                SendAttemptHarness.storedField(atSubmit, "submittedExpiries"),
            )
            val after = prefs.pending.values.single()
            assertEquals(listOf(seam.legId), after.submittedTxIds)
            assertEquals(
                "the submit's result dropped the recorded expiry",
                mapOf(seam.legId to EXPIRY),
                SendAttemptHarness.storedField(after, "submittedExpiries"),
            )
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    /**
     * The settlement seam: the real createStoreAndSubmit over a [RecordingBroadcaster] with one leg, which records the
     * stored row at the moment it is asked to submit.
     */
    private class StoringSeam(
        private val peer: String,
        private val prefs: PaymentPrefs,
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        private val leg = RecordingBroadcaster.leg(seed = 0x5C, expiryHeight = EXPIRY)
        val legId: String = leg.txIdString()
        val broadcaster = RecordingBroadcaster(listOf(leg))
        val rowAtSubmit: MutableList<ZchatPreferences.PendingMessageData> = Collections.synchronizedList(mutableListOf())

        init {
            broadcaster.answerSubmit(legId) { transaction ->
                prefs.pending.values
                    .firstOrNull { it.id.startsWith("pending_") }
                    ?.let { rowAtSubmit += it }
                TransactionSubmitResult.Success(transaction.txId)
            }
        }

        suspend fun useCase() =
            co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository =
                    co.electriccoin.zcash.ui.common.usecase
                        .Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    co.electriccoin.zcash.ui.common.repository
                        .ZashiProposalRepositoryImpl(
                            accountDataSource =
                                co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource(
                                    co.electriccoin.zcash.ui.common.usecase
                                        .syntheticZashiAccount(),
                                ),
                            proposalDataSource = this,
                            zashiSpendingKeyDataSource =
                                co.electriccoin.zcash.ui.common.usecase
                                    .Zc101FakeZashiSpendingKeyDataSource(),
                        ).also(SendHarnessScopes.current()::own),
                accountDataSource =
                    co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource(
                        co.electriccoin.zcash.ui.common.usecase
                            .syntheticZashiAccount(),
                    ),
                navigationRouter =
                    co.electriccoin.zcash.ui.common.usecase
                        .Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal = syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-recorded-memo")

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult = F2SubmitPath.createStoreAndSubmit(broadcaster)

        @Suppress("UNCHECKED_CAST")
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = F2SubmitPath.createStoreAndSubmit(broadcaster, onStored = onStored as suspend (List<Any>) -> Unit)
    }
}
