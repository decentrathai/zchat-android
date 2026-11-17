package co.electriccoin.zcash.ui.common.repository

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
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import kotlin.test.Test

/**
 * DOUBLE-PAY (money loss) — RED regression, repository half.
 *
 * `ProposalDataSourceImpl.submitTransactionInternal` (ProposalDataSource.kt:395-455) broadcasts the
 * transaction first (line 400), classifies the authoritative [SubmitResult] (lines 440-449), and only
 * THEN — on the same unguarded path, before the result is returned — calls
 * `synchronizer.refreshTransactions()` and `synchronizer.refreshAllBalances()` (lines 451-452). There
 * is no try/catch around those two calls, so anything they throw (derived-data DB lock/IO, a closed
 * wallet, a JNI error out of `CompactBlockProcessor.refreshWalletSummary`) DISCARDS the computed
 * result and leaves `submitTransaction` by the exception path — indistinguishable, one layer up, from
 * a transaction that never left the wallet.
 *
 * Both repositories then fabricate the worst possible answer out of that exception:
 *
 *   * [ZashiProposalRepositoryImpl.submit]      (ZashiProposalRepository.kt:230-239)
 *   * [KeystoneProposalRepositoryImpl.submit]   (KeystoneProposalRepository.kt:270-278)
 *
 * both `catch (e: Exception)` and publish `SubmitResult.Failure(txIds = emptyList(), code = 0, ...)`
 * into `submitState`. `TransactionProgressVM` (lines 66-67, 183-220) renders exactly that as
 * **"Send Failed — There was an error attempting to send coins. Try it again, please."**, with the
 * "View transaction" button suppressed because the catch emptied `txIds`, and with both Back and
 * Close routed to `onBackToSendForm` → `cancelKeystoneProposalFlow(clearSendForm = false)` → a Send
 * form still holding the same recipient and amount. The user is told to try again, and one tap pays
 * the same money a second time.
 *
 * INVARIANT ENCODED HERE: the repository cannot know whether an exception out of `submitTransaction`
 * arrived before or after the broadcast, so it must never resolve that ambiguity as "failed". A
 * transaction the SDK has already broadcast must never reach the user as `SubmitResult.Failure` —
 * the "Send Failed / Try it again, please" screen. Ambiguity has to be reported as "sent — check
 * your history" (the existing [ZashiProposalRepository.submitOrchardSweep] KDoc, lines 85-88, states
 * this mechanism verbatim for the sweep; it was never applied to `submit`).
 *
 * The tests drive the REAL repositories with a hand-written [ProposalDataSource] whose
 * `submitTransaction` RECORDS the broadcast and only then throws — the exact shape of the
 * post-broadcast refresh failure, injected at the one seam that is fakeable on the JVM
 * (`ProposalDataSourceImpl` itself casts to the `final` `SdkSynchronizer` and cannot be subclassed).
 *
 * All addresses/memos/keys/URIs/PCZT bytes are SYNTHETIC placeholders — nothing parses or validates.
 */
class PostBroadcastNeverReportedFailedTest {
    // ── Zashi lane: ordinary wallet Send, and the chat Pay / Send-payment paths ──────────────────

    /**
     * RED at HEAD. The SDK broadcast the payment; the balance refresh that runs afterwards threw.
     * At HEAD `submit` publishes `SubmitResult.Failure(txIds = [])`, i.e. "Send Failed — try it
     * again, please" over money that has already left the wallet.
     */
    @Test
    fun `a broadcast Zashi payment is never published as a Send-Failed result`() =
        runBlocking {
            val dataSource = PostBroadcastFailingDataSource(failAfterBroadcast = true)
            val repository = zashiRepository(dataSource)
            val proposal = syntheticZip321Proposal(SYNTHETIC_PEER, amount = 5_000_000L, memo = "synthetic-payment")

            val outcome = runCatching { repository.submit(proposal) }

            assertEquals(
                "fixture sanity: the SDK must have broadcast before the refresh threw",
                listOf(SYNTHETIC_TXID),
                dataSource.broadcasts
            )
            assertEquals(
                "submit() [outcome: ${describe(outcome)}] published a Send-Failed result for a payment " +
                    "the SDK HAD broadcast. The post-broadcast balance refresh threw, the catch at " +
                    "ZashiProposalRepository.kt:230 turned it into SubmitResult.Failure(txIds = []), and " +
                    "TransactionProgressVM renders that as \"Send Failed / Try it again, please\" with no " +
                    "transaction link and Back/Close returning to a still-populated Send form — one tap " +
                    "pays the same money twice. An exception here is AMBIGUOUS (the refresh runs after the " +
                    "broadcast) and must be reported as \"sent — check your history\", never as failed.",
                NO_FAILURE,
                describePublished(repository.submitState.value)
            )
        }

    /**
     * Control, GREEN at HEAD and after the fix: a clean submit still publishes Success. Pins that the
     * fix cannot be satisfied by suppressing the result altogether.
     */
    @Test
    fun `control - a clean Zashi submit still publishes Success`() =
        runBlocking {
            val dataSource = PostBroadcastFailingDataSource(failAfterBroadcast = false)
            val repository = zashiRepository(dataSource)
            val proposal = syntheticZip321Proposal(SYNTHETIC_PEER, amount = 5_000_000L, memo = "synthetic-payment")

            val result = repository.submit(proposal)

            assertEquals(SubmitResult.Success(listOf(SYNTHETIC_TXID)), result)
            assertEquals(
                SubmitProposalState.Result(SubmitResult.Success(listOf(SYNTHETIC_TXID))),
                repository.submitState.value
            )
        }

    // ── Keystone lane: the hardware-wallet Send path ─────────────────────────────────────────────

    /**
     * RED at HEAD. Same defect, same shape, one repository over:
     * KeystoneProposalRepository.kt:270-278. `SubmitKSProposalUseCase` swallows the rethrow, so the
     * user's only feedback is the same "Send Failed / Try it again, please" screen fed from
     * `submitState`, and the same non-clearing route back to a populated Send form.
     */
    @Test
    fun `a broadcast Keystone payment is never published as a Send-Failed result`() =
        runBlocking {
            val dataSource = PostBroadcastFailingDataSource(failAfterBroadcast = true)
            val repository = keystoneRepository(dataSource)
            val proposal = repository.openSigningSession(dataSource)

            val outcome = runCatching { repository.submit(proposal) }

            assertEquals(
                "fixture sanity: the SDK must have broadcast before the refresh threw",
                listOf(SYNTHETIC_TXID),
                dataSource.broadcasts
            )
            assertEquals(
                "submit() [outcome: ${describe(outcome)}] published a Send-Failed result for a Keystone " +
                    "payment the SDK HAD broadcast. The post-broadcast balance refresh threw and the catch " +
                    "at KeystoneProposalRepository.kt:270 turned it into SubmitResult.Failure(txIds = []) — " +
                    "\"Send Failed / Try it again, please\" over money that has already left the wallet.",
                NO_FAILURE,
                describePublished(repository.submitState.value)
            )
        }

    /** Control, GREEN at HEAD and after the fix: a clean Keystone submit still publishes Success. */
    @Test
    fun `control - a clean Keystone submit still publishes Success`() =
        runBlocking {
            val dataSource = PostBroadcastFailingDataSource(failAfterBroadcast = false)
            val repository = keystoneRepository(dataSource)
            val proposal = repository.openSigningSession(dataSource)

            val result = repository.submit(proposal)

            assertEquals(SubmitResult.Success(listOf(SYNTHETIC_TXID)), result)
            assertEquals(
                SubmitProposalState.Result(SubmitResult.Success(listOf(SYNTHETIC_TXID))),
                repository.submitState.value
            )
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun zashiRepository(dataSource: ProposalDataSource) =
        ZashiProposalRepositoryImpl(
            accountDataSource = FakeZashiAccountDataSource(syntheticZashiAccount()),
            proposalDataSource = dataSource,
            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
        )

    private suspend fun keystoneRepository(dataSource: ProposalDataSource) =
        KeystoneProposalRepositoryImpl(
            accountDataSource = FakeSelectedKeystoneAccountDataSource(syntheticKeystoneAccount()),
            proposalDataSource = dataSource
        )

    /**
     * Brings the Keystone repository to the state `submit` requires: a proposal, its PCZT, the
     * background proofs job finished, and the hardware device's signed PCZT scanned back. The only
     * production writer of `pcztWithSignatures` is `parsePCZT(ur)`, which decodes through the
     * Keystone SDK and cannot run on the JVM, so the scan result is injected into that exact field
     * (the same technique as KeystonePcztBindingToctouTest).
     */
    private suspend fun KeystoneProposalRepositoryImpl.openSigningSession(
        dataSource: PostBroadcastFailingDataSource
    ): Zip321TransactionProposal {
        val proposal = createZip321Proposal(SYNTHETIC_KEYSTONE_URI, allowMultiPayment = true)
        createPCZTFromProposal()
        withTimeout(AWAIT_MS) { dataSource.proofsReady.await() }
        val field = KeystoneProposalRepositoryImpl::class.java.getDeclaredField("pcztWithSignatures")
        field.isAccessible = true
        field.set(this, dataSource.signedPczt)
        return proposal
    }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}

// ── Constants (all synthetic) ────────────────────────────────────────────────────────────────────

/** Bare-alphanumeric and >= 20 chars, so nothing downstream rejects it on shape. */
private const val SYNTHETIC_PEER = "usyntheticpaymentpeer0000000"

private const val SYNTHETIC_KEYSTONE_URI = "zcash:usynthetickeystonepeer?amount=0.05&memo=c3ludGhldGlj"

private const val SYNTHETIC_TXID = "synthetic-broadcast-txid"

/**
 * What the post-broadcast step throws. Stands in for `synchronizer.refreshAllBalances()` /
 * `refreshTransactions()` at ProposalDataSource.kt:451-452 — the calls that run AFTER the broadcast
 * and outside any try/catch.
 */
private const val REFRESH_FAILURE = "synthetic post-broadcast balance refresh failure"

private const val NO_FAILURE = "no Send-Failed result was published"

// ── Assertion vocabulary ─────────────────────────────────────────────────────────────────────────

private fun describePublished(state: SubmitProposalState?): String {
    val published = (state as? SubmitProposalState.Result)?.submitResult
    return if (published is SubmitResult.Failure) {
        "SubmitResult.Failure(txIds = ${published.txIds}) — rendered as \"Send Failed / Try it again, please\""
    } else {
        NO_FAILURE
    }
}

private fun describe(outcome: Result<SubmitResult>): String =
    outcome.fold(
        onSuccess = { "returned $it" },
        onFailure = { "threw ${it::class.simpleName}: ${it.message}" }
    )

// ── Fakes (hand-written; no mockk, no Robolectric) ───────────────────────────────────────────────

/**
 * The settlement seam. Both `submitTransaction` overloads RECORD the broadcast and, when
 * [failAfterBroadcast], throw only afterwards — the exact ordering of
 * `ProposalDataSourceImpl.submitTransactionInternal`, where the SDK has already put the transaction
 * on the wire before the refresh calls that can throw.
 */
private class PostBroadcastFailingDataSource(
    private val failAfterBroadcast: Boolean
) : ProposalDataSource {
    /** Every transaction the SDK actually put on the wire: money that has left the wallet. */
    val broadcasts = mutableListOf<String>()

    val keystoneProposal =
        runBlocking { syntheticZip321Proposal("usynthetickeystonepeer00000", amount = 5_000_000L, memo = "synthetic") }

    val pczt = Pczt(byteArrayOf(0x11, 0x11, 0x11))
    val signedPczt = Pczt(byteArrayOf(0x1A, 0x1A, 0x1A))

    /** Completed when the repository's background proofs job has proofed the PCZT. */
    val proofsReady = CompletableDeferred<Unit>()

    private fun broadcastThen(): SubmitResult {
        broadcasts += SYNTHETIC_TXID
        if (failAfterBroadcast) throw RuntimeException(REFRESH_FAILURE)
        return SubmitResult.Success(txIds = listOf(SYNTHETIC_TXID))
    }

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult = broadcastThen()

    override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
        broadcastThen()

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal = keystoneProposal

    override suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt = pczt

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt {
        // The repository proofs a CLONE — match by content, not identity.
        if (pczt.toByteArray().contentEquals(this.pczt.toByteArray())) proofsReady.complete(Unit)
        return pczt
    }

    // Unused in these scenarios.
    override suspend fun createProposal(account: WalletAccount, send: ZecSend): RegularTransactionProposal =
        error("not used in this test")

    override suspend fun createExactInputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote
    ): ExactInputSwapTransactionProposal = error("not used in this test")

    override suspend fun createExactOutputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote
    ): ExactOutputSwapTransactionProposal = error("not used in this test")

    override suspend fun createShieldProposal(account: WalletAccount): ShieldTransactionProposal =
        error("not used in this test")

    override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
        error("not used in this test")

    override suspend fun probeFee(account: WalletAccount, send: ZecSend): FeeProbe = error("not used in this test")

    override suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe =
        error("not used in this test")

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = error("not used in this test")
}

private suspend fun syntheticZashiAccount(): ZashiAccount {
    val zeroBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
    return ZashiAccount(
        // The SDK's own public test fixture — all values are the fixture's synthetic defaults.
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), zeroBalance),
        sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), zeroBalance),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
        isSelected = true
    )
}

private suspend fun syntheticKeystoneAccount(): KeystoneAccount {
    val zeroBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
    return KeystoneAccount(
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone-self"), zeroBalance),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone-self"), Zatoshi(0)),
        isSelected = true
    )
}

private class FakeZashiAccountDataSource(
    private val account: ZashiAccount
) : AccountDataSource {
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
        index: Long
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit
}

private class FakeSelectedKeystoneAccountDataSource(
    private val account: KeystoneAccount
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
        index: Long
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit
}
