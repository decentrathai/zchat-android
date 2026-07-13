package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.view.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ConversationMode] — the default the new-conversation picker falls back
 * to, the per-mode capability flags the router relies on, and the human-readable labels shown
 * in the compose-screen selector and the chat overflow picker.
 */
class ConversationModeTest {

    @Test
    fun `default mode is Vault (most private)`() {
        // The new-conversation compose flow and getConversationMode both fall back to this when
        // the user never explicitly picks a mode.
        assertEquals(ConversationMode.VAULT, ConversationMode.DEFAULT)
    }

    @Test
    fun `there are exactly three transport modes`() {
        assertEquals(
            listOf(ConversationMode.VAULT, ConversationMode.TUNNEL, ConversationMode.OPEN),
            ConversationMode.entries
        )
    }

    @Test
    fun `labels are stable and unique`() {
        // VAULT's DISPLAY name is "Shielded" (user-facing rename); the enum name stays VAULT
        // because the wire format + stored prefs depend on it.
        assertEquals("Shielded", ConversationMode.VAULT.label())
        assertEquals("Tunnel", ConversationMode.TUNNEL.label())
        assertEquals("Open", ConversationMode.OPEN.label())

        val labels = ConversationMode.entries.map { it.label() }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `vault is shielded-only and disables calls`() {
        assertTrue(ConversationMode.VAULT.isShieldedOnlyTransport)
        assertFalse(ConversationMode.VAULT.supportsCalls)
        assertFalse(ConversationMode.VAULT.isNostrTransport)
        assertFalse(ConversationMode.VAULT.needsBootstrap)
    }

    @Test
    fun `tunnel uses nostr transport, needs bootstrap, and allows calls`() {
        assertFalse(ConversationMode.TUNNEL.isShieldedOnlyTransport)
        assertTrue(ConversationMode.TUNNEL.supportsCalls)
        assertTrue(ConversationMode.TUNNEL.isNostrTransport)
        assertTrue(ConversationMode.TUNNEL.needsBootstrap)
    }

    @Test
    fun `open uses nostr transport without bootstrap and allows calls`() {
        assertFalse(ConversationMode.OPEN.isShieldedOnlyTransport)
        assertTrue(ConversationMode.OPEN.supportsCalls)
        assertTrue(ConversationMode.OPEN.isNostrTransport)
        assertFalse(ConversationMode.OPEN.needsBootstrap)
    }
}
