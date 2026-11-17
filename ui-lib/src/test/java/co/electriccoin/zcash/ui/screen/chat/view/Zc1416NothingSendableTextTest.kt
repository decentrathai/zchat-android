package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-1416 (device-qa round 4 on c39a0f61c, TIMELINE4 12:31-12:34): the payment confirm's only shortfall text is
 * `payment_quote_not_enough` ("this payment needs at least %1$s ZEC, and %2$s ZEC can be sent now"). When the SDK will
 * accept nothing from this wallet the sendable figure is zero, and "0 ZEC can be sent now" beside a Send that stays off
 * is not plain.
 *
 * Wanted: a text for "nothing can be sent now" - `payment_quote_nothing_sendable`, English and Spanish, one placeholder
 * (%1$s = the least this payment needs, in ZEC), no "can be sent now" - and PaymentQuoteStatus shows it for a NotEnough
 * whose spendable is zero.
 */
class Zc1416NothingSendableTextTest {
    private val english = ChatResourceText.strings("values")
    private val spanish = ChatResourceText.strings("values-es")

    @Test
    fun `ZC-1416 nothing sendable has its own text in English and Spanish, without 'can be sent now'`() {
        val problems = mutableListOf<String>()
        val en = english[KEY]
        val es = spanish[KEY]
        if (en == null) problems += "values/$KEY does not exist"
        if (es == null) problems += "values-es/$KEY does not exist"
        for ((lang, text) in listOf("en" to en, "es" to es)) {
            if (text == null) continue
            if (!text.contains("%1\$s")) problems += "$lang $KEY does not name the amount (%1\$s)"
            if (text.contains("%2\$s")) problems += "$lang $KEY names a second (sendable) figure"
        }
        if (en != null && en.contains("can be sent now", ignoreCase = true)) problems += "en $KEY says 'can be sent now'"
        if (es != null && es.contains("se pueden enviar", ignoreCase = true)) problems += "es $KEY says 'se pueden enviar'"
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `ZC-1416 the payment confirm shows the nothing-sendable text`() {
        val dialogs = ChatResourceText.source(DIALOGS)
        assertTrue("PaymentQuoteStatus does not read R.string.$KEY", "R.string.$KEY" in dialogs)
    }

    private companion object {
        const val KEY = "payment_quote_nothing_sendable"
        const val DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
    }
}
