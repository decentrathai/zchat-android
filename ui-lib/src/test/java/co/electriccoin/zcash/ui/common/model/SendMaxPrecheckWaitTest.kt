package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.send.model.SendAllState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Send all says "still confirming" only when waiting would really let it send (review follow-up).
 *
 * Every inbound on-chain chat message lands 1,000 zatoshi per chunk as confirming Ironwood. With 139,000
 * of sealed Orchard and 1,000 confirming, the Send screen said "0.00001 ZEC is still confirming. Send all
 * becomes available once it confirms", and once it confirmed the link flipped to sealed Orchard or below
 * the fee. The precheck now asks what [ShortfallReason.classify] asks: does what is available plus what
 * is confirming cover the smallest sweep, with an allowance for the notes that confirm? All addresses
 * are synthetic.
 */
class SendMaxPrecheckWaitTest {
    @Test
    fun a_confirming_chat_note_next_to_sealed_orchard_names_the_orchard() =
        runTest {
            val account = account(ironwood = 0, pending = 1_000, orchard = 139_000)

            assertEquals(
                SendMaxUnavailable.OrchardSealed(Zatoshi(139_000)),
                SendMaxSearch.precheck(account, SendMaxRecipient.SHIELDED),
            )
            assertEquals(
                SendAllState.Unavailable(SendMaxUnavailable.OrchardSealed(Zatoshi(139_000))),
                SendAllState.of(account, SendMaxRecipient.SHIELDED),
            )
        }

    @Test
    fun a_confirming_note_too_small_to_pay_the_fee_is_below_fee() =
        runTest {
            assertEquals(
                SendMaxUnavailable.BelowFee(have = Zatoshi(0), fee = Zatoshi(10_000)),
                SendMaxSearch.precheck(account(ironwood = 0, pending = 1_000), SendMaxRecipient.SHIELDED),
            )
        }

    /**
     * The smallest shielded sweep is the 10,000 fee plus 1 zatoshi; confirming notes may add up to two
     * actions (10,000) once they are spendable. At that line waiting is promised, one zatoshi under it not.
     */
    @Test
    fun waiting_is_promised_only_at_the_smallest_sweep_plus_the_note_allowance() =
        runTest {
            assertEquals(
                SendMaxUnavailable.StillConfirming(Zatoshi(20_001)),
                SendMaxSearch.precheck(account(ironwood = 0, pending = 20_001), SendMaxRecipient.SHIELDED),
            )
            assertEquals(
                SendMaxUnavailable.BelowFee(have = Zatoshi(0), fee = Zatoshi(10_000)),
                SendMaxSearch.precheck(account(ironwood = 0, pending = 20_000), SendMaxRecipient.SHIELDED),
            )
        }

    /** What is already available counts toward the wait too. */
    @Test
    fun available_plus_confirming_that_covers_the_sweep_is_still_confirming() =
        runTest {
            assertEquals(
                SendMaxUnavailable.StillConfirming(Zatoshi(15_000)),
                SendMaxSearch.precheck(account(ironwood = 5_001, pending = 15_000), SendMaxRecipient.SHIELDED),
            )
        }

    /** The chat shape counts its platform fee and a kept handshake reserve in what waiting must cover. */
    @Test
    fun a_chat_send_all_waits_only_when_the_reserve_and_fees_are_covered_too() =
        runTest {
            val shape = CreateChunkedMessageProposalUseCase.sendAllShape(1, Zatoshi(17_000))

            assertEquals(
                SendMaxUnavailable.OrchardSealed(Zatoshi(139_000)),
                SendMaxSearch.precheck(
                    account(ironwood = 0, pending = 30_000, orchard = 139_000),
                    SendMaxRecipient.SHIELDED,
                    shape,
                ),
            )
            assertEquals(
                SendMaxUnavailable.StillConfirming(Zatoshi(443_002)),
                SendMaxSearch.precheck(
                    account(ironwood = 0, pending = 443_002, orchard = 139_000),
                    SendMaxRecipient.SHIELDED,
                    shape,
                ),
            )
        }

    private companion object {
        fun balance(
            available: Long,
            pending: Long = 0,
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        suspend fun account(
            ironwood: Long,
            pending: Long = 0,
            orchard: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-self"),
                    balance(orchard),
                    balance(ironwood, pending = pending),
                ),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
