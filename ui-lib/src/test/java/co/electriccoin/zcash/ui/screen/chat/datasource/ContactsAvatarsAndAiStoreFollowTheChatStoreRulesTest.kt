package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/**
 * ZC-733-A (register ZC-733, medium; store-opener design, revision 2a, rules S1 and S7, section 3.6 items 1 to 3;
 * class A2): the contact book, the picture index and the AI store follow the chat stores' rules. Each opens through the
 * one opener inside one small holder per store (`LazyEncryptedStore`): a temporary error is a typed error, retried,
 * counted once per process at the start's ask, never cleared; at the third counted start in a row, or for a lost key,
 * the store is set aside with an exact copy and starts empty; the copy is offered back to its wallet only. Every other
 * ask gets the remembered error at once, with no Keystore call; Retry is one more try that never counts or clears.
 * Two AiPreferences objects share one holder. Today each builds the master key and the library's create itself: the
 * raw library error reaches the caller, and a lost key leaves the store unreadable for good.
 *
 * The three classes are built with a fake context ([KeysetPhoneContext]); the library calls are replaced through the
 * holder's own field ([StoreOpenerApi.useCalls]); the files are maps ([KeysetPhone]). All names are synthetic.
 */
class ContactsAvatarsAndAiStoreFollowTheChatStoreRulesTest {
    /** What the phone's security system does for the stand-in library. */
    private enum class Keystore { ANSWERS, OUT, SILENT_WHILE_DATA, KEY_GONE }

    private class Store(
        val label: String,
        val file: String,
        val keyName: String,
        val make: (Context) -> Any,
        val holder: (Any, Context) -> Any,
    )

    private val stores =
        listOf(
            Store(
                "the contact book",
                "zchat_contact_book_enc",
                DEFAULT_KEY,
                { ContactBookImpl(it) },
                { o, _ -> StoreOpenerApi.holderOf(o) },
            ),
            Store("the picture index", "zchat_avatar_index_enc", DEFAULT_KEY, { AvatarStore(it) }, { o, _ -> StoreOpenerApi.holderOf(o) }),
            Store("the AI store", "zchat_ai_prefs", AI_KEY, { AiPreferences(it) }, { _, c -> StoreOpenerApi.aiHolder(c) }),
        )

    /** One phone with one of the three stores on it, the stand-in library and the holder. */
    private inner class Phone(
        val store: Store,
        entries: Map<String, Any?>? = Keysets.wrappedEntries() + ("AQ-1" to "AU-1"),
    ) {
        val files = KeysetPhone()
        val context = KeysetPhoneContext(files)
        var keystore = Keystore.ANSWERS
        val waits = mutableListOf<Long>()
        val calls =
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ ->
                    if (keystore == Keystore.OUT) throw KeyStoreException("synthetic: no answer")
                    files.Library(name).open()
                },
                createByKeyName = { name, _ ->
                    when (keystore) {
                        Keystore.OUT -> throw KeyStoreException("synthetic: no answer")
                        Keystore.SILENT_WHILE_DATA -> throw KeyStoreException("synthetic: no answer for the old file")
                        Keystore.KEY_GONE -> throw AEADBadTagException("synthetic: the key is gone")
                        Keystore.ANSWERS -> files.Library(name).open()
                    }
                },
            )

        init {
            if (entries != null) files.put(store.file, entries)
        }

        val obj: Any = store.make(context)
        val holder: Any by lazy { store.holder(obj, context).also { StoreOpenerApi.useCalls(it, calls, waits) } }

        fun ask(function: String): Result<Any?> = runCatching { StoreOpenerApi.ask(holder, function) }

        fun count(): Int? = files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[store.file] as? Int

        fun copies(): List<String> = files.snapshot().keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }
    }

    private fun newProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    @Before
    fun startSeen() {
        newProcess()
        ChatStoreStart.userVisible = true
    }

    @After
    fun startGone() {
        ChatStoreStart.userVisible = false
        newProcess()
    }

    // (1) FAILS today: the raw library error, or nothing at all.
    @Test
    fun `1 - a temporary error at the start's ask is the typed error, counted once, nothing touched`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            val before = phone.files.entries(store.file)
            phone.keystore = Keystore.OUT

            val outcome = phone.ask("askAtStart")

            assertTrue("${store.label}: not the typed error: $outcome", outcome.exceptionOrNull() is EncryptedStoreUnavailableException)
            assertEquals("${store.label}: the file changed", before, phone.files.entries(store.file))
            assertEquals("${store.label}: the count", 1, phone.count())
            assertTrue("${store.label}: the holder does not say unavailable", StoreOpenerApi.unavailable(phone.holder))
        }
    }

    // (2)
    @Test
    fun `2 - every other ask - the remembered error with no library call, and Retry is one uncounted try`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            phone.keystore = Keystore.OUT
            phone.ask("askAtStart")
            val asked = phone.calls.asked.size
            val waited = phone.waits.size

            val again = phone.ask("get")
            assertEquals(
                "${store.label}: get() is not the holder's error",
                "OtherStoreUnavailableException",
                again.exceptionOrNull()?.javaClass?.simpleName,
            )
            assertEquals("${store.label}: get() asked the library", asked, phone.calls.asked.size)

            val retry = phone.ask("tryAgain")
            assertTrue("${store.label}: Retry did not fail while the Keystore is out", retry.isFailure)
            assertTrue("${store.label}: Retry did not ask the library", phone.calls.asked.size > asked)
            assertEquals("${store.label}: Retry counted", 1, phone.count())
            assertEquals("${store.label}: Retry waited", waited, phone.waits.size)
            assertEquals("${store.label}: a copy was kept", emptyList<String>(), phone.copies())

            phone.keystore = Keystore.ANSWERS
            assertTrue("${store.label}: Retry did not open the store", phone.ask("tryAgain").isSuccess)
            assertFalse("${store.label}: still unavailable after it opened", StoreOpenerApi.unavailable(phone.holder))
        }
    }

    // (2b)
    @Test
    fun `2b - the start's ask after a failed background ask makes its own counted try, and asks nothing once open`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            phone.keystore = Keystore.OUT
            assertTrue(phone.ask("get").isFailure)
            assertEquals("${store.label}: a background ask counted", null, phone.count())
            val asked = phone.calls.asked.size

            assertTrue(phone.ask("askAtStart").isFailure)
            assertTrue("${store.label}: the start's ask made no try of its own", phone.calls.asked.size > asked)
            assertEquals("${store.label}: the start's ask was not counted once", 1, phone.count())

            phone.keystore = Keystore.ANSWERS
            val open = phone.ask("tryAgain").getOrThrow()
            val after = phone.calls.asked.size
            assertSame("${store.label}: the start's ask did not answer the open store", open, phone.ask("askAtStart").getOrThrow())
            assertEquals("${store.label}: the start's ask opened again", after, phone.calls.asked.size)
        }
    }

    // (3) FAILS today: unreadable for good.
    @Test
    fun `3 - at the third start in a row the store is set aside with every entry and starts empty and usable`() {
        stores.forEach { store ->
            val phone = Phone(store)
            val before = phone.files.entries(store.file)
            phone.keystore = Keystore.SILENT_WHILE_DATA
            repeat(2) {
                newProcess()
                assertTrue("${store.label}: a start opened while the Keystore is silent", phone.ask("askAtStart").isFailure)
            }
            newProcess()
            val third = phone.ask("askAtStart")

            assertTrue("${store.label}: the third start did not start the store empty: $third", third.isSuccess)
            assertEquals("${store.label}: the kept copy", before, phone.files.entries(phone.copies().single()))
            val keys = phone.files.entries(store.file).keys
            assertEquals("${store.label}: the store holds more than its key entries", setOf(Keysets.KEY_ENTRY, Keysets.VALUE_ENTRY), keys)
        }
    }

    // (4)
    @Test
    fun `4 - a lost key at the start's ask is set aside at once, and at any other ask nothing is cleared`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            val before = phone.files.entries(store.file)
            phone.keystore = Keystore.KEY_GONE
            assertTrue("${store.label}: the start's ask did not start the store empty", phone.ask("askAtStart").isSuccess)
            assertEquals("${store.label}: the kept copy", before, phone.files.entries(phone.copies().single()))

            newProcess()
            val other = Phone(store)
            val otherBefore = other.files.entries(store.file)
            other.keystore = Keystore.KEY_GONE
            assertTrue(other.ask("get").isFailure)
            assertEquals("${store.label}: a background ask cleared the store", otherBefore, other.files.entries(store.file))
            assertEquals("${store.label}: a background ask kept a copy", emptyList<String>(), other.copies())
        }
    }

    // (5)
    @Test
    fun `5 - the copy cannot be written - nothing is cleared`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            val before = phone.files.entries(store.file)
            phone.files.refuseCommitWhen = { it.contains(UNREADABLE_STORE_COPY_MARKER) }
            phone.keystore = Keystore.KEY_GONE

            assertTrue("${store.label}: a store was handed out", phone.ask("askAtStart").isFailure)
            assertEquals("${store.label}: the store was cleared without a copy", before, phone.files.entries(store.file))
        }
    }

    // (6)
    @Test
    fun `6 - the kept copy is offered to the wallet that owns the chats, and to no other, and comes back entry for entry`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            val before = phone.files.entries(store.file)
            phone.files.put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to WALLET_A))
            phone.keystore = Keystore.KEY_GONE
            assertTrue("${store.label}: the start's ask did not start the store empty", phone.ask("askAtStart").isSuccess)
            val copy = phone.copies().single()
            val kept = KeptChatStoreCopies(plainPrefs = phone.files::plain, fileNames = phone.files::listing)

            assertEquals("${store.label}: not offered to its wallet", listOf(copy), kept.offered())
            phone.files.put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to WALLET_B))
            assertEquals("${store.label}: offered to another wallet", emptyList<String>(), kept.offered())
            phone.files.put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to WALLET_A))
            assertTrue("${store.label}: the restore failed", kept.restore())
            assertEquals("${store.label}: the copy did not come back entry for entry", before, phone.files.entries(store.file))
        }
    }

    // (7)
    @Test
    fun `7 - two AI store objects in one process share one store, and an open store is never copied or cleared`() {
        val ai = stores.last()
        val phone = Phone(ai)
        StoreOpenerApi.useCalls(StoreOpenerApi.aiHolder(phone.context), phone.calls, phone.waits)
        val first = AiPreferences(phone.context)
        val second = AiPreferences(phone.context)

        first.getToken()
        second.getToken()
        assertEquals("the library was asked for each object", 1, phone.calls.asked.size)

        val before = phone.files.entries(ai.file)
        phone.keystore = Keystore.KEY_GONE
        listOf("askAtStart", "tryAgain", "get").forEach { phone.ask(it) }
        second.getUserId()
        assertEquals("an open store was copied", emptyList<String>(), phone.copies())
        assertEquals("an open store was cleared", before, phone.files.entries(ai.file))
    }

    // (8)
    @Test
    fun `8 - the contact book's move of the old plain list runs after the open`() {
        val book = stores.first()
        val phone = Phone(book)
        phone.files.put(LEGACY_CONTACTS, mapOf("contacts" to CONTACTS))

        assertTrue("the start's ask did not open the contact book", phone.ask("askAtStart").isSuccess)

        assertEquals("the list did not move into the store", CONTACTS, phone.files.entries(book.file)["contacts"])
        assertEquals("the plain list was not removed", null, phone.files.entries(LEGACY_CONTACTS)["contacts"])
    }

    // (9) Guard of the names, through the calls themselves.
    @Test
    fun `9 - the file names, the key names and the schemes are those of 2-12-4`() {
        stores.forEach { store ->
            newProcess()
            val phone = Phone(store)
            assertNotNull("${store.label}: the start's ask did not open the store", phone.ask("askAtStart").getOrNull())
            val expected = listOf("createByKeyName:${store.file}:${store.keyName}")
            assertEquals("${store.label}: not the file and key of 2.12.4", expected, phone.calls.asked)
        }
        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        val implementation = opener.substringAfter("object LibraryEncryptedFileCalls", "")
        val siv = Regex("""PrefKeyEncryptionScheme\.AES256_SIV""").findAll(implementation).count()
        val gcm = Regex("""PrefValueEncryptionScheme\.AES256_GCM""").findAll(implementation).count()
        assertEquals("the two creates do not name AES256_SIV", 2, siv)
        assertEquals("the two creates do not name AES256_GCM", 2, gcm)
    }

    private companion object {
        const val DEFAULT_KEY = "_androidx_security_master_key_"
        const val AI_KEY = "zchat_ai_master_key"
        const val LEGACY_CONTACTS = "zchat_contact_book"
        const val CONTACTS = """[{"address":"usyntheticaux01","name":"Synthetic Ann","addedAt":1700000000000}]"""
        val WALLET_A = "a".repeat(64)
        val WALLET_B = "b".repeat(64)
    }
}
