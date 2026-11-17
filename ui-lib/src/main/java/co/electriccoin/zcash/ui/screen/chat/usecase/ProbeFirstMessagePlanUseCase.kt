package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlan
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlanCheck
import kotlinx.coroutines.CancellationException

/**
 * M2 (fix-all 2.13.0) — the first-message plan check Koin gives ChatViewModel: two never-stored probes of the selected
 * wallet, one for our KEX and one for the message, decided by FirstMessagePlanFunding
 * ([CreateChunkedMessageProposalUseCase.probeFirstMessagePlan]). Anything it cannot answer, a thrown error included,
 * is [FirstMessagePlan.Unchecked] (fail closed: the send pays nothing).
 */
class ProbeFirstMessagePlanUseCase(
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
) : FirstMessagePlanCheck {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun check(
        destination: String,
        messageMemos: Int,
        amountPerOutput: Zatoshi,
    ): FirstMessagePlan =
        try {
            createChunkedMessageProposal.probeFirstMessagePlan(destination, messageMemos, amountPerOutput)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.debug { "First-message plan not checked: ${e.javaClass.simpleName}" }
            FirstMessagePlan.Unchecked(e)
        }
}
