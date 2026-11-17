package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SpendableCover
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase

/**
 * The memo an in-chat payment carries. `sendPayment` sends it and the Payment dialog counts its chunks
 * for the fee, so both read it from here.
 */
fun paymentMemoText(memo: String): String = if (memo.isNotBlank()) "Payment: $memo" else "Payment from ZCHAT"

/** How many memo outputs [paymentMemoText] takes: a payment goes out in the reply format. */
fun paymentMemoChunks(memo: String): Int =
    ZMSGProtocol.calculateChunkCount(paymentMemoText(memo), isInitMessage = false).coerceAtLeast(1)

/**
 * The least an in-chat payment of [amount] takes from the wallet (C-5): the amount on output 0, dust on
 * each extra memo output, the platform-fee output, and the ZIP 317 fee for those outputs plus a change
 * output. More input notes cost more, so this is a floor; the SDK's proposal still decides.
 */
fun paymentOutlay(
    amount: Zatoshi,
    memoChunks: Int,
): Zatoshi {
    val chunks = memoChunks.coerceAtLeast(1)
    return Zatoshi(
        CreateChunkedMessageProposalUseCase.totalCost(chunks, amount).value +
            Zip317.conventionalFeeZatoshi(chunks + 2)
    )
}

/** The part of [paymentOutlay] that is fees and dust rather than the amount itself. */
fun paymentFees(
    amount: Zatoshi,
    memoChunks: Int,
): Zatoshi = Zatoshi(paymentOutlay(amount, memoChunks).value - amount.value)

/**
 * Whether the Payment dialog may offer Send for [amount] (C-5). In zatoshi, never Doubles, and against
 * [peerSendable], the Ironwood that can reach another person: sealed Orchard and confirming ZEC cannot
 * fund a peer payment, and the fees come on top of the amount.
 */
fun canAffordPayment(
    amount: Zatoshi,
    memoChunks: Int,
    peerSendable: Zatoshi,
): Boolean = amount.value > 0 && spendableCovers(peerSendable.value, paymentOutlay(amount, memoChunks).value)

/**
 * ZC-1417 — whether [spendableZat] can pay a transaction of [costZat] before the question is asked. The SDK refuses a
 * non-zero change below [Zip317.MARGINAL_FEE_ZATOSHI] (it then needs a larger input), so a wallet holding a little more
 * than the cost cannot pay it: only exactly the cost (no change) or at least the cost plus the marginal fee does.
 */
fun spendableCovers(
    spendableZat: Long,
    costZat: Long,
): Boolean = SpendableCover.covers(spendableZat, costZat)

/** ZC-1417 — what [spendable] must reach to pay [cost] by [spendableCovers]: the cost, or above it the cost plus the margin. */
fun spendableNeed(
    spendable: Zatoshi,
    cost: Zatoshi,
): Zatoshi = if (spendable.value > cost.value) Zatoshi(cost.value + Zip317.MARGINAL_FEE_ZATOSHI) else cost
