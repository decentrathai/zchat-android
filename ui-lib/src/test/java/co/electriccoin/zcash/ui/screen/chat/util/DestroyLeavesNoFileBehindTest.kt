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

/**
 * ZC-692-W (register ZC-692, high; owner rule W-41, and W-57: "Destroy everything" is true): when Destroy All hands
 * over to the end of its process, the app's own storage on the phone holds none of ZChat's files.
 *
 * Before the fix the wipe deleted the folders it knew by name: `no_backup/` kept everything but the SDK's folder,
 * a folder nobody named (`app_*`, the device-protected folder, a second storage volume) stayed, the two
 * phone-security keys were never deleted, message notifications stayed on screen, the clipboard kept what the user
 * copied, and the notification channels and the rows of cancelled jobs stayed.
 *
 * Wanted: the wipe deletes BY LISTING, so a file nobody named goes too. The proof is the list of FILES that are left
 * (register ZC-705: a store file can exist with its key material in the clear, so "the keys are deleted" proves
 * nothing): the system's `code_cache` and `lib`, the job library's own table and the record of the wipe, nothing
 * else. Every key goes, by name and by listing. Every notification, every channel (by listing) and the clipboard go.
 * All work is cancelled and its rows are cleared.
 */
class DestroyLeavesNoFileBehindTest {
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
    private val leftAfterAWipe = (FakePhone.STAYS + record).sorted()

    @Test
    fun `nothing is left but the code cache, the job table and the record`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val before = phone.files()
            assertTrue(
                "fixture: one file for every row and the files nobody named are on the phone",
                before.containsAll(FakePhone.ROWS_A2_TO_A16 + FakePhone.UNNAMED + phone.externalRows + FakePhone.PHRASE_STORE)
            )

            phone.destroy()

            assertEquals(leftAfterAWipe, phone.files())
        }

    @Test
    fun `what a late writer wrote during the uninstall request is gone at the end`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.onUninstallRequest = {
                // A cached store flushing its map after the sweep: the phrase store, an encrypted chat store whose
                // key material sits in the clear (the shape register ZC-705 describes), a plain file, and one in
                // each of the other roots.
                phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml("12a9aabbcc", "1288ddeeff"))
                phone.put(
                    "data/shared_prefs/zchat_e2e_keys_encrypted.xml",
                    FakePhone.phraseStoreXml("{\"primaryKeyId\":1,\"key\":[{\"keyData\":{\"value\":\"in the clear\"}}]}", "{}")
                )
                phone.put("data/files/late_write.json", "{}\n")
                phone.put("de/shared_prefs/late.xml")
                File(phone.volumes[1], "cache/late.tmp").apply { parentFile?.mkdirs() }.writeText("x\n")
            }

            phone.destroy()

            assertEquals("fixture: the uninstall request ran", 1, phone.count("startActivity"))
            assertEquals(leftAfterAWipe, phone.files())
        }

    @Test
    fun `every phone-security key is deleted, by name and by listing, on a thread of its own`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            assertEquals("fixture: two keys before", 2, phone.keys.size)
            phone.keys += "a_key_nobody_named"

            phone.destroy()

            assertEquals("no key is left", emptySet<String>(), phone.keys.toSet())
            assertTrue(
                "the two known names are deleted even when the listing would not show them",
                phone.events.containsAll(listOf("keystore.delete:_androidx_security_master_key_", "keystore.delete:zchat_ai_master_key"))
            )
            val keyThreads = phone.threadsOf("keystore.")
            val fileThreads = phone.threadsOf("delete:")
            assertTrue("fixture: keys and files were deleted", keyThreads.isNotEmpty() && fileThreads.isNotEmpty())
            assertEquals(
                "the key delete runs on another thread than the file deletes",
                emptySet<Thread>(),
                keyThreads intersect fileThreads
            )
            assertTrue(
                "the first key call comes after the phrase store is gone",
                phone.events.indexOfFirst { it.startsWith("keystore.") } > phone.events.indexOf("delete:" + FakePhone.PHRASE_STORE)
            )
        }

    @Test
    fun `notifications and clipboard - every notification, every listed channel and the clipboard go`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            assertEquals("fixture: the app's four channels", 4, phone.channels.size)
            phone.channels += "a_channel_a_later_version_added"
            phone.channelGroups += "a_group"

            phone.destroy()

            val events = phone.events
            val uninstall = events.indexOf("startActivity")
            val wipe = events.take(uninstall)
            assertEquals("one cancel-all before the uninstall request", 1, wipe.count { it == "notifications.cancelAll" })
            assertEquals("one clipboard clear before the uninstall request", 1, wipe.count { it == "clipboard.clear" })
            assertEquals(
                "every channel the phone lists is deleted before the uninstall request: the app's four and the fifth",
                setOf(
                    "channel.delete:sync_channel_v2",
                    "channel.delete:chat_messages_v3",
                    "channel.delete:incoming_calls_v1",
                    "channel.delete:tex_recovery",
                    "channel.delete:a_channel_a_later_version_added",
                ),
                wipe.filter { it.startsWith("channel.delete:") }.toSet()
            )
            assertTrue("a channel group is deleted too", "channelGroup.delete:a_group" in wipe)
            assertEquals("no channel is left", emptyList<String>(), phone.channels.toList())
            assertEquals("no channel group is left", emptyList<String>(), phone.channelGroups.toList())
            val firstChannelDelete = wipe.indexOfFirst { it.startsWith("channel.delete:") }
            assertTrue(
                "the sync service is stopped and the notifications are cancelled before a channel is deleted",
                wipe.indexOf("stopService") in 0 until firstChannelDelete &&
                    wipe.indexOf("notifications.cancelAll") in 0 until firstChannelDelete
            )
            // The final pass repeats the cancels: a notice the still-running app posted meanwhile goes too.
            val finalPass = events.drop(uninstall)
            assertEquals("one cancel-all in the final pass", 1, finalPass.count { it == "notifications.cancelAll" })
            assertTrue("the final pass lists the channels again", "channels.list" in finalPass)
        }

    @Test
    fun `background jobs are cleared - all work is cancelled and then its rows are cleared`() {
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.destroy()
            val wipe = phone.events.take(phone.events.indexOf("startActivity"))
            assertEquals(
                "one cancelAllWork and then one pruneWork before the uninstall request",
                listOf("work.cancelAll", "work.prune"),
                wipe.filter { it.startsWith("work.") }
            )
        }
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.failAt += "work.cancelAll"
            phone.destroy()
            val wipe = phone.events.take(phone.events.indexOf("startActivity"))
            assertEquals(
                "pruneWork is still called when cancelAllWork throws",
                listOf("work.cancelAll", "work.prune"),
                wipe.filter { it.startsWith("work.") }
            )
            assertEquals("the wipe goes on", leftAfterAWipe, phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
        }
    }

    @Test
    fun `with no services, no application context and no package manager the real calls run to the end`() {
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.useRealCalls()
            // As DestroyLeavesNoStoreBehindTest builds it: only the context and the chat stores are set.
            phone.destroy(phone.manager(wired = false))
            assertEquals(leftAfterAWipe, phone.files())
            assertTrue("the uninstall is asked for", "startActivity" in phone.events)
            assertFalse("Destroy never starts the sync service", "startService" in phone.events)
        }
        FakePhone(packageName = null).use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml("12aa", "12bb"))
            phone.put("data/files/x")
            phone.useRealCalls()
            phone.destroy(phone.manager(wired = false))
            assertEquals("with no package name the wipe still deletes", listOf(record), phone.files())
        }
    }

    @Test
    fun `the final pass lists through applicationInfo and off the caller's thread`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val caller = Thread.currentThread()

            phone.destroy()

            assertTrue(
                "the final pass reads applicationInfo.dataDir after the uninstall request",
                phone.dataDirReadsAfterUninstall.isNotEmpty()
            )
            assertTrue("no part of the wipe runs on the caller's thread", phone.dataDirReadsAfterUninstall.none { it === caller })
            assertTrue("no file is deleted on the caller's thread", phone.threadsOf("delete:").none { it === caller })
        }

    @Test
    fun `folders do not count - the sweep reports files only`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.put("data/lib/libexample.so")
            phone.destroy()
            listOf("data/cache", "data/files", "data/shared_prefs", "data/databases", "de/cache", "de/code_cache")
                .forEach { File(phone.root, it).mkdirs() }

            assertEquals(
                "empty folders, lib, code_cache, the job table and the record are not what is left",
                emptyList<String>(),
                phone.wipe("sweepFiles", phone.context)
            )
            assertTrue("the system's lib folder is not touched", File(phone.root, "data/lib/libexample.so").isFile)
            assertTrue("empty folders may stay", File(phone.root, "de/code_cache").isDirectory)

            val stubborn = phone.put("data/files/stubborn.bin")
            phone.refuseDelete = { it == stubborn }
            assertEquals(
                "a file the storage would not delete is what is left",
                listOf(stubborn.path),
                phone.wipe("sweepFiles", phone.context)
            )
        }

    @Test
    fun `the list of what may be left holds no notification channel`() =
        FakePhone().use { phone ->
            assertEquals(
                listOf("lib", "code_cache", "no_backup/androidx.work.workdb*", "no_backup/zchat_destroy_pending"),
                phone.wipeField("MAY_BE_LEFT")
            )
        }
}
