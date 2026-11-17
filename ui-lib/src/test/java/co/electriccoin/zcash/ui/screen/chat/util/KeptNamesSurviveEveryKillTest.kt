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
 * ZC-1066 (register; Fable's decision of 19 Oct 2026 under W-57, `FABLE_ZC1066_KEPT_NAMES_20261019.md`) — the kept names
 * survive a kill at any point of a Destroy that keeps them. The copy of the names (K1) moves to after the record (W1) and
 * before the phrase store's delete (W2), its file is written whole (temp file, then rename), and a start that finds a
 * keep record whose seal verifies (ZC-1014), the phrase store still there and no list makes the copy first, then
 * finishes, and ends its process (it read stores that the pass then deleted).
 *
 *  (a) the list exists when W2 deletes the phrase store;
 *  (b) killed before the record: no list file anywhere, and the next start changes nothing;
 *  (c) killed after the record and before the list: the next start copies the names, finishes, keeps the list and its
 *      key, and ends its process;
 *  (d) killed after the list and before W2: the next start finishes and keeps the list as it was (no second copy).
 *
 * All addresses and names are synthetic.
 */
class KeptNamesSurviveEveryKillTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(180)

    private var handlerBefore: Thread.UncaughtExceptionHandler? = null
    private var keyBefore: Any? = null

    private val keyField =
        Class
            .forName("co.electriccoin.zcash.ui.screen.chat.util.DestroyRecordSeal")
            .getDeclaredField("recordKey")
            .apply { isAccessible = true }

    @Before
    fun setUp() {
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
        keyBefore = keyField.get(null)
        val key =
            javax.crypto.KeyGenerator
                .getInstance("AES")
                .apply { init(256) }
                .generateKey()
        keyField.set(null, { key })
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
        keyField.set(null, keyBefore)
    }

    private val list = "data/no_backup/zchat_kept_names"
    private val keptLeft = (FakePhone.STAYS + list).sorted()
    private val names = setOf("zs1keptkillalpha00001" to "Ann", "zs1keptkillbravo00001" to "Bo")

    private fun KeepRig.prepare(): DestroyManager {
        phone.seedEverything()
        contacts += names.toList()
        return manager(keep = setOf("contacts"))
    }

    private fun KeepRig.startPass() {
        phone.killAt = null
        phone.dieAt.clear()
        phone.newProcess()
        phone.wipe("atProcessStart", phone.context)
    }

    private fun KeepRig.outcome(): String = phone.wipeField("startOutcome")!!.javaClass.simpleName

    private fun KeepRig.kept(): Set<Pair<String, String>> = KeptNamesApi.entries(KeptNamesApi.read(readerStore())).toSet()

    @Test
    fun `(a) the list exists when the phrase store is deleted`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            var listAtW2: Boolean? = null
            rig.phone.killAt = { operation, file ->
                if (operation == "delete" && file == rig.phone.phraseStore) listAtW2 = File(rig.phone.root, list).isFile
                false
            }

            rig.phone.destroy(manager)

            assertEquals("the list was not there when the phrase store was deleted", true, listAtW2)
            assertEquals(names, rig.kept())
        }

    @Test
    fun `(b) killed before the record - no list file, and the next start changes nothing`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.killAt = { operation, file -> operation == "rename" && file == rig.phone.recordFile }

            rig.phone.destroy(manager)

            assertFalse("a list was made before the record", File(rig.phone.root, list).exists())
            assertFalse("a list's temp file was made before the record", File(rig.phone.root, "$list.tmp").exists())
            val before = rig.phone.files() - "data/no_backup/zchat_destroy_pending.tmp"
            rig.startPass()
            assertEquals("None", rig.outcome())
            assertEquals("the start changed something", before, rig.phone.files())
            assertTrue("the phrase store is gone", rig.phone.phraseStore.isFile)
        }

    @Test
    fun `(c) killed after the record and before the list - the next start copies, finishes and ends its process`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.killAt = { operation, file -> operation == "write" && file.name == "zchat_kept_names.tmp" }

            rig.phone.destroy(manager)

            assertTrue("fixture: the record is there", rig.phone.recordFile.isFile)
            assertTrue("fixture: the phrase store is still there", rig.phone.phraseStore.isFile)
            assertFalse("fixture: no list yet", File(rig.phone.root, list).exists())
            val kills = rig.phone.count("kill:")
            rig.startPass()

            assertEquals("Finished", rig.outcome())
            assertEquals("the names were not kept", keptLeft, rig.phone.files())
            assertEquals(setOf(KeepRig.ALIAS), rig.phone.keys.toSet())
            assertEquals(names, rig.kept())
            assertTrue(
                "the start that copied (and so read stores it then deleted) does not end its process",
                rig.phone.count("kill:") > kills,
            )
        }

    @Test
    fun `(d) killed after the list and before the phrase store - the next start keeps the list as it was`() =
        KeepRig().use { rig ->
            val manager = rig.prepare()
            rig.phone.killAt = { operation, file -> operation == "delete" && file == rig.phone.phraseStore }

            rig.phone.destroy(manager)

            val listFile = File(rig.phone.root, list)
            assertTrue("fixture: the list was written before the cut", listFile.isFile)
            val bytes = listFile.readBytes()
            rig.startPass()

            assertEquals("Finished", rig.outcome())
            assertEquals(keptLeft, rig.phone.files())
            assertTrue("the list changed", bytes.contentEquals(listFile.readBytes()))
            assertFalse("the start copied the names a second time", "names.startCopy" in rig.phone.events)
        }
}
