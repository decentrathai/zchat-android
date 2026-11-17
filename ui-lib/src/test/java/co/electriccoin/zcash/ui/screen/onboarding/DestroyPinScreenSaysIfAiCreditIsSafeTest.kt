package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.BALANCE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.CHALLENGE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.KEY_STATE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.OWN_KEY_ID
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.SIGN_IN
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.callSuspending
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.ai.AiTabAccessFixture
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * AI-KEY-T (W-52, W-53; AI-SIGNIN-KEY-SPEC.md revision 2b, sections 4.3, 5.4 and 6.6, change 5, F8): the one line on step 1
 * of the Destroy PIN set-up, before the PIN can be saved. Opening the screen calls nobody; each action calls on its tap.
 *
 * THE CONTRACT (new suspending functions of AiAccountAccess, the "ONE function" of the fragment and its two taps, by name):
 *   destroyPinLine(): an enum named UNREADABLE, CANNOT, NOT_YET, SAFE or NONE; reads the store, makes no call, never throws
 *                     for a store that cannot be read
 *   linkNow(): Boolean  the tap on "Link now": true when the account is linked afterwards; no saved key: false and no call
 *   checkNow()          the tap on "Check now": ONE /ai/balance call, whose answer may change the line
 *
 * WHY IT FAILS TODAY: the three functions do not exist, and the place on step 1 draws nothing (its default).
 */
class DestroyPinScreenSaysIfAiCreditIsSafeTest {
    private var phone = AiKeyScreensFixture()

    @After
    fun endProcess() = phone.close()

    private fun line(): String =
        runBlocking { (callSuspending(phone.access, "destroyPinLine") as Enum<*>).name }

    private fun linkNow(): Boolean = runBlocking { callSuspending(phone.access, "linkNow") as Boolean }

    private fun checkNow() {
        runBlocking { callSuspending(phone.access, "checkNow") }
    }

    private fun assertLine(
        wanted: String,
        what: String,
        arrange: AiKeyScreensFixture.() -> Unit,
    ) {
        phone.close()
        phone = AiKeyScreensFixture()
        phone.arrange()
        assertEquals(what, wanted, line())
        assertEquals("$what: opening the screen made a call", emptyList<String>(), phone.requests.toList())
    }

    @Test
    fun `the five rows of spec 5_4 in their order, and no call`() {
        assertLine("UNREADABLE", "the store cannot be read") { unreadableStore() }
        assertLine("CANNOT", "a parked key") { store(saved = false, parked = true) }
        assertLine("CANNOT", "the verdict own") { store(verdict = "own", paid = "yes") }
        assertLine("CANNOT", "linked and parked together") { store(verdict = "linked", paid = "yes", parked = true) }
        assertLine("NOT_YET", "no verdict, paid") { store(paid = "yes") }
        assertLine("NOT_YET", "no verdict, unknown") { store() }
        assertLine("SAFE", "linked, paid") { store(verdict = "linked", paid = "yes") }
        assertLine("SAFE", "linked, not paid") { store(verdict = "linked", paid = "no") }
        assertLine("NONE", "not paid, not linked") { store(paid = "no") }
        assertLine("NONE", "no saved key") { }
    }

    @Test
    fun `a phone that never used AI - no line and no AI file made by asking`() {
        assertEquals("NONE", line())
        assertFalse("asking made the AI file", phone.files.exists(AiPreferences.PREFS_NAME))
    }

    @Test
    fun `Link now - on the tap, and the line becomes the safe one`() {
        phone.store()
        phone.answer = { 200 to """{"signInKeyId":"$OWN_KEY_ID","paid":true}""" }

        assertTrue("the tap did not say linked", linkNow())

        assertEquals(listOf(KEY_STATE), phone.requests.toList())
        assertEquals("SAFE", line())
    }

    @Test
    fun `Link now that fails - false and the line stays, for a 404, a limit, a server error and no network`() {
        val answers: List<suspend (String) -> Pair<Int, String>> =
            listOf(
                { 404 to "{}" },
                { 429 to """{"error":"limit"}""" },
                { 500 to """{"error":"internal"}""" },
                { throw IOException("synthetic: no network") },
            )
        for ((index, answer) in answers.withIndex()) {
            phone.close()
            phone = AiKeyScreensFixture()
            phone.store()
            phone.answer = answer

            assertFalse("answer $index: the tap said linked", linkNow())

            assertEquals("answer $index", "NOT_YET", line())
        }
    }

    @Test
    fun `Link now with no saved key - no call, the tap opens no account`() {
        assertFalse(linkNow())
        assertEquals(emptyList<String>(), phone.requests.toList())
    }

    @Test
    fun `Check now - ONE balance call, the safe line stays when the mark holds`() {
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = { 200 to """{"balanceUsd":1.0,"signInKeyId":"$OWN_KEY_ID","paid":true}""" }

        checkNow()

        assertEquals(listOf(BALANCE), phone.requests.toList())
        assertEquals("SAFE", line())
    }

    @Test
    fun `Check now - the line changes when the mark was wrong`() {
        phone.store(verdict = "linked", paid = "yes")
        phone.answer = { path ->
            when (path) {
                BALANCE -> 200 to """{"balanceUsd":1.0,"signInKeyId":null,"paid":true}"""
                CHALLENGE -> 200 to AiTabAccessFixture.CHALLENGE_ANSWER
                SIGN_IN -> 409 to """{"error":"no account","code":"unknown_key"}"""
                else -> throw AssertionError("the tap asked $path")
            }
        }

        checkNow()

        assertEquals("the tap asked balance once", 1, phone.requests.count { it == BALANCE })
        assertEquals("the tap asked key/state", 0, phone.requests.count { it == KEY_STATE })
        assertEquals("CANNOT", line())
    }

    @Test
    fun `Check now offline - the stored mark stays`() {
        phone.store(verdict = "linked", paid = "yes")

        checkNow()

        assertEquals("SAFE", line())
    }

    // ---------------------------------------------------------------------------------------- the one place on step 1

    private fun source(relative: String): String = SourceScan.stripComments(File(repoRoot(), "$UI/$relative").readText())

    private fun onboarding(): String =
        File(repoRoot(), "$UI/screen/onboarding")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { SourceScan.stripComments(it.readText()) }

    @Test
    fun `source - the screen fills the place, after the credential gate, and draws every text of the five rows`() {
        val screen = source("screen/onboarding/AndroidDestroyPinSetup.kt")
        val gate = screen.indexOf("if (!credentialConfirmed) return")
        val filled = screen.indexOf("aiCreditLine =")
        assertTrue("AndroidDestroyPinSetup does not fill the place for the AI line", filled >= 0)
        assertTrue("the place is filled before the credential gate", gate in 0 until filled)
        val all = onboarding()
        for (key in LINE_KEYS) assertTrue("the set-up does not draw $key", "R.string.$key" in all)
    }

    @Test
    fun `source - opening the screen starts neither tap`() {
        val all = onboarding()
        for (block in Regex("""LaunchedEffect\([^)]*\)\s*\{""").findAll(all).map { all.substring(it.range.last).take(BLOCK_CHARS) }) {
            val body = blockAt(block)
            for (tap in listOf("linkNow", "checkNow")) assertFalse("a LaunchedEffect calls $tap: $body", tap in body)
        }
        assertTrue("no tap calls linkNow", "linkNow(" in all)
        assertTrue("no tap calls checkNow", "checkNow(" in all)
    }

    @Test
    fun `source - the sentence of state 5 is drawn under the place on step 1 and stays on the card`() {
        val view = source("screen/onboarding/view/DestroyPinSetupView.kt")
        val step = view.substringAfter("private fun SetupStepOne(", "").substringBefore("private fun SetupStepTwo(")
        val place = step.indexOf("aiCreditLine()")
        val note = step.indexOf("R.string.destroy_kept_ai_key_note")
        val button = step.indexOf("ZashiButton(")
        assertTrue(
            "destroy_kept_ai_key_note is not between the place and the buttons ($place, $note, $button)",
            place in 0 until note && note < button,
        )
        val card = source("screen/enhanceddestroy/view/EnhancedDestroyView.kt")
        assertTrue("the card lost destroy_kept_ai_key_note", "R.string.destroy_kept_ai_key_note" in card)
    }

    @Test
    fun `source - Destroy All names no AI network class and asks nothing about AI credit`() {
        val manager = source("screen/chat/util/DestroyManager.kt")
        for (name in listOf("AiAccountAccess", "AiApiClient")) assertFalse("DestroyManager names $name", name in manager)
    }

    private fun blockAt(text: String): String {
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(0, i + 1)
            }
        }
        return text
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
        const val BLOCK_CHARS = 4_000
        val LINE_KEYS =
            listOf(
                "destroy_pin_ai_unreadable",
                "destroy_pin_ai_cannot",
                "destroy_pin_ai_not_yet",
                "destroy_pin_ai_link_now",
                "destroy_pin_ai_link_failed",
                "destroy_pin_ai_safe",
                "destroy_pin_ai_check_now",
            )
    }
}
