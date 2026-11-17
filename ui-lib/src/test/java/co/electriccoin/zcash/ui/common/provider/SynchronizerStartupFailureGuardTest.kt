package co.electriccoin.zcash.ui.common.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.test.Test

/** The interception rule behind [SynchronizerStartupFailureGuard]; see SynchronizerInitFailureSurfacesErrorTest. */
class SynchronizerStartupFailureGuardTest {
    private val sdkFrame = StackTraceElement("cash.z.ecc.android.sdk.Synchronizer\$Companion", "new", "Synchronizer.kt", 1)
    private val appFrame = StackTraceElement("co.electriccoin.zcash.ui.SomeViewModel", "load", "SomeViewModel.kt", 1)

    private fun failure(vararg frames: StackTraceElement) =
        IllegalStateException("boom").apply { stackTrace = arrayOf(*frames) }

    @Test
    fun `an SDK failure while no synchronizer runs is a start-up failure`() {
        assertTrue(
            SynchronizerStartupFailureGuard.isStartupFailure(
                throwable = failure(sdkFrame),
                isThreadTerminating = false,
                isSynchronizerStarting = true,
            )
        )
    }

    @Test
    fun `an SDK failure wrapped by app code is still recognised through its cause`() {
        val wrapped = RuntimeException("wrapper", failure(sdkFrame)).apply { stackTrace = arrayOf(appFrame) }
        assertTrue(SynchronizerStartupFailureGuard.isStartupFailure(wrapped, false, true))
    }

    @Test
    fun `nothing is intercepted while a synchronizer is running`() {
        assertFalse(SynchronizerStartupFailureGuard.isStartupFailure(failure(sdkFrame), false, false))
    }

    @Test
    fun `an exception that is terminating its thread is never intercepted`() {
        assertFalse(SynchronizerStartupFailureGuard.isStartupFailure(failure(sdkFrame), true, true))
    }

    @Test
    fun `an app exception with no SDK code in it is never intercepted`() {
        assertFalse(SynchronizerStartupFailureGuard.isStartupFailure(failure(appFrame), false, true))
    }

    @Test
    fun `a non-matching exception is passed to the previous handler and a match is reported instead`() {
        val passedOn = mutableListOf<Throwable>()
        val reported = mutableListOf<Throwable>()
        val guard =
            SynchronizerStartupFailureGuard(
                delegate = { _, throwable -> passedOn += throwable },
                isSynchronizerStarting = { true },
                onStartupFailure = { reported += it },
            )
        val appBug = failure(appFrame)
        val sdkFailure = failure(sdkFrame)

        guard.uncaughtException(Thread.currentThread(), appBug)
        guard.uncaughtException(Thread.currentThread(), sdkFailure)

        assertEquals(listOf<Throwable>(appBug), passedOn)
        assertEquals(listOf<Throwable>(sdkFailure), reported)
    }
}
