package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The shortfall sheet names a cause the wallet state shows (M-1).
 *
 * It used to say "Your previous transaction is still confirming" for every "Insufficient balance". On
 * the owner's phone nothing was pending, so the wait it asked for never helped. "Still confirming" is
 * now said only when waiting really covers the send: the confirming Ironwood, plus what is already
 * spendable, must reach the SDK's `need` with room for the extra notes' fee (two more actions,
 * conservatively; since ZC-1909 by the first-message gate's covers rule). All addresses are synthetic.
 */
class ShortfallReasonTest {
    /** The owner's phone: 443,002 Ironwood, 139,000 sealed Orchard, nothing pending; have 582,002, need 587,002. */
    @Test
    fun the_owner_state_is_not_enough_not_still_confirming() =
        runTest {
            val account = account(ironwood = balance(443_002), orchard = 139_000)

            assertEquals(
                ShortfallReason.NotEnough(have = Zatoshi(443_002), need = Zatoshi(587_002), pending = Zatoshi(0)),
                ShortfallReason.classify(account, need = Zatoshi(587_002)),
            )
        }

    @Test
    fun waiting_that_covers_the_need_is_still_confirming() =
        runTest {
            val account = account(ironwood = balance(available = 0, changePending = 443_002))

            assertEquals(
                ShortfallReason.StillConfirming(pending = Zatoshi(443_002), need = Zatoshi(300_000)),
                ShortfallReason.classify(account, need = Zatoshi(300_000)),
            )
        }

    /**
     * ZC-1909 [test-change-ok: ZC-1909 owner W-148/W-150: one covers rule for the gate, the classifier and the note wait]:
     * available + pending must cover the need by the ZC-1417 rule the first-message gate uses (spendableCovers): exactly
     * the need, or the need plus the marginal fee (5,000) or more. It used to ask need + 10,000, so a KEX change the gate
     * had accepted (cost + 5,000 .. 9,999) became "not enough" after the KEX was paid.
     */
    @Test
    fun still_confirming_follows_the_gate_s_covers_rule() =
        runTest {
            val account = account(ironwood = balance(available = 100_000, changePending = 200_000))

            for (need in listOf(300_000L, 295_000L, 290_001L, 290_000L)) {
                assertEquals(
                    ShortfallReason.StillConfirming(pending = Zatoshi(200_000), need = Zatoshi(need)),
                    ShortfallReason.classify(account, need = Zatoshi(need)),
                    "need $need",
                )
            }
            // 1 .. 4,999 above the need leaves a change the SDK refuses: waiting does not help.
            for (need in listOf(299_999L, 295_001L)) {
                assertEquals(
                    ShortfallReason.NotEnough(have = Zatoshi(100_000), need = Zatoshi(need), pending = Zatoshi(200_000)),
                    ShortfallReason.classify(account, need = Zatoshi(need)),
                    "need $need",
                )
            }
            assertEquals(
                ShortfallReason.NotEnough(have = Zatoshi(100_000), need = Zatoshi(300_001), pending = Zatoshi(200_000)),
                ShortfallReason.classify(account, need = Zatoshi(300_001)),
            )
        }

    @Test
    fun pending_that_would_not_cover_it_is_not_a_reason_to_wait() =
        runTest {
            val account = account(ironwood = balance(available = 100_000, valuePending = 50_000))

            assertEquals(
                ShortfallReason.NotEnough(have = Zatoshi(100_000), need = Zatoshi(200_000), pending = Zatoshi(50_000)),
                ShortfallReason.classify(account, need = Zatoshi(200_000)),
            )
        }

    /** The balance looks sufficient only because it counts sealed Orchard, which cannot pay a peer. */
    @Test
    fun only_orchard_would_cover_it() =
        runTest {
            val account = account(ironwood = balance(443_002), orchard = 139_000)

            assertEquals(
                ShortfallReason.OrchardStranded(
                    orchard = Zatoshi(139_000),
                    have = Zatoshi(443_002),
                    need = Zatoshi(500_000),
                ),
                ShortfallReason.classify(account, need = Zatoshi(500_000)),
            )
        }

    /** The SDK gave no figure: neutral copy, with the confirming amount as a note only. */
    @Test
    fun an_unknown_need_is_not_enough_and_carries_the_pending_amount() =
        runTest {
            val account = account(ironwood = balance(available = 20_000, changePending = 443_002), orchard = 139_000)

            assertEquals(
                ShortfallReason.NotEnough(have = Zatoshi(20_000), need = null, pending = Zatoshi(443_002)),
                ShortfallReason.classify(account, need = null),
            )
        }

    private companion object {
        fun balance(
            available: Long,
            changePending: Long = 0,
            valuePending: Long = 0,
        ) = WalletBalance(Zatoshi(available), Zatoshi(changePending), Zatoshi(valuePending))

        suspend fun account(
            ironwood: WalletBalance,
            orchard: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), ironwood),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
