package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
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
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import kotlin.test.Test

/**
 * ZC-102 Facet 2 (denial / lost signing session — no fund loss) — RED regression.
 *
 * On a KEYSTONE-selected account, an inbound dust auto-send (KEX-ack / read receipt / group control
 * memo — all `directSubmit = true, skipNavigation = true`, fired by inbound traffic with no user
 * interaction) DESTROYS the user's in-flight hardware-signing session:
 *
 *   CreateChunkedMessageProposalUseCase.invoke() [dust ack]:
 *     1. createMultiOutputProposal -> KeystoneAccount branch:
 *          keystoneProposalRepository.createZip321Proposal(..)   <- OVERWRITES the singleton
 *          keystoneProposalRepository.createPCZTFromProposal()      proposal cell + proposalPczt
 *     2. directSubmit && KeystoneAccount && skipNavigation:
 *          throw UnsupportedOperationException (no Keystone auto-sign)
 *     3. catch -> keystoneProposalRepository.clear()             <- WIPES proposalPczt,
 *          pcztWithProofs, transactionProposal, pcztWithSignatures and cancels the proofs job
 *
 * The user is meanwhile mid-signing on the hardware device: the proposal/PCZT they are signing
 * lived in that same process-wide singleton. After the wipe, SubmitKSProposalUseCase's
 * `getTransactionProposal()` (`transactionProposal.filterNotNull().first()`) suspends forever, or
 * `submit()` dies with "PCZT with proofs is null" — the signing session is unrecoverable and the
 * user must start over. An attacker (or any peer) can trigger this at will by sending dust that
 * provokes an auto-ack while the victim is signing.
 *
 * INVARIANT ENCODED HERE: a background/inbound dust auto-send must NOT clear or corrupt an
 * in-flight Keystone signing session. After the dust ack fails, the Keystone repository must still
 * hold the USER's proposal and the USER's PCZT.
 *
 * This test drives the REAL CreateChunkedMessageProposalUseCase + REAL
 * KeystoneProposalRepositoryImpl with hand fakes only at the data-source seam. At HEAD the dust
 * ack first overwrites and then clear()s the session: RED.
 *
 * All addresses/memos/URIs/PCZT bytes are SYNTHETIC placeholders — nothing parses or validates.
 */
class KeystoneSigningSessionDustWipeTest {

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private class Harness(
        val dataSource: Zc102KeystonePcztDataSource,
        val keystoneRepository: KeystoneProposalRepositoryImpl,
        val useCase: CreateChunkedMessageProposalUseCase,
    )

    private suspend fun harness(): Harness {
        val account = syntheticKeystoneAccount()
        val accounts = Zc102FakeKeystoneAccountDataSource(account)
        val dataSource = Zc102KeystonePcztDataSource()
        val keystoneRepository =
            KeystoneProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = dataSource,
            )
        val zashiRepository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = dataSource,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = keystoneRepository,
                zashiProposalRepository = zashiRepository,
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = Zc102NoBiometricGate(),
            )
        return Harness(dataSource, keystoneRepository, useCase)
    }

    /** Establishes the in-flight hardware-signing session: proposal + PCZT in the Keystone repo. */
    private suspend fun Harness.openUserSigningSession() {
        keystoneRepository.createZip321Proposal(ZC102_USER_SIGNING_URI, allowMultiPayment = true)
        keystoneRepository.createPCZTFromProposal()
        // The background proofs job has proofed the USER's PCZT — the session is fully in flight,
        // exactly the "scan/sign with the Keystone device" state.
        withTimeout(AWAIT_MS) { dataSource.userProofsReady.await() }
    }

    // ── The regression (RED at HEAD) ─────────────────────────────────────────────────────────────

    /**
     * RED at HEAD. The deterministic sequence of the confirmed field failure:
     *
     *   1. the user opens a Keystone signing session (proposal + PCZT + proofs in the repository)
     *      and walks off to sign on the hardware device;
     *   2. an inbound dust auto-ack fires on the SAME singleton repository
     *      (directSubmit = true, skipNavigation = true, rawMemo = true — the frictionless ack path);
     *   3. the ack path overwrites the cell, then throws (no Keystone auto-sign), and its catch
     *      calls keystoneProposalRepository.clear() — the user's in-flight session is gone.
     *
     * The invariant: after the dust ack fails, the repository must STILL hold the user's proposal
     * and PCZT so the signing session can complete. At HEAD both are wiped to null.
     */
    @Test
    fun `an inbound dust auto-ack must not wipe the in-flight Keystone signing session`() =
        runBlocking {
            val h = harness()
            val userProposal =
                syntheticZip321Proposal("usynthetic-keystone-peer", amount = 5_000_000L, memo = "synthetic-user-payment")
            val dustAck =
                syntheticZip321Proposal(ZC102_DUST_PEER, amount = 1_000L, memo = "synthetic-ack")
            h.dataSource.userSigningProposal = userProposal
            h.dataSource.dustAckProposal = dustAck

            // 1. The in-flight hardware-signing session.
            h.openUserSigningSession()
            assertSame(
                "fixture sanity: the repository must hold the user's signing proposal",
                userProposal,
                h.keystoneRepository.transactionProposal.value,
            )
            assertSame(
                "fixture sanity: the repository must hold the user's session PCZT",
                h.dataSource.userPczt,
                h.keystoneRepository.getProposalPCZT(),
            )

            // 2. While the user is signing on the device, an inbound dust auto-ack fires. It fails
            //    (there is no Keystone auto-sign) — HOW it fails is not pinned here, only that the
            //    failure must not take the user's signing session down with it.
            val ackOutcome =
                runCatching {
                    h.useCase(
                        destinationAddress = ZC102_DUST_PEER,
                        senderAddress = "usynthetic-keystone-self",
                        message = "synthetic-inbound-ack",
                        isFirstMessage = false,
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true,
                    )
                }
            val ackDescription =
                ackOutcome.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
                    ?: "completed without error"

            // 3. INVARIANT: the in-flight signing session SURVIVES the background dust ack.
            assertEquals(
                "the Keystone proposal cell after the inbound dust auto-ack (ack outcome: $ackDescription) — " +
                    "the session the user is signing on the hardware device must survive a background " +
                    "dust send; wiping it strands SubmitKSProposalUseCase.getTransactionProposal() forever",
                LABEL_USER_PROPOSAL,
                describeProposalCell(h.keystoneRepository.transactionProposal.value, userProposal, dustAck),
            )
            assertEquals(
                "the Keystone proposalPczt after the inbound dust auto-ack (ack outcome: $ackDescription) — " +
                    "the PCZT mid-signature on the hardware device must survive a background dust send",
                LABEL_USER_PCZT,
                describePcztCell(h.keystoneRepository.getProposalPCZT(), h.dataSource.userPczt, h.dataSource.dustPczt),
            )
        }

    // ── Control (GREEN at HEAD) ──────────────────────────────────────────────────────────────────

    /**
     * Control: with NO inbound dust ack, the established signing session persists. Proves the
     * harness wiring (real repository, PCZT seam, proofs await) is sound, so the regression above
     * fails ONLY because the dust ack destroyed the session — not because of the fakes.
     */
    @Test
    fun `control - without an inbound dust ack the Keystone signing session persists`() =
        runBlocking {
            val h = harness()
            val userProposal =
                syntheticZip321Proposal("usynthetic-keystone-peer", amount = 5_000_000L, memo = "synthetic-user-payment")
            val dustAck =
                syntheticZip321Proposal(ZC102_DUST_PEER, amount = 1_000L, memo = "synthetic-ack")
            h.dataSource.userSigningProposal = userProposal
            h.dataSource.dustAckProposal = dustAck

            h.openUserSigningSession()

            assertEquals(
                LABEL_USER_PROPOSAL,
                describeProposalCell(h.keystoneRepository.transactionProposal.value, userProposal, dustAck),
            )
            assertEquals(
                LABEL_USER_PCZT,
                describePcztCell(h.keystoneRepository.getProposalPCZT(), h.dataSource.userPczt, h.dataSource.dustPczt),
            )
        }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}

// ── Constants (all synthetic) ────────────────────────────────────────────────────────────────────

/** The user's own Keystone send being signed on the hardware device. Synthetic; never parsed. */
private const val ZC102_USER_SIGNING_URI =
    "zcash:usynthetickeystonepeer?amount=0.05&memo=c3ludGhldGljLXNpZ25pbmc"

/**
 * The inbound peer the dust ack answers. Bare-alphanumeric and >= 20 chars so it passes the
 * ZIP-321 builder's address-shape guard; the built dust URI starts with "zcash:<this address>".
 */
private const val ZC102_DUST_PEER = "usyntheticdustpeer0000000000"

private const val LABEL_USER_PROPOSAL = "the USER's in-flight signing proposal"
private const val LABEL_USER_PCZT = "the USER's session PCZT (awaiting hardware signature)"

private fun describeProposalCell(
    actual: TransactionProposal?,
    user: TransactionProposal,
    dust: TransactionProposal,
): String =
    when {
        actual === user -> LABEL_USER_PROPOSAL
        actual == null -> "null — the signing session was WIPED (keystoneProposalRepository.clear())"
        actual === dust -> "the dust ack's own proposal — the signing session was OVERWRITTEN"
        else -> "an unknown proposal (fixture bug)"
    }

private fun describePcztCell(
    actual: Pczt?,
    user: Pczt,
    dust: Pczt,
): String =
    when {
        actual === user -> LABEL_USER_PCZT
        actual == null -> "null — the session PCZT was WIPED (clear() nulled proposalPczt)"
        actual === dust -> "the dust ack's PCZT — the session PCZT was OVERWRITTEN"
        else -> "an unknown PCZT (fixture bug)"
    }

// ── Fakes (hand-written; no mockk, no Robolectric) ───────────────────────────────────────────────

private suspend fun syntheticKeystoneAccount(): KeystoneAccount {
    val zeroBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
    return KeystoneAccount(
        // The SDK's own public test fixture — all values are the fixture's synthetic defaults.
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone-self"), zeroBalance),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone-self"), Zatoshi(0)),
        isSelected = true,
    )
}

private class Zc102FakeKeystoneAccountDataSource(
    private val account: KeystoneAccount,
) : AccountDataSource {
    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
    override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
    override val zashiAccount: Flow<ZashiAccount?> = flowOf(null)

    override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

    override suspend fun getSelectedAccount(): WalletAccount = account

    override suspend fun getZashiAccount(): ZashiAccount = error("the KEYSTONE account is selected in this test")

    override suspend fun selectAccount(account: Account) = Unit

    override suspend fun selectAccount(account: WalletAccount) = Unit

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit
}

/** The dust auto-ack path must never raise a biometric gate; failing loudly beats hanging. */
private class Zc102NoBiometricGate : BiometricRepository {
    override val allowedAuthenticators: Int = 0

    override fun onBiometricResult(result: BiometricResult) = Unit

    override suspend fun requestBiometrics(request: BiometricRequest) =
        error("the dust auto-ack path must not request biometrics in this test")
}

/**
 * The seam under the REAL KeystoneProposalRepositoryImpl. Maps the two ZIP-321 creations of this
 * scenario (the user's signing session, matched exactly; the dust ack, matched by its destination
 * address — the built URI's amount/memo formatting is irrelevant) to pre-built distinct proposals,
 * and each proposal to a distinct PCZT so the test can tell WHOSE session the singleton holds.
 */
private class Zc102KeystonePcztDataSource : ProposalDataSource {
    lateinit var userSigningProposal: Zip321TransactionProposal
    lateinit var dustAckProposal: Zip321TransactionProposal

    /** Distinct byte payloads: identity AND content distinguish the user's PCZT from the dust one. */
    val userPczt = Pczt(byteArrayOf(0x11, 0x11, 0x11))
    val dustPczt = Pczt(byteArrayOf(0x22, 0x22, 0x22))

    /** Completed when the repository's background proofs job has proofed the USER's PCZT. */
    val userProofsReady = CompletableDeferred<Unit>()

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal =
        when {
            zip321Uri == ZC102_USER_SIGNING_URI -> userSigningProposal
            zip321Uri.contains(ZC102_DUST_PEER) -> dustAckProposal
            else -> error("unexpected ZIP-321 URI in this test")
        }

    override suspend fun createPcztFromProposal(
        account: WalletAccount,
        proposal: Proposal,
    ): Pczt =
        when {
            proposal === userSigningProposal.proposal -> userPczt
            proposal === dustAckProposal.proposal -> dustPczt
            else -> error("unexpected proposal in this test")
        }

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt {
        // The repository proofs a CLONE — match by content, not identity.
        if (pczt.toByteArray().contentEquals(userPczt.toByteArray())) {
            userProofsReady.complete(Unit)
        }
        return pczt
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

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult =
        error("not used in this test")

    override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
        error("not used in this test")

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = error("not used in this test")
}
