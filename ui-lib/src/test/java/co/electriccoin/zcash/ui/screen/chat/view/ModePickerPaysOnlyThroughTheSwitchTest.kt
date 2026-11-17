package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T3b-1 (fix-all 2.13.0, train T3b; train T3 "money surprise").
 *
 * At 2d739b5df the conversation-mode picker's Apply (AndroidChat, ConversationModePickerDialog onPick) calls
 * changeConversationMode and, for Tunnel or Open, ensureNostrBootstrapSent in the same tap, and shows the new mode at
 * once. The bootstrap pays our KEX or ZBOOT before the price question the mode change raises, and Cancel on that
 * question leaves the header showing a mode the chat is not in.
 *
 * Must hold: the picker hands the pick to ChatViewModel.switchConversationMode and to nothing that can pay (no
 * bootstrap, no direct mode change), and it does not show the picked mode before the chat is in it (the header follows
 * the stored mode). The switch's question, when it also pays for the chat setup, says so in English and Spanish
 * (paid_action_mode_change_setup, picked by PaidActionConfirmation.includesChatSetup), and never claims the setup is
 * free or that the switch sends nothing. The Spanish is agent-drafted for the F14 native-speaker pass. The new
 * confirmation field and text are reached by name, so this file compiles before they exist.
 */
class ModePickerPaysOnlyThroughTheSwitchTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the mode picker hands the pick to the switch and to nothing that pays`() {
        val onPick = pickerOnPick()
        assertTrue(
            "viewModel.switchConversationMode(peerAddress, picked)" in onPick,
            "the picker must hand the pick to ChatViewModel.switchConversationMode:\n$onPick",
        )
        for (payer in listOf("ensureNostrBootstrapSent", "changeConversationMode", "sendKEXMessage", "reSendOurIdentity")) {
            assertTrue(payer !in onPick, "the picker calls $payer itself, outside the switch's question:\n$onPick")
        }
        assertTrue(
            "currentMode = picked" !in onPick,
            "the picker shows the picked mode before the chat is in it (Cancel would leave it showing):\n$onPick",
        )
    }

    @Test
    fun `a switch that also pays for the chat setup says so in English and Spanish`() {
        val confirmation = setupConfirmation()
        val setupText =
            runCatching { R.string::class.java.getField("paid_action_mode_change_setup").getInt(null) }
                .getOrElse { fail("there is no paid_action_mode_change_setup text") }
        assertEquals(setupText, PaidActionCopy.actionText(confirmation), "the question must show the setup text")
        assertEquals(SETUP_EN, en["paid_action_mode_change_setup"], "the English text")
        assertEquals(SETUP_ES, es["paid_action_mode_change_setup"], "the Spanish text")
    }

    @Test
    fun `the setup text names what is paid and promises nothing more`() {
        for (text in listOf(SETUP_EN)) {
            assertTrue("key exchange" in text && "relay identity" in text, "it must name what the switch pays: $text")
            assertTrue("on-chain" in text, "it must say these go on-chain: $text")
            assertTrue("after your contact answers" in text, "it must say some go only after the contact answers: $text")
            assertTrue("free" !in text && "nothing" !in text.lowercase(), "it must not call the setup free: $text")
        }
        assertTrue("intercambio de claves" in SETUP_ES && "identidad de relay" in SETUP_ES && "en la cadena" in SETUP_ES)
    }

    /** Control, green before and after: a switch that pays only for the mode change keeps its own text. */
    @Test
    fun `control - a mode change alone keeps the mode-change text`() {
        val plain =
            PaidActionConfirmation(
                PaidAction.MODE_CHANGE,
                1,
                cash.z.ecc.android.sdk.model
                    .Zatoshi(17_000L)
            )
        assertEquals(R.string.paid_action_mode_change, PaidActionCopy.actionText(plain))
    }

    // --------------------------------------------------------------- harness

    /** The onPick lambda of the chat screen's ConversationModePickerDialog, up to its onDismiss. */
    private fun pickerOnPick(): String {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val dialog = source.indexOf("ConversationModePickerDialog(")
        if (dialog < 0) fail("AndroidChat no longer shows ConversationModePickerDialog")
        val start = source.indexOf("onPick =", dialog)
        val end = source.indexOf("onDismiss =", start)
        if (start < 0 || end < 0) fail("the picker's onPick / onDismiss were not found")
        return source
            .substring(start, end)
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }

    /** MODE_CHANGE for two transactions with includesChatSetup = true, built by name. */
    private fun setupConfirmation(): PaidActionConfirmation {
        val ctor =
            PaidActionConfirmation::class.java.constructors.firstOrNull { it.parameterCount == 5 }
                ?: fail("PaidActionConfirmation has no includesChatSetup field")
        return ctor.newInstance(
            PaidAction.MODE_CHANGE,
            2,
            cash.z.ecc.android.sdk.model
                .Zatoshi(34_000L),
            false,
            true,
        ) as PaidActionConfirmation
    }

    private companion object {
        const val SETUP_EN =
            "Switching this chat's mode. Your contact can't be reached over the relays yet, so this switch also sets up " +
                "that connection on-chain: your key exchange and your relay identity, whichever is still missing. Some " +
                "of these are sent only after your contact answers; all of them are counted below."
        const val SETUP_ES =
            "Cambiar el modo de este chat. Aún no se puede llegar a tu contacto por los relays, así que este cambio " +
                "también prepara esa conexión en la cadena: tu intercambio de claves y tu identidad de relay, lo que " +
                "aún falte. Algunas de estas transacciones se envían solo cuando tu contacto responda; todas se cuentan " +
                "abajo."
    }
}
