package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.work.WorkIds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZC-955 (review of ZC-755, 13 Oct, finding 1): a Reset whose saved-wallet clear fails still stops the sync service,
 * and background sync stays held.
 *
 * ZC-090-R restates this class on the Reset that restarts ZCHAT ([test-change-ok: ZC-090-R ends the Reset with a
 * restart; the plain clear leaves the running process]; RED.md maps each case of before to its case here). The stop is
 * now asked BEFORE the saved wallet is cleared (step S5, before S6), the restart stands in a `finally` that covers
 * every step after the record, and the next start deletes the phrase store whatever S6 did (ZC-090-P's pass). So the
 * protection is asserted as: the stop was asked before S6; from the stop on, no start of the sync service is let
 * through in this process (with an intent or without), and the app cannot lift that hold; the process ends; and at
 * the next start, with the record still on the phone, the pass holds background sync before any service can act. A
 * failure after the record never answers "not started" and never returns before the restart is requested, so no
 * screen says the Reset did not happen while the wallet is being removed.
 *
 * Driven whole on FakePhone ([ResetDriver]) with a recording context of this test's own around the phone's.
 */
class AResetThatCannotClearTheSavedWalletStillStopsTheSyncServiceTest {
    private val events = CopyOnWriteArrayList<String>()
    private val drivers = ArrayList<ResetDriver>()

    @After
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    @Test
    fun `a saved-wallet clear that throws comes after the stop request, and the restart still ends the process`() =
        runBlocking {
            val reset = driver(walletClearFailure = IllegalStateException("synthetic: the key store does not answer"))

            val answer = runCatching { reset.reset(keepFiles = true) }.getOrNull()

            assertTrue("the saved-wallet clear was not tried: $events", ENCRYPTED_CLEAR in events)
            assertTrue("the stop was never asked: $events", STOP_REQUEST in events)
            assertTrue("the stop came after the clear: $events", events.indexOf(STOP_REQUEST) < events.indexOf(ENCRYPTED_CLEAR))
            assertEquals("the restart is not the last word: $events", PROCESS_END_REQUEST, events.last())
            assertEquals("a Reset past its record answered as if it had not begun", "STARTED", answer)
            assertTheSyncServiceStaysStopped(reset)
        }

    @Test
    fun `no plain settings clear in the living process - a throwing step after the record still ends in the stop and the restart`() =
        runBlocking {
            val source = File(repoRoot(), USE_CASE).readText()
            assertFalse("the Reset still clears the plain settings in the living process", "standardPreferenceProvider" in source)
            for (step in listOf(ResetDriver.STEP_OWNER_NONE, ResetDriver.STEP_FLEXA)) {
                events.clear()
                val reset = driver()
                reset.phone.failAt += step
                runCatching { reset.reset(keepFiles = false) }
                assertTrue("$step threw and the stop was never asked: $events", STOP_REQUEST in events)
                assertEquals("$step threw and the restart is not the last word: $events", PROCESS_END_REQUEST, events.last())
                assertTheSyncServiceStaysStopped(reset)
            }
        }

    @Test
    fun `a stop request that throws as well hides nothing - the clear, the hold and the restart still come`() =
        runBlocking {
            val reset = driver(walletClearFailure = IllegalStateException("synthetic: the key store does not answer"))
            (reset.context as RecordingContext).stopFailure = SecurityException("synthetic: the stop was refused")

            val answer = runCatching { reset.reset(keepFiles = true) }.getOrNull()

            assertTrue("the stop was never asked: $events", STOP_REQUEST in events)
            assertTrue("a refused stop skipped the saved-wallet clear: $events", ENCRYPTED_CLEAR in events)
            assertEquals("the restart is not the last word: $events", PROCESS_END_REQUEST, events.last())
            assertEquals("STARTED", answer)
            assertTheSyncServiceStaysStopped(reset)
        }

    @Test
    fun `a caller cancelled after the record still sees the stop, the clear that throws and the restart`() =
        runBlocking {
            val reset = driver(walletClearFailure = IllegalStateException("synthetic: the key store does not answer"))
            val caller: Job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { runCatching { reset.reset(keepFiles = true) } }
            reset.phone.killAt = { operation, file ->
                if (operation == "sync" && file.name == "no_backup") caller.cancel()
                false
            }
            caller.start()
            caller.join()
            val end = System.currentTimeMillis() + WAIT_MS
            while (PROCESS_END_REQUEST !in events && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)

            assertTrue("fixture: the record was written", reset.record() != null)
            assertTrue("the saved-wallet clear was never tried: $events", ENCRYPTED_CLEAR in events)
            assertTrue("the caller was cancelled and the stop was never asked: $events", STOP_REQUEST in events)
            assertTrue("the caller was cancelled and the restart never came: $events", PROCESS_END_REQUEST in events)
            assertTheSyncServiceStaysStopped(reset)
        }

    @Test
    fun `guard - a Reset whose steps all answer - one stop, the clear, the restart, and no error`() =
        runBlocking {
            val reset = driver()

            val answer = reset.reset(keepFiles = true)

            assertEquals("STARTED", answer)
            assertEquals(listOf(STOP_REQUEST, ENCRYPTED_CLEAR, PROCESS_END_REQUEST), events.toList())
        }

    @Test
    fun `the next start with the record still there - the pass holds background sync before any service acts`() =
        runBlocking {
            val reset = driver(walletClearFailure = IllegalStateException("synthetic: the key store does not answer"))
            runCatching { reset.reset(keepFiles = true) }
            assertTrue("fixture: the record is on the phone", reset.record() != null)

            reset.phone.newProcess()
            reset.resetState()
            assertEquals("fixture: a new process holds nothing yet", false, reset.phone.wipeField("backgroundSyncHeld"))
            reset.resetWipe("atProcessStart", reset.phone.context)

            assertEquals("the pass did not hold background sync", true, reset.phone.wipeField("backgroundSyncHeld"))
            assertEquals("Android's restart of the sticky service (no intent) would sync", true, reset.resetWipe("syncStartRefused", false))
        }

    // ------------------------------------------------------------------------------------------ the driver

    /**
     * After the Reset, in its own process: the stop was asked; a start of the sync service with an intent (the screens,
     * once a wallet is ready) or without one (Android's restart of a sticky service) is refused; background sync is
     * held, and the app's own call that turns it on for a wallet lets no start through while the Reset runs.
     */
    private fun assertTheSyncServiceStaysStopped(reset: ResetDriver) {
        assertTrue("the stop was never asked: $events", STOP_REQUEST in events)
        assertEquals("a start with an intent would sync after the stop", true, reset.resetWipe("syncStartRefused", true))
        assertEquals("a start without an intent would sync after the stop", true, reset.resetWipe("syncStartRefused", false))
        assertEquals("background sync is not held", true, reset.phone.wipeField("backgroundSyncHeld"))
        // The app's own call that turns sync on for a wallet (the screens, once a wallet is ready) lets nothing through.
        runCatching { WorkIds.enableBackgroundSynchronization(reset.phone.context) }
        assertEquals("after the app turned sync on, a start with an intent would sync", true, reset.resetWipe("syncStartRefused", true))
        assertEquals("after the app turned sync on, a start without an intent would sync", true, reset.resetWipe("syncStartRefused", false))
    }

    private fun driver(walletClearFailure: Exception? = null): ResetDriver {
        val driver = ResetDriver().also { drivers += it }
        driver.seedPhraseStore()
        driver.context = RecordingContext(events, driver.phone.context)
        driver.encryptedSettings = FakeSettings(ENCRYPTED_CLEAR, events, walletClearFailure)
        driver.onRestart = { events += PROCESS_END_REQUEST }
        return driver
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val USE_CASE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt"
        const val ENCRYPTED_CLEAR = "encrypted settings clear"
        const val STOP_REQUEST = "stop request"
        const val START_REQUEST = "start request"
        const val PROCESS_END_REQUEST = "process end request"
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
    }

    /** The test's own recording context around the phone's: it records what Android is asked about services. */
    private class RecordingContext(
        private val events: MutableList<String>,
        base: Context,
    ) : ContextWrapper(base) {
        // The JVM's ContextWrapper answers default values (unit tests return defaults), so what the Reset reads of the
        // phone is handed on to the phone's own context by hand.
        private val phone = base

        override fun getApplicationInfo(): ApplicationInfo = phone.applicationInfo

        override fun getPackageName(): String? = phone.packageName

        var stopFailure: RuntimeException? = null

        override fun getApplicationContext(): Context = this

        override fun stopService(name: Intent?): Boolean {
            events += STOP_REQUEST
            stopFailure?.let { throw it }
            return true
        }

        override fun startService(service: Intent?): ComponentName? {
            events += START_REQUEST
            return null
        }

        override fun startForegroundService(service: Intent?): ComponentName? {
            events += START_REQUEST
            return null
        }
    }

    /** Settings that record their clear and then, when told so, throw. */
    private class FakeSettings(
        private val name: String,
        private val events: MutableList<String>,
        private val failure: Exception? = null,
    ) : PreferenceProvider {
        override suspend fun clearPreferences(): Boolean {
            yield()
            events += name
            failure?.let { throw it }
            return true
        }

        override suspend fun hasKey(key: PreferenceKey): Boolean = false

        override suspend fun putString(
            key: PreferenceKey,
            value: String?,
        ) = Unit

        override suspend fun putStringSet(
            key: PreferenceKey,
            value: Set<String>?,
        ) = Unit

        override suspend fun putLong(
            key: PreferenceKey,
            value: Long?,
        ) = Unit

        override suspend fun getLong(key: PreferenceKey): Long? = null

        override suspend fun getString(key: PreferenceKey): String? = null

        override suspend fun getStringSet(key: PreferenceKey): Set<String>? = null

        override fun observe(key: PreferenceKey): Flow<String?> = emptyFlow()

        override suspend fun remove(key: PreferenceKey) = Unit
    }
}
