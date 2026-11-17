package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.walletstartup.RecordingPrefs
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ZC-707 (store-opener design, revision 2a, sections 2.1, 3.3 and 5.1).
 *
 * The rule "after three failed starts in a row, keep a copy and empty the store" was promised for three starts the
 * user met. [openEncryptedStore] counted every CALL instead: the chat storage is asked for again within one process
 * (the start-up screen's Try again, the 15-minute alarm, an incoming message), so one outage of the phone's Keystore
 * could use up all three counts while the user saw the safety screen once, and the third ask set the store aside.
 *
 * And a store that this process had already opened could be emptied by a later open of the same process (a lost-key
 * error is acted on at once), while the object handed out earlier was still in use: it would go on writing entries
 * locked with keys that are no longer in the file.
 *
 * Required:
 *  - a store's count of failed starts goes up at most once per process, per store; a background open never counts and
 *    does not use up the process's one count;
 *  - a store this process has already opened is never counted, copied, emptied or recreated by a later open in the
 *    same process, whatever the error says;
 *  - a lost key of a store NOT opened before in this process is still acted on at once;
 *  - the app's call sites pass the app's own environment, whose launch is the process's one.
 *
 * A process is modelled by [StoreOpenerApi.newProcess]; the files are the in-memory ones of the other opener tests.
 */
class StoreOpenFailureCountsOncePerProcessTest {
    private val storeName = "zchat_e2e_keys_encrypted"
    private val otherStoreName = "zchat_group_keys_encrypted"

    private val original: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }

        fun count(name: String): Any? = entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[name]
    }

    private fun phone(stores: List<String> = listOf(storeName)): Files =
        Files().apply { stores.forEach { (file(it) as RecordingPrefs).entries.putAll(original) } }

    private val keystoreOut: () -> SharedPreferences = { throw KeyStoreException("the master key exists but is unusable") }

    private fun ask(
        process: StoreOpenerApi.Process,
        files: Files,
        name: String = storeName,
        userVisible: Boolean = true,
        open: () -> SharedPreferences = keystoreOut,
    ) = runCatching { StoreOpenerApi.open(process, name, files::file, userVisible, open = open) }

    private fun assertStopped(
        outcome: Result<SharedPreferences>,
        what: String
    ) = assertTrue(
        outcome.exceptionOrNull() is EncryptedStoreUnavailableException,
        "$what: expected EncryptedStoreUnavailableException, got $outcome",
    )

    // (1)
    @Test
    fun `three failed opens in one process count once and leave the store as it is`() {
        val files = phone()
        val process = StoreOpenerApi.newProcess()

        repeat(3) { index -> assertStopped(ask(process, files), "ask ${index + 1}") }

        assertEquals(1, files.count(storeName), "three asks of one process counted as more than one failed start")
        assertEquals(emptyList(), files.copies(), "one process set the store aside")
        assertEquals(original, files.entries(storeName), "one process emptied the store")
    }

    // (2)
    @Test
    fun `the next process counts again and only the third process keeps a copy and recreates the store`() {
        val files = phone()

        val first = StoreOpenerApi.newProcess()
        repeat(2) { assertStopped(ask(first, files), "process 1") }
        assertEquals(1, files.count(storeName), "process 1")

        val second = StoreOpenerApi.newProcess()
        repeat(2) { assertStopped(ask(second, files), "process 2") }
        assertEquals(2, files.count(storeName), "process 2")
        assertEquals(emptyList(), files.copies(), "the store was set aside before the third failed start")
        assertEquals(original, files.entries(storeName))

        val recreated = RecordingPrefs()
        val third =
            ask(StoreOpenerApi.newProcess(), files) {
                if (files.entries(storeName).isNotEmpty()) throw KeyStoreException("still unusable") else recreated
            }

        assertSame(recreated, third.getOrNull(), "the third failed start must recreate the store, got $third")
        assertEquals(1, files.copies().size)
        assertEquals(original, files.entries(files.copies().single()), "the kept copy does not hold every entry")
        assertEquals(null, files.count(storeName), "a store that opened keeps no count")
    }

    // (3)
    @Test
    fun `two stores that fail in one process have one count each`() {
        val files = phone(listOf(storeName, otherStoreName))
        val process = StoreOpenerApi.newProcess()

        repeat(2) {
            assertStopped(ask(process, files, storeName), storeName)
            assertStopped(ask(process, files, otherStoreName), otherStoreName)
        }

        assertEquals(1, files.count(storeName))
        assertEquals(1, files.count(otherStoreName))
        assertEquals(emptyList(), files.copies())
    }

    // (4), a Keystore that does not answer
    @Test
    fun `a store this process opened is not counted when a later open of the same process fails`() {
        val files = phone()
        val process = StoreOpenerApi.newProcess()
        val live = RecordingPrefs()
        assertSame(live, ask(process, files) { live }.getOrNull(), "the first open")

        repeat(3) { index -> assertStopped(ask(process, files), "later ask ${index + 1}") }

        assertEquals(null, files.count(storeName), "a store that is open in this process was counted as a failed start")
        assertEquals(emptyList(), files.copies(), "a store that is open in this process was set aside")
        assertEquals(original, files.entries(storeName), "a store that is open in this process was emptied")
    }

    // (4), a lost key
    @Test
    fun `a store this process opened is never emptied by a later open of the same process that says the key is gone`() {
        val files = phone()
        val process = StoreOpenerApi.newProcess()
        val live = RecordingPrefs()
        assertSame(live, ask(process, files) { live }.getOrNull(), "the first open")

        val later =
            ask(process, files) {
                if (files.entries(storeName).isNotEmpty()) throw AEADBadTagException("tag mismatch") else RecordingPrefs()
            }

        assertStopped(later, "the later open")
        assertEquals(original, files.entries(storeName), "the store was emptied under the object handed out earlier")
        assertEquals(emptyList(), files.copies(), "the store was set aside under the object handed out earlier")
        assertEquals(null, files.count(storeName))
    }

    // (5) Guard: holds before and after the fix.
    @Test
    fun `a lost key at the first open of a process still keeps a copy and recreates the store at once`() {
        val files = phone()
        val recreated = RecordingPrefs()

        val outcome =
            ask(StoreOpenerApi.newProcess(), files) {
                if (files.entries(storeName).isNotEmpty()) throw AEADBadTagException("tag mismatch") else recreated
            }

        assertSame(recreated, outcome.getOrNull(), "got $outcome")
        assertEquals(original, files.entries(files.copies().single()))
        assertEquals(emptyMap(), files.entries(storeName))
    }

    // (6) Guard: holds before and after the fix.
    @Test
    fun `background opens count nothing and leave the process its one count`() {
        val files = phone()
        val process = StoreOpenerApi.newProcess()

        repeat(2) { assertStopped(ask(process, files, userVisible = false), "a background open") }
        assertEquals(emptyMap(), files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME), "a background open wrote a mark")
        assertEquals(original, files.entries(storeName))

        assertStopped(ask(process, files, userVisible = true), "the open the user sees")
        assertEquals(1, files.count(storeName), "the background opens used up the process's count")
        assertEquals(emptyList(), files.copies())
    }

    // (7)
    @Test
    fun `every call site of the app passes the environment of the app`() {
        val sources = StoreOpenerApi.mainSources()
        val problems = mutableListOf<String>()
        var openerCalls = 0
        var bundleCalls = 0
        sources.forEach { (path, code) ->
            StoreOpenerApi.calls(code, "openEncryptedStore").forEach { call ->
                openerCalls++
                val expected = if (call.enclosingFunction == "openSetAsideBundle") "env = env" else "env = appStoreOpenEnv("
                if (!call.arguments.contains(expected)) {
                    problems += "$path: openEncryptedStore( in ${call.enclosingFunction} has no \"$expected\""
                }
            }
            StoreOpenerApi.calls(code, "openSetAsideBundle").forEach { call ->
                bundleCalls++
                if (!call.arguments.contains("env = appStoreOpenEnv(")) {
                    problems += "$path: openSetAsideBundle( in ${call.enclosingFunction} has no \"env = appStoreOpenEnv(\""
                }
            }
        }
        assertTrue(openerCalls >= 2 && bundleCalls >= 1, "the scan found $openerCalls and $bundleCalls calls: it reads nothing")

        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        if (!Regex("""\bfun appStoreOpenEnv\(""").containsMatchIn(opener)) {
            problems += "EncryptedStoreOpener.kt has no appStoreOpenEnv("
        } else if (!StoreOpenerApi.declaration(opener, "appStoreOpenEnv").contains("launch = ChatStoreStart.launch")) {
            problems += "appStoreOpenEnv does not set launch = ChatStoreStart.launch"
        }
        val start = opener.substringAfter("object ChatStoreStart", "").substringBefore("\n}")
        if (!Regex("""\bval launch\b""").containsMatchIn(start)) problems += "ChatStoreStart.launch is not a val"

        assertEquals(emptyList(), problems)
    }

    // (8)
    @Test
    fun `the refused second count is logged with the fixed words the phone check counts`() {
        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        val body = StoreOpenerApi.declaration(opener, "openEncryptedStore")
        assertTrue(body.contains("countOnce("), "openEncryptedStore does not ask the launch whether this start was counted")

        val refusal = body.substringAfter("countOnce(").substringBefore("throw EncryptedStoreUnavailableException")
        assertTrue(
            refusal.contains("Log.w(") && refusal.contains("already counted in this start") && refusal.contains("\$name"),
            "the branch that refuses a second count has no warning with \"already counted in this start\" and the " +
                "store's name before it throws: <$refusal>",
        )
    }

    // (9) Guard: holds before and after the fix.
    @Test
    fun `the sync service builds no chat storage and calls no opener of its own`() {
        val service = StoreOpenerApi.mainSource("ui/service/SyncForegroundService.kt")

        listOf(
            "ZchatPreferencesImpl(",
            "openEncryptedStore(",
            "openSetAsideBundle(",
            "EncryptedSetAsideBundles(",
            "EncryptedSharedPreferences",
            "MasterKey",
        ).forEach { assertFalse(service.contains(it), "SyncForegroundService.kt holds $it") }
        assertTrue(
            Regex("""val zchatPreferences: ZchatPreferences by inject\(\)""").containsMatchIn(service),
            "SyncForegroundService.kt no longer has the chat storage by injection",
        )
    }

    // (10)
    @Test
    fun `a new process is for tests only`() {
        val sources = StoreOpenerApi.mainSources()
        val uses = sources.flatMap { (path, code) -> Regex("""\bforgetAll\(""").findAll(code).map { path } }
        assertEquals(
            listOf("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt"),
            uses,
            "forgetAll( must occur once under src/main, as its declaration",
        )
        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        val launch = opener.substringAfter("class StoreOpenLaunch", "").substringBefore("\n}")
        assertTrue(Regex("""internal fun forgetAll\(\)""").containsMatchIn(launch), "StoreOpenLaunch declares no internal forgetAll()")

        // What it does: the same launch object counts again, as a new process would.
        val files = phone()
        val process = StoreOpenerApi.newProcess()
        repeat(2) { assertStopped(ask(process, files), "before") }
        StoreOpenerApi.forgetAll(process)
        repeat(2) { assertStopped(ask(process, files), "after") }
        assertEquals(2, files.count(storeName))
        assertSame(StoreOpenerApi.appProcess().launch, StoreOpenerApi.appProcess().launch, "ChatStoreStart.launch is not one object")
    }
}
