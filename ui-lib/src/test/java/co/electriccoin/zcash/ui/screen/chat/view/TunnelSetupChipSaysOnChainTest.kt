package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1275: during a Tunnel handshake the chip says "End-to-end encrypted over NOSTR — not on-chain", while the setup goes
 * on-chain and is paid.
 *
 * Seams: `object TunnelSetupChip { fun shows(mode: ConversationMode, handshakeComplete: Boolean): Boolean }` in
 * `screen.chat.model` (by reflection, so the module's other tests compile before it exists), the text
 * `chat_privacy_chip_tunnel_setup` (EN, ES). Which branch the chip takes is read from the source: a JVM test cannot compose
 * `PrivacyStatusCard`. What makes a handshake "complete" is the implementer's to wire, and is a phone check.
 */
class TunnelSetupChipSaysOnChainTest {
    private fun shows(
        mode: ConversationMode,
        handshakeComplete: Boolean,
    ): Boolean {
        val type =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.TunnelSetupChip") }.getOrNull()
        val chip = assertNotNull(type, "TunnelSetupChip does not exist")
        val method = assertNotNull(chip.methods.firstOrNull { it.name == "shows" }, "TunnelSetupChip.shows does not exist")
        return method.invoke(chip.getField("INSTANCE").get(null), mode, handshakeComplete) as Boolean
    }

    @Test
    fun only_an_unfinished_tunnel_handshake_says_setup() {
        assertTrue(shows(ConversationMode.TUNNEL, handshakeComplete = false))
        assertFalse(shows(ConversationMode.TUNNEL, handshakeComplete = true))
        assertFalse(shows(ConversationMode.OPEN, handshakeComplete = false), "Open has no on-chain setup")
        assertFalse(shows(ConversationMode.VAULT, handshakeComplete = false))
    }

    @Test
    fun the_chip_checks_for_the_setup_before_it_says_not_on_chain() {
        val source =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        val start = source.indexOf("private fun PrivacyStatusCard(")
        val card = source.substring(start, source.indexOf("\n@Composable", start))
        val setup = card.indexOf("R.string.chat_privacy_chip_tunnel_setup")
        val nostr = card.indexOf("R.string.chat_privacy_chip_nostr")
        assertTrue(setup >= 0, "the chip has no setup text")
        assertTrue(setup < nostr, "the chip says not on-chain first")
        assertTrue("TunnelSetupChip.shows(" in card, "the chip does not ask the rule")
    }

    @Test
    fun the_setup_text_says_on_chain_in_both_languages() {
        val en = assertNotNull(ChatResourceText.strings("values")["chat_privacy_chip_tunnel_setup"], "no English text")
        val es = assertNotNull(ChatResourceText.strings("values-es")["chat_privacy_chip_tunnel_setup"], "no Spanish text")
        assertNotEquals(en, es)
        assertTrue("on-chain" in en, en)
        assertFalse("not on-chain" in en, en)
        assertTrue("cadena" in es, es)
        assertFalse("no va en la cadena" in es, es)
        for (text in listOf(en.lowercase(), es.lowercase())) {
            assertFalse("free" in text || "gratis" in text, "setup is paid: $text")
        }
        assertEquals(en.count { it == '%' }, es.count { it == '%' })
    }
}
