package co.electriccoin.zcash.ui.screen.wallettab

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.OrchardSweepDecision
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Wallet tab's "Old balance" card (sweep spec §1.1 E1, owner decision 3): shown whenever Orchard holds
 * anything, with the body for its state and the right button; hidden when there is nothing; and, for a
 * balance too small to move only, dismissible until that balance changes. All addresses are synthetic.
 */
class WalletTabSweepCardTest {
    @Test
    fun each_decision_has_its_body_and_button() {
        val orchard = Zatoshi(139_000)
        val cases =
            mapOf(
                OrchardSweepDecision.Available(orchard) to
                    Triple(R.string.move_old_balance_card_body_available, R.string.move_old_balance_action, false),
                OrchardSweepDecision.WaitForSync(orchard, Zatoshi(1_000)) to
                    Triple(R.string.move_old_balance_card_body_wait, R.string.move_old_balance_details, false),
                OrchardSweepDecision.Keystone(orchard) to
                    Triple(R.string.move_old_balance_card_body_keystone, R.string.move_old_balance_details, false),
                OrchardSweepDecision.TooSmall(orchard) to
                    Triple(R.string.move_old_balance_card_body_too_small, R.string.move_old_balance_details, true),
            )

        cases.forEach { (decision, expected) ->
            val (body, action, isDismissible) = expected
            val card = assertNotNull(oldBalanceCard(decision, dismissedAtZatoshi = null), "$decision")
            assertEquals(stringRes(R.string.move_old_balance_card_title), card.title)
            assertEquals(StringResource.ByResource(body, listOf(stringRes(orchard))), card.body, "$decision")
            assertEquals(stringRes(action), card.action, "$decision")
            assertEquals(isDismissible, card.isDismissible, "$decision")
        }
    }

    @Test
    fun nothing_in_orchard_shows_no_card() {
        assertNull(oldBalanceCard(OrchardSweepDecision.None, dismissedAtZatoshi = null))
    }

    @Test
    fun a_dismissed_too_small_card_comes_back_only_when_the_balance_changes() {
        assertNull(oldBalanceCard(OrchardSweepDecision.TooSmall(Zatoshi(20_000)), dismissedAtZatoshi = 20_000))
        assertNotNull(oldBalanceCard(OrchardSweepDecision.TooSmall(Zatoshi(20_001)), dismissedAtZatoshi = 20_000))
        // Dismissing is for the too-small card only: a movable balance always shows.
        assertNotNull(oldBalanceCard(OrchardSweepDecision.Available(Zatoshi(20_000)), dismissedAtZatoshi = 20_000))
    }

    @Test
    fun the_dismissal_key_is_per_account_and_stable() =
        runTest {
            val account = AccountFixture.new()
            val key = oldBalanceDismissalKey(zashi(account))

            assertEquals(key, oldBalanceDismissalKey(zashi(account)))
            assertTrue(key.matches(Regex("[0-9a-f]+")), key)
        }

    private companion object {
        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun zashi(account: Account) =
            ZashiAccount(
                sdkAccount = account,
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(20_000)),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
