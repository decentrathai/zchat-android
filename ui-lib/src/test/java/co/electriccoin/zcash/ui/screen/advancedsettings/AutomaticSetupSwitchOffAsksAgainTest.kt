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
 * KI-23 (ZC-020; SPEC D7) — Advanced Settings has a row "Automatic setup payments" that shows whether ZCHAT may pay
 * chat setup transactions by itself, and turns that OFF.
 *
 * Today the only way to withdraw the "Allow handshake transactions?" answer is hidden inside Move Old Balance: a user
 * who allowed the payments once cannot stop them.
 *
 * Wanted:
 *  - turning it off withdraws the standing consent with one checked write; a write that did not reach the disk
 *    changes nothing and is reported;
 *  - after that an automatic key reply asks first and pays nothing;
 *  - nothing under `advancedsettings/` can turn the payments ON (the only way on is the question, which shows the
 *    price); the consent is read off the state builder; the confirm is the only caller of the turn-off; the view shows
 *    the dialogs with their own texts.
 *
 * The new class is reached by name: it does not exist before the fix. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSetupSwitchOffAsksAgainTest {
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

    private val vm by lazy { source("ui/screen/advancedsettings/AdvancedSettingsVM.kt") }
    private val view by lazy { source("ui/screen/advancedsettings/AdvancedSettingsView.kt") }
    private val state by lazy { source("ui/screen/advancedsettings/AdvancedSettingsState.kt") }

    /** The row's collaborator over [store], built by name. */
    private fun switch(store: ZchatPreferences): Any {
        val type =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.advancedsettings.AutomaticSetupPayments") }.getOrNull()
                ?: throw AssertionError("there is no AutomaticSetupPayments: Advanced Settings cannot turn automatic setup payments off")
        return type
            .getConstructor(ZchatPreferences::class.java, CoroutineDispatcher::class.java)
            .newInstance(store, Dispatchers.Unconfined)
    }

    private suspend fun callSuspend(
        target: Any,
        name: String,
    ): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                val method = target.javaClass.methods.single { it.name == name }
                method.invoke(target, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

    /** A store that holds only the standing consent; [clearSaved] false: the clear does not reach the disk. */
    private class ConsentStore(
        var consent: Boolean,
        private val clearSaved: Boolean = true,
    ) {
        val clears = AtomicInteger()
        val other = mutableListOf<String>()
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, m, _ ->
                when (m.name) {
                    "hasConsentedToHandshakeSpend" -> consent
                    "clearConsentedToHandshakeSpend" -> {
                        clears.incrementAndGet()
                        if (clearSaved) consent = false
                        clearSaved
                    }
                    else -> {
                        other += m.name
                        if (m.returnType == java.lang.Boolean.TYPE) false else null
                    }
                }
            } as ZchatPreferences
    }

    @Test
    fun `turning it off withdraws the consent with one checked write`() =
        runBlocking {
            val store = ConsentStore(consent = true)
            val switch = switch(store.proxy)

            assertEquals(true, callSuspend(switch, "isOn"))
            assertEquals("turning off did not report that it was saved", true, callSuspend(switch, "turnOff"))

            assertEquals("the consent was not cleared exactly once", 1, store.clears.get())
            assertEquals(false, callSuspend(switch, "isOn"))
            assertEquals("the switch touched something else in the store: ${store.other}", emptyList<String>(), store.other)
        }

    @Test
    fun `a turn-off that does not reach the disk changes nothing and says so`() =
        runBlocking {
            val store = ConsentStore(consent = true, clearSaved = false)
            val switch = switch(store.proxy)

            assertEquals("a clear that was not saved was reported as saved", false, callSuspend(switch, "turnOff"))
            assertEquals("the row would show Off although the payments are still on", true, callSuspend(switch, "isOn"))
        }

    @Test
    fun `after it is turned off an automatic key reply asks first and pays nothing`() =
        runBlocking {
            val peer = "usyntheticki23switchpeer0001"
            val prefs = HandshakePrefs(peer, consent = true)
            // W-62 (ZC-599): a key exchange at a chat that still waits for the contact's first key (our key pair, no
            // contact key) now waits for Accept. This case is about the switch, so the contact's key is already held
            // (the key exchange repeats it).
            val keys = E2EEncryption.generateKeyPair()
            prefs.e2ePeerKey[peer] = keys.publicKey
            val seam = HandshakeSeam(peer)
            val chat = HandshakeHarness.handshakeViewModel(prefs, seam)

            assertEquals(true, callSuspend(switch(prefs.proxy), "turnOff"))
            HandshakeHarness.handleKexMemo(chat, HandshakeHarness.kexMemoFrom(peer, keys), "synthetic-ki23-kex")
            HandshakeHarness.awaitIdle(chat)

            assertEquals("an automatic key reply was paid after automatic setup payments were turned off", 0, seam.proposalRequests.get())
            assertTrue(
                "with the payments off the key reply did not ask first",
                HandshakeHarness.sendMessageState(chat) is SendMessageState.NeedsHandshakeSpendConsent,
            )
        }

    // ZC-1013 (coordinator's decision, 15 Oct 2026): the row may turn the payments back on, behind its confirm; only the
    // switch's own turnOn writes the standing consent (AutomaticSetupSwitchTurnsBackOnTest pins the confirm).
    @Test
    fun only_the_switch_under_advanced_settings_can_turn_the_payments_on() {
        val folder = requireNotNull(sourceFile("ui/screen/advancedsettings/AdvancedSettingsVM.kt").parentFile)
        val offenders =
            folder
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("setConsentedToHandshakeSpend") }
                .map { it.name }
                .toList()
        assertEquals("a file under advancedsettings/ grants the standing consent", listOf("AutomaticSetupPayments.kt"), offenders)
    }

    @Test
    fun the_row_reads_the_consent_off_the_state_builder_and_only_the_confirm_turns_it_off() {
        assertTrue(
            "AdvancedSettingsVM is not given AutomaticSetupPayments",
            vm.contains("private val automaticSetupPayments: AutomaticSetupPayments"),
        )
        val builder = functionBody(vm, "createState")
        assertTrue("the row's title is not its string", builder.contains("R.string.advanced_settings_auto_setup_payments)"))
        for (key in listOf("subtitle_on", "subtitle_off")) {
            val resource = "R.string.advanced_settings_auto_setup_payments_$key"
            assertTrue("the row does not show $resource", builder.contains(resource))
        }
        val readsConsent = builder.contains("isOn(") || builder.contains("hasConsentedToHandshakeSpend")
        assertFalse("createState reads the consent (main thread)", readsConsent)
        assertFalse("the view model reads the store itself", vm.contains("hasConsentedToHandshakeSpend") || vm.contains("ZchatPreferences"))
        assertTrue("the consent is never read", vm.contains("automaticSetupPayments.isOn()"))

        val calls = Regex("""automaticSetupPayments\.turnOff\(\)""").findAll(vm).count()
        assertEquals("turnOff is not called from exactly one place", 1, calls)
        val confirm = functionBody(vm, "onAutomaticSetupPaymentsTurnOffConfirm")
        assertTrue("turnOff is not in the confirm handler", confirm.contains("automaticSetupPayments.turnOff()"))
        assertTrue("a turn-off that was not saved is not told to the user", confirm.contains("NOT_SAVED"))
        assertTrue("the row is not read again after the turn-off", confirm.contains("automaticSetupPayments.isOn()"))
        assertFalse("the row's tap turns the payments off at once", functionBody(vm, "onAutomaticSetupPaymentsClick").contains("turnOff"))
    }

    @Test
    fun the_view_shows_the_dialogs_with_their_own_texts() {
        assertTrue("the state carries no dialog", state.contains("automaticSetupPaymentsDialog"))
        assertTrue("the view does not show the dialog", view.contains("state.automaticSetupPaymentsDialog"))
        val keys =
            listOf("off_title", "off_text", "off_button", "cancel_button", "on_title", "on_text", "on_button", "ok_button", "not_saved")
        for (key in keys) {
            assertTrue(
                "the view does not show advanced_settings_auto_setup_payments_$key",
                view.contains("R.string.advanced_settings_auto_setup_payments_$key"),
            )
        }
    }

    /** The text of the function [name] in [source]: from its declaration to the next declaration at the same depth. */
    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val start = Regex("""fun $name\(""").find(source)?.range?.first ?: throw AssertionError("there is no fun $name")
        val next = Regex("""\n    (private |internal |override )?fun """).find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }

    private fun source(relative: String): String = sourceFile(relative).readText()

    private fun sourceFile(relative: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                ?: throw AssertionError("could not locate the ui-lib module from ${start.path}")
        val file = module.resolve("src/main/java/co/electriccoin/zcash/$relative")
        if (!file.isFile) throw AssertionError("missing ${file.path}")
        return file
    }
}
