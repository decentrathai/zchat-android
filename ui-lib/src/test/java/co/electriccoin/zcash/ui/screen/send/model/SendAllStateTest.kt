package co.electriccoin.zcash.ui.screen.send.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What the Send screen's "Send all" link shows (W-3). It used to appear whenever the TOTAL was above the
 * fee and fill `spendable - 10,000`, so a wallet whose Ironwood was still confirming got a fill of
 * 0.00000000 with no reason given. Now it is either ready, calculating, or disabled with the reason.
 * All addresses are synthetic.
 */
class SendAllStateTest {
    @Test
    fun confirming_funds_disable_the_link_with_the_amount_instead_of_filling_zero() =
        runTest {
            val account = account(ironwood = WalletBalance(Zatoshi(0), Zatoshi(443_002), Zatoshi(0)))

            assertEquals(
                SendAllState.Unavailable(SendMaxUnavailable.StillConfirming(Zatoshi(443_002))),
                SendAllState.of(account, SendMaxRecipient.SHIELDED),
            )
        }

    @Test
    fun a_sendable_wallet_needs_a_recipient_before_it_can_price_the_fee() =
        runTest {
            val account = account(ironwood = balance(443_002))

            assertEquals(SendAllState.NeedsRecipient, SendAllState.of(account, recipient = null))
            assertEquals(SendAllState.Ready, SendAllState.of(account, SendMaxRecipient.SHIELDED))
        }

    @Test
    fun the_last_tap_outcome_wins_until_the_form_is_edited() =
        runTest {
            val account = account(ironwood = balance(443_002))
            val failed = SendAllState.Unavailable(SendMaxUnavailable.CouldNotPrice)

            assertEquals(
                SendAllState.Calculating,
                SendAllState.of(account, SendMaxRecipient.SHIELDED, SendAllState.Calculating),
            )
            assertEquals(failed, SendAllState.of(account, SendMaxRecipient.SHIELDED, failed))
        }

    @Test
    fun nothing_shielded_hides_the_link() =
        runTest {
            val empty = account(ironwood = balance(0))

            assertEquals(SendAllState.Hidden, SendAllState.of(empty, SendMaxRecipient.SHIELDED))
            assertEquals(SendAllState.Hidden, SendAllState.of(account = null, recipient = SendMaxRecipient.SHIELDED))
        }

    private companion object {
        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(ironwood: WalletBalance) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(0), ironwood),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
