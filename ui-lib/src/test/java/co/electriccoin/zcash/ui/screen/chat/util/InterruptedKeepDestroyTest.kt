package co.electriccoin.zcash.ui.screen.chat.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/**
 * DESTROY-ADV-W (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, 6, 6.1 and 10.2) —
 * a Destroy with the "keep names" choice that is cut off, and the start pass that finishes it.
 *
 *  - killed before the record's rename: no record, every file as before;
 *  - killed after the record and before the list's rename (ZC-1066: the phrase store is still there, K1 comes before
 *    W2): the start pass copies the names first, then finishes; the list and its key are kept;
 *  - killed after the list's rename, at each later step: the start pass leaves exactly the files of a full run, the
 *    list byte for byte, its key, and no record; a pass that finishes cancels the alarm and the job a kept Destroy
 *    arms; one that does not finish leaves them (B10);
 *  - a record with a missing line, a wrong end, another install or `keep=` with an unknown value deletes NOTHING: the
 *    list and every store stay byte for byte; the record is removed;
 *  - a second destroyAll on the same object changes neither the record nor the list;
 *  - in a process that is not the main one nothing is deleted and the end of the process is requested.
 *
 * All addresses and names are synthetic.
 */
class InterruptedKeepDestroyTest {
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

    private val list = "data/no_backup/zchat_kept_names"
    private val defaultLeft = FakePhone.STAYS.sorted()
    private val keptLeft = (FakePhone.STAYS + list).sorted()

    private fun KeepRig.prepare(): DestroyManager {
        phone.seedEverything()
        contacts += listOf(A to "Ann", B to "Bo")
        return manager(keep = setOf("contacts"))
    }

    private fun KeepRig.startPass() {
        phone.killAt = null
        phone.dieAt.clear()
        phone.newProcess()
        phone.wipe("atProcessStart", phone.context)
    }

    @Test
    fun `killed before the record's rename - no record, every file as before`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            val before = rig.phone.files()
            val keysBefore = rig.phone.keys.toSet()
            rig.phone.killAt = { operation, file -> operation == "rename" && file == rig.phone.recordFile }

            rig.phone.destroy(manager)

            assertFalse("no record", rig.phone.recordFile.exists())
            // What a cut before the rename leaves is the record's temp file; the next start removes it.
            assertEquals("every file as before", before, rig.phone.files() - "data/no_backup/zchat_destroy_pending.tmp")
            rig.startPass()
            assertEquals("a start with no record deletes nothing", before, rig.phone.files())
            assertEquals(keysBefore, rig.phone.keys.toSet())
        }

    @Test
    fun `killed after the record and before the list's rename - the start pass copies the names, then finishes`() =
        KeepRig().use { rig ->
            // ZC-1066: the record of a keep Destroy beside a live phrase store counts only with its seal (ZC-1014).
            val seal = Class.forName("co.electriccoin.zcash.ui.screen.chat.util.DestroyRecordSeal")
            val keyField = seal.getDeclaredField("recordKey").apply { isAccessible = true }
            val keyBefore = keyField.get(null)
            val recordKey =
                javax.crypto.KeyGenerator
                    .getInstance("AES")
                    .apply { init(256) }
                    .generateKey()
            keyField.set(null, { recordKey })
            try {
                val manager = rig.prepare()
                rig.phone.killAt = { operation, file -> operation == "rename" && file.name == "zchat_kept_names" }

                rig.phone.destroy(manager)

                assertTrue("fixture: the record is there", rig.phone.recordFile.isFile)
                assertTrue("fixture: the phrase store is still there (K1 before W2)", rig.phone.phraseStore.isFile)
                assertFalse("fixture: the list was not renamed", File(rig.phone.root, list).exists())
                rig.startPass()
                assertEquals("the names were not kept: the files of a full kept run", keptLeft, rig.phone.files())
                assertEquals("its key and no other", setOf(KeepRig.ALIAS), rig.phone.keys.toSet())
                assertEquals(
                    "the list does not hold the names",
                    setOf(A to "Ann", B to "Bo"),
                    KeptNamesApi.entries(KeptNamesApi.read(rig.readerStore())).toSet(),
                )
            } finally {
                keyField.set(null, keyBefore)
            }
        }

    @Test
    fun `killed after the list's rename at each later step - the start pass keeps the list and its key only`() {
        val cuts: List<Pair<String, (KeepRig) -> Unit>> =
            listOf(
                "W3 (the first key delete)" to { rig -> rig.phone.dieAt += "keystore.delete" },
                "W4 (other processes)" to { rig -> rig.phone.dieAt += "proc.list" },
                "W5 (the contact book's file)" to { rig ->
                    rig.phone.killAt = { op, file -> op == "delete" && file.name == "zchat_contact_book_enc.xml" }
                },
                "W6 (the settings in memory)" to { rig -> rig.phone.dieAt += "encrypted.clear" },
                "W7 (background work)" to { rig -> rig.phone.dieAt += "work.cancelAll" },
                "W9 (Flexa)" to { rig -> rig.phone.dieAt += "flexa.disconnect" },
            )
        for ((name, cut) in cuts) {
            KeepRig().use { rig ->
                val manager = rig.prepare()
                cut(rig)

                rig.phone.destroy(manager)

                val listFile = File(rig.phone.root, list)
                assertTrue("$name: fixture: the list was written before the cut", listFile.isFile)
                val listBytes = listFile.readBytes()
                rig.startPass()

                assertEquals("$name: the files of a full run, no record", keptLeft, rig.phone.files())
                assertArrayEquals("$name: the list byte for byte", listBytes, listFile.readBytes())
                assertEquals("$name: its key and no other", setOf(KeepRig.ALIAS), rig.phone.keys.toSet())
                assertEquals(
                    "$name: the start pass finished",
                    "Finished",
                    rig.phone
                        .wipeField("startOutcome")!!
                        .javaClass.simpleName
                )
            }
        }
    }

    @Test
    fun `a start pass that finishes cancels the alarm and the job, one that does not finish leaves them`() {
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.destroy(manager)
            assertEquals("fixture: one alarm armed", 1, rig.alarms.size)
            assertEquals("fixture: one job enqueued", 1, rig.jobs.size)
            val before = rig.phone.events.size
            rig.startPass()
            val pass = rig.phone.events.drop(before)
            assertTrue("the finishing pass cancels the alarm", "alarm.cancel" in pass)
            assertTrue("the finishing pass cancels the job", "work.cancelAll" in pass)
            assertEquals(keptLeft, rig.phone.files())
        }
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.destroy(manager)
            rig.phone.processName = null
            val before = rig.phone.events.size
            rig.startPass()
            val pass = rig.phone.events.drop(before)
            assertFalse("a pass that cannot judge cancels no alarm", "alarm.cancel" in pass)
            assertFalse("a pass that cannot judge cancels no job", "work.cancelAll" in pass)
            assertTrue("the record stays for the next start", rig.phone.recordFile.isFile)
        }
    }

    @Test
    fun `a record that does not fit deletes nothing - the list and every store stay, the record goes`() {
        val variants: List<Pair<String, (Long, String) -> String>> =
            listOf(
                "a missing line" to { install, store ->
                    val body = "zchat-destroy 1\ninstall=$install\nstore=$store\n"
                    body + "end=" + FakePhone.sha256Hex(body) + "\n"
                },
                "a wrong end" to { install, store ->
                    "zchat-destroy 1\ninstall=$install\nstore=$store\nkeep=contacts\nend=" + "0".repeat(64) + "\n"
                },
                "another install" to { install, store -> recordText(install + 1, store, "contacts") },
                "an unknown keep value" to { install, store -> recordText(install, store, "wallet") },
                "two keep values" to { install, store -> recordText(install, store, "contacts,wallet") },
            )
        for ((name, text) in variants) {
            KeepRig().use { rig ->
                rig.phone.seedEverything()
                rig.writeOlderList(listOf(A to "Ann"))
                // A Destroy that got past W2: the phrase store is gone, so only the record decides.
                rig.phone.phraseStore.delete()
                val store = FakePhone.storeHash("12a9aabbcc", "1288ddeeff")
                rig.phone.put("data/no_backup/zchat_destroy_pending", text(rig.phone.installTime, store))
                val before = rig.phone.files().filterNot { it.endsWith("zchat_destroy_pending") }
                val bytes = before.associateWith { File(rig.phone.root, it).readBytes().toList() }
                val keys = rig.phone.keys.toSet()

                rig.startPass()

                assertEquals("$name: nothing is deleted", before, rig.phone.files())
                assertEquals(
                    "$name: every file byte for byte",
                    bytes,
                    before.associateWith { File(rig.phone.root, it).readBytes().toList() }
                )
                assertEquals("$name: every key stays", keys, rig.phone.keys.toSet())
                assertFalse("$name: the record is gone", rig.phone.recordFile.exists())
            }
        }
    }

    @Test
    fun `control - a whole record with keep=contacts and the list on the phone is valid and spares the list`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.writeOlderList(listOf(A to "Ann"))
            rig.phone.phraseStore.delete()
            val store = FakePhone.storeHash("12a9aabbcc", "1288ddeeff")
            rig.phone.put("data/no_backup/zchat_destroy_pending", recordText(rig.phone.installTime, store, "contacts"))
            val bytes = File(rig.phone.root, list).readBytes()

            rig.startPass()

            assertEquals(keptLeft, rig.phone.files())
            assertArrayEquals(bytes, File(rig.phone.root, list).readBytes())
            assertEquals(setOf(KeepRig.ALIAS), rig.phone.keys.toSet())
        }

    @Test
    fun `a second destroyAll on the same object changes neither the record nor the list`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.destroy(manager)
            val record = rig.phone.recordFile.readBytes()
            val listBytes = File(rig.phone.root, list).readBytes()
            rig.contacts += listOf("zs1keepomega00000" to "Omega")

            rig.phone.destroy(manager)

            assertArrayEquals(record, rig.phone.recordFile.readBytes())
            assertArrayEquals(listBytes, File(rig.phone.root, list).readBytes())
        }

    @Test
    fun `in a process that is not the main one nothing is deleted and the end of the process is requested`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.dieAt += "keystore.delete"
            rig.phone.destroy(manager)
            rig.phone.dieAt.clear()
            rig.phone.newProcess()
            rig.phone.processName = "xyz.example.zchat:other"
            val before = rig.phone.files()
            val keys = rig.phone.keys.toSet()

            rig.phone.wipe("atProcessStart", rig.phone.context)

            assertEquals(before, rig.phone.files())
            assertEquals(keys, rig.phone.keys.toSet())
            assertTrue("the end of the process is requested", rig.phone.processEndRequested)
        }

    private companion object {
        const val A = "zs1keepalpha00000"
        const val B = "zs1keepbeta000000"

        /** A whole record with [keep] on its keep line, worked out here and not by the product. */
        fun recordText(
            install: Long,
            store: String,
            keep: String,
        ): String {
            val body = "zchat-destroy 1\ninstall=$install\nstore=$store\nkeep=$keep\n"
            return body + "end=" + FakePhone.sha256Hex(body) + "\n"
        }
    }
}
