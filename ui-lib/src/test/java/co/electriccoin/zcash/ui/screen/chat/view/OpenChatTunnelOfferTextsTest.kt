package co.electriccoin.zcash.ui.screen.chat.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1017 (decided under W-57, 15 Oct) — the banner of a paid key exchange held in an Open chat: the decided English
 * wording with the amount from the code's quote (a placeholder, never a figure in the text), Spanish beside it with the
 * same placeholders (an agent draft for the native-speaker pass), the two buttons, and the line shown instead of a
 * usable Accept when the balance is short. The chat screen draws them from these resources and writes no amount itself.
 * Accept is enabled only when what can pay another person covers the quote.
 */
class OpenChatTunnelOfferTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val expectedEnglish =
        mapOf(
            "chat_open_tunnel_offer_full" to
                "%1\$s wants a private Tunnel chat and has already paid their part. Accept switches this chat to Tunnel " +
                "and costs about %2\$s ZEC once. Messages are free afterwards.",
            "chat_open_tunnel_offer_accept" to "Accept and pay %1\$s ZEC",
            "chat_open_tunnel_offer_keep_open" to "Keep Open",
            "chat_open_tunnel_offer_needs_funds" to
                "Not enough spendable ZEC right now: about %1\$s ZEC is needed, and ZEC that is still confirming doesn't " +
                "count. This chat stays Open until then.",
        )

    @Test
    fun the_offer_texts_are_english_and_spanish_with_the_same_placeholders() {
        expectedEnglish.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(
                SpanishSendPaymentDialogTest.placeholders(english),
                SpanishSendPaymentDialogTest.placeholders(spanish),
                "$key: the Spanish placeholders differ",
            )
            assertTrue(
                "Tunnel" in spanish || "Open" in spanish || key == "chat_open_tunnel_offer_accept",
                "$key: the mode name is translated",
            )
        }
    }

    @Test
    fun the_chat_screen_draws_the_offer_from_the_resources_and_writes_no_amount() {
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        for (key in expectedEnglish.keys) {
            assertTrue("R.string.$key" in view, "ChatDetailView does not draw $key")
        }
        assertFalse("0.00017" in view || "0,00017" in view, "ChatDetailView writes the amount itself instead of the quote")
    }

    @Test
    fun accept_is_enabled_only_when_the_spendable_balance_covers_the_quote() {
        val offer =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer") }.getOrNull()
                ?: throw AssertionError("no OpenChatTunnelOffer: the Open chat's key exchange has no Accept rule")
        val instance = offer.getField("INSTANCE").get(null)
        val canAccept = offer.getMethod("canAccept", java.lang.Long.TYPE, java.lang.Long.TYPE)
        assertFalse(canAccept.invoke(instance, 0L, QUOTE) as Boolean, "Accept enabled with nothing to pay")
        assertFalse(canAccept.invoke(instance, QUOTE - 1, QUOTE) as Boolean, "Accept enabled one zatoshi short")
        assertTrue(canAccept.invoke(instance, QUOTE, QUOTE) as Boolean, "Accept disabled with exactly the quote")
    }

    private companion object {
        const val QUOTE = 17_000L
    }
}
