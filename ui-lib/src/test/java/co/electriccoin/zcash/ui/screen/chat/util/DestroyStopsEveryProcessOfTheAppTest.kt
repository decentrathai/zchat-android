package co.electriccoin.zcash.ui.screen.chat.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * ZC-692-W (register ZC-692, high; the Destroy side of ZC-706): Destroy All ends every other process of the app.
 *
 * After a crash a second process of ZChat (`<package>:crash`) can be alive. Before ZC-706 it ran the whole app start
 * and so held the recovery phrase in its memory and a cached copy of the phrase store that any write puts back on
 * disk, whole; Destroy All ended only its own process, so that copy outlived the wipe. Destroy does not rest on
 * ZC-706's fix: it ends the other processes itself, after the phrase store is gone and before the other files, and
 * looks again in the final pass. It asks two lists, the phone's own (`/proc`) first and Android's second, and it
 * never ends a process that is not this app's.
 */
class DestroyStopsEveryProcessOfTheAppTest {
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

    private val crashPid = 4001
    private val leftAfterAWipe = (FakePhone.STAYS + "data/no_backup/zchat_destroy_pending").sorted()
    private val phraseDelete = "delete:" + FakePhone.PHRASE_STORE

    private fun FakePhone.crashProcess() = Triple(crashPid, ownUid, "$packageName:crash")

    private fun FakePhone.ended(): List<String> = events.filter { it.startsWith("kill:") }

    @Test
    fun `a live crash process is ended after the phrase store is gone and before the other files`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.procList += phone.crashProcess()
            phone.androidList += phone.crashProcess()

            phone.destroy()

            val events = phone.events
            val end = events.indexOf("kill:$crashPid")
            val phrase = events.indexOf(phraseDelete)
            val otherFile = events.indexOfFirst { it.startsWith("delete:") && !it.startsWith(phraseDelete) }
            assertTrue("the crash process is ended; events=$events", end >= 0)
            assertTrue("after the phrase file is gone (phrase $phrase, end $end)", phrase in 0 until end)
            assertTrue("before the other files are deleted (end $end, first other file $otherFile)", end < otherFile)
            assertEquals("it is ended once and this process last", listOf("kill:$crashPid", "kill:${phone.ownPid}"), phone.ended())
            val finalPass = events.drop(events.indexOf("startActivity"))
            assertTrue(
                "both lists are read again in the final pass; after the uninstall request: $finalPass",
                "proc.list" in finalPass && "android.list" in finalPass
            )
            assertEquals(leftAfterAWipe, phone.files())
        }

    @Test
    fun `a process only one of the two lists shows is ended`() {
        FakePhone().use { phone ->
            phone.procList += phone.crashProcess()
            phone.destroy()
            assertTrue("only the phone's own list shows it: ${phone.ended()}", "kill:$crashPid" in phone.ended())
        }
        FakePhone().use { phone ->
            phone.androidList += phone.crashProcess()
            phone.destroy()
            assertTrue("only Android's list shows it: ${phone.ended()}", "kill:$crashPid" in phone.ended())
        }
    }

    @Test
    fun `a process that does not die - every file is still deleted and the process end is requested`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.procList += phone.crashProcess()
            phone.androidList += phone.crashProcess()
            phone.unkillable += crashPid

            phone.destroy()

            assertTrue("it was asked to end", "kill:$crashPid" in phone.ended())
            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
        }

    @Test
    fun `an Android list that never answers is waited for half a second and no longer`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.procList += phone.crashProcess()
            phone.hangAt += "android.list"

            phone.destroy()

            assertTrue("the process the phone's own list shows is ended all the same", "kill:$crashPid" in phone.ended())
            val listWaits = phone.waits.filter { "W4" in it.first }
            assertTrue("fixture: the wait for Android's list ran out: ${phone.waits}", listWaits.isNotEmpty())
            assertTrue("no wait for Android's list is longer than 0.5 s: $listWaits", listWaits.all { it.second <= 500L })
            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
        }

    @Test
    fun `alone - nothing is ended but this process, at the end`() =
        FakePhone().use { phone ->
            phone.seedEverything()

            phone.destroy()

            assertEquals(listOf("kill:${phone.ownPid}"), phone.ended())
            assertEquals(
                "the request to end this process is the last thing the phone is asked",
                "kill:${phone.ownPid}",
                phone.events.last { it != "handler.swap" }
            )
        }

    @Test
    fun `a process with another name or another uid is never ended`() =
        FakePhone().use { phone ->
            val pkg = phone.packageName!!
            val foreign =
                listOf(
                    Triple(5001, phone.ownUid + 1, "$pkg:crash"),
                    Triple(5002, phone.ownUid + 1, pkg),
                    Triple(5003, phone.ownUid, "com.example.other"),
                    Triple(5004, phone.ownUid, "$pkg.other"),
                    Triple(5005, phone.ownUid, "${pkg}2:crash"),
                    Triple(5006, phone.ownUid, ""),
                )
            phone.procList += foreign
            phone.androidList += foreign

            phone.destroy()

            assertEquals(listOf("kill:${phone.ownPid}"), phone.ended())
        }

    @Test
    fun `with no package name no process is ended`() =
        FakePhone(packageName = null).use { phone ->
            phone.procList += Triple(crashPid, phone.ownUid, "pkg:crash")
            phone.androidList += Triple(crashPid, phone.ownUid, "null:crash")

            phone.destroy()

            assertEquals(listOf("kill:${phone.ownPid}"), phone.ended())
        }
}
