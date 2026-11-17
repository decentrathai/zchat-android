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
 * ZC692S-X1 (fix-all 2.13.0, train T6b fixup; T6b cross review, must-fix 6, GPT ZC-692-S F1 and Grok ZC-692-S F1; an
 * addendum to the registered start-pass residual R-9) — the start pass never finishes a Destroy on a wallet whose
 * phrase store it cannot see, and a record it cannot remove never lets a new wallet be made beside it.
 *
 * Two storage faults, each rare, make an unasked wipe today:
 * 1. A record that does not fit (it names another phrase store, or another install) cannot be removed. The start pass
 *    says Ignored and the app opens: the record stays on the phone.
 * 2. At a later start the phone cannot say whether the phrase store is there (`File.exists()` answers false on an I/O
 *    error or a refused lookup). The pass skips the comparison of the store with the record and finishes that old
 *    Destroy: the phone-security keys, the chats and the wallet's files of the LIVE wallet are deleted.
 *
 * Wanted: an existence check that cannot answer is no answer: nothing is deleted, the record stays and the process
 * ends, as for any start that cannot judge its record. A record that does not fit and cannot be removed blocks the
 * making of a wallet when no phrase store is on the phone (RecordStuck), and never blocks a wallet that is there.
 *
 * Every value is synthetic. Nothing here touches a real phone.
 */
class StartPassNeverWipesOnAStoreItCannotSeeTest {
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

    private fun FakePhone.start() {
        wipe("atProcessStart", context)
    }

    private fun FakePhone.outcome(): String = wipeField("startOutcome")!!.javaClass.simpleName

    /** Every file of the app's own storage with its bytes, without the record. */
    private fun FakePhone.snapshot(): Map<String, String> =
        files().filter { it != record }.associateWith { File(root, it).readBytes().joinToString(",") }

    private fun FakePhone.putRecord(
        install: Long = installTime,
        store: String = FakePhone.storeHash("12a9aabbcc", "1288ddeeff")
    ): File = put(record, FakePhone.recordText(install, store))

    private fun assertNothingWasDeleted(
        phone: FakePhone,
        before: Map<String, String>,
        keysBefore: Set<String>
    ) {
        assertEquals("every file is byte for byte as before", before, phone.snapshot())
        assertEquals("a phone-security key was deleted", keysBefore, phone.keys.toSet())
        assertFalse("a key was deleted: ${phone.events}", phone.events.any { it.startsWith("keystore.delete") })
        assertFalse(
            "something besides the record was handed to the delete call: ${phone.events}",
            phone.events.any { it.startsWith("delete:") && it != "delete:$record" }
        )
    }

    @Test
    fun `a record that does not fit and stays, then a start that cannot see the phrase store - nothing is deleted`() =
        FakePhone().use { phone ->
            // A live wallet, and a record that names another phrase store and cannot be removed.
            phone.seedEverything()
            phone.putRecord(store = FakePhone.storeHash("another", "wallet"))
            phone.refuseDelete = { it == phone.recordFile }
            phone.start()
            assertEquals("fixture sanity: the record does not fit the wallet on the phone", "Ignored", phone.outcome())
            assertTrue("fixture sanity: the record could not be removed", phone.recordFile.isFile)
            val before = phone.snapshot()
            val keysBefore = phone.keys.toSet()

            // The next start: the phone cannot look inside the folder that holds the phrase store.
            phone.newProcess()
            val prefs = phone.phraseStore.parentFile!!
            assertTrue("fixture sanity: the folder's search permission can be taken", prefs.setExecutable(false, false))
            try {
                assertFalse(
                    "fixture sanity: the test runs with a user that the folder's permission binds (not root)",
                    phone.phraseStore.exists()
                )
                phone.start()
            } finally {
                prefs.setExecutable(true, false)
            }

            assertNothingWasDeleted(phone, before, keysBefore)
            assertTrue("the record is gone although nothing was judged", phone.recordFile.isFile)
            assertTrue(
                "a start that could not tell whether the phrase store is there did not end its process: ${phone.events}",
                phone.processEndRequested
            )
        }

    @Test
    fun `a record that does not fit and cannot be removed, with no phrase store - RecordStuck, nothing deleted`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            assertTrue("fixture sanity", phone.phraseStore.delete())
            phone.putRecord(install = phone.installTime + 1)
            phone.refuseDelete = { it == phone.recordFile }
            val before = phone.snapshot()
            val keysBefore = phone.keys.toSet()

            phone.start()

            assertNothingWasDeleted(phone, before, keysBefore)
            assertTrue("fixture sanity: the record could not be removed", phone.recordFile.isFile)
            assertEquals(
                "a record that does not fit and could not be removed let the app start, so a new wallet can be made " +
                    "beside it",
                "RecordStuck",
                phone.outcome()
            )
        }

    /** Control, green before and after: a wallet on the phone is never blocked by a record it cannot remove. */
    @Test
    fun `control - a record that does not fit and cannot be removed never blocks the wallet that is there`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.putRecord(install = phone.installTime + 1)
            phone.refuseDelete = { it == phone.recordFile }
            val before = phone.snapshot()
            val keysBefore = phone.keys.toSet()

            phone.start()

            assertNothingWasDeleted(phone, before, keysBefore)
            assertEquals("Ignored", phone.outcome())
            assertFalse(phone.processEndRequested)
        }
}
