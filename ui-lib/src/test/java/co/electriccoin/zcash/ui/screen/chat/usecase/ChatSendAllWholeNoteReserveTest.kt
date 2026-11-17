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
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * RED pin for the owner's C-8 decision (2026-09-10): the handshake reserve a chat Send All keeps back
 * must stay behind as ONE change note worth exactly the reserve, or Send All must refuse
 * ([SendMaxUnavailable.CouldNotPrice]) rather than send.
 *
 * A chat Send All to a contact whose handshake has not run keeps `handshakeSpendQuote(1)` (17,000 zatoshi)
 * so that handshake can still be paid. [SendMaxSearch] accepts a probe on its fee, pools and transaction
 * count alone, so it cannot see how the kept value is left. With Ironwood notes 431,002 + 12,000 the
 * selector pays the send from the first note and leaves a change of exactly 5,000 next to the untouched
 * 12,000 note. A 5,000 note is uneconomic to spend and neither note alone pays the 17,000 handshake, so
 * the reserve the screen promised cannot pay for what it was kept for. Today Send All returns
 * Max(410,002, fee 15,000) for this wallet.
 *
 * The fix reads the change from the proposal itself (the sweep spec's pure-Kotlin wire reader). When it
 * cannot read that change, the search must fail closed. Either way, this wallet must never get a Send
 * All that splits the reserve: CouldNotPrice, or a Max whose selection leaves exactly one change note
 * of 17,000 and no unselected note. [zip317ChatPrice] stands in for the SDK's selector, as in
 * ChatSendAllReserveSplitTest (which pins today's limitation and has to change with the fix). All
 * addresses are synthetic.
 */
class ChatSendAllWholeNoteReserveTest {
    @Test
    fun the_handshake_reserve_is_never_split_across_notes() =
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

            when (result) {
                SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice) -> Unit
                is SendMaxResult.Max -> {
                    val left = leftBehind(notes, outputs(result.amount.value), result.fee.value)
                    assertEquals(
                        Left(change = keeps, unselected = emptyList()),
                        left,
                        "Send All $result keeps the $keeps-zatoshi reserve as $left: split, so no single note " +
                            "can pay the handshake it was kept for",
                    )
                }
                else -> fail("expected CouldNotPrice or a Max that keeps the reserve whole, got $result")
            }
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
