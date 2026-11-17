package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.DAY
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.HOUR
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R2 (fix-all 2.13.0, train T5; W-11, W-15; SPEC.md §3.2, §3.3): the swap rules, pure, on a fake clock.
 *
 * The pool feeds the policy what it already sees (connects, REQ → EOSE, EVENTs, CLOSED reasons, the OK of real
 * publishes); on each watchdog tick the policy says: offline (no swap), hold, replace slot N after a probe of a
 * candidate, or probe nos.lol for the compat slot's return. Slot 0 (ZChat's relay) is never swapped. Synthetic urls.
 */
class RelayHealthPolicyTest {
    private companion object {
        const val OWN = "wss://own.test"
        const val P = "wss://primal.test"
        const val N = "wss://noslol.test"
        const val M = "wss://mom.test"
        const val S = "wss://social.test"
        const val X = "wss://oxtr.test"
        val DEFAULTS = listOf(OWN, P, N)
        val CATALOG = listOf(P, N, M, S, X)
        val LONG_CATALOG = CATALOG + (1..8).map { "wss://extra$it.test" }
        const val T0 = 1_790_000_000_000L

        fun kind(decision: Any): String = decision.javaClass.simpleName
    }

    /** The policy by name, with a fake clock the test moves. */
    private class Policy(
        catalog: List<String> = CATALOG,
    ) {
        val clock = AtomicLong(T0)
        val raw: Any = RelayHealthApi.policy(DEFAULTS, RelayHealthApi.config(catalog, clock))

        val now: Long get() = clock.get()

        fun at(t: Long): Policy {
            clock.set(t)
            return this
        }

        private fun signal(
            name: String,
            url: String,
        ) {
            RelayHealthApi.call(raw, name, url, now)
        }

        fun connectOk(url: String) = signal("onConnectOk", url)

        fun connectFail(url: String) = signal("onConnectFail", url)

        fun disconnected(url: String) = signal("onDisconnected", url)

        fun reqSent(url: String) = signal("onReqSent", url)

        fun eose(url: String) = signal("onEose", url)

        fun event(url: String) = signal("onEvent", url)

        fun closed(
            url: String,
            reason: String,
        ): Boolean = RelayHealthApi.call(raw, "onClosed", url, reason, now) as Boolean

        fun ok(
            url: String,
            ok: Boolean,
            reason: String?,
        ) {
            RelayHealthApi.call(raw, "onOk", url, ok, reason, now)
        }

        /** A relay that opened and answered a REQ with an EOSE just now. */
        fun up(vararg urls: String) =
            urls.forEach {
                connectOk(it)
                reqSent(it)
                eose(it)
            }

        /** A fresh REQ → EOSE on each of [urls] (the watchdog's re-subscribe on a live socket). */
        fun fresh(vararg urls: String) =
            urls.forEach {
                reqSent(it)
                eose(it)
            }

        fun state(url: String): String = RelayHealthApi.call(raw, "state", url, now).toString()

        fun tick(): Any = checkNotNull(RelayHealthApi.call(raw, "tick", now))

        fun probe(
            url: String,
            ok: Boolean,
        ): Boolean = RelayHealthApi.call(raw, "onProbeResult", url, ok, now) as Boolean

        fun commit(
            slot: Int,
            url: String,
        ): Any = checkNotNull(RelayHealthApi.call(raw, "commitSwap", slot, url, now))

        @Suppress("UNCHECKED_CAST")
        fun active(): List<String> = RelayHealthApi.call(raw, "activeUrls") as List<String>

        @Suppress("UNCHECKED_CAST")
        fun floorSet(): List<String> = RelayHealthApi.call(raw, "floorSetUrls", now) as List<String>

        /** Tick, and when the decision is a replacement whose probe succeeds, swap: what the pool does. */
        fun tickAndSwap(probeOk: Boolean = true): Any {
            val d = tick()
            if (kind(d) == "Replace") {
                val candidate = RelayHealthApi.prop(d, "candidate") as String
                if (probe(candidate, probeOk)) commit(RelayHealthApi.prop(d, "slot") as Int, candidate)
            }
            return d
        }
    }

    private fun assertReplace(
        decision: Any,
        slot: Int,
        old: String,
        candidate: String,
    ) {
        assertEquals("Replace", kind(decision), "expected a replacement, got $decision")
        assertEquals(slot, RelayHealthApi.prop(decision, "slot"))
        assertEquals(old, RelayHealthApi.prop(decision, "old"))
        assertEquals(candidate, RelayHealthApi.prop(decision, "candidate"))
    }

    private fun assertNoReplace(
        decision: Any,
        why: String,
    ) = assertTrue(kind(decision) != "Replace" && kind(decision) != "ProbeCompat", "$why: $decision")

    /** The policy with the defaults up at T0 and primal failing to connect from T0. */
    private fun primalDownFromT0(catalog: List<String> = CATALOG): Policy =
        Policy(catalog).apply {
            up(OWN, N)
            connectFail(P)
        }

    @Test
    fun `H1 a slot relay down for 10 minutes while the others work is replaced after a probe`() {
        val p = primalDownFromT0()
        p.at(T0 + 9 * MIN).fresh(OWN, N)
        assertNoReplace(p.tick(), "9 minutes without an open is not DOWN yet")
        p.connectFail(P)

        p.at(T0 + 10 * MIN).fresh(OWN, N)
        assertEquals("DOWN", p.state(P))
        val d = p.tick()
        assertReplace(d, 1, P, M)
        assertTrue(p.probe(M, true), "a candidate whose probe succeeded is taken")
        val stored = p.commit(1, M)
        assertEquals(listOf(OWN, M, N), p.active())
        assertEquals(M, RelayHealthApi.prop(stored, "slot1"))
        assertEquals(N, RelayHealthApi.prop(stored, "slot2"))
    }

    @Test
    fun `H2 everything failing for an hour is the phone offline - no swap, and the DOWN timers restart`() {
        val p = Policy()
        p.up(OWN, P, N)
        listOf(OWN, P, N).forEach { p.disconnected(it) }
        var t = T0
        while (t <= T0 + HOUR) {
            p.at(t)
            listOf(OWN, P, N).forEach { p.connectFail(it) }
            val d = p.tick()
            assertNoReplace(d, "no swap while every relay fails, at +${(t - T0) / MIN} min")
            // Five minutes after the last good answer from any relay, the phone counts as offline.
            if (t > T0 + 5 * MIN) assertEquals("Offline", kind(d), "at +${(t - T0) / MIN} min")
            t += 2 * MIN
        }
        assertEquals(DEFAULTS, p.active())

        // The network is back; primal alone still fails. Its DOWN timer started at the last offline tick.
        p.at(t).up(OWN, N)
        p.connectFail(P)
        assertNoReplace(p.tick(), "the DOWN timer restarted while offline")
        p.at(t + 10 * MIN).fresh(OWN, N)
        assertReplace(p.tick(), 1, P, M)
    }

    @Test
    fun `H2b EVENTs or OK true alone keep the network gate open`() {
        val p = Policy()
        p.connectOk(OWN)
        p.connectOk(N)
        p.connectFail(P)
        for (minute in 1..11) {
            p.at(T0 + minute * MIN)
            p.event(OWN)
            p.ok(N, true, "")
        }
        assertReplace(p.tick(), 1, P, M)
    }

    @Test
    fun `H3 ZChat's relay in slot 0 is never swapped`() {
        val p = Policy()
        p.up(P, N)
        p.connectFail(OWN)
        var t = T0
        while (t <= T0 + 3 * HOUR) {
            p.at(t).fresh(P, N)
            p.connectFail(OWN)
            assertNoReplace(p.tick(), "slot 0 at +${(t - T0) / MIN} min")
            t += 2 * MIN
        }
        assertEquals("DOWN", p.state(OWN))
        assertEquals(DEFAULTS, p.active())
    }

    @Test
    fun `H4 the first CLOSED with auth-required, restricted or blocked makes the relay READ_BLOCKED and it is replaced`() {
        for ((slot, reason) in listOf(
            1 to "ERROR: auth-required: requested filter requires authentication",
            1 to "restricted: this relay does not serve kind 1059",
            2 to "blocked: not on the allow list",
        )) {
            val p = Policy()
            p.up(OWN, P, N)
            val url = DEFAULTS[slot]
            assertTrue(p.closed(url, reason), "the first CLOSED \"$reason\" is a read block")
            assertEquals("READ_BLOCKED", p.state(url))
            assertReplace(p.tick(), slot, url, M)
        }
    }

    @Test
    fun `H4b a CLOSED for another reason is not a read block`() {
        val p = Policy()
        p.up(OWN, P, N)
        assertFalse(p.closed(P, "rate-limited: slow down"))
        assertFalse(p.closed(P, "error: shutting down idle subscription"))
        assertEquals("OK", p.state(P))
        assertNoReplace(p.tick(), "a transient CLOSED")
    }

    @Test
    fun `H5 three blocked OKs within 30 minutes make WRITE_REFUSED and the relay is replaced, duplicate and rate-limited never`() {
        val p = Policy()
        p.up(OWN, P, N)
        repeat(10) {
            p.ok(P, false, "duplicate: already have this event")
            p.ok(P, false, "rate-limited: slow down")
        }
        assertEquals("OK", p.state(P))
        assertNoReplace(p.tick(), "duplicate and rate-limited are not refusals")

        p.at(T0 + MIN).ok(P, false, "blocked: synthetic")
        p.at(T0 + 2 * MIN).ok(P, false, "blocked: synthetic")
        assertEquals("OK", p.state(P), "two refusals are not three")
        p.at(T0 + 3 * MIN).ok(P, false, "blocked: synthetic")
        p.fresh(OWN, N)
        assertEquals("WRITE_REFUSED", p.state(P))
        assertReplace(p.tick(), 1, P, M)

        // A later OK true ends it.
        p.ok(P, true, "")
        assertEquals("OK", p.state(P))
    }

    @Test
    fun `H6 at most one swap per 10 minutes, 2 minutes while fewer than two relays work, 6 per 24 hours`() {
        val p = Policy(LONG_CATALOG)
        p.up(OWN, P, N)
        p.closed(P, "blocked: synthetic")
        assertReplace(p.tickAndSwap(), 1, P, M)

        // A minute later ZChat's relay drops (DOWN only after 10 minutes) and slot 2 refuses reads: two relays still
        // work (ZChat's relay is not DOWN yet, and M), so the next swap waits 10 minutes after the first.
        p.at(T0 + MIN).fresh(M)
        p.disconnected(OWN)
        p.connectFail(OWN)
        p.closed(N, "blocked: synthetic")
        p.at(T0 + 2 * MIN).fresh(M)
        assertNoReplace(p.tick(), "a second swap 2 minutes after the first while two relays work")
        p.at(T0 + 10 * MIN).fresh(M)
        assertReplace(p.tickAndSwap(), 2, N, S)

        // Now fewer than two work (ZChat's relay DOWN, slot 1 read-blocked): 2 minutes apart.
        p.at(T0 + 11 * MIN).fresh(S)
        p.closed(M, "blocked: synthetic")
        assertEquals("DOWN", p.state(OWN))
        assertNoReplace(p.tick(), "one minute after the last swap")
        p.at(T0 + 12 * MIN).fresh(S)
        assertReplace(p.tickAndSwap(), 1, M, X)

        // Swaps 4, 5, 6 (each relay read-blocked after it came in), then no 7th within 24 h of the first.
        var t = T0 + 12 * MIN
        var slot = 1
        repeat(3) {
            val victim = p.active()[slot]
            t += 11 * MIN
            p.at(t).fresh(*p.active().drop(1).toTypedArray())
            p.closed(victim, "blocked: synthetic")
            assertEquals("Replace", kind(p.tickAndSwap()), "swap ${it + 4} at +${(t - T0) / MIN} min")
            slot = 3 - slot
        }
        val victim = p.active()[slot]
        t += 11 * MIN
        p.at(t).fresh(*p.active().drop(1).toTypedArray())
        p.closed(victim, "blocked: synthetic")
        assertNoReplace(p.tick(), "a 7th swap within 24 hours")
        p.at(T0 + DAY + MIN).fresh(*p.active().drop(1).toTypedArray())
        assertEquals("Replace", kind(p.tick()), "the 24-hour window freed")
    }

    @Test
    fun `H7 a failed probe gives the candidate 6 hours, a swapped-out relay 24 hours, and the next one is tried`() {
        val p = Policy()
        p.up(OWN, P, N)
        p.closed(P, "blocked: synthetic")
        val first = p.tick()
        assertReplace(first, 1, P, M)
        assertFalse(p.probe(M, false), "a failed probe is not a swap")
        assertEquals(DEFAULTS, p.active())

        p.at(T0 + 11 * MIN).fresh(OWN, N)
        assertReplace(p.tick(), 1, P, S)
        assertTrue(p.probe(S, true))
        p.commit(1, S)

        // S is read-blocked later: P (swapped out) waits 24 h, M (failed probe) 6 h.
        p.at(T0 + 30 * MIN).fresh(OWN, N)
        p.closed(S, "blocked: synthetic")
        assertReplace(p.tick(), 1, S, X)
        p.probe(X, false)
        p.at(T0 + 5 * HOUR).fresh(OWN, N)
        assertNoReplace(p.tick(), "M within 6 h of its failed probe, P and X in cooldown")
        p.at(T0 + 6 * HOUR + MIN).fresh(OWN, N)
        assertReplace(p.tick(), 1, S, M)
        p.probe(M, false)
        // P was swapped out at +11 min: free again 24 h later, and first in the catalog.
        p.at(T0 + DAY + 12 * MIN).fresh(OWN, N)
        assertReplace(p.tick(), 1, S, P)
    }

    @Test
    fun `H8 a relay up 3 minutes and down 3 minutes never reaches DOWN`() {
        val p = Policy()
        p.up(OWN, P, N)
        var t = T0
        var up = true
        while (t <= T0 + 2 * HOUR) {
            p.at(t).fresh(OWN, N)
            if (up) p.up(P) else p.disconnected(P)
            if (!up) p.connectFail(P)
            assertTrue(p.state(P) != "DOWN", "DOWN at +${(t - T0) / MIN} min")
            assertNoReplace(p.tick(), "flapping at +${(t - T0) / MIN} min")
            t += 3 * MIN
            up = !up
        }
    }

    @Test
    fun `H9 a working swapped-in relay stays - nothing is swapped for a better score`() {
        val p = primalDownFromT0()
        p.at(T0 + 10 * MIN).fresh(OWN, N)
        p.tickAndSwap()
        assertEquals(listOf(OWN, M, N), p.active())
        p.up(M)
        for (hours in listOf(1L, 23L, 25L, 72L)) {
            p.at(T0 + hours * HOUR).fresh(OWN, M, N)
            assertNoReplace(p.tick(), "a working relay at +$hours h")
        }
        assertEquals(listOf(OWN, M, N), p.active())
    }

    @Test
    fun `H10 no healthy candidate - the set is kept`() {
        val p = primalDownFromT0(listOf(P, N))
        p.at(T0 + 20 * MIN).fresh(OWN, N)
        val d = p.tick()
        assertEquals("Hold", kind(d), "$d")
        assertEquals(DEFAULTS, p.active())
    }

    @Test
    fun `H11 SUSPECT for 30 minutes is unavailable, 29 minutes then an EOSE is not`() {
        fun suspectAt(p: Policy): Long {
            p.up(OWN, P, N)
            p.at(T0 + MIN).reqSent(P)
            p.at(T0 + 2 * MIN).fresh(OWN, N)
            p.tick()
            p.at(T0 + 3 * MIN).reqSent(P)
            p.at(T0 + 4 * MIN).fresh(OWN, N)
            p.tick()
            assertEquals("SUSPECT", p.state(P), "two REQs of the last three without an EOSE in 15 s")
            return T0 + 4 * MIN
        }

        val p = Policy()
        val since = suspectAt(p)
        p.at(since + 29 * MIN).fresh(OWN, N)
        assertNoReplace(p.tick(), "SUSPECT for 29 minutes")
        p.at(since + 30 * MIN).fresh(OWN, N)
        assertReplace(p.tick(), 1, P, M)

        val q = Policy()
        val since2 = suspectAt(q)
        q.at(since2 + 29 * MIN).fresh(OWN, N)
        q.fresh(P)
        assertEquals("OK", q.state(P), "an EOSE ends SUSPECT")
        q.at(since2 + 31 * MIN).fresh(OWN, N)
        assertNoReplace(q.tick(), "SUSPECT ended by an EOSE")
    }

    @Test
    fun `H12 the compat slot - nos lol out, a fallback in, probes every 30 minutes, back after two OK probes`() {
        val p = Policy()
        p.up(OWN, P)
        p.connectFail(N)
        p.at(T0 + 10 * MIN).fresh(OWN, P)
        assertReplace(p.tickAndSwap(), 2, N, M)
        val out = T0 + 10 * MIN
        assertEquals(listOf(OWN, P, M), p.active())
        assertTrue(N in p.floorSet(), "nos.lol stays in the floor set while it is out")

        p.at(out + 10 * MIN).fresh(OWN, P, M)
        assertNoReplace(p.tick(), "nos.lol is probed 30 minutes after it left, not sooner")
        p.at(out + 30 * MIN).fresh(OWN, P, M)
        assertEquals("ProbeCompat", kind(p.tick()))
        assertFalse(p.probe(N, true), "one OK probe is not two in a row")
        p.at(out + 45 * MIN).fresh(OWN, P, M)
        assertNoReplace(p.tick(), "the next probe is 30 minutes later")
        p.at(out + 60 * MIN).fresh(OWN, P, M)
        assertEquals("ProbeCompat", kind(p.tick()))
        assertFalse(p.probe(N, false), "a failed probe restarts the count")
        p.at(out + 90 * MIN).fresh(OWN, P, M)
        assertEquals("ProbeCompat", kind(p.tick()))
        assertFalse(p.probe(N, true))
        p.at(out + 120 * MIN).fresh(OWN, P, M)
        assertEquals("ProbeCompat", kind(p.tick()))
        assertTrue(p.probe(N, true), "two OK probes in a row: nos.lol takes slot 2 back")
        p.commit(2, N)
        assertEquals(DEFAULTS, p.active())
        assertEquals(DEFAULTS, p.floorSet())

        // The fallback went to cooldown: if slot 2 fails again it is not the first choice.
        p.at(out + 125 * MIN).fresh(OWN, P)
        p.closed(N, "blocked: synthetic")
        p.at(out + 135 * MIN).fresh(OWN, P)
        assertReplace(p.tick(), 2, N, S)
    }

    @Test
    fun `H12b a READ_BLOCKED nos lol is probed again only after 24 hours`() {
        val p = Policy()
        p.up(OWN, P, N)
        p.closed(N, "ERROR: auth-required: requested filter requires authentication")
        assertReplace(p.tickAndSwap(), 2, N, M)
        for (minutes in listOf(30L, 60L, 12 * 60L, 23 * 60L + 59)) {
            p.at(T0 + minutes * MIN).fresh(OWN, P, M)
            assertNoReplace(p.tick(), "a read-blocked nos.lol probed at +$minutes min")
        }
        p.at(T0 + DAY).fresh(OWN, P, M)
        assertEquals("ProbeCompat", kind(p.tick()))
    }

    @Test
    fun `H12c after 30 days out nos lol is no longer probed and leaves the floor set`() {
        val p = Policy()
        p.up(OWN, P)
        p.connectFail(N)
        p.at(T0 + 10 * MIN).fresh(OWN, P)
        p.tickAndSwap()
        val out = T0 + 10 * MIN
        p.at(out + 29 * DAY).fresh(OWN, P, M)
        assertTrue(N in p.floorSet(), "29 days out")
        assertEquals("ProbeCompat", kind(p.tick()))
        p.probe(N, false)
        p.at(out + 30 * DAY).fresh(OWN, P, M)
        assertFalse(N in p.floorSet(), "30 days out: nos.lol no longer holds the seen list's floor down")
        assertNoReplace(p.tick(), "30 days out: no more probes")
        assertEquals(listOf(OWN, P, M), p.floorSet())
    }
}
