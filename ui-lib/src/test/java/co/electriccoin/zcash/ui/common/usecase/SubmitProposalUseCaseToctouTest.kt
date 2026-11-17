package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import kotlin.test.Test

/**
 * ZC-101 (HIGH, money-loss) — the SURVIVING use-case-level race, part 1: [SubmitProposalUseCase]
 * (the primary regular-send + swap confirm path).
 *
 * The first ZC-101 fix made `ZashiProposalRepository.submit(proposal)` settle the proposal PASSED
 * to it (pinned by ZashiProposalRepositoryToctouTest — do not touch). But the use case still
 * CAPTURES that proposal from the process-wide singleton `transactionProposal` cell AFTER the
 * biometric await:
 *
 *   invoke():
 *     1. biometricRepository.requestBiometrics(..)   <- SUSPENDS while the user authenticates
 *     2. zashiProposalRepository.getTransactionProposal()
 *          = transactionProposal.filterNotNull().first()   <- reads the SHARED CELL *after* 1.
 *     3. submitZashiProposal(captured)               <- settles whatever step 2 captured
 *
 * During the biometric window any inbound-triggered dust auto-send (KEX-ack, read receipt, group
 * control memo — all directSubmit, no biometric gate of their own) overwrites the cell with its own
 * proposal B. Step 2 then captures B, and the user's biometric authorizes THE DUST ACK: B is
 * broadcast, the UI reports success, and the user's real payment A silently never reaches the
 * network. Passing the captured proposal to submit() is useless if the CAPTURE itself is late.
 *
 * INVARIANT ENCODED HERE: the send the user confirmed and authorized must settle the proposal that
 * was created FOR THAT SEND — regardless of what the shared cell holds after the biometric.
 *
 * This test drives the REAL SubmitProposalUseCase + REAL ZashiProposalRepositoryImpl with a
 * biometric fake that genuinely suspends, replaying the interleaving deterministically:
 * create A -> park on biometric -> dust B overwrites cell -> authenticate -> observe settlement.
 * At HEAD the use case captures the cell after the prompt and settles B: RED.
 *
 * All addresses/memos/keys/URIs are SYNTHETIC placeholders — nothing here parses or validates.
 */
class SubmitProposalUseCaseToctouTest {

    private class Harness(
        val dataSource: Zc101RecordingProposalDataSource,
        val repository: ZashiProposalRepositoryImpl,
        val biometrics: Zc101SuspendingBiometricRepository,
        val useCase: SubmitProposalUseCase,
    )

    private suspend fun harness(): Harness {
        val account = syntheticZashiAccount()
        val dataSource = Zc101RecordingProposalDataSource()
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = Zc101FakeAccountDataSource(account),
                proposalDataSource = dataSource,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val biometrics = Zc101SuspendingBiometricRepository()
        val metadataRepository = Zc101FakeMetadataRepository()
        val useCase =
            SubmitProposalUseCase(
                navigationRouter = Zc101RecordingNavigationRouter(),
                accountDataSource = Zc101FakeAccountDataSource(account),
                zashiProposalRepository = repository,
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                biometricRepository = biometrics,
                swapRepository = Zc101FakeSwapRepository(),
                metadataRepository = metadataRepository,
                processSwapTransaction =
                    ProcessSwapTransactionUseCase(
                        metadataRepository = metadataRepository,
                        swapDataSource = Zc101FakeSwapDataSource(),
                    ),
                prefillSend = PrefillSendUseCase(),
            )
        return Harness(dataSource, repository, biometrics, useCase)
    }

    /**
     * RED at HEAD. The deterministic interleaving of the surviving race:
     *
     *   1. the confirm screen created the user's payment proposal A (cell := A);
     *   2. the user taps Send — SubmitProposalUseCase parks INSIDE the biometric await;
     *   3. biometric window: an inbound dust auto-ack creates proposal B on the same singleton
     *      repository (cell := B — the overwrite);
     *   4. the user authenticates THE PAYMENT;
     *   5. the use case captures the cell (now B) and settles it.
     *
     * The invariant: settlement must receive A — the proposal created for the send the user
     * authorized. At HEAD the post-biometric capture reads B and the dust ack settles instead.
     */
    @Test
    fun `send path settles the proposal created for THIS send even if dust overwrites the cell during the biometric`() =
        runBlocking {
            val h = harness()

            val paymentA = syntheticZip321Proposal("usynthetic-payment-peer", amount = 5_000_000L, memo = "synthetic-payment")
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")
            h.dataSource.preparedByUri[ZC101_PAYMENT_URI] = paymentA
            h.dataSource.preparedByUri[ZC101_DUST_ACK_URI] = dustB

            // 1. The confirm screen created the user's payment proposal (cell := A).
            val created = h.repository.createZip321Proposal(ZC101_PAYMENT_URI, allowMultiPayment = true)
            assertSame("fixture sanity: repository must return the payment proposal", paymentA, created)

            // 2. The user taps Send: the use case parks inside the biometric await.
            val submitJob = launch(Dispatchers.Default) { h.useCase(paymentA) }
            withTimeout(AWAIT_MS) { h.biometrics.promptShown.await() }

            // 3. Biometric window: an inbound dust auto-send (KEX-ack/read-receipt/group memo)
            //    creates its own proposal on the SAME process-wide singleton — overwriting the cell.
            h.repository.createZip321Proposal(ZC101_DUST_ACK_URI, allowMultiPayment = true)
            assertSame(
                "mechanism sanity: the singleton cell now holds the dust proposal",
                dustB,
                h.repository.transactionProposal.value,
            )

            // 4. The user authenticates THE PAYMENT.
            h.biometrics.approve()
            submitJob.join()

            // 5. Settlement runs in the use case's detached scope — await it.
            val settled = withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }

            // INVARIANT: exactly one settlement, and it must be the payment proposal A.
            assertEquals("exactly one proposal must reach settlement", 1, h.dataSource.submittedProposals.size)
            assertEquals(
                "the settled proposal must be the one created FOR the send the biometric authorized — " +
                    "capturing the shared cell AFTER the prompt settles the dust ack and silently " +
                    "drops the user's payment while reporting success",
                ZC101_LABEL_INTENDED_A,
                describeSettled(settled, paymentA, dustB),
            )
        }

    /**
     * Control (GREEN at HEAD): with no interleaved creation during the biometric window, the use
     * case settles the just-created proposal. Proves the harness wiring — the biometric park, the
     * detached-submit await, the recording seam — is sound, so the regression above fails ONLY
     * because of the overwrite, not because of the fakes.
     */
    @Test
    fun `control - without interleaving the send path settles the just-created proposal`() =
        runBlocking {
            val h = harness()

            val paymentA = syntheticZip321Proposal("usynthetic-payment-peer", amount = 5_000_000L, memo = "synthetic-payment")
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")
            h.dataSource.preparedByUri[ZC101_PAYMENT_URI] = paymentA

            h.repository.createZip321Proposal(ZC101_PAYMENT_URI, allowMultiPayment = true)

            val submitJob = launch(Dispatchers.Default) { h.useCase(paymentA) }
            withTimeout(AWAIT_MS) { h.biometrics.promptShown.await() }
            h.biometrics.approve()
            submitJob.join()

            val settled = withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }
            assertEquals(1, h.dataSource.submittedProposals.size)
            assertEquals(ZC101_LABEL_INTENDED_A, describeSettled(settled, paymentA, dustB))
        }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
