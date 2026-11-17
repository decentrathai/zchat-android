package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.ContextWrapper
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatClaim
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.LiveChatStores
import co.electriccoin.zcash.ui.screen.chat.datasource.MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR
import co.electriccoin.zcash.ui.screen.chat.datasource.PrefsChatOwnerRecord
import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.datasource.WalletScopedStore
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * C6, R6-4 review finding 13 (P1, privacy / hidden data): a paused chat copy must not cross a wallet switch.
 *
 * Two outage rounds leave the restore of a chat store paused, with a kept copy of wallet A's chats unanswered. B5
 * lets the user tap Continue on that paused screen and use ZCHAT. Delete Wallet with "keep files" then skips the only
 * code that deletes kept copies, and loading wallet B's phrase (claimFor) moves only the live stores. The first
 * launch that opens cleanly lifts the pause, and the start-up screen then offered wallet A's copy to wallet B:
 * Restore wrote A's keyset and ciphertext into B's live store, and B's own chats were kept only as an answered copy
 * that is never offered again.
 *
 * Required: a kept copy belongs to the wallet whose chats it holds. While another wallet owns the chats it is not
 * offered, not restored and not answered; it stays on the phone and comes back when its own wallet is loaded again.
 * A copy kept before copies were tagged (an older install) still counts as the current wallet's.
 */
class ChatCopyFollowsItsWalletTest {
    private val storeName = "zchat_e2e_keys_encrypted"

    /** Wallet A's chat keys as they are on disk (Tink keyset and ciphertext). */
    private val walletAChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "a-keyset-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "a-keyset-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** Wallet B's own chats, set aside when A was loaded and put back when B is loaded again. */
    private val walletBChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "b-keyset-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "b-keyset-2",
            "BRy2-encrypted-key" to "BVa8-encrypted-value",
            "BRy3-encrypted-key" to "BVa9-encrypted-value",
        )

    private val walletA = "a".repeat(64)
    private val walletB = "b".repeat(64)

    private enum class Keystore { ANSWERS, OUT, LATE }

    /** The shared_prefs folder of one phone, with a clock that moves on so each kept copy gets its own name. */
    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()
        var now = 100L

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun listing(): List<String> = byName.filterValues { it.entries.isNotEmpty() }.keys.map { "$it.xml" }

        fun tick(): Long = ++now
    }

    /** The app context of the phone: every SharedPreferences file is one of [files]. */
    private class FilesContext(
        private val files: Files
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(
            name: String,
            mode: Int
        ): SharedPreferences = files.file(name)

        override fun getApplicationContext(): android.content.Context = this
    }

    private fun copiesOf(files: Files) = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

    /** The real owner record, over this phone's files. */
    private fun ownership(files: Files, bundles: MutableMap<String, Map<String, Map<String, Any?>>>): ChatWalletOwnership =
        ChatWalletOwnership(
            record = PrefsChatOwnerRecord(FilesContext(files)),
            bundles =
                object : SetAsideBundles {
                    override fun write(
                        tag: String,
                        content: Map<String, Map<String, Any?>>
                    ) {
                        bundles[tag] = content
                    }

                    override fun read(tag: String) = bundles[tag]

                    override fun delete(tag: String) {
                        bundles.remove(tag)
                    }
                },
            live =
                object : LiveChatStores {
                    override fun stores(): List<WalletScopedStore> =
                        listOf(
                            object : WalletScopedStore {
                                override val storeId = storeName

                                override fun exportEntries(): Map<String, Any?> = files.entries(storeName)

                                override fun importEntries(entries: Map<String, Any?>) {
                                    val store = files.file(storeName) as RecordingPrefs
                                    store.entries.clear()
                                    store.entries.putAll(entries)
                                }
                            }
                        )

                    override fun reload() = Unit
                },
            untaggedMarks = copiesOf(files),
        )

    /** One launch the user sees, with the phone's security system in state [keystore]. */
    private fun launch(
        files: Files,
        keystore: Keystore,
    ): StartupStorageCheck {
        var opens = 0
        val check =
            StartupStorageCheck(
                openSeedStore = {},
                openChatStores = {
                    openEncryptedStore(
                        name = storeName,
                        open = {
                            when (keystore) {
                                Keystore.ANSWERS -> RecordingPrefs()
                                Keystore.OUT -> throw KeyStoreException("the phone's security system did not answer")
                                Keystore.LATE ->
                                    if (++opens <= TRIES_TO_OPEN_IN_ONE_LAUNCH) {
                                        throw KeyStoreException("the phone's security system did not answer yet")
                                    } else {
                                        RecordingPrefs()
                                    }
                            }
                        },
                        plainPrefs = files::file,
                        nowMillis = files::tick,
                        sleep = {},
                    )
                },
                ioDispatcher = Dispatchers.Unconfined,
                keptChatCopies = { copiesOf(files).unhandled() },
                chatCopyRestoreFailed = { copiesOf(files).restoreFailed() },
                chatCopyRestorePaused = { copiesOf(files).restorePaused() },
            )
        runBlocking { check.run() }
        return check
    }

    private fun outage(files: Files) = repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR) { launch(files, Keystore.OUT) }

    private fun repair(files: Files) =
        WalletStartupRepair(
            persistableWalletProvider = unusedDependency(),
            walletRestoringStateProvider = unusedDependency(),
            walletDataDatabase =
                object : WalletDataDatabase {
                    override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
                },
            appRestarter = {},
            keptChatCopies = copiesOf(files),
        )

    /**
     * Wallet A owns the chats. Two outage rounds pause the restore with A's copy unanswered, and the user taps
     * Continue on the paused screen and chats on. Wallet B's chats were set aside on this phone earlier.
     */
    private fun walletAPausedWithBSetAside(): Pair<Files, MutableMap<String, Map<String, Map<String, Any?>>>> {
        val files = Files()
        val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
        bundles[walletB] = mapOf(storeName to walletBChats)
        assertEquals(ChatClaim.ADOPTED, ownership(files, bundles).claimFor(walletA))
        (files.file(storeName) as RecordingPrefs).entries.putAll(walletAChats)

        outage(files)
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("the first restore reported a failure", runBlocking { repair(files).restoreChatCopy() })
        outage(files)
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("the extra try was never offered", runBlocking { repair(files).restoreChatCopy() })
        repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
        assertEquals(
            StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
            launch(files, Keystore.LATE).state.value,
        )
        assertTrue("Continue on the paused screen failed", runBlocking { repair(files).acknowledgeChatCopyPause() })
        assertTrue("wallet A's chats are not kept unanswered", copiesOf(files).unhandled().any { files.entries(it) == walletAChats })
        return files to bundles
    }

    @Test
    fun wallet_b_is_not_offered_wallet_a_s_paused_copy() {
        val (files, bundles) = walletAPausedWithBSetAside()

        // Delete Wallet with "keep files", then wallet B's phrase: B's chats come back into the live store.
        assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletB))
        assertEquals(walletBChats, files.entries(storeName))

        // The phone's security system answers at once: the pause lifts.
        val launched = launch(files, Keystore.ANSWERS).state.value

        assertFalse(
            "wallet B was offered wallet A's kept chats: $launched",
            launched is StartupStorageCheck.State.ChatCopyKept,
        )
        assertEquals(StartupStorageCheck.State.Ready, launched)
        assertEquals("wallet B's live chats were touched", walletBChats, files.entries(storeName))
    }

    @Test
    fun a_restore_under_wallet_b_never_writes_wallet_a_s_chats_into_b_s_store() {
        val (files, bundles) = walletAPausedWithBSetAside()
        ownership(files, bundles).claimFor(walletB)
        launch(files, Keystore.ANSWERS)

        // Whatever the screen shows, a Restore tap under wallet B must not move A's copy into B's store.
        runBlocking { repair(files).restoreChatCopy() }

        assertEquals("wallet A's keyset and ciphertext were written into wallet B's live store", walletBChats, files.entries(storeName))
        assertTrue(
            "wallet B's own chats were set aside as a copy nobody is offered",
            files.byName.keys.none { it.contains(UNREADABLE_STORE_COPY_MARKER) && files.entries(it) == walletBChats },
        )
    }

    @Test
    fun wallet_a_s_copy_comes_back_when_wallet_a_is_loaded_again() {
        val (files, bundles) = walletAPausedWithBSetAside()
        ownership(files, bundles).claimFor(walletB)
        launch(files, Keystore.ANSWERS)
        runBlocking { repair(files).continueWithoutChatCopy() }

        // Wallet A's phrase again: A's chats of the pause come back into the live store, and A's copy is offered.
        assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
        val back = launch(files, Keystore.ANSWERS).state.value

        assertTrue("wallet A's kept chats were not offered back to wallet A: $back", back is StartupStorageCheck.State.ChatCopyKept)
        val offered = (back as StartupStorageCheck.State.ChatCopyKept).copies
        assertTrue("the offer is not wallet A's kept chats: $offered", offered.any { files.entries(it) == walletAChats })
        assertTrue(runBlocking { repair(files).restoreChatCopy() })
        assertEquals("wallet A's chats did not come back", walletAChats, files.entries(storeName))
    }

    // Control: holds before and after the fix — a copy kept before copies were tagged counts as the current wallet's.
    @Test
    fun an_untagged_copy_from_an_older_install_is_still_offered_to_the_current_wallet() {
        val files = Files()
        ownership(files, LinkedHashMap()).claimFor(walletA)
        (files.file("$storeName${UNREADABLE_STORE_COPY_MARKER}50") as RecordingPrefs).entries.putAll(walletAChats)

        val launched = launch(files, Keystore.ANSWERS).state.value

        assertTrue("an older install's kept copy was hidden: $launched", launched is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(runBlocking { repair(files).restoreChatCopy() })
        assertEquals(walletAChats, files.entries(storeName))
    }

    /**
     * F10 (fix-all 2.13.0, chat copy #16): a copy PARKED after its second pause (never offered by itself again) follows
     * its wallet like a paused one. Wallet B is neither told about wallet A's park nor shown the Settings "Restore old
     * chats" row for it, and cannot release it; when wallet A is loaded again its park is said, and Settings reaches
     * A's copy. Fails at 7d8799d29: the fourth failed restore only pauses again.
     */
    @Test
    fun a_parked_copy_follows_its_wallet() {
        val files = Files()
        val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
        bundles[walletB] = mapOf(storeName to walletBChats)
        assertEquals(ChatClaim.ADOPTED, ownership(files, bundles).claimFor(walletA))
        (files.file(storeName) as RecordingPrefs).entries.putAll(walletAChats)

        fun outageRound(): StartupStorageCheck.State {
            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            return launch(files, Keystore.LATE).state.value
        }

        fun offeredAndRestored() {
            val offered = launch(files, Keystore.ANSWERS).state.value
            assertTrue("the kept copy was not offered: $offered", offered is StartupStorageCheck.State.ChatCopyKept)
            assertTrue("the restore reported a failure", runBlocking { repair(files).restoreChatCopy() })
        }

        outage(files)
        offeredAndRestored()
        outage(files)
        offeredAndRestored()
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound())
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        offeredAndRestored()
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound())
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        files.now += ChatCopyBackoffApi.DAY_MS + 1
        offeredAndRestored()
        val parked = outageRound()
        assertTrue("wallet A's fourth failed restore did not park the copy: $parked", ChatCopyBackoffApi.isParked(parked))

        // Wallet B's phrase before A read the parked screen.
        assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletB))
        val underB = launch(files, Keystore.ANSWERS).state.value
        assertEquals("wallet B was told about wallet A's park, or offered A's copy: $underB", StartupStorageCheck.State.Ready, underB)
        assertEquals("wallet B is shown wallet A's parked copy in Settings", emptyList<String>(), ChatCopyBackoffApi.held(copiesOf(files)))
        assertFalse("wallet B released wallet A's parked copy", ChatCopyBackoffApi.retryHeld(copiesOf(files)))

        // Wallet A again: its park is said, and Settings reaches its copy.
        assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
        val underA = launch(files, Keystore.ANSWERS).state.value
        assertTrue("wallet A's park was not said when A came back: $underA", ChatCopyBackoffApi.isParked(underA))
        assertTrue(ChatCopyBackoffApi.acknowledgeChatCopyPark(repair(files)))
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS).state.value)
        assertTrue(
            "wallet A's parked copy is not listed for Settings",
            ChatCopyBackoffApi.held(copiesOf(files)).any { files.entries(it) == walletAChats },
        )
        assertTrue(ChatCopyBackoffApi.retryHeld(copiesOf(files)))
        val back = launch(files, Keystore.ANSWERS).state.value
        assertTrue("wallet A's copy was not offered after Restore old chats: $back", back is StartupStorageCheck.State.ChatCopyKept)
        assertTrue((back as StartupStorageCheck.State.ChatCopyKept).copies.any { files.entries(it) == walletAChats })
    }

    private companion object {
        /** openEncryptedStore's tries to open a store inside one launch (the first one and its two retries). */
        const val TRIES_TO_OPEN_IN_ONE_LAUNCH = 3
    }
}
