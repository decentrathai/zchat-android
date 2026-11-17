package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.R
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-411 (KI-184; fix-all 2.13.0, train T6a): the notice that says why a call ended stays on screen long enough to
 * read, in every shipped language.
 *
 * Today every notice is shown for as long as the call state is Ended, which is IDLE_RESET_DELAY_MS = 2,000 ms. The
 * no-relay text ("Couldn't connect. ZChat's call relay wasn't available …") is 21 words in English and 31 in Spanish:
 * at about 0.3 s a word it needs 6.3 s and 9.3 s. Two short Spanish texts (the microphone permission and the call
 * collision, 7 words each) need 2.1 s.
 *
 * The rule, for every CallEndReason and for English and Spanish: hold >= max(2,000 ms, words x 300 ms), and
 * hold <= 15,000 ms (the notice sits over the top of the app). The hold is `CallEndReason.noticeHoldMs`, read by name
 * so this file compiles before it exists; until it exists the hold is what the app does today, IDLE_RESET_DELAY_MS
 * read from VoiceCallManager.kt.
 */
class EndedCallNoticeIsLongEnoughToReadTest {
    private val uiLibDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                if (module.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) return@lazy module
            }
            dir = current.parentFile
        }
        fail("could not locate the ui-lib module from ${start.path}")
    }

    private fun strings(values: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/common/$values/call_screen_strings.xml")
        if (!file.isFile) fail("expected ${file.path}")
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
    }

    private val en: Map<String, String> by lazy { strings("values") }
    private val es: Map<String, String> by lazy { strings("values-es") }

    /** Resource id to key, for the call_ended* texts. */
    private val keyOfId: Map<Int, String> by lazy {
        en.keys.filter { it.startsWith("call_ended") }.associateBy { key ->
            runCatching { R.string::class.java.getField(key).getInt(null) }.getOrElse { fail("no string resource $key") }
        }
    }

    private fun reasonObject(simpleName: String): CallEndReason =
        runCatching {
            Class
                .forName("co.electriccoin.zcash.ui.call.CallEndReason\$$simpleName")
                .getField("INSTANCE")
                .get(null) as CallEndReason
        }.getOrNull() ?: fail("CallEndReason.$simpleName does not exist")

    private val noRelay: CallEndReason by lazy { reasonObject("NoRelay") }

    private val reasons: List<CallEndReason> by lazy {
        listOf(
            CallEndReason.UserEnded,
            CallEndReason.Declined,
            CallEndReason.Busy,
            CallEndReason.PermissionDenied,
            CallEndReason.IceFailed,
            CallEndReason.Timeout,
            CallEndReason.Glare,
            CallEndReason.Shutdown,
            CallEndReason.BackPressed,
            CallEndReason.RemoteHangup("user_ended"),
            CallEndReason.SetupFailed("x"),
            reasonObject("ConnectFailed"),
            noRelay,
        )
    }

    private fun labelKey(reason: CallEndReason): String {
        val id = reason.javaClass.getMethod("getLabelRes").invoke(reason) as Int
        return keyOfId[id] ?: fail("$reason shows a text that is not a call_ended* resource")
    }

    /** How long the notice for [reason] is on screen: its own hold, else (today) the length of the Ended state. */
    private fun holdMs(reason: CallEndReason): Long {
        val own = runCatching { reason.javaClass.getMethod("getNoticeHoldMs").invoke(reason) as Long }.getOrNull()
        if (own != null) return own
        val manager = uiLibDir.resolve("src/main/java/co/electriccoin/zcash/ui/call/VoiceCallManager.kt").readText()
        val match =
            Regex("""const val IDLE_RESET_DELAY_MS\s*=\s*([0-9_]+)L""").find(manager)
                ?: fail("VoiceCallManager.kt has no IDLE_RESET_DELAY_MS")
        return match.groupValues[1].replace("_", "").toLong()
    }

    /** Words a reader reads: whitespace-separated tokens that hold a letter or a digit (a lone dash is not a word). */
    private fun words(text: String): Int =
        text.split(Regex("""\s+""")).count { token -> token.any { it.isLetterOrDigit() } }

    @Test
    fun `the fixture counts the no-relay text as 21 English and 31 Spanish words`() {
        assertEquals("call_ended_no_relay", labelKey(noRelay))
        assertEquals(21, words(en.getValue("call_ended_no_relay")))
        assertEquals(31, words(es.getValue("call_ended_no_relay")))
        assertEquals(4, words("Call collision — please retry"))
    }

    @Test
    fun `every ended notice stays at least 0,3 s a word and at least 2 s, in English and Spanish`() {
        val tooShort =
            reasons.flatMap { reason ->
                val key = labelKey(reason)
                val hold = holdMs(reason)
                listOf("en" to en[key], "es" to es[key]).mapNotNull { (lang, text) ->
                    if (text == null) fail("$lang $key missing")
                    val needed = maxOf(MIN_HOLD_MS, words(text) * MS_PER_WORD)
                    if (hold >= needed) null else "$reason $lang $key: shown $hold ms, ${words(text)} words need $needed ms"
                }
            }
        assertEquals(emptyList(), tooShort, "ended-call notices that cannot be read in the time they are shown")
    }

    @Test
    fun `the no-relay notice stays at least 4 s`() {
        val hold = holdMs(noRelay)
        assertTrue(
            hold >= NO_RELAY_FLOOR_MS,
            "the no-relay notice is shown $hold ms; the register's floor is $NO_RELAY_FLOOR_MS ms",
        )
    }

    @Test
    fun `no ended notice stays longer than 15 s`() {
        val tooLong = reasons.filter { holdMs(it) > MAX_HOLD_MS }.map { "$it: ${holdMs(it)} ms" }
        assertEquals(emptyList(), tooLong, "the notice covers the top of the app; none may stay longer than $MAX_HOLD_MS ms")
    }

    @Test
    fun `the short English notices keep today's 2 s`() {
        // The callee's "Call ended" and the other two-to-four-word notices must not start to linger.
        listOf(
            CallEndReason.UserEnded,
            CallEndReason.Declined,
            CallEndReason.Busy,
            CallEndReason.Timeout,
            CallEndReason.RemoteHangup("user_ended"),
        ).forEach { assertEquals(MIN_HOLD_MS, holdMs(it), "$it") }
    }

    private companion object {
        const val MS_PER_WORD = 300L
        const val MIN_HOLD_MS = 2_000L
        const val MAX_HOLD_MS = 15_000L
        const val NO_RELAY_FLOOR_MS = 4_000L
    }
}
