package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import kotlin.test.Test

/**
 * ZC-101 (HIGH, money-loss): TOCTOU on the process-wide singleton proposal cell in
 * [ZashiProposalRepositoryImpl] causes a PHANTOM-SUCCESS PAYMENT DROP.
 *
 * The chat direct-send flow (CreateChunkedMessageProposalUseCase.invoke) does:
 *
 *   1. createZip321Proposal(...)   -> stores the user's ABOVE-DUST payment proposal (A) in the
 *                                     shared `transactionProposal` MutableStateFlow cell;
 *   2. biometric spend-auth        -> SUSPENDS for as long as the user takes to authenticate;
 *   3. submit()                    -> RE-READS the shared cell (no proposal argument, no lock)
 *                                     and submits whatever is in it.
 *
 * During the biometric window (step 2) any inbound-triggered dust auto-send — a KEX-ack, a read
 * receipt, a group control memo (all directSubmit=true, skipNavigation=true, dust amount so they
 * carry NO biometric gate of their own) — calls createProposalInternal on the SAME repository
 * singleton and OVERWRITES the cell with its own dust proposal (B). The user's biometric then
 * authorizes B: the dust memo is submitted (and the UI reports success), while the user's real
 * payment proposal A is silently dropped and never reaches the network.
 *
 * INVARIANT ENCODED HERE: a value send must submit/authorize the proposal that was created FOR
 * THAT SEND — even if a concurrent proposal creation overwrites the shared cell before submit.
 *
 * This test exercises the REAL [ZashiProposalRepositoryImpl] with a hand-written recording
 * [ProposalDataSource] fake, replaying the exact repository call sequence of the race
 * deterministically (create A -> create B overwrites -> submit for the A-send) and observing
 * which proposal object reaches ProposalDataSource.submitTransaction — the settlement seam, which
 * is stable across any fix. At HEAD, submit() reads the cell and settles B: RED.
 *
 * NOTE: the higher-fidelity interleaving through CreateChunkedMessageProposalUseCase.invoke (real
 * biometric suspension via a CompletableDeferred fake) is not JVM-testable — invoke's ZIP-321 URI
 * builder uses android.util.Base64, which throws on the JVM without Robolectric (see the existing
 * ChunkedProposalSendTest header). The repository seam below is the exact sequence that use case
 * performs around the biometric await.
 *
 * All addresses/memos/keys below are SYNTHETIC placeholders — nothing here parses or validates.
 */
class ZashiProposalRepositoryToctouTest {

    // Synthetic ZIP-321 URIs: the fake keys its prepared results off these exact strings.
    private companion object {
        const val PAYMENT_URI = "zcash:usynthetic-payment-peer?amount=0.05&memo=c3ludGhldGljLXBheW1lbnQ"
        const val DUST_ACK_URI = "zcash:usynthetic-dust-peer?amount=0.00001&memo=c3ludGhldGljLWFjaw"

        const val LABEL_PAYMENT_A = "payment-A (the user's above-dust send)"
        const val LABEL_DUST_B = "dust-B (the concurrent inbound auto-ack)"

        /**
         * Each call parses a minimal, hand-encoded proposal protobuf -> a DISTINCT Proposal object
         * (the SDK Proposal doesn't override equals, so object identity distinguishes A from B).
         * Wire bytes: field 1 (protoVersion) varint 1; field 2 (feeRule) varint 3 (ZIP-317) — the
         * only field ProposalUnsafe's init validates.
         */
        fun newDistinctSdkProposal(): Proposal =
            Proposal.fromByteArray(byteArrayOf(0x08, 0x01, 0x10, 0x03))
    }

    // ── Hand-written fakes (no mockk) ────────────────────────────────────────────────────────────

    /**
     * Returns a pre-built, distinct proposal per ZIP-321 URI and RECORDS which [Proposal] object
     * reaches the settlement call. Identity (===) is meaningful: the SDK Proposal does not override
     * equals, and the repository must hand the datasource the very object created for the send.
     */
    private class RecordingProposalDataSource : ProposalDataSource {
        val preparedByUri = mutableMapOf<String, Zip321TransactionProposal>()
        val submittedProposals = mutableListOf<Proposal>()

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal =
            preparedByUri.getValue(zip321Uri)

        override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult {
            submittedProposals += proposal
            return SubmitResult.Success(txIds = listOf("synthetic-txid"))
        }

        // Unused in this scenario.
        override suspend fun createProposal(account: WalletAccount, send: ZecSend): RegularTransactionProposal =
            error("not used in this test")

        override suspend fun createExactInputProposal(
            account: WalletAccount,
            send: ZecSend,
            quote: SwapQuote,
        ): ExactInputSwapTransactionProposal = error("not used in this test")

        override suspend fun createExactOutputProposal(
            account: WalletAccount,
            send: ZecSend,
            quote: SwapQuote,
        ): ExactOutputSwapTransactionProposal = error("not used in this test")

        override suspend fun createShieldProposal(account: WalletAccount): ShieldTransactionProposal =
            error("not used in this test")

        override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
            error("not used in this test")

        override suspend fun probeFee(account: WalletAccount, send: ZecSend): FeeProbe =
            error("not used in this test")

        override suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe =
            error("not used in this test")

        override suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt =
            error("not used in this test")

        override suspend fun addProofsToPczt(pczt: Pczt): Pczt = error("not used in this test")

        override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
            error("not used in this test")

        override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = error("not used in this test")
    }

    private class FakeAccountDataSource(private val account: ZashiAccount) : AccountDataSource {
        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
        override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
        override val zashiAccount: Flow<ZashiAccount?> = flowOf(account)

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

        override suspend fun getSelectedAccount(): WalletAccount = account

        override suspend fun getZashiAccount(): ZashiAccount = account

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
    }

    private class FakeZashiSpendingKeyDataSource : ZashiSpendingKeyDataSource {
        // Synthetic key bytes — never derived from any real seed. UnifiedSpendingKey.new() validates
        // through the native Rust backend (not loadable in a plain JVM unit test), so the opaque
        // wrapper is built reflectively; the recording ProposalDataSource never reads it.
        override suspend fun getZashiSpendingKey(): UnifiedSpendingKey {
            val constructor =
                UnifiedSpendingKey::class.java.declaredConstructors.first {
                    it.parameterTypes.singleOrNull() == FirstClassByteArray::class.java
                }
            constructor.isAccessible = true
            return constructor.newInstance(FirstClassByteArray(ByteArray(64) { 0x5A })) as UnifiedSpendingKey
        }
    }

    // ── Fixture builders (all-synthetic SDK models) ──────────────────────────────────────────────

    private suspend fun syntheticZashiAccount(): ZashiAccount {
        val zeroBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return ZashiAccount(
            // The SDK's own public test fixture — all values are the fixture's synthetic defaults.
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), zeroBalance),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), zeroBalance),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }

    private suspend fun syntheticZip321Proposal(destination: String, amount: Long, memo: String) =
        Zip321TransactionProposal(
            destination = WalletAddress.Unified.new(destination),
            amount = Zatoshi(amount),
            memo = Memo(memo),
            proposal = newDistinctSdkProposal(),
        )

    private fun labelOf(
        submitted: Proposal,
        paymentA: Zip321TransactionProposal,
        dustB: Zip321TransactionProposal,
    ): String =
        when {
            submitted === paymentA.proposal -> LABEL_PAYMENT_A
            submitted === dustB.proposal -> LABEL_DUST_B
            else -> "unknown proposal (fixture bug)"
        }

    // ── The ZC-101 regression ────────────────────────────────────────────────────────────────────

    /**
     * RED at HEAD. Replays the biometric-window race deterministically at the repository seam:
     *
     *   1. the user's above-dust payment creates proposal A (cell := A);
     *   2. while the send awaits spend-auth, an inbound dust auto-ack creates proposal B on the
     *      same singleton (cell := B — the overwrite);
     *   3. the authenticated payment path calls submit() FOR THE A-SEND.
     *
     * The invariant: the settlement call must receive A — the proposal created for the send that
     * was authorized. At HEAD submit() re-reads the shared cell and settles B instead: the dust
     * ack is broadcast, the UI reports success, and the user's real payment silently evaporates.
     */
    @Test
    fun `submit settles the proposal created for THIS send even if a dust proposal overwrote the shared cell`() =
        runBlocking {
            val dataSource = RecordingProposalDataSource()
            val repository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource,
                    zashiSpendingKeyDataSource = FakeZashiSpendingKeyDataSource(),
                )

            val paymentA = syntheticZip321Proposal("usynthetic-payment-peer", amount = 5_000_000L, memo = "synthetic-payment")
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")
            dataSource.preparedByUri[PAYMENT_URI] = paymentA
            dataSource.preparedByUri[DUST_ACK_URI] = dustB

            // 1. The user's above-dust payment creates ITS proposal (this send then parks on biometrics).
            val created = repository.createZip321Proposal(PAYMENT_URI, allowMultiPayment = true)
            assertSame("fixture sanity: repository must return the payment proposal", paymentA, created)

            // 2. Biometric window: an inbound dust auto-send (KEX-ack/read-receipt/group memo) creates
            //    its own proposal on the SAME process-wide singleton — overwriting the shared cell.
            repository.createZip321Proposal(DUST_ACK_URI, allowMultiPayment = true)
            assertSame(
                "mechanism sanity: the singleton cell now holds the dust proposal",
                dustB,
                repository.transactionProposal.value,
            )

            // 3. The user's biometric approves THE PAYMENT; the payment path submits its send.
            repository.submit(created)

            // INVARIANT: exactly one settlement, and it must be the payment proposal A.
            assertEquals(
                "exactly one proposal must reach settlement",
                1,
                dataSource.submittedProposals.size,
            )
            val settled = labelOf(dataSource.submittedProposals.single(), paymentA, dustB)
            assertEquals(
                "the settled proposal must be the one created FOR the authorized send — " +
                    "anything else silently drops the user's payment while reporting success",
                LABEL_PAYMENT_A,
                settled,
            )
        }

    /**
     * Control (GREEN at HEAD): with no interleaved creation, submit() settles the just-created
     * proposal. Proves the fixture wiring is sound, so the regression above fails ONLY because of
     * the interleaving — not because of the fakes.
     */
    @Test
    fun `control - without interleaving submit settles the just-created proposal`() =
        runBlocking {
            val dataSource = RecordingProposalDataSource()
            val repository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource,
                    zashiSpendingKeyDataSource = FakeZashiSpendingKeyDataSource(),
                )

            val paymentA = syntheticZip321Proposal("usynthetic-payment-peer", amount = 5_000_000L, memo = "synthetic-payment")
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")
            dataSource.preparedByUri[PAYMENT_URI] = paymentA

            val created = repository.createZip321Proposal(PAYMENT_URI, allowMultiPayment = true)
            repository.submit(created)

            assertEquals(1, dataSource.submittedProposals.size)
            assertEquals(
                LABEL_PAYMENT_A,
                labelOf(dataSource.submittedProposals.single(), paymentA, dustB),
            )
        }
}
