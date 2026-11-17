package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_KEY
import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyLaunches.Keystore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * ZC-709 (register ZC-709, low; store-opener design, revision 2a, sections 3.5 and 5.3, class A): kept copies of the
 * same chats no longer pile up. When a store is set aside and a kept copy of THIS store, of the same wallet, already
 * holds exactly what the store holds (the copy the user just put back and that could not be read again), that copy is
 * used again: it becomes unanswered and takes the path the round calls for, and no new file is written. A store that
 * holds nothing of the user's (no entry, or only its own key entries) leaves no copy at all, also when a restore
 * replaces it.
 *
 * The starts are driven through the opener with the folder's copy names ([ChatCopyLaunches]); the files are maps
 * ([ChatCopyPhoneFiles]), and case 6 uses a phone whose commits can be refused ([KeysetPhone]). All names synthetic.
 */
class KeptCopiesOfTheSameChatsAreKeptOnceTest {
    private val original = ChatCopyLaunches.chats("original")

    private fun phone(): ChatCopyLaunches =
        ChatCopyLaunches(ChatCopyPhoneFiles()).apply { (files.file(store) as RecordingPrefs).entries.putAll(original) }

    // (1) and (2), three failed starts per round. FAILS today: eleven files.
    @Test
    fun `1 and 2 - five rounds of restore and outage leave one copy file, unanswered after each round`() {
        val phone = phone()
        phone.outage()
        val first = phone.copyFiles().single()

        for (round in 1..5) {
            assertTrue("round $round: the copy is not offered", phone.bringOffer())
            assertTrue("round $round: the restore failed", runBlocking { phone.repair().restoreChatCopy() })
            phone.outage()
            assertTrue(
                "round $round: the copy set aside again is not unanswered: ${phone.copies().unhandled()}",
                phone.copyFiles().all { it in phone.copies().unhandled() },
            )
        }

        assertEquals("the folder holds more than one copy of the same chats", 1, phone.copyFiles().size)
        assertEquals("the copy is not the original chats byte for byte", original, phone.files.entries(phone.copyFiles().single()))
        assertEquals("the copy that survived is not the first one", first, phone.copyFiles().single())
    }

    // (1) and (2), a lost key: the round ends with the key called gone.
    @Test
    fun `1 and 2 - a put-back copy whose key is gone is set aside into the same file and called gone`() {
        val phone = phone()
        phone.launch(Keystore.KEY_GONE)
        val first = phone.copyFiles().single()
        var rounds = 0
        while (rounds < 5 && phone.bringOffer()) {
            rounds++
            assertTrue("round $rounds: the restore failed", runBlocking { phone.repair().restoreChatCopy() })
            phone.launch(Keystore.KEY_GONE)
        }

        assertEquals("the lost key never reached its verdict", 1, rounds)
        assertEquals("the key was not called gone", listOf(phone.store), phone.copies().restoreFailed())
        assertEquals("the folder holds more than one copy of the same chats", listOf(first), phone.copyFiles())
        assertEquals(original, phone.files.entries(first))
    }

    // (3) FAILS today: a key-only copy each time, and the first is offered.
    @Test
    fun `3 - nothing of the user's leaves no copy, and a restore over key entries only writes no copy of them`() {
        val keysOnly =
            ChatCopyLaunches(ChatCopyPhoneFiles()).apply {
                (files.file(store) as RecordingPrefs).entries.putAll(original.filterKeys { it in ChatCopyLaunches.KEYSET_ENTRIES })
            }
        keysOnly.outage()
        assertEquals("a store with only its key entries was copied", emptyList<String>(), keysOnly.copyFiles())
        assertEquals("a copy of nothing is offered", StartupStorageCheck.State.Ready, keysOnly.launch(Keystore.ANSWERS))

        val phone = phone()
        phone.outage()
        assertTrue(phone.bringOffer())
        val before = phone.copyFiles()
        assertTrue(runBlocking { phone.repair().restoreChatCopy() })
        assertEquals("the restore kept a copy of the recreated store's key entries", before, phone.copyFiles())
    }

    // (4) Guard: passes before and after.
    @Test
    fun `4 - two outages over different chats keep two files`() {
        val phone = phone()
        phone.outage()
        assertTrue(phone.bringOffer())
        assertTrue(runBlocking { phone.repair().continueWithoutChatCopy() })
        (phone.files.file(phone.store) as RecordingPrefs).entries.putAll(ChatCopyLaunches.chats("later"))
        phone.outage()

        assertEquals(2, phone.copyFiles().size)
    }

    // (5) Guard: passes before and after.
    @Test
    fun `5 - an identical copy of another wallet is not used again, a new one is written`() {
        val phone = phone()
        val theirs = "${phone.store}${UNREADABLE_STORE_COPY_MARKER}50"
        (phone.files.file(theirs) as RecordingPrefs).entries.putAll(original)
        (phone.files.file(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME) as RecordingPrefs).entries["copy_owner:$theirs"] = "b".repeat(64)
        (phone.files.file(CHAT_OWNER_PREFS_NAME) as RecordingPrefs).entries[CHAT_OWNER_KEY] = "a".repeat(64)

        phone.outage()

        assertEquals("another wallet's copy was used", 2, phone.copyFiles().size)
        assertEquals("another wallet's copy changed", original, phone.files.entries(theirs))
    }

    // (6) The order of the reuse.
    @Test
    fun `6 - the marks that make the copy unanswered are written before the store is cleared`() {
        val store = "zchat_e2e_keys_encrypted"
        val old = "$store${UNREADABLE_STORE_COPY_MARKER}500"
        val fresh = "$store${UNREADABLE_STORE_COPY_MARKER}777"
        val marks = listOf("handled_copy:$old", "dismissed_copy:$old", "restore_held:$old", "restore_parked:$old")

        fun phone(): KeysetPhone =
            KeysetPhone().apply {
                put(store, original)
                put(old, original)
                put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, mapOf(store to 2) + marks.associateWith { true })
            }

        fun setAside(phone: KeysetPhone): Result<Any> =
            runCatching {
                StoreOpenerApi.open(
                    StoreOpenerApi.newProcess(),
                    store,
                    phone::plain,
                    nowMillis = { 777L },
                    env = mapOf("copyNames" to { phone.snapshot().keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) } }),
                ) {
                    if (phone.entries(store).keys.any { it.startsWith("AQ-") }) throw KeyStoreException("synthetic: no answer")
                    phone.Library(store).open()
                }
            }

        // The copy is used again: its marks go, no new file.
        val reused = phone()
        assertTrue(setAside(reused).isSuccess)
        assertFalse("a new copy was written although the same chats were kept", reused.exists(fresh))
        marks.forEach { assertFalse("$it is still on disk", reused.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME).containsKey(it)) }

        // The store's clear is refused: the marks are on disk all the same, so they were written first.
        val clearRefused = phone().apply { refuseCommit += store }
        setAside(clearRefused)
        assertFalse("a new copy was written", clearRefused.exists(fresh))
        val marksAfter = clearRefused.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        marks.forEach { assertFalse("$it was not written before the store was cleared", marksAfter.containsKey(it)) }

        // The marks are refused: a new copy is written, as today.
        val marksRefused = phone().apply { refuseCommit += ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME }
        assertTrue(setAside(marksRefused).isSuccess)
        assertEquals("no new copy after a refused mark write", original, marksRefused.entries(fresh))
        assertEquals("the old copy changed", original, marksRefused.entries(old))

        // The marks and the new copy are refused: nothing is cleared and the call throws.
        val bothRefused = phone().apply { refuseCommit += setOf(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, fresh) }
        val outcome = setAside(bothRefused)
        assertTrue("the call did not throw: $outcome", outcome.exceptionOrNull() is EncryptedStoreUnavailableException)
        assertEquals("the store was cleared", original, bothRefused.entries(store))
    }
}
