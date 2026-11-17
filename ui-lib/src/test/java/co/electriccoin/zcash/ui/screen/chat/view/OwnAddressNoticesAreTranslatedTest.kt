package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T3-extra (fix-all 2.13.0, train T3) — the three notices of "never pay one of our own addresses", in English and
 * Spanish: the chat send refused at the chokepoint, the mode changed on this phone only, and the timer not changed.
 *
 * Each is a ChatSendCopy constant whose English resource is the constant and whose Spanish resource exists; ChatSendCopy
 * maps it, so a Spanish phone reads it in Spanish, and the toast mapper shows it as it is. None promises more than the
 * code does: each says nothing was paid, none says anything was sent, the mode text says the change is on this device
 * only (no retry is armed for it) and the timer text says the timer did not change. The Spanish is agent-drafted for
 * the F14 native-speaker pass. Constants are read by name, so this file compiles before they exist.
 */
class OwnAddressNoticesAreTranslatedTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the chat send refused for one of our own addresses is a mapped constant in English and Spanish`() =
        assertNotice("OWN_ADDRESS_NOT_PAID", "chat_send_own_address_not_paid", SEND_EN, SEND_ES)

    @Test
    fun `the mode change kept on this phone is a mapped constant in English and Spanish`() =
        assertNotice("MODE_OWN_ADDRESS_NOT_PAID", "chat_send_mode_own_address_not_paid", MODE_EN, MODE_ES)

    @Test
    fun `the timer left unchanged is a mapped constant in English and Spanish`() =
        assertNotice("TIMER_OWN_ADDRESS_NOT_PAID", "chat_send_timer_own_address_not_paid", TIMER_EN, TIMER_ES)

    @Test
    fun `no notice promises more than the code does`() {
        for (text in listOf(SEND_EN, MODE_EN, TIMER_EN)) {
            assertTrue("one of your own addresses" in text, "the reason must be named: $text")
            assertTrue("Nothing was paid." in text, "it must say nothing was paid: $text")
            assertTrue(" was sent" !in text && "it'll sync" !in text, "it must not promise a send: $text")
        }
        assertTrue("on this device only" in MODE_EN, "no retry is armed, so the change is on this device only")
        assertTrue("not changed" in TIMER_EN, "the timer is refused before it is set")
        for (text in listOf(SEND_ES, MODE_ES, TIMER_ES)) {
            assertTrue("una de tus propias direcciones" in text, "the Spanish must name the reason: $text")
            assertTrue("No se pagó nada." in text, "the Spanish must say nothing was paid: $text")
        }
    }

    private fun assertNotice(
        constantName: String,
        resourceName: String,
        english: String,
        spanish: String,
    ) {
        val constant =
            runCatching { ChatSendCopy::class.java.getField(constantName).get(null) as String }
                .getOrElse { fail("ChatSendCopy has no $constantName") }
        assertEquals(english, constant, "the constant")
        assertEquals(constant, en[resourceName], "the English resource must be the constant")
        assertEquals(spanish, es[resourceName], "the Spanish resource")
        val id = R.string::class.java.getField(resourceName).getInt(null)
        assertEquals(id, ChatSendCopy.localizedResource(constant), "a Spanish phone must find the translation")
        assertEquals(constant, constant.toZchatUserMessage("fallback"), "the toast path must not replace the text")
    }

    private companion object {
        const val SEND_EN =
            "Not sent — this chat is with one of your own addresses, and ZCHAT never pays to send to your own " +
                "address. Nothing was paid."
        const val SEND_ES =
            "No se envió: este chat es con una de tus propias direcciones, y ZCHAT nunca paga por enviar a tu " +
                "propia dirección. No se pagó nada."
        const val MODE_EN =
            "Mode changed on this device only — this chat is with one of your own addresses, and ZCHAT never pays " +
                "to send the change there. Nothing was paid."
        const val MODE_ES =
            "El modo cambió solo en este dispositivo: este chat es con una de tus propias direcciones, y ZCHAT " +
                "nunca paga por enviar el cambio allí. No se pagó nada."
        const val TIMER_EN =
            "Timer not changed — this chat is with one of your own addresses, and ZCHAT never pays to send the " +
                "timer there. Nothing was paid."
        const val TIMER_ES =
            "El temporizador no cambió: este chat es con una de tus propias direcciones, y ZCHAT nunca paga por " +
                "enviar el temporizador allí. No se pagó nada."
    }
}
