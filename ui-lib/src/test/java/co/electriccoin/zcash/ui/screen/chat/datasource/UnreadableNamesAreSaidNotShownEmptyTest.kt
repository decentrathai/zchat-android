package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStoreException
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-733-B (register ZC-733, medium; store-opener design, revision 2a, rule S2, section 3.6 items 5 and 6; class B1):
 * contact names that cannot be read are said, not shown as an empty list. The contact book gets a flag (`unreadable`,
 * true while its store cannot be opened or its list cannot be read) and three `try…` methods that refuse, write
 * nothing and answer false while it is set (one more try of the store first); every older double of ContactBook keeps
 * compiling with defaults. The Team rename records itself only after a read that worked (ZC-128). The chat list says
 * so, and so do the three pickers of saved contacts; the contact dialogs refuse with a true sentence.
 *
 * Today the book answers an empty list and says nothing, and the Team rename records itself done on an unreadable book.
 * The book runs over a fake context and the stand-in library of [KeysetPhone]. All names are synthetic.
 */
class UnreadableNamesAreSaidNotShownEmptyTest {
    private val phone = KeysetPhone()
    private var out = true
    private val calls =
        StoreOpenerApi.FileCalls(
            createWithMasterKey = { name, _ ->
                if (out) throw KeyStoreException("synthetic: no answer")
                phone.Library(name).open()
            },
            createByKeyName = { name, _ ->
                if (out) throw KeyStoreException("synthetic: no answer")
                phone.Library(name).open()
            },
        )
    private val ann = Contact(address = "usyntheticnamesann", name = "Synthetic Ann", addedAt = Instant.ofEpochMilli(1))

    private fun book(): ContactBookImpl {
        phone.put(STORE, Keysets.wrappedEntries() + ("contacts" to "[]"))
        val book = ContactBookImpl(KeysetPhoneContext(phone))
        runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(book), calls) }
        return book
    }

    @Before
    fun newProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    @After
    fun endProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    // (1) FAILS today: no flag.
    @Test
    fun `1 - the flag - unavailable or damaged is unreadable and reads empty, a good read clears it`() {
        val book = book()
        assertEquals(emptyList<Contact>(), book.getAllContacts())
        assertTrue("an unavailable contact book is not said", StoreOpenerApi.flag(book, "getUnreadable"))

        out = false
        phone.put(STORE, mapOf("contacts" to "not a contact list"))
        assertTrue(runCatching { StoreOpenerApi.ask(StoreOpenerApi.holderOf(book), "tryAgain") }.isSuccess)
        assertEquals(emptyList<Contact>(), book.getAllContacts())
        assertTrue("a damaged list is not said", StoreOpenerApi.flag(book, "getUnreadable"))

        phone.put(STORE, mapOf("contacts" to "[]"))
        book.getAllContacts()
        assertFalse("a good read did not clear the flag", StoreOpenerApi.flag(book, "getUnreadable"))
    }

    // (2)
    @Test
    fun `2 - the refusals - while unreadable the three try methods answer false and write nothing, then succeed`() {
        val book = book()
        book.getAllContacts()
        for ((function, arguments) in listOf<Pair<String, Array<Any?>>>(
            "tryAddContact" to arrayOf(ann),
            "tryRemoveContact" to arrayOf(ann.address),
            "tryUpdateContactName" to arrayOf(ann.address, "Renamed"),
        )) {
            val asked = calls.asked.size
            assertFalse("$function was done on an unreadable book", StoreOpenerApi.tryContact(book, function, *arguments))
            assertTrue("$function made no more try of the store", calls.asked.size > asked)
            assertEquals("$function wrote", 0, phone.writesTo(STORE))
        }

        out = false
        assertTrue("the add failed once the store opened", StoreOpenerApi.tryContact(book, "tryAddContact", ann))
        assertEquals(ann.name, book.getContact(ann.address)?.name)
    }

    // (3)
    @Test
    fun `3 - the defaults - an older double compiles and answers never unreadable and true`() {
        val double =
            object : ContactBook {
                val saved = mutableListOf<Contact>()

                override fun addContact(contact: Contact) {
                    saved += contact
                }

                override fun removeContact(address: String) = Unit

                override fun getContact(address: String): Contact? = saved.firstOrNull { it.address == address }

                override fun getAllContacts(): List<Contact> = saved

                override fun hasContact(address: String): Boolean = getContact(address) != null

                override fun updateContactName(
                    address: String,
                    newName: String
                ) = Unit

                override fun clearAll() = Unit
            }
        assertFalse(StoreOpenerApi.flag(double, "getUnreadable"))
        assertTrue(StoreOpenerApi.tryContact(double, "tryAddContact", ann))
        assertEquals(listOf(ann), double.saved)
        assertTrue(StoreOpenerApi.tryContact(double, "tryRemoveContact", ann.address))
        assertTrue(StoreOpenerApi.tryContact(double, "tryUpdateContactName", ann.address, "x"))
    }

    // (4) FAILS today: the rename is recorded on an unreadable book (ZC-128).
    @Test
    fun `4 - the Team rename is not recorded on an unreadable contact book`() {
        val book = book()
        var done = false
        val once =
            object : TeamContactMigration.RenameOnce {
                override fun done(): Boolean = done

                override fun markDone() {
                    done = true
                }
            }

        TeamContactMigration.runAtStart(book, once)

        assertFalse("the one-off rename was recorded although the book could not be read", done)
    }

    // (6)
    @Test
    fun `6 - the pictures line follows the picture index's holder`() {
        phone.put("zchat_avatar_index_enc", Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val avatars = AvatarStore(KeysetPhoneContext(phone))
        StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(avatars), calls)
        avatars.getSelfAvatar()
        assertTrue("the picture index's flag does not follow its holder", StoreOpenerApi.flag(avatars, "getUnavailable"))
        out = false
        StoreOpenerApi.ask(StoreOpenerApi.holderOf(avatars), "tryAgain")
        assertFalse(StoreOpenerApi.flag(avatars, "getUnavailable"))

        assertEquals(emptyList<Int>(), StoreOpenerApi.unreadableStoreLines(names = false, pictures = false))
        assertEquals(listOf(stringId("chat_list_pictures_unreadable")), StoreOpenerApi.unreadableStoreLines(names = false, pictures = true))
        assertEquals(listOf(stringId("chat_list_names_unreadable")), StoreOpenerApi.unreadableStoreLines(names = true, pictures = false))
        assertEquals(
            listOf(stringId("chat_list_names_unreadable"), stringId("chat_list_pictures_unreadable")),
            StoreOpenerApi.unreadableStoreLines(names = true, pictures = true),
        )
    }

    // (7)
    @Test
    fun `7 - the three strings in English and Spanish`() {
        val english = strings("values")
        val spanish = strings("values-es")
        val endings =
            mapOf(
                "chat_list_names_unreadable" to ("Nothing was deleted." to "No se borró nada."),
                "chat_list_pictures_unreadable" to ("Nothing was deleted." to "No se borró nada."),
                "chat_list_contact_not_saved" to ("Nothing was changed." to "No se cambió nada."),
            )
        for ((key, ending) in endings) {
            val en = english[key] ?: throw AssertionError("no English $key")
            val es = spanish[key] ?: throw AssertionError("no Spanish $key")
            assertNotEquals("$key: the Spanish is the English", en, es)
            assertFalse("$key holds a placeholder", en.contains("%") || es.contains("%"))
            assertTrue("$key (English) does not end with '${ending.first}'", en.endsWith(ending.first))
            assertTrue("$key (Spanish) does not end with '${ending.second}'", es.endsWith(ending.second))
        }
    }

    private fun stringId(key: String): Int =
        try {
            Class.forName("co.electriccoin.zcash.ui.R\$string").getField(key).getInt(null)
        } catch (_: NoSuchFieldException) {
            throw AssertionError("ZC-733-B: there is no string $key")
        }

    private fun strings(qualifier: String): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val file = File(dir ?: error("repo root not found"), "ui-lib/src/main/res/ui/common/$qualifier/chat_send_strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private companion object {
        const val STORE = "zchat_contact_book_enc"
    }
}
