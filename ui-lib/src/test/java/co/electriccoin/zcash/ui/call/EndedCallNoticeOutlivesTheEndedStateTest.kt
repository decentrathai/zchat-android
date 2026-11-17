package co.electriccoin.zcash.ui.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-411 (KI-184; fix-all 2.13.0, train T6a): the wiring that lets the ended-call notice stay for
 * `CallEndReason.noticeHoldMs` (EndedCallNoticeIsLongEnoughToReadTest pins the times). Compose and WebRTC cannot run
 * on the JVM, so this reads the sources (the CallFailureWiringTest idiom).
 *
 * Today CallOverlay draws the notice only while the call state is Ended (`is Ended -> EndedToast(s.reason)`), and
 * VoiceCallManager flips Ended to Idle after IDLE_RESET_DELAY_MS = 2,000 ms. Wanted:
 * - the manager's timing does not change (a redial and the callee's accept during Ended depend on it, and a longer
 *   Ended state would widen the window of its uncancelled reset timer);
 * - CallOverlay remembers the last ended reason in its own state: set when the state becomes Ended, kept through the
 *   flip to Idle, cleared after noticeHoldMs, by a tap on the notice, or when a new call starts (any state that is
 *   neither Idle nor Ended); the Ended branch of the screen `when` no longer draws the notice itself;
 * - EndedToast(reason, onDismiss) is tappable and still shows `stringResource(reason.labelRes)`.
 */
class EndedCallNoticeOutlivesTheEndedStateTest {
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
    private val overlaySource: String by lazy { callDir.resolve("CallOverlay.kt").readText() }
    private val overlay: String by lazy { functionBody(overlaySource, "CallOverlay") }
    private val toast: String by lazy { functionBody(overlaySource, "EndedToast") }

    /** The brace-balanced blocks of every `LaunchedEffect(...) { ... }` in CallOverlay. */
    private val effects: List<String> by lazy {
        Regex("""LaunchedEffect\(""").findAll(overlay).map { blockAfter(overlay.substring(it.range.first), "") }.toList()
    }

    @Test
    fun `the manager's Ended state still lasts 2 s`() {
        assertTrue(
            Regex("""private const val IDLE_RESET_DELAY_MS = 2_000L""").containsMatchIn(manager),
            "VoiceCallManager must keep IDLE_RESET_DELAY_MS = 2_000L (it also gates a redial)",
        )
        listOf("teardownConfined", "endTransient").forEach { name ->
            assertTrue("delay(IDLE_RESET_DELAY_MS)" in functionBody(manager, name), "$name must still wait IDLE_RESET_DELAY_MS")
        }
        assertTrue("noticeHoldMs" !in manager, "the notice's hold belongs to the overlay, not to the call state machine")
    }

    @Test
    fun `the Ended branch of the screen when no longer draws the notice`() {
        val branches = Regex("""CallState\.Ended\s*->[^\n]*""").findAll(overlay).map { it.value }.toList()
        assertTrue(branches.isNotEmpty(), "CallOverlay must still handle CallState.Ended")
        val drawing = branches.filter { "EndedToast(" in it }
        assertEquals(emptyList(), drawing, "the notice is still drawn only while the state is Ended (2 s)")
    }

    @Test
    fun `the overlay remembers the ended notice in its own state`() {
        assertTrue(
            Regex("""var\s+endedNotice\s+by\s+remember\s*\{\s*mutableStateOf<[^>]+\?>\(null\)\s*\}""")
                .containsMatchIn(overlay),
            "CallOverlay must keep `var endedNotice by remember { mutableStateOf<...?>(null) }`",
        )
        val setter =
            effects.firstOrNull { Regex("""CallState\.Ended\s*->""").containsMatchIn(it) }
                ?: fail("no LaunchedEffect in CallOverlay sets the notice when the state becomes Ended")
        assertTrue(
            Regex("""CallState\.Ended\s*->\s*\{?\s*[^\n]*endedNotice\s*=""").containsMatchIn(setter),
            "Ended must set endedNotice: $setter",
        )
        assertTrue(
            Regex("""CallState\.Idle\s*->\s*Unit""").containsMatchIn(setter),
            "Idle must keep the notice (the manager flips Ended to Idle after 2 s): $setter",
        )
        assertTrue(
            Regex("""else\s*->\s*endedNotice\s*=\s*null""").containsMatchIn(setter),
            "a new call (any state that is neither Idle nor Ended) must clear the notice: $setter",
        )
    }

    @Test
    fun `each ending gets its own full hold`() {
        // Keyed per ending with a counter, not on the reason: two endings with the same reason each get a full hold.
        assertTrue(
            Regex("""class\s+EndedNotice\(\s*val\s+ending:\s*Int,\s*val\s+reason:\s*CallEndReason,?\s*\)""")
                .containsMatchIn(overlaySource),
            "the remembered notice must carry a per-ending counter beside the reason",
        )
        val timer = effects.firstOrNull { "noticeHoldMs" in it } ?: fail("no LaunchedEffect in CallOverlay waits noticeHoldMs")
        assertTrue(Regex("""delay\([^)]*noticeHoldMs\)""").containsMatchIn(timer), "the timer must delay(noticeHoldMs): $timer")
        assertTrue(Regex("""endedNotice\s*=\s*null""").containsMatchIn(timer), "the timer must clear the notice: $timer")
        assertTrue(
            Regex("""LaunchedEffect\(endedNotice\)\s*\{[^}]*noticeHoldMs""").containsMatchIn(overlay),
            "the timer must be keyed on the remembered notice (reason and counter), so a new ending restarts it",
        )
    }

    @Test
    fun `the notice is drawn from the remembered state, only with no call on screen, and a tap dismisses it`() {
        assertTrue(
            Regex("""EndedToast\(\s*\w+\.reason,\s*onDismiss\s*=\s*\{\s*endedNotice\s*=\s*null\s*\}\s*\)""")
                .containsMatchIn(overlay),
            "CallOverlay must draw EndedToast(<notice>.reason, onDismiss = { endedNotice = null })",
        )
        assertTrue(
            Regex("""if\s*\(\s*!active\s*\)\s*\{?\s*endedNotice\?\.let""").containsMatchIn(overlay),
            "the notice must be drawn only while no call screen is up (`if (!active) endedNotice?.let { ... }`)",
        )
        assertTrue(
            Regex("""private fun EndedToast\(\s*reason:\s*CallEndReason,\s*onDismiss:\s*\(\)\s*->\s*Unit,?\s*\)""")
                .containsMatchIn(overlaySource),
            "EndedToast must take (reason: CallEndReason, onDismiss: () -> Unit)",
        )
        assertTrue(
            Regex("""clickable\(\s*onClick\s*=\s*onDismiss\s*\)""").containsMatchIn(toast),
            "the notice's surface must be clickable(onClick = onDismiss)",
        )
        assertTrue("stringResource(reason.labelRes)" in toast, "the notice must still show stringResource(reason.labelRes)")
    }

    private companion object {
        /** The body of `fun name(...) { ... }`: the first brace-balanced block after the declaration. */
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
