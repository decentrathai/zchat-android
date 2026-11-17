package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What the Wallet-tab card, the chat dialog and the sheet decide about the sealed Orchard balance (sweep
 * spec §2.4), in order: nothing, Keystone, still pending, too small (25,000 or less), available. The
 * owner's phones: HONOR 139,000 of Orchard (+ 443,002 of Ironwood), Seeker about 818,000. All addresses
 * are synthetic.
 */
class OrchardSweepDecisionTest {
    @Test
    fun each_state_in_order() =
        runTest {
            assertEquals(OrchardSweepDecision.None, decideOrchardSweep(zashi(orchard = 0)))
            assertEquals(OrchardSweepDecision.None, decideOrchardSweep(keystone(orchard = 0)))
            assertEquals(
                OrchardSweepDecision.Keystone(Zatoshi(139_000)),
                decideOrchardSweep(keystone(orchard = 139_000)),
            )
            assertEquals(
                OrchardSweepDecision.WaitForSync(orchard = Zatoshi(139_000), pending = Zatoshi(39_000)),
                decideOrchardSweep(zashi(orchard = 100_000, orchardPending = 39_000)),
            )
            assertEquals(
                OrchardSweepDecision.WaitForSync(orchard = Zatoshi(39_000), pending = Zatoshi(39_000)),
                decideOrchardSweep(zashi(orchard = 0, orchardPending = 39_000)),
            )
        }

    @Test
    fun the_threshold_is_25000_inclusive() =
        runTest {
            assertEquals(25_000L, MIN_SWEEPABLE_ORCHARD)
            assertEquals(20_000L, MIN_ORCHARD_SWEEP_FEE)
            assertEquals(OrchardSweepDecision.TooSmall(Zatoshi(1)), decideOrchardSweep(zashi(orchard = 1)))
            assertEquals(OrchardSweepDecision.TooSmall(Zatoshi(25_000)), decideOrchardSweep(zashi(orchard = 25_000)))
            assertEquals(OrchardSweepDecision.Available(Zatoshi(25_001)), decideOrchardSweep(zashi(orchard = 25_001)))
        }

    @Test
    fun the_owner_phones_can_move() =
        runTest {
            assertEquals(
                OrchardSweepDecision.Available(Zatoshi(139_000)),
                decideOrchardSweep(zashi(orchard = 139_000, ironwood = 443_002)),
            )
            assertEquals(OrchardSweepDecision.Available(Zatoshi(139_000)), decideOrchardSweep(zashi(orchard = 139_000)))
            assertEquals(OrchardSweepDecision.Available(Zatoshi(818_000)), decideOrchardSweep(zashi(orchard = 818_000)))
        }

    @Test
    fun the_chat_dialog_offers_a_move_only_when_one_can_arrive() {
        assertEquals(ChatSweepPrompt.MOVE, chatSweepPrompt(Zatoshi(139_000), ironwoodPending = Zatoshi(0)))
        assertEquals(ChatSweepPrompt.MOVE, chatSweepPrompt(Zatoshi(25_001), ironwoodPending = Zatoshi(0)))
        assertEquals(ChatSweepPrompt.TOO_SMALL, chatSweepPrompt(Zatoshi(25_000), ironwoodPending = Zatoshi(0)))
        assertEquals(ChatSweepPrompt.TOO_SMALL, chatSweepPrompt(Zatoshi(1), ironwoodPending = Zatoshi(0)))
        assertEquals(ChatSweepPrompt.NONE, chatSweepPrompt(Zatoshi(0), ironwoodPending = Zatoshi(0)))
        // Ironwood confirming: waiting is the answer, the dialog stays as it was.
        assertEquals(ChatSweepPrompt.NONE, chatSweepPrompt(Zatoshi(139_000), ironwoodPending = Zatoshi(1_000)))
    }

    private companion object {
        fun balance(
            available: Long,
            pending: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        suspend fun unified(
            orchard: Long,
            orchardPending: Long,
            ironwood: Long
        ) = UnifiedInfo(
            WalletAddress.Unified.new("usynthetic-self"),
            balance(orchard, orchardPending),
            balance(ironwood),
        )

        suspend fun zashi(
            orchard: Long,
            orchardPending: Long = 0,
            ironwood: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = unified(orchard, orchardPending, ironwood),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

        suspend fun keystone(orchard: Long) =
            KeystoneAccount(
                sdkAccount = AccountFixture.new(),
                unified = unified(orchard, 0, 0),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
