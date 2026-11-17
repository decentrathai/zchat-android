package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F9 (fix-all 2.13.0, train T1): Send all works on a wallet that also holds Sapling value.
 *
 * At HEAD `SendMaxSearch.precheck` turns Send all off for any wallet with even 1 zat of available Sapling, on the
 * premise that "the app cannot yet read a proposal's input pools". It can: the wire reader (`readSweepShape`) reads
 * every input's pool, and the send funnel already uses it to refuse a Sapling crossing. So a wallet with enough
 * Ironwood and a little Sapling dust gets no Send all at all.
 *
 * Wanted:
 * - the probe reports the proposal's input pools (`FeeProbe.Built.inputPools`, from `readSweepShape`, null when the
 *   proposal cannot be read);
 * - the scan treats an input from any pool other than Ironwood like an Orchard input: k is too small, so k grows. A
 *   proposal funded from Sapling is never accepted, and a proposal whose pools cannot be read is CouldNotPrice;
 * - Sapling no longer turns Send all off by itself. Only when Ironwood cannot cover the smallest sweep does the Sapling
 *   reason show, after "still confirming" and the sealed Orchard reason (Orchard can be moved with the Old balance
 *   card, Sapling cannot be used by Send all at all), and its copy says Send all uses only Ironwood instead of "Send
 *   all is off while …".
 *
 * [SaplingSelector] stands in for SDK 3.0.0's input selector: it spends from the first pool of Ironwood, Orchard,
 * Sapling whose notes alone cover amount + fee (PoolSpendGating), oldest first. All addresses are synthetic.
 */
class SendAllWithSaplingTest {
    @Test
    fun sapling_dust_no_longer_turns_send_all_off() =
        runTest {
            val selector = SaplingSelector(ironwood = listOf(400_000, 43_002), sapling = listOf(20_000))

            val result = selector.search()

            assertEquals(SendMaxResult.Max(Zatoshi(433_002), Zatoshi(10_000)), result)
            assertEquals(0, selector.saplingFundedProbes)
        }

    /**
     * The first probe reserves too little fee, Ironwood alone falls short, and the SDK funds it from Sapling at exactly
     * the fee the scan asked for. That proposal would publish its amount (a Sapling crossing) and would not sweep
     * Ironwood: k is too small, never an answer.
     */
    @Test
    fun a_sapling_funded_probe_grows_k_and_is_never_accepted() =
        runTest {
            val selector =
                SaplingSelector(ironwood = listOf(200_000, 200_000, 121_000), sapling = listOf(818_000))

            val result = selector.search()

            assertTrue(selector.saplingFundedProbes > 0, "the fixture must reach a Sapling-funded probe")
            assertEquals(SendMaxResult.Max(Zatoshi(506_000), Zatoshi(15_000)), result)
        }

    @Test
    fun a_probe_that_only_ever_spends_sapling_never_fills() =
        runTest {
            val account = zashi(ironwood = 443_002, sapling = 818_000)

            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account },
                    probe = { _, amount ->
                        FeeProbeBuilt.of(fee = 443_002 - amount.value, inputPools = listOf(PROPOSAL_POOL_SAPLING))
                    },
                )

            assertFalse(result is SendMaxResult.Max, "a Sapling-funded proposal was accepted: $result")
        }

    @Test
    fun a_probe_whose_input_pools_cannot_be_read_is_never_accepted() =
        runTest {
            val account = zashi(ironwood = 443_002)

            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account },
                    probe = { _, _ -> FeeProbeBuilt.of(fee = 10_000, inputPools = null) },
                )

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice), result)
        }

    @Test
    fun enough_ironwood_with_sapling_is_worth_probing() =
        runTest {
            assertNull(SendMaxSearch.precheck(zashi(ironwood = 443_002, sapling = 20_000), SendMaxRecipient.SHIELDED))
        }

    /** Control: with no Ironwood to sweep, a Sapling-only wallet is told about Sapling, not "below the fee". */
    @Test
    fun a_sapling_only_wallet_is_told_about_sapling() =
        runTest {
            assertEquals(
                SendMaxUnavailable.SaplingPresent(Zatoshi(500_000)),
                SendMaxSearch.precheck(zashi(ironwood = 0, sapling = 500_000), SendMaxRecipient.SHIELDED),
            )
            assertEquals(
                SendMaxUnavailable.SaplingPresent(Zatoshi(500_000)),
                SendMaxSearch.precheck(zashi(ironwood = 8_000, sapling = 500_000), SendMaxRecipient.SHIELDED),
            )
        }

    @Test
    fun sealed_orchard_is_named_before_sapling() =
        runTest {
            assertEquals(
                SendMaxUnavailable.OrchardSealed(Zatoshi(139_000)),
                SendMaxSearch.precheck(
                    zashi(ironwood = 0, orchard = 139_000, sapling = 20_000),
                    SendMaxRecipient.SHIELDED,
                ),
            )
        }

    /** The production probes read the input pools from the proposal the SDK built. */
    @Test
    fun the_wallet_probes_report_the_proposal_input_pools() =
        runTest {
            val proposal =
                SweepProposalBytes.proposal(
                    inputs =
                        listOf(
                            SweepProposalBytes.Input(PROPOSAL_POOL_SAPLING, 818_000),
                            SweepProposalBytes.ironwood(5),
                        ),
                    fee = 10_000,
                )
            val dataSource = ProposalDataSourceImpl(AnsweringSynchronizerProvider(proposal))
            val account = zashi(ironwood = 443_002, sapling = 818_000)

            val zip321 = dataSource.probeZip321Fee(account, "zcash:usynthetic-peer?amount=0.001") as FeeProbe.Built
            assertEquals(listOf(PROPOSAL_POOL_SAPLING, PROPOSAL_POOL_IRONWOOD), FeeProbeBuilt.inputPoolsOf(zip321))

            val send =
                ZecSend(
                    destination = WalletAddress.Unified.new("usynthetic-peer"),
                    amount = Zatoshi(100_000),
                    memo = Memo("rent"),
                    proposal = null,
                )
            val wallet = dataSource.probeFee(account, send) as FeeProbe.Built
            assertEquals(listOf(PROPOSAL_POOL_SAPLING, PROPOSAL_POOL_IRONWOOD), FeeProbeBuilt.inputPoolsOf(wallet))
        }

    @Test
    fun the_sapling_reason_says_send_all_uses_only_ironwood() {
        val english = ChatResourceText.strings("values").getValue(REASON_KEY)
        val spanish = ChatResourceText.strings("values-es").getValue(REASON_KEY)

        assertFalse("off while" in english, "Sapling no longer turns Send all off by itself: \"$english\"")
        assertTrue("Ironwood" in english, "the reason must say Send all uses only Ironwood: \"$english\"")
        assertFalse("desactivado mientras" in spanish, "Spanish still says Send all is off: \"$spanish\"")
        assertTrue("Ironwood" in spanish, "Spanish must say Send all uses only Ironwood: \"$spanish\"")
        assertNotEquals(english, spanish, "the Spanish is a translation, not a copy")
        assertEquals(placeholders(english), placeholders(spanish), "the same placeholders in both languages")

        val compose = ChatResourceText.source(COMPOSE_VM_PATH)
        val chat =
            compose
                .substringAfter("is SendMaxUnavailable.SaplingPresent ->")
                .substringBefore("SendMaxUnavailable.")
        assertFalse("is off while" in chat, "the chat Send All still says it is off: $chat")
        assertTrue("Ironwood" in chat, "the chat Send All reason must say it uses only Ironwood: $chat")
    }

    // --------------------------------------------------------------------------------- harness

    private suspend fun SaplingSelector.search(): SendMaxResult =
        SendMaxSearch.search(
            recipient = SendMaxRecipient.SHIELDED,
            readAccount = { zashi(ironwood = ironwood.sum(), sapling = sapling.sum()) },
            probe = { _, amount -> probe(amount.value) },
        )

    /**
     * Oldest-first selection from the first pool of Ironwood, then Sapling, whose notes alone cover amount + fee.
     * ZIP 317 over the actions: a pool's spends and outputs share a bundle padded to two actions; a Sapling-funded send
     * to a unified recipient pays into Ironwood, so it has one Sapling spend bundle and one Ironwood output bundle.
     */
    private class SaplingSelector(
        val ironwood: List<Long>,
        val sapling: List<Long>,
    ) {
        var saplingFundedProbes = 0

        @Suppress("ReturnCount") // exact sweep, sweep with change, or nothing
        fun probe(amount: Long): FeeProbe {
            val fromIronwood =
                select(ironwood, amount) { spends, change -> MARGINAL * maxOf(GRACE, spends, 1 + change) }
            if (fromIronwood != null) {
                val (fee, change, spends) = fromIronwood
                return FeeProbeBuilt.of(fee, List(spends) { PROPOSAL_POOL_IRONWOOD }, changeOf(change, IRONWOOD))
            }
            val fromSapling =
                select(sapling, amount) { spends, change -> MARGINAL * (maxOf(GRACE, spends, change) + GRACE) }
            if (fromSapling != null) {
                saplingFundedProbes++
                // Charged exactly what the scan asked for, so only the input pools can tell it apart.
                val spends = fromSapling.third
                val asked = ironwood.sum() - amount
                val change = sapling.take(spends).sum() - amount - asked
                return FeeProbeBuilt.of(asked, List(spends) { PROPOSAL_POOL_SAPLING }, changeOf(change, SAPLING))
            }
            return FeeProbe.Short
        }

        private fun changeOf(
            change: Long,
            pool: Int
        ) = if (change == 0L) emptyList() else listOf(ProposalChange(change, pool))

        /** (fee, change, spends) of the first oldest-first prefix of [notes] that covers amount + fee. */
        @Suppress("ReturnCount")
        private fun select(
            notes: List<Long>,
            amount: Long,
            feeOf: (spends: Int, change: Int) -> Long,
        ): Triple<Long, Long, Int>? {
            var sum = 0L
            notes.forEachIndexed { index, note ->
                sum += note
                val spends = index + 1
                val exactFee = feeOf(spends, 0)
                if (sum == amount + exactFee) return Triple(exactFee, 0L, spends)
                val changeFee = feeOf(spends, 1)
                val change = sum - amount - changeFee
                if (change >= MARGINAL) return Triple(changeFee, change, spends)
            }
            return null
        }
    }

    /** Answers the proposal calls with [proposal]; any other call is a fixture bug. */
    private class AnsweringSynchronizerProvider(
        proposal: Proposal
    ) : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

        private val answering: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { _, method, _ ->
                // proposeSend is an SDK extension that calls proposeTransfer.
                if (method.name in setOf("proposeFulfillingPaymentUri", "proposeTransfer")) {
                    proposal
                } else {
                    error("unexpected ${method.name}")
                }
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(answering)

        override suspend fun getSynchronizer(): Synchronizer = answering

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val MARGINAL = 5_000L
        const val GRACE = 2
        const val IRONWOOD = PROPOSAL_POOL_IRONWOOD
        const val SAPLING = PROPOSAL_POOL_SAPLING
        const val REASON_KEY = "send_all_reason_sapling"
        const val COMPOSE_VM_PATH =
            "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ZchatComposeVM.kt"

        fun placeholders(text: String): Set<String> =
            Regex("""%(\d+)\$([sd])""").findAll(text).map { it.value }.toSet()

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun zashi(
            ironwood: Long,
            orchard: Long = 0,
            sapling: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), balance(ironwood)),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(sapling)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
