package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-459 (fix-all 2.13.0, train T6; RELAY-GROUP-SPEC.md §5): `stop()` cancels the first health check.
 *
 * `NostrInboxManager.start()` schedules one extra health check shortly after start (a minute in the app), so a relay
 * that refused the first read is replaced quickly. At HEAD the job is not kept, so `stop()` cannot cancel it: an inbox
 * that is stopped and started again inside that minute gets the old check and the new start's one. Wanted: one first
 * check per running inbox, the last start's.
 *
 * A real NostrInboxManager over a pool with relay health and local MockWebServer relays. Slot 1 answers every upgrade
 * with 502 and counts as DOWN (the fake clock is moved one second on, the confirm time is 100 ms), and the three
 * catalog candidates accept a probe's REQ and never answer it. So every health check probes exactly one candidate: the
 * probe fails after 300 ms, that candidate rests for 6 hours and the next check takes the next one. The number of
 * probe REQs over the three candidates is the number of health checks that ran. The periodic watchdog tick (every two
 * minutes) never runs inside a test.
 */
class StopCancelsTheFirstHealthCheckTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var inbox: NostrInboxManager? = null
    private val clock = AtomicLong(1_790_000_000_000L)
    private val identity = NOSTRIdentity.fromSeed(Random.nextBytes(64))

    private lateinit var b: HealthRelay
    private lateinit var urlB: String
    private lateinit var pool: NostrRelayPool
    private val candidates = mutableListOf<HealthRelay>()

    @After
    fun tearDown() {
        runCatching { inbox?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /** A (slot 0) and C (slot 2) serve, B (slot 1) is down, D1..D3 are the catalog candidates that never answer. */
    private fun manager(): NostrInboxManager {
        val (_, urlA) = relay()
        val down = relay(HealthRelay.Mode.DOWN_502)
        b = down.first
        urlB = down.second
        val (_, urlC) = relay()
        val candidateUrls =
            List(CANDIDATES) {
                val (candidate, url) = relay(HealthRelay.Mode.SILENT)
                candidates += candidate
                url
            }
        val config = RelayHealthApi.config(listOf(urlB, urlC) + candidateUrls, clock)
        RelayHealthApi.setProp(config, "firstCheckDelayMs", FIRST_CHECK_MS)
        RelayHealthApi.setProp(config, "downAfterMs", DOWN_AFTER_MS)
        RelayHealthApi.setProp(config, "probeTimeoutMs", PROBE_TIMEOUT_MS)
        pool = RelayHealthApi.pool(listOf(urlA, urlB, urlC), scope, null, config, null)
        return NostrInboxManager(pool).also { inbox = it }
    }

    /** The health checks that ran: each one probes one candidate (a REQ with a limit). */
    private fun firstChecks(): Int = candidates.sumOf { it.probeReqs().size }

    /** After [START_TO_STOP_MS] B's first refused upgrade is on record; one second later it counts as DOWN. */
    private suspend fun letSlot1GoDown() {
        delay(START_TO_STOP_MS)
        clock.addAndGet(CLOCK_STEP_MS)
        assertEquals("DOWN", RelayHealthApi.healthState(pool, urlB), "fixture: slot 1 is DOWN after the clock step")
    }

    @Test
    fun `an inbox stopped and started again inside the delay runs one first health check`() =
        runBlocking {
            val manager = manager()
            manager.start(identity)
            letSlot1GoDown()
            manager.stop()
            delay(STOP_TO_START_MS)
            manager.start(identity)
            // Past the first start's delay and the second start's, each with its probe.
            delay(FIRST_CHECK_MS + PROBE_TIMEOUT_MS + SETTLE_MS)

            assertEquals(
                1,
                firstChecks(),
                "the first start's health check ran after stop() beside the second start's " +
                    "(probes per candidate: ${candidates.map { it.probeReqs().size }})",
            )
        }

    @Test
    fun `control - an inbox that is not stopped runs one first health check`() =
        runBlocking {
            val manager = manager()
            manager.start(identity)
            letSlot1GoDown()
            delay(FIRST_CHECK_MS + PROBE_TIMEOUT_MS + SETTLE_MS)

            assertEquals(1, firstChecks(), "probes per candidate: ${candidates.map { it.probeReqs().size }}")
            assertTrue(candidates.first().probeReqs().isNotEmpty(), "the check probes the first catalog candidate")
        }

    @Test
    fun `control - an inbox that is stopped and stays stopped runs no health check`() =
        runBlocking {
            val manager = manager()
            manager.start(identity)
            letSlot1GoDown()
            manager.stop()
            delay(FIRST_CHECK_MS + PROBE_TIMEOUT_MS + SETTLE_MS)

            assertEquals(0, firstChecks(), "probes per candidate: ${candidates.map { it.probeReqs().size }}")
        }

    private companion object {
        const val CANDIDATES = 3
        const val FIRST_CHECK_MS = 1_500L
        const val DOWN_AFTER_MS = 100L
        const val PROBE_TIMEOUT_MS = 300L
        const val CLOCK_STEP_MS = 1_000L
        const val START_TO_STOP_MS = 300L
        const val STOP_TO_START_MS = 300L
        const val SETTLE_MS = 1_200L
    }
}
