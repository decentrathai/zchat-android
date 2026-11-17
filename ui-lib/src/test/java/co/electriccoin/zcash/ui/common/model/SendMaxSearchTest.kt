package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Send all asks the SDK for the exact maximum (W-1, W-2, W-3).
 *
 * The owner's phone held 443,002 zatoshi of Ironwood and 139,000 of sealed Orchard. Send all filled
 * 572,002 (Orchard counted in, a flat 10,000 fee), the SDK refused, and the refusal read "please wait".
 * Only Ironwood can pay another person, and the fee of a sweep is 5,000 per spent note (at least two),
 * so the right fill there is 433,002 and a wallet of three or more notes needs more than 10,000.
 *
 * [FakeSelector] stands in for SDK 3.0.0's input selector in the three ways the search depends on:
 * pools in the order Ironwood, then Orchard, then both combined when neither alone covers amount + fee
 * (optionally Orchard first for a ZIP 318 canonical amount); oldest-first note selection until the
 * running sum covers amount + fee; and the ZIP 317 fee of what it selected. Change of exactly 5,000 is
 * accepted by the SDK and stranded as dust, so every accepted result must have NO change. All addresses
 * are synthetic.
 */
class SendMaxSearchTest {
    @Test
    fun owner_state_fills_ironwood_minus_the_two_action_fee() =
        runTest {
            val selector = FakeSelector(ironwood = listOf(400_000, 43_002), orchard = listOf(139_000))

            val result = selector.search(zashi(selector))

            assertEquals(SendMaxResult.Max(Zatoshi(433_002), Zatoshi(10_000)), result)
            selector.assertSwept(result)
        }

    /**
     * The Seeker: three Ironwood notes and more Orchard than Ironwood. The first probe reserves too
     * little fee, Ironwood alone falls short and the SDK funds it from Orchard. That must mean "k is too
     * small", not a public-crossing dead end: the true maximum is V - 5,000 x 3.
     */
    @Test
    fun seeker_state_steps_past_the_orchard_funded_probe_to_an_ironwood_only_sweep() =
        runTest {
            val selector =
                FakeSelector(ironwood = listOf(200_000, 200_000, 121_000), orchard = listOf(818_000))

            val result = selector.search(zashi(selector))

            assertEquals(SendMaxResult.Max(Zatoshi(506_000), Zatoshi(15_000)), result)
            assertTrue(selector.orchardFundedProbes > 0, "the Orchard-funded probe path must be exercised")
            selector.assertSwept(result)
        }

    @Test
    fun many_notes_pay_one_action_each() =
        runTest {
            val selector = FakeSelector(ironwood = List(5) { 100_000L })

            val result = selector.search(zashi(selector))

            assertEquals(SendMaxResult.Max(Zatoshi(475_000), Zatoshi(25_000)), result)
            selector.assertSwept(result)
        }

    @Test
    fun a_transparent_recipient_pays_one_more_action() =
        runTest {
            val selector = FakeSelector(ironwood = listOf(400_000, 43_002), transparentRecipient = true)

            val result = selector.search(zashi(selector), SendMaxRecipient.TRANSPARENT)

            assertEquals(SendMaxResult.Max(Zatoshi(428_002), Zatoshi(15_000)), result)
            selector.assertSwept(result)
        }

    /**
     * Owner decision 4: when the SDK goes Orchard-first for a canonical amount, the next step down can
     * only be an Ironwood proposal that leaves 5,000 of change. Never step down silently; say it cannot
     * sweep exactly.
     */
    @Test
    fun a_canonical_amount_that_would_strand_5000_of_change_is_reported_not_filled() =
        runTest {
            val selector =
                FakeSelector(
                    ironwood = listOf(200_000, 200_000, 121_000),
                    orchard = listOf(818_000),
                    orchardFirstAmounts = setOf(506_000),
                )

            val result = selector.search(zashi(selector))

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice), result)
            assertEquals(5_000L, selector.changeByAmount[501_000], "the fixture must reach the 5,000-change trap")
        }

    @Test
    fun a_balance_below_the_fee_says_so_without_probing() =
        runTest {
            val selector = FakeSelector(ironwood = listOf(8_000))

            val result = selector.search(zashi(selector))

            assertEquals(
                SendMaxResult.Unavailable(SendMaxUnavailable.BelowFee(have = Zatoshi(8_000), fee = Zatoshi(10_000))),
                result,
            )
            assertEquals(0, selector.probes)
        }

    @Test
    fun tex_is_unsupported_without_probing() =
        runTest {
            val selector = FakeSelector(ironwood = listOf(443_002))

            val result = selector.search(zashi(selector), SendMaxRecipient.TEX)

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.TexUnsupported), result)
            assertEquals(0, selector.probes)
        }

    @Test
    fun a_wallet_whose_ironwood_is_confirming_is_told_to_wait_never_filled_zero() =
        runTest {
            val account =
                zashi(
                    ironwood = WalletBalance(Zatoshi(0), Zatoshi(443_002), Zatoshi(0)),
                    orchard = balance(0),
                )

            assertEquals(
                SendMaxUnavailable.StillConfirming(Zatoshi(443_002)),
                SendMaxSearch.precheck(account, SendMaxRecipient.SHIELDED),
            )
        }

    @Test
    fun an_orchard_only_wallet_is_told_its_funds_are_sealed() =
        runTest {
            val account = zashi(ironwood = balance(0), orchard = balance(139_000))

            assertEquals(
                SendMaxUnavailable.OrchardSealed(Zatoshi(139_000)),
                SendMaxSearch.precheck(account, SendMaxRecipient.SHIELDED),
            )
        }

    @Test
    fun the_scan_gives_up_after_its_cap_instead_of_guessing() =
        runTest {
            val account = zashi(ironwood = balance(1_000_000), orchard = balance(0))
            var probes = 0

            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account },
                    probe = { _, _ ->
                        probes++
                        FeeProbe.Short
                    },
                )

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice), result)
            assertEquals(SendMaxSearch.MAX_EXTRA_ACTIONS + 1, probes)
        }

    @Test
    fun a_two_transaction_proposal_is_never_accepted() =
        runTest {
            val account = zashi(ironwood = balance(443_002), orchard = balance(0))

            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account },
                    probe = { _, _ ->
                        FeeProbe.Built(Zatoshi(10_000), usesOrchardInputs = false, transactionCount = 2)
                    },
                )

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice), result)
        }

    /** The balance is read before EVERY probe, so a value that moves mid-search is priced as it is now. */
    @Test
    fun the_balance_is_re_read_before_every_probe() =
        runTest {
            val short = zashi(ironwood = balance(443_002), orchard = balance(0))
            val movedToOrchard = zashi(ironwood = balance(0), orchard = balance(139_000))
            var reads = 0
            var probes = 0

            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { if (++reads == 1) short else movedToOrchard },
                    probe = { _, _ ->
                        probes++
                        FeeProbe.Short
                    },
                )

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.OrchardSealed(Zatoshi(139_000))), result)
            assertEquals(2, reads)
            assertEquals(1, probes)
        }

    /**
     * The peer budget is Ironwood alone on both account types, while the displayed spendable balance
     * keeps counting Orchard (and Sapling on Zashi): display values stay as they are.
     */
    @Test
    fun peer_spendable_is_ironwood_available_and_the_displayed_spendable_is_unchanged() =
        runTest {
            val zashi = zashi(ironwood = balance(443_002), orchard = balance(139_000), sapling = 20_000)
            val keystone =
                KeystoneAccount(
                    sdkAccount = AccountFixture.new(),
                    unified =
                        UnifiedInfo(
                            WalletAddress.Unified.new("usynthetic-keystone-self"),
                            balance(139_000),
                            balance(443_002),
                        ),
                    transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                    isSelected = true,
                )

            assertEquals(Zatoshi(443_002), zashi.peerSpendableBalance)
            assertEquals(Zatoshi(443_002), keystone.peerSpendableBalance)
            assertEquals(Zatoshi(602_002), zashi.spendableShieldedBalance)
            assertEquals(Zatoshi(582_002), keystone.spendableShieldedBalance)
        }

    private suspend fun FakeSelector.search(
        account: WalletAccount,
        recipient: SendMaxRecipient = SendMaxRecipient.SHIELDED,
    ): SendMaxResult =
        SendMaxSearch.search(
            recipient = recipient,
            readAccount = { account },
            probe = { _, amount -> probe(amount.value) },
        )

    /**
     * An oldest-first, pool-ordered stand-in for the SDK's input selector over explicit notes.
     * [orchardFirstAmounts] are the amounts for which it tries Orchard alone first (ZIP 318 canonical
     * denominations).
     */
    private class FakeSelector(
        val ironwood: List<Long>,
        val orchard: List<Long> = emptyList(),
        val transparentRecipient: Boolean = false,
        val orchardFirstAmounts: Set<Long> = emptySet(),
    ) {
        var probes = 0
        var orchardFundedProbes = 0

        /** The change each built proposal would leave, by amount. */
        val changeByAmount = mutableMapOf<Long, Long>()

        fun probe(amount: Long): FeeProbe {
            probes++
            val ironwoodOnly = {
                select(ironwood, amount, usesOrchard = false) { spends, change -> fee(spends, 0, change) }
            }
            val orchardOnly = {
                select(orchard, amount, usesOrchard = true) { spends, change -> fee(0, spends, change) }
            }
            val order =
                if (amount in orchardFirstAmounts) {
                    listOf(orchardOnly, ironwoodOnly)
                } else {
                    listOf(ironwoodOnly, orchardOnly)
                }
            order.forEach { pool ->
                pool()?.let { return built(amount, it) }
            }
            return combined(amount)?.let { built(amount, it) } ?: FeeProbe.Short
        }

        /** Every accepted sweep spends exactly the Ironwood available and leaves no change. */
        fun assertSwept(result: SendMaxResult) {
            val max = result as SendMaxResult.Max
            assertEquals(ironwood.sum(), max.amount.value + max.fee.value, "amount + fee must be all the Ironwood")
            assertEquals(0L, changeByAmount[max.amount.value], "an accepted sweep must leave no change")
        }

        private fun built(
            amount: Long,
            selection: Selection
        ): FeeProbe {
            changeByAmount[amount] = selection.change
            if (selection.usesOrchard) orchardFundedProbes++
            val change = if (selection.change == 0L) emptyList() else listOf(ProposalChange(selection.change, CHANGE_POOL))
            return FeeProbeBuilt.of(selection.fee, selection.pools, change, usesOrchardInputs = selection.usesOrchard)
        }

        /** The first oldest-first prefix of [notes] that covers amount + its fee, or null. */
        @Suppress("ReturnCount") // exact sweep, sweep with change, or nothing
        private fun select(
            notes: List<Long>,
            amount: Long,
            usesOrchard: Boolean,
            feeOf: (spends: Int, change: Boolean) -> Long,
        ): Selection? {
            var sum = 0L
            notes.forEachIndexed { index, note ->
                sum += note
                val spends = index + 1
                val exactFee = feeOf(spends, false)
                val pools = List(spends) { if (usesOrchard) PROPOSAL_POOL_ORCHARD else PROPOSAL_POOL_IRONWOOD }
                if (sum == amount + exactFee) return Selection(exactFee, 0, usesOrchard, pools)
                val changeFee = feeOf(spends, true)
                val change = sum - amount - changeFee
                if (change >= DUST_THRESHOLD) return Selection(changeFee, change, usesOrchard, pools)
            }
            return null
        }

        /** Neither pool alone covers: every Ironwood note plus Orchard notes oldest-first. */
        @Suppress("ReturnCount") // exact sweep, sweep with change, or nothing
        private fun combined(amount: Long): Selection? {
            var orchardSum = 0L
            orchard.forEachIndexed { index, note ->
                orchardSum += note
                val sum = ironwood.sum() + orchardSum
                val exactFee = fee(ironwood.size, index + 1, false)
                val pools = List(ironwood.size) { PROPOSAL_POOL_IRONWOOD } + List(index + 1) { PROPOSAL_POOL_ORCHARD }
                if (sum == amount + exactFee) return Selection(exactFee, 0, usesOrchard = true, pools)
                val changeFee = fee(ironwood.size, index + 1, true)
                val change = sum - amount - changeFee
                if (change >= DUST_THRESHOLD) return Selection(changeFee, change, usesOrchard = true, pools)
            }
            return null
        }

        /**
         * ZIP 317: each Orchard-protocol bundle is padded to two actions; Ironwood carries the recipient
         * (when shielded) and any change; a transparent recipient is one more logical action.
         */
        private fun fee(
            ironwoodSpends: Int,
            orchardSpends: Int,
            change: Boolean
        ): Long {
            val ironwoodOutputs = (if (transparentRecipient) 0 else 1) + (if (change) 1 else 0)
            val ironwoodActions =
                if (ironwoodSpends + ironwoodOutputs > 0) maxOf(GRACE, ironwoodSpends, ironwoodOutputs) else 0
            val orchardActions = if (orchardSpends > 0) maxOf(GRACE, orchardSpends) else 0
            val transparentActions = if (transparentRecipient) 1 else 0
            return MARGINAL * maxOf(GRACE, ironwoodActions + orchardActions + transparentActions)
        }

        private data class Selection(
            val fee: Long,
            val change: Long,
            val usesOrchard: Boolean,
            /** The pool of every note it spends, as the wire reader reads a proposal's inputs. */
            val pools: List<Int>,
        )
    }

    private companion object {
        const val MARGINAL = 5_000L
        const val GRACE = 2

        /** Change lands in Ironwood: the recipient's bundle carries it. */
        const val CHANGE_POOL = PROPOSAL_POOL_IRONWOOD

        /** Change below this is dust the SDK will not create; change of exactly this is accepted (L-1). */
        const val DUST_THRESHOLD = 5_000L

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun zashi(
            selector: FakeSelector,
            sapling: Long = 0,
        ) = zashi(
            ironwood = balance(selector.ironwood.sum()),
            orchard = balance(selector.orchard.sum()),
            sapling = sapling,
        )

        suspend fun zashi(
            ironwood: WalletBalance,
            orchard: WalletBalance,
            sapling: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), orchard, ironwood),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(sapling)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
