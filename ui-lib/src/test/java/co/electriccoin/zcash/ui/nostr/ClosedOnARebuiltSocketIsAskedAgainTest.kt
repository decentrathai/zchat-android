package co.electriccoin.zcash.ui.nostr

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-501 (fix-all 2.13.0, train T6; N2 of the ZC-482 analysis; the defect came with ZC-406): a deferred re-subscribe
 * holds the relay's "a wait is pending" flag (RelayState.resubPending) for its whole wait, but the wait is planned for
 * one socket. When the socket is rebuilt meanwhile (a drop, the foreground kick) and the relay CLOSEs the new socket's
 * replayed read, planResubscribe finds the flag set and the CLOSED is dropped; the old wait then wakes, sees another
 * socket and returns without a REQ. Nothing asks that relay for the read again: it stays connected and acknowledges
 * publishes, but carries none of our reads until its socket is rebuilt once more.
 *
 * Wanted: the wait that is already running asks again, when it is due, on the socket in use then, for what that
 * socket does not hold; a rebuilt socket's replay is not doubled, and the socket the wait was planned for is never
 * asked.
 *
 * Virtual time, no socket ([ResubscribeVirtualTimeFixture]); slot 1, window 2 s, backoff 500 ms. The timeline of every
 * case: a CLOSED on socket A at 0 (asked again at 500), a second at 1 000 (one wait, due at 2 000, the window doubles
 * to 4 s).
 */
class ClosedOnARebuiltSocketIsAskedAgainTest {
    private val fixture = ResubscribeVirtualTimeFixture()

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a CLOSED on the rebuilt socket while the old socket's deferral waits is asked again when that deferral is due`() {
        val a = oneDeferralWaiting()
        fixture.advanceTo(1_200)
        val b = fixture.rebuild()
        assertEquals(1, fixture.held(b), "fixture: the rebuilt socket holds the replayed read")
        fixture.advanceTo(1_500)
        fixture.closeRead(b)

        fixture.advanceTo(1_999)
        assertEquals(0, fixture.held(b), "the read closed on the rebuilt socket was asked again before the wait was due")
        fixture.advanceTo(2_000)
        assertEquals(
            1,
            fixture.held(b),
            "the relay closed the rebuilt socket's read while the old socket's deferred re-subscribe waited, and nothing " +
                "asked for it again when that wait was due: the relay carries no read of ours until its next rebuild",
        )
        assertEquals(0, fixture.held(a), "the socket the wait was planned for (replaced since) was asked")
    }

    @Test
    fun `after two rebuilds the read is asked again on the socket in use, never on an older one`() {
        val a = oneDeferralWaiting()
        fixture.advanceTo(1_200)
        val b = fixture.rebuild()
        fixture.advanceTo(1_400)
        val c = fixture.rebuild()
        fixture.advanceTo(1_600)
        fixture.closeRead(c)
        assertEquals(0, fixture.held(c), "fixture: the CLOSED took the read off the socket in use")

        fixture.advanceTo(2_000)
        assertEquals(
            1,
            fixture.held(c),
            "the read closed on the socket in use (the second rebuild) was not asked again when the wait was due",
        )
        assertEquals(1, fixture.held(b), "the first rebuilt socket (replaced since) holds more or less than its own replay")
        assertEquals(0, fixture.held(a), "the socket the wait was planned for (replaced since) was asked")
    }

    @Test
    fun `control - a CLOSED inside the window on the same socket is asked again at its end`() {
        val a = oneDeferralWaiting()

        fixture.advanceTo(1_999)
        assertEquals(0, fixture.held(a), "the deferred re-subscribe came before the window's end")
        fixture.advanceTo(2_000)
        assertEquals(1, fixture.held(a), "the read closed inside the window was not asked again, once, at its end")
    }

    @Test
    fun `control - a rebuilt socket whose replay is not closed is not asked twice`() {
        oneDeferralWaiting()
        fixture.advanceTo(1_200)
        val b = fixture.rebuild()

        fixture.advanceTo(2_000)
        assertEquals(1, fixture.held(b), "the rebuilt socket's replayed read was doubled (or lost) when the wait was due")
        fixture.advanceTo(10_000)
        assertEquals(1, fixture.held(b), "the rebuilt socket's replayed read was doubled (or lost) later")
    }

    @Test
    fun `control - a rebuilt socket that is not connected when the wait is due is asked nothing`() {
        oneDeferralWaiting()
        fixture.advanceTo(1_200)
        val b = fixture.rebuild(connected = false, replay = false)

        fixture.advanceTo(2_000)
        assertEquals(0, fixture.held(b), "a socket that is not connected was asked (its connect replays every read)")
    }

    @Test
    fun `control - a read-blocked relay at the due time is asked nothing`() {
        oneDeferralWaiting()
        fixture.advanceTo(1_200)
        val b = fixture.rebuild()
        fixture.advanceTo(1_500)
        fixture.closeRead(b, reason = "auth-required: x")
        assertTrue(fixture.readBlocked(), "fixture: the CLOSED auth-required made the relay read-blocked")

        fixture.advanceTo(2_000)
        assertEquals(0, fixture.held(b), "a read-blocked relay was asked again when the wait was due")
        fixture.advanceTo(10_000)
        assertEquals(0, fixture.held(b), "a read-blocked relay (not slot 0) was asked again later")
    }

    /**
     * The timeline up to 1 000: socket A's read is CLOSED at 0 and asked again at 500 (the backoff), CLOSED again at
     * 1 000, inside the 2 s window: one deferred re-subscribe waits, due at 2 000. @return socket A.
     */
    private fun oneDeferralWaiting(): RelayClient {
        val a = fixture.client
        fixture.closeRead(a)
        fixture.advanceTo(499)
        assertEquals(0, fixture.held(a), "fixture: the first CLOSED was asked again before the backoff")
        fixture.advanceTo(500)
        assertEquals(1, fixture.held(a), "fixture: the first CLOSED was asked again after the backoff")
        fixture.advanceTo(1_000)
        fixture.closeRead(a)
        assertEquals(0, fixture.held(a), "fixture: the second CLOSED took the read off socket A")
        return a
    }
}
