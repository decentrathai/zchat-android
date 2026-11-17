package co.electriccoin.zcash.ui.screen.walletstartup

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyBackoffApi.DAY_MS
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyBackoffApi.HOUR_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * F10 (fix-all 2.13.0, chat copy #16) — under a Keystore that fails on and off, the kept chat copy is offered again
 * and again, with no bound.
 *
 * At 7d8799d29 a restore that fails while the phone's security system does not answer PAUSES the restore
 * (EncryptedStoreOpener answerFailedRestore), and ANY later launch whose store opens at once lifts the pause, so the
 * copy is offered again at the next start. One cycle is: the offer, Restore, a restart, two "ZCHAT can't open your
 * chats now" launches, the paused screen, Continue, the next clean launch and the offer again — with no counter and
 * no clock, so it never ends; each failed restore also keeps one more copy file. The only way out is "Continue
 * without them", which hides the copy for good (Round 8 D6 accepted this with no cap; the fix-all
 * plan bounds it).
 *
 * Required (chat-copy design A–G with the adversarial review's corrections, PLAN F10):
 *  - the first pause offers the copy again at the next clean launch (as today), the second one not before a day later,
 *    and the next failed restore PARKS the copy: it is never offered by itself again, never called gone and never
 *    answered, and the start-up screen says so once;
 *  - the hold is per COPY, never per store: chats set aside by a new outage during a pause or a park are offered at
 *    once (review break 1), "Continue without them" never releases a parked copy (break 4), and the paused screen's
 *    Continue never swallows the parked explanation (break 3);
 *  - More → Advanced Settings → Restore old chats lets the user try again: the copy is offered once more, a failure
 *    parks it again at once, and a restore that opens ends the episode, so the next outage gets the full budget;
 *  - a phone clock that went back never holds a copy for ever.
 */
class ChatCopyOfferBackoffTest {
    private val storeName = "zchat_e2e_keys_encrypted"
    private val secondStore = "zchat_messages_encrypted"

    private val chatKeysOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** Chats the user writes after the copy was parked (or while a pause lasts). */
    private val newChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-3",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-4",
            "BRy2-encrypted-key" to "BVa8-encrypted-value",
        )

    /** Chats written later still, after the new chats were given up. */
    private val laterChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-5",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-6",
            "CTz3-encrypted-key" to "CWb7-encrypted-value",
        )

    private val secondStoreChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-7",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-8",
            "DMq4-encrypted-key" to "DXc6-encrypted-value",
        )

    private enum class Keystore {
        /** It answers at once. */
        ANSWERS,

        /** It does not answer at all. */
        OUT,

        /** It answers only after ZCHAT's tries to open each store, i.e. only the recreate of an emptied store. */
        LATE,
    }

    private fun copiesOf(files: ChatCopyPhoneFiles) =
        KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

    private fun phone(): ChatCopyPhoneFiles =
        ChatCopyPhoneFiles().apply { (file(storeName) as RecordingPrefs).entries.putAll(chatKeysOnDisk) }

    /**
     * One launch the user sees. Every store in [stores] is opened (a failure of one does not keep the next from being
     * tried; the first failure is what the launch reports), and the start-up check is the app's: the copies it is
     * given are [KeptChatStoreCopies.unhandled], as the other chat-copy tests give it, so the hold must be the check's.
     */
    private fun launch(
        files: ChatCopyPhoneFiles,
        keystore: Keystore,
        stores: List<String> = listOf(storeName),
    ): StartupStorageCheck.State {
        val check =
            StartupStorageCheck(
                openSeedStore = {},
                openChatStores = {
                    var first: Exception? = null
                    for (store in stores) {
                        var opens = 0
                        try {
                            openEncryptedStore(
                                name = store,
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
                        } catch (e: Exception) {
                            if (first == null) first = e
                        }
                    }
                    first?.let { throw it }
                },
                ioDispatcher = Dispatchers.Unconfined,
                keptChatCopies = { copiesOf(files).unhandled() },
                chatCopyRestoreFailed = { copiesOf(files).restoreFailed() },
                chatCopyRestorePaused = { copiesOf(files).restorePaused() },
            )
        runBlocking { check.run() }
        return check.state.value
    }

    /** Two launches that stop, then one that reaches the recreate and gets an answer only for the emptied store. */
    private fun outageRound(
        files: ChatCopyPhoneFiles,
        stores: List<String> = listOf(storeName),
    ): StartupStorageCheck.State {
        repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT, stores) }
        return launch(files, Keystore.LATE, stores)
    }

    private fun repair(
        files: ChatCopyPhoneFiles,
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

    private fun restore(files: ChatCopyPhoneFiles) =
        assertTrue(
            "the restore reported a failure",
            runBlocking {
                repair(files).restoreChatCopy()
            }
        )

    private fun isKept(state: StartupStorageCheck.State) = state is StartupStorageCheck.State.ChatCopyKept

    /**
     * The first failed restore buys the extra try, the second one pauses (offered again at the next clean launch),
     * the third one pauses for a day. Returns the phone just before the fourth restore fails: the copy is put back.
     */
    private fun phoneBeforeTheFourthFailedRestore(): ChatCopyPhoneFiles {
        val files = phone()
        outageRound(files)
        assertTrue("the kept copy was not offered", isKept(launch(files, Keystore.ANSWERS)))
        restore(files)

        // Failure 1: the extra try.
        outageRound(files)
        assertTrue("the extra try was not offered", isKept(launch(files, Keystore.ANSWERS)))
        restore(files)

        // Failure 2: the first pause; offered again at the next clean launch.
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        assertTrue("the first pause did not offer the copy at the next clean launch", isKept(launch(files, Keystore.ANSWERS)))
        restore(files)

        // Failure 3: the second pause; offered again a day later.
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        files.advance(DAY_MS + 1)
        assertTrue("the second pause did not offer the copy a day later", isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        return files
    }

    /** The fourth failed restore: the copy is parked. Returns the state of that launch. */
    private fun parkedPhone(): Pair<ChatCopyPhoneFiles, StartupStorageCheck.State> {
        val files = phoneBeforeTheFourthFailedRestore()
        return files to outageRound(files)
    }

    private fun parkedAndRead(): ChatCopyPhoneFiles {
        val (files, state) = parkedPhone()
        assertTrue("the fourth failed restore did not park the copy: $state", ChatCopyBackoffApi.isParked(state))
        assertTrue("the parked screen could not be answered", ChatCopyBackoffApi.acknowledgeChatCopyPark(repair(files)))
        return files
    }

    private fun reachable(
        files: ChatCopyPhoneFiles,
        entries: Map<String, Any?>
    ): Boolean = files.entries(storeName) == entries || copiesOf(files).unhandled().any { files.entries(it) == entries }

    // 1 — fails at 7d8799d29: each cycle pauses again and the copy is offered again, for ever.
    @Test
    fun an_on_off_keystore_parks_the_copy_after_the_second_pause_instead_of_offering_it_forever() {
        val (files, state) = parkedPhone()

        assertTrue("the fourth failed restore paused the copy again instead of parking it: $state", ChatCopyBackoffApi.isParked(state))
        assertEquals(listOf(storeName), ChatCopyBackoffApi.parkedStores(state))
        assertTrue(ChatCopyBackoffApi.acknowledgeChatCopyPark(repair(files)))

        // The Keystore answers again, the next day, a month later: the copy is never offered by itself.
        for (wait in listOf(0L, HOUR_MS, DAY_MS + 1, 30 * DAY_MS)) {
            files.advance(wait)
            val later = launch(files, Keystore.ANSWERS)
            assertEquals("the parked copy was offered again ${wait}ms later: $later", StartupStorageCheck.State.Ready, later)
        }
        assertTrue(
            "the parked copy is not listed for Settings",
            ChatCopyBackoffApi.held(copiesOf(files)).any {
                files.entries(it) ==
                    chatKeysOnDisk
            }
        )
    }

    // 2 — fails at 7d8799d29: the second pause lifts at the next clean launch like the first.
    @Test
    fun the_second_pause_waits_a_day_before_the_copy_is_offered_again() {
        val files = phone()
        outageRound(files)
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        outageRound(files)
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })

        files.advance(HOUR_MS)
        assertEquals("the second pause offered the copy an hour later", StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))
        files.advance(DAY_MS - HOUR_MS - 100)
        assertEquals(
            "the second pause offered the copy before a day passed",
            StartupStorageCheck.State.Ready,
            launch(files, Keystore.ANSWERS)
        )
        files.advance(200)
        val dayLater = launch(files, Keystore.ANSWERS)
        assertTrue("the copy was not offered once the day passed: $dayLater", isKept(dayLater))
        assertTrue(
            "the offer is not the kept chats",
            (dayLater as StartupStorageCheck.State.ChatCopyKept).copies.any { files.entries(it) == chatKeysOnDisk },
        )
    }

    // 3 — control, green before and after: a parked (at HEAD: paused) copy is never called gone and never answered.
    @Test
    fun a_parked_copy_is_never_called_gone_and_never_answered() {
        val (files, state) = parkedPhone()

        assertFalse("the key was called gone: $state", state is StartupStorageCheck.State.ChatCopyRestoreFailed)
        assertEquals("the key was recorded as gone for good", emptyList<String>(), copiesOf(files).restoreFailed())
        assertTrue(
            "the kept chat keys were marked answered, so no screen would ever reach them",
            copiesOf(files).unhandled().any { files.entries(it) == chatKeysOnDisk },
        )
        files.copies().forEach { assertTrue("copy $it was emptied", files.entries(it).isNotEmpty()) }
        val offer = runBlocking { repair(files).offerFor(startupFailureCause(null, state)) }
        assertFalse("the start-up screen says the key is gone", offer == WalletStartupRepair.Offer.ChatCopyRestoreFailed)
    }

    // 4 — fails at 7d8799d29 (no parked state).
    @Test
    fun the_parked_screen_is_said_once_and_then_zchat_opens() =
        runBlocking {
            val (files, state) = parkedPhone()
            assertTrue("not parked: $state", ChatCopyBackoffApi.isParked(state))
            val offer = repair(files).offerFor(startupFailureCause(null, state))
            assertTrue("the parked state does not get its own screen: $offer", ChatCopyBackoffApi.isParkedOffer(offer))
            val content = walletStartupFailedContent(offer)
            assertEquals(ChatCopyBackoffApi.stringId("wallet_startup_chat_copy_parked_title"), content.title)
            assertEquals(ChatCopyBackoffApi.stringId("wallet_startup_chat_copy_parked_message"), content.message)
            assertEquals(
                "the parked screen must have one way on, which only says it was read",
                listOf(ChatCopyBackoffApi.PARK_READ_ACTION),
                content.buttons.map { it.action.name },
            )
            assertEquals(listOf(ChatCopyBackoffApi.stringId("wallet_startup_chat_copy_parked_button")), content.buttons.map { it.text })

            // The Keystore answers at the next launch, before the user read it: still said, not skipped.
            assertTrue("the parked screen was skipped", ChatCopyBackoffApi.isParked(launch(files, Keystore.ANSWERS)))

            var restarted = false
            assertTrue(ChatCopyBackoffApi.acknowledgeChatCopyPark(repair(files) { restarted = true }))
            assertTrue("ZCHAT did not restart after the parked screen was read", restarted)
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))
            assertEquals(StartupStorageCheck.State.Ready, outageRound(files))
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))
        }

    // 5 — fails at 7d8799d29 (no retryHeld).
    @Test
    fun try_again_from_settings_offers_the_copy_once_and_a_failure_parks_again_at_once() {
        val files = parkedAndRead()
        val copyCountAtPark = files.copies().size

        assertTrue("Settings could not let the parked copy be offered again", ChatCopyBackoffApi.retryHeld(copiesOf(files)))
        assertEquals("nothing is held any more", emptyList<String>(), ChatCopyBackoffApi.held(copiesOf(files)))
        assertTrue("the copy was not offered after Restore old chats", isKept(launch(files, Keystore.ANSWERS)))
        restore(files)

        // The Keystore still fails: parked again at once — no extra try, no new pauses.
        val again = outageRound(files)
        assertTrue("a failed try from Settings started new automatic offers: $again", ChatCopyBackoffApi.isParked(again))
        assertTrue(ChatCopyBackoffApi.acknowledgeChatCopyPark(repair(files)))
        files.advance(2 * DAY_MS)
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))
        assertEquals("a failed try kept more than one new copy", copyCountAtPark + 1, files.copies().size)

        // Once more from Settings, and this time the Keystore answers: the chats are back and the episode is over.
        assertTrue(ChatCopyBackoffApi.retryHeld(copiesOf(files)))
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))
        assertEquals("the old chats did not come back", chatKeysOnDisk, files.entries(storeName))
        assertEquals(emptyList<String>(), ChatCopyBackoffApi.held(copiesOf(files)))
        assertEquals(emptyList<String>(), copiesOf(files).unhandled())
        val leftover = files.entries(OPEN_FAILURES_FILE).keys.filter { it.startsWith("restore_") || it.startsWith("used_after_pause") }
        assertEquals("marks of the finished episode were left behind", emptyList<String>(), leftover)
    }

    // 6 — fails at 7d8799d29 (no retryHeld); after the fix it proves no count leaks past a success.
    @Test
    fun a_success_ends_the_episode_so_the_next_outage_gets_the_full_budget() {
        val files = parkedAndRead()
        assertTrue(ChatCopyBackoffApi.retryHeld(copiesOf(files)))
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))

        // Months later a new outage sets the chats aside again.
        files.advance(60 * DAY_MS)
        outageRound(files)
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        outageRound(files)
        assertTrue("the next outage did not get its extra try", isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        val third = outageRound(files)
        assertEquals(
            "the next outage was parked without its pauses",
            StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
            third
        )
    }

    // 7 — control, green before and after (at HEAD every pause lifts at the next clean launch).
    @Test
    fun the_clock_going_back_never_holds_the_copy_forever() {
        val files = phone()
        outageRound(files)
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        outageRound(files)
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        outageRound(files)
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        assertTrue(isKept(launch(files, Keystore.ANSWERS)))
        restore(files)
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })

        // The phone's clock is set back a year (a dead battery, a wrong manual date).
        files.now -= 365 * DAY_MS
        val back = launch(files, Keystore.ANSWERS)
        assertTrue("a clock set back held the copy (it would wait a year): $back", isKept(back))
    }

    // 9 — fails at 7d8799d29 (no park). Review break 4: Continue without them never releases a parked copy.
    @Test
    fun continue_without_the_new_chats_never_releases_the_parked_copy_nor_hides_later_chats() {
        val files = parkedAndRead()
        (files.file(storeName) as RecordingPrefs).entries.putAll(newChats)

        outageRound(files)
        val offered = launch(files, Keystore.ANSWERS)
        assertTrue("the new chats set aside during the park were not offered: $offered", isKept(offered))
        assertTrue(runBlocking { repair(files).continueWithoutChatCopy() })

        assertTrue(
            "Continue without them answered or released the parked copy",
            ChatCopyBackoffApi.held(copiesOf(files)).any { files.entries(it) == chatKeysOnDisk },
        )
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS))

        // The user chats on, then tries the parked copy from Settings.
        (files.file(storeName) as RecordingPrefs).entries.putAll(laterChats)
        assertTrue(ChatCopyBackoffApi.retryHeld(copiesOf(files)))
        val back = launch(files, Keystore.ANSWERS)
        assertTrue("the parked copy was not offered from Settings: $back", isKept(back))
        restore(files)

        assertEquals("the parked chats did not come back", chatKeysOnDisk, files.entries(storeName))
        assertTrue("the chats written after the park were hidden by the restore", reachable(files, laterChats))
    }

    // 10 — fails at 7d8799d29 (no park). Review break 1: the hold is per copy, not per store.
    @Test
    fun a_new_outage_during_a_park_offers_the_new_chats_at_once() {
        val files = parkedAndRead()
        (files.file(storeName) as RecordingPrefs).entries.putAll(newChats)

        outageRound(files)
        val next = launch(files, Keystore.ANSWERS)

        assertTrue("the chats set aside during the park are held back with the parked copy: $next", isKept(next))
        val copies = (next as StartupStorageCheck.State.ChatCopyKept).copies
        assertTrue("the offer is not the new chats: $copies", copies.any { files.entries(it) == newChats })
        assertFalse("the parked copy was offered by itself: $copies", copies.any { files.entries(it) == chatKeysOnDisk })
    }

    // 11 — fails at 7d8799d29 (no park). Review break 3: the paused screen's Continue is not the parked screen's.
    @Test
    fun the_paused_screen_s_continue_does_not_swallow_the_park_explanation() {
        val both = listOf(storeName, secondStore)
        val files = phone()

        // Round 1: only the first store holds chats.
        outageRound(files, both)
        assertTrue(isKept(launch(files, Keystore.ANSWERS, both)))
        restore(files)
        outageRound(files, both) // first store: the extra try
        (files.file(secondStore) as RecordingPrefs).entries.putAll(secondStoreChats)
        assertTrue(isKept(launch(files, Keystore.ANSWERS, both)))
        restore(files)
        // First store: the first pause. The second store's chats are set aside by the same outage (its first copy).
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files, both))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        assertTrue(isKept(launch(files, Keystore.ANSWERS, both)))
        restore(files)
        // First store: the second pause; second store: its extra try.
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), outageRound(files, both))
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })
        files.advance(DAY_MS + 1)
        assertTrue(isKept(launch(files, Keystore.ANSWERS, both)))
        restore(files)

        // First store: parked; second store: its first pause. Both are to be said.
        val paused = outageRound(files, both)
        assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(secondStore)), paused)
        assertTrue(runBlocking { repair(files).acknowledgeChatCopyPause() })

        // The next launch the Keystore answers (a launch it does not answer only stops with "can't open your chats now").
        val next = launch(files, Keystore.ANSWERS, both)
        assertTrue("the paused screen's Continue swallowed the parked explanation: $next", ChatCopyBackoffApi.isParked(next))
        assertEquals(listOf(storeName), ChatCopyBackoffApi.parkedStores(next))
    }

    // 12 — fails at 7d8799d29: the start-up screen is given every unanswered copy and the hold is per store.
    @Test
    fun the_app_offers_only_the_copies_that_are_not_held() {
        val files = parkedAndRead()
        val copies = copiesOf(files)
        assertEquals("a parked copy is offered", emptyList<String>(), copies.offered())
        assertTrue("the parked copy is not held", ChatCopyBackoffApi.held(copies).isNotEmpty())
        assertTrue(
            "the parked copy is not among the unanswered copies",
            copies.unhandled().containsAll(ChatCopyBackoffApi.held(copies)),
        )
        val module = ChatCopyBackoffApi.source("di/UseCaseModule.kt")
        assertTrue(
            "the start-up check is given every unanswered copy, not the ones that may be offered",
            module.contains("keptChatCopies = { koin.get<KeptChatStoreCopies>().offered() }"),
        )
    }

    // 13 — fails at 7d8799d29 (no RestoreOldChats). The Advanced Settings row: shown only while a copy is held; its
    // confirmed action lets the copy be offered again BEFORE ZCHAT restarts, and restarts only when that worked.
    @Test
    fun the_settings_row_is_shown_only_while_a_copy_is_held_and_restarts_after_the_retry() {
        val nothingHeld = phone()
        var restartedWithNothing = false
        val idle = ChatCopyBackoffApi.restoreOldChats(copiesOf(nothingHeld), { restartedWithNothing = true })
        assertFalse("the row is shown with nothing held", ChatCopyBackoffApi.isAvailable(idle))
        assertFalse("the row's action reported success with nothing held", ChatCopyBackoffApi.retryAndRestart(idle))
        assertFalse("ZCHAT restarted although nothing was changed", restartedWithNothing)

        val files = parkedAndRead()
        var heldAtRestart: List<String>? = null
        val row = ChatCopyBackoffApi.restoreOldChats(copiesOf(files), { heldAtRestart = ChatCopyBackoffApi.held(copiesOf(files)) })
        assertTrue("the row is not shown while a copy is parked", ChatCopyBackoffApi.isAvailable(row))
        assertTrue(ChatCopyBackoffApi.retryAndRestart(row))
        assertEquals("ZCHAT did not restart, or restarted before the copy was let go", emptyList<String>(), heldAtRestart)
        assertFalse("the row is still shown after the retry", ChatCopyBackoffApi.isAvailable(row))
    }

    private companion object {
        /** openEncryptedStore's tries to open a store inside one launch (the first one and its two retries). */
        const val TRIES_TO_OPEN_IN_ONE_LAUNCH = 3

        const val OPEN_FAILURES_FILE = "zchat_encrypted_store_open_failures"
    }
}
