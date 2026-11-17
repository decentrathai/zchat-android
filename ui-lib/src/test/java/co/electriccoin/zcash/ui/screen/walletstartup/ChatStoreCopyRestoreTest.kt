package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * R3-3 (2), round-2 report section 4 item 9 — after the third failed launch in a row the chat stores are recreated
 * (KR-1 keeps an exact copy first) and the chats look empty. There was no way back to that copy.
 *
 * Required: the launch that recreated them shows the start-up screen with the choice to restore the kept copy or to
 * continue without it. Restoring puts every entry back exactly (what the recreated store held is kept as a copy
 * too) and restarts ZCHAT; continuing keeps the copy on the phone and stops asking. Nothing is ever deleted.
 */
class ChatStoreCopyRestoreTest {
    private val storeName = "zchat_e2e_keys_encrypted"
    private val chatKeysOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** The shared_prefs folder of one phone. A file listed in [writeBlocked] silently keeps its old content. */
    private class Files(
        private val writeBlocked: Set<String> = emptySet()
    ) {
        val byName = LinkedHashMap<String, RecordingPrefs>()

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = name !in writeBlocked) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun listing(): List<String> = byName.filterValues { it.entries.isNotEmpty() }.keys.map { "$it.xml" }

        fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }
    }

    private fun copiesOf(files: Files) = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = { 200L })

    /** A phone whose chat store was recreated: a copy of the old one kept, the new one holding [current]. */
    private fun phoneAfterRecreate(
        current: Map<String, Any?> = emptyMap(),
        writeBlocked: Set<String> = emptySet(),
    ): Files =
        Files(writeBlocked).apply {
            byName[storeName] = RecordingPrefs(current, allowWrites = storeName !in writeBlocked)
            byName["$storeName${UNREADABLE_STORE_COPY_MARKER}100"] = RecordingPrefs(chatKeysOnDisk, allowWrites = true)
        }

    private fun check(
        files: Files,
        openChatStores: () -> Unit,
    ) = StartupStorageCheck(
        openSeedStore = {},
        openChatStores = openChatStores,
        ioDispatcher = Dispatchers.Unconfined,
        keptChatCopies = { copiesOf(files).unhandled() },
    )

    @Test
    fun the_launch_that_recreates_the_chat_stores_offers_to_restore_the_kept_copy() =
        runBlocking {
            val files = Files().apply { (file(storeName) as RecordingPrefs).entries.putAll(chatKeysOnDisk) }
            var keystoreBroken = true
            val openStore = {
                openEncryptedStore(
                    name = storeName,
                    open = {
                        if (files.entries(storeName).isEmpty()) keystoreBroken = false
                        if (keystoreBroken) throw KeyStoreException("the master key exists but is unusable") else RecordingPrefs()
                    },
                    plainPrefs = files::file,
                    sleep = {},
                )
            }
            repeat(2) { check(files) { openStore() }.run() } // launches 1 and 2: the safety screen, nothing changed

            val third = check(files) { openStore() }
            third.run()

            val state = third.state.value
            assertTrue("expected the restore offer, got $state", state is StartupStorageCheck.State.ChatCopyKept)
            val cause = startupFailureCause(null, state)
            assertNotNull("the start-up screen is not shown", cause)
            assertEquals(WalletStartupRepair.Offer.RestoreChatCopy, repair(files).offerFor(cause))
            assertEquals("the main app must wait for the choice", SecretState.LOADING, gatedSecretState(SecretState.READY, state))
            assertFalse("the splash must not hide the choice", isStartupStillLoading(SecretState.READY, state))
            assertEquals(1, files.copies().size)
        }

    @Test
    fun restoring_puts_every_entry_back_keeps_what_the_new_store_held_and_restarts() =
        runBlocking {
            val sinceRecreate = mapOf("AQnew-key" to "AUnew-value")
            val files = phoneAfterRecreate(current = sinceRecreate)
            var restarted = false

            assertTrue("the restore reported a failure", repair(files) { restarted = true }.restoreChatCopy())

            assertEquals("the kept copy was not put back exactly", chatKeysOnDisk, files.entries(storeName))
            assertTrue("what the recreated store held was lost", files.copies().any { files.entries(it) == sinceRecreate })
            assertTrue("the kept copy was deleted", files.copies().any { files.entries(it) == chatKeysOnDisk })
            assertEquals("the restored copy is still offered", emptyList<String>(), copiesOf(files).unhandled())
            assertTrue("ZCHAT must restart to open the restored store", restarted)
        }

    @Test
    fun continuing_without_the_copy_keeps_it_and_stops_asking() =
        runBlocking {
            val files = phoneAfterRecreate()
            var restarted = false

            assertTrue(repair(files) { restarted = true }.continueWithoutChatCopy())

            assertEquals("the copy was deleted", chatKeysOnDisk, files.entries("$storeName${UNREADABLE_STORE_COPY_MARKER}100"))
            assertEquals(emptyMap<String, Any?>(), files.entries(storeName))
            val next = check(files) {}
            next.run()
            assertEquals("the choice is asked again", StartupStorageCheck.State.Ready, next.state.value)
            assertTrue(restarted)
        }

    @Test
    fun a_restore_that_cannot_be_written_changes_nothing_and_keeps_offering_the_copy() =
        runBlocking {
            val files = phoneAfterRecreate(writeBlocked = setOf(storeName))
            var restarted = false

            assertFalse(repair(files) { restarted = true }.restoreChatCopy())

            assertEquals(emptyMap<String, Any?>(), files.entries(storeName))
            assertEquals(listOf("$storeName${UNREADABLE_STORE_COPY_MARKER}100"), copiesOf(files).unhandled())
            assertFalse("ZCHAT restarted although nothing was restored", restarted)
        }

    // Pin: holds before and after the fix.
    @Test
    fun without_a_kept_copy_the_app_starts_normally() =
        runBlocking {
            val files = Files()
            val next = check(files) {}
            next.run()
            assertEquals(StartupStorageCheck.State.Ready, next.state.value)
        }

    private fun repair(
        files: Files,
        restart: () -> Unit = {},
    ) = WalletStartupRepair(
        persistableWalletProvider = unusedDependency(),
        walletRestoringStateProvider = unusedDependency(),
        walletDataDatabase =
            object : WalletDataDatabase {
                override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
            },
        appRestarter = { restart() },
        keptChatCopies = copiesOf(files),
    )
}
