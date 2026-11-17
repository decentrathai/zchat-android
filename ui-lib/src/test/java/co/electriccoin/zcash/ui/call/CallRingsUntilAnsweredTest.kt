package co.electriccoin.zcash.ui.call

import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1469 (OT213n B3; owner findings 2 Nov, point 9: "I don't hear the sound when one phone is calling to another").
 * At 669cf19e1 the callee's only sound is the incoming-call notification's channel sound, which plays once (no
 * FLAG_INSISTENT, no looping Ringtone or MediaPlayer), and the caller hears nothing at all: there is no ringback tone
 * anywhere in `call/` (lanes/L4_calls.md).
 *
 * Wanted (FIX_PLAN REVISION 1, B3), in `co.electriccoin.zcash.ui.call`:
 * - `interface CallRingPlayer { fun startRingtone(); fun startRingback(); fun stop() }`: the callee's looping ringtone,
 *   the caller's ringback tone, and silence;
 * - `class CallRingController(player: CallRingPlayer)` with `fun onState(state: VoiceCallManager.CallState)` and
 *   `fun release()`: Ringing starts the ringtone, Dialling starts the ringback; accepting (Connecting after Ringing),
 *   InCall, Ended (any reason) and Idle leave nothing playing; release() (teardown, shutdown) leaves nothing playing;
 * - the app wires it: a main source in `call/` builds a CallRingController, feeds it the call state and releases it,
 *   and an Android CallRingPlayer loops the ringtone, plays a ToneGenerator ringback and reads the ringer mode.
 *
 * Reached by name, so this class compiles before the fix. The fake player is a java.lang.reflect.Proxy that records
 * which sound is playing. The sound on the phone (ring volume, silent mode, vibrate) is a device check.
 */
class CallRingsUntilAnsweredTest {
    private val peer = "b".repeat(64)
    private val ringing = VoiceCallManager.CallState.Ringing(peer, "c1")
    private val dialling = VoiceCallManager.CallState.Dialling(peer, "c1")
    private val connecting = VoiceCallManager.CallState.Connecting(peer, "c1")
    private val inCall = VoiceCallManager.CallState.InCall(peer, "c1")

    /** What the fake player is playing now: "ringtone", "ringback" or null. */
    private class Sound {
        var playing: String? = null
        val calls = mutableListOf<String>()
    }

    private class Harness(
        val controller: Any,
        val sound: Sound,
    ) {
        private val onState = controller.javaClass.getMethod("onState", VoiceCallManager.CallState::class.java)
        private val release = controller.javaClass.getMethod("release")

        fun feed(vararg states: VoiceCallManager.CallState) = states.forEach { onState.invoke(controller, it) }

        fun release() {
            release.invoke(controller)
        }
    }

    private fun harness(): Harness {
        val playerType =
            runCatching { Class.forName("co.electriccoin.zcash.ui.call.CallRingPlayer") }.getOrNull()
                ?: fail("ZC-1469: CallRingPlayer does not exist; no looping ringtone on the callee, no ringback on the caller")
        val controllerType =
            runCatching { Class.forName("co.electriccoin.zcash.ui.call.CallRingController") }.getOrNull()
                ?: fail("ZC-1469: co.electriccoin.zcash.ui.call.CallRingController does not exist; nothing starts or stops a call sound")
        val sound = Sound()
        val player =
            Proxy.newProxyInstance(playerType.classLoader, arrayOf(playerType)) { proxy, method, args ->
                when (method.name) {
                    "startRingtone" -> sound.playing = "ringtone"
                    "startRingback" -> sound.playing = "ringback"
                    "stop" -> sound.playing = null
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.get(0)
                    "toString" -> return@newProxyInstance "FakeCallRingPlayer"
                }
                sound.calls += method.name
                null
            }
        val controller = controllerType.getConstructor(playerType).newInstance(player)
        return Harness(controller, sound)
    }

    @Test
    fun `the callee hears a ringtone while it rings`() {
        val h = harness()
        h.feed(ringing)
        assertEquals("ringtone", h.sound.playing, "ZC-1469: Ringing must start the callee's ringtone")
    }

    @Test
    fun `the caller hears a ringback while it dials`() {
        val h = harness()
        h.feed(dialling)
        assertEquals("ringback", h.sound.playing, "ZC-1469: Dialling must start the caller's ringback tone")
    }

    @Test
    fun `accepting stops the ringtone`() {
        val h = harness()
        h.feed(ringing, connecting)
        assertEquals(null, h.sound.playing, "ZC-1469: the ringtone must stop when the callee accepts")
        h.feed(inCall)
        assertEquals(null, h.sound.playing, "ZC-1469: nothing plays in the call")
    }

    @Test
    fun `the ringback stops when the call connects`() {
        val h = harness()
        h.feed(dialling, connecting, inCall)
        assertEquals(null, h.sound.playing, "ZC-1469: the ringback must stop at InCall")
    }

    @Test
    fun `every end stops the sound`() {
        val ends =
            listOf(
                VoiceCallManager.CallState.Ended(CallEndReason.Declined),
                VoiceCallManager.CallState.Ended(CallEndReason.UserEnded),
                VoiceCallManager.CallState.Ended(CallEndReason.RemoteHangup("bye")),
                VoiceCallManager.CallState.Ended(CallEndReason.Timeout),
                VoiceCallManager.CallState.Ended(CallEndReason.Busy),
                VoiceCallManager.CallState.Idle,
            )
        for (start in listOf(ringing, dialling)) {
            for (end in ends) {
                val h = harness()
                h.feed(start, end)
                assertEquals(null, h.sound.playing, "ZC-1469: $end after $start must leave nothing playing")
            }
        }
    }

    @Test
    fun `release stops the sound on teardown`() {
        for (start in listOf(ringing, dialling)) {
            val h = harness()
            h.feed(start)
            h.release()
            assertEquals(null, h.sound.playing, "ZC-1469: release() during $start must leave nothing playing")
        }
    }

    @Test
    fun `a new call after an ended one rings again`() {
        val h = harness()
        h.feed(ringing, VoiceCallManager.CallState.Ended(CallEndReason.Declined), VoiceCallManager.CallState.Idle, dialling)
        assertEquals("ringback", h.sound.playing, "ZC-1469: the next call must get its sound again")
    }

    @Test
    fun `the app wires a real ring player`() {
        val sources = callDir.listFiles { f -> f.extension == "kt" }.orEmpty().associate { it.name to it.readText() }
        val wiring = sources.filterValues { "CallRingController(" in it && !it.contains("class CallRingController(") }
        assertTrue(wiring.isNotEmpty(), "ZC-1469: no main source in call/ builds a CallRingController")
        val fedAndReleased = wiring.values.any { ".onState(" in it && ".release()" in it }
        assertTrue(fedAndReleased, "ZC-1469: the CallRingController must be fed the call state and released")
        val players = sources.filterValues { Regex(""":\s*CallRingPlayer\b""").containsMatchIn(it) }
        assertTrue(players.isNotEmpty(), "ZC-1469: no Android CallRingPlayer implementation in call/")
        val player = players.values.joinToString("\n")
        assertTrue("isLooping = true" in player || "FLAG_INSISTENT" in player, "ZC-1469: the callee's ringtone must loop")
        assertTrue("ToneGenerator" in player, "ZC-1469: the caller's ringback must be a ToneGenerator tone")
        assertTrue("ringerMode" in player, "ZC-1469: the ringtone must respect the ringer mode (silent / vibrate)")
    }

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
}
