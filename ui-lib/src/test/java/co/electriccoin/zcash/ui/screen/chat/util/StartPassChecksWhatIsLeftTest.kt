package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.chat.datasource.PausedChatRestores
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheck
import co.electriccoin.zcash.ui.screen.walletstartup.gatedSecretState
import co.electriccoin.zcash.ui.screen.walletstartup.isStartupStillLoading
import co.electriccoin.zcash.ui.screen.walletstartup.startupFailureCause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/**
 * ZC-692-S (register ZC-692, high; owner rule W-41): "finished" is checked, and only what could NOT be removed is
 * shown.
 *
 * Before the fix nothing finished a cut-off Destroy All, so nothing checked it either, and the start-up gate had no
 * state for it.
 *
 * Wanted: on a valid record the start pass replays the cleanup of the wipe (the phrase store, the keys, other
 * processes, every file, the sync service, the alarm, the job, all work and its rows, every notification and channel,
 * the clipboard). Then it asks what is left: files by listing (empty folders are not leftovers), keys by the two
 * known names and by listing, active notifications, other processes by two lists. An error or no answer is never
 * read as "nothing left". All done: the outcome is FINISHED and the app starts as on a new phone and shows nothing.
 * Something left: UNFINISHED with the kinds; the start-up check maps it to a state of its own BEFORE it opens any
 * store, drawn by its own branch: a title, a body, one line per kind that is left, the action text and ONE button
 * that asks for the uninstall. That screen must show neither "Do not uninstall ZCHAT or clear its data." nor the
 * "Back up recovery phrase" button nor "Try again". A record that could not be removed gives RECORD_STUCK and the
 * blocking screen: no wallet can be created or restored in that process.
 *
 * Every new name is reached by name or by reflection, so this file compiles before the fix.
 */
class StartPassChecksWhatIsLeftTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(180)

    private val record = "data/no_backup/zchat_destroy_pending"
    private val wiped = FakePhone.STAYS.sorted()

    private fun FakePhone.start() {
        wipe("atProcessStart", context)
    }

    private fun FakePhone.outcome(): Any = wipeField("startOutcome")!!

    private fun kindsOf(outcome: Any): Any? = outcome.javaClass.getMethod("getKinds").invoke(outcome)

    private fun walletWithRecord(phone: FakePhone) {
        phone.seedEverything()
        phone.put(record, FakePhone.recordText(phone.installTime, FakePhone.storeHash("12a9aabbcc", "1288ddeeff")))
    }

    private fun assertUnfinished(
        why: String,
        kinds: Set<String>,
        phone: FakePhone
    ) {
        val outcome = phone.outcome()
        assertEquals(why, "Unfinished", outcome.javaClass.simpleName)
        assertEquals(why, kinds, kindsOf(outcome))
        assertFalse("$why: the record is gone whatever the check says", phone.recordFile.exists())
    }

    // ------------------------------------------------------------------ the pass and its check

    @Test
    fun `on a valid record the pass replays the cleanup of the wipe`() =
        FakePhone().use { phone ->
            walletWithRecord(phone)
            phone.keys += "a_key_nobody_named"
            phone.channels += "a_channel_a_later_version_added"
            phone.channelGroups += "a_group"
            phone.activeNotifications = 3
            phone.procList += Triple(4100, phone.ownUid, "xyz.example.zchat:crash")
            phone.androidList += Triple(4100, phone.ownUid, "xyz.example.zchat:crash")

            phone.start()

            val events = phone.events
            for (call in listOf(
                "stopService",
                "job.cancel",
                "alarm.cancel",
                "work.cancelAll",
                "work.prune",
                "notifications.cancelAll",
                "clipboard.clear",
                "kill:4100",
                "keystore.delete:_androidx_security_master_key_",
                "keystore.delete:zchat_ai_master_key",
                "keystore.delete:a_key_nobody_named",
                "channelGroup.delete:a_group",
            )) {
                assertTrue("the pass makes the call $call; events=$events", call in events)
            }
            assertEquals("no channel is left", emptyList<String>(), phone.channels.toList())
            assertTrue(
                "the sync service is stopped before a channel is deleted",
                events.indexOf("stopService") in 0 until events.indexOfFirst { it.startsWith("channel.delete:") }
            )
            assertTrue("all work is cancelled before its rows are cleared", events.indexOf("work.cancelAll") < events.indexOf("work.prune"))
            assertTrue(
                "the phrase store is the first thing deleted",
                events.first { it.startsWith("delete:") } == "delete:" + FakePhone.PHRASE_STORE
            )
            assertFalse("the pass never starts the sync service", "startService" in events)
            assertEquals(emptySet<String>(), phone.keys.toSet())
            assertEquals(0, phone.activeNotifications)
            assertEquals(wiped, phone.files())
            assertEquals("Finished", phone.outcome().javaClass.simpleName)
            assertFalse("the main process is not ended", phone.processEndRequested)
        }

    @Test
    fun `all done - FINISHED, the record is gone, and the check asked every question`() =
        FakePhone().use { phone ->
            walletWithRecord(phone)

            phone.start()

            assertEquals("Finished", phone.outcome().javaClass.simpleName)
            assertFalse(phone.recordFile.exists())
            val events = phone.events
            val lastDelete = events.indexOfLast { it.startsWith("delete:") && it != "delete:$record" }
            val questions =
                listOf(
                    "keystore.contains:_androidx_security_master_key_",
                    "keystore.contains:zchat_ai_master_key",
                    "notifications.active",
                )
            for (question in questions) {
                assertTrue("the check asks $question after the deletes; events=$events", events.lastIndexOf(question) > lastDelete)
            }
            assertTrue("the keys are listed after the deletes", events.lastIndexOf("keystore.list") > lastDelete)
            assertTrue("the phone's own process list is read after the deletes", events.lastIndexOf("proc.list") > lastDelete)
            assertTrue("Android's process list is read after the deletes", events.lastIndexOf("android.list") > lastDelete)
            assertTrue("the record goes after the check", events.indexOf("delete:$record") > events.lastIndexOf("notifications.active"))
            val recordFolder = "sync:data/no_backup"
            val recordDelete = events.indexOf("delete:$record")
            assertTrue("the record's folder is synced before its delete", events.indexOf(recordFolder) in 0 until recordDelete)
            assertTrue("and again after it", events.lastIndexOf(recordFolder) > recordDelete)
        }

    @Test
    fun `empty folders are not leftovers - FINISHED`() =
        FakePhone().use { phone ->
            // What Android keeps and makes again, and what a library made before the app's start: folders only, and
            // the job library's own files.
            for (folder in listOf(
                "data/cache",
                "data/code_cache",
                "data/files",
                "data/shared_prefs",
                "data/databases",
                "data/no_backup",
                "de/cache",
                "de/code_cache",
            )) {
                File(phone.root, folder).mkdirs()
            }
            FakePhone.STAYS.forEach { phone.put(it) }
            phone.put(record, FakePhone.recordText(phone.installTime, "none"))
            // Folders the pass cannot delete either: they still are not files.
            phone.refuseDelete = { it.isDirectory }

            phone.start()

            assertEquals("Finished", phone.outcome().javaClass.simpleName)
            assertFalse(phone.recordFile.exists())
            assertTrue("the empty folders may stay", File(phone.root, "de/code_cache").isDirectory)
            assertEquals(emptySet<Any>(), phone.wipe("check", phone.context))
        }

    @Test
    fun `a key is left - UNFINISHED with keys`() {
        FakePhone().use { phone ->
            walletWithRecord(phone)
            phone.failAt += "keystore.delete"
            phone.start()
            assertUnfinished("the two known keys could not be deleted", setOf("keys"), phone)
            assertEquals("everything else went", wiped, phone.files())
        }
        FakePhone().use { phone ->
            walletWithRecord(phone)
            phone.keys.clear()
            phone.keys += "a_key_nobody_named"
            phone.failAt += "keystore.delete"
            phone.start()
            assertUnfinished("a key only the listing shows", setOf("keys"), phone)
        }
    }

    @Test
    fun `the Keystore does not answer - keys too, an error is not none`() {
        val faults =
            mapOf<String, (FakePhone) -> Unit>(
                "the listing throws" to { it.failAt += "keystore.list" },
                "the listing throws an Error" to { it.errorAt += "keystore.list" },
                "the question for a name throws" to { it.failAt += "keystore.contains" },
                "the listing never answers" to { it.hangAt += "keystore.list" },
                "the question for a name never answers" to { it.hangAt += "keystore.contains" },
            )
        for ((what, fault) in faults) {
            FakePhone().use { phone ->
                walletWithRecord(phone)
                phone.keys.clear()
                fault(phone)
                phone.start()
                assertUnfinished(what, setOf("keys"), phone)
            }
        }
    }

    @Test
    fun `a notification is still active, or the question fails - notifications`() {
        val faults =
            mapOf<String, (FakePhone) -> Unit>(
                "one stays on screen" to {
                    it.activeNotifications = 1
                    it.stubbornNotifications = true
                },
                "the question throws" to { it.failAt += "notifications.active" },
                "the question throws an Error" to { it.errorAt += "notifications.active" },
                "the question never answers" to { it.hangAt += "notifications.active" },
            )
        for ((what, fault) in faults) {
            FakePhone().use { phone ->
                walletWithRecord(phone)
                fault(phone)
                phone.start()
                assertUnfinished(what, setOf("notifications"), phone)
            }
        }
    }

    @Test
    fun `another process in one list only, or neither list answering - process`() {
        val other = Triple(4100, 10_123, "xyz.example.zchat:crash")
        val faults =
            mapOf<String, (FakePhone) -> Unit>(
                "the phone's own list still shows one" to {
                    it.procList += other
                    it.unkillable += 4100
                    it.failAt += "android.list"
                },
                "only Android's list shows one" to {
                    it.androidList += other
                    it.unkillable += 4100
                },
                "neither list answers" to { it.failAt += listOf("proc.list", "android.list") },
                "one throws and the other never answers" to {
                    it.failAt += "proc.list"
                    it.hangAt += "android.list"
                },
            )
        for ((what, fault) in faults) {
            FakePhone().use { phone ->
                walletWithRecord(phone)
                fault(phone)
                phone.start()
                assertUnfinished(what, setOf("process"), phone)
            }
        }
        FakePhone().use { phone ->
            // One list answering "none" is an answer.
            walletWithRecord(phone)
            phone.failAt += "android.list"
            phone.start()
            assertEquals("one list that answers is enough", "Finished", phone.outcome().javaClass.simpleName)
        }
        FakePhone().use { phone ->
            // A process of another app, or of another user, is not ours.
            walletWithRecord(phone)
            phone.procList += Triple(4200, phone.ownUid, "xyz.example.zchat.other")
            phone.androidList += Triple(4300, phone.ownUid + 1, "xyz.example.zchat:crash")
            phone.start()
            assertEquals("Finished", phone.outcome().javaClass.simpleName)
            assertFalse("kill:4200" in phone.events || "kill:4300" in phone.events)
        }
    }

    @Test
    fun `several kinds are left - each is named`() =
        FakePhone().use { phone ->
            walletWithRecord(phone)
            val stubborn = File(phone.root, "data/files/metadata/metadata_1")
            phone.refuseDelete = { it == stubborn }
            phone.failAt += listOf("keystore.delete", "notifications.active")
            phone.start()
            assertUnfinished("files, keys and notifications", setOf("files", "keys", "notifications"), phone)
        }

    @Test
    fun `a folder that cannot be listed is not read as empty`() =
        FakePhone().use { phone ->
            phone.put("data/files/x")
            val locked = File(phone.root, "data/files")
            assertEquals("fixture: one file", listOf("data/files/x"), phone.files())
            try {
                assertTrue("fixture: the folder can be made unlistable", locked.setReadable(false))
                if (locked.listFiles() == null) {
                    @Suppress("UNCHECKED_CAST")
                    val kinds = phone.wipe("check", phone.context) as Set<String>
                    assertTrue("a listing that fails names files: $kinds", "files" in kinds)
                }
            } finally {
                locked.setReadable(true)
            }
        }

    // ------------------------------------------------------------------ the start-up gate

    /** The storage check, built by reflection with [outcome] as what the start made of a record. */
    private fun gate(
        outcome: Any,
        opened: MutableList<String>
    ): StartupStorageCheck {
        val constructors = StartupStorageCheck::class.java.constructors.filterNot { it.isSynthetic }
        val constructor = constructors.maxByOrNull { it.parameterCount }!!
        assertEquals(
            "StartupStorageCheck takes what the start made of a record as a seventh, defaulted parameter",
            7,
            constructor.parameterCount
        )
        val openSeedStore: suspend () -> Unit = { opened += "seed" }
        val openChatStores: () -> Unit = { opened += "chats" }
        val none: () -> List<String> = { emptyList() }
        val paused: () -> PausedChatRestores = { PausedChatRestores.NONE }
        val startOutcome: () -> Any = { outcome }
        val arguments = arrayOf(openSeedStore, openChatStores, Dispatchers.Unconfined, none, none, paused, startOutcome)
        return constructor.newInstance(*arguments) as StartupStorageCheck
    }

    private fun stateFor(
        outcome: Any,
        opened: MutableList<String> = ArrayList()
    ): StartupStorageCheck.State {
        val check = gate(outcome, opened)
        runBlocking { check.run() }
        return check.state.value
    }

    private fun outcomeNamed(name: String): Any {
        val outcome = Class.forName(FakePhone.WIPE_CLASS + "\$StartOutcome\$" + name)
        return outcome.getField("INSTANCE").get(null)!!
    }

    private fun unfinished(vararg kinds: String): Any =
        Class.forName(FakePhone.WIPE_CLASS + "\$StartOutcome\$Unfinished").getConstructor(Set::class.java).newInstance(kinds.toSet())

    /** `destroyStartContent(state)` of the start-up screen's file, or null; fails when the function is missing. */
    private fun contentFor(state: StartupStorageCheck.State): Any? {
        val file = Class.forName("co.electriccoin.zcash.ui.screen.walletstartup.WalletStartupFailedScreenKt")
        val function =
            file.methods.firstOrNull { it.name == "destroyStartContent" && it.parameterCount == 1 }
                ?: throw AssertionError("the start-up screen has no function destroyStartContent(state)")
        return function.invoke(null, state)
    }

    private fun Any.read(name: String): Any? = javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(this)

    private fun id(name: String): Int = Class.forName("co.electriccoin.zcash.ui.R\$string").getField(name).getInt(null)

    @Test
    fun `UNFINISHED and RECORD_STUCK become states of their own before any store is opened`() {
        val opened = ArrayList<String>()
        val unfinishedState = stateFor(unfinished("files", "keys"), opened)
        assertEquals("DestroyUnfinished", unfinishedState.javaClass.simpleName)
        assertEquals(setOf("files", "keys"), unfinishedState.read("kinds"))
        assertEquals("no store was opened", emptyList<String>(), opened)

        val stuckState = stateFor(outcomeNamed("RecordStuck"), opened)
        assertEquals("DestroyRecordStuck", stuckState.javaClass.simpleName)
        assertEquals("no store was opened", emptyList<String>(), opened)

        for (state in listOf(unfinishedState, stuckState)) {
            assertNotNull("the start-up screen is shown for $state", startupFailureCause(null, state))
            assertFalse("the splash does not stay over it", isStartupStillLoading(SecretState.NONE, state))
            for (secret in SecretState.entries) {
                assertEquals(
                    "no wallet can be created, restored or opened in this process ($secret)",
                    SecretState.LOADING,
                    gatedSecretState(secret, state)
                )
            }
        }
    }

    @Test
    fun `IGNORED, FINISHED and NONE show nothing - the check goes on to its ordinary states`() {
        for (name in listOf("None", "Finished", "Ignored")) {
            val opened = ArrayList<String>()
            val state = stateFor(outcomeNamed(name), opened)
            assertEquals("$name: the check goes on and opens the stores", listOf("seed", "chats"), opened)
            assertEquals(name, StartupStorageCheck.State.Ready, state)
            assertNull("$name: no screen", startupFailureCause(null, state))
            assertNull("$name: nothing of Destroy is shown", contentFor(state))
        }
        // The default of the new parameter is "no record": every existing caller of the constructor is as before.
        val opened = ArrayList<String>()
        val check =
            StartupStorageCheck(
                openSeedStore = { opened += "seed" },
                openChatStores = { opened += "chats" },
                ioDispatcher = Dispatchers.Unconfined
            )
        runBlocking { check.run() }
        assertEquals(StartupStorageCheck.State.Ready, check.state.value)
        assertEquals(listOf("seed", "chats"), opened)
    }

    @Test
    fun `the could-not-remove screen shows exactly the lines of the kinds that were left, and one button`() {
        val lineOf =
            mapOf(
                "files" to "destroy_unfinished_files",
                "keys" to "destroy_unfinished_keys",
                "notifications" to "destroy_unfinished_notifications",
                "process" to "destroy_unfinished_process",
            )
        val sets =
            listOf(
                setOf("files"),
                setOf("keys"),
                setOf("notifications"),
                setOf("process"),
                setOf("keys", "process"),
                setOf("files", "keys", "notifications", "process"),
            )
        for (kinds in sets) {
            val content = contentFor(stateFor(unfinished(*kinds.toTypedArray())))
            assertNotNull("a screen for $kinds", content)
            assertEquals(id("destroy_unfinished_title"), content!!.read("title"))
            assertEquals(id("destroy_unfinished_body"), content.read("body"))
            assertEquals(
                "exactly the lines of $kinds, each its own text, in the order files, keys, notifications, process",
                lineOf.filterKeys { it in kinds }.values.map { id(it) },
                content.read("lines")
            )
            assertEquals(id("destroy_unfinished_action"), content.read("action"))
            assertEquals("the one button asks for the uninstall", id("destroy_unfinished_button"), content.read("button"))
        }
    }

    @Test
    fun `the record-stuck screen has its own title and body, no lines, and the same one button`() {
        val content = contentFor(stateFor(outcomeNamed("RecordStuck")))
        assertNotNull(content)
        assertEquals(id("destroy_record_stuck_title"), content!!.read("title"))
        assertEquals(id("destroy_record_stuck_body"), content.read("body"))
        assertEquals(emptyList<Int>(), content.read("lines"))
        assertEquals(id("destroy_unfinished_action"), content.read("action"))
        assertEquals(id("destroy_unfinished_button"), content.read("button"))
    }

    // ------------------------------------------------------------------ source: their own branch, not the frame

    @Test
    fun `the two states are drawn by their own branch - no Do-not-uninstall line, no backup button, no Try again`() {
        val screen = DestroySources.code(SCREEN)
        val view = DestroySources.block(screen, "fun DestroyStartView(")
        val content = DestroySources.block(screen, "fun destroyStartContent(")
        assertNotNull("the start-up screen file has a view of its own for the two states: DestroyStartView", view)
        assertNotNull("and their content as a value: destroyStartContent", content)
        val forbidden =
            listOf(
                "wallet_startup_failed_do_not_uninstall",
                "wallet_startup_failed_backup_button",
                "onBackUpRecoveryPhrase",
                "wallet_startup_failed_try_again",
                "WalletStartupFailedView(",
                "walletStartupFailedContent(",
                "onTryAgain",
            )
        for (name in forbidden) {
            assertFalse("the view of the two states must not name $name", name in view!!)
            assertFalse("the content of the two states must not name $name", name in content!!)
        }
        assertEquals("the view has ONE button", 1, Regex("""ZashiButton\(""").findAll(view!!).count())
        assertTrue("and that button asks for the uninstall", Regex("""onClick\s*=\s*onUninstall""").containsMatchIn(view))

        val entry = DestroySources.block(screen, "fun WalletStartupFailedScreen()").orEmpty()
        val branch = entry.indexOf("destroyStartContent(storage)")
        val leave = entry.indexOf("return", branch)
        val frame = entry.indexOf("WalletStartupFailedView(")
        val offer = entry.indexOf("repair.offerFor(")
        assertTrue("the screen asks for the two states first", branch >= 0)
        assertTrue(
            "and leaves before the start-up screen's frame and its offers are reached",
            leave in (branch + 1) until minOf(frame, offer) && frame > 0 && offer > 0
        )
        val drawn = entry.substring(branch, leave)
        assertTrue("the branch draws DestroyStartView", "DestroyStartView(" in drawn)
        assertTrue("its button asks the phone to uninstall ZCHAT", "requestUninstall(" in drawn)
        val request = DestroySources.block(screen, "private fun requestUninstall(").orEmpty()
        assertTrue("requestUninstall opens the system's uninstall screen", "Intent.ACTION_DELETE" in request)
        assertTrue("for this package", "package:" in request)

        // The frame is unchanged: every existing state still shows its three fixed parts.
        val frameView = screen.substringAfter("fun WalletStartupFailedView(").substringBefore("@PreviewScreens")
        for (name in listOf("wallet_startup_failed_do_not_uninstall", "wallet_startup_failed_backup_button", "onBackUpRecoveryPhrase")) {
            assertTrue("the frame of the existing states still names $name", name in frameView)
        }
    }

    @Test
    fun `the gate reads the outcome first and the app wires the real one`() {
        val check = DestroySources.code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/walletstartup/StartupStorageCheck.kt")
        val open = DestroySources.block(check, "private suspend fun openStores()").orEmpty()
        assertTrue("fixture: openStores", "openSeedStore()" in open)
        val asked = open.indexOf("destroyStartOutcome()")
        assertTrue("the outcome is read before the first store is opened", asked in 0 until open.indexOf("openSeedStore()"))
        val module = DestroySources.code("ui-lib/src/main/java/co/electriccoin/zcash/di/UseCaseModule.kt")
        assertTrue(
            "the dependency graph gives the check DestroyWipe's start outcome",
            Regex("""destroyStartOutcome\s*=\s*\{\s*DestroyWipe\.startOutcome\s*\}""").containsMatchIn(module)
        )
    }

    // ------------------------------------------------------------------ the texts

    @Test
    fun `the ten texts exist in English and Spanish, differ, and have no placeholder`() {
        val english = ChatResourceText.strings("values")
        val spanish = ChatResourceText.strings("values-es")
        val expected =
            mapOf(
                "destroy_unfinished_title" to "Destroy All could not remove everything",
                "destroy_unfinished_body" to "ZCHAT finished Destroy All as far as it could. Still on this phone:",
                "destroy_unfinished_files" to "Files in ZCHAT's own storage",
                "destroy_unfinished_keys" to
                    "ZCHAT's keys in the phone's security system, or the phone's security system did not answer",
                "destroy_unfinished_notifications" to "ZCHAT notifications",
                "destroy_unfinished_process" to "A ZCHAT process that is still running",
                // ZC-888: background jobs still pending after the pass are named too.
                "destroy_unfinished_jobs" to "ZCHAT background tasks that are still waiting",
                "destroy_unfinished_action" to
                    "Uninstall ZCHAT now. Uninstalling removes ZCHAT together with what is left in its own storage. " +
                    "If no uninstall screen appears, uninstall ZCHAT in your phone's Settings → Apps.",
                "destroy_unfinished_button" to "Uninstall ZCHAT",
                "destroy_record_stuck_title" to "ZCHAT cannot set up a wallet here right now",
                "destroy_record_stuck_body" to
                    "Destroy All ran on this phone, and ZCHAT cannot clear its record of that because the phone's storage " +
                    "did not let it. To use ZCHAT again, uninstall it and install it again.",
            )
        for ((key, text) in expected) {
            assertEquals("English $key", text, english[key])
            val translated = spanish[key]
            assertTrue("Spanish $key exists", !translated.isNullOrBlank())
            assertFalse("Spanish $key is not the English text", translated == text)
            for (value in listOf(text, translated!!)) {
                assertFalse("$key has no placeholder: $value", "%" in value)
            }
        }
        assertEquals(
            "exactly these keys start with destroy_unfinished_ or destroy_record_stuck_",
            expected.keys.sorted(),
            english.keys.filter { it.startsWith("destroy_unfinished_") || it.startsWith("destroy_record_stuck_") }.sorted()
        )
        assertEquals(
            expected.keys.sorted(),
            spanish.keys.filter { it.startsWith("destroy_unfinished_") || it.startsWith("destroy_record_stuck_") }.sorted()
        )
        // The Spanish screen tells the user to uninstall and where, as the English one does, and promises no more.
        assertTrue("Ajustes → Aplicaciones" in spanish.getValue("destroy_unfinished_action"))
        assertTrue("hasta donde pudo" in spanish.getValue("destroy_unfinished_body"))
        for (file in listOf("values", "values-es")) {
            val xml = File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui/common/$file/destroy_identity_strings.xml").readText()
            for (key in expected.keys) assertTrue("$key lives in $file/destroy_identity_strings.xml", "name=\"$key\"" in xml)
        }
    }

    private companion object {
        const val SCREEN = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/walletstartup/WalletStartupFailedScreen.kt"
    }
}
