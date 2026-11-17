package co.electriccoin.zcash.ui.screen.deletewallet

import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.AI_TOKEN
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.AI_USER_ID
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.RECORD_RENAME
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_ACCOUNTS
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_AI_READ
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_ALARM_ARM
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_FLEXA
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_JOB_ENQUEUE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEPT_DELETE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEPT_WRITE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEYS_CONTACTS
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_KEYS_NOTES
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_OWNER_NONE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_OWNER_WRITE
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_RESTART
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_STOP
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_WAKE_CANCEL
import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver.Companion.STEP_WALLET_CLEAR
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-090-R (registers ZC-090, high, its Delete Wallet half, and ZC-755; RESET-LEFTOVERS-SPEC.md revision 2d, sections
 * 4.2 RS-1 to RS-5, 4.3, 4.4 and 6.3): a Reset ZCHAT writes its record before it deletes or clears anything, and no
 * step can hold it. After the unlock prompt it keeps a saved AI access key in the reset wallet's own set-aside file
 * (with the box unticked), writes the owner, reads the names of the cached Contacts and notes keys, writes its record,
 * marks the chats as nobody's (unticked), stops the sync service, background work and notifications, arms one alarm
 * and enqueues one job, clears the saved wallet and restarts ZCHAT. Everything else is deleted at the next start by
 * ZC-090-P's pass. A Reset that cannot write its record, or cannot keep a key, does not start, and deletes nothing.
 *
 * Driven whole on [FakePhone] by [ResetDriver]; a stand-in that never answers blocks its thread (a latch that is never
 * released), so only a real limit lets the Reset go on, and the limits are counted on the phone's own clock (the
 * waits that gave up). Every new name is reached by name or reflection: this class compiles before the fix.
 *
 * Order note: the kept file (S1b) is written before the owner (S1) here. The unedited source case of batch ZC-718
 * (AiDataFollowsItsWalletTest, "n - source") requires the keep step to stand before recordChatOwner; both come before
 * the record, neither deletes anything, and a Reset that stops between them has written nothing a claim reads (RED.md).
 */
class ResetWritesItsRecordFirstAndNeverWaitsTest {
    private val drivers = ArrayList<ResetDriver>()

    @After
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    private fun driver(): ResetDriver = ResetDriver().also { drivers += it }.apply { seedPhraseStore() }

    // ------------------------------------------------------------------ the order

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `order - the kept file, the owner, the names, the record, nobody's, the stops, one alarm and one job, the wallet clear, the restart`() =
        runBlocking {
            val reset = driver()
            assertEquals("the Reset did not start", STARTED, reset.reset(keepFiles = false))
            val events = reset.phone.events
            val order =
                listOf(
                    STEP_KEPT_WRITE,
                    STEP_OWNER_WRITE,
                    STEP_KEYS_CONTACTS,
                    STEP_KEYS_NOTES,
                    RECORD_RENAME,
                    STEP_OWNER_NONE,
                    STEP_STOP,
                    "job.cancel",
                    "alarm.cancel",
                    "work.cancelAll",
                    "notifications.cancelAll",
                    STEP_ALARM_ARM,
                    STEP_JOB_ENQUEUE,
                    STEP_WALLET_CLEAR,
                    STEP_RESTART,
                )
            val at = order.map { step -> step to reset.at(step) }
            for ((step, index) in at) assertTrue("no $step in $events", index >= 0)
            assertEquals("the steps are not in the order of spec 4.4: $events", at.sortedBy { it.second }.map { it.first }, order)
            assertTrue("the job is enqueued after the cancel of all work", reset.at(STEP_JOB_ENQUEUE) > reset.at("work.cancelAll"))
            val record = reset.at(RECORD_RENAME)
            val before = events.take(record)
            assertEquals(
                "something was deleted or cleared before the record: $before",
                emptyList<String>(),
                before.filter { it.startsWith("delete:") || it.endsWith(".clear") || it == STEP_STOP || it == STEP_OWNER_NONE },
            )
        }

    @Test
    fun `no screen without a wallet - between the wallet clear and the restart request there is no other event`() =
        runBlocking {
            for (keep in listOf(true, false)) {
                val reset = driver()
                reset.reset(keepFiles = keep)
                val events = reset.phone.events
                val clear = reset.at(STEP_WALLET_CLEAR)
                assertTrue("keep=$keep: the saved wallet was not cleared: $events", clear >= 0)
                assertEquals("keep=$keep: the event after the wallet clear: $events", STEP_RESTART, events.getOrNull(clear + 1))
            }
        }

    @Test
    fun `the record - seven lines in order, keep=all, keep=ai-key or keep=none, the wallet's tag, an end that matches`() =
        runBlocking {
            val ticked = driver()
            ticked.reset(keepFiles = true)
            val text = ticked.record()
            assertTrue("no record after a started Reset", text != null)
            val lines = text!!.lines().filter { it.isNotEmpty() }
            assertEquals(
                "the seven lines of spec 4.3",
                listOf("zchat-reset 1", "install", "store", "keep", "owner", "files", "end"),
                lines.map { it.substringBefore('=') },
            )
            assertEquals("all", ticked.recordLines()["keep"])
            assertEquals(ticked.tag, ticked.recordLines()["owner"])
            assertEquals(ticked.expectedStoreHash(), ticked.recordLines()["store"])
            assertEquals(ticked.phone.installTime.toString(), ticked.recordLines()["install"])
            val body = text.substringBefore("end=")
            assertEquals("the end line", FakePhone.sha256Hex(body), ticked.recordLines()["end"])

            val withKey = driver()
            withKey.reset(keepFiles = false)
            assertEquals("ai-key", withKey.recordLines()["keep"])

            val noKey = driver().apply { aiKept = emptyMap() }
            noKey.reset(keepFiles = false)
            assertEquals("none", noKey.recordLines()["keep"])
        }

    // ------------------------------------------------------------------ S1b: the AI access key

    @Test
    fun `the key is kept before the record - exactly the token and its user id, as the only part of the kept file`() =
        runBlocking {
            val reset = driver()
            reset.reset(keepFiles = false)
            val kept = reset.bundles[reset.tag]
            assertEquals("the kept file holds the AI store's part and nothing else", setOf("zchat_ai_prefs"), kept?.keys)
            assertEquals(setOf(AI_TOKEN, AI_USER_ID), kept!!.getValue("zchat_ai_prefs").keys)
            assertTrue("the kept file is written before the record", reset.at(STEP_KEPT_WRITE) in 0 until reset.at(RECORD_RENAME))

            val noKey = driver().apply { aiKept = emptyMap() }
            noKey.reset(keepFiles = false)
            assertTrue("no saved key: no file is written", noKey.at(STEP_KEPT_WRITE) < 0)
            assertTrue("no saved key: no file is deleted in this process", noKey.at(STEP_KEPT_DELETE) < 0)

            val ticked = driver()
            ticked.bundles[ticked.tag] = mapOf("messages" to mapOf("m" to "synthetic"))
            ticked.reset(keepFiles = true)
            assertTrue("keep ticked: S1b reads nothing", ticked.at(STEP_AI_READ) < 0)
            assertEquals("keep ticked: this wallet's set-aside file is not touched", setOf("messages"), ticked.bundles[ticked.tag]?.keys)
        }

    @Test
    fun `a key that cannot be kept - the Reset does not start, and never goes on with keep=none`() =
        runBlocking {
            for ((fault, step) in listOf(
                "fails" to STEP_KEPT_WRITE,
                "hangs" to STEP_KEPT_WRITE,
                "fails" to STEP_AI_READ,
                "hangs" to STEP_AI_READ,
            )) {
                val reset = driver()
                when (fault) {
                    "fails" -> reset.phone.failAt += step
                    else -> reset.phone.hangAt += step
                }
                val answer = reset.reset(keepFiles = false)
                val what = "$step $fault"
                assertEquals("$what: the answer", NOT_STARTED, answer)
                assertNull("$what: a record was written", reset.record())
                assertNotStartedLeavesEverything(reset, what)
                assertFalse("$what: the kept file stayed", reset.bundles.containsKey(reset.tag))
            }
        }

    // ------------------------------------------------------------------ S2: the names

    @Test
    fun `the names - the cached Contacts and notes keys only, never the account list, none with keep ticked`() =
        runBlocking {
            val reset = driver()
            reset.reset(keepFiles = false)
            assertEquals(
                "the files line",
                reset.expectedFiles().sorted(),
                reset
                    .recordLines()["files"]
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotEmpty() }
                    .sorted(),
            )
            assertTrue("the account list was asked", reset.at(STEP_ACCOUNTS) < 0)

            val ticked = driver()
            ticked.reset(keepFiles = true)
            assertEquals("", ticked.recordLines()["files"])
            assertTrue("keep ticked: the key stores were asked", ticked.at(STEP_KEYS_CONTACTS) < 0 && ticked.at(STEP_KEYS_NOTES) < 0)
        }

    @Test
    fun `the names step cannot refuse - a key store that never answers, then an empty files line and the Reset goes on`() =
        runBlocking {
            val reset = driver()
            reset.phone.hangAt += STEP_KEYS_CONTACTS
            assertEquals(STARTED, reset.reset(keepFiles = false))
            assertEquals("", reset.recordLines()["files"])
            assertTrue("the restart", reset.at(STEP_RESTART) >= 0)
            assertTrue(
                "the names step waited longer than 2 s",
                reset.phone.waits.all {
                    it.second <= NAMES_LIMIT_MS || !it.first.contains("names")
                },
            )
        }

    // ------------------------------------------------------------------ no SDK call

    @Test
    fun `no SDK call - no erase, no synchronizer, no close, no clear of a store`() {
        val source = SourceScan.stripComments(resetSource())
        for (name in listOf(
            "deleteSdkDataFlow",
            "getSynchronizer(",
            "closeFlow",
            "clearAll()",
            "WalletCoordinator",
            "SynchronizerProvider",
        )) {
            assertFalse("ResetZashiUseCase.kt names $name", name in source)
        }
    }

    // ------------------------------------------------------------------ not started

    @Test
    fun `not started - the unlock prompt, the owner write, the record, the store, the install time, a Destroy record`() =
        runBlocking {
            val cancelled = driver().apply { unlock = "cancel" }
            assertEquals("the unlock prompt cancelled", CANCELLED, cancelled.reset(keepFiles = false))
            assertNotStartedLeavesEverything(cancelled, "cancelled")
            val failed = driver().apply { unlock = "fail" }
            assertEquals("the unlock prompt failed", CANCELLED, failed.reset(keepFiles = false))
            assertNotStartedLeavesEverything(failed, "failed")

            val cases: List<Pair<String, (ResetDriver) -> Unit>> =
                listOf(
                    "the owner write throws" to { it.phone.failAt += STEP_OWNER_WRITE },
                    "the owner write never answers" to { it.phone.hangAt += STEP_OWNER_WRITE },
                    "the record cannot be written" to { d -> d.phone.refuseWrite = { it.name.startsWith("zchat_reset_pending") } },
                    "the store has no hash" to { File(it.phone.dataDir, "shared_prefs/co.electriccoin.zcash.encrypted.xml").delete() },
                    "the install time is unknown" to { it.phone.installTime = 0L },
                    "a Destroy record file exists" to { it.phone.put("data/no_backup/zchat_destroy_pending", "zchat-destroy 1\n") },
                )
            for ((what, fault) in cases) {
                val reset = driver()
                fault(reset)
                assertEquals("$what: the answer", NOT_STARTED, reset.reset(keepFiles = false))
                assertNull("$what: a record was written", reset.record())
                assertNotStartedLeavesEverything(reset, what)
                assertFalse("$what: the kept file stayed", reset.bundles.containsKey(reset.tag))
            }
        }

    @Test
    fun `not started stays not started - an owner write released after the answer writes no record`() =
        runBlocking {
            val reset = driver()
            reset.phone.hangAt += STEP_OWNER_WRITE
            assertEquals(NOT_STARTED, reset.reset(keepFiles = false))
            // The hung write is released when the phone is closed; the worker then ends. Nothing more is written.
            reset.phone.hangAt.clear()
            Thread.sleep(LATE_WORKER_MS)
            assertNull("a record appeared after 'not started'", reset.record())
            assertTrue("the restart was requested after 'not started'", reset.at(STEP_RESTART) < 0)
        }

    // ------------------------------------------------------------------ nothing holds it

    @Test
    fun `nothing holds it - after the record each of these never answers, and the restart still comes within the limits`() =
        runBlocking {
            for (step in listOf(STEP_OWNER_NONE, STEP_STOP, STEP_FLEXA, STEP_WALLET_CLEAR)) {
                val reset = driver()
                reset.phone.hangAt += step
                assertEquals("$step: the answer", STARTED, reset.reset(keepFiles = false))
                assertTrue("$step: the restart was not requested: ${reset.phone.events}", reset.at(STEP_RESTART) >= 0)
                val waited = reset.phone.waits.sumOf { it.second }
                assertTrue("$step: the Reset waited $waited ms on the phone's clock", waited <= SUM_OF_LIMITS_MS)
                assertTrue("$step: a wait gave up (the limit is real)", reset.phone.waits.isNotEmpty())
            }
        }

    @Test
    fun `it always restarts - a step after the record that throws an Error`() =
        runBlocking {
            for (step in listOf(STEP_OWNER_NONE, STEP_STOP, STEP_FLEXA, STEP_ALARM_ARM, STEP_WALLET_CLEAR)) {
                val reset = driver()
                reset.phone.errorAt += step
                val answer = runCatching { reset.reset(keepFiles = false) }
                assertTrue("$step: the restart was not requested: ${reset.phone.events}", reset.at(STEP_RESTART) >= 0)
                assertFalse("$step: the Reset answered 'not started' after its record", answer.getOrNull() == NOT_STARTED)
            }
        }

    // ------------------------------------------------------------------ the sync service between S5 and S7, and at the next start

    @Test
    fun `no sync while a Reset record exists - a start request between the stop and the restart, and the next process's start`() =
        runBlocking {
            val reset = driver()
            var refusedWithIntent: Any? = null
            var refusedWithout: Any? = null
            reset.duringWalletClear = {
                refusedWithIntent = reset.resetWipe("syncStartRefused", true)
                refusedWithout = reset.resetWipe("syncStartRefused", false)
            }
            assertEquals(STARTED, reset.reset(keepFiles = true))
            assertEquals("a start request with an intent between S5 and S7 would sync", true, refusedWithIntent)
            assertEquals("a start with no intent between S5 and S7 would sync", true, refusedWithout)
            assertEquals("background sync is not held after the Reset", true, reset.phone.wipeField("backgroundSyncHeld"))

            // The next process, with the record still on the phone: the start pass runs before any service starts.
            reset.phone.newProcess()
            reset.resetState()
            assertTrue("fixture: the record is on the phone at the next start", reset.record() != null)
            reset.resetWipe("atProcessStart", reset.phone.context)
            assertEquals("the next process's no-intent start would sync", true, reset.resetWipe("syncStartRefused", false))
        }

    @Test
    fun `the wake - after a finished pass the start cancels the alarm and the job, a start with no record cancels nothing`() =
        runBlocking {
            val reset = driver()
            assertEquals(STARTED, reset.reset(keepFiles = true))
            reset.phone.newProcess()
            reset.resetState()
            val before = reset.phone.events.size
            reset.installResetWipeSeams()
            reset.resetWipe("atProcessStart", reset.phone.context)
            reset.resetWipe("cancelWakeAfterThePass", reset.phone.context)
            assertTrue(
                "a start after a finished pass did not cancel the Reset's alarm and job: ${reset.phone.events.drop(before)}",
                reset.phone.events
                    .drop(before)
                    .any { it.startsWith(STEP_WAKE_CANCEL) },
            )

            // compat-2124: a start with no record (every start of an install that never reset) does what it does today.
            val today = driver()
            today.installResetWipeSeams()
            today.resetWipe("atProcessStart", today.phone.context)
            today.resetWipe("cancelWakeAfterThePass", today.phone.context)
            assertTrue("a start with no record cancelled the wake", today.at(STEP_WAKE_CANCEL) < 0)
        }

    @Test
    fun `source - the sync service asks the Reset's rule before it starts, and the app does not lift the hold during a Reset`() {
        val service = SourceScan.stripComments(source("ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt"))
        val start = service.substringAfter("override fun onStartCommand(", "")
        val rule = start.indexOf("ResetWipe.syncStartRefused(")
        assertTrue("onStartCommand does not ask ResetWipe.syncStartRefused", rule >= 0)
        assertTrue("the rule is asked before the service promotes itself", rule < start.indexOf("promoteToForeground("))
        val work = SourceScan.stripComments(source("ui-lib/src/main/java/co/electriccoin/zcash/work/WorkIds.kt"))
        val enable = work.substringAfter("fun enableBackgroundSynchronization(", "")
        val guard = enable.indexOf("ResetWipe.resetRunning")
        assertTrue("enableBackgroundSynchronization does not stop while a Reset runs", guard >= 0)
        assertTrue("the guard stands before the periodic sync is enqueued", guard < enable.indexOf("enqueueUniquePeriodicWork("))
        assertTrue("the guard stands before the alarm is set", guard < enable.indexOf("SyncAlarmScheduler.schedule("))
        val receiver = SourceScan.stripComments(source("ui-lib/src/main/java/co/electriccoin/zcash/work/SyncAlarmReceiver.kt"))
        val onReceive = receiver.substringAfter("override fun onReceive(", "")
        val held = onReceive.indexOf("ResetWipe.resetRunning")
        assertTrue("the alarm's receiver does not stop while a Reset runs", held >= 0)
        assertTrue("the receiver looks before it starts anything", held < onReceive.indexOf("SyncForegroundService"))
    }

    // ------------------------------------------------------------------ what it must not do

    @Test
    fun `what it must not do - no clipboard, no Keystore delete, no file of the data folder deleted, no AI server`() =
        runBlocking {
            val reset = driver()
            reset.phone.keys += listOf("_androidx_security_master_key_", "zchat_ai_master_key")
            reset.phone.put("data/shared_prefs/zchat_example.xml")
            reset.reset(keepFiles = false)
            val events = reset.phone.events
            assertFalse("the clipboard was cleared", events.any { it.startsWith("clipboard.") })
            assertFalse("a Keystore key was deleted", events.any { it.startsWith("keystore.delete") })
            assertFalse("a file was deleted in this process: $events", events.any { it.startsWith("delete:") })
            assertEquals(2, reset.phone.keys.size)

            val source = SourceScan.stripComments(resetSource())
            assertTrue("the Reset reaches the AI store through AiResetKeep", "AiResetKeep" in source)
            for (name in listOf("AiApiClient", "OkHttp", "HttpURLConnection", "Retrofit")) {
                assertFalse("ResetZashiUseCase.kt names $name", name in source)
            }
        }

    @Test
    fun `guard - the helpers keep their names`() {
        val source = resetSource()
        for (name in listOf("private suspend fun recordChatOwner()", "private suspend fun markChatsWiped()")) {
            assertTrue("ResetZashiUseCase.kt lost $name", name in source)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertNotStartedLeavesEverything(
        reset: ResetDriver,
        what: String,
    ) {
        val events = reset.phone.events
        for (step in listOf(STEP_WALLET_CLEAR, STEP_RESTART, STEP_STOP, STEP_OWNER_NONE, STEP_FLEXA, STEP_ALARM_ARM)) {
            assertFalse("$what: $step happened: $events", events.any { it.startsWith(step) })
        }
        assertFalse("$what: a file was deleted: $events", events.any { it.startsWith("delete:") })
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private fun resetSource(): String = source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt")

    private companion object {
        const val STARTED = "STARTED"
        const val NOT_STARTED = "NOT_STARTED"
        const val CANCELLED = "CANCELLED"
        const val NAMES_LIMIT_MS = 2_000L
        const val LATE_WORKER_MS = 300L

        // S1 with S3 5 s, S1b 2 s, S2 2 s, S4 1 s, S5 3 s, S6 2 s (spec 4.4)
        const val SUM_OF_LIMITS_MS = 15_000L
    }
}
