package co.electriccoin.zcash.ui.screen.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ZC-718 follow-up (found by the full run of train/T7aR at 9b8a78e8c, 20 Oct 2026): AiTabVM's first step off the main
 * thread is AiImageStore.prepare(), the first read of the owner file (batch ZC-718). When that read throws, the error
 * leaves the view model's coroutine uncaught: on a phone the app stops, and in the JVM suite it was reported into a
 * later test (E2ESeedRecoveryProofTest, "uncaught exceptions before the test started"). The rule: the first read
 * never throws; the store answers from the flat folder until the owner can be read, and every later call reads again.
 */
class AiTabFirstReadNeverEscapesTest {
    @Test
    fun `the first read of the owner file that throws does not leave prepare`() {
        val store = AiImageStore(OwnerFileThrows())

        val thrown = runCatching { store.prepare() }.exceptionOrNull()

        assertTrue("prepare() let the owner file's error out: $thrown", thrown == null)
    }

    /** A context whose preferences cannot be read (the owner file), with a files folder of its own. */
    private class OwnerFileThrows : ContextWrapper(null) {
        private val files: File = Files.createTempDirectory("zchat-ai-first-read").toFile().apply { deleteOnExit() }

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = files

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences = throw SecurityException("synthetic: the owner file cannot be read")
    }
}
