package co.electriccoin.zcash.ui.screen.walletstartup

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/** The shared_prefs folder of one phone, with a clock that moves on so each kept copy gets its own name (F0: shared). */
private typealias Files = ChatCopyPhoneFiles

/**
 * R5-3, round-4 report section 5C — "a long Keystore outage is now called permanent".
 *
 * R4-5 stopped the restore loop: once the user has put a kept copy back and the store still cannot be read,
 * ZCHAT says the key is gone and never offers that restore again. The verdict is reached from two very
 * different failures, though. One is the phone saying the keyset does not decrypt (a changed screen lock, the
 * files on another phone) — nothing will ever open it. The other is the phone's security system not answering
 * at all, which is temporary by nature and only counts as "unreadable" after
 * MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR launches in a row. An outage that spans the restore plus those
 * launches therefore ends the user's old chats although no key was ever lost: the files stay on the phone, but
 * only a developer could get them back.
 *
 * Required: before that verdict, a failure that never said the key was lost is given one more restore — exactly
 * one, so the loop R4-5 closed stays closed — and the verdict is only reached when that extra round fails too.
 * A keyset that really does not decrypt is still answered at once. No file is deleted on any of these paths.
 */
class ChatStoreRestoreOutageRetryTest {
    private val storeName = "zchat_e2e_keys_encrypted"
    private val chatKeysOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** The keys of the chats the user starts after giving the old ones up — a second set worth just as much. */
    private val newChatKeys: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-3",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-4",
            "BRy2-encrypted-key" to "BVa8-encrypted-value",
        )

    /** What the phone's security system does when ZCHAT asks it for the store's key. */
    private enum class Keystore {
        /** It answers, so the store opens (and an emptied store gets a fresh keyset). */
        ANSWERS,

        /** It does not answer at all — right after an update or a reboot. Nothing is said about any key. */
        OUT,

        /** It answers, and the stored keyset does not decrypt: that key is gone for good. */
        KEY_GONE,

        /**
         * B5: it does not answer the tries ZCHAT makes to open the store, and answers only the last call of the
         * launch — the one that starts an emptied store. The outage is not over: the next launch meets it again.
         */
        LATE,
    }

    private fun copiesOf(files: Files) = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

    private fun phone(): Files = Files().apply { (file(storeName) as RecordingPrefs).entries.putAll(chatKeysOnDisk) }

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
                                // A store with no keyset yet needs the Keystore just as much as one with a keyset.
                                Keystore.OUT -> throw KeyStoreException("the phone's security system did not answer")
                                Keystore.KEY_GONE ->
                                    if (files.entries(storeName).isNotEmpty()) {
                                        throw javax.crypto.AEADBadTagException("the stored keyset does not decrypt")
                                    } else {
                                        RecordingPrefs()
                                    }
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

    /** The launches in a row an outage has to stop before ZCHAT treats the store as unreadable. */
    private fun outage(files: Files) = repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR) { launch(files, Keystore.OUT) }

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

    /** A phone whose chats were set aside by an outage, put back by the user, and set aside by the outage again. */
    private fun phoneAfterOneFailedRestoreDuringAnOutage(): Files {
        val files = phone()
        outage(files)
        val offered = launch(files, Keystore.ANSWERS)
        assertTrue("the kept copy was not offered: ${offered.state.value}", offered.state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("the restore reported a failure", runBlocking { repair(files).restoreChatCopy() })
        assertEquals("the copy was not put back", chatKeysOnDisk, files.entries(storeName))
        outage(files)
        return files
    }

    @Test
    fun a_keystore_that_never_answered_does_not_end_the_old_chats_on_the_first_restore() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()

            // The outage ends. Nothing ever said a key was lost, so the user must get the copy offered again.
            val next = launch(files, Keystore.ANSWERS)

            assertEquals(
                "the key was called gone although the Keystore only went quiet",
                emptyList<String>(),
                copiesOf(files).restoreFailed(),
            )
            assertTrue(
                "the old chats were ended by an outage, with no way back for the user: ${next.state.value}",
                next.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
        }

    /**
     * B5, Grok Q10 / GPT Q10 / C-2.3: the second round of an outage is not a verdict either. The loop R4-5 closed
     * stays closed — while the phone's security system still does not answer, the same restore is not offered a
     * third time — but nothing about a lost key is said, and the copy is not marked answered: it waits.
     *
     * C6, R6-4 review finding 16: the loop is closed only WHILE THE OUTAGE LASTS. Once the phone's security system
     * answers at the first tries of a launch, the pause lifts and the copy is offered once more (see
     * each_pause_gives_one_offer_and_a_third_outage_round_pauses_again). The second launch below is a whole outage
     * round again (two launches stopped, then one that reaches the recreate and gets an answer only for the emptied
     * store), so its state is decided after the recreate path, not by a launch that simply stopped.
     *
     * F10 (fix-all 2.13.0, chat copy #16; Round 8 D6 had accepted it with no cap; the fix-all plan bounds it): under a Keystore
     * that fails on and off the offer no longer comes back once per cycle for ever. The first pause offers the copy
     * again at the next clean launch, the second one not before a day later, and the next failed restore PARKS the
     * copy: never offered by itself again, never called gone, never answered, reachable from More → Advanced Settings →
     * Restore old chats. That bound is pinned by under_an_on_off_keystore_the_offer_comes_back_twice_then_the_copy_is_parked
     * and by ChatCopyOfferBackoffTest.
     */
    @Test
    fun the_extra_try_is_the_only_one_so_the_restore_loop_stays_closed() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)

            // The one extra try: the user puts the copy back a second time and the outage returns. Its last launch
            // gets an answer only for the emptied store, so ZCHAT opens while the outage is still going on.
            assertTrue("the one extra try was never offered", repair(files).restoreChatCopy())
            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            val during = launch(files, Keystore.LATE)
            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            val stillDuring = launch(files, Keystore.LATE)
            assertFalse(
                "the second launch stopped before the recreate path, so nothing below is checked: ${stillDuring.state.value}",
                stillDuring.state.value is StartupStorageCheck.State.ChatStoresUnavailable,
            )

            for (state in listOf(during.state.value, stillDuring.state.value)) {
                assertFalse(
                    "the same restore is offered a third time while the outage lasts, so the user is in a loop again: $state",
                    state is StartupStorageCheck.State.ChatCopyKept,
                )
                assertFalse(
                    "the key was called gone for good although the phone's security system only went quiet: $state",
                    state is StartupStorageCheck.State.ChatCopyRestoreFailed,
                )
                val offer = repair(files).offerFor(startupFailureCause(null, state))
                assertNotEquals(WalletStartupRepair.Offer.RestoreChatCopy, offer)
                assertNotEquals(WalletStartupRepair.Offer.ChatCopyRestoreFailed, offer)
            }
            assertEquals("the key was recorded as gone for good", emptyList<String>(), copiesOf(files).restoreFailed())
            assertTrue(
                "the kept chat keys were marked answered, so ZCHAT would never offer them again",
                copiesOf(files).unhandled().any { files.entries(it) == chatKeysOnDisk },
            )
        }

    /**
     * B5, Grok Q10 / GPT Q10 / C-2.3 (KEYSTORE-TEMP-TERMINAL-VERDICT): two restore rounds that failed only because
     * the phone's security system did not answer never end in "the key is gone for good". The copy stays unanswered,
     * and once the Keystore answers again it is offered one more time — and it restores.
     */
    @Test
    fun two_outage_rounds_keep_the_copy_and_offer_it_again_once_the_keystore_answers() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            assertTrue("the one extra try was never offered", repair(files).restoreChatCopy())
            outage(files)

            assertEquals(
                "two temporary outages were recorded as a key that is gone for good",
                emptyList<String>(),
                copiesOf(files).restoreFailed(),
            )
            assertTrue(
                "the kept chat keys were marked answered, so ZCHAT would never offer them again",
                copiesOf(files).unhandled().any { files.entries(it) == chatKeysOnDisk },
            )

            // The outage ends: the phone's security system answers ZCHAT's first try.
            val back = launch(files, Keystore.ANSWERS)
            assertTrue(
                "the kept chats were not offered again after the phone's security system answered: ${back.state.value}",
                back.state.value is StartupStorageCheck.State.ChatCopyKept,
            )

            assertTrue("the restore reported a failure", repair(files).restoreChatCopy())
            val restored = launch(files, Keystore.ANSWERS)
            assertEquals(StartupStorageCheck.State.Ready, restored.state.value)
            assertEquals("the old chat keys did not come back", chatKeysOnDisk, files.entries(storeName))
            assertEquals(emptyList<String>(), copiesOf(files).restoreFailed())
        }

    /**
     * B5: the pause is bounded — each time the Keystore comes back the copy is offered ONCE; put back into another
     * outage, the next round pauses again instead of offering the same restore while the outage lasts.
     */
    @Test
    fun each_pause_gives_one_offer_and_a_third_outage_round_pauses_again() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            assertTrue(repair(files).restoreChatCopy())
            outage(files)
            val back = launch(files, Keystore.ANSWERS)
            assertTrue(
                "not offered after the Keystore answered: ${back.state.value}",
                back.state.value is StartupStorageCheck.State.ChatCopyKept,
            )

            // A third round: put back, and the outage returns once more.
            assertTrue(repair(files).restoreChatCopy())
            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            val during = launch(files, Keystore.LATE)

            assertFalse(
                "the restore is offered again while the outage lasts: ${during.state.value}",
                during.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
            assertEquals("a third outage round called the key gone", emptyList<String>(), copiesOf(files).restoreFailed())
            // F10: the second pause offers the copy again only a day later.
            files.advance(DAY_MS + 1)
            val answered = launch(files, Keystore.ANSWERS)
            assertTrue(
                "the copy was not offered once more after the Keystore answered: ${answered.state.value}",
                answered.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
        }

    /**
     * B5: the pause is said once, in its own words, and its one button lets ZCHAT open without the offer while the
     * outage lasts; the copy comes back as soon as the phone's security system answers at the first tries.
     */
    @Test
    fun the_pause_is_explained_once_then_zchat_opens_without_the_offer_until_the_keystore_answers() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            assertTrue(repair(files).restoreChatCopy())
            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            val paused = launch(files, Keystore.LATE).state.value

            assertEquals(StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)), paused)
            assertEquals(WalletStartupRepair.Offer.ChatCopyRestorePaused, repair(files).offerFor(startupFailureCause(null, paused)))

            var restarted = false
            assertTrue("the explanation could not be answered", repair(files) { restarted = true }.acknowledgeChatCopyPause())
            assertTrue("ZCHAT did not restart after the explanation was read", restarted)

            // Still in the outage: ZCHAT opens, says nothing more and offers nothing.
            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.LATE).state.value)
            assertTrue("the kept chat keys were given up", copiesOf(files).unhandled().any { files.entries(it) == chatKeysOnDisk })

            // The outage ends.
            val back = launch(files, Keystore.ANSWERS)
            assertTrue(
                "not offered once the Keystore answered: ${back.state.value}",
                back.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
            assertTrue(repair(files).restoreChatCopy())
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS).state.value)
            assertEquals("the old chat keys did not come back", chatKeysOnDisk, files.entries(storeName))
        }

    /**
     * F10 (fix-all 2.13.0, chat copy #16). Round 8 D6 accepted, with no cap, that under a Keystore that fails on and off
     * each cycle (the copy put back, two launches that stop, one that reaches the recreate and pauses the restore, then
     * a launch the Keystore answers) offers the same copy again, for as long as the cycles go on. The fix-all plan
     * bounds it (F10). Now the offer comes back twice — at the next clean launch after the first pause, and a day after
     * the second — and the next failed restore parks the copy: it is not offered by itself any more, it is never called
     * gone, and every copy is still on the phone.
     */
    @Test
    fun under_an_on_off_keystore_the_offer_comes_back_twice_then_the_copy_is_parked() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            assertTrue("the one extra try was never offered", repair(files).restoreChatCopy())

            repeat(PAUSES_BEFORE_THE_PARK) { cycle ->
                repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
                assertEquals(
                    "cycle $cycle: the failed restore did not pause",
                    StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
                    launch(files, Keystore.LATE).state.value,
                )
                assertTrue(repair(files).acknowledgeChatCopyPause())
                if (cycle > 0) files.advance(DAY_MS + 1)
                val back = launch(files, Keystore.ANSWERS).state.value
                assertTrue(
                    "cycle $cycle: the copy was not offered again once the Keystore answered: $back",
                    back is StartupStorageCheck.State.ChatCopyKept,
                )
                assertEquals("cycle $cycle: the key was called gone", emptyList<String>(), copiesOf(files).restoreFailed())
                assertTrue("cycle $cycle: the restore reported a failure", repair(files).restoreChatCopy())
            }

            repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
            val parked = launch(files, Keystore.LATE).state.value
            assertTrue("the third failed round paused again instead of parking the copy: $parked", ChatCopyBackoffApi.isParked(parked))
            assertTrue(ChatCopyBackoffApi.acknowledgeChatCopyPark(repair(files)))
            files.advance(30 * DAY_MS)
            assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS).state.value)
            assertEquals("the key was called gone", emptyList<String>(), copiesOf(files).restoreFailed())
            assertTrue("the kept chat keys were deleted", files.copies().any { files.entries(it) == chatKeysOnDisk })
            assertTrue("the kept chat keys were answered", copiesOf(files).unhandled().any { files.entries(it) == chatKeysOnDisk })
        }

    @Test
    fun the_chats_come_back_when_the_keystore_answers_during_the_extra_try() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            assertTrue("the one extra try was never offered", repair(files).restoreChatCopy())

            // This time the phone's security system answers: the very store the user put back opens.
            val back = launch(files, Keystore.ANSWERS)

            assertEquals(StartupStorageCheck.State.Ready, back.state.value)
            assertEquals("the restored chat keys were touched", chatKeysOnDisk, files.entries(storeName))
            assertEquals(emptyList<String>(), copiesOf(files).restoreFailed())
        }

    @Test
    fun every_copy_stays_on_the_phone_through_the_extra_try() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            repair(files).restoreChatCopy()
            outage(files)
            launch(files, Keystore.ANSWERS)
            repair(files).acknowledgeChatCopyFailure()

            val copies = files.copies()
            assertTrue("the kept chat keys were deleted", copies.any { files.entries(it) == chatKeysOnDisk })
            copies.forEach { assertTrue("copy $it was emptied", files.entries(it).isNotEmpty()) }
        }

    @Test
    fun `new chats get their own extra try after the user gave the old ones up`() =
        runBlocking {
            val files = phoneAfterOneFailedRestoreDuringAnOutage()
            launch(files, Keystore.ANSWERS)
            assertTrue("the one extra try was never offered", repair(files).continueWithoutChatCopy())

            // ZCHAT starts with empty chat stores; the user chats again, and those keys are what is at stake now.
            (files.file(storeName) as RecordingPrefs).entries.putAll(newChatKeys)

            // Months later another outage sets the new chats aside; the user puts them back and it is still there.
            outage(files)
            val offered = launch(files, Keystore.ANSWERS)
            assertTrue(
                "the new chats were not offered back: ${offered.state.value}",
                offered.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
            assertTrue("the restore reported a failure", repair(files).restoreChatCopy())
            outage(files)
            val next = launch(files, Keystore.ANSWERS)

            assertEquals(
                "the new chats were ended without the extra try the old ones were given",
                emptyList<String>(),
                copiesOf(files).restoreFailed(),
            )
            assertTrue(
                "an outage that ended one set of chats takes the extra try from every later set too: ${next.state.value}",
                next.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
        }

    // Pin: holds before and after the fix — a keyset that really does not decrypt is still answered at once.
    @Test
    fun a_keyset_that_does_not_decrypt_is_still_answered_on_the_first_restore() =
        runBlocking {
            val files = phone()
            val first = launch(files, Keystore.KEY_GONE)
            assertTrue(first.state.value is StartupStorageCheck.State.ChatCopyKept)
            assertTrue(repair(files).restoreChatCopy())

            val second = launch(files, Keystore.KEY_GONE)

            assertTrue(
                "a key that is really gone must not buy another round: ${second.state.value}",
                second.state.value is StartupStorageCheck.State.ChatCopyRestoreFailed,
            )
            assertEquals("nothing must be offered for restoring any more", emptyList<String>(), copiesOf(files).unhandled())
        }

    // Pin: holds before and after the fix — an outage on its own still keeps the chats and offers them back.
    @Test
    fun an_outage_before_any_restore_still_keeps_the_chats_and_offers_them_back() =
        runBlocking {
            val files = phone()
            outage(files)

            val offered = launch(files, Keystore.ANSWERS)

            assertTrue(
                "the kept copy was not offered: ${offered.state.value}",
                offered.state.value is StartupStorageCheck.State.ChatCopyKept,
            )
            assertEquals(emptyList<String>(), copiesOf(files).restoreFailed())
            assertTrue("the chat keys were not kept", files.copies().any { files.entries(it) == chatKeysOnDisk })
        }

    private companion object {
        /** openEncryptedStore's tries to open a store inside one launch (the first one and its two retries). */
        const val TRIES_TO_OPEN_IN_ONE_LAUNCH = 3

        /** F10: the pauses before a copy is parked (the first one offers at the next clean launch, the second a day later). */
        const val PAUSES_BEFORE_THE_PARK = 2

        const val DAY_MS = ChatCopyBackoffApi.DAY_MS
    }
}
