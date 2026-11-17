package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.derivePrivacyStatus
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1479 + the in-chat part of ZC-1472 (owner findings 2 Nov, points 12 and 19a; FIX_PLAN REVISION 1, B2; Fable M1, S9).
 *
 * Today every chat body shows lines that ask nothing of the user: the violet "Extra Security (Post-Quantum): On … long
 * press to turn off" banner, the amber PENDING banner, "Messages are shielded on-chain via Zcash" /
 * "End-to-end encrypted over NOSTR — not on-chain", and under the composer "Free — sent over NOSTR". After Open → Tunnel
 * Apply the chat looks the same, so the owner cannot tell whether a handshake ran or ZEC was spent.
 *
 * Wanted, as two pure rules in `screen.chat.model` that ChatDetailView wires:
 * - `object ChatStatusLines { fun show(mode: ConversationMode, privacyStatus: PrivacyStatus,
 *   qsStatus: QuantumShieldStatus, tunnelHandshakeComplete: Boolean): Collection<*> }` — the status lines the chat body
 *   shows. Empty for a healthy chat in every mode and every Extra Security status (Extra Security moves to the header
 *   chip); non-empty for an unfinished Tunnel setup (paid, ZC-1275 chip kept), stranded or confirming Ironwood funds and
 *   a wallet that needs shielding.
 * - `object SendCostLabel { fun text(mode: ConversationMode, tunnelSendIsFree: Boolean, cost: Zatoshi): Any? }` — the
 *   price on the send button. Non-null wherever a send spends ZEC (Shielded, and Tunnel before its handshake is done),
 *   null for Open and a finished Tunnel. M1: the composer cost row is removed and this label added IN THE SAME COMMIT,
 *   so a paid single message never goes out without its price on screen.
 *
 * The new API is reached by name, so this file compiles before it exists. Which line the screen draws is a phone check
 * (no Compose tests here); the source checks at the end only pin that the screen asks these rules.
 */
class ChatBodyShowsOnlyLinesThatNeedTheUserTest {
    private fun objectMethod(
        className: String,
        name: String,
        arity: Int,
        missing: String,
    ): Pair<Any?, Method> {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.$className") }.getOrElse {
                throw AssertionError("no co.electriccoin.zcash.ui.screen.chat.model.$className: $missing")
            }
        val method =
            cls.methods.firstOrNull { it.name == name && it.parameterCount == arity }
                ?: throw AssertionError("$className has no $name with $arity parameters")
        val receiver =
            if (Modifier.isStatic(method.modifiers)) null else cls.getField("INSTANCE").get(null)
        return receiver to method
    }

    private fun lines(
        mode: ConversationMode,
        privacy: PrivacyStatus,
        qs: QuantumShieldStatus,
        tunnelHandshakeComplete: Boolean,
    ): Collection<*> {
        val (receiver, method) = objectMethod("ChatStatusLines", "show", 4, "the chat body still decides its lines inline")
        val result = method.invoke(receiver, mode, privacy, qs, tunnelHandshakeComplete)
        return assertNotNull(result as? Collection<*>, "ChatStatusLines.show must return the collection of visible lines, was $result")
    }

    private fun costLabel(
        mode: ConversationMode,
        tunnelSendIsFree: Boolean,
    ): Any? {
        val (receiver, method) = objectMethod("SendCostLabel", "text", 3, "the price is still a composer row, not on the send button")
        return method.invoke(receiver, mode, tunnelSendIsFree, COST)
    }

    @Test
    fun a_healthy_chat_shows_no_status_line_in_any_mode_or_extra_security_status() {
        val healthy =
            listOf(
                Triple(ConversationMode.VAULT, true, "Shielded"),
                Triple(ConversationMode.TUNNEL, true, "finished Tunnel"),
                Triple(ConversationMode.OPEN, true, "Open"),
                Triple(ConversationMode.OPEN, false, "Open before any handshake (Open has no on-chain setup)"),
            )
        for ((mode, handshakeDone, label) in healthy) {
            for (qs in QuantumShieldStatus.values()) {
                val shown = lines(mode, HEALTHY, qs, handshakeDone)
                assertTrue(shown.isEmpty(), "$label with Extra Security $qs still shows body lines: $shown")
            }
        }
    }

    @Test
    fun lines_that_need_the_user_stay() {
        assertFalse(
            lines(ConversationMode.TUNNEL, HEALTHY, QuantumShieldStatus.NONE, tunnelHandshakeComplete = false).isEmpty(),
            "an unfinished Tunnel setup is paid and on-chain (ZC-1275): its line must stay",
        )
        assertFalse(lines(ConversationMode.VAULT, STRANDED, QuantumShieldStatus.NONE, true).isEmpty(), "stranded funds")
        assertFalse(lines(ConversationMode.VAULT, CONFIRMING, QuantumShieldStatus.NONE, true).isEmpty(), "Ironwood confirming")
        assertFalse(lines(ConversationMode.VAULT, NEEDS_SHIELDING, QuantumShieldStatus.NONE, true).isEmpty(), "needs shielding")
    }

    @Test
    fun the_send_button_carries_the_price_wherever_a_send_spends_zec() {
        assertNotNull(costLabel(ConversationMode.VAULT, tunnelSendIsFree = false), "a Shielded send costs ZEC: no price shown")
        assertNotNull(costLabel(ConversationMode.VAULT, tunnelSendIsFree = true), "Shielded is paid whatever the relay state")
        assertNotNull(costLabel(ConversationMode.TUNNEL, tunnelSendIsFree = false), "an unfinished Tunnel send pays on-chain")
        assertNull(costLabel(ConversationMode.TUNNEL, tunnelSendIsFree = true), "a finished Tunnel is free: no price")
        assertNull(costLabel(ConversationMode.OPEN, tunnelSendIsFree = false), "Open is free: no price, no 'Free' row")
        assertNull(costLabel(ConversationMode.OPEN, tunnelSendIsFree = true), "Open is free: no price, no 'Free' row")
        val label = costLabel(ConversationMode.VAULT, tunnelSendIsFree = false)
        if (label is Zatoshi) assertEquals(COST, label, "the label shows the composer's own figure (ZC-696)")
        if (label is CharSequence) assertTrue(label.isNotBlank(), "an empty price label")
    }

    @Test
    fun the_chat_screen_asks_the_two_rules_and_drops_the_free_row() {
        val view = SourceScan.stripComments(ChatResourceText.source(CDV))
        assertTrue("ChatStatusLines.show(" in view, "ChatDetailView does not ask ChatStatusLines.show for its body lines")
        assertTrue("SendCostLabel.text(" in view, "ChatDetailView does not put SendCostLabel.text on the send button")
        assertFalse("R.string.chat_composer_free_nostr" in view, "the composer still shows \"Free — sent over NOSTR\"")
    }

    private companion object {
        const val CDV = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        val COST = Zatoshi(17_000)
        val HEALTHY = PrivacyStatus.DEFAULT
        val STRANDED = derivePrivacyStatus(Zatoshi(50_000), Zatoshi(0), Zatoshi(0), Zatoshi(0))
        val CONFIRMING =
            derivePrivacyStatus(
                orchardBalance = Zatoshi(50_000),
                ironwoodBalance = Zatoshi(40_000),
                saplingBalance = Zatoshi(0),
                transparentBalance = Zatoshi(0),
                ironwoodAvailable = Zatoshi(0),
            )
        val NEEDS_SHIELDING = derivePrivacyStatus(Zatoshi(0), Zatoshi(0), Zatoshi(0), Zatoshi(30_000))
    }
}
