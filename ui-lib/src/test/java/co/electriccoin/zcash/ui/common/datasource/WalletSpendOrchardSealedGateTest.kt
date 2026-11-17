package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * RED pin for the wallet's own spend paths bypassing the sealed-Orchard gate.
 *
 * The messaging chokepoint (CreateChunkedMessageProposalUseCase) refuses a peer-addressed spend from
 * an Orchard-only account. Zashi's Send / Pay / Swap screens do not go through it: they budget from
 * `spendableShieldedBalance` (which counts Orchard) and call `ProposalDataSource` directly, so an
 * Orchard-only user could ask the SDK for a payment that is either consensus-invalid (ZIP 258: an
 * Orchard Action may only pay its own address) or a turnstile crossing with a public amount.
 *
 * [ProposalDataSourceImpl] is the single funnel every wallet proposal passes through, so the gate
 * belongs there — BEFORE the synchronizer is touched. Shielding is deliberately exempt: shielding
 * transparent value lands in Ironwood and is the way OUT of the stranded state.
 */
class WalletSpendOrchardSealedGateTest {
    /** Thrown the moment a proposal reaches the SDK layer. */
    private class ReachedSdk : RuntimeException("proposal reached the synchronizer")

    private class MustNotBeReached : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = throw ReachedSdk()

        override fun resetSynchronizer() = Unit
    }

    private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private suspend fun account(orchard: Long, ironwood: Long, transparent: Long = 0) =
        ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), balance(ironwood)),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(transparent)),
            isSelected = true,
        )

    private suspend fun payment() =
        ZecSend(
            destination = WalletAddress.Unified.new("usynthetic-peer"),
            amount = Zatoshi(10_000),
            memo = Memo("rent"),
            proposal = null,
        )

    private val dataSource = ProposalDataSourceImpl(MustNotBeReached())

    private fun Throwable.reachedSdk(): Boolean = generateSequence(this) { it.cause }.any { it is ReachedSdk }

    /** The HONOR device: 135_000 zatoshi in Orchard, nothing in Ironwood, asked to pay someone. */
    @Test
    fun wallet_send_from_orchard_only_account_is_refused_before_the_sdk() =
        runTest {
            assertFailsWith<OrchardSealedException> {
                dataSource.createProposal(account(orchard = 135_000, ironwood = 0), payment())
            }
        }

    @Test
    fun zip321_payment_from_orchard_only_account_is_refused_before_the_sdk() =
        runTest {
            assertFailsWith<OrchardSealedException> {
                dataSource.createZip321Proposal(
                    account = account(orchard = 811_000, ironwood = 0),
                    zip321Uri = "zcash:usynthetic-peer?amount=0.0001",
                    allowMultiPayment = false,
                )
            }
        }

    /** A split wallet may pay: the gate must stand aside and let the SDK choose the pool. */
    @Test
    fun wallet_send_from_split_account_reaches_the_sdk() =
        runTest {
            val e =
                assertFailsWith<Exception> {
                    dataSource.createProposal(account(orchard = 135_000, ironwood = 676_000), payment())
                }
            assertTrue(e.reachedSdk(), "expected the proposal to reach the synchronizer, got: $e")
        }

    /** Shielding transparent value is never gated — it is how an Orchard-only wallet gets Ironwood funds. */
    @Test
    fun shielding_is_not_gated_even_for_an_orchard_only_account() =
        runTest {
            val e =
                assertFailsWith<Exception> {
                    dataSource.createShieldProposal(account(orchard = 135_000, ironwood = 0, transparent = 50_000))
                }
            assertTrue(e.reachedSdk(), "shielding must reach the synchronizer, got: $e")
        }

    // R2-1 (d), plan step p2-d3ab7b768b97: the swap funnels carry the same gate.

    private fun quote() = Zc101FakeSwapQuote(Clock.System.now())

    @Test
    fun exact_input_swap_from_orchard_only_account_is_refused_before_the_sdk() =
        runTest {
            assertFailsWith<OrchardSealedException> {
                dataSource.createExactInputProposal(account(orchard = 135_000, ironwood = 0), payment(), quote())
            }
        }

    @Test
    fun exact_output_swap_from_orchard_only_account_is_refused_before_the_sdk() =
        runTest {
            assertFailsWith<OrchardSealedException> {
                dataSource.createExactOutputProposal(account(orchard = 135_000, ironwood = 0), payment(), quote())
            }
        }

    /** The gate stands aside for a split wallet on the swap funnels too. */
    @Test
    fun swaps_from_a_split_account_reach_the_sdk() =
        runTest {
            val split = account(orchard = 135_000, ironwood = 676_000)
            val exactInput = assertFailsWith<Exception> { dataSource.createExactInputProposal(split, payment(), quote()) }
            val exactOutput = assertFailsWith<Exception> { dataSource.createExactOutputProposal(split, payment(), quote()) }
            assertTrue(exactInput.reachedSdk(), "exact input: $exactInput")
            assertTrue(exactOutput.reachedSdk(), "exact output: $exactOutput")
        }
}
