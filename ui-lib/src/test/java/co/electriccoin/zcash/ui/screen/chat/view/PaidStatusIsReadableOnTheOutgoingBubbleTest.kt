package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.design.theme.colors.NightwireLightColors
import org.junit.Test
import kotlin.math.pow
import kotlin.test.assertTrue

/**
 * ZC-1706 remainder (QA2 P3 polish; device-qa free pass, light theme: "green Paid/amount on the teal bubble"): a payment
 * request we sent shows its "Paid ..." line in the theme's success green (`chatColors().success`) on our own bubble. In
 * Nightwire Light the outgoing bubble is teal (BubbleSent 0xFF006B78) and the success green is 0xFF0E7A3F: two dark colours
 * about 1.2:1 apart, so "Paid" and its amount are nearly invisible.
 *
 * Wanted: on an outgoing bubble (`isOutgoing`) the paid/pending status line and its icon take the bubble's own foreground
 * (`textColor`, white on the outgoing bubble), as the rest of the card does; on an incoming bubble the success green stays
 * (it reads on the light incoming bubble: the second test pins that contrast so the change cannot go the other way).
 *
 * Source check: `PaymentRequestContent` is Compose and this module has no Compose test host. How it looks: phone check
 * in the QA repeat.
 */
class PaidStatusIsReadableOnTheOutgoingBubbleTest {
    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(
        a: Long,
        b: Long,
    ): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun argb(c: androidx.compose.ui.graphics.Color): Long = c.value.shr(32).toLong() and 0xFFFFFFFFL

    private val statusColorLine by lazy {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt")
        val body = src.substringAfter("internal fun PaymentRequestContent(").substringBefore("\n}\n")
        Regex("""val statusColor\s*=\s*([^\n]+(?:\n\s+[^\n]+)?)""").find(body)?.groupValues?.get(1)
            ?: throw AssertionError("PaymentRequestContent has no statusColor")
    }

    @Test
    fun `the paid status line of our own request is not drawn in the success green on the teal bubble`() {
        val green = contrast(argb(NightwireLightColors.AccentSuccess), argb(NightwireLightColors.BubbleSent))
        assertTrue(
            statusColorLine.contains("isOutgoing"),
            "statusColor ($statusColorLine) ignores isOutgoing: our own \"Paid\" line is drawn in the success green on the " +
                "outgoing bubble (contrast %.2f:1 in Nightwire Light, needs 4.5:1)".format(green),
        )
    }

    @Test
    fun `the success green stays readable where it is kept, on the incoming bubble`() {
        val onIncoming = contrast(argb(NightwireLightColors.AccentSuccess), argb(NightwireLightColors.BubbleReceived))
        assertTrue(onIncoming >= 4.5, "the success green on the incoming light bubble is %.2f:1".format(onIncoming))
        val whiteOnSent = contrast(0xFFFFFFFFL, argb(NightwireLightColors.BubbleSent))
        assertTrue(whiteOnSent >= 4.5, "white on the outgoing light bubble is %.2f:1".format(whiteOnSent))
    }
}
