package co.electriccoin.zcash.work

import co.electriccoin.zcash.ui.screen.chat.util.DestroySources
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * ZC-692-S (register ZC-692, high; owner rule W-41): a process that finished a Destroy All at its start starts no
 * background sync until the app itself turns it on for a wallet.
 *
 * The first start after a cut-off Destroy All is often one nobody sees: Android restarts the sync service of a killed
 * process by itself, and the sync alarm and the periodic job start the process too. Before the fix the broadcast or
 * the restart that woke that very process showed the sync notice and armed the alarm again, one moment after the pass
 * had cancelled both.
 *
 * Wanted: a start pass on a valid record sets `DestroyWipe.backgroundSyncHeld`. While it is set
 * `SyncAlarmReceiver.onReceive` neither starts the service nor sets the next alarm, and
 * `SyncForegroundService.onStartCommand` with no intent (Android restarting the service) stops the service and
 * returns "do not restart". `WorkIds.enableBackgroundSynchronization`, which the app calls only for a wallet that is
 * ready, clears the flag first. After a record that was not valid, and with no record, the flag is not set. Nothing
 * else reads or writes it.
 */
class StartPassHoldsBackgroundSyncTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(120)

    private val record = "data/no_backup/zchat_destroy_pending"
    private val alarmAsked = "getSystemService:alarm"

    private fun FakePhone.start() {
        wipe("atProcessStart", context)
    }

    private fun FakePhone.held(): Boolean = wipeField("backgroundSyncHeld") as Boolean

    /** One alarm broadcast; what it did is in the phone's events. Today's receiver throws without an alarm service. */
    private fun FakePhone.alarmFires(): List<String> {
        val before = events.size
        runCatching { SyncAlarmReceiver().onReceive(context, null) }
        return events.drop(before)
    }

    private fun assertReceiverBehavesAsToday(
        why: String,
        events: List<String>
    ) {
        assertTrue("$why: the alarm starts the sync service; events=$events", "startService" in events)
        assertTrue("$why: and asks for the alarm service to set the next alarm", alarmAsked in events)
    }

    @Test
    fun `control - with no flag the receiver starts the service and sets the next alarm`() =
        FakePhone().use { phone ->
            assertFalse(phone.held())
            assertReceiverBehavesAsToday("no Destroy", phone.alarmFires())
        }

    @Test
    fun `a start pass on a valid record sets the flag, and the receiver then does nothing`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.put(record, FakePhone.recordText(phone.installTime, FakePhone.storeHash("12a9aabbcc", "1288ddeeff")))

            phone.start()

            assertTrue("the pass sets 'background sync held'", phone.held())
            val fired = phone.alarmFires()
            assertFalse("the receiver does not start the service: $fired", "startService" in fired)
            assertFalse("the receiver does not set the next alarm: $fired", alarmAsked in fired)
            assertEquals("the receiver does nothing at all", emptyList<String>(), fired)
        }

    @Test
    fun `the flag alone holds the receiver`() =
        FakePhone().use { phone ->
            phone.setWipeField("backgroundSyncHeld", true)
            assertEquals(emptyList<String>(), phone.alarmFires())
        }

    @Test
    fun `the app turns sync on - enableBackgroundSynchronization clears the flag first, and the receiver is as today`() {
        FakePhone().use { phone ->
            phone.setWipeField("backgroundSyncHeld", true)

            // On the JVM the job library is not there: the call throws after its first statement.
            runCatching { WorkIds.enableBackgroundSynchronization(phone.context) }

            assertFalse("the flag is cleared before anything else can fail", phone.held())
            assertReceiverBehavesAsToday("after the app turned sync on", phone.alarmFires())
        }
        val enable = DestroySources.block(DestroySources.code(WORK_IDS), "fun enableBackgroundSynchronization(").orEmpty()
        assertTrue(
            "the first statement of enableBackgroundSynchronization clears the flag; found: ${enable.take(80)}",
            enable.removePrefix("{").trimStart().startsWith("DestroyWipe.backgroundSyncHeld = false")
        )
        val disable = DestroySources.block(DestroySources.code(WORK_IDS), "fun disableBackgroundSynchronization(").orEmpty()
        assertFalse("turning sync off does not touch the flag", "backgroundSyncHeld" in disable)
    }

    @Test
    fun `after a record that was not valid, and with no record, the flag is not set`() {
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.put(record, FakePhone.recordText(phone.installTime, FakePhone.storeHash("another", "wallet")))
            phone.start()
            assertEquals("Ignored", phone.wipeField("startOutcome")!!.javaClass.simpleName)
            assertFalse("IGNORED: nothing was wiped and a wallet may be there", phone.held())
            assertReceiverBehavesAsToday("after IGNORED", phone.alarmFires())
        }
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.start()
            assertEquals("None", phone.wipeField("startOutcome")!!.javaClass.simpleName)
            assertFalse("NONE", phone.held())
            assertReceiverBehavesAsToday("after NONE", phone.alarmFires())
        }
    }

    @Test
    fun `the restarted service - with the flag and no intent it stops itself and is not restarted`() {
        val start = DestroySources.block(DestroySources.code(SERVICE), "override fun onStartCommand(").orEmpty()
        assertTrue("fixture: onStartCommand", "promoteToForeground(" in start)
        val read = start.indexOf("DestroyWipe.backgroundSyncHeld")
        assertTrue("onStartCommand reads the flag", read >= 0)
        assertTrue("before it goes to the foreground", read < start.indexOf("promoteToForeground("))
        val guard = Regex("""if \(intent == null && DestroyWipe\.backgroundSyncHeld\)\s*\{\s*stopSelf\(\)\s*return START_NOT_STICKY\s*\}""")
        assertTrue(
            "for a start with no intent it stops itself and returns START_NOT_STICKY",
            guard.containsMatchIn(start)
        )
        assertEquals("the flag is read once there", 1, Regex("""backgroundSyncHeld""").findAll(start).count())
        // A start by the app for a wallet (ACTION_START) and the ordinary restart are untouched.
        assertTrue("ACTION_START, null ->" in start)
        assertTrue("return START_STICKY" in start)
    }

    @Test
    fun `only the receiver and the service read the flag, only DestroyWipe and WorkIds write it`() {
        val namers =
            DestroySources
                .shipped(setOf("kt", "java"))
                .filter { "backgroundSyncHeld" in SourceScan.stripComments(it.readText()) }
                .map { DestroySources.relative(it) }
        assertEquals(listOf(SERVICE, DestroySources.WIPE, RECEIVER, WORK_IDS, RESET_WIPE).sorted(), namers.sorted())
        // ZC-090-P: the Reset's start pass holds background sync after a valid record, as Destroy's does; it only sets it.
        assertEquals(
            "ResetWipe only sets it",
            listOf("backgroundSyncHeld = true"),
            Regex("""backgroundSyncHeld\s*=[^=]\w*""")
                .findAll(DestroySources.code(RESET_WIPE))
                .map { it.value }
                .distinct()
                .toList()
        )

        val write = Regex("""backgroundSyncHeld\s*=[^=]""")
        assertFalse("the receiver only reads it", write.containsMatchIn(DestroySources.code(RECEIVER)))
        assertFalse("the service only reads it", write.containsMatchIn(DestroySources.code(SERVICE)))
        val receiver = DestroySources.block(DestroySources.code(RECEIVER), "override fun onReceive(").orEmpty()
        assertTrue(
            "the receiver looks at the flag before it does anything; found: ${receiver.take(80)}",
            receiver.removePrefix("{").trimStart().startsWith("if (DestroyWipe.backgroundSyncHeld) return")
        )
        assertEquals(
            "WorkIds only clears it",
            listOf("backgroundSyncHeld = false"),
            Regex("""backgroundSyncHeld\s*=[^=]\w*""").findAll(DestroySources.code(WORK_IDS)).map { it.value }.toList()
        )
        val wipe = DestroySources.code(DestroySources.WIPE)
        assertEquals(
            "DestroyWipe sets it in one place, after a valid record's pass",
            1,
            Regex("""backgroundSyncHeld = true""").findAll(wipe).count()
        )
        assertTrue(
            "and that place is the end of a valid pass",
            "backgroundSyncHeld = true" in DestroySources.block(wipe, "private fun finish(").orEmpty()
        )
    }

    private companion object {
        const val RECEIVER = "ui-lib/src/main/java/co/electriccoin/zcash/work/SyncAlarmReceiver.kt"
        const val SERVICE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt"
        const val WORK_IDS = "ui-lib/src/main/java/co/electriccoin/zcash/work/WorkIds.kt"
        const val RESET_WIPE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/ResetWipe.kt"
    }
}
