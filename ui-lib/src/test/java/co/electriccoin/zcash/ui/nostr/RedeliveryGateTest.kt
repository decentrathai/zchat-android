package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #8: the watchdog forces a dedup-clearing backlog replay only when a DM/reaction was actually missed
 * for lack of a live collector AND a ChatViewModel collector is alive NOW. [NostrChatBridge.shouldConsumeRedelivery]
 * is that gate; this pins the defer-vs-consume decision. (Consume-once itself is AtomicBoolean.getAndSet —
 * a stdlib guarantee — so it isn't re-tested here.) Pure decision (no Android deps) → src/test.
 */
class RedeliveryGateTest {

    @Test
    fun `consume when a miss is pending and a collector is alive`() {
        assertTrue(NostrChatBridge.shouldConsumeRedelivery(pendingRedeliverySet = true, subscriberCount = 1))
    }

    @Test
    fun `defer when a miss is pending but NO collector is alive`() {
        // The flag must stay set (caller returns false without clearing) so the miss survives to a later tick.
        assertFalse(NostrChatBridge.shouldConsumeRedelivery(pendingRedeliverySet = true, subscriberCount = 0))
    }

    @Test
    fun `do nothing when nothing was missed, even with a live collector`() {
        assertFalse(NostrChatBridge.shouldConsumeRedelivery(pendingRedeliverySet = false, subscriberCount = 3))
    }

    @Test
    fun `do nothing when neither a miss is pending nor a collector is alive`() {
        assertFalse(NostrChatBridge.shouldConsumeRedelivery(pendingRedeliverySet = false, subscriberCount = 0))
    }

    @Test
    fun `multiple collectors still consume a pending miss`() {
        assertTrue(NostrChatBridge.shouldConsumeRedelivery(pendingRedeliverySet = true, subscriberCount = 5))
    }
}
