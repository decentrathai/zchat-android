package co.electriccoin.zcash.ui.call

import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1467 (OT213n B3; owner findings 2 Nov, points 8 and 10): when a call rings or is accepted, the chat's text field
 * keeps focus and the soft keyboard stays up over half of the call screen, on both phones, in audio and video calls.
 * CallOverlay is a Compose sibling of the chat (MainActivity) and nothing in `call/` clears focus or hides the IME.
 *
 * Wanted (FIX_PLAN REVISION 1, B3; lanes/L4_calls.md):
 * - a pure predicate `CallImePolicy.shouldDismissIme(state: VoiceCallManager.CallState): Boolean` (a Kotlin `object` in
 *   `co.electriccoin.zcash.ui.call`), true for every call state that shows a call screen (Ringing, Dialling,
 *   Connecting, InCall, audio or video) and false for Idle and Ended;
 * - CallOverlay asks it and, when true, clears focus and hides the keyboard (`clearFocus(` and `.hide()` on the
 *   software keyboard controller), so the callee who accepts from the Ringing screen with the chat field focused
 *   sees no keyboard;
 * - the overlay is no longer squeezed by the IME: either CallOverlay() is hosted outside MainActivity's
 *   `.imePadding()` surface, or CallOverlay consumes the IME insets itself (`WindowInsets.ime`).
 *
 * The predicate is reached by name, so this class compiles before the fix. Compose cannot run on the JVM here, so the
 * overlay wiring is read from the sources (the CallFailureWiringTest idiom); the keyboard itself is a phone check.
 */
class CallHidesKeyboardTest {
    private val peer = "a".repeat(64)

    private val policy: Pair<Any, Method> by lazy {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.call.CallImePolicy") }.getOrNull()
                ?: fail("ZC-1467: CallImePolicy does not exist; nothing hides the keyboard when a call starts")
        val instance: Any = cls.getField("INSTANCE").get(null) ?: fail("CallImePolicy must be a Kotlin object")
        val method = cls.getMethod("shouldDismissIme", VoiceCallManager.CallState::class.java)
        instance to method
    }

    private fun shouldDismissIme(state: VoiceCallManager.CallState): Boolean = policy.second.invoke(policy.first, state) as Boolean

    @Test
    fun `every state with a call screen hides the keyboard`() {
        val shown =
            listOf(
                VoiceCallManager.CallState.Ringing(peer, "c1"),
                VoiceCallManager.CallState.Ringing(peer, "c1", isVideo = true),
                VoiceCallManager.CallState.Dialling(peer, "c1"),
                VoiceCallManager.CallState.Dialling(peer, "c1", isVideo = true),
                VoiceCallManager.CallState.Connecting(peer, "c1"),
                VoiceCallManager.CallState.Connecting(peer, "c1", isVideo = true),
                VoiceCallManager.CallState.InCall(peer, "c1"),
                VoiceCallManager.CallState.InCall(peer, "c1", isMuted = true),
                VoiceCallManager.CallState.InCall(peer, "c1", isVideo = true),
            )
        shown.forEach { state -> assertTrue(shouldDismissIme(state), "ZC-1467: the keyboard must be hidden in $state") }
    }

    @Test
    fun `no call screen leaves the keyboard alone`() {
        assertEquals(false, shouldDismissIme(VoiceCallManager.CallState.Idle), "Idle must not touch the keyboard")
        assertEquals(
            false,
            shouldDismissIme(VoiceCallManager.CallState.Ended(CallEndReason.UserEnded)),
            "Ended must not touch the keyboard",
        )
    }

    @Test
    fun `the overlay asks the policy and hides the keyboard`() {
        val overlay = functionBody(source("call/CallOverlay.kt"), "CallOverlay")
        assertTrue("CallImePolicy.shouldDismissIme(" in overlay, "ZC-1467: CallOverlay must ask CallImePolicy.shouldDismissIme(state)")
        assertTrue("clearFocus(" in overlay, "ZC-1467: CallOverlay must clear the focused chat field")
        assertTrue(".hide()" in overlay, "ZC-1467: CallOverlay must hide the software keyboard")
    }

    @Test
    fun `the overlay is not squeezed by the keyboard`() {
        val main = source("MainActivity.kt")
        val overlaySource = source("call/CallOverlay.kt")
        val surfaceAt = Regex("""BlankSurface\(\s*Modifier[^{]*?\.imePadding\(\)""").find(main)
        val insideImeSurface =
            surfaceAt != null &&
                "CallOverlay(" in blockAfter(main.substring(surfaceAt.range.first), "BlankSurface(")
        assertTrue(
            !insideImeSurface || "WindowInsets.ime" in overlaySource,
            "ZC-1467: CallOverlay() sits inside MainActivity's imePadding() surface and does not consume the IME insets",
        )
    }

    private val uiDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                val candidate = module.resolve("src/main/java/co/electriccoin/zcash/ui")
                if (candidate.resolve("call").isDirectory) return@lazy candidate
            }
            dir = current.parentFile
        }
        fail("could not locate ui-lib's sources from ${start.path}")
    }

    private fun source(path: String): String = uiDir.resolve(path).readText()

    private fun functionBody(
        src: String,
        name: String,
    ): String {
        val at = Regex("""fun\s+$name\s*\(""").find(src) ?: fail("fun $name not found")
        return blockAfter(src.substring(at.range.first), "fun $name")
    }

    /** The brace-balanced block that follows the first `{` after [what] at the start of [src]. */
    private fun blockAfter(
        src: String,
        what: String,
    ): String {
        val open = src.indexOf('{')
        if (open < 0) fail("no block after $what")
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced block after $what")
    }
}
