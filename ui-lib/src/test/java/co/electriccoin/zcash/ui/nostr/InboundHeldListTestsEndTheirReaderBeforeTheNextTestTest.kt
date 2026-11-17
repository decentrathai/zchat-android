package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-710: drive the real held-list test's teardown with a reader that cannot leave until released.
 * No production seam or load is needed; the cancellation hold decides when the old reader can leave.
 */
class InboundHeldListTestsEndTheirReaderBeforeTheNextTestTest {
    @Test
    fun `tearDown does not return while its cancelled reader is still subscribed`() {
        withHeldReader { held ->
            held.startTeardown()
            held.awaitCancellingHold()
            assertEquals(1, inboundReaders().subscriptionCount.value, "fixture: the held reader lost its subscription")
            assertFalse(
                held.returned.await(HOLD_MS, TimeUnit.MILLISECONDS),
                "InboundWithoutCollectorIsHeldTest.tearDown returned while its cancelled reader was still subscribed",
            )
        }
    }

    @Test
    fun `the original bounded test reads 512 after the preceding tearDown returns`() {
        withHeldReader { held ->
            held.startTeardown()
            held.awaitCancellingHold()
            val returnedWhileHeld = held.returned.await(HOLD_MS, TimeUnit.MILLISECONDS)
            val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var releaseReader: Deferred<Unit>? = null
            val next = InboundWithoutCollectorIsHeldTest()
            try {
                if (returnedWhileHeld) {
                    held.assertTeardownSucceeded()
                    releaseReader =
                        releaseScope.async {
                            // The stale reader consumes the burst's first 256 buffer slots. Release it only AFTER
                            // the fresh reader subscribes, so the fresh reader starts beyond those old slots and
                            // receives the 344 held entries. Holding it throughout would instead block delivery.
                            withTimeout(WAIT_MS) { inboundReaders().subscriptionCount.first { it == 2 } }
                            held.release()
                        }
                } else {
                    // A repaired teardown is still waiting: let its own reader finish before the next test.
                    held.releaseAndFinish()
                }
                next.`the held list is bounded and drops the oldest first`()
            } finally {
                held.release()
                cleanupAll(
                    { finishOriginal(next) },
                    { releaseReader?.let { runBlocking { it.await() } } },
                    { endScope(releaseScope) },
                )
            }
        }
    }

    @Test
    fun `control - a released reader finishes and leaves no bridge subscriber`() {
        withHeldReader { held ->
            held.release()
            held.startTeardown()
            held.releaseAndFinish()
            assertEquals(0, inboundReaders().subscriptionCount.value)
        }
    }

    @Test
    fun `control - the original bounded test keeps 512 when the old reader has finished`() {
        withHeldReader { held ->
            held.release()
            held.startTeardown()
            held.releaseAndFinish()
            val next = InboundWithoutCollectorIsHeldTest()
            try {
                next.`the held list is bounded and drops the oldest first`()
            } finally {
                finishOriginal(next)
            }
        }
    }

    private fun withHeldReader(check: (HeldReader) -> Unit) {
        establishEmptyBoundary()
        val held = HeldReader()
        try {
            held.startCollecting()
            held.awaitCollecting()
            check(held)
        } finally {
            cleanupAll(
                { held.releaseAndFinish() },
                { awaitNoReaders() },
                { assertTrue(heldQueueIsEmpty(), "fixture: this case left held messages behind") },
            )
        }
    }

    private class HeldReader {
        private val original = InboundWithoutCollectorIsHeldTest()
        private val scope = readerScopeOf(original)
        private val collecting = CountDownLatch(1)
        private val cancelling = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val returned = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private var teardown: Thread? = null
        private var reader: Job? = null

        fun startCollecting() {
            val dm =
                InboundWithoutCollectorIsHeldTest::class.java
                    .getDeclaredMethod("dm", Integer.TYPE)
                    .apply { isAccessible = true }
                    .invoke(original, -1) as NostrInboxManager.InboundDm
            reader =
                scope.launch(CoroutineExceptionHandler { _, error -> failure.compareAndSet(null, error) }) {
                    NostrChatBridge.inbound.collect { chat ->
                        if (chat.eventId == dm.eventId) {
                            collecting.countDown()
                            try {
                                awaitCancellation()
                            } finally {
                                cancelling.countDown()
                                assertTrue(
                                    released.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS),
                                    "fixture: cancellation hold was never released",
                                )
                            }
                        }
                    }
                }
            runBlocking { withTimeout(WAIT_MS) { inboundReaders().subscriptionCount.first { it == 1 } } }
            NostrChatBridge.dispatch(dm, originalPreferences())
        }

        fun awaitCollecting() {
            assertTrue(collecting.await(WAIT_MS, TimeUnit.MILLISECONDS), "fixture: the tagged DM was never collected")
        }

        fun startTeardown() {
            assertTrue(teardown == null, "fixture: teardown was started twice")
            teardown =
                thread(name = "zc710-original-teardown") {
                    try {
                        original.tearDown()
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        returned.countDown()
                    }
                }
        }

        fun awaitCancellingHold() {
            assertTrue(cancelling.await(WAIT_MS, TimeUnit.MILLISECONDS), "fixture: original teardown did not cancel its reader")
        }

        fun assertTeardownSucceeded() {
            failure.get()?.let { throw AssertionError("original teardown threw instead of finishing", it) }
        }

        fun release() = released.countDown()

        fun releaseAndFinish() {
            release()
            cleanupAll(
                { endScope(scope) },
                {
                    teardown?.let { worker ->
                        worker.join(WAIT_MS)
                        assertFalse(worker.isAlive, "fixture: original teardown thread is still running after release")
                    }
                },
                { reader?.let { assertTrue(it.isCompleted, "fixture: held reader did not finish after release") } },
                { assertTeardownSucceeded() },
            )
        }
    }

    private companion object {
        const val HOLD_MS = 500L
        const val WAIT_MS = 10_000L
        const val HOLD_LIMIT_MS = 30_000L

        fun readerScopeOf(test: InboundWithoutCollectorIsHeldTest): CoroutineScope =
            InboundWithoutCollectorIsHeldTest::class.java
                .getDeclaredField("readerScope")
                .apply { isAccessible = true }
                .get(test) as CoroutineScope

        fun originalPreferences(): ZchatPreferences {
            val type = InboundWithoutCollectorIsHeldTest::class.java.declaredClasses.single { it.simpleName == "Prefs" }
            val prefs = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            return type.getDeclaredField("proxy").apply { isAccessible = true }.get(prefs) as ZchatPreferences
        }

        fun inboundReaders(): MutableSharedFlow<*> =
            NostrChatBridge::class.java
                .getDeclaredField("_inbound")
                .apply { isAccessible = true }
                .get(NostrChatBridge)
                as MutableSharedFlow<*>

        fun awaitNoReaders() =
            runBlocking {
                withTimeout(WAIT_MS) { inboundReaders().subscriptionCount.first { it == 0 } }
            }

        fun heldQueueIsEmpty(): Boolean {
            val queue =
                NostrChatBridge::class.java
                    .getDeclaredField("held")
                    .apply { isAccessible = true }
                    .get(NostrChatBridge)
                    as Collection<*>
            return synchronized(queue) { queue.isEmpty() }
        }

        /** Drain earlier synthetic test entries through the public flow, never by clearing the bridge's queue. */
        fun establishEmptyBoundary() {
            awaitNoReaders()
            val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                drainScope.launch { NostrChatBridge.inbound.collect {} }
                runBlocking {
                    withTimeout(WAIT_MS) {
                        inboundReaders().subscriptionCount.first { it == 1 }
                        while (!heldQueueIsEmpty()) delay(10)
                    }
                }
            } finally {
                endScope(drainScope)
            }
            awaitNoReaders()
            assertTrue(heldQueueIsEmpty(), "fixture: bridge did not reach an empty held-list boundary")
        }

        fun finishOriginal(test: InboundWithoutCollectorIsHeldTest) {
            try {
                test.tearDown()
            } finally {
                endScope(readerScopeOf(test))
            }
        }

        /** A failed join never prevents the independent cleanup of another owned scope or thread. */
        fun cleanupAll(vararg actions: () -> Unit) {
            var failure: Throwable? = null
            for (action in actions) {
                try {
                    action()
                } catch (error: Throwable) {
                    val earlier = failure
                    if (earlier == null) failure = error else earlier.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }

        fun endScope(scope: CoroutineScope) {
            scope.cancel()
            runBlocking { withTimeout(WAIT_MS) { assertNotNull(scope.coroutineContext[Job]).join() } }
        }
    }
}
