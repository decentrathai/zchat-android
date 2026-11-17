package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC1013-X1 (T7aD review of 19 Oct 2026, finding 2; money-path rule 1, quote first) — turning "Automatic setup payments"
 * back On from Advanced Settings.
 *
 * Today the confirm names only the price of one setup message, and the setups the user answered "Not now" to in the chat
 * question are paid on the next block tick or chat open with no question: the standing consent covers them at once.
 *
 * Wanted:
 *  (a) a setup declined with "Not now" is not paid after the switch is turned on: a retry pays nothing, and once the user
 *      opens a chat it asks again (the same question, the same count and price); Allow there pays it once;
 *  (b) the switch quotes what is waiting now with the in-chat question's own count and price
 *      (`AutomaticSetupPayments.waitingNow()`: transactions, cost, declined), and a declined setup is counted apart;
 *  (c) the turn-on confirm shows those lines (EN and ES), read on the row's tap.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSetupTurnOnQuotesWaitingTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        clearProcessQueue()
    }

    @After
    fun tearDown() {
        clearProcessQueue()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    /** The process-wide queue of the consent question is shared by every chat screen: no test leaves anything in it. */
    private fun clearProcessQueue() {
        for (name in listOf("queuedAutomaticSetups", "dismissedAutomaticSetups")) {
            val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableSet<*>)?.clear()
        }
        val queue = runCatching { Class.forName(QUEUE) }.getOrNull() ?: return
        val instance = queue.getField("INSTANCE").get(null)
        queue.methods.firstOrNull { it.name == "clearAll" && it.parameterCount == 0 }?.invoke(instance)
    }

    private fun switch(store: ZchatPreferences): Any =
        AutomaticSetupPayments::class.java
            .getConstructor(ZchatPreferences::class.java, CoroutineDispatcher::class.java)
            .newInstance(store, Dispatchers.Unconfined)

    private suspend fun callSuspend(
        target: Any,
        name: String,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
                ?: throw AssertionError("AutomaticSetupPayments has no $name: the turn-on confirm cannot quote what waits")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(target, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun property(
        target: Any,
        name: String,
    ): Any? {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val method =
            target.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name == getter || it.name.startsWith("$getter-")) }
                ?: throw AssertionError("${target.javaClass.simpleName} has no $name")
        return method.invoke(target)
    }

    private suspend fun questionFor(
        peer: String,
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
    ): Pair<ChatViewModel, SendMessageState.NeedsHandshakeSpendConsent> {
        val chat = HandshakeHarness.handshakeViewModel(prefs, seam)
// W-62 (ZC-599), as e8c3da340 did for AutomaticSetupSwitchOffAsksAgainTest: a key exchange at a chat that still waits
        // for the contact's first key now waits for Accept. This case is about the question, so the contact's key is already
        // held (the key exchange repeats it).
        val keys = E2EEncryption.generateKeyPair()
        prefs.e2ePeerKey[peer] = keys.publicKey
        HandshakeHarness.handleKexMemo(chat, HandshakeHarness.kexMemoFrom(peer, keys), "synthetic-$peer")
        HandshakeHarness.awaitIdle(chat)
        val ask = HandshakeHarness.sendMessageState(chat)
        assertTrue("fixture: the key reply asks first while the payments are off", ask is SendMessageState.NeedsHandshakeSpendConsent)
        assertEquals("fixture: nothing is paid before the answer", 0, seam.proposalRequests.get())
        return chat to (ask as SendMessageState.NeedsHandshakeSpendConsent)
    }

    @Test
    fun `(a) a setup declined with Not now is not paid after the switch is turned on, it asks again`() =
        runBlocking {
            val peer = "usyntheticzc1013x1peer000001"
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            val (chat, ask) = questionFor(peer, prefs, seam)

            chat.dismissOrchardShieldingWarning()
            assertEquals(true, callSuspend(switch(prefs.proxy), "turnOn"))
            assertTrue("fixture: the payments are on", prefs.consent)

            chat.retryKexAckIfResponder(peer)
            HandshakeHarness.awaitIdle(chat)
            assertEquals(
                "a setup declined with Not now was paid on the next retry after the switch was turned on",
                0,
                seam.proposalRequests.get()
            )

            // A chat open: the declined setup may ask once more (HS-6), and it does, with the same quote.
            chat.askAgainForHandshakeConsent()
            chat.retryKexAckIfResponder(peer)
            HandshakeHarness.awaitIdle(chat)
            assertEquals("a setup declined with Not now was paid at the chat open with no question", 0, seam.proposalRequests.get())
            val again = HandshakeHarness.sendMessageState(chat)
            assertTrue("the declined setup did not ask again: $again", again is SendMessageState.NeedsHandshakeSpendConsent)
            again as SendMessageState.NeedsHandshakeSpendConsent
            assertEquals("the question asked again with another count", ask.pendingPeers, again.pendingPeers)
            assertEquals("the question asked again with another price", ask.estimatedCost, again.estimatedCost)
            assertTrue("the screen would not show the question", chat.isHandshakeSpendQuestionOpen())

            chat.grantHandshakeSpendConsent()
            HandshakeHarness.awaitIdle(chat)
            HandshakeHarness.awaitTrue { seam.proposalRequests.get() >= 1 }
            assertEquals("Allow did not pay the key reply exactly once", 1, seam.proposalRequests.get())
        }

    @Test
    fun `(b) the switch quotes what waits now with the question's own count and price, a declined setup apart`() =
        runBlocking {
            val peer = "usyntheticzc1013x1peer000002"
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            val (chat, ask) = questionFor(peer, prefs, seam)

            val waiting = callSuspend(switch(prefs.proxy), "waitingNow") ?: throw AssertionError("nothing is quoted")
            assertEquals("the count is not the question's", ask.pendingPeers, property(waiting, "transactions"))
            assertEquals("the price is not the question's", ask.estimatedCost.toString(), property(waiting, "cost").toString())
            assertEquals("nothing was declined yet", 0, property(waiting, "declined"))

            chat.dismissOrchardShieldingWarning()
            val after = callSuspend(switch(prefs.proxy), "waitingNow") ?: throw AssertionError("nothing is quoted")
            assertEquals("a declined setup is quoted as if turning on paid it", 0, property(after, "transactions"))
            assertEquals("the declined setup is not counted apart", ask.pendingPeers, property(after, "declined"))
            assertEquals("the quote read something other than the store's handshake facts", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("quoting paid something", 0, seam.proposalRequests.get())
        }

    @Test
    fun `(c) the turn-on confirm shows what waits and what asks again, read on the row's tap, EN and ES`() {
        val vm = source("ui/screen/advancedsettings/AdvancedSettingsVM.kt")
        val click = functionBody(vm, "onAutomaticSetupPaymentsClick")
        assertTrue("the row's tap does not read what waits", "automaticSetupPayments.waitingNow()" in click)
        val view = source("ui/screen/advancedsettings/AdvancedSettingsView.kt")
        val at = view.indexOf("Kind.CONFIRM_TURN_ON")
        assertTrue("the view shows the turn-on confirm", at >= 0)
        val dialog = view.substring(at, view.indexOf("Kind.", at + 10).let { if (it < 0) view.length else it })
        for (key in listOf("waiting", "declined")) {
            assertTrue("the turn-on confirm does not show R.plurals.$PREFIX$key", "R.plurals.$PREFIX$key" in dialog)
        }
        for (folder in listOf("values", "values-es")) {
            val res = resource("res/ui/advanced_settings/$folder/strings.xml")
            for (key in listOf("waiting", "declined")) {
                assertTrue("$folder has no plurals $PREFIX$key", Regex("""<plurals name="$PREFIX$key">""").containsMatchIn(res))
            }
        }
        val en = resource("res/ui/advanced_settings/values/strings.xml")
        val es = resource("res/ui/advanced_settings/values-es/strings.xml")
        assertTrue("the Spanish texts are the English ones", plural(en, "waiting") != plural(es, "waiting"))
    }

    private fun plural(
        res: String,
        key: String,
    ): String {
        val start = res.indexOf("<plurals name=\"$PREFIX$key\">")
        return res.substring(start, res.indexOf("</plurals>", start))
    }

    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val start = Regex("""fun $name\(""").find(source)?.range?.first ?: throw AssertionError("there is no fun $name")
        val next = Regex("""\n    (private |internal |override )?fun """).find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }

    private fun source(relative: String): String = resource("java/co/electriccoin/zcash/$relative")

    private fun resource(relative: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                ?: throw AssertionError("could not locate the ui-lib module from ${start.path}")
        val file = module.resolve("src/main/$relative")
        if (!file.isFile) throw AssertionError("missing ${file.path}")
        return file.readText()
    }

    private companion object {
        const val QUEUE = "co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupQueue"
        const val PREFIX = "advanced_settings_auto_setup_payments_"
    }
}
