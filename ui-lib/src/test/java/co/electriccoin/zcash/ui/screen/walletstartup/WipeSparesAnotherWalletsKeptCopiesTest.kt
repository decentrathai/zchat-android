package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_KEY
import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.LiveChatStores
import co.electriccoin.zcash.ui.screen.chat.datasource.PrefsChatOwnerRecord
import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.datasource.WalletScopedStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException

/**
 * ZC-733-B (register ZC-733, medium; store-opener design, revision 2a, rule S14, section 3.6 item 8; class B3): the
 * wipe of one wallet does not delete another wallet's kept copies. Delete Wallet without "keep" deletes the kept
 * copies of the wallet that is wiped (tagged with it, or untagged) and their marks; a copy tagged with another wallet
 * stays, byte for byte, with its owner tag and its marks, and is offered when that wallet is loaded again. Destroy All
 * still deletes every copy.
 *
 * The two functions of KeptChatStoreCopies the wipe uses (they delete no file; ZchatPreferences.clearAll deletes what
 * the first names, and the file of marks only when the second says nothing is left), over a phone of [KeysetPhone],
 * reached by name ([StoreOpenerApi]). Today the wipe deletes every copy and the whole file of marks. All synthetic.
 */
class WipeSparesAnotherWalletsKeptCopiesTest {
    private val walletA = "a".repeat(64)
    private val walletB = "b".repeat(64)
    private val book = "zchat_contact_book_enc"
    private val data: File = Files.createTempDirectory("zchat-zc733b-wipe").toFile().apply { deleteOnExit() }
    private val phone = KeysetPhone(File(data, "shared_prefs"))
    private val copies = KeptChatStoreCopies(plainPrefs = phone::plain, fileNames = phone::listing)

    @Before
    fun newProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    @After
    fun endProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    private fun owner(tag: String) = phone.put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to tag))

    private fun copy(
        name: String,
        owner: String?,
        marks: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val entries = Keysets.wrappedEntries() + ("AQ-$name" to "AU-$name")
        phone.put(name, entries)
        phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, marks + (if (owner != null) mapOf("copy_owner:$name" to owner) else emptyMap()))
        return entries
    }

    /** What ZchatPreferences.clearAll does with the two functions: delete what the first names; the marks file only if empty. */
    private fun wipe(): List<String> {
        val listed = phone.listing().map { it.removeSuffix(".xml") }.filter { UNREADABLE_STORE_COPY_MARKER in it }
        val named = StoreOpenerApi.copiesToWipe(copies, listed)
        named.forEach { phone.remove(it) }
        if (!StoreOpenerApi.cleanMarksForWipe(copies)) phone.remove(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        return named
    }

    private fun marks(): Map<String, Any?> = phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)

    // (1) FAILS today.
    @Test
    fun `1 - the story - B's wipe spares A's copy, and A gets its names back`() {
        val context = KeysetPhoneContext(phone, dataDir = data)
        val contactBook = ContactBookImpl(context)
        var unreadable = true
        StoreOpenerApi.useCalls(
            StoreOpenerApi.holderOf(contactBook),
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ -> phone.Library(name).open() },
                createByKeyName = { name, _ ->
                    if (unreadable) throw KeyStoreException("synthetic: no answer")
                    phone.Library(name).open()
                },
            ),
        )
        val names = Keysets.wrappedEntries() + ("contacts" to "[]")
        phone.put(book, names)
        owner(walletA)
        val bundles = MemoryBundles()
        val live =
            object : LiveChatStores {
                override fun stores(): List<WalletScopedStore> = contactBook.walletScopedStores()

                override fun reload() = Unit
            }
        val ownership = ChatWalletOwnership(PrefsChatOwnerRecord(context), bundles, live, copies)
        ownership.claimFor(walletB)
        unreadable = false
        val aCopy = phone.snapshot().keys.single { it.startsWith("$book$UNREADABLE_STORE_COPY_MARKER") }
        val before = phone.entries(aCopy)

        wipe()

        assertEquals("A's copy was deleted or changed by B's wipe", before, phone.entries(aCopy))
        assertEquals("A's copy lost its owner tag", walletA, marks()["copy_owner:$aCopy"])
        ownership.markWiped()
        ownership.claimFor(walletA)
        assertEquals("A's copy is not offered when A is back", listOf(aCopy), copies.offered())
        assertTrue(copies.restore())
        assertEquals("A's names did not come back", names, phone.entries(book))
    }

    // (2)
    @Test
    fun `2 - this wallet's own copies are named, and its marks, the counts and untagged marks go`() {
        owner(walletB)
        copy("$book${UNREADABLE_STORE_COPY_MARKER}10", walletB, mapOf("handled_copy:$book${UNREADABLE_STORE_COPY_MARKER}10" to true))
        copy("$book${UNREADABLE_STORE_COPY_MARKER}11", null)
        phone.put(
            ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME,
            mapOf(book to 2, "restore_tried:$walletB:$book" to true, "restore_paused:$book" to true),
        )

        val named = wipe()

        assertEquals(setOf("$book${UNREADABLE_STORE_COPY_MARKER}10", "$book${UNREADABLE_STORE_COPY_MARKER}11"), named.toSet())
        assertEquals("marks of the wiped wallet are left", emptyMap<String, Any?>(), marks())
    }

    // (3)
    @Test
    fun `3 - the other wallet's copy keeps its state and its wallet's marks`() {
        owner(walletB)
        val declined = "$book${UNREADABLE_STORE_COPY_MARKER}20"
        val held = "$book${UNREADABLE_STORE_COPY_MARKER}21"
        copy(declined, walletA, mapOf("handled_copy:$declined" to true, "dismissed_copy:$declined" to true))
        copy(held, walletA, mapOf("restore_held:$held" to 5L, "restore_offer_after:$held" to 9L))
        val kept =
            mapOf(
                "restore_tried:$walletA:$book" to true,
                "restore_paused:$book" to true,
                "restore_paused_owner:$book" to walletA,
            )
        phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, kept + mapOf(book to 1))
        val before = marks() - book

        val named = wipe()

        assertEquals("the other wallet's copies were named", emptyList<String>(), named)
        assertEquals("the other wallet's marks changed", before, marks())
    }

    // (4)
    @Test
    fun `4 - nothing spared - nothing is left and the file of marks goes`() {
        owner(walletB)
        copy("$book${UNREADABLE_STORE_COPY_MARKER}30", walletB)
        wipe()
        assertFalse("the file of marks stayed although nothing is spared", phone.exists(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME))
    }

    // (5)
    @Test
    fun `5 - a copy of A's set-aside bundle is spared at B's wipe, one of B's own bundle is named`() {
        owner(walletB)
        val ofA = "zchat_set_aside_$walletA${UNREADABLE_STORE_COPY_MARKER}40"
        val ofB = "zchat_set_aside_$walletB${UNREADABLE_STORE_COPY_MARKER}41"
        copy(ofA, null)
        copy(ofB, null)

        assertEquals(listOf(ofB), wipe())
        assertTrue("a copy of A's bundle was deleted", phone.exists(ofA))
    }

    // (6)
    @Test
    fun `6 - no owner recorded - untagged copies are named, tagged ones spared`() {
        for (recorded in listOf("none", null)) {
            phone.remove(CHAT_OWNER_PREFS_NAME)
            if (recorded != null) owner(recorded)
            val untagged = "$book${UNREADABLE_STORE_COPY_MARKER}5${if (recorded == null) 1 else 0}"
            val tagged = "$book${UNREADABLE_STORE_COPY_MARKER}6${if (recorded == null) 1 else 0}"
            copy(untagged, null)
            copy(tagged, walletA)

            assertEquals("record $recorded", listOf(untagged), wipe())
            assertTrue("record $recorded: a tagged copy was deleted", phone.exists(tagged))
        }
    }

    // (7)
    @Test
    fun `7 - source - clearAll deletes what the first function names, the marks file only when nothing is left`() {
        val preferences = StoreOpenerApi.mainSource("ui/screen/chat/datasource/ZchatPreferences.kt")
        val wipe = StoreOpenerApi.declaration(preferences, "clearAllStores")
        assertTrue("clearAll does not ask copiesToWipe(", "copiesToWipe(" in wipe)
        assertTrue("clearAll does not ask cleanMarksForWipe(", "cleanMarksForWipe(" in wipe)
        assertTrue(
            "the file of marks is deleted unconditionally",
            Regex(
                """if \(!\s*\w+\.cleanMarksForWipe\(\)\)\s*""" +
                    """appContext\.deleteSharedPreferences\(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME\)""",
            ).containsMatchIn(wipe),
        )
        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        val keptCopies = opener.substringAfter("class KeptChatStoreCopies", "").substringBefore("\n}")
        assertFalse("KeptChatStoreCopies deletes a file", Regex("""deleteSharedPreferences\(|\.delete\(\)""").containsMatchIn(keptCopies))
    }

    // (8) Guard.
    @Test
    fun `8 - source - Destroy All still deletes every preferences file by listing`() {
        val destroy = StoreOpenerApi.mainSource("ui/screen/chat/util/DestroyManager.kt")
        assertTrue("Destroy All no longer sweeps the app's files", "sweepFiles(" in destroy)
        val wipe = StoreOpenerApi.mainSource("ui/screen/chat/util/DestroyWipe.kt")
        val sweep = StoreOpenerApi.declaration(wipe, "sweepDataFolder")
        assertTrue("the sweep no longer lists shared_prefs first", "FOLDER_PREFS" in sweep)
    }

    /** A bundle store in memory (the wallet that is set aside, by tag). */
    private class MemoryBundles : SetAsideBundles {
        val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()

        override fun write(
            tag: String,
            content: Map<String, Map<String, Any?>>
        ) {
            bundles[tag] = content
        }

        override fun read(tag: String): Map<String, Map<String, Any?>>? = bundles[tag]

        override fun delete(tag: String) {
            bundles.remove(tag)
        }
    }
}
