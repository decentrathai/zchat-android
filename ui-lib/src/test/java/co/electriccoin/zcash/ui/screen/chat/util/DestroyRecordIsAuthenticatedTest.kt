package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.lang.reflect.InvocationTargetException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ZC-1014 (register; the DESTROY-ADV-W finding F-W1; coordinator's decision of 15 Oct 2026) — the record of a Destroy that
 * keeps names is authenticated. A record with keep=contacts carries a MAC made with the wallet's own Android Keystore key
 * (the key the encrypted stores use; W3 deletes it with the wallet), so a start that still finds the recovery-phrase
 * store finishes such a Destroy only when the MAC verifies. Today a cut between the record and the phrase-store delete
 * is never finished (the middle-ground rule of the W fix ignores every keep=contacts record beside a live phrase store).
 *
 *  (a) killed between the record write and the phrase-store delete: the next start finishes the wipe; keep=contacts keeps
 *      only the names list (none exists yet at that cut, so nothing is kept);
 *  (b) a keep=contacts record with no MAC or a bad MAC while the phrase store exists: nothing is deleted and the app offers
 *      once "The last Destroy did not finish. Run it again?";
 *  (c) a record MAC'd by another wallet's key: nothing is deleted (and the offer);
 *  (d) a Destroy without keep, cut at the same place: everything is deleted at the next start, as the first spec says
 *      (its record format is pinned by the first spec's tests and carries no MAC), and nothing is offered;
 *  (e) a Keystore that cannot be used at the start: never a wipe. ZC1014-X1 (T7aD review of 19 Oct, finding 3): the seal
 *      check answers verifies / does not verify / cannot tell; a Keystore that throws or does not answer within the
 *      start's limit cannot tell, so the record is kept and the process ends (the start pass's cannot-judge path) and the
 *      next start judges again; nothing is offered yet;
 *  (f) ZC1014-X1: a seal check that does not answer in time is "cannot tell", as (e);
 *  (g) ZC1014-X1: the offer of (b) survives the end of the process before the chat list opens (it is on disk, beside the
 *      record), and is still shown once.
 *  (h) ZC1014-X2 (T7aD review b of 19 Oct, finding 3): a Keystore that cannot answer at every start does not end every
 *      start for ever: after three starts that could not check the seal (counted on disk beside the record), nothing is
 *      deleted, the record goes and the app offers once to run Destroy again, as for a seal that does not verify.
 *  The offer: the chat list shows it once and its answer runs the normal Destroy flow (the red button's).
 *
 * The record seal `DestroyRecordSeal` is reached by name; its key is replaced by an AES key held in memory. All names and
 * addresses are synthetic.
 */
class DestroyRecordIsAuthenticatedTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(120)

    private var handlerBefore: Thread.UncaughtExceptionHandler? = null
    private var keyBefore: Any? = null

    @Before
    fun setUp() {
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
        keyBefore = runCatching { sealField().get(null) }.getOrNull()
        takeOffer()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
        runCatching { sealField().set(null, keyBefore) }
        runCatching { takeOffer() }
    }

    private val record = "data/no_backup/zchat_destroy_pending"
    private val wiped = FakePhone.STAYS.sorted()

    // ZC-1066: what a finished keep Destroy leaves when its list was made (K1 before W2).
    private val keptWiped = (FakePhone.STAYS + "data/no_backup/zchat_kept_names").sorted()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun sealClass(): Class<*> =
        runCatching { Class.forName(SEAL) }.getOrNull()
            ?: throw AssertionError("there is no $SEAL: the record of a Destroy is not authenticated (ZC-1014)")

    private fun sealField() = sealClass().getDeclaredField("recordKey").apply { isAccessible = true }

    private fun useKey(key: () -> SecretKey?) {
        sealField().set(null, key)
    }

    private fun takeOffer(): Boolean {
        val type = sealClass()
        val method = type.methods.first { it.name == "takeRerunOffer" && it.parameterCount == 0 }
        return try {
            method.invoke(type.getField("INSTANCE").get(null)) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun KeepRig.start() {
        phone.killAt = null
        phone.dieAt.clear()
        phone.newProcess()
        phone.wipe("atProcessStart", phone.context)
    }

    private fun FakePhone.outcome(): String = wipeField("startOutcome")!!.javaClass.simpleName

    /** A keep=contacts Destroy cut right before the phrase store's delete: the record is whole, nothing is deleted. */
    private fun KeepRig.cutBeforeThePhraseStore(keep: Set<String> = setOf("contacts")) {
        phone.seedEverything()
        contacts += listOf("zs1sealalpha00000" to "Ann")
        val manager = manager(keep = keep)
        phone.killAt = { operation, file -> operation == "delete" && file == phone.phraseStore }
        phone.destroy(manager)
        assertTrue("fixture: the record is there", phone.recordFile.isFile)
        assertTrue("fixture: the phrase store is still there", phone.phraseStore.isFile)
    }

    private fun recordText(
        keep: String,
        mac: String?,
    ): String {
        val body = "zchat-destroy 1\ninstall=1700000000123\nstore=${FakePhone.storeHash("12a9aabbcc", "1288ddeeff")}\nkeep=$keep\n"
        val whole = body + "end=" + FakePhone.sha256Hex(body) + "\n"
        return if (mac == null) whole else whole + "mac=$mac\n"
    }

    // ZC1014-X1: the offer's own mark (beside the record) is not something of the wallet's: it is left out like the record.
    private fun FakePhone.snapshot(): Map<String, List<Byte>> =
        files().filter { it != record && it != offerMark && it != sealTries }.associateWith { File(root, it).readBytes().toList() }

    private val offerMark = "data/no_backup/zchat_destroy_rerun_offer"

    // ZC1014-X2: beside the record, how many starts could not check its seal (not the wallet's: left out like the record).
    private val sealTries = "data/no_backup/zchat_destroy_seal_tries"

    @Test
    fun `(a) a keep Destroy cut between the record and the phrase store is finished at the next start`() =
        KeepRig().use { rig ->
            val key = newKey()
            useKey { key }
            rig.cutBeforeThePhraseStore()
            assertTrue("the record carries a MAC", "\nmac=" in rig.phone.recordFile.readText())

            rig.start()

            // ZC-1066: K1 comes before W2, so the list was made before the cut and is kept.
            assertEquals("the wipe is finished and the list is kept", keptWiped, rig.phone.files())
            assertEquals("only the list's key is left", setOf(KeepRig.ALIAS), rig.phone.keys.toSet())
            assertEquals("Finished", rig.phone.outcome())
            assertFalse("nothing is offered", takeOffer())
        }

    @Test
    fun `(b) a keep record with no MAC or a bad MAC beside a live phrase store deletes nothing and offers a new Destroy`() {
        for (mac in listOf(null, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "not-base64")) {
            KeepRig().use { rig ->
                val key = newKey()
                useKey { key }
                rig.phone.seedEverything()
                rig.phone.put(record, recordText("contacts", mac))
                val before = rig.phone.snapshot()
                val keys = rig.phone.keys.toSet()

                rig.start()

                assertEquals("mac=$mac: nothing is deleted", before, rig.phone.snapshot())
                assertEquals("mac=$mac: every key stays", keys, rig.phone.keys.toSet())
                assertFalse("mac=$mac: the record is removed", rig.phone.recordFile.exists())
                assertEquals("Ignored", rig.phone.outcome())
                assertTrue("mac=$mac: the app offers to run Destroy again", takeOffer())
                assertFalse("mac=$mac: the offer is shown once", takeOffer())
            }
        }
    }

    @Test
    fun `(c) a record MAC'd by another wallet's key deletes nothing`() =
        KeepRig().use { rig ->
            val first = newKey()
            useKey { first }
            rig.cutBeforeThePhraseStore()
            val before = rig.phone.snapshot()
            val keys = rig.phone.keys.toSet()
            val other = newKey()
            useKey { other }

            rig.start()

            assertEquals("nothing is deleted", before, rig.phone.snapshot())
            assertEquals(keys, rig.phone.keys.toSet())
            assertTrue("the app offers to run Destroy again", takeOffer())
        }

    @Test
    fun `(d) a Destroy without keep cut at the same place is finished, its record keeps the first spec's form`() =
        KeepRig().use { rig ->
            val key = newKey()
            useKey { key }
            rig.cutBeforeThePhraseStore(keep = emptySet())
            val text = rig.phone.recordFile.readText()
            assertTrue("keep is empty", "\nkeep=\n" in text)
            assertFalse("the first spec's record carries no MAC line", "mac=" in text)

            rig.start()

            assertEquals("everything is deleted", wiped, rig.phone.files())
            assertEquals("Finished", rig.phone.outcome())
            assertFalse("nothing is offered", takeOffer())
        }

    @Test
    fun `(e) a Keystore that cannot be used at the start deletes nothing and keeps the record, never a wipe`() =
        KeepRig().use { rig ->
            val key = newKey()
            useKey { key }
            rig.cutBeforeThePhraseStore()
            val before = rig.phone.snapshot()
            val recordBefore = rig.phone.recordFile.readText()
            useKey { throw IllegalStateException("synthetic Keystore fault") }
            val kills = rig.phone.count("kill:")

            rig.start()

            assertEquals("nothing is deleted", before, rig.phone.snapshot())
            assertTrue("a seal check that cannot tell removed the record: the cut Destroy is dropped", rig.phone.recordFile.isFile)
            assertEquals("the record changed", recordBefore, rig.phone.recordFile.readText())
            assertTrue("the start that cannot tell does not end its process (cannot judge)", rig.phone.count("kill:") > kills)
            assertEquals("None", rig.phone.outcome())
            assertFalse("nothing is offered while the record waits for the next start", takeOffer())

            // The next start, with a Keystore that answers, finishes the Destroy.
            useKey { key }
            rig.start()
            assertEquals("the next start did not finish the Destroy", keptWiped, rig.phone.files())
            assertEquals("Finished", rig.phone.outcome())
        }

    @Test
    fun `(f) a seal check that does not answer in time keeps the record and ends the process`() {
        val stuck = java.util.concurrent.CountDownLatch(1)
        try {
            KeepRig().use { rig ->
                val key = newKey()
                useKey { key }
                rig.cutBeforeThePhraseStore()
                val before = rig.phone.snapshot()
                useKey {
                    stuck.await()
                    key
                }
                val kills = rig.phone.count("kill:")

                rig.start()

                assertEquals("nothing is deleted", before, rig.phone.snapshot())
                assertTrue("a slow Keystore removed the record: the cut Destroy is dropped", rig.phone.recordFile.isFile)
                assertTrue("the start that cannot tell does not end its process (cannot judge)", rig.phone.count("kill:") > kills)
                assertEquals("None", rig.phone.outcome())
                assertFalse("nothing is offered while the record waits for the next start", takeOffer())
            }
        } finally {
            stuck.countDown()
        }
    }

    @Test
    fun `(g) the offer survives the end of the process before the chat list opens, and is shown once`() =
        KeepRig().use { rig ->
            val key = newKey()
            useKey { key }
            rig.phone.seedEverything()
            rig.phone.put(record, recordText("contacts", null))
            val before = rig.phone.snapshot()

            rig.start()
            assertEquals("Ignored", rig.phone.outcome())
            assertFalse("fixture: the record is removed", rig.phone.recordFile.exists())

            // The process ends before the chat list opens: what it held in memory is gone.
            forgetInMemoryOffer()
            rig.start()

            assertEquals("nothing is deleted", before, rig.phone.snapshot())
            assertTrue("the offer did not survive the end of the process", takeOffer())
            assertFalse("the offer is shown once", takeOffer())
            forgetInMemoryOffer()
            rig.start()
            assertFalse("the offer came back after it was shown", takeOffer())
        }

    @Test
    fun `(h) after three starts that cannot check the seal the record goes and Destroy is offered again`() =
        KeepRig().use { rig ->
            val key = newKey()
            useKey { key }
            rig.cutBeforeThePhraseStore()
            val before = rig.phone.snapshot()
            val keys = rig.phone.keys.toSet()
            useKey { throw IllegalStateException("synthetic Keystore fault") }

            for (attempt in 1..2) {
                rig.start()
                assertTrue("start $attempt: the record is kept", rig.phone.recordFile.isFile)
                assertEquals("start $attempt", "None", rig.phone.outcome())
                assertFalse("start $attempt: nothing is offered yet", takeOffer())
            }
            rig.start()

            assertEquals("the third start that cannot check the seal deleted something", before, rig.phone.snapshot())
            assertEquals("a key was deleted", keys, rig.phone.keys.toSet())
            assertFalse(
                "the third start that cannot check the seal kept the record: every start ends for ever",
                rig.phone.recordFile.exists(),
            )
            assertEquals("Ignored", rig.phone.outcome())
            assertTrue("the app does not offer to run Destroy again", takeOffer())
            assertFalse("the count of tries is left on the phone", File(rig.phone.root, sealTries).exists())
        }

    /** A new process: the seal object's in-memory offer is as at a start (false). */
    private fun forgetInMemoryOffer() {
        val field = sealClass().getDeclaredField("rerunOffer").apply { isAccessible = true }
        (field.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

    @Test
    fun `the offer - the chat list shows it once and its answer runs the red button's flow`() {
        val list = code("$UI/screen/chat/view/ChatListView.kt")
        val at = list.indexOf("R.string.destroy_rerun_question")
        assertTrue("the chat list shows the question", at >= 0)
        val dialog = list.substring(list.lastIndexOf("AlertDialog(", at), list.indexOf("dismissButton", at))
        assertTrue("its answer runs the normal Destroy flow", "onDestroyTapped()" in dialog)
        val chat = code("$UI/screen/chat/AndroidChat.kt")
        assertEquals("AndroidChat takes the offer once", 1, Regex("""DestroyRecordSeal\.takeRerunOffer\(\)""").findAll(chat).count())
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("The last Destroy did not finish. Run it again?", en["destroy_rerun_question"])
        for (key in listOf("destroy_rerun_question", "destroy_rerun_yes", "destroy_rerun_no")) {
            assertTrue("no English $key", key in en)
            assertNotEquals("$key is not translated", en[key], es[key] ?: throw AssertionError("no Spanish $key"))
        }
    }

    private fun code(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        assertTrue("fixture: $path", file.isFile)
        return SourceScan.stripComments(file.readText())
    }

    private companion object {
        const val SEAL = "co.electriccoin.zcash.ui.screen.chat.util.DestroyRecordSeal"
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
    }
}
