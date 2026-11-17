package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.ContextWrapper
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.R
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
 * Round 8 D6 (REVIEW_7C F17, F18, F19, and the smaller issue "restore_* marks are per store, not per owner").
 *
 * C6 tagged every kept chat copy and every paused restore with the wallet that owned the chats when it was made, so
 * a copy is offered only to that wallet. Three gaps remain:
 *
 *  - F17: on a phone upgraded from 2.12.4 no chat owner is recorded, so copies and pauses made there carry no tag,
 *    and an untagged copy counts as the CURRENT wallet's. The owner is later written by Delete Wallet
 *    (recordOwnerIfUnset) or by the first claim (ADOPTED), and neither tags what is already on the phone. After
 *    recordOwnerIfUnset(A) and claimFor(B), wallet B is offered A's kept chats, and Restore writes A's keyset and
 *    ciphertext into B's live store. Required: everything untagged is tagged with the wallet that owned it when the
 *    owner record is filled or handed over, so it is offered only to that wallet.
 *  - F18: the "used after the pause" mark and the restore_tried / restore_retried marks are kept per store, not per
 *    wallet. Wallet B's Continue-without removes A's "used after the pause" mark (A's re-offer then loses its warning
 *    and a restore hides A's pause-time chats as an answered copy), and B inherits the extra restore A already spent.
 *    Required: those marks belong to the wallet that made them; the old per-store keys keep their meaning for the
 *    wallet that owns them.
 *  - F19: the "used after the pause" mark is set on Continue, before anything is written. When the next clean
 *    launch lifts the pause and the store still holds only its keyset, the offer says "You have used ZCHAT
 *    since…" although nothing was written. Required: the plain offer text then.
 *
 * All wallet tags, keysets and entries here are synthetic.
 */
class ChatCopyMarksBelongToOneWalletTest {
    private val storeName = "zchat_e2e_keys_encrypted"

    /** Wallet A's chat keys as they are on disk (Tink keyset and ciphertext). */
    private val walletAChats: Map<String, Any?> =
        mapOf(
            KEY_KEYSET to "a-keyset-1",
            VALUE_KEYSET to "a-keyset-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** What wallet A chats in the recreated store after Continue on the paused screen. */
    private val walletAPauseTimeChats: Map<String, Any?> =
        mapOf(
            KEY_KEYSET to "a-keyset-3",
            VALUE_KEYSET to "a-keyset-4",
            "PAID-encrypted-key" to "may-have-been-sent-encrypted-value",
        )

    /** Wallet B's own chats. */
    private val walletBChats: Map<String, Any?> =
        mapOf(
            KEY_KEYSET to "b-keyset-1",
            VALUE_KEYSET to "b-keyset-2",
            "BRy2-encrypted-key" to "BVa8-encrypted-value",
            "BRy3-encrypted-key" to "BVa9-encrypted-value",
        )

    /** A recreated store the user has not written to yet: only its fresh Tink keyset. */
    private val keysetOnly: Map<String, Any?> =
        mapOf(
            KEY_KEYSET to "fresh-keyset-1",
            VALUE_KEYSET to "fresh-keyset-2",
        )

    private val walletA = "a".repeat(64)
    private val walletB = "b".repeat(64)
    private val walletC = "c".repeat(64)

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

    private fun Files.live(): RecordingPrefs = file(storeName) as RecordingPrefs

    /** The open-failures file, where the restore marks are kept (as the app writes it). */
    private fun Files.marks(): RecordingPrefs = file(OPEN_FAILURES_FILE) as RecordingPrefs

    private fun copiesOf(files: Files) = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

    /** The real owner record, over this phone's files; set-aside bundles in [bundles]. */
    private fun ownership(
        files: Files,
        bundles: MutableMap<String, Map<String, Map<String, Any?>>>
    ): ChatWalletOwnership =
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

                                // A snapshot, as the real export is: a bundle never changes with the live store.
                                override fun exportEntries(): Map<String, Any?> = LinkedHashMap(files.entries(storeName))

                                override fun importEntries(entries: Map<String, Any?>) {
                                    val store = files.live()
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

    /** A put-back copy meets the outage again: two launches stop, the third reaches the recreate and opens late. */
    private fun outageAfterARestore(files: Files): StartupStorageCheck.State {
        repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
        return launch(files, Keystore.LATE).state.value
    }

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
     * The live store holds wallet A's chats. Two outage rounds pause the restore with A's copy unanswered, and the
     * user taps Continue on the paused screen. Whether an owner is recorded is up to the caller.
     */
    private fun pausedAndContinued(files: Files) {
        files.live().entries.putAll(walletAChats)
        outage(files)
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("the first restore reported a failure", runBlocking { repair(files).restoreChatCopy() })
        outage(files)
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("the extra try was never offered", runBlocking { repair(files).restoreChatCopy() })
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageAfterARestore(files))
        assertTrue("Continue on the paused screen failed", runBlocking { repair(files).acknowledgeChatCopyPause() })
        assertTrue("wallet A's chats are not kept unanswered", copiesOf(files).unhandled().any { files.entries(it) == walletAChats })
    }

    /** Asserts that the current wallet is offered nothing, and that a Restore tap leaves its live store alone. */
    private fun assertNothingOfferedAndNothingRestored(
        files: Files,
        who: String,
        liveChats: Map<String, Any?>,
    ) {
        val launched = launch(files, Keystore.ANSWERS).state.value
        assertEquals("$who is offered another wallet's kept chats", emptyList<String>(), copiesOf(files).unhandled())
        assertEquals("$who is offered another wallet's kept chats", emptyList<String>(), copiesOf(files).offered())
        assertEquals("$who's launch: $launched", StartupStorageCheck.State.Ready, launched)
        assertFalse("a Restore tap under $who put a kept copy back", runBlocking { repair(files).restoreChatCopy() })
        assertEquals("another wallet's keyset and ciphertext were written into $who's live store", liveChats, files.entries(storeName))
    }

    // (a) F17: Delete Wallet records the owner on a phone that never had one; the next wallet must not get A's copy.
    @Test
    fun a_copy_kept_before_any_owner_was_recorded_is_not_offered_to_the_next_wallet() {
        val files = Files()
        val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
        pausedAndContinued(files)

        // Delete Wallet with "keep files" records wallet A while its phrase is still here; then wallet B's phrase.
        ownership(files, bundles).recordOwnerIfUnset(walletA)
        assertEquals(ChatClaim.STARTED_EMPTY, ownership(files, bundles).claimFor(walletB))
        files.live().entries.putAll(walletBChats)

        assertNothingOfferedAndNothingRestored(files, "wallet B", walletBChats)

        // Wallet A's phrase again: A's kept chats are offered to A, and they restore.
        assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
        val back = launch(files, Keystore.ANSWERS).state.value
        assertTrue("wallet A's kept chats were not offered back to wallet A: $back", back is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(
            "the offer is not wallet A's kept chats",
            (back as StartupStorageCheck.State.ChatCopyKept).copies.any { files.entries(it) == walletAChats },
        )
        assertTrue(runBlocking { repair(files).restoreChatCopy() })
        assertEquals("wallet A's chats did not come back", walletAChats, files.entries(storeName))
    }

    // (a) F17, variant: the first claim adopts an owner-less phone; the wallet after it must not get the adopted copies.
    @Test
    fun copies_adopted_by_the_first_wallet_are_not_offered_to_the_next_one() {
        val files = Files()
        val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
        pausedAndContinued(files)

        // An install from before P2e: wallet B adopts everything on the phone; then wallet C's phrase.
        assertEquals(ChatClaim.ADOPTED, ownership(files, bundles).claimFor(walletB))
        assertEquals(ChatClaim.STARTED_EMPTY, ownership(files, bundles).claimFor(walletC))

        assertNothingOfferedAndNothingRestored(files, "wallet C", emptyMap())

        // The copies wallet B adopted come back with wallet B.
        assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletB))
        val back = launch(files, Keystore.ANSWERS).state.value
        assertTrue("wallet B's adopted copies were not offered back to wallet B: $back", back is StartupStorageCheck.State.ChatCopyKept)
    }

    // (b) F18: wallet B's Continue-without must not end wallet A's "used after the pause" episode.
    @Test
    fun another_wallet_s_continue_without_keeps_the_used_after_pause_mark_of_wallet_a() =
        runBlocking {
            val files = Files()
            val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
            bundles[walletB] = mapOf(storeName to walletBChats)
            assertEquals(ChatClaim.ADOPTED, ownership(files, bundles).claimFor(walletA))
            pausedAndContinued(files)
            // ZCHAT opens during the pause and wallet A chats on.
            files.live().entries.putAll(walletAPauseTimeChats)

            // Wallet B: its own outage keeps a copy, and B answers the offer with Continue without it.
            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletB))
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS).state.value)
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue("wallet B's Continue without its copy failed", repair(files).continueWithoutChatCopy())

            // Wallet A again.
            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
            assertEquals(
                "wallet B's Continue-without ended wallet A's used-after-pause episode",
                listOf(storeName),
                copiesOf(files).restorePaused().usedAfterPause,
            )
            val back = launch(files, Keystore.ANSWERS).state.value
            assertTrue("wallet A's kept chats were not offered back: $back", back is StartupStorageCheck.State.ChatCopyKept)
            assertEquals(
                "wallet A's re-offer lost its warning that a restore sets the chats of the pause aside",
                WalletStartupRepair.Offer.RestoreChatCopyAfterUse,
                repair(files).offerFor(startupFailureCause(null, back)),
            )
        }

    // (b) F18, variant: the extra restore wallet A spent is A's; wallet B's first outage still gets its own.
    @Test
    fun the_extra_restore_wallet_a_spent_is_not_taken_from_wallet_b() =
        runBlocking {
            val files = Files()
            val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
            bundles[walletB] = mapOf(storeName to walletBChats)
            assertEquals(ChatClaim.ADOPTED, ownership(files, bundles).claimFor(walletA))
            files.live().entries.putAll(walletAChats)

            // Wallet A: a restore met the outage again, so A's one extra try is now given (and spent).
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())
            outage(files)
            assertTrue("wallet A's extra try", launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)

            // Wallet B's phrase. B's own chats meet their first outage, B puts them back, and the outage returns.
            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletB))
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS).state.value)
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())
            val afterB = outageAfterARestore(files)

            assertFalse(
                "wallet B's first failed restore was paused at once: B inherited the extra try wallet A spent",
                afterB is StartupStorageCheck.State.ChatCopyRestorePaused,
            )
            assertTrue("wallet B's extra try was not offered: $afterB", afterB is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(
                "the offer is not wallet B's chats",
                (afterB as StartupStorageCheck.State.ChatCopyKept).copies.any { files.entries(it) == walletBChats },
            )

            // Wallet A again: its extra try stays spent, so its next failed restore pauses.
            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())
            assertEquals(
                "wallet A was given a second extra try",
                StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
                outageAfterARestore(files),
            )
        }

    // (c) F19: Continue, then a clean launch while the store still holds only its keyset: the plain offer.
    @Test
    fun a_store_that_holds_no_chats_after_continue_gets_the_plain_offer() =
        runBlocking {
            val files = Files()
            pausedAndContinued(files)
            // The restart after Continue: the recreated store got its keyset, and the user has written nothing yet.
            files.live().entries.putAll(keysetOnly)

            val back = launch(files, Keystore.ANSWERS).state.value

            assertTrue("the kept chats were not offered after the pause: $back", back is StartupStorageCheck.State.ChatCopyKept)
            assertFalse(
                "the offer says the user has used ZCHAT since the pause, although the store holds no chats",
                (back as StartupStorageCheck.State.ChatCopyKept).usedAfterPause,
            )
            val offer = repair(files).offerFor(startupFailureCause(null, back))
            assertEquals(WalletStartupRepair.Offer.RestoreChatCopy, offer)
            assertEquals(R.string.wallet_startup_chat_copy_message, walletStartupFailedContent(offer).message)
        }

    // (e) Old per-store marks, written before this fix: the wallet that owns the chats keeps its behaviour.
    // Pin: holds before and after the fix.
    @Test
    fun an_old_used_after_pause_mark_still_warns_the_wallet_that_owns_it() =
        runBlocking {
            val files = Files()
            assertEquals(ChatClaim.ADOPTED, ownership(files, LinkedHashMap()).claimFor(walletA))
            files.live().entries.putAll(walletAPauseTimeChats)
            val copy = "$storeName${UNREADABLE_STORE_COPY_MARKER}50"
            (files.file(copy) as RecordingPrefs).entries.putAll(walletAChats)
            files.marks().entries["copy_owner:$copy"] = walletA
            files.marks().entries["used_after_pause:$storeName"] = walletA

            val back = launch(files, Keystore.ANSWERS).state.value

            assertTrue("wallet A's kept chats were not offered: $back", back is StartupStorageCheck.State.ChatCopyKept)
            assertTrue("the old mark lost its warning", (back as StartupStorageCheck.State.ChatCopyKept).usedAfterPause)
            assertTrue(repair(files).restoreChatCopy())
            assertEquals(walletAChats, files.entries(storeName))
            assertTrue(
                "the chats of the pause were hidden by the restore",
                copiesOf(files).unhandled().any { files.entries(it) == walletAPauseTimeChats },
            )
        }

    // (e) An old mark with no wallet in it (a phone that never recorded an owner) belongs to the wallet that is
    // recorded for it later, and to no other.
    @Test
    fun an_old_owner_less_used_after_pause_mark_goes_with_the_wallet_recorded_for_it() =
        runBlocking {
            val files = Files()
            val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
            files.live().entries.putAll(walletAPauseTimeChats)
            val copy = "$storeName${UNREADABLE_STORE_COPY_MARKER}50"
            (files.file(copy) as RecordingPrefs).entries.putAll(walletAChats)
            files.marks().entries["used_after_pause:$storeName"] = ""

            ownership(files, bundles).recordOwnerIfUnset(walletA)
            assertEquals(ChatClaim.STARTED_EMPTY, ownership(files, bundles).claimFor(walletB))
            files.live().entries.putAll(walletBChats)
            assertEquals(
                "wallet B was told it used ZCHAT during wallet A's pause",
                emptyList<String>(),
                copiesOf(files).restorePaused().usedAfterPause,
            )
            assertNothingOfferedAndNothingRestored(files, "wallet B", walletBChats)

            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
            val back = launch(files, Keystore.ANSWERS).state.value
            assertTrue("wallet A's kept chats were not offered: $back", back is StartupStorageCheck.State.ChatCopyKept)
            assertTrue("wallet A's old mark lost its warning", (back as StartupStorageCheck.State.ChatCopyKept).usedAfterPause)
        }

    // (e) An old per-store restore_retried mark is the owning wallet's spent extra try. Pin: holds before and after.
    @Test
    fun an_old_restore_retried_mark_still_counts_for_the_wallet_that_owns_the_chats() =
        runBlocking {
            val files = Files()
            assertEquals(ChatClaim.ADOPTED, ownership(files, LinkedHashMap()).claimFor(walletA))
            files.live().entries.putAll(walletAChats)
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            // Written by an older build: this store's one extra try is already spent.
            files.marks().entries["restore_retried:$storeName"] = true

            assertTrue(repair(files).restoreChatCopy())

            assertEquals(
                "the extra try an older build recorded as spent was given again",
                StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
                outageAfterARestore(files),
            )
        }

    // (e) An old per-store restore_tried mark (a restore tapped before the update) is still decided by the next
    // failed open. Pin: holds before and after the fix.
    @Test
    fun an_old_restore_tried_mark_is_still_decided_by_the_next_open() =
        runBlocking {
            val files = Files()
            assertEquals(ChatClaim.ADOPTED, ownership(files, LinkedHashMap()).claimFor(walletA))
            files.live().entries.putAll(walletAChats)
            // Written by an older build: the user put the copy back, and the next open decides.
            files.marks().entries["restore_tried:$storeName"] = true

            // The next open fails for a whole outage round: the extra try is given, and the copy is offered.
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())

            assertEquals(
                "the older build's restore was not counted: a second extra try was given",
                StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
                outageAfterARestore(files),
            )
        }

    // (e) An old per-store restore_retried mark goes with the wallet that owned the chats when the phrase changes.
    @Test
    fun an_old_restore_retried_mark_stays_with_its_wallet_across_a_phrase_change() =
        runBlocking {
            val files = Files()
            val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
            bundles[walletB] = mapOf(storeName to walletBChats)
            assertEquals(ChatClaim.ADOPTED, ownership(files, bundles).claimFor(walletA))
            files.live().entries.putAll(walletAChats)
            files.marks().entries["restore_retried:$storeName"] = true

            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletB))
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())
            val afterB = outageAfterARestore(files)
            assertTrue("wallet B inherited wallet A's spent extra try: $afterB", afterB is StartupStorageCheck.State.ChatCopyKept)

            // Wallet A again: A's old mark is still A's.
            assertEquals(ChatClaim.RESTORED, ownership(files, bundles).claimFor(walletA))
            outage(files)
            assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())
            assertEquals(
                "wallet A's spent extra try was given again",
                StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
                outageAfterARestore(files),
            )
        }

    private companion object {
        const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        const val VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
        const val OPEN_FAILURES_FILE = "zchat_encrypted_store_open_failures"

        /** openEncryptedStore's tries to open a store inside one launch (the first one and its two retries). */
        const val TRIES_TO_OPEN_IN_ONE_LAUNCH = 3
    }
}
