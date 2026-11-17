package co.electriccoin.zcash.ui.common.usecase

import androidx.lifecycle.Lifecycle
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
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.EphemeralAddress
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SimpleSwapAsset
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Shared hand-written fakes (no mockk, no Robolectric) for the ZC-101 use-case-level TOCTOU
 * regressions: [SubmitProposalUseCaseToctouTest] and [SubmitIncreaseEphemeralGapLimitUseCaseToctouTest],
 * and for the confirm-screen VM-binding tests (SwapQuoteVMToctouTest, ReviewTransactionVMToctouTest,
 * EphemeralLockVMToctouTest) that pin WHICH proposal instance each confirm screen binds into its
 * confirm closure.
 *
 * The repository-seam test (ZashiProposalRepositoryToctouTest) pins that
 * `ZashiProposalRepository.submit(proposal)` settles the proposal PASSED to it. These fixtures let
 * the tests go one level up and pin WHICH proposal each use case captures: both surviving callsites
 * read the shared singleton `transactionProposal` cell only AFTER the biometric await, so a
 * concurrent proposal creation during the prompt still swaps what settles.
 *
 * All addresses/memos/keys/URIs below are SYNTHETIC placeholders — nothing parses or validates.
 */

internal const val ZC101_PAYMENT_URI = "zcash:usynthetic-payment-peer?amount=0.05&memo=c3ludGhldGljLXBheW1lbnQ"
internal const val ZC101_DUST_ACK_URI = "zcash:usynthetic-dust-peer?amount=0.00001&memo=c3ludGhldGljLWFjaw"

internal const val ZC101_LABEL_INTENDED_A = "proposal-A (the send the user's biometric authorized)"
internal const val ZC101_LABEL_DUST_B = "dust-B (the concurrent inbound auto-ack)"

/**
 * Each call parses a minimal, hand-encoded proposal protobuf -> a DISTINCT Proposal object (the SDK
 * Proposal doesn't override equals, so object identity distinguishes A from B). Wire bytes: field 1
 * (protoVersion) varint 1; field 2 (feeRule) varint 3 (ZIP-317) — the only validated field.
 */
internal fun newDistinctSdkProposal(): Proposal = Proposal.fromByteArray(byteArrayOf(0x08, 0x01, 0x10, 0x03))

internal fun describeSettled(
    settled: Proposal,
    intendedA: TransactionProposal,
    dustB: TransactionProposal,
): String =
    when {
        settled === intendedA.proposal -> ZC101_LABEL_INTENDED_A
        settled === dustB.proposal -> ZC101_LABEL_DUST_B
        else -> "unknown proposal (fixture bug)"
    }

// ── Fixture builders (all-synthetic SDK models) ──────────────────────────────────────────────────

internal suspend fun syntheticZashiAccount(): ZashiAccount {
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

internal suspend fun syntheticZip321Proposal(destination: String, amount: Long, memo: String) =
    Zip321TransactionProposal(
        destination = WalletAddress.Unified.new(destination),
        amount = Zatoshi(amount),
        memo = Memo(memo),
        proposal = newDistinctSdkProposal(),
    )

/** The gap-limit bump: a tiny self-send to the wallet's own ephemeral transparent address. */
internal suspend fun syntheticGapLimitProposal() =
    RegularTransactionProposal(
        destination = WalletAddress.Transparent.new("tsynthetic-ephemeral-self"),
        amount = Zatoshi(100),
        memo = Memo(""),
        proposal = newDistinctSdkProposal(),
    )

// ── Hand-written fakes ───────────────────────────────────────────────────────────────────────────

/**
 * Returns pre-built, distinct proposals and RECORDS which [Proposal] object reaches the settlement
 * call. Identity (===) is meaningful: the SDK Proposal does not override equals, and settlement
 * must receive the very object created for the authorized send.
 */
internal class Zc101RecordingProposalDataSource : ProposalDataSource {
    val preparedByUri = mutableMapOf<String, Zip321TransactionProposal>()
    var preparedRegular: RegularTransactionProposal? = null

    val submittedProposals = mutableListOf<Proposal>()

    /** Completed with the first proposal that reaches settlement (submit runs in a detached scope). */
    val firstSettlement = CompletableDeferred<Proposal>()

    override suspend fun createProposal(account: WalletAccount, send: ZecSend): RegularTransactionProposal =
        checkNotNull(preparedRegular) { "no regular proposal prepared" }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal = preparedByUri.getValue(zip321Uri)

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult {
        submittedProposals += proposal
        firstSettlement.complete(proposal)
        return SubmitResult.Success(txIds = listOf("synthetic-txid"))
    }

    // Unused in these scenarios.
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

/**
 * The seam that makes the race window drivable on the JVM: [requestBiometrics] parks on a deferred
 * the test controls — exactly like the real repository suspends until the user authenticates.
 */
internal class Zc101SuspendingBiometricRepository : BiometricRepository {
    /** Completed once the use case is parked INSIDE the biometric await. */
    val promptShown = CompletableDeferred<Unit>()

    private val approval = CompletableDeferred<Unit>()

    override val allowedAuthenticators: Int = 0

    override fun onBiometricResult(result: BiometricResult) = Unit

    override suspend fun requestBiometrics(request: BiometricRequest) {
        promptShown.complete(Unit)
        approval.await()
    }

    /** The user authenticates: releases the use case parked in [requestBiometrics]. */
    fun approve() {
        approval.complete(Unit)
    }
}

internal class Zc101FakeAccountDataSource(private val account: ZashiAccount) : AccountDataSource {
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

internal class Zc101FakeZashiSpendingKeyDataSource : ZashiSpendingKeyDataSource {
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

internal class Zc101RecordingNavigationRouter : NavigationRouter {
    val commands = mutableListOf<String>()

    override fun forward(vararg routes: Any) {
        commands += "forward"
    }

    override fun replace(vararg routes: Any) {
        commands += "replace"
    }

    override fun replaceAll(vararg routes: Any) {
        commands += "replaceAll"
    }

    override fun back() {
        commands += "back"
    }

    override fun backTo(route: KClass<*>) {
        commands += "backTo"
    }

    override fun backToRoot() {
        commands += "backToRoot"
    }

    override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
}

@Suppress("TooManyFunctions")
internal class Zc101FakeKeystoneProposalRepository : KeystoneProposalRepository {
    override val transactionProposal: Flow<TransactionProposal?> = flowOf(null)
    override val submitState: Flow<SubmitProposalState?> = flowOf(null)

    override suspend fun createProposal(zecSend: ZecSend) = error("keystone path not used in this test")

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal = error("keystone path not used in this test")

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal = error("keystone path not used in this test")

    override suspend fun createZip321Proposal(
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal = error("keystone path not used in this test")

    override suspend fun createShieldProposal() = error("keystone path not used in this test")

    override suspend fun createPCZTFromProposal() = error("keystone path not used in this test")

    override suspend fun createPCZTEncoder(): UREncoder = error("keystone path not used in this test")

    override suspend fun parsePCZT(ur: UR) = error("keystone path not used in this test")

    override suspend fun submit(proposal: TransactionProposal): SubmitResult =
        error("keystone path not used in this test")

    override fun clear() = Unit

    override suspend fun getTransactionProposal(): TransactionProposal = error("keystone path not used in this test")

    override fun getProposalPCZT(): Pczt? = error("keystone path not used in this test")
}

internal class Zc101FakeSwapRepository : SwapRepository {
    /** Settable so the SwapQuoteVM tests can place a fresh synthetic quote on the sheet. */
    val quoteState = MutableStateFlow<SwapQuoteData?>(null)

    override val assets: StateFlow<SwapAssetsData> = MutableStateFlow(SwapAssetsData())
    override val selectedAsset: StateFlow<SwapAsset?> = MutableStateFlow(null)
    override val slippage: StateFlow<BigDecimal> = MutableStateFlow(BigDecimal.ZERO)
    override val quote: StateFlow<SwapQuoteData?> = quoteState

    override fun select(asset: SwapAsset?) = Unit

    override fun setSlippage(amount: BigDecimal) = Unit

    override fun requestRefreshAssets() = Unit

    override suspend fun requestRefreshAssetsOnce() = Unit

    override fun requestExactInputQuote(amount: BigDecimal, address: String, refundAddress: String) = Unit

    override fun requestExactOutputQuote(amount: BigDecimal, address: String, refundAddress: String) = Unit

    override fun requestExactInputIntoZec(
        amount: BigDecimal,
        refundAddress: String,
        destinationAddress: String,
    ) = Unit

    override fun clear() = Unit

    override fun clearQuote() = Unit
}

@Suppress("TooManyFunctions")
internal class Zc101FakeMetadataRepository : MetadataRepository {
    override fun flipTxBookmark(txId: String) = Unit

    override fun createOrUpdateTxNote(txId: String, note: String) = Unit

    override fun deleteTxNote(txId: String) = Unit

    override fun markTxMemoAsRead(txId: String) = Unit

    @Suppress("LongParameterList")
    override fun markTxAsSwap(
        depositAddress: String,
        provider: String,
        origin: SwapAsset,
        destination: SwapAsset,
        totalFees: Zatoshi,
        totalFeesUsd: BigDecimal,
        amountOutFormatted: BigDecimal,
        mode: SwapMode,
        status: SwapStatus,
    ) = Unit

    @Suppress("LongParameterList")
    override suspend fun markTxAsSwapAwait(
        depositAddress: String,
        provider: String,
        origin: SwapAsset,
        destination: SwapAsset,
        totalFees: Zatoshi,
        totalFeesUsd: BigDecimal,
        amountOutFormatted: BigDecimal,
        mode: SwapMode,
        status: SwapStatus,
    ) = Unit

    @Suppress("LongParameterList")
    override fun updateSwap(
        depositAddress: String,
        amountOutFormatted: BigDecimal,
        status: SwapStatus,
        mode: SwapMode,
        origin: SwapAsset,
        destination: SwapAsset,
    ) = Unit

    override fun addSwapAssetToHistory(tokenTicker: String, chainTicker: String) = Unit

    override fun observeTransactionMetadata(transaction: Transaction): Flow<TransactionMetadata> =
        error("not used in this test")

    override fun observeSwapMetadata(): Flow<List<TransactionSwapMetadata>?> = error("not used in this test")

    override suspend fun getSwapMetadata(depositAddress: String): TransactionSwapMetadata? =
        error("not used in this test")

    override fun observeLastUsedAssetHistory(): Flow<Set<SimpleSwapAsset>?> = error("not used in this test")

    override fun delete() = Unit
}

internal class Zc101FakeSwapDataSource : SwapDataSource {
    override suspend fun getSupportedTokens(): List<SwapAsset> = error("not used in this test")

    @Suppress("LongParameterList")
    override suspend fun requestQuote(
        swapMode: SwapMode,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String,
    ): SwapQuote = error("not used in this test")

    override suspend fun submitDepositTransaction(txHash: String, depositAddress: String) =
        error("not used in this test")

    override suspend fun checkSwapStatus(
        depositAddress: String,
        supportedTokens: List<SwapAsset>,
    ): SwapQuoteStatus = error("not used in this test")
}

internal class Zc101FakeEphemeralAddressRepository : EphemeralAddressRepository {
    var invalidateCalls = 0
        private set

    override fun observe(): Flow<EphemeralAddress?> = flowOf(null)

    override suspend fun get(): EphemeralAddress? = null

    override suspend fun create(): EphemeralAddress = error("not used in this test")

    override suspend fun invalidate() {
        invalidateCalls++
    }
}

// ── Confirm-screen VM harness fakes (ZC-101 VM-binding tests) ────────────────────────────────────

/**
 * All-synthetic [SwapQuote]: constant amounts only, and a NON-ZEC destination asset so the quote
 * sheet's Confirm button routes to the submit path (a ZEC destination routes to on-ramp instead).
 * [timestamp] should be "now" so the MEDIUM-020 staleness re-check at Confirm passes.
 */
internal class Zc101FakeSwapQuote(
    override val timestamp: Instant,
    override val mode: SwapMode = SwapMode.EXACT_INPUT,
) : SwapQuote {
    private val syntheticChain =
        SwapBlockchain(
            chainTicker = "synthchain",
            chainName = stringRes("Synthetic Chain"),
            chainIcon = imageRes(0),
        )

    override val originAsset: SwapAsset =
        ZecSwapAsset(
            tokenTicker = "ZEC",
            tokenName = stringRes("Zcash"),
            tokenIcon = imageRes(0),
            blockchain = syntheticChain,
            usdPrice = BigDecimal("30"),
            assetId = "synthetic-zec",
            decimals = 8,
        )

    override val destinationAsset: SwapAsset =
        DynamicSwapAsset(
            tokenTicker = "SYNTH",
            tokenName = stringRes("Synthetic Token"),
            tokenIcon = imageRes(0),
            usdPrice = BigDecimal.ONE,
            assetId = "synthetic-token",
            decimals = 6,
            blockchain = syntheticChain,
        )

    override val depositAddress: SwapAddress = DynamicSwapAddress("synthetic-deposit")
    override val destinationAddress: SwapAddress = DynamicSwapAddress("synthetic-destination")
    override val refundAddress: SwapAddress = DynamicSwapAddress("synthetic-refund")
    override val provider: String = "synthetic"
    override val zecExchangeRate: BigDecimal = BigDecimal("30")

    override val amountIn: BigDecimal = BigDecimal("100000000")
    override val amountInFormatted: BigDecimal = BigDecimal("1")
    override val amountInUsd: BigDecimal = BigDecimal("30")

    override val amountOut: BigDecimal = BigDecimal("29700000")
    override val amountOutUsd: BigDecimal = BigDecimal("29.7")
    override val amountOutFormatted: BigDecimal = BigDecimal("29.7")

    override val affiliateFee: BigDecimal = BigDecimal("0.001")
    override val affiliateFeeZatoshi: Zatoshi = Zatoshi(100_000)
    override val affiliateFeeUsd: BigDecimal = BigDecimal("0.03")

    override val deadline: Instant = timestamp + 10.minutes
    override val slippage: BigDecimal = BigDecimal("1")

    override fun getTotal(proposal: Proposal?): BigDecimal = BigDecimal("1.0001")

    override fun getTotalUsd(proposal: Proposal?): BigDecimal = BigDecimal("30.003")

    override fun getTotalFeesUsd(proposal: Proposal?): BigDecimal = BigDecimal("0.04")

    /**
     * Proposal-dependent ON PURPOSE (mirrors the real quote: no proposal, no known ZEC fee): a
     * quote-sheet render built WITHOUT a swap proposal — the display cast yielded null — shows a
     * zero ZEC fee, letting the VM tests deterministically identify WHICH render they captured.
     */
    override fun getTotalFeesZatoshi(proposal: Proposal?): Zatoshi = if (proposal == null) Zatoshi(0) else Zatoshi(110_000)
}

/** The swap the quote sheet displays: a swap-typed proposal wrapping a distinct SDK proposal. */
internal suspend fun syntheticSwapProposal(quote: SwapQuote) =
    ExactInputSwapTransactionProposal(
        destination = WalletAddress.Transparent.new("tsynthetic-swap-deposit"),
        amount = Zatoshi(100_000_000),
        memo = Memo(""),
        proposal = newDistinctSdkProposal(),
        quote = quote,
    )

/** A plain regular send (non-ZIP321) for the ReviewTransaction regular-send confirm path. */
internal suspend fun syntheticRegularSendProposal(destination: String, amount: Long, memo: String) =
    RegularTransactionProposal(
        destination = WalletAddress.Unified.new(destination),
        amount = Zatoshi(amount),
        memo = Memo(memo),
        proposal = newDistinctSdkProposal(),
    )

internal class Zc101FakeApplicationStateProvider : ApplicationStateProvider {
    override val isInForeground: Flow<Boolean> = flowOf(true)

    override fun onThirdPartyUiShown() = Unit

    override fun onApplicationLifecycleChanged(event: Lifecycle.Event) = Unit

    override fun observeOnForeground(): Flow<Unit> = emptyFlow()
}

internal class Zc101FakeAddressBookRepository : AddressBookRepository {
    override val contacts: Flow<List<EnhancedABContact>?> = flowOf(emptyList())

    override fun saveContact(name: String, address: String, chain: String?) = Unit

    override fun updateContact(contact: EnhancedABContact, name: String, address: String, chain: String?) = Unit

    override fun deleteContact(contact: EnhancedABContact) = Unit

    override fun observeContactByAddress(address: String): Flow<EnhancedABContact?> = flowOf(null)

    override fun delete() = Unit
}

internal class Zc101FakeExchangeRateRepository : ExchangeRateRepository {
    override val state: StateFlow<ExchangeRateState> = MutableStateFlow(ExchangeRateState.OptedOut)

    override fun refreshExchangeRateUsd() = Unit
}
