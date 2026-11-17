package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.isTransientWait
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.built
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.decide
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.nameOf
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.outpoint
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1909 (owner test of release APK 17a900ce0411 at 65f4f1323, 16 Nov 2026; register high, money-path rules 3 and 4):
 * in a fresh Shielded chat the first send showed "sending" for minutes, then the red "Insufficient balance for a Shielded
 * message …", although the wallet had just been funded; Retry then worked.
 *
 * Hypothesis A (code read): three places answer "will this be spendable once it confirms?" with two rules.
 * - The first-message plan gate (FirstMessagePlanFunding.decide) lets the KEX be paid when one KEX change note covers
 *   the message by spendableCovers: exactly its cost, or the cost plus Zip317.MARGINAL_FEE_ZATOSHI (5,000) or more.
 * - The send-time classifier (ShortfallReason.classify) said StillConfirming only when have + pending reached the need
 *   plus CONFIRMING_NOTES_FEE (10,000).
 * - The note-wait loop after the KEX waits only on the classifier's "…confirm on-chain…" text (isTransientWait).
 * So a KEX change of exactly the cost, or of cost + 5,000 .. cost + 9,999, passed the gate, the KEX was paid, and the
 * message's shortfall became NotEnough: a hard error after a paid setup step.
 *
 * Wanted: one rule. Over the whole margin range the gate is Fundable exactly when the classifier, given that change
 * note as the only Ironwood still confirming, says StillConfirming, and exactly when the loop's error is a wait.
 * All txids and addresses are synthetic.
 */
class ConfirmingCoverAgreementTest {
    private companion object {
        /** One memo at 1,000 zat: 1,000 + 1,000 platform fee + 15,000 network fee. */
        const val ONE_MEMO_COST = 17_000L

        /** Two memos: one more output, one more 5,000 action. */
        const val TWO_MEMO_COST = 23_000L
        const val MARGIN = Zip317.MARGINAL_FEE_ZATOSHI
        const val TX_A = "aa000000000000000000000000000000000000000000000000000000000019091"
    }

    private fun account(
        available: Long,
        changePending: Long,
    ): ZashiAccount =
        runBlocking {
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0)),
                        WalletBalance(Zatoshi(available), Zatoshi(changePending), Zatoshi(0)),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
        }

    /** The gate's answer for a KEX (one note in, [change] out) and a message that spends the same note. */
    private fun gate(
        change: Long,
        cost: Long,
    ): Boolean {
        val note = outpoint(TX_A, 0, change + ONE_MEMO_COST)
        return nameOf(decide(built(listOf(note), listOf(change)), built(listOf(note), listOf(change)), cost)) == "Fundable"
    }

    @Test
    fun `the gate and the classifier agree over the whole margin range`() {
        val base = account(available = 0, changePending = 0)
        for (cost in listOf(ONE_MEMO_COST, TWO_MEMO_COST)) {
            val disagreements = mutableListOf<Long>()
            for (change in (cost - 1)..(cost + 3 * MARGIN)) {
                val acct = base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(0), Zatoshi(change), Zatoshi(0))))
                val waits = ShortfallReason.classify(acct, Zatoshi(cost)) is ShortfallReason.StillConfirming
                if (gate(change, cost) != waits) disagreements += change
            }
            assertTrue(
                disagreements.isEmpty(),
                "cost $cost: the gate lets the KEX be paid but the send then calls the change 'not enough' (or the other way) " +
                    "for ${disagreements.size} change values, first ${disagreements.take(3)}, last ${disagreements.takeLast(3)}",
            )
        }
    }

    @Test
    fun `the note-wait loop waits exactly when the gate said the change pays the message`() {
        val base = account(available = 0, changePending = 0)
        val cost = ONE_MEMO_COST
        val disagreements = mutableListOf<Long>()
        for (change in (cost - 1)..(cost + 3 * MARGIN)) {
            val acct = base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(0), Zatoshi(change), Zatoshi(0))))
            val notice =
                CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                    reason = ShortfallReason.classify(acct, Zatoshi(cost)),
                    isValueTransfer = false,
                )
            val loopWaits = isTransientWait(InsufficientFundsException(notice))
            if (gate(change, cost) != loopWaits) disagreements += change
        }
        assertTrue(
            disagreements.isEmpty(),
            "the loop and the gate disagree for ${disagreements.size} change values: ${disagreements.take(5)}",
        )
    }

    @Test
    fun `a KEX change of cost plus 5,000 to 9,999 is a wait, not 'not enough'`() {
        for (extra in listOf(MARGIN, MARGIN + 1, 7_000L, 2 * MARGIN - 1)) {
            val reason = ShortfallReason.classify(account(available = 0, changePending = ONE_MEMO_COST + extra), Zatoshi(ONE_MEMO_COST))
            assertEquals(
                ShortfallReason.StillConfirming(pending = Zatoshi(ONE_MEMO_COST + extra), need = Zatoshi(ONE_MEMO_COST)),
                reason,
                "change = cost + $extra",
            )
        }
        // Exactly the cost (no change output) pays it too.
        assertTrue(ShortfallReason.classify(account(0, ONE_MEMO_COST), Zatoshi(ONE_MEMO_COST)) is ShortfallReason.StillConfirming)
    }

    @Test
    fun `control - a change the SDK refuses for the message stays not enough`() {
        // 1 .. 4,999 above the cost leaves a change below the marginal fee, which the SDK refuses: nothing to wait for.
        for (extra in listOf(1L, 2_000L, MARGIN - 1)) {
            val reason = ShortfallReason.classify(account(available = 0, changePending = ONE_MEMO_COST + extra), Zatoshi(ONE_MEMO_COST))
            assertTrue(reason is ShortfallReason.NotEnough, "change = cost + $extra was $reason")
            assertTrue(!gate(ONE_MEMO_COST + extra, ONE_MEMO_COST), "the gate paid a KEX for a change of cost + $extra")
        }
        // Below the cost: never a wait.
        assertTrue(ShortfallReason.classify(account(0, ONE_MEMO_COST - 1), Zatoshi(ONE_MEMO_COST)) is ShortfallReason.NotEnough)
        // Nothing pending: never a wait, whatever is available.
        assertTrue(ShortfallReason.classify(account(10_000, 0), Zatoshi(ONE_MEMO_COST)) is ShortfallReason.NotEnough)
    }
}
