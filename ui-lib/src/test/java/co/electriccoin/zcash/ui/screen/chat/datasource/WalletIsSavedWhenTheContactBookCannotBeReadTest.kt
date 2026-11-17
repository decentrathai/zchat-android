package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.Contact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.time.Instant

/**
 * ZC-733-B (register ZC-733, medium; store-opener design, revision 2a, rule S12, section 3.6 item 8; class B2): a
 * wallet is always saved, whatever the contact book or the picture index answers. At a change of wallet a store that
 * cannot be opened is set aside with an exact copy for the wallet it belonged to (or erased, when a Delete Wallet
 * without "keep" had meant to erase it and it belongs to nobody) and the claim goes on. It stops only when the copy
 * cannot be written (nothing is cleared) or the store cannot be made new after the copy (the copy stays and is offered
 * at the next start). A store with no file is not made by a change of wallet that has nothing to bring into it.
 *
 * The REAL ChatWalletOwnership, with the real owner record, the real kept copies and an in-memory bundle store; the
 * real contact book, picture index and AI store holder over a fake context and the stand-in library of [KeysetPhone].
 * Today the claim throws when the contact book cannot be opened, and a change of wallet makes every store's file.
 * All names and wallet tags are synthetic.
 */
class WalletIsSavedWhenTheContactBookCannotBeReadTest {
    private class Phone {
        val data: File = Files.createTempDirectory("zchat-zc733b-phone").toFile().apply { deleteOnExit() }
        val files = KeysetPhone(File(data, "shared_prefs"))
        val context = KeysetPhoneContext(files, dataDir = data)

        /** Every open fails as the Keystore not answering. */
        var out = false

        /** A file that exists cannot be opened (its key does not answer); a new one can, unless [cannotMakeNew]. */
        var oldUnreadable = false
        var cannotMakeNew = false
        val calls =
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ ->
                    if (out || cannotMakeNew) throw KeyStoreException("synthetic: no answer")
                    files.Library(name).open()
                },
                createByKeyName = { name, _ ->
                    if (out || oldUnreadable) throw KeyStoreException("synthetic: no answer")
                    files.Library(name).open()
                },
            )
        val book = ContactBookImpl(context)
        val avatars = AvatarStore(context)
        val messages = InMemoryStore("messages", mapOf("m1" to "hello"))
        val bundles = InMemoryBundles()
        val record = PrefsChatOwnerRecord(context)
        val copies = KeptChatStoreCopies(plainPrefs = files::plain, fileNames = files::listing)
        var withAi = false

        init {
            runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(book), calls) }
            runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(avatars), calls) }
            runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.aiHolder(context), calls) }
        }

        val live =
            object : LiveChatStores {
                override fun stores(): List<WalletScopedStore> =
                    listOf(messages) + book.walletScopedStores() + avatars.walletScopedStores() +
                        (if (withAi) listOf(StoreOpenerApi.walletStore(StoreOpenerApi.aiHolder(context), AI)) else emptyList())

                override fun reload() = Unit
            }
        val ownership = ChatWalletOwnership(record, bundles, live, copies)

        fun owner(tag: String?) {
            if (tag == null) files.remove(CHAT_OWNER_PREFS_NAME) else files.put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to tag))
        }

        fun copiesOf(store: String): List<String> = files.snapshot().keys.filter { it.startsWith("$store$UNREADABLE_STORE_COPY_MARKER") }
    }

    private val held = Keysets.wrappedEntries() + mapOf("contacts" to """[{"address":"usyntheticann","name":"Ann","addedAt":1}]""")

    @Before
    fun newProcess() {
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
        ChatStoreStart.userVisible = false
    }

    @After
    fun endProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    // (1) FAILS today: the claim throws.
    @Test
    fun `1 - a wallet's contact book that cannot be opened is set aside with a copy and the wallet is saved`() {
        for (store in listOf(BOOK, INDEX)) {
            newProcess()
            val phone = Phone()
            phone.owner(WALLET_A)
            phone.files.put(store, held)
            phone.oldUnreadable = true

            val claim = runCatching { phone.ownership.claimFor(WALLET_B) }

            assertTrue("$store: the claim failed: $claim", claim.isSuccess)
            assertEquals("$store: the owner", WALLET_B, phone.record.owner())
            val copy = phone.copiesOf(store).single()
            assertEquals("$store: the copy", held, phone.files.entries(copy))
            val marks = phone.files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
            assertEquals("$store: the copy's owner", WALLET_A, marks["copy_owner:$copy"])
            val bundleA = phone.bundles.bundles.getValue(WALLET_A)
            assertEquals("$store: A's bundle lost the other stores", mapOf("m1" to "hello"), bundleA["messages"])
            assertTrue("$store: the live store's file is not there", phone.files.exists(store))
            if (store == BOOK) {
                val ann = Contact("usyntheticnew", "New", Instant.ofEpochMilli(1))
                assertTrue("the live contact book is not usable", StoreOpenerApi.tryContact(phone.book, "tryAddContact", ann))
            }
        }
    }

    // (2)
    @Test
    fun `2 - when A comes back its copy of the contact book is offered at the next start`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        phone.files.put(BOOK, held)
        phone.oldUnreadable = true
        runCatching { phone.ownership.claimFor(WALLET_B) }
        phone.oldUnreadable = false

        assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor(WALLET_A))
        assertEquals("A's copy is not offered", phone.copiesOf(BOOK), phone.copies.offered())
    }

    // (3) FAILS today: the claim throws.
    @Test
    fun `3 - nobody's book that cannot be opened is erased, with no copy, and the wallet is saved`() {
        for (store in listOf(BOOK, INDEX)) {
            newProcess()
            val phone = Phone()
            phone.owner(NO_CHAT_OWNER)
            phone.files.put(store, held)
            phone.out = true

            val claim = runCatching { phone.ownership.claimFor(WALLET_B) }

            assertTrue("$store: the claim failed: $claim", claim.isSuccess)
            assertFalse("$store: the leftover file is still there", phone.files.exists(store))
            assertEquals("$store: a copy was made", emptyList<String>(), phone.copiesOf(store))
        }
    }

    // (4) FAILS today: the file stays.
    @Test
    fun `4 - Delete Wallet erases a store that is not open and cannot be opened, and clears an open one`() {
        val closed = Phone()
        closed.files.put(BOOK, held)
        closed.files.put(INDEX, held)
        closed.out = true
        closed.book.clearAll()
        closed.avatars.clearAll()
        assertFalse("the unopenable contact book stayed", closed.files.exists(BOOK))
        assertFalse("the unopenable picture index stayed", closed.files.exists(INDEX))

        newProcess()
        val open = Phone()
        open.files.put(BOOK, held)
        open.book.getAllContacts()
        open.book.clearAll()
        assertTrue("an open contact book was erased instead of cleared", open.files.exists(BOOK))
        assertEquals(emptyMap<String, Any?>(), open.files.entries(BOOK))
    }

    // (6) Guard.
    @Test
    fun `6 - the copy cannot be written - the claim fails and nothing is touched`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        phone.files.put(BOOK, held)
        phone.out = true
        phone.files.refuseCommitWhen = { it.contains(UNREADABLE_STORE_COPY_MARKER) }

        assertTrue("the claim went on without a copy", runCatching { phone.ownership.claimFor(WALLET_B) }.isFailure)
        assertEquals("the book changed", held, phone.files.entries(BOOK))
        assertEquals("the owner changed", WALLET_A, phone.record.owner())
    }

    // (7)
    @Test
    fun `7 - the store cannot be made new - the opener's own error, the copy stays and is offered`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        phone.files.put(BOOK, held)
        phone.oldUnreadable = true
        phone.cannotMakeNew = true

        val error = runCatching { phone.ownership.claimFor(WALLET_B) }.exceptionOrNull()

        val causes = generateSequence(error) { it.cause }
        assertTrue("not the opener's typed error: $error", causes.any { it is EncryptedStoreUnavailableException })
        assertFalse(
            "the holder's error, not the opener's",
            generateSequence(error) { it.cause }.any { it.javaClass.simpleName == "OtherStoreUnavailableException" },
        )
        assertEquals("the owner changed", WALLET_A, phone.record.owner())
        val copy = phone.copiesOf(BOOK).single()
        assertEquals(WALLET_A, phone.files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)["copy_owner:$copy"])
        phone.oldUnreadable = false
        phone.cannotMakeNew = false
        assertEquals("the copy is not offered", listOf(copy), phone.copies.offered())
    }

    // (8)
    @Test
    fun `8 - erased only when nobody's - with no record or a wallet's tag a copy is kept`() {
        for (owner in listOf(null, WALLET_A)) {
            newProcess()
            val phone = Phone()
            phone.owner(owner)
            phone.files.put(BOOK, held)
            phone.out = true
            runCatching { StoreOpenerApi.walletStore(StoreOpenerApi.holderOf(phone.book), BOOK).importEntries(emptyMap()) }
            val copy = phone.copiesOf(BOOK).singleOrNull()?.let { phone.files.entries(it) }
            assertEquals("owner $owner: the book was erased without a copy", held, copy)
        }
    }

    // (9) FAILS today: the claim also opens the picture index, which has no file, and that open fails.
    @Test
    fun `9 - an open book is handed over as that object, with no open`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        phone.files.put(BOOK, held)
        phone.book.getAllContacts()
        val asked = phone.calls.asked.size
        phone.out = true

        assertTrue(runCatching { phone.ownership.claimFor(WALLET_B) }.isSuccess)
        val opens =
            phone.calls.asked
                .drop(asked)
                .filter { it.contains(BOOK) }
        assertEquals("an open was made for an open book", emptyList<String>(), opens)
        assertEquals("a copy was made of an open book", emptyList<String>(), phone.copiesOf(BOOK))
    }

    // (10)
    @Test
    fun `10 - nobody's book that opens is not erased as a file, the import empties it`() {
        val phone = Phone()
        phone.owner(NO_CHAT_OWNER)
        phone.files.put(BOOK, held)

        assertTrue(runCatching { phone.ownership.claimFor(WALLET_B) }.isSuccess)
        assertTrue("the book that opens was erased", phone.files.exists(BOOK))
        assertFalse("the import did not empty it", phone.files.entries(BOOK).containsKey("contacts"))
    }

    // (11) FAILS today: the hand-over makes the store.
    @Test
    fun `11 - no file and nothing to bring in - nothing is made and no key is asked for`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        phone.out = true

        assertTrue("the claim failed", runCatching { phone.ownership.claimFor(WALLET_B) }.isSuccess)
        assertFalse("the contact book was made", phone.files.exists(BOOK))
        assertFalse("the picture index was made", phone.files.exists(INDEX))
        assertEquals("the library was asked", emptyList<String>(), phone.calls.asked)
        val bundleA = phone.bundles.bundles.getValue(WALLET_A)
        val inBundle = bundleA[BOOK].orEmpty()
        assertEquals("A's bundle holds the book", emptyMap<String, Any?>(), inBundle)
    }

    // (12)
    @Test
    fun `12 - no file and something to bring in - the store is made and holds it`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        phone.bundles.bundles[WALLET_B] = mapOf(BOOK to mapOf("contacts" to "[]"))

        assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor(WALLET_B))
        assertEquals("[]", phone.files.entries(BOOK)["contacts"])
    }

    // (13)
    @Test
    fun `13 - the AI store's holder in the same claim`() {
        val wallet = Phone().apply { withAi = true }
        wallet.owner(WALLET_A)
        wallet.files.put(AI, held)
        wallet.oldUnreadable = true
        assertTrue("the claim failed for an AI store that cannot be opened", runCatching { wallet.ownership.claimFor(WALLET_B) }.isSuccess)
        assertEquals(WALLET_A, wallet.files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)["copy_owner:${wallet.copiesOf(AI).single()}"])

        newProcess()
        val nobody = Phone().apply { withAi = true }
        nobody.owner(NO_CHAT_OWNER)
        nobody.files.put(AI, held)
        nobody.out = true
        assertTrue(runCatching { nobody.ownership.claimFor(WALLET_B) }.isSuccess)
        assertFalse("nobody's AI store stayed", nobody.files.exists(AI))
        // ZC-1031: it may hold the only key to AI credit and may become readable later: set aside as a kept copy, never
        // erased (the other stores of nobody: case 3).
        val setAside = nobody.copiesOf(AI).map { nobody.files.entries(it) }
        assertEquals("nobody's AI store was erased instead of set aside", listOf(held), setAside)

        newProcess()
        val none = Phone().apply { withAi = true }
        none.owner(WALLET_A)
        assertTrue(runCatching { none.ownership.claimFor(WALLET_B) }.isSuccess)
        assertFalse("an AI store was made", none.files.exists(AI))
        assertFalse("the AI key name was asked for", none.calls.asked.any { it.contains(AI) })
    }

    private companion object {
        const val BOOK = "zchat_contact_book_enc"
        const val INDEX = "zchat_avatar_index_enc"
        const val AI = "zchat_ai_prefs"
        val WALLET_A = "a".repeat(64)
        val WALLET_B = "b".repeat(64)
    }
}
