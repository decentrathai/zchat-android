package co.electriccoin.zcash.ui.screen.chat.util

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * FD-5 (fix-all 2.13.0, train T4b; fold SPEC "FD-5", R5-1): Destroy All must leave no store behind when it hands
 * over to the process kill.
 *
 * On the Fold (28 Sep) a destroyed install still had `shared_prefs/zchat_e2e_keys_encrypted.xml` after Destroy All,
 * and `co.electriccoin.zcash*.xml` came back in both runs. The wipe runs while the UI, the sync service and the
 * Nostr inbox are alive, and any SharedPreferences write after the file sweep (step 6) writes the cached map back
 * to disk. The sync service (START_STICKY), the 15-min exact alarm and WorkManager also bring the process back
 * after the kill. And `no_backup` (the SDK wallet database) is never swept, so a failed SDK delete leaves it.
 *
 * Pure JVM: DestroyManager is allocated without its constructor (sun.misc.Unsafe, same idiom as
 * ResetZashiWipeDispatcherTest); only `context` and `zchatPreferences` are set. Every other collaborator stays
 * null, so steps 1-3 and 5 throw inside their own try/catch and are skipped (step 3 failing is exactly the case
 * test 4 needs). `unitTests.isReturnDefaultValues = true` makes Intent, Uri, Log and Process.killProcess inert.
 *
 * The fake context points the app folders at separate subfolders of a temp dir D (never D itself: if filesDir
 * were D, step 9 would delete shared_prefs too and the late write below would fail for lack of a folder).
 * `startActivity` (the uninstall request) plays the late writer: it recreates `shared_prefs` and writes the e2e
 * store and a files-dir file back, as a live writer does after the sweep.
 *
 * Every path is built from the temp dir `root`. The outer field is not called `dataDir`: inside the fake context
 * (a ContextWrapper) that name resolves to the stub `getDataDir()`, which returns null, so `File(dataDir, ...)`
 * became a path relative to the working directory and wrote `ui-lib/shared_prefs/` into the repo. The fake also
 * answers `getDataDir()` with `root`, and [tearDown] fails the test if anything appeared in the working directory.
 */
class DestroyLeavesNoStoreBehindTest {
    private lateinit var root: File
    private val events = mutableListOf<String>()
    private val workingDirNames = listOf("shared_prefs", "databases", "files", "cache", "no_backup")
    private lateinit var workingDirBefore: List<String>

    private fun workingDirFolders() = workingDirNames.filter { File(it).exists() }

    @Before
    fun setUp() {
        workingDirBefore = workingDirFolders()
        root = Files.createTempDirectory("fd5-destroy").toFile()
        File(root, "shared_prefs").mkdirs()
        File(root, "shared_prefs/zchat_e2e_keys_encrypted.xml").writeText("<map/>\n")
        File(root, "shared_prefs/zchat_preferences.xml").writeText("<map/>\n")
        File(root, "cache").mkdirs()
        File(root, "files").mkdirs()
        File(root, "files/avatar.bin").writeText("x\n")
        File(root, "no_backup/co.electricoin.zcash").mkdirs()
        File(root, "no_backup/co.electricoin.zcash/zcash_sdk_mainnet_data.sqlite3").writeText("db\n")
        File(root, "no_backup/androidx.work.workdb").writeText("wm\n")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        assertEquals(
            "the test must write only under its temp dir, never into the working directory (${File("").absolutePath})",
            workingDirBefore,
            workingDirFolders()
        )
    }

    // ------------------------------------------------------------------ fakes

    private inner class FakeContext(
        private val lateWriter: Boolean,
        private val stopServiceThrows: Boolean,
    ) : ContextWrapper(null) {
        var startActivityCalled = false
        var stopServiceCalls = 0

        /** Threads that read the data dir AFTER the uninstall request (the final sweep). */
        val sweepThreadsAfterUninstall = mutableListOf<Thread>()

        override fun getApplicationInfo(): ApplicationInfo {
            if (startActivityCalled) sweepThreadsAfterUninstall += Thread.currentThread()
            return ApplicationInfo().apply { dataDir = root.path }
        }

        override fun getDataDir(): File = root

        override fun getCacheDir(): File = File(root, "cache")

        override fun getFilesDir(): File = File(root, "files")

        override fun getNoBackupFilesDir(): File = File(root, "no_backup")

        override fun getExternalCacheDir(): File? = null

        override fun getExternalFilesDir(type: String?): File? = null

        override fun databaseList(): Array<String> = emptyArray()

        override fun getPackageName(): String = "xyz.example.fd5"

        override fun getApplicationContext(): android.content.Context? = null

        override fun getSystemService(name: String): Any? = null

        override fun stopService(service: Intent?): Boolean {
            stopServiceCalls++
            events += "stopService"
            if (stopServiceThrows) throw SecurityException("FD-5 test: stopService refused")
            return true
        }

        override fun startService(service: Intent?): ComponentName? {
            events += "startService"
            return null
        }

        override fun startActivity(intent: Intent?) {
            startActivityCalled = true
            events += "startActivity"
            if (lateWriter) {
                // A live writer (a recomposition, the sync service, the Nostr inbox) flushing its cached map
                // after the file sweep: Android recreates the folder and the file.
                File(root, "shared_prefs").mkdirs()
                File(root, "shared_prefs/zchat_e2e_keys_encrypted.xml").writeText("<map/>\n")
                File(root, "files").mkdirs()
                File(root, "files/late_write.json").writeText("{}\n")
            }
        }
    }

    private fun fakeZchatPreferences(context: FakeContext): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "clearAll" -> {
                    events += "clearAll(stopServiceBefore=${context.stopServiceCalls > 0})"
                    null
                }
                "toString" -> "FakeZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    private fun allocateBare(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, clazz)!!
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any?
    ) = target.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .set(target, value)

    private fun runDestroy(
        lateWriter: Boolean,
        stopServiceThrows: Boolean = false
    ): FakeContext {
        val context = FakeContext(lateWriter, stopServiceThrows)
        val manager = allocateBare(DestroyManager::class.java) as DestroyManager
        setField(manager, "context", context)
        setField(manager, "zchatPreferences", fakeZchatPreferences(context))
        runBlocking { manager.destroyAll(requestUninstall = true) }
        return context
    }

    private fun filesUnder(dir: File): List<String> =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path }
            .toList()

    // ------------------------------------------------------------------ tests

    @Test
    fun `a store written back after the file sweep is gone when destroyAll hands over to the kill`() {
        val context = runDestroy(lateWriter = true)

        assertTrue("the uninstall request must have run (it plays the late writer)", context.startActivityCalled)
        assertFalse(
            "zchat_e2e_keys_encrypted.xml was written back after the sweep and is still on disk at the kill",
            File(root, "shared_prefs/zchat_e2e_keys_encrypted.xml").exists()
        )
        assertEquals("shared_prefs must hold no file at all", emptyList<String>(), filesUnder(File(root, "shared_prefs")))
        assertEquals("files must hold no file at all", emptyList<String>(), filesUnder(File(root, "files")))
    }

    @Test
    fun `background sync is stopped before the wipe starts`() {
        runDestroy(lateWriter = false)

        val clear = events.firstOrNull { it.startsWith("clearAll") }
        assertTrue("clearAll() must have run", clear != null)
        assertEquals(
            "SyncForegroundService must be stopped (context.stopService) before the stores are cleared; events=$events",
            "clearAll(stopServiceBefore=true)",
            clear
        )
        assertFalse("the destroy must never START the sync service; events=$events", events.contains("startService"))
    }

    /** Guard (green before the fix): stopping background work is best effort and can never block the wipe. */
    @Test
    fun `the wipe still runs when stopping background work throws`() {
        runDestroy(lateWriter = false, stopServiceThrows = true)

        assertTrue("clearAll() must run even when stopService throws; events=$events", events.any { it.startsWith("clearAll") })
        assertEquals("shared_prefs must be empty", emptyList<String>(), filesUnder(File(root, "shared_prefs")))
    }

    @Test
    fun `the wallet database is gone even when the SDK delete fails`() {
        // walletCoordinator is null: step 3 (deleteSdkDataFlow) throws, as a failed erase would.
        runDestroy(lateWriter = false)

        assertEquals(
            "no_backup/co.electricoin.zcash (the SDK wallet database: viewing keys, history, memos) must hold no file",
            emptyList<String>(),
            filesUnder(File(root, "no_backup/co.electricoin.zcash"))
        )
        assertTrue(
            "no_backup/androidx.work.workdb must stay: deleting a live WorkManager database risks a crash before the kill",
            File(root, "no_backup/androidx.work.workdb").exists()
        )
    }

    @Test
    fun `the final sweep after the uninstall request runs off the caller thread`() {
        val callerThread = Thread.currentThread()
        val context = runDestroy(lateWriter = true)

        assertTrue(
            "a final sweep must read the data dir after the uninstall request",
            context.sweepThreadsAfterUninstall.isNotEmpty()
        )
        assertTrue(
            "the final sweep must run on Dispatchers.IO, not on the caller (Main) thread",
            context.sweepThreadsAfterUninstall.none { it === callerThread }
        )
    }
}
