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
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 P5, §1.4 steps 6-7): a payment of a request has its stored transaction id
 * and expiry on the durable started record BEFORE anything is submitted.
 *
 * F2 built the step (the one submit path hands every stored id and expiry to `onTransactionsStored` before the first
 * submit, and submits nothing if that record throws), and F4 wired it for the 1:1 chat rows. At 3df251d7f the fulfil
 * passes no callback, so its started record never learns the id: a fulfil killed mid-submit, made offline, or whose memo
 * cannot be read can only be judged by the Round 8 height window.
 *
 * Drives the real fulfil, the real use case and repository, and the real createStoreAndSubmit over a recording
 * broadcaster ([FulfilStoredIdsHarness] answers recordFulfilStored). When the broadcaster is asked to submit, the record
 * must already hold the txid and its expiry. When the record cannot be written, nothing is submitted, the paid marker
 * stays (Pay stays hidden) and the notice never says "Payment sent". All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilRecordsIdsBeforeSubmitTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5recordbeforepeer1"
        const val AMOUNT = 1_000L
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
    fun `the payment's txid and expiry are on the started record before the first submit`() =
        runBlocking {
            val requestId = "synthetic-f5-p5-request-a"
            val h = FulfilStoredIdsHarness(PEER)
            val seam = StoringSeam(PEER, h, requestId, seed = 0x51)
            val vm = h.viewModel(seam, requestId, AMOUNT)
            PaymentHarness.setBlockHeight(vm, H)

            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("the payment was not submitted once", listOf(seam.legId), seam.broadcaster.submitted())
            assertEquals(
                "the txid and expiry were not on the started record when the payment was submitted",
                listOf(listOf(seam.legId) to mapOf(seam.legId to EXPIRY)),
                seam.recordAtSubmit.toList(),
            )
            assertTrue("fixture: the request is marked paid", requestId in h.prefs.paid)
            assertEquals("fixture sanity", emptyList<String>(), h.prefs.unexpectedCalls)
        }

    @Test
    fun `a record that cannot be written submits nothing and keeps the request paid`() =
        runBlocking {
            val requestId = "synthetic-f5-p5-request-b"
            val h = FulfilStoredIdsHarness(PEER)
            h.recordFails = true
            val seam = StoringSeam(PEER, h, requestId, seed = 0x52)
            val vm = h.viewModel(seam, requestId, AMOUNT)
            PaymentHarness.setBlockHeight(vm, H)

            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("the fulfil did not try to record the stored payment", 1, h.recordCalls.get())
            assertEquals("a payment was submitted without its record", emptyList<String>(), seam.broadcaster.submitted())
            assertTrue("the paid marker was withdrawn over a stored payment", requestId in h.prefs.paid)
            val state = PaymentHarness.sendMessageState(vm)
            val message =
                when (state) {
                    is SendMessageState.Success -> state.label
                    is SendMessageState.Error -> state.message
                    else -> "$state"
                }
            assertNotEquals("the notice says Payment sent", ChatSendCopy.PAYMENT_SENT, message)
            assertTrue("the notice does not ask to check the transactions: $message", "check your transactions" in message)
        }

    /** The real createStoreAndSubmit over a one-leg [RecordingBroadcaster] that reads the record at the submit. */
    private class StoringSeam(
        private val peer: String,
        private val h: FulfilStoredIdsHarness,
        private val requestId: String,
        seed: Int,
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        private val leg = RecordingBroadcaster.leg(seed = seed, expiryHeight = EXPIRY)
        val legId: String = leg.txIdString()
        val broadcaster = RecordingBroadcaster(listOf(leg))
        val recordAtSubmit: MutableList<Pair<List<String>, Map<String, Long>>> = Collections.synchronizedList(mutableListOf())

        init {
            broadcaster.answerSubmit(legId) { transaction ->
                recordAtSubmit += h.recorded[requestId] ?: (emptyList<String>() to emptyMap())
                TransactionSubmitResult.Success(transaction.txId)
            }
        }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal = syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-fulfil-memo")

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
