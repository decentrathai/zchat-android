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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T5 fixup RH-X1 (fix-all 2.13.0, train T5; REVIEW_CROSS.md finding 5, medium; relay-health SPEC §3.2 READ_BLOCKED,
 * R-RH-1).
 *
 * At 0c9146395 the first `auth-required:`, `restricted:` or `blocked:` CLOSED makes a relay READ_BLOCKED for the rest
 * of the process: the pool never asks it for our messages again on that socket, its OKs stop counting as copies, and
 * the outbox skips it. For a public relay that is fine, it is swapped. Slot 0, ZChat's own relay, is never swapped, so
 * one such CLOSED from it (a restart, a misconfiguration, a rate rule) leaves the phone without its main relay until
 * the app is restarted, and the outbox stops putting our wraps on it (R-RH-1).
 *
 * Wanted: slot 0 is asked again after a pause (RelayHealthConfig.ownReadRetryMs, 5 minutes by default; 300 ms here)
 * while it refuses, and the read block ends as soon as it answers a read (an EOSE in time or an EVENT): its state is OK,
 * its acks count again, and the outbox re-offers it the wraps it did not count while it refused. Local MockWebServer
 * relays; a fake clock for the health rules; synthetic events.
 */
class OwnRelayReadBlockIsNotForTheProcessTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    @Test
    fun `ZChat's relay that refused our reads once is read again once it serves them`() =
        runBlocking {
            val (own, urlOwn) = relay(HealthRelay.Mode.RESTRICTED)
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val config = RelayHealthApi.config(listOf(urlB, urlC), clock)
            // The pause before slot 0 is asked again (absent at 0c9146395, where slot 0 is never asked again).
            runCatching { RelayHealthApi.setProp(config, "ownReadRetryMs", 300L) }
            val p =
                RelayHealthApi
                    .pool(listOf(urlOwn, urlB, urlC), scope, null, config, RelayHealthApi.CountingSetStore().proxy)
                    .also { pool = it }
            p.start()
            p.subscribe(INBOX_FILTER) { }
            val subscribed =
                HealthRelay.awaitTrue {
                    own.inboxReqs().isNotEmpty() && b.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty()
                }
            assertTrue(subscribed, "fixture: the three relays got our read")
            delay(500)
            assertEquals("READ_BLOCKED", RelayHealthApi.healthState(p, urlOwn), "fixture: restricted: is a read block")
            val whileBlocked = HealthRelay.event("synthetic-rhx1-while-blocked", 1_790_000_000L)
            assertEquals(2, p.publish(whileBlocked), "fixture: a copy on a relay that refuses our reads is not counted")

            own.mode = HealthRelay.Mode.SERVES
            val readAgain = HealthRelay.awaitTrue(10_000) { RelayHealthApi.healthState(p, urlOwn) == "OK" }
            assertTrue(
                readAgain,
                "ZChat's relay serves our reads again, but the phone never asks it again: read-blocked for the process " +
                    "(state ${RelayHealthApi.healthState(p, urlOwn)}, ${own.inboxReqs().size} REQ(s))",
            )
            assertEquals(
                3,
                p.publish(HealthRelay.event("synthetic-rhx1-after", 1_790_000_001L)),
                "ZChat's relay's ack does not count again",
            )

            RelayHealthApi.tick(p)
            val reoffered = HealthRelay.awaitTrue { own.publishCount("synthetic-rhx1-while-blocked") >= 2 }
            assertTrue(reoffered, "the outbox never offered ZChat's relay the wrap it did not count while it refused")
        }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private companion object {
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("d".repeat(64)))
    }
}
