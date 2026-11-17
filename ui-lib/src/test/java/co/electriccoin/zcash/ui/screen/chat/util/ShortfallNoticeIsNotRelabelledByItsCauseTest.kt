package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.SubmitRejectedException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D1 (E2) — once the use case's shortfall notice carries the rejection that proves nothing was stored as its cause,
 * [toZchatUserMessage] joins every message in the chain before it classifies. The rejection's text embeds the SDK's
 * "Insufficient balance (have A, need R including fee)", which matches the generic shortfall case first, so the
 * "still confirming, wait" notice ([CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE]) is shown as
 * "add ZEC" ([CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE]).
 *
 * Wanted: a shortfall notice the use case wrote is shown as it was chosen, whatever its cause says.
 */
class ShortfallNoticeIsNotRelabelledByItsCauseTest {
    private fun rejectionThatStoredNothing() =
        SubmitRejectedException(
            "Transaction submit failed (code=0 Insufficient balance (have 0, need 17000 including fee))",
            nothingStored = true,
        )

    /** Built with initCause, so this file compiles with and without the exception's `cause` parameter. */
    private fun notice(text: String): InsufficientFundsException =
        InsufficientFundsException(text).also { it.initCause(rejectionThatStoredNothing()) }

    @Test
    fun `a still-confirming notice with a nothing-stored rejection as its cause keeps its wait copy`() {
        assertEquals(
            CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
            notice(CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE).toZchatUserMessage("synthetic fallback"),
        )
    }

    /** Control, GREEN before and after: the other two notices keep their copy too. */
    @Test
    fun `control - the add-ZEC and payment notices keep their copy with the same cause`() {
        assertEquals(
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
            notice(CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE).toZchatUserMessage("synthetic fallback"),
        )
        assertEquals(
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE,
            notice(CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE).toZchatUserMessage("synthetic fallback"),
        )
    }

    /** Control, GREEN before and after: a shortfall with no words of its own is still classified from its chain. */
    @Test
    fun `control - a shortfall with a blank message is classified from its cause`() {
        val blank = InsufficientFundsException().also { it.initCause(rejectionThatStoredNothing()) }
        assertEquals(
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
            blank.toZchatUserMessage("synthetic fallback"),
        )
    }
}
