package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.M
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.N
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.OWN
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.P
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ZC-460 (fix-all 2.13.0, train T6a; RELAY-GROUP-SPEC §3): two pieces of code decide that a relay will not serve our
 * reads, with different tests.
 *
 * At 77f383475 RelayClient calls `onAuthRequired` for a CLOSED whose text contains "auth-required" anywhere, and the
 * pool then stops counting that relay's acknowledgements, leaves it out of the outbox and stops asking it again. The
 * relay-health rules are told through `onClosedReason` only, and `RelayHealthPolicy.isReadBlockReason` wants the NIP-01
 * prefix form with a colon ("auth-required: …"). For a CLOSED that says "auth-required" without the colon the two
 * disagree: the pool treats the relay as read-blocked, the rules say OK, so it stays in the set, and every publish it
 * acknowledges clears SUSPECT again. It is replaced only after 30 minutes without a successful publish to it.
 *
 * Wanted: one rule. Every reason that read-blocks the relay in the pool makes it READ_BLOCKED in the rules too, so a
 * public relay is replaced at the next tick. The prefix forms, the reasons that are no read block and slot 0's
 * RH-X1 retry stay as they are. Pure policy on a fake clock, then the real pool against local MockWebServer relays;
 * synthetic urls and events.
 */
class ReadBlockedByAuthRequiredIsOneRuleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    // ---- (a) the policy table ----

    @Test
    fun `a CLOSED that says auth-required without the prefix colon is a read block in the rules`() {
        for (reason in listOf("auth-required", "Auth-Required", "error: auth-required please sign in")) {
            val p = HealthPolicyDriver()
            p.up(OWN, P, N)
            p.reqSent(P)
            assertTrue(p.closed(P, reason), "the pool read-blocks a relay for CLOSED \"$reason\"; the rules do not")
            assertEquals("READ_BLOCKED", p.state(P), "CLOSED \"$reason\"")
            val d = p.tick()
            assertTrue(isReplace(d, 1, P, M), "a relay read-blocked by CLOSED \"$reason\" is not replaced: $d")
        }
    }

    @Test
    fun `control - the prefix forms are a read block`() {
        for (reason in listOf("auth-required: x", "ERROR: auth-required: x", "restricted: x", "blocked: x")) {
            val p = HealthPolicyDriver()
            p.up(OWN, P, N)
            p.reqSent(P)
            assertTrue(p.closed(P, reason), "CLOSED \"$reason\" is a read block")
            assertEquals("READ_BLOCKED", p.state(P), "CLOSED \"$reason\"")
            val d = p.tick()
            assertTrue(isReplace(d, 1, P, M), "CLOSED \"$reason\": $d")
        }
    }

    @Test
    fun `control - rate-limited, a bare restricted or blocked, another error and no reason are no read block`() {
        for (reason in listOf("rate-limited: x", "restricted", "blocked", "error: busy", "")) {
            val p = HealthPolicyDriver()
            p.up(OWN, P, N)
            p.reqSent(P)
            assertFalse(p.closed(P, reason), "CLOSED \"$reason\" is not a read block")
            assertEquals("OK", p.state(P), "CLOSED \"$reason\"")
            assertFalse(isSwapOrProbe(p.tick()), "CLOSED \"$reason\" swapped the relay")
        }
    }

    // ---- (b) the real pool ----

    @Test
    fun `the pool and the rules agree on a relay that closes every read with a bare auth-required, and it is replaced`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(closes = "auth-required")
            val (c, urlC) = relay()
            val (d, urlD) = relay()
            val clock = AtomicLong(T0)
            val p = pool(listOf(urlA, urlB, urlC), RelayHealthApi.config(listOf(urlB, urlC, urlD), clock))
            p.subscribe(INBOX_FILTER) { }
            val asked =
                HealthRelay.awaitTrue {
                    a.inboxReqs().isNotEmpty() && b.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty()
                }
            assertTrue(asked, "fixture: the three relays got our read")
            delay(500)

            // The existing pool rule (T1b-2), a control: the relay took the event, its acknowledgement is not a copy.
            val acks = p.publish(HealthRelay.event("synthetic-zc460-bare", 1_790_000_000L))
            assertEquals(1, b.publishCount("synthetic-zc460-bare"), "fixture: B took and acknowledged the event")
            assertEquals(2, acks, "an acknowledgement from a relay that refuses our reads was counted")
            assertEquals(
                "READ_BLOCKED",
                RelayHealthApi.healthState(p, urlB),
                "the pool does not count this relay's acknowledgements, but the relay-health rules do not know it " +
                    "refuses our reads",
            )

            clock.addAndGet(MIN)
            RelayHealthApi.tick(p)
            assertEquals(
                listOf(urlA, urlD, urlC),
                RelayHealthApi.activeUrls(p),
                "a relay that refuses every read with auth-required was not replaced at the next tick",
            )
            assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "D got our subscription")
        }

    // ---- (c) controls ----

    @Test
    fun `control - a relay that closes with the prefix form is read-blocked in the pool and in the rules`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.AUTH_GATED)
            val (c, urlC) = relay()
            val p = pool(listOf(urlA, urlB, urlC), RelayHealthApi.config(listOf(urlB, urlC), AtomicLong(T0)))
            p.subscribe(INBOX_FILTER) { }
            val asked =
                HealthRelay.awaitTrue {
                    a.inboxReqs().isNotEmpty() && b.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty()
                }
            assertTrue(asked, "fixture: the three relays got our read")
            delay(500)

            assertEquals("READ_BLOCKED", RelayHealthApi.healthState(p, urlB))
            assertEquals(2, p.publish(HealthRelay.event("synthetic-zc460-gated", 1_790_000_000L)))
            assertEquals(1, b.publishCount("synthetic-zc460-gated"), "fixture: B took and acknowledged the event")
        }

    @Test
    fun `control - a rate-limited relay is read-blocked in neither`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val p = pool(listOf(urlA, urlB, urlC), RelayHealthApi.config(listOf(urlB, urlC), AtomicLong(T0)))
            p.subscribe(INBOX_FILTER) { }
            val asked =
                HealthRelay.awaitTrue {
                    a.inboxReqs().isNotEmpty() && b.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty()
                }
            assertTrue(asked, "fixture: the three relays got our read")
            delay(500)

            assertNotEquals("READ_BLOCKED", RelayHealthApi.healthState(p, urlB), "rate-limited: is not a read block")
            assertEquals(3, p.publish(HealthRelay.event("synthetic-zc460-limited", 1_790_000_000L)), "its ack counts")
        }

    @Test
    fun `control - ZChat's relay that closed with a bare auth-required is asked again and read again once it serves`() =
        runBlocking {
            val (own, urlOwn) = relay(closes = "auth-required")
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            val config = RelayHealthApi.config(listOf(urlB, urlC), AtomicLong(T0))
            RelayHealthApi.setProp(config, "ownReadRetryMs", 300L)
            val p = pool(listOf(urlOwn, urlB, urlC), config)
            p.subscribe(INBOX_FILTER) { }
            val asked =
                HealthRelay.awaitTrue {
                    own.inboxReqs().isNotEmpty() && b.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty()
                }
            assertTrue(asked, "fixture: the three relays got our read")
            delay(200)
            assertEquals(
                2,
                p.publish(HealthRelay.event("synthetic-zc460-own-blocked", 1_790_000_000L)),
                "a copy on ZChat's relay while it refuses our reads is not counted",
            )
            assertTrue(
                HealthRelay.awaitTrue { own.inboxReqs().size >= 2 },
                "RH-X1: ZChat's relay was not asked again after the pause (${own.inboxReqs().size} REQ)",
            )

            own.customClosedReason = null
            val reqsWhileRefusing = own.inboxReqs().size
            assertTrue(
                HealthRelay.awaitTrue { own.inboxReqs().size > reqsWhileRefusing },
                "RH-X1: ZChat's relay was not asked again once more after the pause",
            )
            val readAgain = HealthRelay.awaitTrue { RelayHealthApi.healthState(p, urlOwn) == "OK" }
            assertTrue(readAgain, "ZChat's relay serves our reads again: ${RelayHealthApi.healthState(p, urlOwn)}")
            delay(200)
            assertEquals(
                3,
                p.publish(HealthRelay.event("synthetic-zc460-own-after", 1_790_000_001L)),
                "RH-X1: ZChat's relay's ack does not count again after it answered a read",
            )
            assertEquals(listOf(urlOwn, urlB, urlC), RelayHealthApi.activeUrls(p), "slot 0 is never swapped")
        }

    private fun pool(
        urls: List<String>,
        config: Any,
    ): NostrRelayPool =
        RelayHealthApi
            .pool(urls, scope, null, config, RelayHealthApi.CountingSetStore().proxy)
            .also {
                pool = it
                it.start()
            }

    private fun relay(
        mode: HealthRelay.Mode = HealthRelay.Mode.SERVES,
        closes: String? = null,
    ): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        relay.customClosedReason = closes
        return relay to relay.start()
    }

    private companion object {
        const val T0 = 1_790_000_000_000L
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("e".repeat(64)))
    }
}
