package co.electriccoin.zcash.ui.screen.deletewallet

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding [12] (main-thread-io), pinned again for the Reset of ZC-090-R (review REVIEW_T7aR_2_20261020.md, limitation
 * 4: ResetZashiWipeDispatcherTest was removed with the old in-process wipe, so nothing pinned the Reset's work to the
 * IO thread any more). `ResetZashiUseCase.invoke` is called from the screen's view model on the main thread; after the
 * unlock prompt its whole body (the payment gate, the steps it waits for, the record, the restart) must run on the IO
 * pool, never on the caller's thread: a body on the main thread blocks it for up to the steps' limits (seconds) and, under
 * StrictMode's penaltyDeath, stops the app in the middle of the Reset.
 *
 * The restart (step S7) is called by the body itself, not by a step thread, so the thread it runs on is the body's.
 * Driven whole on FakePhone ([ResetDriver]); keep ticked and unticked.
 */
class ResetWorkRunsOnTheIoThreadTest {
    private val drivers = ArrayList<ResetDriver>()

    @After
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    @Test
    fun `the Reset's body runs on the IO pool, never on the caller's thread`() {
        for (keepFiles in listOf(true, false)) {
            val reset = ResetDriver().also { drivers += it }.apply { seedPhraseStore() }
            var body: Thread? = null
            reset.onRestart = { body = Thread.currentThread() }
            val caller = Thread.currentThread()

            val answer = runBlocking { reset.reset(keepFiles) }

            assertEquals("keep=$keepFiles: the Reset did not start", "STARTED", answer)
            val thread = body
            assertNotNull("keep=$keepFiles: the restart was not called", thread)
            assertNotSame("keep=$keepFiles: the Reset ran on the caller's (main) thread", caller, thread)
            assertTrue(
                "keep=$keepFiles: the Reset's body did not run on the IO pool (${thread?.name})",
                thread!!.name.startsWith("DefaultDispatcher-worker"),
            )
        }
    }
}
