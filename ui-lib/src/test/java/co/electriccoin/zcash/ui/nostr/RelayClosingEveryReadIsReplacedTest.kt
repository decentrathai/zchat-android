package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.M
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.N
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.OWN
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.P
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.T0
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.isReplace
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.isSwapOrProbe
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T5 fixup RH-X3 (fix-all 2.13.0, train T5; REVIEW_CROSS.md finding 7, medium; relay-health SPEC §3.2 SUSPECT).
 *
 * At 0c9146395 `RelayHealthPolicy.onClosed` drops the REQ a CLOSED ends and, unless the reason is a read block
 * (`auth-required:`, `restricted:`, `blocked:`), records nothing. A public relay that closes every one of our reads
 * with "rate-limited: …" (or "error: …") while its socket stays up is therefore never DOWN, never SUSPECT and never
 * replaced: the phone reads two relays for the rest of the process.
 *
 * Wanted: a CLOSED that ends a read still waiting for its EOSE counts as that read unanswered in time, so two such
 * reads of the last three make the relay SUSPECT and it is replaced after 30 minutes (SPEC §3.3). One such CLOSED alone
 * is no verdict, and a CLOSED of a read the relay already answered (an idle subscription it ends) counts for nothing.
 * Pure policy on a fake clock, then the real pool against local MockWebServer relays; synthetic urls and events.
 */
class RelayClosingEveryReadIsReplacedTest {
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
    fun `a relay that closes every read with rate-limited is SUSPECT and replaced after 30 minutes`() {
        val p = HealthPolicyDriver()
        p.up(OWN, P, N)

        p.at(T0 + MIN).reqSent(P)
        assertFalse(p.closed(P, "rate-limited: slow down"), "rate-limited: is not a read block")
        p.fresh(OWN, N)
        assertEquals("OK", p.state(P), "one closed read alone is no verdict")
        assertFalse(isSwapOrProbe(p.tick()), "one closed read alone swapped the relay")

        p.at(T0 + 2 * MIN).reqSent(P)
        p.closed(P, "rate-limited: slow down")
        p.fresh(OWN, N)
        assertEquals(
            "SUSPECT",
            p.state(P),
            "two reads of the last three closed without an answer must make the relay SUSPECT (the CLOSED was not counted)",
        )
        val since = T0 + 2 * MIN

        var t = since
        while (t < since + 30 * MIN) {
            p.at(t).fresh(OWN, N)
            p.reqSent(P)
            p.closed(P, "rate-limited: slow down")
            assertFalse(isSwapOrProbe(p.tick()), "swapped after ${(t - since) / MIN} minutes of SUSPECT")
            t += 2 * MIN
        }
        p.at(since + 30 * MIN).fresh(OWN, N)
        val d = p.tick()
        assertTrue(isReplace(d, 1, P, M), "a relay that refused every read for 30 minutes was not replaced: $d")
    }

    @Test
    fun `control - a CLOSED of a read already answered, or one refused read between answered ones, is no verdict`() {
        val p = HealthPolicyDriver()
        p.up(OWN, P, N)
        for (minute in 1..5) {
            p.at(T0 + minute * MIN).fresh(OWN, N)
            p.fresh(P)
            p.closed(P, "error: shutting down idle subscription")
        }
        assertEquals("OK", p.state(P), "a relay that ends idle subscriptions after their EOSE")

        for (minute in 6..45) {
            p.at(T0 + minute * MIN).fresh(OWN, N)
            p.reqSent(P)
            p.closed(P, "rate-limited: slow down")
            p.fresh(P)
            assertEquals("OK", p.state(P), "one refused read between answered ones, at +$minute min")
            assertFalse(isSwapOrProbe(p.tick()), "a relay that answers every other read was swapped at +$minute min")
        }
    }

    @Test
    fun `the pool replaces a relay that closes every read with rate-limited`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val (d, urlD) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val p =
                RelayHealthApi
                    .pool(
                        listOf(urlA, urlB, urlC),
                        scope,
                        null,
                        RelayHealthApi.config(listOf(urlB, urlC, urlD), clock),
                        RelayHealthApi.CountingSetStore().proxy,
                    ).also { pool = it }
            p.start()
            p.subscribe(INBOX_FILTER) { }
            val refused =
                HealthRelay.awaitTrue {
                    a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() && b.inboxReqs().size >= 2
                }
            assertTrue(refused, "fixture: A and C got our read, B closed it and the pool asked B again")
            delay(500)
            assertEquals(
                "SUSPECT",
                RelayHealthApi.healthState(p, urlB),
                "a relay that closed both of our reads with rate-limited: is not SUSPECT",
            )

            clock.addAndGet(31 * MIN)
            val before = a.inboxReqs().size to c.inboxReqs().size
            p.redeliverBacklog() // the watchdog's fresh REQs: A and C answer at the new time
            val fresh = HealthRelay.awaitTrue { a.inboxReqs().size > before.first && c.inboxReqs().size > before.second }
            assertTrue(fresh, "fixture: A and C gave fresh EOSEs")
            delay(300)

            RelayHealthApi.tick(p)
            assertEquals(
                listOf(urlA, urlD, urlC),
                RelayHealthApi.activeUrls(p),
                "the relay that refused every read for 30 minutes was not replaced",
            )
            assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "D got our subscription")
        }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private companion object {
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("b".repeat(64)))
    }
}
