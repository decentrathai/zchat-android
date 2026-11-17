package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bridge 4 (FINAL_PLAN 2.2 "ZChat Team", 5.2 OD6 b/c): the team has no keys for the OLD ZChat Team address,
 * so messages sent to it are never read. The contact entry for that address is renamed "ZChat Team (old
 * address, not monitored)".
 *
 * - renamed: the default entry gets exactly that name; a name the user chose is kept, with the label added.
 * - idempotent: a second run changes nothing.
 * - matched by address: only the entry whose address hashes to c5de4d11214a… is touched, whatever its name;
 *   an entry named "ZChat Team" at another address (the current Team address) is left alone.
 * - zero sends: it only renames a contact book entry, never adds or removes one, and has no way to send.
 */
class TeamContactMigrationTest {
    private val oldAddress = "u1synthetic0ld7eamaddressforthistestonly0000000000000000000000000000000000000000"
    private val oldHash = sha256(oldAddress)
    private val otherAddress = "u1synthetic0therc0ntactaddressforthistestonly00000000000000000000000000000000000"

    @Test
    fun renamed_the_default_entry() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team"), Contact(otherAddress, "Ana"))
        assertTrue(TeamContactMigration.run(book, oldHash))
        assertEquals("ZChat Team (old address, not monitored)", book.getContact(oldAddress)?.name)
        assertEquals("Ana", book.getContact(otherAddress)?.name)
    }

    @Test
    fun renamed_keeps_a_name_the_user_chose() {
        val book = RecordingContactBook(Contact(oldAddress, "Support"))
        assertTrue(TeamContactMigration.run(book, oldHash))
        assertEquals("Support (old ZChat Team address, not monitored)", book.getContact(oldAddress)?.name)
    }

    @Test
    fun idempotent() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team"))
        assertTrue(TeamContactMigration.run(book, oldHash))
        val afterFirst = book.getAllContacts()
        val writesAfterFirst = book.writes.size
        assertFalse(TeamContactMigration.run(book, oldHash), "a second run must report no change")
        assertFalse(TeamContactMigration.run(book, oldHash))
        assertEquals(afterFirst, book.getAllContacts())
        assertEquals(writesAfterFirst, book.writes.size, "a second run must not write")
    }

    @Test
    fun matched_by_address_not_by_name() {
        val book =
            RecordingContactBook(
                Contact(otherAddress, "ZChat Team"),
                Contact("  " + oldAddress.uppercase() + " ", "Old helpdesk"),
            )
        assertTrue(TeamContactMigration.run(book, oldHash))
        assertEquals("ZChat Team", book.getContact(otherAddress)?.name, "a Team entry at another address is not renamed")
        assertEquals("Old helpdesk (old ZChat Team address, not monitored)", book.getContact(oldAddress)?.name)
    }

    @Test
    fun no_entry_at_the_old_address_changes_nothing() {
        val book = RecordingContactBook(Contact(otherAddress, "ZChat Team"))
        assertFalse(TeamContactMigration.run(book, oldHash))
        assertTrue(book.writes.isEmpty())
    }

    @Test
    fun zero_sends_it_only_renames() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team"), Contact(otherAddress, "Ana"))
        TeamContactMigration.run(book, oldHash)
        assertEquals(listOf("updateContactName"), book.writes.map { it.first }.distinct(), "only a rename, nothing added or removed")
        assertEquals(2, book.getAllContacts().size)
        // No send path is reachable: the migration's source names no sender, relay, proposal or synchronizer.
        val src =
            File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/TeamContactMigration.kt")
                .readText()
        for (word in listOf("send", "Nostr", "Relay", "Proposal", "Synchronizer", "ChatViewModel", "submit")) {
            assertFalse(word in src.substringAfter("object TeamContactMigration"), "migration must not reference \"$word\"")
        }
    }

    @Test
    fun a_contact_book_read_failure_changes_nothing_and_does_not_throw() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team")).apply { failReads = true }
        assertFalse(TeamContactMigration.run(book, oldHash))
        assertTrue(book.writes.isEmpty())
    }

    @Test
    fun production_target_is_the_old_team_address_and_never_the_current_one() {
        assertTrue(TeamContactMigration.OLD_TEAM_ADDRESS_SHA256.startsWith("c5de4d11214a"))
        assertEquals(64, TeamContactMigration.OLD_TEAM_ADDRESS_SHA256.length)
        // The owner kept the current Team address (c1275ecbb): its entry must never be renamed.
        assertNotEquals(TeamContactMigration.OLD_TEAM_ADDRESS_SHA256, sha256(BuildConfig.ZCHAT_TEAM_ADDRESS.trim().lowercase()))
        val book = RecordingContactBook(Contact(BuildConfig.ZCHAT_TEAM_ADDRESS, "ZChat Team"))
        assertFalse(TeamContactMigration.run(book))
        assertEquals("ZChat Team", book.getContact(BuildConfig.ZCHAT_TEAM_ADDRESS)?.name)
    }

    // ---- R4-6 (round 3 report 4 item 15): the rename is a one-off, not something every start redoes ----

    @Test
    fun a_label_the_user_removed_does_not_come_back_at_the_next_start() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team"))
        val once = InMemoryRenameOnce()
        assertTrue(TeamContactMigration.runAtStart(book, once, oldHash))
        assertEquals("ZChat Team (old address, not monitored)", book.getContact(oldAddress)?.name)

        // The user renames the entry back — their choice, on their own contact list.
        book.updateContactName(oldAddress, "ZChat Team")
        val writesBefore = book.writes.size

        assertFalse(TeamContactMigration.runAtStart(book, once, oldHash), "the rename must not run a second time")
        assertEquals("ZChat Team", book.getContact(oldAddress)?.name, "the user's own name must stay")
        assertEquals(writesBefore, book.writes.size, "a later start must not write to the contact book")
    }

    @Test
    fun the_pass_is_recorded_even_when_there_was_nothing_to_rename() {
        val book = RecordingContactBook(Contact(otherAddress, "Ana"))
        val once = InMemoryRenameOnce()
        assertFalse(TeamContactMigration.runAtStart(book, once, oldHash))
        assertTrue(once.done(), "a start that read the contact book has made the one-off pass")
    }

    @Test
    fun a_contact_book_that_could_not_be_read_does_not_count_as_the_pass() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team")).apply { failReads = true }
        val once = InMemoryRenameOnce()
        assertFalse(TeamContactMigration.runAtStart(book, once, oldHash))
        assertFalse(once.done(), "nothing was checked, so the next start must try again")

        book.failReads = false
        assertTrue(TeamContactMigration.runAtStart(book, once, oldHash), "the retry must still rename")
        assertEquals("ZChat Team (old address, not monitored)", book.getContact(oldAddress)?.name)
    }

    @Test
    fun a_recorded_pass_reads_nothing_at_all() {
        val book = RecordingContactBook(Contact(oldAddress, "ZChat Team"))
        val once = InMemoryRenameOnce().apply { markDone() }
        assertFalse(TeamContactMigration.runAtStart(book, once, oldHash))
        assertEquals("ZChat Team", book.getContact(oldAddress)?.name)
        assertTrue(book.writes.isEmpty())
        assertEquals(0, book.listings, "a recorded pass must not even list the contact book")
    }

    private class InMemoryRenameOnce : TeamContactMigration.RenameOnce {
        private var flag = false

        override fun done(): Boolean = flag

        override fun markDone() {
            flag = true
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        return assertNotNull(dir)
    }

    /** In-memory book keyed like ContactBookImpl (trim + lowercase); records every write. */
    private class RecordingContactBook(
        vararg initial: Contact
    ) : ContactBook {
        private val map = linkedMapOf<String, Contact>()
        val writes = mutableListOf<Pair<String, String>>()
        var listings = 0
        var failReads = false

        init {
            initial.forEach { map[it.address.trim().lowercase()] = it }
        }

        override fun addContact(contact: Contact) {
            writes += "addContact" to contact.address
            map[contact.address.trim().lowercase()] = contact
        }

        override fun removeContact(address: String) {
            writes += "removeContact" to address
            map.remove(address.trim().lowercase())
        }

        override fun getContact(address: String): Contact? = if (failReads) null else map[address.trim().lowercase()]

        override fun getAllContacts(): List<Contact> {
            listings++
            if (failReads) throw IllegalStateException("keystore")
            return map.values.toList()
        }

        override fun hasContact(address: String): Boolean = getContact(address) != null

        override fun updateContactName(
            address: String,
            newName: String
        ) {
            writes += "updateContactName" to address
            val key = address.trim().lowercase()
            map[key]?.let { map[key] = it.copy(name = newName) }
        }

        override fun clearAll() {
            writes += "clearAll" to ""
            map.clear()
        }
    }
}
