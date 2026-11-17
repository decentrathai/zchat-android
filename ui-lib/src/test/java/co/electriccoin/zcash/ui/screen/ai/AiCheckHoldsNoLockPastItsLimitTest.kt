package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.BALANCE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.KEY_STATE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OWN_KEY_ID
import co.electriccoin.zcash.ui.screen.deletewallet.AI_CHECK_LIMIT_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AI key round 3 (W-97; train-AIKEY/ROUND3.md item 6; Grok 6 of review-final1): an operation abandoned at the limit holds no
 * lock, and the Destroy PIN taps have the same limit.
 *
 * WHY IT FAILS TODAY: withinTheLimit leaves its thread blocked inside operation, which holds oneAtATime, so the next AI work
 * waits for a socket that may never answer. linkNow and checkNow have no limit at all, so the busy mark of the Destroy PIN
 * line stays on: its finally is only reached when the call returns.
 *
 * Not pinned: how the lock is released or timed. REAL TIME, as in ResetScreenAiCheckIsBoundedTest: the stand-in blocks its
 * thread and ignores interrupts, which a virtual clock cannot show. Everything here is synthetic.
 */
class AiCheckHoldsNoLockPastItsLimitTest {
    private val phone = AiKeyScreensFixture()
    private val released = CountDownLatch(1)
    private val blockFirst = AtomicBoolean(true)
    private val callsAfterTheFirst = AtomicInteger()

    @After
    fun tearDown() {
        released.countDown()
        phone.close()
    }

    /** The first call blocks its thread until the test lets go; the calls after it answer at once. */
    private fun firstCallHangs(answer: String) {
        phone.answer = {
            if (blockFirst.getAndSet(false)) {
                waitForRelease()
            } else {
                callsAfterTheFirst.incrementAndGet()
            }
            200 to answer
        }
    }

    private fun waitForRelease() {
        while (true) {
            try {
                released.await()
                return
            } catch (_: InterruptedException) {
                // A blocked socket or disk read need not answer an interrupt.
            }
        }
    }

    /** Runs [call] on a thread of its own: true when it ended within [limitMs]. */
    private fun endsWithin(
        limitMs: Long,
        call: suspend () -> Unit,
    ): Boolean {
        val thread =
            Thread {
                runCatching { runBlocking { call() } }
            }.apply { isDaemon = true }
        thread.start()
        thread.join(limitMs)
        return !thread.isAlive
    }

    private fun assertReleasedNothingWritten() {
        released.countDown()
        Thread.sleep(SETTLE_MS)
        assertFalse("the late answer wrote a verdict", "linked" in phone.ai.getLinkStateJson().orEmpty())
    }

    @Test
    fun `6 - the Reset screen's check abandoned at the limit - a chat's key still gets its answer`() {
        phone.store()
        firstCallHangs("""{"signInKeyId":"$OWN_KEY_ID","paid":true}""")
        assertTrue("the check itself passed its limit", endsWithin(CAPPED_MS) { phone.access.resetScreenCheck { } })
        assertEquals("fixture: the check's call", listOf(KEY_STATE), phone.requests.toList())

        val key = AtomicReference<String?>()
        val ended = endsWithin(LATER_WORK_MS) { key.set(phone.access.keyForCharge().first) }

        assertTrue("the abandoned check still holds the lock: later AI work waits for its socket", ended)
        assertEquals(AiKeyScreensFixture.TOKEN, key.get())
    }

    @Test
    fun `6 - the Reset screen's check abandoned at the limit - the next tab open still gets its answer`() {
        phone.store()
        firstCallHangs("""{"signInKeyId":"$OWN_KEY_ID","paid":true}""")
        assertTrue(endsWithin(CAPPED_MS) { phone.access.resetScreenCheck { } })

        assertTrue("the abandoned check still holds the lock", endsWithin(LATER_WORK_MS) { phone.access.open() })
    }

    @Test
    fun `6 - Link now on a blocked call ends at the limit, false, and the late answer writes nothing`() {
        phone.store()
        firstCallHangs("""{"signInKeyId":"$OWN_KEY_ID","paid":true}""")
        val linked = AtomicReference<Boolean?>()

        val ended = endsWithin(CAPPED_MS) { linked.set(phone.access.linkNow()) }

        assertTrue("Link now has no limit: the button stays disabled", ended)
        assertEquals(false, linked.get())
        assertReleasedNothingWritten()
    }

    @Test
    fun `6 - Check now on a blocked call ends at the limit, and the late answer writes nothing`() {
        phone.store(verdict = "linked", paid = "yes")
        firstCallHangs("""{"balanceUsd":1.0,"signInKeyId":null,"paid":true}""")

        val ended = endsWithin(CAPPED_MS) { phone.access.checkNow() }

        assertEquals("fixture: the check's call", listOf(BALANCE), phone.requests.toList())
        assertTrue("Check now has no limit: the button stays disabled", ended)
        released.countDown()
        Thread.sleep(SETTLE_MS)
        assertTrue("the late answer changed the link", "\"verdict\":\"linked\"" in phone.ai.getLinkStateJson().orEmpty())
    }

    private companion object {
        const val MARGIN_MS = 2_500L
        const val CAPPED_MS = AI_CHECK_LIMIT_MS + MARGIN_MS
        const val LATER_WORK_MS = 3_000L
        const val SETTLE_MS = 500L
    }
}
