package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ProposingSynchronizerProvider
import co.electriccoin.zcash.ui.common.datasource.RequestProposalBytes
import co.electriccoin.zcash.ui.common.datasource.Zip321Payment
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.orchardSealedRefusal
import co.electriccoin.zcash.ui.common.model.publicCrossingRefusal
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapQuote
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * RED pin for the public-crossing leak that survives the sealed-Orchard gate.
 *
 * [orchardSealedRefusal] refuses only when Ironwood is entirely unspendable. But SDK 3.0.0 selects
 * inputs with a pool preference of [Ironwood, Orchard, Sapling] and spends the first pool whose notes
 * ALONE cover amount + fee — so a wallet holding, say, 20,000 spendable zatoshi in Ironwood and plenty
 * in Orchard passes the gate, and the SDK quietly funds the send from Orchard instead. Orchard value
 * leaving the pool crosses the NU6.3 turnstile, and ZIP 318 makes that crossing's amount PUBLIC.
 *
 * A second path reaches the same place and no balance arithmetic can predict it: when the amount is a
 * canonical ZIP 318 denomination ({1,2,5}x10^k between 0.01 and 10,000 ZEC), `propose_transfer` FIRST
 * tries an Orchard-only proposal on purpose, even when Ironwood could have paid.
 *
 * So the check cannot be an estimate — it has to read the built proposal. SDK 3.0.0 exposes exactly
 * that: `Proposal.usesOrchardInputs()`. This pins the decision that consumes it.
 */
class PublicCrossingRefusalTest {
    private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private suspend fun unified(orchard: Long, ironwood: Long) =
        UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), balance(ironwood))

    /** The leak: the gate let this through, and the SDK funded it from the sealed pool. */
    @Test
    fun a_proposal_that_spends_orchard_is_refused_even_when_ironwood_is_nonzero() =
        runTest {
            val refusal =
                publicCrossingRefusal(
                    proposalUsesOrchardInputs = true,
                    unified = unified(orchard = 818_000, ironwood = 20_000),
                )

            assertNotNull(refusal, "an Orchard-funded peer send publishes its amount and must be refused")
            assertEquals(Zatoshi(818_000), refusal.orchardAvailable)
            assertEquals(Zatoshi(20_000), refusal.ironwoodAvailable)
            assertTrue(
                refusal.message!!.contains("Orchard") && refusal.message!!.contains("public", ignoreCase = true),
                "the user must be told WHY it was refused, was: ${refusal.message}",
            )
        }

    /** An Ironwood-funded send is private and must not be touched. */
    @Test
    fun an_ironwood_only_proposal_is_allowed() =
        runTest {
            assertNull(
                publicCrossingRefusal(
                    proposalUsesOrchardInputs = false,
                    unified = unified(orchard = 818_000, ironwood = 1_078_000),
                ),
            )
        }

    /**
     * The check reads the PROPOSAL, not the balances: a wallet with no Orchard at all can never produce
     * an Orchard-spending proposal, and one that somehow did would still be refused.
     */
    @Test
    fun the_decision_follows_the_proposal_not_the_balance() =
        runTest {
            assertNull(publicCrossingRefusal(false, unified(orchard = 0, ironwood = 1_078_000)))
            assertNotNull(publicCrossingRefusal(true, unified(orchard = 0, ironwood = 1_078_000)))
        }

    // R2-1 (d), plan step p2-d3ab7b768b97: each of the four proposal funnels applies the refusal to the
    // proposal the SDK built. The wallet is split (Orchard and Ironwood both spendable), so the sealed
    // gate lets it through and only the built proposal can show the crossing.

    private suspend fun splitAccount() =
        ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = unified(orchard = 818_000, ironwood = 20_000),
            sapling =
                SaplingInfo(
                    WalletAddress.Sapling.new("zsynthetic-self"),
                    balance(0),
                ),
            transparent =
                TransparentInfo(
                    WalletAddress.Transparent.new("tsynthetic-self"),
                    Zatoshi(0),
                ),
            isSelected = true,
        )

    private suspend fun send() =
        ZecSend(
            destination = WalletAddress.Unified.new(PEER),
            amount = Zatoshi(100_000),
            memo = Memo(""),
            proposal = null,
        )

    private fun quote() = Zc101FakeSwapQuote(Clock.System.now())

    /** The SDK built a proposal paying the one payment asked for, funded from [pool]. */
    private fun sdkFundedFrom(pool: Int) =
        ProposingSynchronizerProvider(
            RequestProposalBytes.proposal(
                RequestProposalBytes.Step(
                    "zcash:$PEER?amount=0.001",
                    payments = 1,
                    inputPool = pool,
                ),
            ),
        )

    private fun funnel(pool: Int) =
        ProposalDataSourceImpl(sdkFundedFrom(pool)).apply {
            readZip321 = {
                listOf(Zip321Payment(PEER, Zatoshi(100_000), ""))
            }
        }

    /** Every funnel, called on [account]; each returns the proposal it built. */
    private fun funnels(
        account: WalletAccount
    ): Map<String, suspend (ProposalDataSource) -> Any> =
        mapOf(
            "createProposal" to { it.createProposal(account, send()) },
            "createZip321Proposal" to { it.createZip321Proposal(account, "zcash:$PEER?amount=0.001", false) },
            "createExactInputProposal" to { it.createExactInputProposal(account, send(), quote()) },
            "createExactOutputProposal" to { it.createExactOutputProposal(account, send(), quote()) },
        )

    @Test
    fun every_funnel_refuses_a_proposal_the_sdk_funded_from_orchard() =
        runTest {
            // Every funnel is tried before failing, so a failure names each funnel that let it through.
            val letThrough =
                funnels(splitAccount()).mapNotNull { (name, call) ->
                    val refusal = runCatching { call(funnel(ORCHARD)) }.exceptionOrNull()
                    if (refusal is PublicCrossingException) null else "$name -> ${refusal ?: "a proposal"}"
                }
            assertEquals(emptyList(), letThrough, "an Orchard-funded proposal must be refused by every funnel")
        }

    @Test
    fun every_funnel_passes_a_proposal_the_sdk_funded_from_ironwood() =
        runTest {
            val refused =
                funnels(splitAccount()).mapNotNull { (name, call) ->
                    runCatching { call(funnel(IRONWOOD)) }.exceptionOrNull()?.let { "$name -> $it" }
                }
            assertEquals(emptyList(), refused, "an Ironwood-funded proposal must pass every funnel")
        }

    private companion object {
        // A checksum-valid synthetic address: the scan path decodes the recipient it compares (R3-1).
        val PEER = SyntheticUnifiedAddress.mainnet(1)
        const val ORCHARD = 3
        const val IRONWOOD = 4
    }
}
