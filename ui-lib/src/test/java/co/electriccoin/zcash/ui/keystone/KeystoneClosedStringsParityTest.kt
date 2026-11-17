package co.electriccoin.zcash.ui.keystone

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * K0 (fix-all 2.13.0, train T1) — every text the Keystone close adds or changes, in English and Spanish.
 *
 * - the new chat refusal (ChatSendCopy.KEYSTONE_CHATS_CLOSED): its English resource is the constant, its Spanish one
 *   is the reviewed text, ChatSendCopy maps it, and neither mapper replaces it;
 * - the automatic-setup and group refusals now say where to switch / that this is for this version (the old text
 *   said "Switch to your ZChat account", which no screen let the user do);
 * - the Wallet-tab chip's content description and the firmware floor text on the Keystone scan screen.
 *
 * The Spanish is agent-drafted and goes to the F14 native-speaker pass. No text promises more than the code does:
 * the chip exists whenever a Keystone account does, and the firmware refusal comes before anything is submitted.
 */
class KeystoneClosedStringsParityTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the chat refusal for a Keystone account is a mapped constant with English and Spanish resources`() {
        val constant = ChatSendCopy::class.java.getField("KEYSTONE_CHATS_CLOSED").get(null) as String
        assertEquals(CHATS_CLOSED_EN, constant, "the constant")
        assertEquals(constant, en["chat_send_keystone_chats_closed"], "the English resource must be the constant")
        assertEquals(CHATS_CLOSED_ES, es["chat_send_keystone_chats_closed"], "the Spanish resource")
        val id = R.string::class.java.getField("chat_send_keystone_chats_closed").getInt(null)
        assertEquals(id, ChatSendCopy.localizedResource(constant), "a Spanish phone must find the translation")
        assertEquals(constant, constant.toZchatUserMessage("fallback"), "the toast path must not replace the text")
    }

    @Test
    fun `the changed Keystone refusals say where to switch and that this is for this version`() {
        assertEquals(SETUP_EN, ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP)
        assertEquals(SETUP_EN, en["chat_send_keystone_automatic_setup"])
        assertEquals(SETUP_ES, es["chat_send_keystone_automatic_setup"])
        assertEquals(GROUP_EN, ChatSendCopy.GROUP_KEYSTONE_REFUSED)
        assertEquals(GROUP_EN, en["chat_send_group_keystone_refused"])
        assertEquals(GROUP_ES, es["chat_send_group_keystone_refused"])
        for (text in listOf(SETUP_EN, GROUP_EN, CHATS_CLOSED_EN)) {
            assertTrue("Switch to your ZChat account" !in text, "a text still asks for a switch no screen offers: $text")
        }
    }

    @Test
    fun `the Wallet-tab chip and the firmware floor texts exist in English and Spanish`() {
        assertEquals("Switch account", en["wallet_tab_account_chip_cd"])
        assertEquals("Cambiar de cuenta", es["wallet_tab_account_chip_cd"])
        assertEquals(FIRMWARE_EN, en["keystone_firmware_below_minimum"])
        assertEquals(FIRMWARE_ES, es["keystone_firmware_below_minimum"])
    }

    private companion object {
        const val CHATS_CLOSED_EN =
            "A Keystone account is selected. In this version ZChat can't sign chat messages or chat payments with " +
                "Keystone, so nothing was sent or paid. Tap the account name in the Wallet tab and choose your ZChat " +
                "account to chat."
        const val CHATS_CLOSED_ES =
            "Hay una cuenta Keystone seleccionada. En esta versión ZChat no puede firmar mensajes ni pagos del chat " +
                "con Keystone, así que no se envió ni se pagó nada. Toca el nombre de la cuenta en la pestaña " +
                "Billetera y elige tu cuenta de ZChat para chatear."
        const val SETUP_EN =
            "A Keystone account is selected. ZChat can't sign chat setup payments with it in this version, so " +
                "nothing was sent or paid. Tap the account name in the Wallet tab and choose your ZChat account to " +
                "finish setting up this chat."
        const val SETUP_ES =
            "Hay una cuenta Keystone seleccionada. En esta versión ZChat no puede firmar con ella los pagos de " +
                "configuración del chat, así que no se envió ni se pagó nada. Toca el nombre de la cuenta en la " +
                "pestaña Billetera y elige tu cuenta de ZChat para terminar de configurar este chat."
        const val GROUP_EN =
            "Group messages can't be sent from a Keystone account in this version. Nothing was sent or paid."
        const val GROUP_ES =
            "Los mensajes de grupo no se pueden enviar desde una cuenta Keystone en esta versión. No se envió ni se " +
                "pagó nada."
        const val FIRMWARE_EN = "Update your Keystone to firmware 3.0.2 or newer, then sign again. Nothing was sent."
        const val FIRMWARE_ES = "Actualiza tu Keystone al firmware 3.0.2 o posterior y vuelve a firmar. No se envió nada."
    }
}
