package co.electriccoin.zcash.ui.screen.chat.util

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RED pin for the sealed-pool refusal being swallowed.
 *
 * [OrchardSealedException] is thrown by the proposal chokepoint when every spendable zatoshi sits in
 * the Orchard pool that NU6.3 sealed (ZIP 258). Only `ChatViewModel.sendMessage` pre-empts it with the
 * "Migrate to Ironwood" dialog. Every OTHER send surface — the first message of a new chat in
 * ZchatComposeVM, replies, reactions, group invites, unlocks, payment requests — reports failures
 * through [toZchatUserMessage], which by design never echoes a throwable's own text and recognised only
 * `MemoTooLongException` by type. The refusal therefore degraded to the generic retry prompt with no
 * mention of Ironwood, on all of those paths, every time.
 */
class OrchardSealedErrorMappingTest {
    private val fallback = "Couldn't send your message. Please try again."

    @Test
    fun sealed_pool_refusal_is_explained_not_swallowed() {
        val shown = OrchardSealedException(Zatoshi(135_000)).toZchatUserMessage(fallback)

        assertNotEquals(fallback, shown, "The refusal must not degrade to the generic retry prompt")
        assertTrue(shown.contains("Ironwood"), "The user must be told where the funds have to go, was: $shown")
        assertTrue(shown.contains("Orchard"), "…and why: the Orchard pool is sealed. Was: $shown")
    }

    @Test
    fun sealed_pool_refusal_is_recognised_anywhere_in_the_cause_chain() {
        // Repositories and coroutine plumbing wrap the chokepoint's exception before a ViewModel sees it.
        val wrapped =
            RuntimeException(
                "proposal failed",
                IllegalStateException("repository", OrchardSealedException(Zatoshi(1))),
            )

        assertNotEquals(fallback, wrapped.toZchatUserMessage(fallback))
        assertEquals(
            OrchardSealedException(Zatoshi(1)).toZchatUserMessage(fallback),
            wrapped.toZchatUserMessage(fallback),
        )
    }

    @Test
    fun an_already_surfaced_sealed_pool_string_passes_the_string_entry_point_unchanged() {
        // A SendMessageState.Error carrying the hand-written copy must reach the toast as-is: it is
        // clean text, not raw framework output.
        val text = OrchardSealedException(Zatoshi(1)).message!!

        assertEquals(text, text.toZchatUserMessage(fallback))
    }

    /**
     * RED pin (Fable verification, round 2): the "still confirming" copy contains the words
     * "sealed Orchard pool", so a text classifier that tests the sealed phrase FIRST re-labels the
     * wait-copy as the migrate-copy on the toast path (SendMessageState.Error carries the already
     * mapped string, and AndroidChat re-runs it through String.toZchatUserMessage).
     */
    @Test
    fun confirming_copy_is_not_reclassified_as_the_migrate_copy_on_the_string_path() {
        val confirming = OrchardSealedException(Zatoshi(135_000), ironwoodPending = Zatoshi(676_000)).message!!
        val migrate = OrchardSealedException(Zatoshi(135_000)).message!!
        assertNotEquals(confirming, migrate)

        assertEquals(confirming, confirming.toZchatUserMessage(fallback))
        assertEquals(migrate, migrate.toZchatUserMessage(fallback))
        assertEquals(
            confirming,
            OrchardSealedException(Zatoshi(1), ironwoodPending = Zatoshi(1)).toZchatUserMessage(fallback),
        )
    }
}
