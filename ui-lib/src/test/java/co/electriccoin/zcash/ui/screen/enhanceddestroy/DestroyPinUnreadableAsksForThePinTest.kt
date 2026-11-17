package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1042 (cross-family review of 19 Oct 2026, GPT T7aD-1 finding 6, high) — Emergency Destroy reads whether a Destroy PIN
 * is set with `runCatching { hasDestroyPin() }.getOrDefault(false)`: a store that cannot be read counts as "no PIN", so
 * the phone's screen lock, or on a phone without one the single button, starts the countdown with no PIN proof.
 *
 * Wanted (fail closed): a PIN store that cannot be read is treated as a PIN that is set. The PIN is asked; the screen lock
 * and the no-proof button start nothing. A PIN check that cannot read the store starts nothing either, and says that the
 * PIN could not be checked (EN and ES), never "incorrect".
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestroyPinUnreadableAsksForThePinTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A Destroy store that cannot be read: every read throws, and the PIN check too. */
    private class UnreadablePrefs {
        val verifies = AtomicInteger()
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "UnreadablePrefs"
                    "verifyDestroyPinWithLockout" -> {
                        verifies.incrementAndGet()
                        throw IllegalStateException("synthetic store fault")
                    }
                    else -> throw IllegalStateException("synthetic store fault: ${method.name}")
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

    private fun <T> emergency(
        lock: Boolean,
        block: (EnhancedDestroyVM, UnreadablePrefs, FakePhone) -> T,
    ): T =
        FakePhone().use { phone ->
            val prefs = UnreadablePrefs()
            val vm = EnhancedDestroyVM(router, prefs.proxy, phone.manager(), { lock })
            scheduler.runCurrent()
            block(vm, prefs, phone)
        }

    @Test
    fun `a PIN store that cannot be read asks for the PIN, and the no-proof button starts nothing`() =
        emergency(lock = false) { vm, _, phone ->
            assertTrue("a PIN store that cannot be read counts as no PIN", vm.state.value.hasPin)
            vm.state.value.onStartWithoutProof()
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            assertEquals("the countdown started with no PIN proof", DestroyStep.CONFIRM_INTENT, vm.state.value.currentStep)
            assertFalse("a wipe started with no PIN proof", phone.recordFile.exists())
        }

    @Test
    fun `a PIN store that cannot be read is not passed by the phone's screen lock`() =
        emergency(lock = true) { vm, _, phone ->
            assertTrue("a PIN store that cannot be read counts as no PIN", vm.state.value.hasPin)
            vm.handleBiometricSuccess()
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            assertEquals("the screen lock replaced the PIN proof", DestroyStep.CONFIRM_INTENT, vm.state.value.currentStep)
            assertFalse("a wipe started with no PIN proof", phone.recordFile.exists())
        }

    @Test
    fun `a PIN check that cannot read the store starts nothing and says the PIN could not be checked`() =
        emergency(lock = false) { vm, prefs, phone ->
            vm.state.value.onPinChange("2468")
            vm.state.value.onPinSubmit()
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            assertEquals("the PIN was not asked for and checked", 1, prefs.verifies.get())
            assertEquals("the countdown started with no PIN proof", DestroyStep.CONFIRM_INTENT, vm.state.value.currentStep)
            assertFalse("a wipe started with no PIN proof", phone.recordFile.exists())
            val error: StringResource? = vm.state.value.pinError
            val cannotCheck =
                runCatching { R.string::class.java.getField("destroy_pin_cannot_check").getInt(null) }.getOrNull()
                    ?: throw AssertionError("there is no text that says the PIN could not be checked")
            assertEquals("the refusal does not say the PIN could not be checked", stringRes(cannotCheck), error)
            val en = ChatResourceText.strings("values")
            val es = ChatResourceText.strings("values-es")
            val english = en["destroy_pin_cannot_check"] ?: throw AssertionError("no English destroy_pin_cannot_check")
            assertFalse("the text says the PIN was wrong", "Incorrect" in english)
            assertNotEquals("not translated", english, es["destroy_pin_cannot_check"] ?: throw AssertionError("no Spanish text"))
        }
}
