package co.electriccoin.zcash.ui.service

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1559 (Fable review of ZC-1555's fix, finding 1): after a sync the inbox service leaves the foreground unless it is
 * needed, and an idle Android 16 phone then has no network for it. "Needed" read only Open and Tunnel chats, but a
 * Shielded chat with a call channel (the peer's Nostr key is held: ChatDetailView shows the call button for
 * hasNostrCallChannel) also receives its calls over the relays. So a callee whose chats are all Shielded missed every
 * call on an idle phone. The rule reads the conversation-mode preferences ("mode:<peer>", "pubkey:<peer>").
 *
 * At RED there is no RelayInboxKeepAlive.
 */
class RelayInboxStaysUpForEveryCallChannelTest {
    private fun needed(entries: Map<String, Any?>): Boolean {
        val k = Class.forName("co.electriccoin.zcash.ui.service.RelayInboxKeepAlive")
        return k.getMethod("needed", Map::class.java).invoke(k.getField("INSTANCE").get(null), entries) as Boolean
    }

    @Test
    fun `a Shielded chat whose peer Nostr key is held keeps the inbox up`() {
        assertTrue(needed(mapOf("mode:u1peer" to "VAULT", "pubkey:u1peer" to "ab".repeat(32))))
    }

    @Test
    fun `an Open or a Tunnel chat keeps the inbox up`() {
        assertTrue(needed(mapOf("mode:u1a" to "OPEN")))
        assertTrue(needed(mapOf("mode:u1b" to "TUNNEL")))
    }

    @Test
    fun `no relay chat and no call channel lets it go`() {
        assertFalse(needed(emptyMap()))
        assertFalse(needed(mapOf("mode:u1c" to "VAULT", "hasSeenModeIntro" to true)))
        assertFalse(needed(mapOf("pubkey:u1d" to "", "mode:u1d" to "VAULT")))
    }
}
