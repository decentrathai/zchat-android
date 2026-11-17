package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AiWarningShownMeansOnTheScreenTest has finished its screens when its tearDown unsets Dispatchers.Main.
 *
 * A cancelled screen is not finished when `viewModelScope.cancel()` returns: its work ends on another thread and then
 * resumes its parents through Dispatchers.Main. A tearDown that unsets Main at once loses that race now and then, the
 * pool thread throws "Dispatchers.Main was accessed when the platform dispatcher was absent", and the next `runTest` in
 * the JVM fails with UncaughtExceptionsBeforeTest (SendAllStateTest, 53 classes later, full run at e50e61a97).
 *
 * No race in this test: the screen handed to the tearDown cannot finish until this test lets it go, and it never touches
 * Main while it ends. A tearDown that returns during the hold did not wait for its screens.
 */
class AiWarningTearDownEndsItsScreensFirstTest {
    private class HeldScreen : ViewModel() {
        val started = CountDownLatch(1)
        val letGo = CountDownLatch(1)

        init {
            viewModelScope.launch(Dispatchers.Default) {
                started.countDown()
                try {
                    awaitCancellation()
                } finally {
                    letGo.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    @Test
    fun `tearDown waits for a cancelled screen before it unsets Main`() {
        val test = AiWarningShownMeansOnTheScreenTest()
        test.setUp()
        val screen = HeldScreen()
        assertTrue("fixture: the held screen never started", screen.started.await(WAIT_MS, TimeUnit.MILLISECONDS))
        screensOf(test) += screen

        var thrown: Throwable? = null
        val tearDown =
            thread(name = "ai-warning-tear-down", isDaemon = true) {
                try {
                    test.tearDown()
                } catch (e: InvocationTargetException) {
                    thrown = e.cause ?: e
                } catch (e: IllegalStateException) {
                    thrown = e
                }
            }
        tearDown.join(HOLD_MS)
        val returnedDuringTheHold = !tearDown.isAlive
        screen.letGo.countDown()
        tearDown.join(WAIT_MS)

        assertNull("tearDown threw", thrown)
        assertTrue(
            "tearDown unset Dispatchers.Main while its screen was still finishing; the next runTest in the JVM can fail " +
                "with UncaughtExceptionsBeforeTest",
            !returnedDuringTheHold,
        )
        assertTrue("tearDown never returned after its screen ended", !tearDown.isAlive)
    }

    @Suppress("UNCHECKED_CAST")
    private fun screensOf(test: AiWarningShownMeansOnTheScreenTest): MutableList<ViewModel> =
        AiWarningShownMeansOnTheScreenTest::class.java
            .getDeclaredField("screens")
            .apply { isAccessible = true }
            .get(test) as MutableList<ViewModel>

    private companion object {
        const val WAIT_MS = 10_000L

        /** A tearDown that does not wait returns at once; the class's own wait lasts longer than this. */
        const val HOLD_MS = 500L
        const val HOLD_LIMIT_MS = 30_000L
    }
}
