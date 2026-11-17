package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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

/**
 * DESTROY-ADV-P (register ZC-722; owner W-52: nothing is asked when Destroy All is triggered; W-57: with a Destroy
 * PIN the PIN alone starts Emergency Destroy's countdown, and "I Understand, Continue" goes; Destroy Advanced spec
 * revision 2c, 5.5, 7.2 to 7.4 rules T1 to T5, 9.6 and 10.3).
 *
 * The chat list: no PIN set-up and no PIN reset in the middle of Destroy; one PIN field; the final dialog opens after
 * a right PIN, a confirmed screen lock, or (a phone with neither, B3) from the red button itself; a confirmed screen
 * lock deletes and sets nothing. Emergency Destroy: ONE screen (the fixed sentences and the proof) and then the
 * five-second countdown, which starts from exactly three places; DestroyStep is CONFIRM_INTENT, COUNTDOWN, DESTROYING.
 * Neither path names a set-up, a write of the PIN or of the choice, or the AI code. The texts that went are gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestroyTriggerAsksNothingTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun code(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        assertTrue("fixture: $path", file.isFile)
        return SourceScan.stripComments(file.readText())
    }

    private val clv by lazy { code(CLV) }

    /** The chat list's Destroy region: from the mark "PIN Verify Dialog" to the end of the final dialog. */
    private val clvRegion by lazy {
        val raw = File(ChatResourceText.repoRoot, CLV).readText()
        val start = raw.indexOf("// PIN Verify Dialog")
        val end = raw.indexOf("// Group Chat Coming Soon Dialog")
        assertTrue("fixture: the Destroy dialogs of ChatListView", start in 0 until end)
        SourceScan.stripComments(raw.substring(start, end))
    }

    // ---- the chat list ------------------------------------------------------------------------------------------------

    @Test
    fun `(1) the chat list sets up and resets no PIN`() {
        assertFalse("showPinSetupDialog" in clv)
        for (name in listOf("onSetupDestroyPin", "onResetDestroyPin")) assertFalse("ChatListView has $name", name in clv)
        for (key in listOf(
            "chat_destroy_set_pin_title",
            "chat_destroy_set_pin_body",
            "chat_destroy_pin_new_label",
            "chat_destroy_pin_confirm_label",
            "chat_destroy_pin_mismatch",
            "chat_destroy_pin_reset_title",
            "chat_destroy_pin_reset_prompt",
        )) {
            assertFalse("ChatListView uses $key", "R.string.$key" in clv)
        }
        val chat = code(ANDROID_CHAT)
        assertFalse("AndroidChat sets a PIN", "setDestroyPin(" in chat)
        assertFalse("AndroidChat clears the PIN", "clearDestroyPin(" in chat)
    }

    @Test
    fun `(2) one PIN field and no option between the PIN dialog and the end of the final dialog`() {
        assertEquals(1, Regex("""OutlinedTextField\(""").findAll(clvRegion).count())
        for (word in listOf(
            "Checkbox",
            "Switch(",
            "RadioButton",
            "ExpandMore",
            "AnimatedVisibility",
            "navigationRouter",
            "setDestroy",
            "screen.ai.",
        )) {
            assertFalse("the chat list's Destroy region has $word", word in clvRegion)
        }
    }

    @Test
    fun `(3) the final dialog opens in exactly three places`() {
        val opens = Regex("""showDestroyDialog = true""").findAll(clv).map { it.range.first }.toList()
        assertEquals("showDestroyDialog = true in exactly three places", 3, opens.size)
        val success = clv.indexOf("result == DestroyPinVerifyResult.Success")
        assertTrue("one after a right PIN", opens.any { it > success && it - success < 200 })
        val lock = clv.indexOf("Activity.RESULT_OK")
        assertTrue("one in the screen-lock result", opens.any { it > lock && it - lock < 200 })
        val neither = KeptNamesScreenApi.functionBody(clv, "onDestroyTapped") ?: throw AssertionError("no onDestroyTapped")
        assertTrue("one in the red button's branch for a phone with neither", "showDestroyDialog = true" in neither)
    }

    @Test
    fun `(4) with no PIN the red button asks for the screen lock, with neither it opens the final dialog, no notice`() {
        val tap = KeptNamesScreenApi.functionBody(clv, "onDestroyTapped") ?: throw AssertionError("no onDestroyTapped")
        assertTrue("no PIN: the screen lock", "launchDestroyScreenLock()" in tap)
        val launcher = KeptNamesScreenApi.functionBody(clv, "launchDestroyScreenLock") ?: throw AssertionError("no launchDestroyScreenLock")
        assertTrue("its title", "R.string.destroy_heading" in launcher)
        assertTrue("its text", "R.string.chat_destroy_screen_lock_prompt" in launcher)
        assertFalse("destroy_error_no_auth" in clv)
        assertFalse("destroy_error_no_auth" in code(ANDROID_CHAT))
    }

    @Test
    fun `(5) a confirmed screen lock deletes and sets nothing`() {
        val at = clv.indexOf("Activity.RESULT_OK")
        assertTrue("fixture: the screen-lock result", at >= 0)
        val result = clv.substring(at, clv.indexOf('}', at))
        for (write in listOf("clear", "setDestroy", "onReset", "PinSetup", "navigate")) {
            assertFalse("the result calls $write", write in result)
        }
    }

    @Test
    fun `(6) guard - onDestroyAll() is called only inside the final dialog's confirm button`() {
        val calls = Regex("""onDestroyAll\(\)""").findAll(clv).map { it.range.first }.toList()
        assertEquals(1, calls.size)
        assertTrue("onDestroyAll() is in the final dialog", calls.single() > clv.indexOf("R.string.chat_destroy_final_title"))
        assertTrue(
            "inside its confirm button",
            clv.lastIndexOf("confirmButton", calls.single()) > clv.indexOf("R.string.chat_destroy_final_title")
        )
    }

    // ---- Emergency Destroy --------------------------------------------------------------------------------------------

    @Test
    fun `(7) DestroyStep is exactly CONFIRM_INTENT, COUNTDOWN, DESTROYING`() {
        assertEquals(listOf("CONFIRM_INTENT", "COUNTDOWN", "DESTROYING"), DestroyStep.entries.map { it.name })
    }

    @Test
    fun `(8) the countdown starts from exactly three places, and with a PIN the screen lock is never asked`() {
        val vm = code(EDM)
        assertEquals("onStartCountdown() from three places", 3, Regex("""onStartCountdown\(\)""").findAll(vm).count() - 1)
        for (name in listOf("handleBiometricSuccess", "onPinSubmit", "onStartWithoutProof")) {
            val body = KeptNamesScreenApi.functionBody(vm, name) ?: throw AssertionError("no $name")
            assertTrue("$name starts the countdown", "onStartCountdown()" in body)
        }
        val pin = KeptNamesScreenApi.functionBody(vm, "onPinSubmit")!!
        assertFalse("after a right PIN the screen lock is asked", Regex("isBiometricAvailable|BIOMETRIC").containsMatchIn(pin))
        val cancel = KeptNamesScreenApi.functionBody(vm, "onCancelCountdown") ?: throw AssertionError("no onCancelCountdown")
        assertTrue("CANCEL returns to the first screen", "DestroyStep.CONFIRM_INTENT" in cancel)
        assertTrue("with an empty PIN field", Regex("""pinInput\s*=\s*""""").containsMatchIn(cancel))
        assertFalse("the view has no onStartCountdown", "onStartCountdown" in code(EDS))
    }

    @Test
    fun `(9) one screen - the proof control on the first screen, no Continue step, the sentences in their places`() {
        val view = code(EDV)
        assertEquals(1, Regex("""OutlinedTextField\(""").findAll(view).count())
        for (word in listOf("Checkbox", "Switch(", "RadioButton", "ExpandMore", "AnimatedVisibility")) assertFalse(word in view)
        for (key in listOf("destroy_last_step", "destroy_go_back", "destroy_continue")) {
            assertFalse("the view uses $key", "R.string.$key" in view)
        }
        val first = KeptNamesScreenApi.functionBody(view, "ConfirmIntentStep") ?: throw AssertionError("no ConfirmIntentStep")
        assertTrue("the PIN field with a PIN", "OutlinedTextField(" in first)
        assertTrue("Authenticate without a PIN", "R.string.destroy_authenticate" in first)
        assertTrue("Start 5-Second Countdown with neither", "R.string.destroy_start_countdown" in first)
        assertTrue("destroy_no_notice on the first screen", "R.string.destroy_no_notice" in first)
        val countdown = KeptNamesScreenApi.functionBody(view, "CountdownStep") ?: throw AssertionError("no CountdownStep")
        assertTrue("destroy_after_countdown in the countdown", "R.string.destroy_after_countdown" in countdown)
        assertTrue("and its kept variant", "R.string.destroy_after_countdown_keep_contacts" in countdown)
    }

    @Test
    fun `(10) neither path names a set-up, a write of the PIN or the choice, or the AI code`() {
        for (text in listOf(clvRegion, code(EDV), code(EDM), code(AED))) {
            for (name in listOf(
                "DestroyPinSetup",
                "DestroyOptions",
                "setDestroyPin(",
                "setDestroyKeep(",
                "setDestroySetup(",
                "clearDestroyPin(",
                "screen.ai.",
            )) {
                assertFalse("a Destroy path names $name", name in text)
            }
        }
    }

    @Test
    fun `(11) the removed keys are gone, the forgot line names the screen lock, the prompt text exists`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in listOf(
            "chat_destroy_set_pin_continue",
            "destroy_last_step",
            "destroy_go_back",
            "destroy_continue",
            "destroy_error_no_auth",
        )) {
            assertFalse("$key still exists", key in en || key in es)
        }
        assertTrue("destroy_start_countdown is kept", "destroy_start_countdown" in en && "destroy_start_countdown" in es)
        val forgot = en.getValue("chat_destroy_forgot_pin")
        assertTrue("screen lock" in forgot)
        assertFalse("Reset" in forgot)
        val forgotEs = es.getValue("chat_destroy_forgot_pin")
        assertTrue("bloqueo de pantalla" in forgotEs)
        assertFalse("Restabl" in forgotEs)
        assertTrue("chat_destroy_screen_lock_prompt" in en && "chat_destroy_screen_lock_prompt" in es)
    }

    // ---- (12) the three states on the real view model ------------------------------------------------------------------

    private class Prefs(
        val pin: String?,
    ) {
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "hasDestroyPin" -> pin != null
                    "getDestroyKeep" -> emptySet<String>()
                    "walletScopeGeneration" -> 1L
                    "verifyDestroyPinWithLockout" ->
                        if (args!![0] == pin) DestroyPinVerifyResult.Success else DestroyPinVerifyResult.Failed(attemptsRemaining = 4)
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
                }
            } as ZchatPreferences
    }

    private val router: NavigationRouter =
        Proxy.newProxyInstance(NavigationRouter::class.java.classLoader, arrayOf(NavigationRouter::class.java)) { p, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> null
            }
        } as NavigationRouter

    /** The real view model on a FakePhone manager; [lock] is whether the phone has a screen lock. */
    private fun <T> emergency(
        pin: String?,
        lock: Boolean,
        block: (vm: Any, phone: FakePhone) -> T,
    ): T =
        FakePhone().use { phone ->
            val manager = phone.manager()
            DestroyManager::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(manager, Prefs(pin).proxy)
            val hasLock: () -> Boolean = { lock }
            val vm =
                EnhancedDestroyVM::class.java.constructors
                    .firstOrNull { c ->
                        c.parameterTypes.contentEquals(
                            arrayOf<Class<*>>(
                                NavigationRouter::class.java,
                                ZchatPreferences::class.java,
                                DestroyManager::class.java,
                                Function0::class.java
                            )
                        )
                    }?.newInstance(router, Prefs(pin).proxy, manager, hasLock)
                    ?: throw AssertionError("EnhancedDestroyVM cannot be built with a reader of the phone's screen lock")
            scheduler.runCurrent()
            block(vm, phone)
        }

    private fun step(vm: Any): String = call((call(vm, "getState") as StateFlow<*>).value!!, "getCurrentStep").toString()

    private fun call(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("${target.javaClass.simpleName} has no $name")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateCall(
        vm: Any,
        getter: String,
        vararg args: Any,
    ) {
        val function = call((call(vm, "getState") as StateFlow<*>).value!!, getter)
        when (function) {
            is Function0<*> -> function.invoke()
            is Function1<*, *> -> (function as Function1<Any, Any?>).invoke(args.single())
            else -> throw AssertionError("$getter is not a function")
        }
    }

    @Test
    fun `(12) each state reaches the countdown by one act and the wipe only after five seconds, CANCEL at four stops it`() {
        val cases: List<Triple<String, String?, Boolean>> =
            listOf(Triple("a PIN", "2468", true), Triple("no PIN and a screen lock", null, true), Triple("neither", null, false))
        for ((name, pin, lock) in cases) {
            emergency(pin, lock) { vm, phone ->
                assertEquals("$name: the first screen", "CONFIRM_INTENT", step(vm))
                when {
                    pin != null -> {
                        stateCall(vm, "getOnPinChange", pin)
                        stateCall(vm, "getOnPinSubmit")
                    }
                    lock -> call(vm, "handleBiometricSuccess")
                    else -> stateCall(vm, "getOnStartWithoutProof")
                }
                scheduler.runCurrent()
                assertEquals("$name: one act reaches the countdown", "COUNTDOWN", step(vm))
                scheduler.advanceTimeBy(4_000)
                scheduler.runCurrent()
                assertEquals("$name: still counting at four seconds", "COUNTDOWN", step(vm))
                stateCall(vm, "getOnCancelCountdown")
                scheduler.advanceTimeBy(10_000)
                scheduler.runCurrent()
                assertEquals("$name: CANCEL returns to the first screen", "CONFIRM_INTENT", step(vm))
                assertFalse("$name: CANCEL at four seconds leaves no wipe", phone.recordFile.exists())
            }
            emergency(pin, lock) { vm, _ ->
                when {
                    pin != null -> {
                        stateCall(vm, "getOnPinChange", pin)
                        stateCall(vm, "getOnPinSubmit")
                    }
                    lock -> call(vm, "handleBiometricSuccess")
                    else -> stateCall(vm, "getOnStartWithoutProof")
                }
                scheduler.runCurrent()
                scheduler.advanceTimeBy(4_900)
                scheduler.runCurrent()
                assertEquals("$name: no wipe before five seconds", "COUNTDOWN", step(vm))
            }
        }
    }

    @Test
    fun `guards - the countdown is five seconds and ends in onFinalDestroy, destroyAll stands twice`() {
        val vm = code(EDM)
        val start = KeptNamesScreenApi.functionBody(vm, "onStartCountdown") ?: throw AssertionError("no onStartCountdown")
        assertTrue("for (i in 5 downTo 1)" in start)
        assertTrue("onFinalDestroy()" in start)
        assertEquals(2, Regex("""destroyManager\.destroyAll\(requestUninstall = true\)""").findAll(vm).count())
    }

    private companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
        const val CLV = "$UI/screen/chat/view/ChatListView.kt"
        const val ANDROID_CHAT = "$UI/screen/chat/AndroidChat.kt"
        const val EDV = "$UI/screen/enhanceddestroy/view/EnhancedDestroyView.kt"
        const val EDM = "$UI/screen/enhanceddestroy/EnhancedDestroyVM.kt"
        const val EDS = "$UI/screen/enhanceddestroy/EnhancedDestroyState.kt"
        const val AED = "$UI/screen/enhanceddestroy/AndroidEnhancedDestroy.kt"
    }
}
