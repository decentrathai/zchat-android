package co.electriccoin.zcash.ui.screen.deletewallet

import co.electriccoin.zcash.ui.screen.ai.AiResetKeep
import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI-KEY-R/T round 2 (W-97; ZC-1223; RT_ROUND2.md item 5, from GPT): a keep writer already admitted (S1B_WRITING) that
 * outlives the Reset it belongs to must not publish. Owner rule (W-41, W-43): Destroy All leaves nothing recoverable; a key
 * published after the sweep is a key that survives it.
 *
 * THE CONTRACT: `SetAsideBundles.write(tag, content, mayPublish: () -> Boolean)`, with a default for the other callers.
 * The writer asks mayPublish right before its commit; false publishes nothing. The Reset answers it with a compare-and-set
 * against giving up, so one of the two wins. A clean-up after the publish is not enough: a kill between the two leaves the
 * key on the phone. The stand-in below takes the call with or without that third argument, so this compiles before the fix.
 *
 * WHY IT FAILS TODAY: stage S1B_WRITING is passed once, before the write starts, and nothing is asked again; the kept file
 * is removed by notStarted() and then published by the late writer.
 */
class AKeepWriterThatOutlivesAResetPublishesNothingTest {
    private val reset = ResetDriver().apply { seedPhraseStore() }
    private val admitted = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val publishes = AtomicInteger(0)
    private val kept: File get() = File(reset.phone.dataDir, "shared_prefs/zchat_set_aside_${reset.tag}.xml")

    @After
    fun tearDown() {
        release.countDown()
        reset.close()
    }

    /** The kept file on disk: its write stops before the publish until [release], when [hold]. */
    private fun bundles(hold: Boolean): SetAsideBundles =
        ResetDriver.standIn { name, args ->
            when (name) {
                "write" -> {
                    admitted.countDown()
                    if (hold) release.await()

                    @Suppress("UNCHECKED_CAST")
                    val mayPublish = args.getOrNull(2) as? Function0<Boolean>
                    if (mayPublish == null || mayPublish()) {
                        publishes.incrementAndGet()
                        kept.parentFile?.mkdirs()
                        kept.writeText("<map/>")
                    }
                    finished.countDown()
                    Unit
                }
                "delete" -> {
                    kept.delete()
                    Unit
                }
                "read" -> null
                else -> throw AssertionError("the Reset called $name on the bundles")
            }
        }

    private fun useBundles(hold: Boolean) {
        val useCase = reset.useCase
        val keep =
            ResetDriver.access(useCase.javaClass.getDeclaredField("aiResetKeep")).get(useCase) as AiResetKeep
        ResetDriver.access(AiResetKeep::class.java.getDeclaredField("bundles")).set(keep, bundles(hold))
    }

    private fun abortedReset() {
        useBundles(hold = true)
        assertEquals("NOT_STARTED", runBlocking { reset.reset(keepFiles = false) })
        assertTrue("fixture: the writer was never admitted", admitted.await(0, TimeUnit.SECONDS))
    }

    private fun lateWriterEnds() {
        release.countDown()
        assertTrue("fixture: the late writer did not end", finished.await(WAIT_S, TimeUnit.SECONDS))
    }

    @Test
    fun `a writer that outlives the aborted Reset publishes nothing`() {
        abortedReset()

        lateWriterEnds()

        assertEquals("the late writer published the key", 0, publishes.get())
        assertFalse("the kept file stands after a Reset that did not start", kept.exists())
    }

    @Test
    fun `Destroy All after the aborted Reset - the late writer brings no key back`() {
        abortedReset()
        DestroyWipe.sweepFiles(reset.phone.context)
        assertFalse("fixture: the sweep left the kept file", kept.exists())

        lateWriterEnds()

        assertFalse("a key to AI credit is on the phone after Destroy All", kept.exists())
        assertEquals("the late writer published the key", 0, publishes.get())
    }

    // GUARD, passes before and after the fix: a writer in time still publishes, once, and the record says so.
    @Test
    fun `a writer in time publishes once and the Reset goes on with keep=ai-key`() {
        useBundles(hold = false)

        assertEquals("STARTED", runBlocking { reset.reset(keepFiles = false) })

        assertEquals(1, publishes.get())
        assertTrue(kept.exists())
        assertEquals("ai-key", reset.recordLines()["keep"])
    }

    @Test
    fun `source - the real bundle asks mayPublish before its commit`() {
        val source = SourceScan.stripComments(File(repoRoot(), STORES).readText())
        val realClass = source.substringAfter("class EncryptedSetAsideBundles")
        val body = realClass.substringAfter("override fun write(").substringBefore("override fun read(")
        val asked = body.indexOf("mayPublish()")
        assertTrue("EncryptedSetAsideBundles.write asks nothing before its commit", asked >= 0 && asked < body.indexOf(".commit()"))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val WAIT_S = 5L
        const val STORES = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ChatWalletOwnershipStores.kt"
    }
}
