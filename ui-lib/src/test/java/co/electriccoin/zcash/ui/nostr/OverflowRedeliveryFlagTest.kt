package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #8 + #9 redelivery-flag truth table — [NostrChatBridge.shouldFlagRedelivery], the sibling of
 * [NostrChatBridge.shouldConsumeRedelivery] (see RedeliveryGateTest). dispatch()'s post-emit branch must flag
 * a DM for a later dedup-clearing replay whenever it did NOT reach a live collector:
 *
 *   - overflow drop     (emitted == false)                → FLAG  (#8: the buffer-full DM was previously
 *                                                             dropped WITHOUT flagging, so only a foreground
 *                                                             reconnect could ever recover it — apparent loss).
 *   - no collector      (emitted == true, subscriber == 0) → FLAG  (regression-lock the no-collector fix).
 *   - genuine hand-off  (emitted == true, subscriber > 0)  → do NOT flag (a real delivery must never trigger
 *                                                             the costly full-backlog re-decrypt).
 *
 * Pure decision, no Android deps → src/test.
 */
class OverflowRedeliveryFlagTest {

    @Test
    fun `overflow drop with a live collector MUST be flagged for redelivery (#8)`() {
        assertTrue(NostrChatBridge.shouldFlagRedelivery(emitted = false, subscriberCount = 1))
    }

    @Test
    fun `a buffered DM with NO live collector MUST be flagged for redelivery (#9 no-collector)`() {
        assertTrue(NostrChatBridge.shouldFlagRedelivery(emitted = true, subscriberCount = 0))
    }

    @Test
    fun `a genuine hand-off to a live collector must NOT be flagged`() {
        assertFalse(NostrChatBridge.shouldFlagRedelivery(emitted = true, subscriberCount = 1))
    }

    @Test
    fun `overflow drop with NO live collector is still flagged`() {
        assertTrue(NostrChatBridge.shouldFlagRedelivery(emitted = false, subscriberCount = 0))
    }

    @Test
    fun `multiple live collectors on a genuine hand-off still must NOT flag`() {
        assertFalse(NostrChatBridge.shouldFlagRedelivery(emitted = true, subscriberCount = 5))
    }
}
