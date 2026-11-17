package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TURN-CREDS (fix-all 2.13.0, train T5; turn-creds SPEC §3.1 T4, phone checks C1, C3, C4): two debug-only seams.
 *
 * - TURN_CREDS_UNAVAILABLE: TurnCredentialsClient.get() behaves as if the backend gave no credential: it returns null
 *   without fetching, and drops a cached one (so no call made while it is armed has the relay). Disarmed, the next
 *   get() fetches again.
 * - TURN_RELAY_ONLY: read by VoiceCallManager.buildPeerConnection (relay-only ICE; CallFailureWiringTest checks the
 *   read site, WebRTC cannot run on the JVM).
 *
 * Like every seam they read "not armed" in a release build (DebugFaultSeamsReleaseAbsenceTest). The seams are reached
 * by name, so this file compiles before they exist.
 */
class TurnSeamsTest {
    @After
    fun tearDown() {
        FaultSeams.disarmAll()
    }

    private fun seam(name: String): FaultSeam =
        FaultSeam.entries.firstOrNull { it.name == name }
            ?: throw AssertionError("FaultSeam.$name is not in this build: the TURN-CREDS phone checks can't run")

    private fun creds(expiresAtMs: Long) =
        TurnCredentials(
            username = "1767229200",
            password = "synthetic-pass",
            uris = TurnCredentialsClient.DEFAULT_TURN_URIS,
            expiresAtMs = expiresAtMs,
        )

    private fun countingClient(onFetch: () -> Unit) =
        TurnCredentialsClient(
            nowMs = { 0L },
            fetcher = {
                onFetch()
                creds(expiresAtMs = 3_600_000L)
            },
        )

    @Test
    fun `TURN_CREDS_UNAVAILABLE - no fetch, no credential, the cache is dropped`() =
        runTest {
            var fetches = 0
            val client = countingClient { fetches++ }
            assertNotNull("fixture sanity: the first get() fetches", client.get())
            assertEquals(1, fetches)

            val unavailable = seam("TURN_CREDS_UNAVAILABLE")
            try {
                FaultSeams.arm(unavailable, 1)
                assertNull("armed: get() must return no credential, even with a fresh one cached", client.get())
                assertNull(client.get())
                assertEquals("armed: get() must not fetch", 1, fetches)
            } finally {
                FaultSeams.disarm(unavailable)
            }

            assertNotNull("disarmed: get() works again", client.get())
            assertEquals("the cache was dropped while armed, so the next get() fetches", 2, fetches)
        }

    @Test
    fun `TURN_RELAY_ONLY exists`() {
        seam("TURN_RELAY_ONLY")
    }

    @Test
    fun `control - nothing armed, a fresh cached credential is reused without a fetch`() =
        runTest {
            var fetches = 0
            val client = countingClient { fetches++ }
            assertNotNull(client.get())
            assertNotNull(client.get())
            assertEquals(1, fetches)
        }
}
