package co.electriccoin.zcash.ui.screen.deletewallet

import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.AI_TOKEN
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.AI_USER_ID
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.RECORD_RENAME
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_AI_READ
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEPT_DELETE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEPT_WRITE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * AI-KEY-R (W-52, W-53; AI-SIGNIN-KEY-SPEC.md revision 2b, sections 5.3 and 6.5): which warning the screen showed decides
 * what a failed read of the AI store means. Only with the warning for a store that cannot be read does the Reset go on,
 * with keep=none; a read that fails after any other warning, and a failed write after any warning, is "not started".
 *
 * THE CONTRACT (new): the use case has a second invoke, `invoke(keepFiles: Boolean, aiUnreadableWarningShown: Boolean)`.
 * The one-argument invoke stays and means false: ResetDriver, ChangeIdentityVM's tests and the earlier classes call it.
 *
 * WHY THE RED CASES FAIL TODAY: the use case has one invoke, and step S1b answers "not started" for every failed read.
 *
 * NOT REPEATED HERE, they are pinned and stay unedited: the record's third value, a fourth value, the pass's rows and
 * both guarded states (ResetStartPassFinishesOnlyItsOwnResetTest, ResetAlsoDeletesOldSetAsideWalletDataTest); the kept
 * file before the record, "a write that fails is not started" (ResetWritesItsRecordFirstAndNeverWaitsTest).
 */
class ResetKeepsTheAiKeyThroughTheStartPassTest {
    private val drivers = ArrayList<ResetDriver>()

    @After
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    private fun driver(): ResetDriver = ResetDriver().also { drivers += it }.apply { seedPhraseStore() }

    private suspend fun ResetDriver.resetAfterTheWarning(shown: Boolean): String {
        val method =
            useCase.javaClass.methods.firstOrNull {
                it.name == "invoke" && it.parameterCount == 3 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            } ?: throw AssertionError("AI-KEY-R: ResetZashiUseCase has no invoke(keepFiles, aiUnreadableWarningShown)")
        val answer =
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    method.invoke(useCase, false, shown, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        return (answer as Enum<*>).name
    }

    @Test
    fun `the unreadable warning was shown and the read fails or is late - the Reset goes on with keep=none`() =
        runBlocking {
            for (fault in listOf("fails", "hangs")) {
                val reset = driver()
                if (fault == "fails") reset.phone.failAt += STEP_AI_READ else reset.phone.hangAt += STEP_AI_READ

                assertEquals("read $fault: the answer", STARTED, reset.resetAfterTheWarning(shown = true))

                assertEquals("read $fault: the record", "none", reset.recordLines()["keep"])
                assertFalse("read $fault: a kept file", reset.bundles.containsKey(reset.tag))
            }
        }

    @Test
    fun `the unreadable warning was shown and the store can be read after all - the key is still kept`() =
        runBlocking {
            val reset = driver()

            assertEquals(STARTED, reset.resetAfterTheWarning(shown = true))

            assertEquals("ai-key", reset.recordLines()["keep"])
            assertTrue("the kept file stands after the record", reset.at(STEP_KEPT_WRITE) in 0 until reset.at(RECORD_RENAME))
        }

    @Test
    fun `the unreadable warning was shown and the WRITE fails or is late - not started, never keep=none`() =
        runBlocking {
            for (fault in listOf("fails", "hangs")) {
                val reset = driver()
                if (fault == "fails") reset.phone.failAt += STEP_KEPT_WRITE else reset.phone.hangAt += STEP_KEPT_WRITE

                assertEquals("write $fault: the answer", NOT_STARTED, reset.resetAfterTheWarning(shown = true))

                assertNull("write $fault: a record", reset.record())
                assertFalse("write $fault: the kept file stayed", reset.bundles.containsKey(reset.tag))
            }
        }

    @Test
    fun `no such warning was shown and the read fails or is late - not started`() =
        runBlocking {
            for (fault in listOf("fails", "hangs")) {
                val reset = driver()
                if (fault == "fails") reset.phone.failAt += STEP_AI_READ else reset.phone.hangAt += STEP_AI_READ

                assertEquals("read $fault: the answer", NOT_STARTED, reset.resetAfterTheWarning(shown = false))

                assertNull("read $fault: a record", reset.record())
            }
        }

    // GUARD, passes before and after the fix (2b): the pass removes the older file, nothing of the wallet goes before the record.
    @Test
    fun `nothing to keep - keep=none, and the Reset itself deletes no older kept file`() =
        runBlocking {
            val reset = driver().apply { aiKept = emptyMap() }
            val older = mapOf("zchat_ai_prefs" to mapOf<String, Any?>(AI_TOKEN to "synthetic-older", AI_USER_ID to "synthetic-user"))
            reset.bundles[reset.tag] = older

            assertEquals(STARTED, reset.reset(keepFiles = false))

            assertEquals("none", reset.recordLines()["keep"])
            assertTrue("the Reset deleted it: ${reset.phone.events}", reset.at(STEP_KEPT_DELETE) < 0)
            assertEquals(older, reset.bundles[reset.tag])
        }

    // GUARD: the spec's "names AiPreferences" no longer holds; the use case holds AiResetKeep, which holds the store.
    @Test
    fun `source - the Reset names no AI network class and makes no network call`() {
        for (path in listOf(USE_CASE, KEEP)) {
            val source = SourceScan.stripComments(File(repoRoot(), path).readText())
            for (name in listOf("AiAccountAccess", "AiApiClient", "HttpClient", "httpClient")) {
                assertFalse("$path names $name", name in source)
            }
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val STARTED = "STARTED"
        const val NOT_STARTED = "NOT_STARTED"
        const val USE_CASE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt"
        const val KEEP = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiResetKeep.kt"
    }
}
