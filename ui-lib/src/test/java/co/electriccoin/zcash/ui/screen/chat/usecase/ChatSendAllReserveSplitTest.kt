package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What a chat Send All that keeps the handshake reserve (C-8) guarantees.
 *
 * What leaves the wallet is exactly `V - keeps`, so what stays behind totals exactly `keeps`, and since
 * the owner's C-8 decision (2026-09-10) it stays behind as ONE Ironwood change output of `keeps`:
 * [SendMaxSearch] reads the change from each probe's proposal. Ironwood notes 431,002 + 12,000 with the
 * 17,000 reserve used to be accepted with the reserve split into a 5,000 change (uneconomic, never
 * spendable) and the untouched 12,000 note, neither of which pays the 17,000 handshake that follows. That
 * split is now [SendMaxUnavailable.CouldNotPrice]: the user enters an amount instead.
 *
 * [zip317ChatPrice] stands in for the SDK's selector; [leftBehind] replays its selection to show where the
 * reserve ends up. All addresses are synthetic.
 */
class ChatSendAllReserveSplitTest {
    @Test
    fun a_reserve_the_selector_would_split_is_could_not_price() =
        runTest {
            val keeps = handshakeSpendQuote(peers = 1).value
            val notes = listOf(431_002L, 12_000L)

            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account(ironwood = notes.sum()) },
                    probe = { _, amount -> zip317ChatPrice(notes, outputs(amount.value)) },
                    shape = CreateChunkedMessageProposalUseCase.sendAllShape(1, Zatoshi(keeps)),
                )

            assertEquals(SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice), result)

            // What the old acceptance left behind at that price: a split reserve, its 5,000 change unspendable.
            val left = leftBehind(notes, outputs(410_002L), 15_000L)
            assertEquals(keeps, left.change + left.unselected.sum())
            assertEquals(Zip317.MARGINAL_FEE_ZATOSHI, left.change)
            assertEquals(listOf(12_000L), left.unselected)
        }

    /** The one-note layout the C-8 tests use: there the change IS the reserve. */
    @Test
    fun a_single_note_leaves_the_whole_reserve_as_change() =
        runTest {
            val keeps = handshakeSpendQuote(peers = 1).value
            val notes = listOf(443_002L)

            val max =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account(ironwood = notes.sum()) },
                    probe = { _, amount -> zip317ChatPrice(notes, outputs(amount.value)) },
                    shape = CreateChunkedMessageProposalUseCase.sendAllShape(1, Zatoshi(keeps)),
                ) as SendMaxResult.Max

            val left = leftBehind(notes, outputs(max.amount.value), max.fee.value)
            assertEquals(Left(change = keeps, unselected = emptyList()), left)
            // The guarantee: nothing beyond V - keeps leaves.
            assertEquals(notes.sum() - keeps, max.amount.value + PLATFORM_FEE + max.fee.value)
        }

    private data class Left(
        val change: Long,
        val unselected: List<Long>,
    )

    private companion object {
        const val PLATFORM_FEE = 1_000L

        /** A one-chunk chat send: output 0 to the peer and the platform-fee output. */
        fun outputs(amount: Long) = listOf(amount, PLATFORM_FEE)

        /** [zip317ChatPrice]'s oldest-first selection at [fee]: the change and the notes it left. */
        fun leftBehind(
            notes: List<Long>,
            outputs: List<Long>,
            fee: Long,
        ): Left {
            val need = outputs.sum() + fee
            var sum = 0L
            var selected = 0
            while (sum < need) sum += notes[selected++]
            return Left(change = sum - need, unselected = notes.drop(selected))
        }

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(ironwood: Long) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(139_000),
                        balance(ironwood),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
