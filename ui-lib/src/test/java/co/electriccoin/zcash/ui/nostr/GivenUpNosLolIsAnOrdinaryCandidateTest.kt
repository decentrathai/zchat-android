package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.M
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.N
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.OWN
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.P
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.S
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.T0
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.isReplace
import co.electriccoin.zcash.ui.nostr.HealthPolicyDriver.Companion.kind
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.DAY
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.HOUR
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-458 (fix-all 2.13.0, train T6a; relay group spec §2): after 30 days out nos.lol is an ordinary replacement
 * candidate, so its probe follows the rule for every replacement.
 *
 * `RelayHealthPolicy.candidateFor` offers nos.lol as a replacement once it is given up (30 days out of the compat
 * slot), and it is second in the catalog, so it is usually the first relay tried for an unavailable slot. But
 * `onProbeResult` sends every probe of nos.lol through the compat rule while nos.lol is not active: a failed probe
 * returns before the 6 h cooldown is recorded, so the same dead relay is picked again at every watchdog tick and the
 * catalog's other relays are never tried (the slot stays dead); an OK probe is taken only at the second in a row.
 *
 * Wanted: the compat rule (two OK probes in a row, no cooldown) applies only while nos.lol is not given up. After the
 * give-up a failed probe gives nos.lol 6 h, during which the next catalog relay is tried, and one OK probe takes it.
 * Before the give-up nothing changes. Pure policy on a fake clock; synthetic urls.
 */
class GivenUpNosLolIsAnOrdinaryCandidateTest {
    private var out = 0L

    /** nos.lol is DOWN, slot 2 goes to M (the first catalog relay that is not active and not nos.lol); [out] = when. */
    private fun nosLolOut(): HealthPolicyDriver {
        val p = HealthPolicyDriver()
        p.up(OWN, P)
        p.connectFail(N)
        out = T0 + 10 * MIN
        p.at(out).fresh(OWN, P)
        assertTrue(isReplace(p.tick(), 2, N, M), "fixture: nos.lol is DOWN, the fallback is M")
        assertTrue(p.probe(M, true), "fixture: M answers its probe")
        p.commit(2, M)
        assertEquals(listOf(OWN, P, M), p.active(), "fixture: the fallback holds slot 2")
        return p
    }

    /** At [t] primal stops connecting; 10 minutes later it is DOWN, ZChat's relay and M still answer. Returns that time. */
    private fun primalDown(
        p: HealthPolicyDriver,
        t: Long,
    ): Long {
        p.at(t).fresh(OWN, M)
        p.connectFail(P)
        p.at(t + 10 * MIN).fresh(OWN, M)
        assertEquals("DOWN", p.state(P), "fixture: primal is DOWN")
        return t + 10 * MIN
    }

    @Suppress("UNCHECKED_CAST")
    private fun floorSet(p: HealthPolicyDriver): List<String> {
        val field = HealthPolicyDriver::class.java.getDeclaredField("raw")
        field.isAccessible = true
        val raw = field.get(p)
        return RelayHealthApi.call(checkNotNull(raw), "floorSetUrls", p.now) as List<String>
    }

    private fun describe(decision: Any): String =
        if (kind(decision) == "Replace") {
            "Replace(${RelayHealthApi.prop(decision, "slot")}, ${RelayHealthApi.prop(decision, "old")}, " +
                "${RelayHealthApi.prop(decision, "candidate")})"
        } else {
            decision.toString()
        }

    private fun assertReplace(
        decision: Any,
        candidate: String,
        message: String,
    ) {
        assertTrue(isReplace(decision, 1, P, candidate), "$message: expected Replace(1, $P, $candidate), was ${describe(decision)}")
    }

    @Test
    fun `a failed probe of the given-up nos lol gives it six hours, and the next catalog relay is tried`() {
        val p = nosLolOut()
        val probed = primalDown(p, out + 30 * DAY)
        assertReplace(p.tick(), N, "given up: nos.lol is the first candidate for primal's slot")
        assertFalse(p.probe(N, false), "a failed probe is never taken")

        p.at(probed + 2 * MIN).fresh(OWN, M)
        assertReplace(p.tick(), S, "the next watchdog tick tries the next catalog relay, not the dead nos.lol again")

        p.at(probed + 6 * HOUR - MIN).fresh(OWN, M)
        assertReplace(p.tick(), S, "5 h 59 min after the failed probe nos.lol is still cooling down")

        p.at(probed + 6 * HOUR).fresh(OWN, M)
        assertReplace(p.tick(), N, "6 h after the failed probe nos.lol is a candidate again")
    }

    @Test
    fun `one OK probe takes the given-up nos lol`() {
        val p = nosLolOut()
        primalDown(p, out + 30 * DAY)
        assertReplace(p.tick(), N, "given up: nos.lol is the first candidate for primal's slot")
        assertTrue(p.probe(N, true), "a replacement candidate is taken at its first OK probe, not the second in a row")

        p.commit(1, N)
        assertEquals(listOf(OWN, N, M), p.active())
        assertEquals(p.active(), floorSet(p), "nos.lol is active again: the floor set is the active set")
    }

    @Test
    fun `control - 29 days out nos lol is not a replacement candidate and only its compat probe asks it`() {
        val p = nosLolOut()
        p.at(out + 29 * DAY).fresh(OWN, P, M)
        assertEquals("ProbeCompat", kind(p.tick()), "29 days out: nos.lol is still probed for the compat slot")
        assertFalse(p.probe(N, true), "not given up: the compat rule wants two OK probes in a row")
        assertTrue(N in floorSet(p), "29 days out: nos.lol still holds the seen list's floor")

        primalDown(p, out + 29 * DAY)
        assertReplace(p.tick(), S, "29 days out: primal's slot goes to the next catalog relay, never to nos.lol")
    }
}
