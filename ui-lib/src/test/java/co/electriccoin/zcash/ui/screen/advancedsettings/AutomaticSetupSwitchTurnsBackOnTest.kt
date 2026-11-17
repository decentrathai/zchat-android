package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-1013 (register, medium; coordinator's decision of 15 Oct 2026) — "Automatic setup payments" can be switched back On
 * from Advanced Settings. Today, after Off, the row only explains that the way back is Allow in a handshake price
 * question, which comes with a payment: a user cannot undo the setting without paying.
 *
 * Wanted:
 *  - AutomaticSetupPayments.turnOn() grants the standing consent with ONE checked write; a write that does not reach the
 *    disk changes nothing and is reported; it touches nothing else in the store;
 *  - turning it on pays nothing by itself (no proposal, no send; the class names no send path); afterwards an automatic
 *    key reply to a contact is sent without the question, as after Allow;
 *  - the row, when Off, opens a confirm (CONFIRM_TURN_ON) with the standing-consent text (what it allows, the price per
 *    setup message from handshakeSpendQuote(1), that it can be switched off again); only that confirm calls turnOn().
 *
 * The new function is reached by name. All addresses and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSetupSwitchTurnsBackOnTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
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
                ?: throw AssertionError("AutomaticSetupPayments has no $name: the row cannot switch the payments back on")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(target, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private class ConsentStore(
        var consent: Boolean,
        private val setSaved: Boolean = true,
    ) {
        val sets = AtomicInteger()
        val other = mutableListOf<String>()
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, m, _ ->
                when (m.name) {
                    "hasConsentedToHandshakeSpend" -> consent
                    "setConsentedToHandshakeSpend" -> {
                        sets.incrementAndGet()
                        if (setSaved) consent = true
                        setSaved
                    }
                    else -> {
                        other += m.name
                        if (m.returnType == java.lang.Boolean.TYPE) false else null
                    }
                }
            } as ZchatPreferences
    }

    @Test
    fun `turning it on grants the standing consent with one checked write`() =
        runBlocking {
            val store = ConsentStore(consent = false)
            val switch = switch(store.proxy)
            assertEquals("turning on did not report that it was saved", true, callSuspend(switch, "turnOn"))
            assertEquals("the consent was not written exactly once", 1, store.sets.get())
            assertEquals(true, callSuspend(switch, "isOn"))
            assertEquals("the switch touched something else in the store: ${store.other}", emptyList<String>(), store.other)
        }

    @Test
    fun `a turn-on that does not reach the disk changes nothing and says so`() =
        runBlocking {
            val store = ConsentStore(consent = false, setSaved = false)
            val switch = switch(store.proxy)
            assertEquals("a write that was not saved was reported as saved", false, callSuspend(switch, "turnOn"))
            assertEquals("the row would show On although the payments are still off", false, callSuspend(switch, "isOn"))
        }

    @Test
    fun `turning it on pays nothing by itself, and afterwards an automatic key reply goes without the question`() =
        runBlocking {
            val peer = "usyntheticzc1013switchpeer01"
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            val chat = HandshakeHarness.handshakeViewModel(prefs, seam)

            assertEquals(true, callSuspend(switch(prefs.proxy), "turnOn"))
            HandshakeHarness.awaitIdle(chat)
            assertEquals("turning the switch on paid something", 0, seam.proposalRequests.get())

// W-62 (ZC-599), as e8c3da340 did for AutomaticSetupSwitchOffAsksAgainTest: a key exchange at a chat that still waits
            // for the contact's first key now waits for Accept. This case is about the question, so the contact's key is already
            // held (the key exchange repeats it).
            val keys = E2EEncryption.generateKeyPair()
            prefs.e2ePeerKey[peer] = keys.publicKey
            val kex = HandshakeHarness.kexMemoFrom(peer, keys)
            HandshakeHarness.handleKexMemo(chat, kex, "synthetic-zc1013-kex")
            HandshakeHarness.awaitIdle(chat)
            assertFalse(
                "with the payments on the key reply still asked",
                HandshakeHarness.sendMessageState(chat) is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertEquals("the key reply was not paid once, as after Allow", 1, seam.proposalRequests.get())
        }

    @Test
    fun `source - only the turn-on confirm calls turnOn, and the switch names no send path`() {
        val vm = source("ui/screen/advancedsettings/AdvancedSettingsVM.kt")
        val calls = Regex("""automaticSetupPayments\.turnOn\(\)""").findAll(vm).count()
        assertEquals("turnOn is not called from exactly one place", 1, calls)
        val confirm = functionBody(vm, "onAutomaticSetupPaymentsTurnOnConfirm")
        assertTrue("turnOn is not in the turn-on confirm", "automaticSetupPayments.turnOn()" in confirm)
        assertTrue("a turn-on that was not saved is not told", "NOT_SAVED" in confirm)
        assertTrue("the row is not read again", "automaticSetupPayments.isOn()" in confirm)
        val click = functionBody(vm, "onAutomaticSetupPaymentsClick")
        assertFalse("the row's tap turns the payments on at once", "turnOn" in click)
        assertTrue("Off opens the turn-on confirm", "CONFIRM_TURN_ON" in click)
        val switch = source("ui/screen/advancedsettings/AutomaticSetupPayments.kt")
        for (name in listOf("ChatViewModel", "grantHandshakeSpendConsent", "Proposal", "Synchronizer", "send(")) {
            assertFalse("AutomaticSetupPayments names $name", name in switch)
        }
        val view = source("ui/screen/advancedsettings/AdvancedSettingsView.kt")
        val at = view.indexOf("Kind.CONFIRM_TURN_ON")
        assertTrue("the view shows the turn-on confirm", at >= 0)
        val dialog = view.substring(at, view.indexOf("Kind.", at + 10).let { if (it < 0) view.length else it })
        for (key in listOf("on_title", "on_text", "on_button", "cancel_button")) {
            assertTrue(
                "the turn-on confirm does not show advanced_settings_auto_setup_payments_$key",
                "R.string.advanced_settings_auto_setup_payments_$key" in dialog
            )
        }
        assertTrue("the price per setup message is the handshake quote", "handshakeSpendQuote(1)" in dialog)
    }

    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val start = Regex("""fun $name\(""").find(source)?.range?.first ?: throw AssertionError("there is no fun $name")
        val next = Regex("""\n    (private |internal |override )?fun """).find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }

    private fun source(relative: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                ?: throw AssertionError("could not locate the ui-lib module from ${start.path}")
        val file = module.resolve("src/main/java/co/electriccoin/zcash/$relative")
        if (!file.isFile) throw AssertionError("missing ${file.path}")
        return file.readText()
    }
}
