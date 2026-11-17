package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A failed/abandoned test must leave neither the ViewModel nor its detached SDK submission running. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendHarnessLifecycleTest {
    private val scopes = SendHarnessScopes()
    private var closed = false

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        if (!closed) scopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `cleanup joins an abandoned held submission without releasing or broadcasting it`() =
        runBlocking {
            val peer = "usyntheticfixturecleanup0001"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer, holdFirstSubmit = true)
            val vm = PaymentHarness.paymentViewModel(prefs, seam)
            assertTrue(vm.sendMessage(peer, "synthetic abandoned submission", 50_000L))
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }

            // Capture both registered jobs before close drains the owner. This narrow reflection
            // inspects the TEST fixture, not the production repository or its implementation paths.
            @Suppress("UNCHECKED_CAST")
            val owned =
                (
                    SendHarnessScopes::class.java
                        .getDeclaredField("jobs")
                        .apply { isAccessible = true }
                        .get(scopes) as Set<Job>
                ).toList()
            assertEquals("one VM and one detached repository must be owned", 2, owned.size)
            assertTrue(owned.contains(vm.viewModelScope.coroutineContext.job))
            assertTrue("settlement is still held before cleanup", owned.all { it.isActive })

            // Model @After after a test exits without calling finish(seam). Do not release the
            // artificial SDK gate: teardown must cancel the attempted send, not synthesize success.
            scopes.close()
            closed = true

            assertTrue("cleanup returned before a job completed", owned.all { it.isCompleted })
            assertTrue("both root jobs must be cancelled", owned.all { it.isCancelled })
            assertFalse("cleanup released the SDK gate", seam.release.isCompleted)
            assertFalse("cleanup manufactured a successful SDK completion", seam.firstSubmitDone.isCompleted)
            assertEquals(1, seam.submits.get())
            assertTrue("abandoned submission was broadcast during cleanup", seam.broadcasts.isEmpty())
            assertEquals(emptyList<String>(), prefs.unexpectedCalls)
            // @After resets Main only after these completed-job assertions.
        }
}
