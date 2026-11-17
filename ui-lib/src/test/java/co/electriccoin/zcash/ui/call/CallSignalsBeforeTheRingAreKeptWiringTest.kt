package co.electriccoin.zcash.ui.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-409 (fix-all 2.13.0, train T5b; t5b-add SPECS §4.4): how VoiceCallManager uses EarlyCallSignalsTest's store.
 * WebRTC cannot run on the JVM, so this reads the sources, as CallFailureWiringTest does.
 *
 * - An OFFER, ICE or HANGUP that is not for the active call is handed to the store (it used to be dropped: "OFFER from
 *   non-active peer or stale callId — ignoring"). Nothing is applied, rung or answered there.
 * - Only onRing's Idle/Ended branch takes from the store: a call its caller already hung up is not rung; otherwise
 *   `bufferedOffer` is the held OFFER (no unconditional `= null`) and the held ICE goes into
 *   `pendingRemoteCandidates` through `parseIceCandidate`, bounded.
 * - acceptIncoming is unchanged: it applies `bufferedOffer` through onOffer, which drains the candidates.
 * - disposeConfined does not clear the store (a call that has not rung yet outlives another call's teardown).
 * - placeCall still emits exactly one OFFER: the caller does not re-send it (a second OFFER could fail a normal call
 *   on a ZChat 2.12.4 callee), and the wire is unchanged. The only other OFFER is the in-call video upgrade
 *   (ZC-1468, owner W-121), built after renegotiation.startUpgrade in requestVideoUpgrade.
 */
class CallSignalsBeforeTheRingAreKeptWiringTest {
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

    @Test
    fun `the store is pure - no Android and no WebRTC`() {
        val file = callDir.resolve("EarlyCallSignals.kt")
        assertTrue(file.isFile, "call/EarlyCallSignals.kt does not exist: signals that arrive before their RING are dropped")
        val source = file.readText()
        listOf("import android", "import androidx", "org.webrtc", "IceCandidate(").forEach {
            assertTrue(it !in source, "EarlyCallSignals must stay pure (ICE as raw payload strings): found `$it`")
        }
    }

    @Test
    fun `the call manager owns one store with its own ICE limits`() {
        assertTrue(
            "private val earlySignals = EarlyCallSignals(MAX_PENDING_ICE, MAX_ICE_PAYLOAD)" in manager,
            "VoiceCallManager must keep `private val earlySignals = EarlyCallSignals(MAX_PENDING_ICE, MAX_ICE_PAYLOAD)`",
        )
        assertEquals(1, count("""earlySignals\.hold\("""), "the store must be filled in one place (holdEarlySignal)")
        assertEquals(1, count("""earlySignals\.take\("""), "only onRing may take from the store")
        assertEquals(3, count("""\bearlySignals\b"""), "earlySignals: its declaration, one hold and one take, nothing else")
    }

    @Test
    fun `an OFFER that is not for the active call is held, not dropped`() {
        val branch = nonActiveBranch("onOffer")
        assertHoldsAndReturns("onOffer", branch)
        listOf("setRemoteDescription", "buildPeerConnection", "emit(", "bufferedOffer", "_state.value =").forEach {
            assertTrue(it !in branch, "an OFFER before its RING must not be applied, answered or rung: found `$it`")
        }
        assertTrue("OFFER from non-active peer" !in manager, "the OFFER that overtakes its RING is still dropped")
    }

    @Test
    fun `ICE that is not for the active call is held, not dropped`() {
        val body = functionBody(manager, "onIce")
        assertTrue(
            "if (!belongsToActiveCall(sender, env.callId)) return" !in body,
            "onIce still drops a candidate that arrives before its RING",
        )
        val branch = nonActiveBranch("onIce")
        assertHoldsAndReturns("onIce", branch)
        listOf("addIceCandidate", "pendingRemoteCandidates", "parseIceCandidate").forEach {
            assertTrue(it !in branch, "ICE before its RING goes to the store only: found `$it`")
        }
    }

    @Test
    fun `a HANGUP that is not for the active call is held, not dropped`() {
        val branch = nonActiveBranch("onHangup")
        assertHoldsAndReturns("onHangup", branch)
        assertTrue("teardownConfined" !in branch, "a HANGUP for another call must not end the active one")
        assertTrue("HANGUP from non-active peer" !in manager, "the HANGUP that overtakes its RING is still dropped")
    }

    @Test
    fun `holdEarlySignal only stores`() {
        val body = functionBody(manager, "holdEarlySignal")
        assertTrue("earlySignals.hold(sender, env," in body, "holdEarlySignal must hand the envelope to the store")
        assertTrue("held" in body, "the log line must say `held` (the phone check counts those lines)")
        listOf("_state.value =", "emit(", "teardownConfined", "hangUpConfined", "peerConnection", "onOffer(").forEach {
            assertTrue(it !in body, "holding a signal must not apply, ring or answer anything: found `$it`")
        }
    }

    @Test
    fun `onRing takes what was held before anything else is set`() {
        val branch = idleBranchOfOnRing()
        val takeAt = branch.indexOf("earlySignals.take(sender, env.callId,")
        assertTrue(takeAt >= 0, "onRing's Idle/Ended branch must take the store's entry for (sender, env.callId)")
        val peerAt = branch.indexOf("activePeer = sender")
        assertTrue(peerAt > takeAt, "the entry must be taken before the call becomes the active one")
        assertEquals(
            functionBody(manager, "onRing").indexOf("earlySignals.take("),
            functionBody(manager, "onRing").lastIndexOf("earlySignals.take("),
            "one take",
        )
    }

    @Test
    fun `a call its caller already hung up is not rung`() {
        val branch = idleBranchOfOnRing()
        val hungUpAt = branch.indexOf("hungUp")
        assertTrue(hungUpAt >= 0, "onRing must read the held entry's hungUp")
        val guard = blockAfter(branch.substring(hungUpAt), "")
        assertTrue("return" in guard, "a hung-up call must leave onRing: found $guard")
        val returnAt = branch.indexOf("return", hungUpAt)
        listOf("activePeer = sender", "_state.value = CallState.Ringing(", "startSetupTimeout()").forEach {
            val at = branch.indexOf(it)
            assertTrue(at > returnAt, "`$it` must come after the hung-up return, so a hung-up call sets nothing")
        }
    }

    @Test
    fun `the buffered offer is the held OFFER`() {
        val branch = idleBranchOfOnRing()
        assertTrue("bufferedOffer = null" !in branch, "onRing still throws away the OFFER that arrived before the RING")
        assertTrue(
            Regex("""bufferedOffer = \w+\??\.offer\b""").containsMatchIn(branch),
            "onRing must set bufferedOffer from the held entry's offer",
        )
        assertTrue("_state.value = CallState.Ringing(sender, env.callId, wantsVideo)" in branch, "then Ringing, as before")
        assertTrue("onOffer(" !in branch && "buildPeerConnection" !in branch, "nothing is applied before Accept")
    }

    @Test
    fun `the held ICE joins the pending candidates through the parser, bounded`() {
        val branch = idleBranchOfOnRing()
        val loop = blockAfter(branch, ".ice")
        assertTrue("parseIceCandidate(" in loop, "held ICE payloads must go through parseIceCandidate, found: $loop")
        assertTrue("pendingRemoteCandidates +=" in loop, "held ICE must be added to pendingRemoteCandidates, found: $loop")
        assertTrue("MAX_PENDING_ICE" in loop, "the pending list keeps its MAX_PENDING_ICE bound, found: $loop")
        assertTrue("addIceCandidate" !in branch, "no candidate is applied before the remote description")
    }

    @Test
    fun `accepting is unchanged - the buffered offer goes through onOffer`() {
        val body = functionBody(manager, "acceptIncoming")
        assertTrue("val pending = bufferedOffer" in body, "acceptIncoming reads bufferedOffer")
        assertTrue("if (pending != null) onOffer(ringing.peerPubkeyHex, pending)" in body, "and applies it through onOffer")
        assertTrue("drainPendingCandidates()" in functionBody(manager, "onOffer"), "onOffer drains the pending candidates")
        assertTrue("earlySignals" !in body, "acceptIncoming does not touch the store")
    }

    @Test
    fun `tearing a call down keeps the store`() {
        assertTrue("private val earlySignals" in manager, "there is no store to keep")
        listOf("disposeConfined", "teardownConfined", "hangUpConfined", "shutdown").forEach { fn ->
            assertTrue("earlySignals" !in functionBody(manager, fn), "$fn must not clear the store")
        }
    }

    @Test
    fun `placeCall still emits exactly one OFFER and nothing else does`() {
        val body = functionBody(manager, "placeCall")
        assertEquals(1, Regex("""CallSignalType\.OFFER""").findAll(body).count(), "placeCall must emit one OFFER")
        assertEquals(1, Regex("""CallSignalType\.RING""").findAll(body).count(), "placeCall must emit one RING")
        // W-121/ZC-1468: besides placeCall's one setup OFFER, exactly one more OFFER may be built, and only in the
        // in-call video upgrade (requestVideoUpgrade), after renegotiation.startUpgrade(...). Call setup still never
        // re-sends its OFFER; the upgrade can only reach a callee that already accepted (InCall), never a Ringing one.
        assertEquals(
            2,
            count("""CallSignalEnvelope\([^()]*CallSignalType\.OFFER"""),
            "the OFFER is built in placeCall and in the in-call video upgrade only (no setup re-send)",
        )
        val upgrade = functionBody(manager, "requestVideoUpgrade")
        assertEquals(1, Regex("""CallSignalType\.OFFER""").findAll(upgrade).count(), "the upgrade sends one OFFER")
        val start = upgrade.indexOf("startUpgrade(")
        val offer = upgrade.indexOf("CallSignalType.OFFER")
        assertTrue(start in 0 until offer, "the upgrade OFFER is built only after renegotiation.startUpgrade(...)")
    }

    private fun count(pattern: String): Int = Regex(pattern).findAll(manager).count()

    /** The block of `if (!belongsToActiveCall(sender, env.callId)) { ... }` in [fn]. */
    private fun nonActiveBranch(fn: String): String {
        val body = functionBody(manager, fn)
        val marker = "if (!belongsToActiveCall(sender, env.callId))"
        val at = body.indexOf(marker)
        if (at < 0) fail("$fn no longer checks belongsToActiveCall(sender, env.callId)")
        val rest = body.substring(at + marker.length)
        assertTrue(rest.trimStart().startsWith("{"), "$fn: the non-active case must be a block that holds the signal")
        return blockAfter(rest, "")
    }

    private fun assertHoldsAndReturns(
        fn: String,
        branch: String,
    ) {
        val holdAt = branch.indexOf("holdEarlySignal(sender, env)")
        assertTrue(holdAt >= 0, "$fn must hand a signal that is not for the active call to holdEarlySignal, found: $branch")
        assertTrue(branch.indexOf("return", holdAt) > holdAt, "$fn must return after holding the signal, found: $branch")
    }

    private fun idleBranchOfOnRing(): String =
        blockAfter(functionBody(manager, "onRing"), "_state.value is CallState.Idle || _state.value is CallState.Ended ->")

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
