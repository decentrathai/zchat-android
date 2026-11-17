package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.OrchardSweepRefusedException
import co.electriccoin.zcash.ui.common.model.OrchardSweepTooSmallException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sweep funnel refuses, before the synchronizer is touched, every account it must not sweep
 * (sweep spec §2.1): Keystone (owner decision 6), a balance too small to arrive as a spendable note, a
 * balance still pending, and an empty Orchard pool. All addresses are synthetic.
 */
class OrchardSweepFunnelTypedTest {
    private class ReachedSdk : RuntimeException("the sweep reached the synchronizer")

    private class MustNotBeReached : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = throw ReachedSdk()

        override fun resetSynchronizer() = Unit
    }

    private val dataSource = ProposalDataSourceImpl(MustNotBeReached())

    @Test
    fun keystone_is_refused_before_the_sdk() =
        runTest {
            val refusal =
                assertFailsWith<TransactionProposalNotCreatedException> {
                    dataSource.createOrchardSweepProposal(keystone(orchard = 139_000))
                }
            assertTrue(refusal.cause is OrchardSweepRefusedException, "${refusal.cause}")
        }

    @Test
    fun too_small_is_refused_before_the_sdk_with_its_figure() =
        runTest {
            val refusal =
                assertFailsWith<OrchardSweepTooSmallException> {
                    dataSource.createOrchardSweepProposal(zashi(orchard = 25_000))
                }
            assertEquals(Zatoshi(25_000), refusal.orchard)
            assertEquals(null, refusal.fee)
        }

    @Test
    fun pending_and_empty_are_refused_before_the_sdk() =
        runTest {
            listOf(zashi(orchard = 139_000, orchardPending = 1_000), zashi(orchard = 0)).forEach { account ->
                val refusal =
                    assertFailsWith<TransactionProposalNotCreatedException> {
                        dataSource.createOrchardSweepProposal(account)
                    }
                assertTrue(refusal.cause is OrchardSweepRefusedException, "${refusal.cause}")
            }
        }

    /** The one account that passes goes on to the SDK: here that is the provider that must not be reached. */
    @Test
    fun an_available_balance_reaches_the_sdk() =
        runTest {
            val refusal =
                assertFailsWith<TransactionProposalNotCreatedException> {
                    dataSource.createOrchardSweepProposal(zashi(orchard = 25_001))
                }
            assertTrue(refusal.cause is ReachedSdk, "${refusal.cause}")
        }

    private companion object {
        fun balance(
            available: Long,
            pending: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        suspend fun zashi(
            orchard: Long,
            orchardPending: Long = 0
        ): WalletAccount =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard, orchardPending)),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )

        suspend fun keystone(orchard: Long): WalletAccount =
            KeystoneAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
