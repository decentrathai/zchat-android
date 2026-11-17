package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 lane k1b, ZC-1814 (Fable review of K1, 12 Nov; money-path rule 1, app-texts rule 2) — the Tunnel offer banner turns
 * Accept off for a wallet in the 17,001..21,999 dead zone (OpenChatTunnelOffer.canAccept, ZC-1417) but its line still
 * said "about 0.00017 ZEC is needed" while the wallet shows more. The line names what the wallet must reach,
 * spendableNeed(spendable, price): the price, or above it the price plus the marginal fee (0.00022 for a 17,000 price),
 * in English and Spanish (one %1$s in each, the figure formatted by the screen).
 *
 * The screen is checked at its source, as Qa2ChatScreenWiringTest does for this banner.
 */
class Qa2K1bTunnelOfferNeedTest {
    private val detail by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
    }

    private val offerBlock by lazy {
        val start = detail.indexOf("val tunnelOffer = openChatTunnelOffer")
        val end = detail.indexOf("if (awaitingChatAccept &&", start)
        assertTrue(start >= 0 && end > start, "the Tunnel offer banner was not found in ChatDetailView.kt")
        detail.substring(start, end)
    }

    /**
     * QA2 k1b2 (ZC-1845, [test-change-ok: ZC-1845]): the source regex is replaced by the figure itself. The banner's line
     * is [co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer] `needsFundsFigure(spendable, price, locale)`;
     * for an 18,000 wallet and the 17,000 price (Accept off: 18,000 is neither the price nor at least the price plus the
     * 5,000 marginal fee) it is 0.00022, in English and in Spanish, inside the rendered line.
     */
    @Test
    fun zc1814_an_18000_wallet_reads_0_00022_in_the_not_enough_line() {
        val offer = co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer
        val figure =
            assertNotNull(
                offer.javaClass.methods.firstOrNull { it.name == "needsFundsFigure" && it.parameterCount == 3 },
                "OpenChatTunnelOffer has no needsFundsFigure(spendable, price, locale): the banner's figure is not testable",
            )
        assertFalse(offer.canAccept(18_000L, 17_000L), "fixture: an 18,000 wallet can Accept a 17,000 price")
        val cases =
            listOf(
                Triple("values", java.util.Locale.US, "0.00022"),
                Triple("values-es", java.util.Locale.forLanguageTag("es-ES"), "0,00022"),
            )
        for ((folder, locale, wanted) in cases) {
            val shown = figure.invoke(offer, Zatoshi(18_000L), Zatoshi(17_000L), locale) as String
            assertEquals(wanted, shown, "$folder: the figure for an 18,000 wallet")
            val line = assertNotNull(ChatResourceText.strings(folder)["chat_open_tunnel_offer_needs_funds"], "$folder has no line")
            val rendered = String.format(locale, line.replace("\\'", "'"), shown)
            assertTrue(wanted in rendered && "0.00017" !in rendered && "0,00017" !in rendered, "$folder: the line reads '$rendered'")
        }
        // The screen shows that figure (not the price) on the line.
        val call =
            assertNotNull(
                Regex("""R\.string\.chat_open_tunnel_offer_needs_funds\s*,\s*([^)]*\)?)""").find(offerBlock),
                "the Tunnel offer banner no longer reads chat_open_tunnel_offer_needs_funds",
            )
        assertTrue("needsFundsFigure(" in call.groupValues[1], "the banner does not show needsFundsFigure: '${call.groupValues[1]}'")
    }

    @Test
    fun zc1814_both_languages_take_the_figure() {
        for (folder in listOf("values", "values-es")) {
            val text = assertNotNull(ChatResourceText.strings(folder)["chat_open_tunnel_offer_needs_funds"], "$folder has no line")
            assertTrue("%1\$s" in text, "$folder: the line lost its figure")
            assertFalse("0.00017" in text || "0,00017" in text, "$folder: a fixed figure in the line")
        }
    }
}
