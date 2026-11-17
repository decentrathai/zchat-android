package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * DESTROY-ADV-W (register ZC-722; owner W-41, W-43, W-47, W-52, W-57; Destroy Advanced spec revision 2c, decision A,
 * sections 6, 6.1, 8 and 10.2) — with the saved choice "Names of your chat contacts", Destroy All keeps ONE list of
 * names and addresses under ONE key of its own and destroys everything else.
 *
 *  - K1, right after the recovery phrase is erased (W2) and before the first key goes (W3): the chat contact book and
 *    the nicknames are read (the nickname first), each on its own worker, all within 2 s; the list is written through
 *    the kept-names store. Nothing is read from the recovery-phrase store.
 *  - W3 deletes every key but `zchat_kept_names_key` and W5 every file but `no_backup/zchat_kept_names`, only when the
 *    list's file exists. The shared key `_androidx_security_master_key_`, the contact book, the nicknames and
 *    `files/address_book/` are gone as in every Destroy.
 *  - the record says `keep=contacts`; no uninstall is requested (it would remove the list); instead one sync alarm is
 *    armed and one unique one-time job is enqueued after W7's cancels, before the end of the process (B10).
 *  - a copy that cannot be made in time (a contact book that never answers or answers late, a key box that throws, no
 *    names) keeps nothing: the default Destroy, uninstall request included; a late answer writes nothing afterwards.
 *  - an older list that cannot be read is never replaced (B7): it and its key are spared as they are.
 *  - the choice follows the wallet (K0): a choice read before the wallet-scope generation went up does not count.
 *  - controls: the empty choice, a choice never read and an unknown value give the default Destroy, and with the empty
 *    choice an older list and its key are destroyed too.
 *
 * Every new name is reached by name. All addresses and names are synthetic.
 */
class DestroyKeepsNamesOnlyTest {
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
    private val list = "data/no_backup/zchat_kept_names"
    private val defaultResult = (FakePhone.STAYS + record).sorted()
    private val keptResult = (FakePhone.STAYS + record + list).sorted()

    @Test
    fun `with the choice only the list of names and its key are left, and no uninstall is requested`() =
        KeepRig().use { rig ->
            val phone = rig.phone
            phone.seedEverything()
            rig.contacts += listOf(A to "Ann", B to "Bo")
            rig.nicknames += mapOf(A to "Annie", C to "Cy")
            val manager = rig.manager(keep = setOf("contacts"))

            phone.destroy(manager)

            assertEquals("the files left", keptResult, phone.files())
            for (gone in listOf(
                "data/shared_prefs/zchat_contact_book_enc.xml",
                "data/shared_prefs/zchat_nicknames_encrypted.xml",
                "data/files/address_book/address_book_1",
            )) {
                assertFalse("$gone is left", gone in phone.files())
            }
            assertEquals("the stand-in Keystore holds the list's key and nothing else", setOf(KeepRig.ALIAS), phone.keys.toSet())
            assertEquals(
                "the list holds exactly the three names, the nickname first",
                setOf(A to "Annie", B to "Bo", C to "Cy"),
                KeptNamesApi.entries(KeptNamesApi.read(rig.readerStore())).toSet()
            )
            assertTrue("the record says keep=contacts", "\nkeep=contacts\n" in phone.recordFile.readText())
            assertEquals("no uninstall request: it would remove the list", 0, phone.count("startActivity"))
            assertTrue("the end of the process is requested", phone.processEndRequested)

            // Order (ZC-1066, Fable's decision of 19 Oct: W1 -> K1 -> W2): the record, the copy, the phrase-store file,
            // then the first key delete.
            val copy = rig.contactReads.single()
            assertTrue("the record was written before the copy", copy.recordThere)
            assertTrue("the phrase store was still there when the copy read the contacts (ZC-1066: K1 comes before W2)", copy.phraseThere)
            assertFalse("a key was deleted before the copy", copy.keyCallsBefore)
            assertFalse("a key was deleted before the list was sealed", rig.sealsAfterKeyDelete.get() > 0)
            assertEquals(
                "the copy reads nothing of the recovery-phrase store",
                listOf("encrypted.clear"),
                phone.events.filter { it.startsWith("encrypted.") }
            )
            assertTrue(
                "the phrase-store provider is reached after the copy, by W6 only",
                phone.events.indexOf("encrypted.clear") > copy.eventsBefore
            )
        }

    @Test
    fun `after a kept Destroy one alarm is armed and one unique job is enqueued, after W7 and before the end`() =
        KeepRig().use { rig ->
            val phone = rig.phone
            phone.seedEverything()
            rig.contacts += listOf(A to "Ann")
            phone.destroy(rig.manager(keep = setOf("contacts")))

            assertEquals("one alarm", 1, rig.alarms.size)
            assertEquals("one job", 1, rig.jobs.size)
            for (call in rig.alarms + rig.jobs) {
                assertTrue("armed after W7's cancel-all", call.cancelAllBefore)
                assertFalse("armed after the end of the process was requested", call.endBefore)
            }
        }

    @Test
    fun `with no list neither an alarm nor a job is armed`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.phone.destroy(rig.manager(keep = emptySet()))
            assertEquals(defaultResult, rig.phone.files())
            assertTrue(rig.alarms.isEmpty())
            assertTrue(rig.jobs.isEmpty())
        }

    @Test
    fun `a contact book that never answers - after the limit the default Destroy runs, uninstall included`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.contactBookHangs = true
            rig.phone.destroy(rig.manager(keep = setOf("contacts")))
            assertDefaultResult(rig)
        }

    @Test
    fun `a contact book that answers after 3 s - the default Destroy, and no list appears afterwards`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.contacts += listOf(A to "Ann")
            rig.contactBookDelayMs = 3_000
            rig.phone.destroy(rig.manager(keep = setOf("contacts")))
            assertDefaultResult(rig)
            assertTrue("fixture: the late answer came", rig.lateAnswer.await(10, TimeUnit.SECONDS))
            Thread.sleep(500)
            assertFalse("a late answer wrote the list", File(rig.phone.root, list).exists())
            assertFalse("a late answer left a temp file", File(rig.phone.root, "$list.tmp").exists())
            assertFalse("a late answer made the list's key", KeepRig.ALIAS in rig.phone.keys)
        }

    @Test
    fun `a key box that throws - the default Destroy`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.contacts += listOf(A to "Ann")
            rig.keyBoxThrows = true
            rig.phone.destroy(rig.manager(keep = setOf("contacts")))
            assertDefaultResult(rig)
        }

    @Test
    fun `no contacts and no nicknames - the default Destroy`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.phone.destroy(rig.manager(keep = setOf("contacts")))
            assertDefaultResult(rig)
        }

    @Test
    fun `an older list that cannot be read is never replaced - it and its key are spared, no uninstall`() {
        for (fault in listOf("another key", "a key box that throws on its read")) {
            KeepRig().use { rig ->
                rig.phone.seedEverything()
                rig.writeOlderList(listOf(D to "Dee"))
                val older = File(rig.phone.root, list).readBytes()
                rig.contacts += listOf(A to "Ann")
                if (fault == "another key") rig.useAnotherKey() else rig.keyBoxThrowsOnRead = true

                rig.phone.destroy(rig.manager(keep = setOf("contacts")))

                assertArrayEquals("$fault: the older list is byte for byte as before", older, File(rig.phone.root, list).readBytes())
                assertEquals("$fault: its key is spared", setOf(KeepRig.ALIAS), rig.phone.keys.toSet())
                assertEquals("$fault: the files left", keptResult, rig.phone.files())
                assertEquals("$fault: no uninstall request", 0, rig.phone.count("startActivity"))
            }
        }
    }

    @Test
    fun `adding to an older list that can be read keeps its names`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.writeOlderList(listOf(D to "Dee", A to "Old Ann"))
            rig.contacts += listOf(A to "Ann")
            rig.phone.destroy(rig.manager(keep = setOf("contacts")))
            assertEquals(
                setOf(A to "Ann", D to "Dee"),
                KeptNamesApi.entries(KeptNamesApi.read(rig.readerStore())).toSet()
            )
        }

    @Test
    fun `the choice follows the wallet - a choice read before the generation went up does not count`() {
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.contacts += listOf(A to "Ann")
            val manager = rig.manager(keep = setOf("contacts"))
            // Reset ZCHAT with keep app data, then another phrase's claim: the stores are reloaded for another wallet.
            rig.generation.incrementAndGet()
            rig.phone.destroy(manager)
            assertDefaultResult(rig, recordKeep = "")
        }
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.contacts += listOf(A to "Ann")
            val manager = rig.manager(keep = setOf("contacts"))
            rig.generation.incrementAndGet()
            rig.reload(manager)
            rig.phone.destroy(manager)
            assertEquals("read again after the change, it counts", keptResult, rig.phone.files())
        }
    }

    @Test
    fun `controls - the empty choice, a choice never read and an unknown value destroy everything`() {
        for (case in listOf("empty", "never read", "unknown")) {
            KeepRig().use { rig ->
                rig.phone.seedEverything()
                rig.contacts += listOf(A to "Ann")
                when (case) {
                    "empty" -> rig.manager(keep = emptySet())
                    "never read" -> rig.manager(keep = setOf("contacts"), read = false)
                    else -> rig.manager(keep = setOf("wallet"))
                }.also { rig.phone.destroy(it) }
                assertDefaultResult(rig, case, recordKeep = "")
            }
        }
    }

    @Test
    fun `control - with the empty choice an older list and its key are destroyed`() =
        KeepRig().use { rig ->
            rig.phone.seedEverything()
            rig.writeOlderList(listOf(D to "Dee"))
            rig.phone.destroy(rig.manager(keep = emptySet()))
            assertDefaultResult(rig, recordKeep = "")
        }

    private fun assertDefaultResult(
        rig: KeepRig,
        case: String = "",
        recordKeep: String = "contacts",
    ) {
        assertEquals("$case: the default Destroy's files", defaultResult, rig.phone.files())
        assertEquals("$case: no key is left", emptySet<String>(), rig.phone.keys.toSet())
        assertEquals("$case: the uninstall is requested", 1, rig.phone.count("startActivity"))
        // The record is written once, at W1, before the copy: a copy that fails leaves keep=contacts on it (the start
        // pass then finds no list and deletes everything); a choice that does not count leaves keep= empty.
        assertTrue("$case: the record's keep line", "\nkeep=$recordKeep\n" in rig.phone.recordFile.readText())
        assertTrue("$case: no alarm is armed", rig.alarms.isEmpty())
        assertTrue("$case: no job is enqueued", rig.jobs.isEmpty())
    }

    private companion object {
        const val A = "zs1keepalpha00000"
        const val B = "zs1keepbeta000000"
        const val C = "zs1keepgamma00000"
        const val D = "zs1keepdelta00000"
    }
}

/**
 * A [FakePhone] with what a Destroy that keeps names needs: a [DestroyManager] built without its constructor and given
 * a contact book, the kept-names store over the phone's `no_backup/` (its file steps go through the phone's stand-ins,
 * so a kill or a power cut reaches them) with a key box whose key sits in the phone's stand-in Keystore, a
 * ZchatPreferences double that answers the choice, the nicknames and the wallet-scope generation, and stand-ins for
 * the alarm and the job a kept Destroy arms (B10).
 */
internal class KeepRig : AutoCloseable {
    val phone = FakePhone()

    val contacts: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    val nicknames: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val generation = AtomicLong(7)

    @Volatile var contactBookHangs = false

    @Volatile var contactBookDelayMs = 0L

    @Volatile var keyBoxThrows = false

    @Volatile var keyBoxThrowsOnRead = false

    val lateAnswer = CountDownLatch(1)
    private val hang = CountDownLatch(1)

    class Snapshot(
        val recordThere: Boolean,
        val phraseThere: Boolean,
        val keyCallsBefore: Boolean,
        val eventsBefore: Int,
    )

    class Armed(
        val cancelAllBefore: Boolean,
        val endBefore: Boolean,
    )

    val contactReads: MutableList<Snapshot> = CopyOnWriteArrayList()
    val sealsAfterKeyDelete = AtomicLong(0)
    val alarms: MutableList<Armed> = CopyOnWriteArrayList()
    val jobs: MutableList<Armed> = CopyOnWriteArrayList()

    private var key: SecretKey = newKey()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun useAnotherKey() {
        key = newKey()
    }

    // ---- the two calls a kept Destroy arms, replaced for this rig and put back at close ----------------------------

    private val seams = listOf("armStartAlarm", "enqueueStartJob")
    private val before = HashMap<String, Any?>()

    init {
        for (name in seams) {
            before[name] = runCatching { phone.wipeField(name) }.getOrNull()
            val into = if (name == "armStartAlarm") alarms else jobs
            val standIn: (android.content.Context) -> Unit = {
                val events = phone.events
                into += Armed(cancelAllBefore = "work.cancelAll" in events, endBefore = phone.processEndRequested)
            }
            runCatching { phone.setWipeField(name, standIn) }
        }
    }

    // ZC-1066: the start pass's copy reads this rig's contact book and nicknames into its list.
    init {
        phone.startCopy = {
            val names = LinkedHashMap<String, String>()
            contacts.forEach { (address, name) -> if (name.isNotBlank()) names[address] = name }
            nicknames.forEach { (address, name) -> if (name.isNotBlank()) names[address] = name }
            names.isNotEmpty() && KeptNamesApi.add(store(), names.map { it.key to it.value })
        }
    }

    override fun close() {
        hang.countDown()
        for (name in seams) runCatching { phone.setWipeField(name, before[name]) }
        phone.close()
    }

    // ---- the kept-names store over the phone ------------------------------------------------------------------------

    private fun box(): Any {
        // Like every stand-in of the phone, the key box does nothing once the phone was killed (FakePhone.dead).
        fun alive() {
            if (phone.dead) throw FakePhone.PhoneError("dead at the kept list's key")
        }
        val existing: () -> SecretKey? = {
            alive()
            if (keyBoxThrows || keyBoxThrowsOnRead) throw IllegalStateException("synthetic Keystore fault")
            if (ALIAS in phone.keys) key else null
        }
        val make: () -> SecretKey = {
            alive()
            if (keyBoxThrows) throw IllegalStateException("synthetic Keystore fault")
            if (phone.events.any { it.startsWith("keystore.delete") }) sealsAfterKeyDelete.incrementAndGet()
            phone.keys += ALIAS
            key
        }
        val remove: () -> Unit = {
            alive()
            phone.keys -= ALIAS
        }
        return KeptNamesApi
            .type(KeptNamesApi.CIPHER_BOX)
            .getConstructor(Function0::class.java, Function0::class.java, Function0::class.java)
            .newInstance(existing, make, remove)
    }

    private val noBackup get() = File(phone.dataDir, "no_backup")

    /** The store the wipe is given: its file steps are the phone's own stand-ins (kills and power cuts reach them). */
    fun store(): Any {
        @Suppress("UNCHECKED_CAST")
        val write = phone.wipeField("writeFile") as (File, ByteArray) -> Unit

        @Suppress("UNCHECKED_CAST")
        val rename = phone.wipeField("renameFile") as (File, File) -> Boolean

        @Suppress("UNCHECKED_CAST")
        val sync = phone.wipeField("syncFolder") as (File) -> Unit
        return KeptNamesApi
            .type(KeptNamesApi.STORE)
            .getConstructor(
                File::class.java,
                KeptNamesApi.boxInterface(),
                Function2::class.java,
                Function2::class.java,
                Function1::class.java,
            ).newInstance(noBackup, box(), write, rename, sync)
    }

    /** A store over the same file and key, for the test's own reads. */
    fun readerStore(): Any =
        KeptNamesApi
            .type(KeptNamesApi.STORE)
            .getConstructor(File::class.java, KeptNamesApi.boxInterface())
            .newInstance(noBackup, box())

    fun writeOlderList(entries: List<Pair<String, String>>) {
        assertTrue("fixture: the older list is written", KeptNamesApi.add(readerStore(), entries))
        assertTrue("fixture: the older list's key", ALIAS in phone.keys)
    }

    // ---- the manager ------------------------------------------------------------------------------------------------

    private val book: ContactBook =
        object : ContactBook {
            override fun addContact(contact: Contact) = throw UnsupportedOperationException("the wipe writes no contact")

            override fun removeContact(address: String) = throw UnsupportedOperationException("the wipe writes no contact")

            override fun getContact(address: String): Contact? = throw UnsupportedOperationException("getContact")

            override fun getAllContacts(): List<Contact> {
                val events = phone.events
                contactReads +=
                    Snapshot(
                        recordThere = phone.recordFile.isFile,
                        phraseThere = phone.phraseStore.exists(),
                        keyCallsBefore = events.any { it.startsWith("keystore.delete") },
                        eventsBefore = events.size,
                    )
                if (contactBookHangs) hang.await()
                if (contactBookDelayMs > 0) {
                    Thread.sleep(contactBookDelayMs)
                    lateAnswer.countDown()
                }
                return contacts.map { Contact(it.first, it.second, Instant.EPOCH) }
            }

            override fun hasContact(address: String): Boolean = throw UnsupportedOperationException("hasContact")

            override fun updateContactName(
                address: String,
                newName: String
            ) = throw UnsupportedOperationException("the wipe writes no contact")

            override fun clearAll() = throw UnsupportedOperationException("the wipe deletes the contact book's file")
        }

    private fun preferences(keep: Set<String>): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            when (method.name) {
                "getDestroyKeep" -> keep
                "walletScopeGeneration" -> generation.get()
                "getAllNicknames" -> HashMap(nicknames)
                "clearAll" -> {
                    generation.incrementAndGet()
                    null
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "KeepRig preferences"
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    /**
     * The phone's wired manager, given the contact book, the store, the preferences double; with [read] the choice is
     * read into memory as a Destroy flow that opens reads it.
     */
    fun manager(
        keep: Set<String>,
        read: Boolean = true,
    ): DestroyManager {
        val manager = phone.manager()
        set(manager, "zchatPreferences", preferences(keep))
        set(manager, "contactBook", book)
        set(manager, "keptNames", store())
        if (read) reload(manager)
        return manager
    }

    /** DestroyManager's one function that reads the choice and the wallet-scope generation again. */
    fun reload(manager: DestroyManager) {
        val method =
            DestroyManager::class.java.methods.firstOrNull { it.name == RELOAD && it.parameterCount == 0 }
                ?: throw AssertionError("DestroyManager has no $RELOAD(): the choice is not held in memory")
        try {
            method.invoke(manager)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun set(
        manager: DestroyManager,
        name: String,
        value: Any?,
    ) {
        val field =
            DestroyManager::class.java.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError("DestroyManager has no field $name")
        field.isAccessible = true
        field.set(manager, value)
    }

    companion object {
        const val ALIAS = "zchat_kept_names_key"
        const val RELOAD = "reloadDestroyKeep"
    }
}
