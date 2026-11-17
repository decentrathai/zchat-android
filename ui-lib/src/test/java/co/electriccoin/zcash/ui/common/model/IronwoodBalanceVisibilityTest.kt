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
 * RED pin for the Ironwood pool being dropped on the floor.
 *
 * Zcash NU6.3 ("Ironwood") activated on mainnet 2026-07-28 at height 3,428,143 and sealed the
 * Orchard pool: ZIP 258 requires every Orchard-pool Action to be created with
 * `enableCrossAddress = 0`, which ZIP 229 defines as "action outputs restricted to use the same
 * protocol-level address as the action's spend". An Orchard Action can therefore only pay its own
 * address, so a ZChat message — an output addressed to a PEER — can no longer be an Orchard
 * output. Value has to live in Ironwood for a user to message anyone at all, and 87.5% of the
 * former Orchard pool had crossed within six weeks of activation.
 *
 * SDK 3.0.0 surfaces that pool: `AccountBalance` gained a third `ironwood: WalletBalance`
 * component alongside `sapling` and `orchard`. Upgrading the SDK compiled clean and left the whole
 * unit suite green precisely BECAUSE nothing read the new field — [UnifiedInfo] carried a single
 * `balance`, populated in AccountDataSource from `balance?.orchard` alone. A migrated wallet
 * therefore reports a total of zero: its funds are on chain, spendable by the SDK, and invisible
 * to this app. A dropped field is silent, which is why no existing test caught it.
 *
 * These tests pin that arithmetic against the real production types.
 */
class IronwoodBalanceVisibilityTest {
    private fun balance(available: Long) =
        WalletBalance(
            available = Zatoshi(available),
            changePending = Zatoshi(0),
            valuePending = Zatoshi(0),
        )

    private val zero = balance(0)

    private suspend fun zashi(
        orchard: WalletBalance,
        ironwood: WalletBalance,
        sapling: WalletBalance = zero,
        transparent: Long = 0,
    ) = ZashiAccount(
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), orchard, ironwood),
        sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), sapling),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(transparent)),
        isSelected = true,
    )

    private suspend fun keystone(
        orchard: WalletBalance,
        ironwood: WalletBalance,
        transparent: Long = 0,
    ) = KeystoneAccount(
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone-self"), orchard, ironwood),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(transparent)),
        isSelected = true,
    )

    /**
     * The migrated user: everything has crossed into Ironwood and nothing is left in Orchard.
     * Their wallet must not read as empty. 811_000 zatoshi is the real balance measured on the
     * Seeker test device on 2026-09-04.
     */
    @Test
    fun ironwood_only_wallet_reports_its_balance() =
        runTest {
            val account = zashi(orchard = zero, ironwood = balance(811_000))

            assertEquals(
                Zatoshi(811_000),
                account.totalShieldedBalance,
                "A wallet whose funds are entirely in Ironwood must not report a zero shielded balance",
            )
            assertEquals(
                Zatoshi(811_000),
                account.spendableShieldedBalance,
                "Ironwood funds are spendable — the SDK selects the pool, so the app must not hide them",
            )
            assertEquals(Zatoshi(811_000), account.totalBalance)
        }

    /**
     * Mid-migration: a ZIP 318 crossing moves value as a schedule of transfers, so both pools hold
     * funds simultaneously for hours. Neither may be dropped.
     */
    @Test
    fun split_wallet_sums_both_shielded_pools() =
        runTest {
            val account = zashi(orchard = balance(135_000), ironwood = balance(676_000))

            assertEquals(
                Zatoshi(811_000),
                account.totalShieldedBalance,
                "Orchard and Ironwood are both shielded pools of the same protocol; both must count",
            )
            assertTrue(
                account.canSpend(Zatoshi(700_000)),
                "Spending must be allowed against the combined shielded balance",
            )
        }

    /**
     * The Orchard-only wallet is the one genuinely stuck — its value cannot reach a peer until it
     * crosses. The arithmetic still reports honestly; the block belongs in the send gate, not here.
     */
    @Test
    fun orchard_only_wallet_still_reports_its_balance() =
        runTest {
            val account = zashi(orchard = balance(135_000), ironwood = zero)

            assertEquals(Zatoshi(135_000), account.totalShieldedBalance)
        }

    /** Keystone accounts carry no Sapling info at all; the same omission applies to them. */
    @Test
    fun keystone_account_counts_ironwood_too() =
        runTest {
            val account = keystone(orchard = zero, ironwood = balance(500_000))

            assertEquals(Zatoshi(500_000), account.totalShieldedBalance)
            assertEquals(Zatoshi(500_000), account.spendableShieldedBalance)
        }

    /**
     * [UnifiedInfo.ironwood] must default to an empty balance so that every pre-existing
     * two-argument construction site keeps compiling and keeps meaning exactly what it meant.
     */
    @Test
    fun ironwood_defaults_to_empty_so_existing_call_sites_are_unchanged() =
        runTest {
            val info = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(135_000))

            assertEquals(Zatoshi(0), info.ironwood.total)
        }
}
