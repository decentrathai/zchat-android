package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.fail

/**
 * ZC-1418 (device-qa round 4 on c39a0f61c, HONOR debug, TIMELINE4 13:02-13:04): the contact's answer was verified (log
 * "KEXACK verified ... key exchange complete") and our own next setup step (the on-chain ZBOOT) failed for funds and was
 * re-armed. The chat banner still said "Waiting for your contact to reply ... tap Reconnect (re-send my key)": untrue (the
 * contact did reply), and Reconnect is a paid action that does not help. Today the choice is inline in ChatDetailView
 * (`conversationMode.supportsCalls && !conversation.hasNostrCallChannel`) with an English literal pair.
 *
 * Wanted:
 * - A display-only model `co.electriccoin.zcash.ui.screen.chat.model.CallsLockedBanner` (object, like
 *   HandshakeNotSentBanner) with `enum class Stage { NONE, WAITING_FOR_CONTACT, OWN_SETUP_NEEDS_FUNDS }`,
 *   `fun stage(callsSupported: Boolean, hasNostrCallChannel: Boolean, contactAnswered: Boolean,
 *   ownSetupWaitsForFunds: Boolean): Stage` and `fun offersReconnect(stage: Stage): Boolean`.
 * - Contact answered + own setup waiting for funds: OWN_SETUP_NEEDS_FUNDS, no Reconnect. Reconnect only while the
 *   contact really has not answered (WAITING_FOR_CONTACT).
 * - Text `chat_calls_locked_own_setup_needs_funds` (English and Spanish; %1$s = ZEC the setup step needs) says our setup
 *   step waits for ZEC, with the figure, and names no Reconnect. ChatDetailView reads the model and that text.
 *
 * The model is reached by reflection, so this file compiles before it exists.
 */
class Zc1418CallsLockedBannerTest {
    private val english = ChatResourceText.strings("values")
    private val spanish = ChatResourceText.strings("values-es")

    @Test
    fun `ZC-1418 contact answered and our setup step short of funds - the banner waits for our ZEC, no Reconnect`() {
        val stage = stage(callsSupported = true, hasNostrCallChannel = false, contactAnswered = true, ownSetupWaitsForFunds = true)
        assertEquals("ZC-1418: the contact replied and our setup step waits for ZEC", "OWN_SETUP_NEEDS_FUNDS", stage.toString())
        assertFalse("ZC-1418: Reconnect is offered although the contact replied (it would spend and not help)", offersReconnect(stage))
    }

    @Test
    fun `ZC-1418 Reconnect appears only while the contact really has not answered`() {
        val waiting = stage(callsSupported = true, hasNostrCallChannel = false, contactAnswered = false, ownSetupWaitsForFunds = false)
        assertEquals("WAITING_FOR_CONTACT", waiting.toString())
        assertTrue("the real wait for the contact offers Reconnect", offersReconnect(waiting))

        val open = stage(callsSupported = true, hasNostrCallChannel = true, contactAnswered = true, ownSetupWaitsForFunds = false)
        assertEquals("NONE", open.toString())
        assertFalse(offersReconnect(open))

        val noCalls = stage(callsSupported = false, hasNostrCallChannel = false, contactAnswered = false, ownSetupWaitsForFunds = false)
        assertEquals("NONE", noCalls.toString())
    }

    @Test
    fun `ZC-1418 the needs-funds text names the figure and no Reconnect, in English and Spanish`() {
        val problems = mutableListOf<String>()
        for ((lang, map) in listOf("en" to english, "es" to spanish)) {
            val text = map[TEXT_KEY]
            if (text == null) {
                problems += "$lang $TEXT_KEY does not exist"
                continue
            }
            if (!text.contains("%1\$s")) problems += "$lang $TEXT_KEY does not name the amount (%1\$s)"
            if (!text.contains("ZEC")) problems += "$lang $TEXT_KEY does not say ZEC"
            if (text.contains("Reconnect", ignoreCase = true) || text.contains("Reconectar", ignoreCase = true)) {
                problems += "$lang $TEXT_KEY names Reconnect"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `ZC-1418 the chat screen chooses the banner through the model and shows the needs-funds text`() {
        val view = ChatResourceText.source(CDV)
        assertTrue("ChatDetailView does not choose the calls-locked banner through CallsLockedBanner", "CallsLockedBanner" in view)
        assertTrue("ChatDetailView does not show R.string.$TEXT_KEY", "R.string.$TEXT_KEY" in view)
    }

    // ── the new model, by name ──────────────────────────────────────────────────────────────────────

    private fun model(): Pair<Class<*>, Any> {
        val cls =
            runCatching { Class.forName(MODEL) }
                .getOrElse { fail("ZC-1418: $MODEL does not exist (the banner choice is inline in ChatDetailView)") }
        val instance: Any = requireNotNull(cls.getField("INSTANCE").get(null)) { "CallsLockedBanner is not an object" }
        return cls to instance
    }

    private fun stage(
        callsSupported: Boolean,
        hasNostrCallChannel: Boolean,
        contactAnswered: Boolean,
        ownSetupWaitsForFunds: Boolean,
    ): Any {
        val (cls, instance) = model()
        val method =
            cls.methods.firstOrNull { it.name == "stage" && it.parameterCount == 4 }
                ?: fail(
                    "ZC-1418: CallsLockedBanner.stage(callsSupported, hasNostrCallChannel, contactAnswered, " +
                        "ownSetupWaitsForFunds) does not exist",
                )
        return requireNotNull(method.invoke(instance, callsSupported, hasNostrCallChannel, contactAnswered, ownSetupWaitsForFunds))
    }

    private fun offersReconnect(stage: Any): Boolean {
        val (cls, instance) = model()
        val method =
            cls.methods.firstOrNull { it.name == "offersReconnect" && it.parameterCount == 1 }
                ?: fail("ZC-1418: CallsLockedBanner.offersReconnect(stage) does not exist")
        return method.invoke(instance, stage) as Boolean
    }

    private companion object {
        const val MODEL = "co.electriccoin.zcash.ui.screen.chat.model.CallsLockedBanner"
        const val TEXT_KEY = "chat_calls_locked_own_setup_needs_funds"
        const val CDV = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
    }
}
