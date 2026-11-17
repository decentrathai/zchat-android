package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_KEY
import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyLaunches.Keystore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-709 (store-opener design, revision 2a, rule S9, sections 3.5, 4.2 and 5.3, class C; ZC-707's side finding): a
 * kept copy the user declined with "Continue without them" can be asked for again under More → Advanced Settings →
 * Restore old chats, while its wallet is the one in ZChat. Today the row shows only held copies (paused or parked),
 * so a declined copy is never offered again although it stays on the phone. A copy called gone for good, a declined
 * copy that holds no chats and a copy a restore answered are not offered.
 *
 * Both offer messages end with a sentence that says so and names the wallet. All names are synthetic.
 */
class DismissedChatCopyIsOfferedAgainFromSettingsTest {
    private val wallet = "a".repeat(64)
    private val otherWallet = "b".repeat(64)

    /** A phone whose chats were set aside by an outage and declined with "Continue without them". */
    private fun declined(): ChatCopyLaunches {
        val phone = ChatCopyLaunches(ChatCopyPhoneFiles())
        (phone.files.file(CHAT_OWNER_PREFS_NAME) as RecordingPrefs).entries[CHAT_OWNER_KEY] = wallet
        (phone.files.file(phone.store) as RecordingPrefs).entries.putAll(ChatCopyLaunches.chats("old"))
        phone.outage()
        assertTrue("fixture: the copy is not offered", phone.launch(Keystore.ANSWERS) is StartupStorageCheck.State.ChatCopyKept)
        assertTrue("fixture: Continue without them failed", runBlocking { phone.repair().continueWithoutChatCopy() })
        return phone
    }

    private fun row(
        copies: KeptChatStoreCopies,
        restarts: MutableList<Unit> = mutableListOf(),
    ): Any = ChatCopyBackoffApi.restoreOldChats(copies, { restarts += Unit })

    private fun setOwner(
        phone: ChatCopyLaunches,
        owner: String,
    ) {
        (phone.files.file(CHAT_OWNER_PREFS_NAME) as RecordingPrefs).entries[CHAT_OWNER_KEY] = owner
    }

    // (1) FAILS today: false.
    @Test
    fun `1 - after Continue without them the Settings row is shown`() {
        val phone = declined()
        assertTrue("a declined copy is not offered from Settings", ChatCopyBackoffApi.isAvailable(row(phone.copies())))
    }

    // (2) FAILS today: Ready.
    @Test
    fun `2 - the row restarts ZCHAT and the next start offers that copy`() {
        val phone = declined()
        val copy = phone.copyFiles().single()
        val restarts = mutableListOf<Unit>()

        assertTrue("the Settings row offered nothing again", ChatCopyBackoffApi.retryAndRestart(row(phone.copies(), restarts)))

        assertEquals("ZCHAT was not restarted", 1, restarts.size)
        val state = phone.launch(Keystore.ANSWERS)
        assertTrue("the next start does not offer the copy: $state", state is StartupStorageCheck.State.ChatCopyKept)
        assertEquals(listOf(copy), (state as StartupStorageCheck.State.ChatCopyKept).copies)
    }

    // (3)
    @Test
    fun `3 - with chats written since, the offer says so and a restore keeps today's chats as a copy that is offered`() {
        val phone = declined()
        val old = phone.copyFiles().single()
        (phone.files.file(phone.store) as RecordingPrefs).entries.putAll(ChatCopyLaunches.chats("new"))
        val today = phone.files.entries(phone.store).toMap()

        assertTrue("the Settings row offered nothing again", ChatCopyBackoffApi.retryAndRestart(row(phone.copies())))
        val state = phone.launch(Keystore.ANSWERS)
        assertTrue("not the offer: $state", state is StartupStorageCheck.State.ChatCopyKept)
        val offer = runBlocking { phone.repair().offerFor((state as StartupStorageCheck.State.ChatCopyKept).notice) }
        assertEquals("the offer does not say that today's chats are set aside", WalletStartupRepair.Offer.RestoreChatCopyAfterUse, offer)

        assertTrue(runBlocking { phone.repair().restoreChatCopy() })
        assertEquals("the old chats are not back in the store", phone.files.entries(old), phone.files.entries(phone.store))
        val kept = phone.copyFiles().single { it != old && phone.files.entries(it) == today }
        assertTrue("today's chats were not kept unanswered", kept in phone.copies().unhandled())
    }

    // (4)
    @Test
    fun `4 - with nothing written since, the plain offer`() {
        val phone = declined()
        assertTrue("the Settings row offered nothing again", ChatCopyBackoffApi.retryAndRestart(row(phone.copies())))
        val state = phone.launch(Keystore.ANSWERS)
        assertTrue("not the offer: $state", state is StartupStorageCheck.State.ChatCopyKept)
        assertFalse("the plain offer was not shown", (state as StartupStorageCheck.State.ChatCopyKept).usedAfterPause)
    }

    // (5)
    @Test
    fun `5 - Continue again - the row is back`() {
        val phone = declined()
        assertTrue("the Settings row offered nothing again", ChatCopyBackoffApi.retryAndRestart(row(phone.copies())))
        assertTrue(phone.launch(Keystore.ANSWERS) is StartupStorageCheck.State.ChatCopyKept)
        assertTrue(runBlocking { phone.repair().continueWithoutChatCopy() })
        assertTrue("the row is not back after a second Continue", ChatCopyBackoffApi.isAvailable(row(phone.copies())))
    }

    // (6)
    @Test
    fun `6 - only this wallet - another wallet neither sees nor touches the copy`() {
        val phone = declined()
        val marksBefore = phone.files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        assertTrue("fixture: the row is not shown to the copy's wallet", ChatCopyBackoffApi.isAvailable(row(phone.copies())))

        setOwner(phone, otherWallet)
        assertFalse("another wallet is offered the copy", ChatCopyBackoffApi.isAvailable(row(phone.copies())))
        assertFalse("another wallet's ask did something", StoreOpenerApi.reoffer(phone.copies()))
        assertEquals("another wallet's ask changed the marks", marksBefore, phone.files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME))

        setOwner(phone, wallet)
        assertTrue("the copy's wallet is back and the row is not", ChatCopyBackoffApi.isAvailable(row(phone.copies())))
    }

    // (7) Guard: passes before and after.
    @Test
    fun `7 - a copy called gone, a declined copy of no chats and a copy a restore answered are not askable`() {
        val gone = ChatCopyLaunches(ChatCopyPhoneFiles())
        (gone.files.file(gone.store) as RecordingPrefs).entries.putAll(ChatCopyLaunches.chats("gone"))
        gone.launch(Keystore.KEY_GONE)
        assertTrue(gone.bringOffer())
        assertTrue(runBlocking { gone.repair().restoreChatCopy() })
        gone.launch(Keystore.KEY_GONE)
        assertFalse("a copy called gone is offered from Settings", ChatCopyBackoffApi.isAvailable(row(gone.copies())))

        val keysOnly = ChatCopyLaunches(ChatCopyPhoneFiles())
        val name = "${keysOnly.store}${UNREADABLE_STORE_COPY_MARKER}40"
        (keysOnly.files.file(name) as RecordingPrefs).entries.putAll(
            ChatCopyLaunches.chats("keys").filterKeys { it in ChatCopyLaunches.KEYSET_ENTRIES },
        )
        (keysOnly.files.file(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME) as RecordingPrefs).entries.putAll(
            mapOf("handled_copy:$name" to true, "dismissed_copy:$name" to true),
        )
        assertFalse("a declined copy of no chats is offered from Settings", ChatCopyBackoffApi.isAvailable(row(keysOnly.copies())))

        val restored = ChatCopyLaunches(ChatCopyPhoneFiles())
        (restored.files.file(restored.store) as RecordingPrefs).entries.putAll(ChatCopyLaunches.chats("restored"))
        restored.outage()
        assertTrue(restored.bringOffer())
        assertTrue(runBlocking { restored.repair().restoreChatCopy() })
        restored.launch(Keystore.ANSWERS)
        assertFalse("a copy a restore answered is offered from Settings", ChatCopyBackoffApi.isAvailable(row(restored.copies())))
    }

    // (8)
    @Test
    fun `8 - the declined mark is written in the same commit that answers the copy`() {
        val store = "zchat_e2e_keys_encrypted"
        val copy = "$store${UNREADABLE_STORE_COPY_MARKER}60"
        val phone = KeysetPhone()
        phone.put(store, ChatCopyLaunches.chats("live"))
        phone.put(copy, ChatCopyLaunches.chats("kept"))
        phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, mapOf("unrelated" to true))
        val copies = KeptChatStoreCopies(plainPrefs = phone::plain, fileNames = { phone.snapshot().keys.map { "$it.xml" } })
        phone.refuseCommit += ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME

        assertFalse("a refused answer was reported as done", copies.dismiss())

        val marks = phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        assertFalse("the copy was answered by a refused write", marks.containsKey("handled_copy:$copy"))
        assertFalse("the copy was marked declined by a refused write", marks.containsKey("dismissed_copy:$copy"))
    }

    // (9) FAILS today.
    @Test
    fun `9 - both offer messages end with the sentence that names the way back and the wallet`() {
        val english = ChatCopyBackoffApi.strings("error", "values")
        val spanish = ChatCopyBackoffApi.strings("error", "values-es")
        // ZC-733-C: the endings as the store-opener spec's section 4.3 words them (the copy, not "them"); the sentence
        // that names the wallet stays.
        val endings =
            mapOf(
                "wallet_startup_chat_copy_message" to
                    (
                        "You can also continue without the copy: it stays on this phone, and while this wallet is the one in " +
                            "ZCHAT you can ask for it again from More → Advanced Settings → Restore old chats." to
                            "También puedes continuar sin la copia: se queda en este teléfono y, mientras esta billetera sea la " +
                            "que está en ZCHAT, puedes volver a pedirla desde Más → Configuración avanzada → Restaurar chats antiguos."
                    ),
                "wallet_startup_chat_copy_reoffer_after_use_message" to
                    (
                        "If you continue without the copy, it stays on this phone; ZCHAT stops offering it by itself, and while " +
                            "this wallet is the one in ZCHAT you can ask for it again from More → Advanced Settings → Restore old " +
                            "chats." to
                            "Si continúas sin la copia, se queda en este teléfono; ZCHAT deja de ofrecértela por sí solo y, mientras " +
                            "esta billetera sea la que está en ZCHAT, puedes volver a pedirla desde Más → Configuración avanzada → " +
                            "Restaurar chats antiguos."
                    ),
            )
        for ((key, ending) in endings) {
            val en = english.getValue(key).replace("\\'", "'")
            val es = spanish.getValue(key).replace("\\'", "'")
            assertTrue("$key (English) does not end with the new sentence: $en", en.endsWith(ending.first))
            assertTrue("$key (Spanish) does not end with the new sentence: $es", es.endsWith(ending.second))
            assertTrue("$key (English) does not name the wallet", en.contains("while this wallet is the one in ZCHAT"))
            assertTrue("$key (Spanish) does not name the wallet", es.contains("mientras esta billetera sea la que está en ZCHAT"))
        }
    }
}
