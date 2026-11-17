package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 3 report 4 item 11 — a proposal the wire reader could not read is refused (R3-1, fail closed), but
 * the user is shown the generic "Message couldn't be sent — please try again" copy. That is wrong twice: no
 * retry can fix it, and it does not say the send was stopped on purpose, with nothing sent, to avoid a
 * payment whose amount would become public.
 *
 * The refusal must carry its own sentence, reach the user through the one chat error mapper, be named as a
 * money refusal by the generic error screen, and be translatable like every other send notice.
 */
class UnreadableProposalIsExplainedTest {
    private val genericChatFallback = "Message couldn't be sent — please try again."

    /** The refusal as the chat sees it: thrown inside refusePublicCrossing, wrapped on the way out. */
    private fun asTheChatSeesIt(): Throwable = TransactionProposalNotCreatedException(unreadableProposalRefusal())

    @Test
    fun the_refusal_is_not_the_generic_try_again_copy() {
        val shown = asTheChatSeesIt().toZchatUserMessage(fallback = genericChatFallback)

        assertNotEquals(
            genericChatFallback,
            shown,
            "a proposal ZChat could not read is not a hiccup the user can retry away",
        )
    }

    @Test
    fun the_refusal_says_that_nothing_was_sent() {
        val shown = asTheChatSeesIt().toZchatUserMessage(fallback = genericChatFallback)

        assertTrue(
            "nothing was sent" in shown.lowercase(),
            "the user must be told the send was stopped before anything went out: $shown",
        )
    }

    /** The whole point of the R3-1 refusal: the send may be exactly the pool crossing that publishes an amount. */
    @Test
    fun the_refusal_says_why_it_stopped() {
        val shown = asTheChatSeesIt().toZchatUserMessage(fallback = genericChatFallback)

        assertTrue(
            "pool" in shown.lowercase() || "public" in shown.lowercase(),
            "the user must learn this is a privacy stop, not a failure: $shown",
        )
    }

    /** The generic error screen renders stackTraceToString() unless the refusal is named here. */
    @Test
    fun the_error_screen_shows_it_as_a_money_refusal() {
        assertEquals(
            asTheChatSeesIt().toZchatUserMessage(fallback = genericChatFallback),
            moneyRefusalMessage(asTheChatSeesIt()),
            "the refusal the error screen shows must be the same sentence the chat shows",
        )
    }

    /** Round 2 report 4 item 16: every reason the mapper can show reaches a Spanish phone in Spanish. */
    @Test
    fun the_refusal_has_a_translation() {
        val shown = asTheChatSeesIt().toZchatUserMessage(fallback = genericChatFallback)

        assertNotNull(ChatSendCopy.localizedResource(shown), "no string resource for: $shown")
    }

    /** The refusal must still be found when the failure is wrapped again on the way to the screen. */
    @Test
    fun it_survives_a_wrapped_cause_chain() {
        val wrapped = RuntimeException("send failed", IllegalStateException("repo", asTheChatSeesIt()))

        assertEquals(
            asTheChatSeesIt().toZchatUserMessage(fallback = genericChatFallback),
            wrapped.toZchatUserMessage(fallback = genericChatFallback),
        )
    }

    /** It must not steal any other failure's words. */
    @Test
    fun ordinary_failures_are_left_alone() {
        assertEquals(genericChatFallback, IllegalStateException("boom").toZchatUserMessage(genericChatFallback))
    }
}
