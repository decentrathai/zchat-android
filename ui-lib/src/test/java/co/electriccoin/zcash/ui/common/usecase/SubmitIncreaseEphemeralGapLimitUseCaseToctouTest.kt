package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import kotlin.test.Test

/**
 * ZC-101 (MAJOR, money-loss) — the SURVIVING use-case-level race, part 2:
 * [SubmitIncreaseEphemeralGapLimitUseCase] (the ephemeral gap-limit bump flow).
 *
 * The first ZC-101 fix made `ZashiProposalRepository.submit(proposal)` settle the proposal PASSED
 * to it, but this use case still CAPTURES that proposal from the process-wide singleton
 * `transactionProposal` cell AFTER the biometric await — and even later, inside a detached scope:
 *
 *   invoke():
 *     1. biometricRepository.requestBiometrics(..)     <- SUSPENDS while the user authenticates
 *     2. submitZashiProposal() launches into a detached scope, which then does
 *          checkNotNull(zashiProposalRepository.transactionProposal.value)   <- reads the SHARED
 *          CELL *after* the prompt (and after navigation)
 *          zashiProposalRepository.submit(captured)
 *
 * The gap-limit proposal A was created by CreateIncreaseEphemeralGapLimitProposalUseCase before
 * the confirm screen. During the biometric window an inbound dust auto-send (KEX-ack, read
 * receipt, group control memo) overwrites the cell with its own proposal B. Step 2 then captures
 * B: the user's biometric authorizes THE DUST ACK, the gap-limit bump silently never happens
 * (subsequent ephemeral-address sends keep failing), and the flow reports success.
 *
 * INVARIANT ENCODED HERE: the authorized gap-limit flow must settle the proposal created FOR that
 * flow — regardless of what the shared cell holds after the biometric.
 *
 * Same deterministic harness as SubmitProposalUseCaseToctouTest: real use case, real
 * ZashiProposalRepositoryImpl, biometric fake that genuinely suspends. At HEAD the post-biometric
 * `transactionProposal.value` capture reads B and settles it: RED.
 *
 * All addresses/memos/keys/URIs are SYNTHETIC placeholders — nothing here parses or validates.
 */
class SubmitIncreaseEphemeralGapLimitUseCaseToctouTest {

    private class Harness(
        val dataSource: Zc101RecordingProposalDataSource,
        val repository: ZashiProposalRepositoryImpl,
        val biometrics: Zc101SuspendingBiometricRepository,
        val useCase: SubmitIncreaseEphemeralGapLimitUseCase,
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
        val useCase =
            SubmitIncreaseEphemeralGapLimitUseCase(
                navigationRouter = Zc101RecordingNavigationRouter(),
                accountDataSource = Zc101FakeAccountDataSource(account),
                zashiProposalRepository = repository,
                biometricRepository = biometrics,
                ephemeralAddressRepository = Zc101FakeEphemeralAddressRepository(),
            )
        return Harness(dataSource, repository, biometrics, useCase)
    }

    /** Replays CreateIncreaseEphemeralGapLimitProposalUseCase's creation step: cell := gap-limit A. */
    private suspend fun createGapLimitProposal(h: Harness) =
        h.repository.createProposal(
            ZecSend(
                destination = WalletAddress.Transparent.new("tsynthetic-ephemeral-self"),
                amount = Zatoshi(100),
                memo = Memo(""),
                proposal = null,
            )
        )

    /**
     * RED at HEAD. The deterministic interleaving of the surviving race:
     *
     *   1. CreateIncreaseEphemeralGapLimitProposalUseCase created gap-limit proposal A (cell := A);
     *   2. the user confirms — SubmitIncreaseEphemeralGapLimitUseCase parks INSIDE the biometric
     *      await;
     *   3. biometric window: an inbound dust auto-ack creates proposal B on the same singleton
     *      repository (cell := B — the overwrite);
     *   4. the user authenticates THE GAP-LIMIT BUMP;
     *   5. the detached submit path captures `transactionProposal.value` (now B) and settles it.
     *
     * The invariant: settlement must receive A. At HEAD the post-biometric capture reads B — the
     * dust ack settles, the gap-limit bump is silently dropped, and the flow reports success.
     */
    @Test
    fun `gap-limit path settles the proposal created for THIS flow even if dust overwrites the cell during the biometric`() =
        runBlocking {
            val h = harness()

            val gapLimitA = syntheticGapLimitProposal()
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")
            h.dataSource.preparedRegular = gapLimitA
            h.dataSource.preparedByUri[ZC101_DUST_ACK_URI] = dustB

            // 1. The gap-limit flow created ITS proposal (cell := A).
            val created = createGapLimitProposal(h)
            assertSame("fixture sanity: repository must return the gap-limit proposal", gapLimitA, created)

            // 2. The user confirms: the use case parks inside the biometric await.
            val submitJob = launch(Dispatchers.Default) { h.useCase(gapLimitA) }
            withTimeout(AWAIT_MS) { h.biometrics.promptShown.await() }

            // 3. Biometric window: an inbound dust auto-send (KEX-ack/read-receipt/group memo)
            //    creates its own proposal on the SAME process-wide singleton — overwriting the cell.
            h.repository.createZip321Proposal(ZC101_DUST_ACK_URI, allowMultiPayment = true)
            assertSame(
                "mechanism sanity: the singleton cell now holds the dust proposal",
                dustB,
                h.repository.transactionProposal.value,
            )

            // 4. The user authenticates THE GAP-LIMIT BUMP.
            h.biometrics.approve()
            submitJob.join()

            // 5. Settlement runs in the use case's detached scope — await it.
            val settled = withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }

            // INVARIANT: exactly one settlement, and it must be the gap-limit proposal A.
            assertEquals("exactly one proposal must reach settlement", 1, h.dataSource.submittedProposals.size)
            assertEquals(
                "the settled proposal must be the one created FOR the flow the biometric authorized — " +
                    "capturing the shared cell AFTER the prompt settles the dust ack, silently drops " +
                    "the gap-limit bump, and leaves ephemeral-address sends failing",
                ZC101_LABEL_INTENDED_A,
                describeSettled(settled, gapLimitA, dustB),
            )
        }

    /**
     * Control (GREEN at HEAD): with no interleaved creation during the biometric window, the use
     * case settles the just-created gap-limit proposal. Proves the harness wiring is sound, so the
     * regression above fails ONLY because of the overwrite, not because of the fakes.
     */
    @Test
    fun `control - without interleaving the gap-limit path settles the just-created proposal`() =
        runBlocking {
            val h = harness()

            val gapLimitA = syntheticGapLimitProposal()
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")
            h.dataSource.preparedRegular = gapLimitA

            createGapLimitProposal(h)

            val submitJob = launch(Dispatchers.Default) { h.useCase(gapLimitA) }
            withTimeout(AWAIT_MS) { h.biometrics.promptShown.await() }
            h.biometrics.approve()
            submitJob.join()

            val settled = withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }
            assertEquals(1, h.dataSource.submittedProposals.size)
            assertEquals(ZC101_LABEL_INTENDED_A, describeSettled(settled, gapLimitA, dustB))
        }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
