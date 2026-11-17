package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-482 (fix-all 2.13.0, train T5b; register ZC-482, medium): the ZC-406 timing cases (RelayClosingTwiceQuicklyIsAskedAgainTest)
 * measure the gap between two REQs at a local relay ([HealthRelay.inboxReqTimesMs]) and compare it with the pool's waits
 * (the 3 s backoff, the 2 s window). The pool waits with kotlinx delay, on the monotonic clock (System.nanoTime); at
 * e79fc3d46 the relay stamps each REQ with System.currentTimeMillis, the wall clock. On the T5b machine the wall clock
 * was stepped back 0.4-1.4 s every 31 s from 10:00 on 1 Oct (systemd-timesyncd correcting a WSL2 clock that ran about
 * 3-4 % fast: 806 steps back and none forward between 09:55 and 17:00), and each step inside a measured gap shortened it
 * by the step: 1726 and 1781 ms for the 3 s backoff, 671 ms for the 2 s window, 374 ms for a 1.5 s gap, each within
 * about 15 ms of a step the journal logged during that run.
 *
 * Wanted: the relay times each REQ on the monotonic clock the waits run on. No clock is stepped here; the case checks
 * which clock the stamps come from. A local MockWebServer relay; a synthetic filter.
 */
class HealthRelayTimesReqsOnTheWaitClockTest {
    private val relays = mutableListOf<HealthRelay>()

    @After
    fun tearDown() {
        relays.forEach { it.shutdown() }
    }

    @Test
    fun `the relay times each REQ on the monotonic clock the pool's waits run on`() =
        runBlocking {
            val relay = HealthRelay().also { relays += it }
            val client = RelayClient(relay.start())
            try {
                client.connect()
                val before = System.nanoTime() / NANOS_PER_MS
                client.subscribe(FILTER) { }
                assertTrue(HealthRelay.awaitTrue { relay.inboxReqTimesMs().isNotEmpty() }, "fixture: the REQ reached the relay")
                val after = System.nanoTime() / NANOS_PER_MS
                val at = relay.inboxReqTimesMs().single()
                assertTrue(
                    at in before..after,
                    "the REQ was timed at $at, not on the monotonic clock of the pool's waits ($before..$after): a wall-clock " +
                        "step inside a measured gap changes the gap by the step",
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun `control - the relay times every inbox REQ once`() =
        runBlocking {
            val relay = HealthRelay().also { relays += it }
            val client = RelayClient(relay.start())
            try {
                client.connect()
                client.subscribe(FILTER) { }
                assertTrue(HealthRelay.awaitTrue { relay.inboxReqs().size == 1 }, "fixture: the first REQ reached the relay")
                client.subscribe(FILTER) { }
                assertTrue(HealthRelay.awaitTrue { relay.inboxReqs().size == 2 }, "fixture: the second REQ reached the relay")
                assertEquals(2, relay.inboxReqTimesMs().size, "every inbox REQ is timed once")
            } finally {
                client.close()
            }
        }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        val FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("c".repeat(64)))
    }
}
