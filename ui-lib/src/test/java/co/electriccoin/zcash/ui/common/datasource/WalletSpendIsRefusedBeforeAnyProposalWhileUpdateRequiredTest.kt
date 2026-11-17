package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.tex.SdkTexWallet
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapQuote
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/** ZC-538: every wallet builder/probe and the late submit backstop stop before touching the SDK. */
class WalletSpendIsRefusedBeforeAnyProposalWhileUpdateRequiredTest {
    private val repositoryJobs = mutableListOf<Job>()

    @Before
    fun setUp() = NetworkUpgradeTestApi.resetIfPresent()

    @After
    fun tearDown() {
        runBlocking { repositoryJobs.forEach { it.cancelAndJoin() } }
        NetworkUpgradeTestApi.resetIfPresent()
    }

    private class ReachedSdk : RuntimeException("synthetic SDK reached")

    private class CountingProvider : SynchronizerProvider {
        var calls = 0
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer {
            calls++
            throw ReachedSdk()
        }

        override fun resetSynchronizer() = Unit
    }

    private suspend fun calls(data: ProposalDataSourceImpl): List<Pair<String, suspend () -> Any?>> {
        val funded = zc538FundedAccount()
        val orchard = zc538FundedAccount(orchard = 811_000, ironwood = 0)
        val send =
            ZecSend(WalletAddress.Unified.new("usynthetic-zc538-peer"), Zatoshi(10_000), Memo("synthetic"), null)
        val quote = Zc101FakeSwapQuote(Clock.System.now())
        return listOf(
            "createProposal" to suspend { data.createProposal(funded, send) },
            "createZip321Proposal" to
                suspend {
                    data.createZip321Proposal(funded, "zcash:usynthetic-zc538-peer?amount=0.0001", false)
                },
            "createExactInputProposal" to suspend { data.createExactInputProposal(funded, send, quote) },
            "createExactOutputProposal" to suspend { data.createExactOutputProposal(funded, send, quote) },
            "createShieldProposal" to suspend { data.createShieldProposal(funded) },
            "createOrchardSweepProposal" to suspend { data.createOrchardSweepProposal(orchard) },
            "probeFee" to suspend { data.probeFee(funded, send) },
            "probeZip321Fee" to suspend { data.probeZip321Fee(funded, "zcash:usynthetic-zc538-peer?amount=0.0001") },
            "probeZip321Plan" to suspend { data.probeZip321Plan(funded, "zcash:usynthetic-zc538-peer?amount=0.0001") },
        )
    }

    @Test
    fun control_every_builder_and_probe_reaches_the_synchronizer_when_compatible() =
        runTest {
            val provider = CountingProvider()
            for ((name, call) in calls(ProposalDataSourceImpl(provider))) {
                val before = provider.calls
                val error = runCatching { call() }.exceptionOrNull()
                assertTrue(
                    generateSequence(error) { it.cause }.any { it is ReachedSdk },
                    "$name fixture failed before SDK: $error",
                )
                assertEquals(before + 1, provider.calls, name)
            }
        }

    @Test
    fun every_builder_and_probe_refuses_without_touching_the_synchronizer() =
        runTest {
            val provider = CountingProvider()
            val calls = calls(ProposalDataSourceImpl(provider))
            NetworkUpgradeTestApi.latch()
            for ((name, call) in calls) {
                NetworkUpgradeTestApi.assertRefusal(runCatching { call() }.exceptionOrNull())
                assertEquals(0, provider.calls, "$name touched SDK while update required")
            }
        }

    @Test
    fun submit_backstop_refuses_before_create_or_store_for_proposal_and_pczt() =
        runTest {
            val provider = CountingProvider()
            val data = ProposalDataSourceImpl(provider)
            val proposal = syntheticZip321Proposal("usynthetic-zc538-peer", 1_000, "synthetic")
            val key = Zc101FakeZashiSpendingKeyDataSource().getZashiSpendingKey()
            val pczt = Pczt(byteArrayOf(0x11, 0x11, 0x11))
            var storedCalls = 0
            NetworkUpgradeTestApi.latch()
            val errors =
                listOf(
                    assertFailsWith<NothingBroadcastException> { data.submitTransaction(proposal.proposal, key) },
                    assertFailsWith<NothingBroadcastException> { data.submitTransaction(pczt, pczt) },
                    assertFailsWith<NothingBroadcastException> {
                        data.submitTransaction(proposal.proposal, key) { storedCalls++ }
                    },
                )
            errors.forEach(NetworkUpgradeTestApi::assertRefusal)
            assertEquals(0, provider.calls, "submit backstop ran after SDK access")
            assertEquals(0, storedCalls, "submit backstop stored a transaction")
            val repository =
                ZashiProposalRepositoryImpl(
                    Zc101FakeAccountDataSource(zc538FundedAccount()),
                    data,
                    Zc101FakeZashiSpendingKeyDataSource(),
                )
            val scope =
                ZashiProposalRepositoryImpl::class.java
                    .getDeclaredField("scope")
                    .apply { isAccessible = true }
                    .get(repository) as CoroutineScope
            repositoryJobs += scope.coroutineContext[Job]!!
            val result = repository.submit(proposal)
            assertEquals(SubmitResult.Failure(emptyList(), 0, NetworkUpgradeTestApi.MESSAGE), result)
        }

    @Test
    fun node_result_is_classified_immediately_after_create_store_submit_before_refresh() {
        val source =
            SourceScan.stripComments(
                ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ProposalDataSource.kt")
            )
        val counted = SourceScan.braceBlock(source, "private suspend fun submitTransactionCounted(")
        val create = counted.indexOf("createStoreAndSubmit(")
        val classify = counted.indexOf("NetworkUpgradeGuard.onSubmitResult(result)")
        val refresh = counted.indexOf("synchronizer.refreshTransactions()")
        assertTrue(create >= 0 && classify > create && refresh > classify, "node result is not classified before refresh")
        val between = counted.substring(create, classify.takeIf { it >= 0 } ?: counted.length)
        assertEquals(1, Regex("""createStoreAndSubmit\s*\(""").findAll(between).count())
        assertEquals(1, Regex("""NetworkUpgradeGuard\.onSubmitResult\(result\)""").findAll(counted).count())
    }

    @Test
    fun tex_shield_refuses_before_the_sdk_but_compatible_control_reaches_it() =
        runTest {
            val provider = CountingProvider()
            val persistableProvider =
                Proxy.newProxyInstance(
                    PersistableWalletProvider::class.java.classLoader,
                    arrayOf(PersistableWalletProvider::class.java),
                ) { _, _, _ -> throw AssertionError("wallet storage must not be read") } as PersistableWalletProvider
            val wallet =
                SdkTexWallet(
                    provider,
                    Zc101FakeAccountDataSource(zc538FundedAccount()),
                    Zc101RecordingProposalDataSource(),
                    Zc101FakeZashiSpendingKeyDataSource(),
                    persistableProvider,
                )
            assertFailsWith<ReachedSdk> { wallet.proposeShield() }
            provider.calls = 0
            NetworkUpgradeTestApi.latch()
            NetworkUpgradeTestApi.assertRefusal(runCatching { wallet.proposeShield() }.exceptionOrNull())
            assertEquals(0, provider.calls)
        }
}

internal suspend fun zc538FundedAccount(
    orchard: Long = 0,
    ironwood: Long = 811_000,
): ZashiAccount {
    fun balance(value: Long) = WalletBalance(Zatoshi(value), Zatoshi(0), Zatoshi(0))
    return ZashiAccount(
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), balance(ironwood)),
        sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(50_000)),
        isSelected = true,
    )
}
