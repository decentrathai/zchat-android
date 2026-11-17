package co.electriccoin.zcash.ui.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TURN-CREDS (fix-all 2.13.0, train T5; turn-creds SPEC §3.1 T2): the wiring of CallFailureReasonTest's mapper and of
 * the two debug seams the phone checks C1..C4 need. WebRTC cannot run on the JVM, so this reads the sources.
 *
 * - VoiceCallManager's three early-end sites (the 45 s setup timeout, the disconnect grace after InCall, and ICE
 *   FAILED) ask `CallFailure.reasonFor(...)` instead of naming CallEndReason.Timeout / CallEndReason.IceFailed.
 * - `relayCandidateSeen` is set inside the existing `callScope.launch` of onIceCandidate (confined, like every other
 *   call field) when the local candidate is `typ relay`, and is reset for every call (placeCall, onRing, teardown).
 * - buildPeerConnection asks for relay-only ICE (IceTransportsType.RELAY) only while the debug seam TURN_RELAY_ONLY
 *   is armed; otherwise ALL, as today.
 * - TurnCredentialsClient.get() checks TURN_CREDS_UNAVAILABLE before anything else: it drops the cache and returns
 *   null without a fetch (TurnSeamsTest checks the behaviour).
 */
class CallFailureWiringTest {
    private val callDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                val candidate = module.resolve("src/main/java/co/electriccoin/zcash/ui/call")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = current.parentFile
        }
        fail("could not locate ui-lib's call package from ${start.path}")
    }

    private val manager: String by lazy { callDir.resolve("VoiceCallManager.kt").readText() }
    private val client: String by lazy { callDir.resolve("TurnCredentialsClient.kt").readText() }

    @Test
    fun `the setup timeout asks the mapper`() {
        val body = functionBody(manager, "startSetupTimeout")
        assertTrue("CallFailure.reasonFor(" in body, "startSetupTimeout must end the call with CallFailure.reasonFor(...)")
        assertTrue("CallFailure.Kind.SETUP_TIMEOUT" in body, "startSetupTimeout must pass Kind.SETUP_TIMEOUT")
        assertTrue("CallEndReason.Timeout" !in body, "startSetupTimeout still names CallEndReason.Timeout (\"No answer\" after the answer)")
        assertTrue("hangUpConfined(" in body, "the setup timeout must still send its HANGUP through hangUpConfined")
    }

    @Test
    fun `the disconnect grace asks the mapper`() {
        val body = functionBody(manager, "scheduleDisconnectTimeout")
        assertTrue("CallFailure.reasonFor(" in body, "scheduleDisconnectTimeout must end the call with CallFailure.reasonFor(...)")
        assertTrue("CallFailure.Kind.ICE_LOST" in body, "scheduleDisconnectTimeout must pass Kind.ICE_LOST")
        assertTrue("CallEndReason.IceFailed" !in body, "scheduleDisconnectTimeout still names CallEndReason.IceFailed")
        assertTrue("hangUpConfined(" !in body, "the ICE path must send no HANGUP (it does not today)")
    }

    @Test
    fun `ICE FAILED asks the mapper`() {
        val body = functionBody(manager, "onIceState")
        assertTrue("CallFailure.reasonFor(" in body, "onIceState's FAILED branch must end the call with CallFailure.reasonFor(...)")
        assertTrue("CallFailure.Kind.ICE_FAILED" in body, "onIceState must pass Kind.ICE_FAILED")
        assertTrue("CallEndReason.IceFailed" !in body, "onIceState still names CallEndReason.IceFailed")
        assertTrue("hangUpConfined(" !in body, "the ICE path must send no HANGUP (it does not today)")
    }

    @Test
    fun `relayCandidateSeen is set confined in onIceCandidate for a relay candidate`() {
        assertTrue(
            Regex("""private var relayCandidateSeen(: Boolean)? = false""").containsMatchIn(manager),
            "VoiceCallManager must keep `private var relayCandidateSeen = false` with the other confined call fields",
        )
        val onCandidate = functionBody(manager, "onIceCandidate")
        val launch = blockAfter(onCandidate, "callScope.launch")
        assertTrue(
            "relayCandidateSeen = true" in launch,
            "relayCandidateSeen must be set inside onIceCandidate's callScope.launch (confined), found: $launch",
        )
        assertTrue("\"relay\"" in onCandidate, "relayCandidateSeen must be set only for a `typ relay` candidate")
        assertEquals(
            1,
            Regex("""relayCandidateSeen = true""").findAll(manager).count(),
            "relayCandidateSeen must be set in one place only",
        )
    }

    @Test
    fun `relayCandidateSeen is reset for every call`() {
        listOf("placeCall", "onRing", "teardownConfined").forEach { fn ->
            assertTrue(
                "relayCandidateSeen = false" in functionBody(manager, fn),
                "$fn must reset relayCandidateSeen, so one call's relay candidate never speaks for the next",
            )
        }
    }

    @Test
    fun `relay-only ICE only while the debug seam TURN_RELAY_ONLY is armed`() {
        val build = functionBody(manager, "buildPeerConnection")
        val assignment =
            build
                .substringAfter("iceTransportsType =", missingDelimiterValue = "")
                .substringBefore("continualGatheringPolicy")
        assertTrue(
            "FaultSeams.isArmed(FaultSeam.TURN_RELAY_ONLY)" in assignment,
            "iceTransportsType must read FaultSeams.isArmed(FaultSeam.TURN_RELAY_ONLY), found: $assignment",
        )
        assertTrue("IceTransportsType.RELAY" in assignment && "IceTransportsType.ALL" in assignment, "RELAY when armed, ALL otherwise")
        assertEquals(1, Regex("""IceTransportsType\.RELAY""").findAll(manager).count(), "IceTransportsType.RELAY must appear only there")
    }

    @Test
    fun `get() checks TURN_CREDS_UNAVAILABLE before it fetches`() {
        val body = functionBody(client, "get")
        val seamAt = body.indexOf("FaultSeams.isArmed(FaultSeam.TURN_CREDS_UNAVAILABLE)")
        assertTrue(seamAt >= 0, "TurnCredentialsClient.get() must read FaultSeams.isArmed(FaultSeam.TURN_CREDS_UNAVAILABLE)")
        val fetchAt = body.indexOf("fetcher()")
        assertTrue(fetchAt < 0 || seamAt < fetchAt, "the seam must be checked before the fetch")
        val branch = blockAfter(body.substring(seamAt), "")
        assertTrue("cached = null" in branch && "return null" in branch, "while armed: drop the cache and return null, found: $branch")
    }

    @Test
    fun `the fetch itself is unchanged - same URL, bound and freshness, no identity sent`() {
        assertTrue("\"https://api.zsend.xyz/api/v1/turn/credentials\"" in client, "the credential URL changed")
        assertTrue("FETCH_TIMEOUT_MS = 4_000L" in client, "the 4 s bound changed")
        assertTrue("FRESHNESS_SLACK_MS = 300_000L" in client, "the 5-min freshness slack changed")
        assertTrue("client.get(CREDENTIALS_URL)" in client, "the request must stay a bare GET of CREDENTIALS_URL")
        listOf("header(", "headers {", "parameter(", "npub", "installId", "x-zchat").forEach {
            assertTrue(it !in client, "TurnCredentialsClient must send no identity with the request: found `$it`")
        }
    }

    private companion object {
        /** The `{ ... }` body of the first `fun <name>(` in [source]. */
        fun functionBody(
            source: String,
            name: String,
        ): String {
            val at =
                Regex("""\bfun\s+$name\s*\(""").find(source)?.range?.first
                    ?: fail("no `fun $name(` in the source")
            return blockAfter(source.substring(at), "")
        }

        /** The brace-balanced block that follows the first [marker] (the first `{` after it). */
        fun blockAfter(
            source: String,
            marker: String,
        ): String {
            val from = if (marker.isEmpty()) 0 else source.indexOf(marker).also { if (it < 0) fail("no `$marker` in: $source") }
            val open = source.indexOf('{', from)
            if (open < 0) fail("no block after `$marker`")
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(open, i + 1)
                    }
                }
            }
            fail("unbalanced braces after `$marker`")
        }
    }
}
