package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * ZC-1472 (money-path rule 4: a "sent, N ZEC" claim comes from the send, never from the quote) — a coroutine-context
 * element a caller puts around an on-chain chat send to learn what that send pays. [CreateChunkedMessageProposalUseCase]
 * reports, right before it hands the transaction to the network, the total of the proposal it built: every output, the
 * platform fee and the proposal's own network fee; null when that fee could not be read. Nothing is reported for a
 * send that never reached that point. The last report of a send that waited for a block and tried again is the one
 * that counts. The send itself is unchanged.
 */
class ProposalPriceSink(
    val onPriced: (Zatoshi?) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProposalPriceSink>
}
