package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyStoreException

/**
 * C6, R6-4 review finding 14 (P1/P2, data hidden): a restore after the pause must not hide the chats of the pause.
 *
 * B5 lets the user tap Continue on the paused screen and chat on in the recreated store. When the pause lifts, the
 * old copy is offered again, and Restore put it back while the chats written during the pause were kept only as an
 * ANSWERED copy — never offered again. An outage during the pause also keeps a copy of the pause-time store; the
 * restore picked one copy per store and marked all of them answered, so the other one was hidden the same way.
 * Rows that say a paid message "may have been sent" can be among them, and re-sending such a row by hand pays twice.
 *
 * Required: after the user chatted during the pause, Restore keeps what it replaces as an UNANSWERED copy that is
 * offered back, and a restore marks answered only the copy it put back (or a copy identical to it, or one holding
 * no chats at all). The re-offer after the pause says that the current chats are set aside (finding 15).
 */
class ChatCopyRestoreAfterPauseKeepsNewChatsTest {
    private val storeName = "zchat_e2e_keys_encrypted"

    private val oldChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** What the user chats in the recreated store while the restore is paused — a paid message among it. */
    private val pauseTimeChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-3",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-4",
            "PAID-encrypted-key" to "may-have-been-sent-encrypted-value",
        )

    /** More chats of the pause, written after an outage during the pause set [pauseTimeChats] aside. */
    private val laterPauseTimeChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-5",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-6",
            "LATER-encrypted-key" to "later-encrypted-value",
        )

    private enum class Keystore { ANSWERS, OUT, LATE }

    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()
        var now = 100L

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun live(): RecordingPrefs = file(STORE) as RecordingPrefs

        fun listing(): List<String> = byName.filterValues { it.entries.isNotEmpty() }.keys.map { "$it.xml" }

        fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }

        fun tick(): Long = ++now
    }

    private fun copiesOf(files: Files) = KeptChatStoreCopies(plainPrefs = files::file, fileNames = files::listing, nowMillis = files::tick)

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

    /** Two outage rounds pause the restore of the old chats; the user taps Continue on the paused screen. */
    private fun pausedAndContinued(): Files {
        val files = Files()
        files.live().entries.putAll(oldChats)
        outage(files)
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(runBlocking { repair(files).restoreChatCopy() })
        outage(files)
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(runBlocking { repair(files).restoreChatCopy() })
        repeat(MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR - 1) { launch(files, Keystore.OUT) }
        assertEquals(
            StartupStorageCheck.State.ChatCopyRestorePaused(listOf(storeName)),
            launch(files, Keystore.LATE).state.value,
        )
        assertTrue("Continue on the paused screen failed", runBlocking { repair(files).acknowledgeChatCopyPause() })
        return files
    }

    /** Whether [entries] can still be reached by the user: live in the store, or in a copy that is still offered. */
    private fun reachable(
        files: Files,
        entries: Map<String, Any?>
    ): Boolean = files.entries(storeName) == entries || copiesOf(files).unhandled().any { files.entries(it) == entries }

    @Test
    fun restoring_the_old_chats_after_the_pause_keeps_the_pause_time_chats_offered() {
        val files = pausedAndContinued()
        // ZCHAT opens during the pause and the user chats on.
        files.live().entries.putAll(pauseTimeChats)

        val back = launch(files, Keystore.ANSWERS).state.value
        assertTrue("the old chats were not offered again after the pause: $back", back is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("the restore reported a failure", runBlocking { repair(files).restoreChatCopy() })

        assertEquals("the old chats did not come back", oldChats, files.entries(storeName))
        assertTrue(
            "the chats written during the pause were kept only as an answered copy, never offered again",
            copiesOf(files).unhandled().any { files.entries(it) == pauseTimeChats },
        )
        val next = launch(files, Keystore.ANSWERS).state.value
        assertTrue("the pause-time chats were not offered back: $next", next is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(
            "the offer is not the pause-time chats",
            (next as StartupStorageCheck.State.ChatCopyKept).copies.any { files.entries(it) == pauseTimeChats },
        )
    }

    @Test
    fun an_outage_during_the_pause_leaves_both_copies_reachable_after_the_restore() {
        val files = pausedAndContinued()
        files.live().entries.putAll(pauseTimeChats)

        // An outage during the pause sets the pause-time chats aside too (a fourth copy). The next launch gets an
        // answer only for the emptied store, so ZCHAT opens while the pause lasts, and the user chats on.
        outage(files)
        assertTrue("the pause-time chats were not kept", files.copies().any { files.entries(it) == pauseTimeChats })
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.LATE).state.value)
        files.live().entries.putAll(laterPauseTimeChats)

        val back = launch(files, Keystore.ANSWERS).state.value
        assertTrue("nothing was offered after the pause: $back", back is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(runBlocking { repair(files).restoreChatCopy() })

        assertTrue("the old chats were hidden by the restore", reachable(files, oldChats))
        assertTrue("the chats of the pause set aside by the outage were hidden by the restore", reachable(files, pauseTimeChats))
        assertTrue("the chats written after that outage were hidden by the restore", reachable(files, laterPauseTimeChats))
    }

    @Test
    fun the_re_offer_after_the_pause_warns_that_the_current_chats_are_set_aside() =
        runBlocking {
            val files = pausedAndContinued()
            files.live().entries.putAll(pauseTimeChats)

            val back = launch(files, Keystore.ANSWERS).state.value
            val offer = repair(files).offerFor(startupFailureCause(null, back))
            val content = walletStartupFailedContent(offer)

            assertNotEquals(
                "the re-offer after the pause says ZCHAT started the chats empty, and does not warn that restoring sets " +
                    "the current chats aside",
                R.string.wallet_startup_chat_copy_message,
                content.message,
            )
            assertEquals(stringId("wallet_startup_chat_copy_reoffer_after_use_message"), content.message)
            assertEquals(
                "the re-offer must keep both ways on",
                listOf(WalletStartupAction.RESTORE_CHAT_COPY, WalletStartupAction.CONTINUE_WITHOUT_CHAT_COPY),
                content.buttons.map { it.action },
            )
        }

    // Control: holds before and after the fix — the first offer, with no pause, keeps its own words.
    @Test
    fun the_first_offer_without_a_pause_keeps_its_message() =
        runBlocking {
            val files = Files()
            files.live().entries.putAll(oldChats)
            outage(files)

            val offered = launch(files, Keystore.ANSWERS).state.value
            val content = walletStartupFailedContent(repair(files).offerFor(startupFailureCause(null, offered)))

            assertEquals(R.string.wallet_startup_chat_copy_message, content.message)
        }

    // Pin: holds before and after the fix — a copy that holds only a keyset (a store recreated with no chats in it
    // yet) is answered with the restore, so it is not offered on its own afterwards.
    @Test
    fun a_copy_holding_only_a_keyset_is_answered_with_the_restore() {
        val files = Files()
        files.live().entries.putAll(oldChats)
        outage(files)
        val keysetOnly = "$storeName${UNREADABLE_STORE_COPY_MARKER}${files.tick()}"
        (files.file(keysetOnly) as RecordingPrefs).entries.putAll(
            mapOf(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-7",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-8",
            )
        )
        assertTrue(launch(files, Keystore.ANSWERS).state.value is StartupStorageCheck.State.ChatCopyKept)

        assertTrue(runBlocking { repair(files).restoreChatCopy() })

        assertEquals(oldChats, files.entries(storeName))
        assertEquals(StartupStorageCheck.State.Ready, launch(files, Keystore.ANSWERS).state.value)
        assertTrue("the keyset-only copy was deleted", files.entries(keysetOnly).isNotEmpty())
    }

    private fun stringId(name: String): Int =
        try {
            R.string::class.java.getField(name).getInt(null)
        } catch (e: NoSuchFieldException) {
            fail("there is no string $name: ${e.message}")
            error("unreachable")
        }

    private companion object {
        const val STORE = "zchat_e2e_keys_encrypted"

        /** openEncryptedStore's tries to open a store inside one launch (the first one and its two retries). */
        const val TRIES_TO_OPEN_IN_ONE_LAUNCH = 3
    }
}
