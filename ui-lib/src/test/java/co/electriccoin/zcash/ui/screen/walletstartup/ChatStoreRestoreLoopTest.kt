package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-5, round-3 report section 4 item 8 — restoring a kept chat copy whose key is really gone must not loop.
 *
 * KR-1 keeps an exact copy of a chat store it cannot read and recreates the store; R3-3 offers that copy back at
 * start-up. The copy holds the very keyset the phone could not unlock. When that key is gone for good (the user
 * changed the screen lock, or the files arrived on another phone), putting the copy back and restarting hands the
 * same unreadable store to the same check: it keeps ANOTHER copy, recreates the store again, and the start-up
 * screen offers the same restore again — at every launch, one more copy file each time, with no way out but
 * giving up.
 *
 * Required: after a restore the next start decides. If the store opens, nothing is said. If it still cannot be
 * read, ZCHAT says once that the key for those old chats is gone and starts normally afterwards; it never offers
 * that restore again. Nothing is deleted: every copy stays on the phone.
 */
class ChatStoreRestoreLoopTest {
    private val storeName = "zchat_e2e_keys_encrypted"
    private val chatKeysOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** The shared_prefs folder of one phone, with a clock that moves on so each kept copy gets its own name. */
    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()
        var now = 100L

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun listing(): List<String> = byName.filterValues { it.entries.isNotEmpty() }.keys.map { "$it.xml" }

        fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }

        fun tick(): Long = ++now
    }

    private fun copiesOf(files: Files) = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

    private fun phone(): Files = Files().apply { (file(storeName) as RecordingPrefs).entries.putAll(chatKeysOnDisk) }

    /**
     * One launch the user sees. [keyGone] is the phone's security system: while it is true the store's own keyset
     * cannot be unlocked, so a store that still holds entries throws the permanent error.
     */
    private fun launch(
        files: Files,
        keyGone: Boolean = true,
    ): StartupStorageCheck {
        val check =
            StartupStorageCheck(
                openSeedStore = {},
                openChatStores = {
                    openEncryptedStore(
                        name = storeName,
                        open = {
                            if (keyGone && files.entries(storeName).isNotEmpty()) {
                                throw javax.crypto.AEADBadTagException("the stored keyset does not decrypt")
                            }
                            RecordingPrefs()
                        },
                        plainPrefs = files::file,
                        nowMillis = files::tick,
                        sleep = {},
                    )
                },
                ioDispatcher = Dispatchers.Unconfined,
                keptChatCopies = { copiesOf(files).unhandled() },
                chatCopyRestoreFailed = { copiesOf(files).restoreFailed() },
            )
        runBlocking { check.run() }
        return check
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

    @Test
    fun a_restore_whose_key_is_really_gone_is_not_offered_a_second_time() =
        runBlocking {
            val files = phone()

            // Launch 1: the key is gone, KR-1 keeps a copy and recreates the store, the restore is offered.
            val first = launch(files)
            assertTrue("expected the restore offer, got ${first.state.value}", first.state.value is StartupStorageCheck.State.ChatCopyKept)

            // The user taps Restore my chats. The copy goes back into the store; ZCHAT restarts.
            assertTrue("the restore reported a failure", repair(files).restoreChatCopy())
            assertEquals("the copy was not put back", chatKeysOnDisk, files.entries(storeName))

            // Launch 2: the key is still gone, so the store cannot be read after all.
            val second = launch(files)

            val state = second.state.value
            assertTrue(
                "the same restore is offered again, so the user is in a loop: $state",
                state is StartupStorageCheck.State.ChatCopyRestoreFailed,
            )
            assertEquals(listOf(storeName), (state as StartupStorageCheck.State.ChatCopyRestoreFailed).stores)
            assertEquals(WalletStartupRepair.Offer.ChatCopyRestoreFailed, repair(files).offerFor(startupFailureCause(null, state)))
            assertEquals("nothing must be offered for restoring any more", emptyList<String>(), copiesOf(files).unhandled())
        }

    @Test
    fun the_explanation_is_given_once_and_then_zchat_starts_normally() =
        runBlocking {
            val files = phone()
            launch(files)
            repair(files).restoreChatCopy()
            launch(files)
            var restarted = false

            assertTrue("the explanation could not be answered", repair(files) { restarted = true }.acknowledgeChatCopyFailure())
            assertTrue("ZCHAT must restart after the explanation", restarted)

            val next = launch(files)
            assertEquals("ZCHAT still stops at the start-up screen", StartupStorageCheck.State.Ready, next.state.value)
        }

    @Test
    fun every_copy_stays_on_the_phone_after_a_restore_that_could_not_be_read() =
        runBlocking {
            val files = phone()
            launch(files)
            repair(files).restoreChatCopy()
            launch(files)
            repair(files).acknowledgeChatCopyFailure()

            val copies = files.copies()
            assertTrue("the kept chat keys were deleted", copies.any { files.entries(it) == chatKeysOnDisk })
            copies.forEach { assertTrue("copy $it was emptied", files.entries(it).isNotEmpty()) }
        }

    @Test
    fun a_restore_that_works_says_nothing_and_leaves_the_chats_in_place() =
        runBlocking {
            val files = phone()
            launch(files)
            assertTrue(repair(files).restoreChatCopy())

            // The phone's security system answers again: the very same store now opens.
            val next = launch(files, keyGone = false)

            assertEquals(StartupStorageCheck.State.Ready, next.state.value)
            assertEquals("the restored chat keys were touched", chatKeysOnDisk, files.entries(storeName))
            assertEquals(emptyList<String>(), copiesOf(files).restoreFailed())
        }

    // Pin: holds before and after the fix — a first kept copy is still offered, and Continue still ends it.
    @Test
    fun a_first_kept_copy_is_still_offered_and_continuing_still_ends_it() =
        runBlocking {
            val files = phone()

            val first = launch(files)
            assertTrue(first.state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).continueWithoutChatCopy())

            val next = launch(files)
            assertEquals(StartupStorageCheck.State.Ready, next.state.value)
            assertFalse("the copy was deleted", files.copies().isEmpty())
        }
}
