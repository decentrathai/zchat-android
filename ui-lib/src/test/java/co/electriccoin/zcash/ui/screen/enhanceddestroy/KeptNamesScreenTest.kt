package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.util.KeptNamesApi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Instant
import java.util.Collections

/**
 * DESTROY-ADV-N (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, 5.2, 9.1, 9.4,
 * 10.1) — the screen "Names you kept" and its Advanced Settings row.
 *
 *  - an entry whose address is already a saved contact of this wallet is not shown; when every entry is one, the
 *    screen says so;
 *  - a list that cannot be read shows its message, no entry and only "Erase the whole list";
 *  - "Erase the whole list" asks first, and only the confirm erases;
 *  - the Advanced Settings row "Names you kept" is there only while the list's file exists, and that check decrypts
 *    nothing;
 *  - every text of spec 9.4 and the two row keys of 9.1 exist in English and Spanish, differ, and hold no placeholder;
 *    the note about a fee (`kept_names_save_pays_note`) is not built, because the first-contact batch FC-SAVED is in
 *    the tree (`FirstKeyAtASavedContactWaitsForAcceptTest`): a saved contact's first key waits for Accept too.
 *
 * The screen's classes are reached by name. All addresses and names are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptNamesScreenTest {
    private lateinit var folder: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        folder = Files.createTempDirectory("kept-names-screen").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        folder.deleteRecursively()
    }

    @Test
    fun `entries already in the contact book are not shown, and all saved is said`() {
        val box = KeptNamesApi.MemoryBox()
        val store = KeptNamesApi.store(folder, box)
        KeptNamesApi.add(store, listOf(A to "Ann", B to "Bo", C to "Cy"))
        val book = KeptNamesScreenApi.Book(saved = setOf(B))
        val vm = KeptNamesScreenApi.viewModel(store, book, KeptNamesScreenApi.AddressBook())
        assertEquals(listOf(A, C), KeptNamesScreenApi.shownAddresses(vm))
        assertFalse(KeptNamesScreenApi.flag(vm, "getAllSaved"))

        val all = KeptNamesScreenApi.viewModel(store, KeptNamesScreenApi.Book(saved = setOf(A, B, C)), KeptNamesScreenApi.AddressBook())
        assertEquals(emptyList<String>(), KeptNamesScreenApi.shownAddresses(all))
        assertTrue("every name is a contact: the screen says so", KeptNamesScreenApi.flag(all, "getAllSaved"))
        assertFalse(KeptNamesScreenApi.flag(all, "getUnreadable"))
    }

    @Test
    fun `a list that cannot be read shows the message, no entry and only Erase the whole list`() {
        KeptNamesApi.add(KeptNamesApi.store(folder, KeptNamesApi.MemoryBox()), listOf(A to "Ann"))
        val other = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        val vm = KeptNamesScreenApi.viewModel(other, KeptNamesScreenApi.Book(), KeptNamesScreenApi.AddressBook())
        assertTrue("the screen says the list cannot be read", KeptNamesScreenApi.flag(vm, "getUnreadable"))
        assertEquals("no entry", emptyList<String>(), KeptNamesScreenApi.shownAddresses(vm))
        assertFalse("cannot be read is not all saved", KeptNamesScreenApi.flag(vm, "getAllSaved"))

        val view = KeptNamesScreenApi.viewSource()
        val unreadable =
            KeptNamesScreenApi.functionBody(view, "UnreadableList")
                ?: throw AssertionError("the view has no UnreadableList")
        assertTrue("the message", "R.string.kept_names_unreadable" in unreadable)
        assertTrue("Erase the whole list", "R.string.kept_names_remove_all" in unreadable)
        for (other in listOf("R.string.kept_names_save)", "R.string.kept_names_remove)", "entries")) {
            assertFalse("a list that cannot be read offers $other", other in unreadable)
        }
        assertTrue("the view shows UnreadableList when the list cannot be read", "UnreadableList(" in view)
    }

    @Test
    fun `erase the whole list asks first, and only the confirm erases the file and the key`() {
        val box = KeptNamesApi.MemoryBox()
        val store = KeptNamesApi.store(folder, box)
        KeptNamesApi.add(store, listOf(A to "Ann"))
        val vm = KeptNamesScreenApi.viewModel(store, KeptNamesScreenApi.Book(), KeptNamesScreenApi.AddressBook())

        KeptNamesScreenApi.call(vm, "onEraseWholeList")
        assertTrue("the confirmation is shown", KeptNamesScreenApi.flag(vm, "getEraseConfirmShown"))
        assertTrue("nothing is erased before the confirm", File(folder, KeptNamesApi.FILE_NAME).exists())
        assertEquals(0, box.erased)

        KeptNamesScreenApi.call(vm, "onEraseDismissed")
        assertFalse(KeptNamesScreenApi.flag(vm, "getEraseConfirmShown"))
        assertTrue("a dismissed confirmation erases nothing", File(folder, KeptNamesApi.FILE_NAME).exists())

        KeptNamesScreenApi.call(vm, "onEraseWholeList")
        KeptNamesScreenApi.call(vm, "onEraseConfirmed")
        assertFalse("the confirm erases the file", File(folder, KeptNamesApi.FILE_NAME).exists())
        assertEquals("the confirm erases the key", 1, box.erased)
        assertFalse(KeptNamesScreenApi.flag(vm, "getEraseConfirmShown"))

        val source = KeptNamesScreenApi.vmSource()
        val eraseCalls = Regex("""\.eraseAll\(\)""").findAll(source).count()
        assertEquals("the view model erases in one place", 1, eraseCalls)
        assertTrue(
            "the erase is in the confirm handler",
            KeptNamesScreenApi.functionBody(source, "onEraseConfirmed")?.contains(".eraseAll()") == true
        )
        assertTrue("the view shows the confirmation", "R.string.kept_names_remove_all_confirm" in KeptNamesScreenApi.viewSource())
    }

    @Test
    fun `the Advanced Settings row is there only while the file exists, and the check decrypts nothing`() {
        val throwing = KeptNamesApi.MemoryBox(throwing = true)
        val store = KeptNamesApi.store(folder, throwing)
        assertFalse("no file, no row", KeptNamesApi.listFileExists(store))
        File(folder, KeptNamesApi.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue("a file, the row (the key box that throws is never asked)", KeptNamesApi.listFileExists(store))

        val listSource = SourceScan.stripComments(File(ChatResourceText.repoRoot, KeptNamesApi.SOURCE).readText())
        val check = KeptNamesScreenApi.functionBody(listSource, "listFileExists") ?: throw AssertionError("no listFileExists")
        for (name in listOf("keyBox", "read(", "Cipher", "readBytes")) {
            assertFalse("the row's check reaches $name", name in check)
        }

        val vm = SourceScan.stripComments(File(ChatResourceText.repoRoot, ADVANCED_VM).readText())
        val builder = KeptNamesScreenApi.declaration(vm, "createState") ?: throw AssertionError("no createState")
        assertTrue("the row's title", "R.string.advanced_settings_kept_names)" in builder)
        assertTrue("the row's second line", "R.string.advanced_settings_kept_names_sub)" in builder)
        assertTrue("the row is shown only while the file exists", ".takeIf { showKeptNames }" in builder)
        assertFalse("createState checks the file (main thread)", "listFileExists" in builder)
        assertTrue("the file is checked", ".listFileExists()" in vm)
        assertFalse("Advanced Settings reads the list", Regex("""\.read\(\)""").containsMatchIn(vm))
        assertTrue("the row opens the screen", "KeptNamesArgs" in vm)
    }

    @Test
    fun `every text of the screen and the two row keys in English and Spanish, different, with no placeholder`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in KEYS) {
            val english = en[key] ?: throw AssertionError("no English $key")
            val spanish = es[key] ?: throw AssertionError("no Spanish $key")
            assertNotEquals("$key is not translated", english, spanish)
            assertFalse("$key has a placeholder", '%' in english || '%' in spanish)
        }
        for ((key, text) in ENGLISH) assertEquals("English $key (spec 9.1, 9.4)", text, en[key])
        // FC-SAVED is in the tree, so the note about a fee is not built (spec 9.4, 10.2 last row).
        assertTrue(
            "fixture: FC-SAVED is in this tree",
            File(ChatResourceText.repoRoot, FC_SAVED_TEST).isFile
        )
        assertFalse("kept_names_save_pays_note is built while FC-SAVED is in", "kept_names_save_pays_note" in en)
        assertFalse("kept_names_save_pays_note is built while FC-SAVED is in", "kept_names_save_pays_note" in es)
        val routeField = runCatching { R.string::class.java.getField("kept_names_title") }.getOrNull()
        assertTrue("R.string.kept_names_title", routeField != null)
    }

    private companion object {
        const val A = "zs1keptalpha0000"
        const val B = "zs1keptbeta00000"
        const val C = "zs1keptgamma0000"
        const val ADVANCED_VM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/AdvancedSettingsVM.kt"
        const val FC_SAVED_TEST =
            "ui-lib/src/test/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/FirstKeyAtASavedContactWaitsForAcceptTest.kt"

        val ENGLISH =
            linkedMapOf(
                "advanced_settings_kept_names" to "Names you kept",
                "advanced_settings_kept_names_sub" to "From an earlier wallet on this phone",
                "kept_names_title" to "Names you kept",
                "kept_names_intro" to
                    "These names and addresses were kept when Destroy All ran on this phone. They are not contacts of " +
                    "this wallet. A kept name is only a name: ZCHAT treats its address as if the name were not on this " +
                    "list, until you save it as a contact. From then on ZCHAT treats that address as it treats every " +
                    "contact you saved yourself.",
                "kept_names_save" to "Save as contact",
                "kept_names_saved" to "Saved as a contact.",
                "kept_names_save_failed" to "The contact could not be saved. The name is still on the list.",
                "kept_names_remove" to "Remove from the list",
                "kept_names_change_failed" to "ZCHAT could not change the list. Nothing was erased.",
                "kept_names_remove_all" to "Erase the whole list",
                "kept_names_remove_all_confirm" to "Erase every name on this list from this phone? ZCHAT cannot undo this.",
                "kept_names_unreadable" to
                    "ZCHAT cannot read the list right now. Nothing was erased. Close ZCHAT and open it again. If this " +
                    "stays, the list cannot be read any more, and you can erase it below.",
                "kept_names_all_saved" to "Every name on the list is already a contact of this wallet.",
            )
        val KEYS = ENGLISH.keys
    }
}

/**
 * The screen "Names you kept" reached by name: `KeptNamesVM(KeptNames, ContactBook, AddressBookRepository,
 * NavigationRouter, CoroutineDispatcher)`, its `state` (`entries` of `KeptNameItem`, `unreadable`, `allSaved`,
 * `eraseConfirmShown`, `notice` as a string id) and its handlers.
 */
internal object KeptNamesScreenApi {
    const val VM = "co.electriccoin.zcash.ui.screen.enhanceddestroy.KeptNamesVM"
    const val VM_SOURCE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/KeptNamesVM.kt"
    const val VIEW_SOURCE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/view/KeptNamesView.kt"

    /** A contact book holding [saved]; with [takes] false a write does not take (the store refused it). */
    class Book(
        saved: Set<String> = emptySet(),
        private val takes: Boolean = true,
    ) : ContactBook {
        val contacts: MutableMap<String, String> = Collections.synchronizedMap(saved.associateWith { "saved" }.toMutableMap())
        val adds: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun addContact(contact: Contact) {
            adds += contact.address
            if (takes) contacts[contact.address] = contact.name
        }

        override fun removeContact(address: String) {
            contacts.remove(address)
        }

        override fun getContact(address: String): Contact? = contacts[address]?.let { Contact(address, it, Instant.EPOCH) }

        override fun getAllContacts(): List<Contact> = contacts.map { Contact(it.key, it.value, Instant.EPOCH) }

        override fun hasContact(address: String): Boolean = address in contacts

        override fun updateContactName(
            address: String,
            newName: String
        ) {
            contacts[address] = newName
        }

        override fun clearAll() = contacts.clear()
    }

    /** The wallet's Contacts list: records every saveContact (name, address, chain); any other call is an error. */
    class AddressBook {
        val saves: MutableList<Triple<String, String, String?>> = Collections.synchronizedList(mutableListOf())
        val other: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val proxy: AddressBookRepository =
            Proxy.newProxyInstance(
                AddressBookRepository::class.java.classLoader,
                arrayOf(AddressBookRepository::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "saveContact" -> {
                        saves += Triple(args!![0] as String, args[1] as String, args[2] as String?)
                        Unit
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "AddressBook"
                    else -> {
                        other += method.name
                        null
                    }
                }
            } as AddressBookRepository
    }

    private val router: NavigationRouter =
        Proxy.newProxyInstance(NavigationRouter::class.java.classLoader, arrayOf(NavigationRouter::class.java)) { p, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "router"
                else -> null
            }
        } as NavigationRouter

    fun viewModel(
        store: Any,
        book: ContactBook,
        addressBook: AddressBook,
    ): Any {
        val type =
            runCatching { Class.forName(VM) }.getOrNull()
                ?: throw AssertionError("there is no $VM: no screen shows the names Destroy All kept (DESTROY-ADV-N)")
        return type
            .getConstructor(
                KeptNamesApi.type(KeptNamesApi.STORE),
                ContactBook::class.java,
                AddressBookRepository::class.java,
                NavigationRouter::class.java,
                CoroutineDispatcher::class.java,
            ).newInstance(store, book, addressBook.proxy, router, Dispatchers.Unconfined)
    }

    fun call(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("${target.javaClass.simpleName} has no $name")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun state(vm: Any): Any = (call(vm, "getState") as StateFlow<*>).value!!

    fun flag(
        vm: Any,
        getter: String,
    ): Boolean = call(state(vm), getter) as Boolean

    fun notice(vm: Any): Int? = call(state(vm), "getNotice") as Int?

    fun shownAddresses(vm: Any): List<String> = (call(state(vm), "getEntries") as List<*>).map { call(it!!, "getAddress") as String }

    fun stringId(key: String): Int = R.string::class.java.getField(key).getInt(null)

    fun vmSource(): String = SourceScan.stripComments(File(ChatResourceText.repoRoot, VM_SOURCE).readText())

    fun viewSource(): String = SourceScan.stripComments(File(ChatResourceText.repoRoot, VIEW_SOURCE).readText())

    /** The text of the class member [name] in [source]: from its declaration to the next member function. */
    fun declaration(
        source: String,
        name: String,
    ): String? {
        val start = Regex("""fun $name\(""").find(source)?.range?.first ?: return null
        val next = Regex("""\n    (private |internal |override )?(suspend )?fun """).find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }

    /** The text of the function [name] in [source]: from its declaration to its closing brace. */
    fun functionBody(
        source: String,
        name: String,
    ): String? {
        val at = Regex("""fun $name\(""").find(source)?.range?.first ?: return null
        val open = source.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        return null
    }
}
