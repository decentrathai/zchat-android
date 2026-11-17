package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RED pin for typed money refusals reaching the user as a Java stack trace.
 *
 * The scanned-ZIP321, swap and Flexa funnels catch everything that is not InsufficientFundsException or
 * TransactionProposalNotCreatedException and hand it to ErrorArgs.General, which renders
 * `stackTraceToString()`. OrchardSealedException and PublicCrossingException carry sentences written for
 * the user; showing them wrapped in a stack trace wastes the only explanation the user gets.
 */
class MoneyRefusalMessageTest {
    @Test
    fun a_sealed_pool_refusal_is_recognised_and_returns_its_own_sentence() {
        val e = OrchardSealedException(Zatoshi(135_000))

        assertEquals(OrchardSealedException.ORCHARD_SEALED_MESSAGE, moneyRefusalMessage(e))
    }

    @Test
    fun a_public_crossing_refusal_is_recognised() {
        val e = PublicCrossingException(Zatoshi(818_000), Zatoshi(20_000))

        assertEquals(PublicCrossingException.PUBLIC_CROSSING_MESSAGE, moneyRefusalMessage(e))
    }

    /** Repositories and coroutine plumbing wrap the refusal before a funnel's catch sees it. */
    @Test
    fun it_is_found_through_a_wrapped_cause_chain() {
        val wrapped = RuntimeException("proposal failed", IllegalStateException("repo", OrchardSealedException(Zatoshi(1))))

        assertEquals(OrchardSealedException.ORCHARD_SEALED_MESSAGE, moneyRefusalMessage(wrapped))
    }

    /** Everything else keeps the existing generic rendering — this must not swallow real diagnostics. */
    @Test
    fun ordinary_failures_are_left_alone() {
        assertNull(moneyRefusalMessage(IllegalStateException("boom")))
        assertNull(moneyRefusalMessage(RuntimeException("network", java.io.IOException("timeout"))))
    }
}
