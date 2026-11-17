package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72, SPEC_F_20261019 rule R8 and its string table, RED tests 14 and 15a (skill app-texts): while a chat's key carries a
 * pre-update mark, the Unverified banner says "Not confirmed" with the mark's own text and a Compare button that opens the
 * safety-number dialog; the claim-held banner (ZC-634) keeps priority; the chat list marks the row
 * (Conversation.keyNotConfirmed, with a content description); the detail screen reads the mark through the view model and
 * redraws on the handshake tick the release bumps. Every text is a resource in English and Spanish.
 *
 * The body says only what the code does (SPEC_DECISIONS D2, D9, D10; review of 3426ade7d finding 3): restored keys are
 * marked too, a relay move needs the tap unless the current relay connection approves it (R3), the label clears only when
 * the contact's next plain Shielded message answers a short Shielded message of ours, and stays for relay-only chats, 2.12.4
 * and silence; nothing is paid. At 2304407ab the body promises the label goes whenever the contact replies.
 */
class LegacyKeyNotConfirmedLabelTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun src(path: String): String = ChatResourceText.source("$UI/$path")

    @Test
    fun `(15a) the four texts exist in English and Spanish with the agreed words`() {
        val expected =
            mapOf(
                "chat_legacy_key_label" to ("Not confirmed" to "Sin confirmar"),
                "chat_legacy_key_compare" to ("Compare" to "Comparar"),
                "chat_legacy_key_list_cd" to ("Security key not confirmed" to "Clave de seguridad sin confirmar"),
                "chat_legacy_key_body" to (BODY_EN to BODY_ES),
            )
        for ((key, texts) in expected) {
            assertEquals("English $key", texts.first, en[key])
            assertEquals("Spanish $key", texts.second, es[key])
        }
    }

    @Test
    fun `(15a) the body promises nothing the code does not do`() {
        val body = en["chat_legacy_key_body"] ?: ""
        assertFalse(
            "the body promises the relay connection never moves (a sealed ZBOOT moves it, R3)",
            body.contains("won't move this chat's relay connection without your tap"),
        )
        assertTrue("the body does not name Shielded as the mode is named", body.contains("Shielded"))
        assertTrue("the body does not say restored keys are marked too", body.contains("restore"))
        assertTrue("the body does not say nothing is paid", body.contains("Nothing is paid"))
        // Review of 3426ade7d, finding 3: the limits of the clear are named (D9 quoted replies, relay-only chats, 2.12.4).
        assertFalse("the body promises the label always goes when the contact replies", body.contains("goes away by itself when"))
        for (limit in listOf("short Shielded", "quoted replies do not count", "Tunnel or Open", "2.12.4", "never write back")) {
            assertTrue("the body does not name the limit \"$limit\"", body.contains(limit))
        }
    }

    @Test
    fun `(14) the Unverified banner says Not confirmed with Compare opening the safety-number dialog, the claim banner first`() {
        val detail = src("screen/chat/view/ChatDetailView.kt")
        val start = detail.indexOf("if (conversation.isE2EReady && !isVerified && !isKeyChanged && safetyNumber != null)")
        assertTrue("the Unverified banner is not where it was", start >= 0)
        val block = detail.substring(start, detail.indexOf("// Key-rotation reminder", start))
        for (key in listOf("chat_legacy_key_label", "chat_legacy_key_body", "chat_legacy_key_compare")) {
            assertTrue("the banner does not show $key", block.contains("R.string.$key"))
        }
        assertTrue("the label does not follow the mark (keyNotConfirmed)", block.contains("keyNotConfirmed"))
        assertTrue("the claim-held banner does not keep priority", block.contains("!keyClaimHeld"))
        assertTrue("Compare does not open the safety-number dialog", block.contains("showSafetyNumberDialog = true"))
        val signatures = Regex("""keyNotConfirmed: Boolean = false""").findAll(detail).count()
        assertTrue("ChatDetailView's screens take no keyNotConfirmed", signatures >= 2)
        assertTrue("the outer screen does not hand keyNotConfirmed on", detail.contains("keyNotConfirmed = keyNotConfirmed"))

        val androidChat = src("screen/chat/AndroidChat.kt")
        assertTrue(
            "the chat screen does not read the mark",
            androidChat.contains("keyNotConfirmed = viewModel.isKeyNotConfirmed(peerAddress)"),
        )
    }

    @Test
    fun `(14) the chat list marks a row whose key is not confirmed, with a content description`() {
        val list = src("screen/chat/view/ChatListView.kt")
        val start = list.indexOf("private fun ConversationItem(")
        assertTrue(start >= 0)
        val item = list.substring(start, list.indexOf("\n}\n", start))
        assertTrue("the row has no marker for keyNotConfirmed", item.contains("conversation.keyNotConfirmed"))
        assertTrue("the marker has no content description", item.contains("R.string.chat_legacy_key_list_cd"))
        val model = src("screen/chat/model/ChatMessage.kt")
        assertTrue("Conversation has no keyNotConfirmed", model.contains("val keyNotConfirmed: Boolean = false"))
        val vm = src("screen/chat/viewmodel/ChatViewModel.kt")
        val at = vm.indexOf("private fun withLiveStatus(")
        assertTrue(at >= 0)
        val live = vm.substring(at, at + 6_000)
        assertTrue("the list does not read the mark at every redraw", live.contains("keyNotConfirmed = zchatPreferences.getLegacyKeyMark("))
    }

    companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
        const val BODY_EN =
            "This chat's security key was saved before ZChat 2.13 (or brought back by a wallet restore), so ZChat can't yet " +
                "tell whether it is really your contact's. The label can go away by itself: after you send a short Shielded " +
                "message, the next Shielded message your contact writes from ZChat 2.13 or newer removes it (quoted replies do " +
                "not count). It stays if you or your contact only use Tunnel or Open, if your contact stays on 2.12.4, or if " +
                "they never write back. Or compare the safety number with your contact in person or on a channel you trust, and " +
                "tap “Mark as verified” only if the numbers match. Until then, a new relay connection for this chat needs your " +
                "tap unless the current one approves it. Nothing is paid for this."
        const val BODY_ES =
            "La clave de seguridad de este chat se guardó antes de ZChat 2.13 (o la trajo una restauración de la billetera), " +
                "así que ZChat aún no puede saber si es realmente la de tu contacto. La etiqueta puede desaparecer sola: después " +
                "de que envíes un mensaje Shielded corto, el siguiente mensaje Shielded que tu contacto escriba con ZChat 2.13 o " +
                "posterior la quita (las respuestas citadas no cuentan). Se queda si tú o tu contacto solo usan Tunnel u Open, si " +
                "tu contacto sigue en 2.12.4 o si nunca responde. O compara el número de seguridad con tu contacto en persona o " +
                "por un canal de confianza, y toca «Marcar como verificado» solo si coinciden. Hasta entonces, una nueva conexión " +
                "por relays para este chat necesita tu toque, salvo que la actual la apruebe. Esto no cuesta nada."
    }
}
