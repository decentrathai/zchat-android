package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * ZC-888 (register, high; prep of 19 Oct 2026) — the start pass that finishes a Destroy leaves notification channels and
 * background jobs, and still calls the Destroy finished:
 *
 *  - Android may restart the sync service while the pass runs (stopService does not wait, and the service is refused only
 *    once backgroundSyncHeld is set, after the pass); the service then makes its channels again, and a channel of a
 *    running foreground service cannot be deleted (SecurityException, ZC-1022);
 *  - check() asks only for active notifications, not for channels, channel groups or pending jobs.
 *
 * Wanted:
 *  (a) a channel delete that Android refuses while the service is still stopping is tried again for about 1 s;
 *  (b) a channel or a channel group still there after the pass is counted (the outcome is Unfinished, never Finished);
 *  (c) a background job still pending after the pass is counted;
 *  (d) every start of the sync service is refused while a Destroy record is on the phone (the service makes no channel
 *      either), and allowed again once the record is gone;
 *  (e) ZC-888-X1: but not while the record is one the pass set aside beside a live wallet (Ignored, the phone did not let
 *      it be removed): that wallet's sync runs.
 *
 * Everything is synthetic.
 */
class DestroyLeavesNoChannelOrJobTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(120)

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
    private val ownStore = FakePhone.storeHash("12a9aabbcc", "1288ddeeff")

    private fun FakePhone.withRecord() {
        seedEverything()
        channelGroups += listOf("chats")
        put(record, FakePhone.recordText(installTime, ownStore))
    }

    private fun FakePhone.start() {
        wipe("atProcessStart", context)
    }

    private fun FakePhone.outcome(): Any = wipeField("startOutcome")!!

    private fun FakePhone.kinds(): Set<*> {
        val outcome = outcome()
        assertEquals("the outcome is not Unfinished", "Unfinished", outcome.javaClass.simpleName)
        return outcome.javaClass.getMethod("getKinds").invoke(outcome) as Set<*>
    }

    private fun refused(phone: FakePhone): Boolean {
        val wipe = Class.forName(FakePhone.WIPE_CLASS)
        val method =
            wipe.methods.firstOrNull { it.name == "syncStartRefused" && it.parameterCount == 1 }
                ?: throw AssertionError("DestroyWipe has no syncStartRefused(context): a sync start is never refused for a record")
        return try {
            method.invoke(wipe.getField("INSTANCE").get(null), phone.context) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `(a) a channel delete refused while the service is still stopping is tried again`() =
        FakePhone().use { phone ->
            phone.withRecord()
            phone.channelDeleteRefusals.set(3)

            phone.start()

            assertEquals("a channel is left after the pass", emptyList<String>(), phone.channels.toList())
            assertEquals("Finished", phone.outcome().javaClass.simpleName)
        }

    @Test
    fun `(b) a channel or a channel group still there after the pass is counted`() {
        FakePhone().use { phone ->
            phone.withRecord()
            phone.channelDeleteRefusals.set(Int.MAX_VALUE)

            phone.start()

            assertTrue("fixture: a channel is left", phone.channels.isNotEmpty())
            assertTrue("a channel left is not counted", DestroyWipe.KIND_NOTIFICATIONS in phone.kinds())
        }
        FakePhone().use { phone ->
            phone.withRecord()
            phone.channelGroupsStay = true

            phone.start()

            assertTrue("fixture: a channel group is left", phone.channelGroups.isNotEmpty())
            assertTrue("a channel group left is not counted", DestroyWipe.KIND_NOTIFICATIONS in phone.kinds())
        }
    }

    @Test
    fun `(c) a background job still pending after the pass is counted`() =
        FakePhone().use { phone ->
            phone.withRecord()
            phone.pendingWorkStays = 1

            phone.start()

            assertTrue("a pending job is not counted", "jobs" in phone.kinds())
        }

    @Test
    fun `(d) every sync-service start is refused while a Destroy record is on the phone`() {
        FakePhone().use { phone ->
            phone.withRecord()
            assertTrue("a sync start is let through while a Destroy record is on the phone", refused(phone))
            phone.start()
            assertEquals("fixture", "Finished", phone.outcome().javaClass.simpleName)
            assertFalse("a sync start is refused after the record is gone", refused(phone))
        }
        val service = SourceScan.stripComments(File(ChatResourceText.repoRoot, SERVICE).readText())
        val onCreate = service.substring(service.indexOf("override fun onCreate()"), service.indexOf("override fun onStartCommand("))
        assertTrue("the service's onCreate does not ask DestroyWipe", "DestroyWipe.syncStartRefused(this)" in onCreate)
        assertTrue(
            "the service makes its channels before it asks",
            onCreate.indexOf("DestroyWipe.syncStartRefused(this)") < onCreate.indexOf("createNotificationChannels()"),
        )
        val start = service.substring(service.indexOf("override fun onStartCommand("))
        val refusal = start.indexOf("DestroyWipe.syncStartRefused(this)")
        assertTrue("onStartCommand does not refuse a start for a record", refusal >= 0)
        assertTrue("onStartCommand refuses after it may promote", refusal < start.indexOf("promoteToForeground("))
    }

    @Test
    fun `(e) a record that stays beside a live wallet does not hold the sync for ever`() =
        FakePhone().use { phone ->
            // ZC-888-X1 (T7aD review b, finding 5): a record of another install, beside this install's live wallet,
            // that the phone does not let ZCHAT remove: the pass ignores it and the wallet stays.
            phone.seedEverything()
            phone.put(record, FakePhone.recordText(phone.installTime + 1, ownStore))
            phone.refuseDelete = { it.name == "zchat_destroy_pending" }

            phone.start()

            assertEquals("fixture", "Ignored", phone.outcome().javaClass.simpleName)
            assertTrue("fixture: the record stays", phone.recordFile.isFile)
            assertFalse("a record the pass set aside refuses the live wallet's sync for ever", refused(phone))
        }

    @Test
    fun `(f) a Destroy run in the process that set a record aside still refuses sync starts`() =
        FakePhone().use { phone ->
            // ZC-888-X2 (recheck of 20 Oct, finding 4): the record set aside (Ignored) stays; the user then runs Destroy
            // in this same process. While that Destroy runs, no sync start is let through.
            phone.seedEverything()
            phone.put(record, FakePhone.recordText(phone.installTime + 1, ownStore))
            phone.refuseDelete = { it.name == "zchat_destroy_pending" }
            phone.start()
            assertEquals("fixture", "Ignored", phone.outcome().javaClass.simpleName)

            phone.destroy()

            assertTrue("fixture: this process wrote its record", phone.wipeField("recordWrittenByThisProcess") != null)
            assertTrue("a sync start is let through while this process's Destroy runs", refused(phone))
        }

    private companion object {
        const val SERVICE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt"
    }
}
