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
 * T5 fixup RH-X4 (fix-all 2.13.0, train T5; REVIEW_CROSS.md finding 8, medium; relay-health SPEC §3.2).
 *
 * At 0c9146395 `RelayHealthPolicy.onConnectOk` calls good(): every successful open clears the relay's SUSPECT time and
 * its REQ and publish windows, and `onDisconnected` forgets the reads that were waiting without counting them. A
 * relay that accepts the socket and drops it before it answers anything is therefore never DOWN (each open restarts
 * that wait) and never SUSPECT, and the pool reconnects it every few seconds for the rest of the process.
 *
 * Wanted (SPEC §3.2: SUSPECT ends with "the next EOSE / EVENT / OK true"): an open marks the relay connected, ends the
 * DOWN wait and keeps the network gate open, but is not an answer; only an EOSE in time, an EVENT or an OK true (or
 * duplicate:) is. A read still waiting when the socket drops counts as unanswered. Such a relay becomes SUSPECT and is
 * replaced after 30 minutes. Pure policy on a fake clock, then the real pool against local MockWebServer relays.
 */
class RelayDroppingBeforeAnyAnswerIsReplacedTest {
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
    fun `a relay that opens and drops before answering a read is SUSPECT and replaced after 30 minutes`() {
        val p = HealthPolicyDriver()
        p.up(OWN, N)
        var since: Long? = null
        var replaced = false
        var t = T0
        while (t <= T0 + 40 * MIN && !replaced) {
            p.at(t).fresh(OWN, N)
            p.connectOk(P)
            p.reqSent(P)
            p.disconnected(P)
            if (since == null && p.state(P) == "SUSPECT") since = t
            if (t >= T0 + MIN) {
                assertEquals(
                    "SUSPECT",
                    p.state(P),
                    "a relay whose every socket dropped before it answered a read is not SUSPECT at +${(t - T0) / MIN} min",
                )
            }
            val d = p.tick()
            val s = since
            if (s == null || t - s < 30 * MIN) {
                assertFalse(isSwapOrProbe(d), "swapped at +${(t - T0) / MIN} min, before 30 minutes of SUSPECT")
            } else {
                assertTrue(isReplace(d, 1, P, M), "a relay SUSPECT for 30 minutes was not replaced: $d")
                replaced = true
            }
            t += MIN
        }
        assertTrue(replaced, "the relay that never answered was never replaced")
    }

    @Test
    fun `a bare open does not end SUSPECT, an EOSE in time does`() {
        val p = HealthPolicyDriver()
        p.up(OWN, P, N)
        p.at(T0 + MIN).reqSent(P)
        p.at(T0 + 2 * MIN).fresh(OWN, N)
        p.tick()
        p.at(T0 + 3 * MIN).reqSent(P)
        p.at(T0 + 4 * MIN).fresh(OWN, N)
        p.tick()
        assertEquals("SUSPECT", p.state(P), "fixture: two REQs of the last three without an EOSE in 15 s")

        p.disconnected(P)
        p.connectOk(P)
        assertEquals("SUSPECT", p.state(P), "a reconnect with no answer ended SUSPECT")

        p.at(T0 + 5 * MIN).fresh(P)
        assertEquals("OK", p.state(P), "an EOSE in time ends SUSPECT")
    }

    @Test
    fun `control - a bare open still ends the DOWN wait and keeps the network gate open`() {
        val p = HealthPolicyDriver()
        p.up(OWN, N)
        p.connectFail(P)
        p.at(T0 + 9 * MIN).fresh(OWN, N)
        p.connectOk(P)
        p.at(T0 + 10 * MIN).fresh(OWN, N)
        assertEquals("OK", p.state(P), "a relay that opened a minute ago is not DOWN")
        assertFalse(isSwapOrProbe(p.tick()), "a relay that just opened was swapped")

        val q = HealthPolicyDriver()
        q.connectFail(P)
        for (minute in 1..11) {
            q.at(T0 + minute * MIN)
            q.connectOk(OWN)
            q.connectOk(N)
            q.connectFail(P)
        }
        val d = q.tick()
        assertTrue(isReplace(d, 1, P, M), "opens alone no longer keep the network gate open: $d")
    }

    @Test
    fun `the pool replaces a relay that drops every socket before it answers`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.DROPS_BEFORE_EOSE)
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
            val dropped =
                HealthRelay.awaitTrue {
                    a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() && b.inboxReqs().size >= 2
                }
            assertTrue(dropped, "fixture: A and C got our read, B dropped two sockets that carried it")
            delay(500)
            assertEquals(
                "SUSPECT",
                RelayHealthApi.healthState(p, urlB),
                "a relay that dropped both sockets before answering our read is not SUSPECT",
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
                "the relay that never answered for 30 minutes was not replaced",
            )
            assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "D got our subscription")
        }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private companion object {
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("c".repeat(64)))
    }
}
