package co.electriccoin.zcash.ui.screen.chat.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZC-692-W (register ZC-692, high; the Destroy half of ZC-090: RD-2, RD-3; owner rule W-41): Destroy All erases the
 * recovery phrase first, and nothing can hold the wipe.
 *
 * Before the fix the phrase store was cleared as step 5, after a wait for a synchronizer that had no limit
 * (`getSynchronizer()`), the synchronizer's close and the SDK's own erase, which has no limit either and can end
 * the app from the SDK's own coroutine. A phone whose synchronizer never came up kept the recovery phrase for ever
 * behind "Destroying All Data…".
 *
 * Wanted: between the last confirming act and the delete of the phrase-store file there is one step, the record,
 * which waits on nothing but the phone's own storage; every later step has a limit and its own catch; the SDK's
 * erase is not called; the process always ends.
 *
 * Time is virtual ([FakePhone.waits]): a stand-in that never answers costs the test nothing, and the limit that was
 * asked for it is what the test reads.
 */
class DestroyErasesTheRecoveryPhraseFirstTest {
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
    }

    private val leftAfterAWipe = (FakePhone.STAYS + "data/no_backup/zchat_destroy_pending").sorted()

    private val phraseDelete = "delete:" + FakePhone.PHRASE_STORE
    private val backupDelete = "delete:" + FakePhone.PHRASE_STORE + ".bak"

    @Test
    fun `the record, then the phrase store and its bak, and nothing else before them`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.put(FakePhone.PHRASE_STORE + ".bak", FakePhone.phraseStoreXml("12cc", "12dd"))

            phone.destroy()

            val events = phone.events
            val record = events.indexOf("rename:data/no_backup/zchat_destroy_pending")
            val phrase = events.indexOf(phraseDelete)
            val backup = events.indexOf(backupDelete)
            assertTrue("the record is written (renamed to its final name); events=$events", record >= 0)
            assertTrue("the phrase-store file and its .bak are deleted; events=$events", phrase >= 0 && backup >= 0)
            assertTrue("the record comes before the delete of the phrase store", record < phrase && record < backup)
            val allowed =
                setOf(
                    "handler.swap",
                    "pm.firstInstallTime",
                    "write:data/no_backup/zchat_destroy_pending.tmp",
                    "rename:data/no_backup/zchat_destroy_pending",
                    "sync:data/no_backup",
                    phraseDelete,
                    backupDelete,
                )
            val before = events.take(maxOf(phrase, backup) + 1)
            assertEquals(
                "nothing but the record may come before the delete of the phrase store: no Keystore call, no process " +
                    "list, no notification or clipboard call, no service stop, no settings clear, no other delete",
                emptyList<String>(),
                before.filterNot { it in allowed }
            )
            assertFalse("the phrase store is gone", phone.phraseStore.exists() || phone.phraseStoreBackup.exists())
        }

    @Test(timeout = 120_000)
    fun `a Keystore that never answers - every file is still deleted and the process still ends`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.hangAt += listOf("keystore.list", "keystore.delete")

            phone.destroy()

            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
            val keyWaits = phone.waits.filter { "keys" in it.first }
            assertTrue("the key delete was waited for, with a limit: ${phone.waits}", keyWaits.isNotEmpty())
            assertTrue("no wait for the keys is longer than 2 s: $keyWaits", keyWaits.all { it.second <= 2_000L })
        }

    @Test
    fun `the encrypted settings are cleared before the standard ones, both before the chat stores, all before Flexa`() =
        FakePhone().use { phone ->
            phone.seedEverything()

            phone.destroy()

            val events = phone.events
            val order = listOf(phraseDelete, "encrypted.clear", "standard.clear", "stopService", "clearAll", "flexa.disconnect")
            val at = order.map { events.indexOf(it) }
            assertTrue("each of $order happens; found at $at; events=$events", at.all { it >= 0 })
            assertEquals("the order of the clears; events=$events", at.sorted(), at)
            assertEquals("the encrypted settings are cleared once", 1, phone.count("encrypted.clear"))
            assertEquals("the standard settings are cleared once", 1, phone.count("standard.clear"))
        }

    @Test(timeout = 120_000)
    fun `no synchronizer exists - the wipe does not wait for one to appear`() =
        FakePhone().use { phone ->
            phone.seedEverything()

            // The provider's current value is null and its getSynchronizer() never returns.
            phone.destroy()

            assertFalse("Destroy must not call getSynchronizer(): it waits for one to appear", "synchronizer.get" in phone.events)
            assertTrue("the running synchronizer is read from the provider's current value", "synchronizer.current" in phone.events)
            assertTrue(
                "the current value is read after the phrase store is gone",
                phone.events.indexOf("synchronizer.current") > phone.events.indexOf(phraseDelete)
            )
            assertEquals("no step waited at all (virtual time)", emptyList<Pair<String, Long>>(), phone.waits.toList())
            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
        }

    @Test(timeout = 120_000)
    fun `settings, chat stores and Flexa that never answer cannot hold the wipe`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.hangAt += listOf("encrypted.clear", "standard.clear", "clearAll", "flexa.disconnect")

            phone.destroy()

            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
            val waits = phone.waits.toMap()
            assertEquals("the encrypted settings: 2 s; waits=${phone.waits}", 2_000L, waits["zchat-destroy-W6-encrypted-settings"])
            assertEquals("the standard settings: 2 s", 2_000L, waits["zchat-destroy-W6-standard-settings"])
            assertEquals("the chat stores: 5 s", 5_000L, waits["zchat-destroy-W8-chat-stores"])
            assertEquals("Flexa: what is left of the shared 10 s", 1_000L, waits["zchat-destroy-W9-flexa"])
            assertEquals("exactly these four waits ran out: ${phone.waits}", 4, phone.waits.size)
        }

    @Test(timeout = 120_000)
    fun `nothing that talks to Android can hold the wipe either`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.hangAt +=
                listOf(
                    "keystore.list",
                    "android.list",
                    "stopService",
                    "encrypted.clear",
                    "standard.clear",
                    "clearAll",
                    "flexa.disconnect",
                    "startActivity",
                )

            phone.destroy()

            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
            val memorySteps = phone.waits.filter { (name, _) -> Regex("zchat-destroy-W[6-9]-.*").matches(name) }
            assertTrue("W6 to W9 share one limit of 10 s: $memorySteps", memorySteps.sumOf { it.second } <= 10_000L)
            assertTrue("no single wait is longer than 5 s: ${phone.waits}", phone.waits.all { it.second <= 5_000L })
            assertTrue(
                "the whole Destroy waits less than 20 s when nothing answers (virtual time): ${phone.waits}",
                phone.waits.sumOf { it.second } <= 20_000L
            )
        }

    @Test
    fun `Destroy does not call the SDK erase and never waits for a synchronizer to appear`() {
        val manager = DestroySources.code(DestroySources.MANAGER)
        val wipe = DestroySources.code(DestroySources.WIPE)
        assertTrue("DestroyWipe.kt exists", wipe.isNotEmpty())
        assertFalse("DestroyManager.kt must not name deleteSdkDataFlow", "deleteSdkDataFlow" in manager)
        assertFalse("DestroyWipe.kt must not name deleteSdkDataFlow", "deleteSdkDataFlow" in wipe)
        assertFalse("DestroyWipe.kt must not name getSynchronizer(", "getSynchronizer(" in wipe)
        // DestroyManager's unreachable remote-kill setup is the one place that may still wait for a synchronizer.
        val setup = DestroySources.block(manager, "suspend fun setupRemoteKill(").orEmpty()
        assertEquals(
            "getSynchronizer( may be named in setupRemoteKill only, not in destroyAll or anything it calls",
            Regex("""getSynchronizer\(""").findAll(setup).count(),
            Regex("""getSynchronizer\(""").findAll(manager).count()
        )
        for (function in listOf("suspend fun destroyAll(", "private suspend fun performFullWipe()", "private fun finalSweep()")) {
            val body = DestroySources.block(manager, function)
            assertTrue("fixture: $function in DestroyManager.kt", body != null)
            assertFalse("$function must not name getSynchronizer(", "getSynchronizer(" in body!!)
        }
    }

    @Test
    fun `control - Reset ZCHAT does not call the SDK erase either`() {
        val reset = DestroySources.code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt")
        assertFalse("ResetZashiUseCase calls deleteSdkDataFlow (ZC-090-R takes the SDK erase out of Reset)", "deleteSdkDataFlow(" in reset)
    }

    @Test(timeout = 120_000)
    fun `a step that throws an Error - the end of the process is still requested`() {
        val steps =
            listOf(
                "pm.firstInstallTime",
                "write",
                "rename",
                "sync",
                "delete",
                "keystore.list",
                "keystore.delete",
                "proc.list",
                "android.list",
                "encrypted.clear",
                "standard.clear",
                "stopService",
                "work.cancelAll",
                "notifications.cancelAll",
                "channels.list",
                "clipboard.clear",
                "clearAll",
                "flexa.disconnect",
                "startActivity",
            )
        val notEnded =
            steps.filterNot { step ->
                FakePhone().use { phone ->
                    phone.seedEverything()
                    phone.errorAt += step
                    phone.destroy()
                    assertTrue("fixture: the step $step was reached; events=${phone.events}", phone.count(step) > 0)
                    phone.processEndRequested
                }
            }
        assertEquals("an Error at these steps kept the process alive", emptyList<String>(), notEnded)
    }

    @Test(timeout = 120_000)
    fun `an Error at one step does not stop the later steps`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.errorAt += listOf("keystore.list", "proc.list", "encrypted.clear", "notifications.cancelAll", "clearAll")

            phone.destroy()

            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the standard settings are still cleared", "standard.clear" in phone.events)
            assertTrue("the clipboard is still cleared", "clipboard.clear" in phone.events)
            assertTrue("the uninstall is still asked for", "startActivity" in phone.events)
            assertTrue("the process end is requested", phone.processEndRequested)
        }

    @Test(timeout = 120_000)
    fun `a thread that throws during the wipe does not stop it and does not reach the app's crash handler`() {
        val seen = CopyOnWriteArrayList<Throwable>()
        val mine = Thread.UncaughtExceptionHandler { _, error -> seen += error }
        Thread.setDefaultUncaughtExceptionHandler(mine)
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.onUninstallRequest = {
                // A synchronizer whose database was deleted under it, on a thread of its own.
                val crashing = Thread { throw IllegalStateException("thrown on another thread during the wipe") }
                crashing.start()
                crashing.join()
            }

            phone.destroy()

            assertEquals("the app's handler (the crash reporter) must not see an error thrown during the wipe", 0, seen.size)
            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
            assertSame("the handler from before is back", mine, Thread.getDefaultUncaughtExceptionHandler())
        }
    }
}
