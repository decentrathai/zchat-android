package co.electriccoin.zcash.ui.screen.chat.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.lang.reflect.Modifier

/**
 * ZC-692-S (register ZC-692, high; owner rule W-41): a Destroy All that was cut off is finished at the next start of
 * the app, and only its own.
 *
 * Before the fix nothing read the record that Destroy All writes (batch ZC-692-W): a wipe that was killed half way
 * stayed half done, a file a late writer brought back stayed, and after a cancelled uninstall the record stayed on
 * the phone for good.
 *
 * Wanted (the walk-through of the Destroy spec, section 4.5, case by case): `DestroyWipe.atProcessStart(context)`,
 * the first statement of the app's start, acts on a record only when it is whole, belongs to this install and names
 * the phrase store that is on the phone (or none is left). Then it deletes once more, in a process that has opened
 * nothing, and removes the record whatever is left. A record that does not fit deletes NOTHING and is removed. With
 * no record nothing happens at all: a wipe is never inferred. Only the main process deletes; a start that cannot
 * judge the record keeps it and ends its process. It never throws.
 *
 * Every new name is reached by name or by reflection, so this file compiles before the fix.
 */
class StartPassFinishesOnlyItsOwnDestroyTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(180)

    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
    }

    private val record = "data/no_backup/zchat_destroy_pending"
    private val wiped = FakePhone.STAYS.sorted()
    private val ownStore = FakePhone.storeHash("12a9aabbcc", "1288ddeeff")

    private fun FakePhone.start() {
        wipe("atProcessStart", context)
    }

    private fun FakePhone.outcome(): String = wipeField("startOutcome")!!.javaClass.simpleName

    private fun FakePhone.held(): Boolean = wipeField("backgroundSyncHeld") as Boolean

    /**
     * Every file of the app's own storage with its bytes, without the record. ZC1014-X1: the mark of the offer to run
     * Destroy again (beside the record) is not the wallet's: left out like the record.
     */
    private fun FakePhone.snapshot(): Map<String, String> =
        files().filter { it != record && it != "data/no_backup/zchat_destroy_rerun_offer" }.associateWith {
            File(root, it).readBytes().joinToString(",")
        }

    private fun FakePhone.putRecord(
        install: Long = installTime,
        store: String = ownStore
    ): File = put(record, FakePhone.recordText(install, store))

    /** A healthy wallet with every kind of file, and a record written by hand for [install] and [store]. */
    private fun walletWithRecord(
        phone: FakePhone,
        install: Long = phone.installTime,
        store: String = ownStore
    ) {
        phone.seedEverything()
        phone.putRecord(install, store)
    }

    private fun assertNothingWasDeleted(
        phone: FakePhone,
        before: Map<String, String>
    ) {
        assertEquals("every file is byte for byte as before", before, phone.snapshot())
        assertEquals("no key was deleted", 2, phone.keys.size)
        assertFalse("no key call was made: ${phone.events}", phone.events.any { it.startsWith("keystore.") })
        assertFalse("no process was listed: ${phone.events}", phone.events.any { it.endsWith(".list") })
        assertFalse(
            "nothing but the record was handed to the delete call: ${phone.events}",
            phone.events.any { it.startsWith("delete:") && it != "delete:$record" }
        )
        assertFalse("nothing was cancelled: ${phone.events}", "notifications.cancelAll" in phone.events || "stopService" in phone.events)
    }

    // ------------------------------------------------------------------ no record: today's behaviour, to the letter

    @Test
    fun `no record - nothing is asked, nothing is deleted, the outcome is NONE`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val before = phone.snapshot()

            phone.start()

            assertEquals("not one call into the phone was made", emptyList<String>(), phone.events)
            assertEquals("a healthy wallet's files are byte for byte as before", before, phone.snapshot())
            assertEquals(2, phone.keys.size)
            assertEquals("None", phone.outcome())
            assertFalse("background sync is not held", phone.held())
            assertFalse(phone.processEndRequested)
        }

    @Test
    fun `no record and stores that cannot be read - they are left alone, a wipe is never inferred`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            // What a start meets after a phone-security fault, or after a wipe that could not write its record: a
            // phrase store without its entries, chat stores that are not even XML, no keys.
            phone.put(FakePhone.PHRASE_STORE, "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n")
            phone.put("data/shared_prefs/zchat_e2e_keys_encrypted.xml", "\u0000\u0001 not a store")
            phone.keys.clear()
            val before = phone.snapshot()

            phone.start()

            assertEquals(emptyList<String>(), phone.events)
            assertEquals(before, phone.snapshot())
            assertEquals("None", phone.outcome())
        }

    @Test
    fun `a temp file of a record that was never renamed is not a record - it is deleted and nothing else`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val before = phone.snapshot()
            phone.put("$record.tmp", FakePhone.recordText(phone.installTime, ownStore))

            phone.start()

            assertEquals("only the temp file went", before, phone.snapshot())
            assertEquals(listOf("delete:$record.tmp"), phone.events)
            assertEquals("None", phone.outcome())
            assertFalse(phone.held())
        }

    // ------------------------------------------------------------------ the wipe cut at each step, then a start

    private class Cut(
        val name: String,
        val arm: (FakePhone) -> Unit
    )

    private fun fileCut(
        name: String,
        at: (FakePhone, String, File) -> Boolean
    ) = Cut(name) { phone -> phone.killAt = { operation, file -> at(phone, operation, file) } }

    private val cuts =
        listOf(
            fileCut("W1 (the record is renamed, its folder not yet synced)") { phone, op, file ->
                op == "sync" && file == phone.recordFile.parentFile
            },
            fileCut("W1 (the record is whole, nothing deleted yet)") { phone, op, file -> op == "delete" && file == phone.phraseStore },
            fileCut("W2 (the phrase store is gone)") { phone, op, file -> op == "delete" && file == phone.phraseStoreBackup },
            Cut("W3 (the keys)") { it.dieAt += "keystore.delete" },
            Cut("W4 (other processes)") { it.dieAt += "proc.list" },
            fileCut("W5 (half of the files)") { phone, op, file ->
                op == "delete" && file == File(phone.root, "data/files/address_book/address_book_1")
            },
            Cut("W6 (the settings in memory)") { it.dieAt += "encrypted.clear" },
            Cut("W7 (background work and traces)") { it.dieAt += "stopService" },
            Cut("W7 (the notifications)") { it.dieAt += "notifications.cancelAll" },
            Cut("W8 (the chat stores in memory)") { it.dieAt += "clearAll" },
            Cut("W9 (Flexa)") { it.dieAt += "flexa.disconnect" },
            Cut("W10 (the uninstall request)") { it.dieAt += "startActivity" },
            Cut("W11 (the final pass)") { phone -> phone.onUninstallRequest = { phone.dieAt += "proc.list" } },
        )

    @Test
    fun `the wipe killed at each step from W1 to W11 - the next start in the main process finishes it`() {
        for (cut in cuts) {
            FakePhone().use { phone ->
                phone.seedEverything()
                cut.arm(phone)
                phone.destroy()
                assertTrue("fixture, ${cut.name}: the phone died during the wipe", phone.dead)
                assertTrue("fixture, ${cut.name}: the record is there at the cut", phone.recordFile.isFile)
                phone.killAt = null
                phone.dieAt.clear()
                phone.onUninstallRequest = {}

                phone.newProcess()
                phone.start()

                assertEquals("${cut.name}: only what may stay is left, and no record", wiped, phone.files())
                assertEquals("${cut.name}: no key", emptySet<String>(), phone.keys.toSet())
                assertEquals("${cut.name}: the outcome", "Finished", phone.outcome())
                assertTrue("${cut.name}: background sync is held", phone.held())
                assertFalse("${cut.name}: the main process is not ended", phone.processEndRequested)
            }
        }
    }

    @Test
    fun `the wipe killed before the record's rename - no record, so the next start deletes nothing`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val before = phone.snapshot()
            phone.killAt = { operation, file -> operation == "rename" && file == phone.recordFile }
            phone.destroy()
            phone.killAt = null

            phone.newProcess()
            phone.start()

            assertEquals("the wallet is as it was (the temp file is gone)", before, phone.snapshot())
            assertFalse(phone.recordFile.exists())
            assertEquals("None", phone.outcome())
        }

    @Test
    fun `Destroy ran to its end and the uninstall was cancelled - the next start removes the record and says nothing`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.destroy()
            assertEquals("fixture: what Destroy leaves", (wiped + record).sorted(), phone.files())

            phone.newProcess()
            phone.start()

            assertEquals(wiped, phone.files())
            assertEquals("Finished", phone.outcome())
        }

    @Test
    fun `the phrase store written back late with the same two entries - the record still fits and it is deleted`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.destroy()
            // A cached settings object flushing its map after the sweep: the same file, so the same two entries.
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml("12a9aabbcc", "1288ddeeff"))
            phone.put("data/shared_prefs/zchat_preferences.xml")
            phone.keys += "_androidx_security_master_key_"

            phone.newProcess()
            phone.start()

            assertEquals("the late files are gone", wiped, phone.files())
            assertEquals(emptySet<String>(), phone.keys.toSet())
            assertEquals("Finished", phone.outcome())
        }

    @Test
    fun `the phrase store's bak twin is the true file - a record made from it fits`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.put(FakePhone.PHRASE_STORE + ".bak", FakePhone.phraseStoreXml("12a9aabbcc", "1288ddeeff"))
            phone.put(FakePhone.PHRASE_STORE, "<map>half written")
            phone.putRecord()

            phone.start()

            assertEquals(wiped, phone.files())
            assertEquals("Finished", phone.outcome())
        }

    // ------------------------------------------------------------------ a record that does not fit deletes nothing

    @Test
    fun `another wallet - a phrase store with other entries - nothing is deleted, the record goes, IGNORED`() =
        FakePhone().use { phone ->
            walletWithRecord(phone, store = FakePhone.storeHash("another", "wallet"))
            val before = phone.snapshot()

            phone.start()

            assertNothingWasDeleted(phone, before)
            assertFalse("the record is gone", phone.recordFile.exists())
            assertEquals("Ignored", phone.outcome())
            assertFalse("background sync is not held: a wallet may be there", phone.held())
            assertFalse(phone.processEndRequested)
        }

    @Test
    fun `another install - nothing is deleted, the record goes, IGNORED`() =
        FakePhone().use { phone ->
            walletWithRecord(phone, install = phone.installTime + 1)
            val before = phone.snapshot()

            phone.start()

            assertNothingWasDeleted(phone, before)
            assertFalse(phone.recordFile.exists())
            assertEquals("Ignored", phone.outcome())
            assertFalse(phone.held())
        }

    @Test
    fun `a record with install=0 is never valid, and Android is not asked`() =
        FakePhone().use { phone ->
            walletWithRecord(phone, install = 0L)
            phone.installTime = 0L
            val before = phone.snapshot()

            phone.start()

            assertNothingWasDeleted(phone, before)
            assertFalse(phone.recordFile.exists())
            assertEquals("Ignored", phone.outcome())
            assertFalse("the install time is not asked for: ${phone.events}", "pm.firstInstallTime" in phone.events)
            assertFalse("the process is not ended over it", phone.processEndRequested)
        }

    @Test
    fun `a torn or edited record - nothing is deleted, the record goes, IGNORED`() {
        val whole = FakePhone.recordText(1_700_000_000_123L, ownStore)
        val lines = whole.trimEnd('\n').split("\n")
        val otherVersionBody = "zchat-destroy 2\n" + lines.subList(1, 4).joinToString("\n") + "\n"
        val withKeepBody = lines.subList(0, 3).joinToString("\n") + "\nkeep=contacts\n"
        val withKeep = withKeepBody + "end=" + FakePhone.sha256Hex(withKeepBody) + "\n"
        val torn =
            mapOf(
                "an empty file" to "",
                "a missing line" to (lines.subList(0, 3) + lines[4]).joinToString("\n") + "\n",
                "cut in the middle" to whole.take(whole.length / 2),
                "a wrong end" to lines.subList(0, 4).joinToString("\n") + "\nend=" + "0".repeat(64) + "\n",
                "another version, with an end that matches" to otherVersionBody + "end=" + FakePhone.sha256Hex(otherVersionBody) + "\n",
                "a keep line that is not empty, with an end that matches" to withKeep,
                "one line more" to whole + "more\n",
                "no last line feed" to whole.trimEnd('\n'),
                "other line ends" to whole.replace("\n", "\r\n"),
            )
        for ((what, text) in torn) {
            FakePhone().use { phone ->
                phone.seedEverything()
                phone.put(record, text)
                val before = phone.snapshot()

                phone.start()

                assertNothingWasDeleted(phone, before)
                assertFalse("$what: the record is gone", phone.recordFile.exists())
                assertEquals(what, "Ignored", phone.outcome())
                assertFalse(what, phone.held())
            }
        }
    }

    @Test
    fun `store=none - with a phrase store on the phone nothing is deleted, with none the pass runs`() {
        FakePhone().use { phone ->
            walletWithRecord(phone, store = "none")
            val before = phone.snapshot()

            phone.start()

            assertNothingWasDeleted(phone, before)
            assertFalse(phone.recordFile.exists())
            assertEquals("Ignored", phone.outcome())
        }
        FakePhone().use { phone ->
            walletWithRecord(phone, store = "none")
            phone.phraseStore.delete()

            phone.start()

            assertEquals(wiped, phone.files())
            assertEquals("Finished", phone.outcome())
        }
        FakePhone().use { phone ->
            // The bak twin alone is a phrase store too.
            walletWithRecord(phone, store = "none")
            phone.phraseStore.renameTo(phone.phraseStoreBackup)
            val before = phone.snapshot()

            phone.start()

            assertNothingWasDeleted(phone, before)
            assertEquals("Ignored", phone.outcome())
        }
    }

    // ------------------------------------------------------------------ a start that cannot judge keeps the record

    @Test
    fun `the start cannot judge - nothing is deleted, the record stays, the process ends, the next start judges`() {
        val faults =
            mapOf<String, (FakePhone) -> Unit>(
                "the install time question throws" to { it.failAt += "pm.firstInstallTime" },
                "the install time question throws an Error" to { it.errorAt += "pm.firstInstallTime" },
                "the install time question never answers" to { it.hangAt += "pm.firstInstallTime" },
                "the phone says install time 0" to { it.installTime = 0L },
                "the process name cannot be determined" to { it.processName = null },
                "the process name question throws an Error" to { it.errorAt += "process.name" },
            )
        for ((what, fault) in faults) {
            FakePhone().use { phone ->
                walletWithRecord(phone)
                val goodInstallTime = phone.installTime
                val recordBefore = phone.recordFile.readBytes().toList()
                val before = phone.snapshot()
                fault(phone)

                phone.start()

                assertNothingWasDeleted(phone, before)
                assertEquals("$what: the record is byte for byte as before", recordBefore, phone.recordFile.readBytes().toList())
                assertFalse("$what: the record was not handed to the delete call", "delete:$record" in phone.events)
                assertTrue("$what: the end of the process is requested", phone.processEndRequested)
                assertFalse("$what: background sync is not held", phone.held())
                assertEquals(what, "None", phone.outcome())

                if ("never answers" !in what) {
                    // A second start that gets its answers judges as usual.
                    phone.failAt.clear()
                    phone.errorAt.clear()
                    phone.installTime = goodInstallTime
                    phone.processName = phone.packageName
                    phone.newProcess()
                    phone.start()
                    assertEquals("$what: the next start finishes the wipe", wiped, phone.files())
                    assertEquals(what, "Finished", phone.outcome())
                }
            }
        }
    }

    // ------------------------------------------------------------------ only the main process deletes

    @Test
    fun `in the crash-report process a record deletes nothing and the process ends`() {
        for (name in listOf("xyz.example.zchat:crash", "xyz.example.zchat:other", "another.package")) {
            FakePhone().use { phone ->
                walletWithRecord(phone)
                val recordBefore = phone.recordFile.readBytes().toList()
                val before = phone.snapshot()
                phone.processName = name

                phone.start()

                assertNothingWasDeleted(phone, before)
                assertEquals("$name: the record stays for the main process", recordBefore, phone.recordFile.readBytes().toList())
                assertFalse("delete:$record" in phone.events)
                assertTrue("$name: the end of the process is requested", phone.processEndRequested)
                assertFalse("$name: the install time is not asked for", "pm.firstInstallTime" in phone.events)
                assertFalse(phone.held())
            }
        }
    }

    @Test
    fun `in the crash-report process with no record nothing happens and the process goes on`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.processName = "xyz.example.zchat:crash"

            phone.start()

            assertEquals(emptyList<String>(), phone.events)
            assertFalse(phone.processEndRequested)
        }

    // ------------------------------------------------------------------ the record never stays

    @Test
    fun `a file that cannot be deleted - the outcome names files and the record is gone after one pass`() =
        FakePhone().use { phone ->
            walletWithRecord(phone)
            val stubborn = File(phone.root, "data/files/metadata/metadata_1")
            phone.refuseDelete = { it == stubborn }

            phone.start()

            val left = (wiped + "data/files/metadata/metadata_1").sorted()
            assertEquals("only the stubborn file is left besides what may stay", left, phone.files())
            assertFalse("the record is gone whatever the check says", phone.recordFile.exists())
            val outcome = phone.wipeField("startOutcome")!!
            assertEquals("Unfinished", outcome.javaClass.simpleName)
            assertEquals(setOf("files"), outcome.javaClass.getMethod("getKinds").invoke(outcome))
            assertTrue(phone.held())
        }

    @Test
    fun `the record's own delete is refused after a valid pass - RECORD_STUCK, and it never fits another wallet`() =
        FakePhone().use { phone ->
            walletWithRecord(phone)
            phone.refuseDelete = { it == phone.recordFile }

            phone.start()

            assertEquals("the pass ran", (wiped + record).sorted(), phone.files())
            assertEquals("RecordStuck", phone.outcome())
            assertTrue("background sync is held", phone.held())

            // Later a wallet of another phrase exists (made in a start in which the record could be judged again):
            // the stuck record does not fit it.
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml("a-new", "wallet"))
            phone.put("data/shared_prefs/zchat_preferences.xml", "new chats\n")
            phone.keys += "_androidx_security_master_key_"
            val before = phone.snapshot()
            phone.newProcess()
            phone.start()

            assertEquals("nothing of the new wallet is deleted", before, phone.snapshot())
            assertEquals(setOf("_androidx_security_master_key_"), phone.keys.toSet())
            assertEquals("Ignored", phone.outcome())
            assertFalse(phone.held())
        }

    // ------------------------------------------------------------------ power cuts (the file layer loses what was not synced)

    @Test
    fun `a power cut around the record's rename - never a deleted phrase store without a record`() {
        val points =
            listOf<(FakePhone, String, File) -> Boolean>(
                { phone, op, file -> op == "rename" && file == phone.recordFile },
                { phone, op, file -> op == "sync" && file == phone.recordFile.parentFile },
                { phone, op, file -> op == "delete" && file == phone.phraseStore },
                { phone, op, file -> op == "delete" && file == phone.phraseStoreBackup },
                { phone, op, file -> op == "delete" && file == File(phone.root, "data/files/address_book/address_book_1") },
            )
        for ((index, point) in points.withIndex()) {
            FakePhone().use { phone ->
                phone.seedEverything()
                val before = phone.snapshot()
                phone.killAt = { operation, file -> point(phone, operation, file) }
                phone.destroy()
                phone.powerCut()
                phone.killAt = null

                val recordThere = phone.recordFile.isFile
                val phraseThere = phone.phraseStore.isFile
                assertTrue("cut $index: never a deleted phrase store without a record", recordThere || phraseThere)
                if (!recordThere) assertEquals("cut $index: no record, so every file is intact", before, phone.snapshot())

                phone.newProcess()
                phone.start()

                if (recordThere) {
                    assertEquals("cut $index: the start finishes the wipe", wiped, phone.files())
                    assertEquals("Finished", phone.outcome())
                } else {
                    assertEquals("cut $index: an ordinary start", before, phone.snapshot())
                    assertEquals("None", phone.outcome())
                }
            }
        }
    }

    @Test
    fun `a power cut right after the record's removal - the record may be back, the phone is still wiped`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.destroy()
            val recordBytes = phone.recordFile.readBytes().toList()
            phone.newProcess()
            // The power goes after the record's delete and before the folder sync that would make it durable.
            phone.killAt = { operation, file -> operation == "sync" && file == phone.recordFile.parentFile && !phone.recordFile.exists() }

            phone.start()
            assertTrue("fixture: the phone died at the last folder sync", phone.dead)
            phone.powerCut()
            phone.killAt = null

            assertEquals("the record is back", recordBytes, phone.recordFile.readBytes().toList())
            assertEquals("the deletes of the pass survived the cut: the phone is still wiped", (wiped + record).sorted(), phone.files())

            phone.newProcess()
            phone.start()

            // The second start finds nothing more to delete and removes the record.
            assertEquals(wiped, phone.files())
            assertEquals("Finished", phone.outcome())
        }

    @Test
    fun `a power cut after the last folder sync - no record comes back`() =
        FakePhone().use { phone ->
            walletWithRecord(phone)

            phone.start()
            phone.powerCut()

            assertEquals("nothing comes back: every delete of the pass and the record's removal were made durable", wiped, phone.files())
        }

    @Test
    fun `a removed record brought back after a new wallet was made - nothing is deleted`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.destroy()
            val oldRecord = phone.recordFile.readBytes()
            phone.newProcess()
            phone.start()
            assertFalse("fixture: the record was removed", phone.recordFile.exists())

            // A new wallet, and then a storage fault brings the old record back.
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml("a-new", "wallet"))
            phone.put("data/shared_prefs/zchat_preferences.xml", "new chats\n")
            phone.put("data/files/address_book/address_book_1", "new contacts\n")
            phone.keys += "_androidx_security_master_key_"
            phone.recordFile.writeBytes(oldRecord)
            val before = phone.snapshot()
            phone.newProcess()

            phone.start()

            assertEquals("the new wallet is untouched", before, phone.snapshot())
            assertEquals(setOf("_androidx_security_master_key_"), phone.keys.toSet())
            assertFalse(phone.recordFile.exists())
            assertEquals("Ignored", phone.outcome())
        }

    // ------------------------------------------------------------------ it never throws

    @Test
    fun `a file layer that throws at every call - the start pass returns`() {
        for (how in listOf("exception", "error", "dead")) {
            FakePhone().use { phone ->
                walletWithRecord(phone)
                val calls = listOf("write", "rename", "delete", "sync")
                when (how) {
                    "exception" -> phone.failAt += calls
                    "error" -> phone.errorAt += calls
                    else -> phone.killAt = { _, _ -> true }
                }

                phone.start()

                assertTrue("$how: the record could not be deleted, so it is still there", phone.recordFile.isFile)
                assertEquals("$how: a valid pass ran and the record is stuck", "RecordStuck", phone.outcome())
            }
        }
        FakePhone().use { phone ->
            // No record, and a context that gives no data folder at all.
            phone.useRealCalls()
            val bare = object : android.content.ContextWrapper(null) {}
            phone.wipe("atProcessStart", bare)
            assertEquals("None", phone.outcome())
        }
    }

    // ------------------------------------------------------------------ source: where it is called, and by which rule

    @Test
    fun `the app's start calls it first - right after super onCreate and before everything else`() {
        val body = DestroySources.block(DestroySources.code(APPLICATION), "override fun onCreate()").orEmpty()
        assertTrue("fixture: ZcashApplication.onCreate", "super.onCreate()" in body)
        val afterSuper = body.substringAfter("super.onCreate()").trimStart()
        assertTrue(
            "the first statement after super.onCreate() is DestroyWipe.atProcessStart(this); found: ${afterSuper.take(80)}",
            afterSuper.startsWith("DestroyWipe.atProcessStart(this)")
        )
        val call = body.indexOf("DestroyWipe.atProcessStart(this)")
        for (later in listOf("registerActivityLifecycleCallbacks", "configureLogging()", "ProcessNameCompat", "MainProcess", "startKoin")) {
            assertTrue("$later comes after the start pass", body.indexOf(later) > call)
        }
        assertEquals("it is called once", 1, Regex("""DestroyWipe\.atProcessStart\(""").findAll(body).count())
    }

    @Test
    fun `DestroyWipe and atProcessStart are public and the main-process rule is ZC-706's, not a second one`() {
        val wipe = Class.forName(FakePhone.WIPE_CLASS)
        assertTrue("DestroyWipe is public (the app module calls it)", Modifier.isPublic(wipe.modifiers))
        val start = wipe.methods.firstOrNull { it.name == "atProcessStart" }
        assertTrue("DestroyWipe.atProcessStart(context) exists and is public", start != null && Modifier.isPublic(start.modifiers))

        val source = DestroySources.code(DestroySources.WIPE)
        assertEquals("DestroyWipe.kt asks MainProcess.isMain, once", 1, Regex("""MainProcess\.isMain\(""").findAll(source).count())
        val pass = DestroySources.block(source, "private fun startPass(").orEmpty()
        assertTrue("the start pass is where the rule is asked", "MainProcess.isMain(" in pass)
        assertFalse(
            "the start pass compares nothing with the package name by itself",
            Regex("""[!=]=\s*(\w+\.)?packageName\b|\bpackageName\s*[!=]=""").containsMatchIn(pass)
        )
        assertFalse(
            "no second comparison of a process name with the package name anywhere in the file",
            Regex("""[pP]rocess\w*\s*[!=]=\s*(\w+\.)?packageName\b|\bpackageName\s*[!=]=\s*\w*[pP]rocess""").containsMatchIn(source)
        )
        assertFalse("the pass does not read the process name by itself", "myProcessName" in source)
        assertFalse("nor through the application class", "Application.getProcessName" in source)
    }

    private companion object {
        const val APPLICATION = "app/src/main/java/co/electriccoin/zcash/app/ZcashApplication.kt"
    }
}
