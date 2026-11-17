package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.nostr.HealthRelay
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.nostr.NostrRelayPool
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * ZC-755 (register, high; also in 2.12.4): after Reset ZCHAT the next wallet's relay inbox must be its own.
 *
 * The relay inbox is a field of the sync service object and is started once for the life of that object, with the
 * relay identity of the wallet that was saved then. Reset never stopped the service, so until the process ended the
 * inbox kept listening and answering under the removed wallet's identity, also after a new wallet was set up.
 *
 * The order case is worded for both Resets: after the encrypted settings clear, the last of "stop request" and
 * "process end request" comes later than the clear, and nothing that could start the service again follows it.
 * With today's Reset that last event is the stop; a Reset that restarts ZCHAT ends with the process end request.
 *
 * ZC-090-R: the Reset runs whole on FakePhone ([ResetDriver]), with a recording context of this test's own around the
 * phone's: its stop and start requests, the encrypted settings clear and the restart (the process end request) are
 * written to [events]. A Reset that restarts ZCHAT clears no plain settings in the running process, so the plain
 * settings this class hands the driver are never cleared.
 */
class AfterAResetTheNextWalletHasItsOwnInboxTest {
    private val events = CopyOnWriteArrayList<String>()
    private val drivers = ArrayList<ResetDriver>()

    @After
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    @Test
    fun `a started Reset stops the sync service, later than the encrypted settings clear and as its last word`() =
        runBlocking {
            val useCase = bareUseCase(FakeSettings(PLAIN_CLEAR, events), FakeSettings(ENCRYPTED_CLEAR, events))

            runTheLastStepsOfAReset(useCase)

            assertTrue("the Reset did not clear the encrypted settings: $events", ENCRYPTED_CLEAR in events)
            val lastEnd = events.indexOfLast { it == STOP_REQUEST || it == PROCESS_END_REQUEST }
            assertTrue(
                "Reset never asked Android to stop the sync service (and no process end was requested): $events",
                lastEnd >= 0,
            )
            assertTrue(
                "the stop must come later than the encrypted settings clear, or a service started in between " +
                    "takes the removed wallet's relay identity again: $events",
                lastEnd > events.indexOf(ENCRYPTED_CLEAR),
            )
            assertEquals(
                "something that could start the sync service again follows the last stop: $events",
                emptyList<String>(),
                events.drop(lastEnd + 1).filter { it == START_REQUEST },
            )
        }

    @Test
    fun `after a Reset no object of this process can serve the next wallet`() =
        runBlocking {
            val useCase = bareUseCase(FakeSettings(PLAIN_CLEAR, events), FakeSettings(ENCRYPTED_CLEAR, events))

            runTheLastStepsOfAReset(useCase)

            assertTrue(
                "neither a stop of the sync service nor a process end was requested, so the service object and " +
                    "its relay inbox of the removed wallet live on for the next wallet: $events",
                events.any { it == STOP_REQUEST || it == PROCESS_END_REQUEST },
            )
        }

    @Test
    fun `a caller cancelled right after the record still sees the encrypted clear and the process end`() =
        runBlocking {
            val useCase = bareUseCase(FakeSettings(PLAIN_CLEAR, events), FakeSettings(ENCRYPTED_CLEAR, events))
            // The screen goes away right after the record (the folder sync that follows its rename), and with it the
            // scope the Reset was started on.
            val caller: Job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { runTheLastStepsOfAReset(useCase) }
            useCase.phone.killAt = { operation, file ->
                if (operation == "sync" && file.name == "no_backup") caller.cancel()
                false
            }
            caller.start()
            caller.join()
            val end = System.currentTimeMillis() + WAIT_MS
            while (PROCESS_END_REQUEST !in events && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)

            assertTrue("fixture: the record was written", useCase.record() != null)
            assertTrue("a plain settings clear ran in the living process: $events", PLAIN_CLEAR !in events)
            assertTrue(
                "the caller was cancelled after the record and the encrypted settings (the saved wallet) were never " +
                    "cleared: $events",
                ENCRYPTED_CLEAR in events,
            )
            assertTrue(
                "the caller was cancelled after the record and neither the stop nor the process end came: $events",
                events.any { it == STOP_REQUEST } && events.any { it == PROCESS_END_REQUEST },
            )
        }

    @Test
    fun `after the unlock prompt the Reset cannot be cancelled, its restart stands in a finally, its stop is Android's`() {
        val source = mainSource("screen/deletewallet/ResetZashiUseCase.kt")
        val invoke = source.substringAfter("operator fun invoke(", "")
        val prompt = invoke.indexOf("requestBiometrics(")
        val shield = Regex("""withContext\([^)]*NonCancellable""").find(invoke)?.range?.first ?: -1
        assertTrue("the Reset's body after the unlock prompt is not under NonCancellable", prompt >= 0 && shield > prompt)
        val finallyBlocks =
            Regex("""finally\s*\{""")
                .findAll(source)
                .map { bracedBlockFrom(source, it.range.last) }
                .toList()
        assertTrue(
            "no finally of ResetZashiUseCase asks for the restart (blocks found: ${finallyBlocks.size})",
            finallyBlocks.any { "appRestarter.restart()" in it },
        )
        assertTrue("the Reset does not stop the background work through DestroyWipe", "DestroyWipe.stopBackgroundWork(" in source)
        val wipe = mainRoot().resolve("screen/chat/util/DestroyWipe.kt").readText()
        val stop = bracedBlockAfter(wipe, "fun stopBackgroundWork(")
        assertTrue(
            "DestroyWipe.stopBackgroundWork does not ask Android to stop SyncForegroundService",
            "stopService(" in stop && "SyncForegroundService::class.java" in stop,
        )
    }

    @Test
    fun `guard - the inbox is a field of the service object and goes with it`() {
        val service = mainSource("service/SyncForegroundService.kt")
        val classBody = service.substringAfter("class SyncForegroundService")
        assertTrue(
            "the relay inbox is no longer a field of the service object",
            "    private val nostrInbox: NostrInboxManager =" in classBody,
        )
        val onDestroy = bracedBlockAfter(service, "override fun onDestroy() {")
        listOf(
            "nostrInbox.stop()",
            "unregisterPublisher()",
            "unregisterKeptResender()",
            "unregisterInboxRotater()",
            "unregisterInboxRefresher()",
            "unregisterRequestNotifier()",
            "unregisterChatNotifier()",
        ).forEach { call ->
            assertTrue("onDestroy of the sync service no longer calls $call", call in onDestroy)
        }
        // No process-wide object makes an inbox of its own: the service is the only maker.
        val makers =
            mainRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "NostrInboxManager.kt" }
                .filter { "NostrInboxManager(" in it.readText() }
                .map { it.name }
                .toList()
        assertEquals(listOf("SyncForegroundService.kt"), makers)
    }

    @Test
    fun `guard - a second inbox started for another wallet asks the relays for that wallet's key only`() =
        runBlocking {
            val relay = HealthRelay()
            val url = relay.start()
            val first = NostrInboxManager(NostrRelayPool(listOf(url)))
            val second = NostrInboxManager(NostrRelayPool(listOf(url)))
            try {
                val walletA = NOSTRIdentity.fromSeed(Random.nextBytes(SEED_BYTES))
                val walletB = NOSTRIdentity.fromSeed(Random.nextBytes(SEED_BYTES))
                first.start(walletA)
                assertTrue("the first inbox sent no REQ", HealthRelay.awaitTrue { relay.reqFrames.isNotEmpty() })
                first.stop()
                val before = relay.reqFrames.size

                second.start(walletB)
                assertTrue(
                    "the second inbox sent no REQ",
                    HealthRelay.awaitTrue { relay.reqFrames.size > before },
                )

                val asked =
                    relay.reqFrames
                        .drop(before)
                        .flatMap { frame ->
                            val keys = JSONArray(frame).getJSONObject(2).getJSONArray("#p")
                            (0 until keys.length()).map { keys.getString(it) }
                        }.toSet()
                assertEquals(setOf(hex(walletB.publicKey)), asked)
            } finally {
                runCatching { first.stop() }
                runCatching { second.stop() }
                relay.shutdown()
            }
        }

    // ------------------------------------------------------------------------------------------ the driver

    /** A started Reset, whole, with the box ticked: what this class is about is how it ends. */
    private suspend fun runTheLastStepsOfAReset(useCase: ResetDriver) {
        useCase.reset(keepFiles = true)
    }

    /** The Reset on a phone of its own; [plain] is never cleared by a Reset that restarts ZCHAT, [encrypted] is. */
    @Suppress("UNUSED_PARAMETER")
    private fun bareUseCase(
        plain: FakeSettings,
        encrypted: FakeSettings,
    ): ResetDriver {
        val driver = ResetDriver().also { drivers += it }
        driver.seedPhraseStore()
        driver.context = RecordingContext(events, driver.phone.context)
        driver.encryptedSettings = encrypted
        driver.onRestart = { events += PROCESS_END_REQUEST }
        return driver
    }

    // ------------------------------------------------------------------------------------------ the source

    private fun mainRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .take(PARENTS_TO_TRY)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .map { it.resolve("src/main/java/co/electriccoin/zcash/ui") }
            .first { it.isDirectory }
    }

    private fun mainSource(path: String): String = mainRoot().resolve(path).readText()

    /** The `{ … }` block that starts at the brace ending [opening]. */
    private fun bracedBlockAfter(
        source: String,
        opening: String,
    ): String {
        val at = source.indexOf(opening)
        assertTrue("not found in the source: $opening", at >= 0)
        return bracedBlockFrom(source, source.indexOf('{', at))
    }

    private fun bracedBlockFrom(
        source: String,
        open: Int,
    ): String {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        return source.substring(open)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val PLAIN_CLEAR = "plain settings clear"
        const val ENCRYPTED_CLEAR = "encrypted settings clear"
        const val STOP_REQUEST = "stop request"
        const val START_REQUEST = "start request"

        // Recorded by a Reset that ends the process: its restart request.
        const val PROCESS_END_REQUEST = "process end request"
        const val SEED_BYTES = 64
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
        const val PARENTS_TO_TRY = 8
    }

    /** The test's own recording context: it records what Android is asked about services, and does nothing. */
    private class RecordingContext(
        private val events: MutableList<String>,
        base: Context,
    ) : ContextWrapper(base) {
        // The JVM's ContextWrapper answers default values (unit tests return defaults), so what the Reset reads of the
        // phone is handed on to the phone's own context by hand.
        private val phone = base

        override fun getApplicationInfo(): ApplicationInfo = phone.applicationInfo

        override fun getPackageName(): String? = phone.packageName

        override fun getApplicationContext(): Context = this

        override fun stopService(name: Intent?): Boolean {
            events += STOP_REQUEST
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

    /** Settings that record their clear. Like the real ones, the clear is a point where a cancelled caller stops. */
    private class FakeSettings(
        private val name: String,
        private val events: MutableList<String>,
    ) : PreferenceProvider {
        var afterClear: () -> Unit = {}

        override suspend fun clearPreferences(): Boolean {
            yield()
            events += name
            afterClear()
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
