package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_KEY
import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyLaunches.Keystore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-709 (store-opener design, revision 2a, rule S3, sections 3.5 and 5.3, class B): the only copy of user data is
 * never deleted by a clean-up rule. Outside Delete Wallet and Destroy (ZchatPreferences.clearAll) no code removes or
 * empties a kept copy that holds anything of the user's, whatever its age, its answer or its number; using a copy
 * again (class A) leaves the FIRST copy, not a later one.
 *
 * Cases 1 and 2 are guards that pass before and after the fix. All names are synthetic.
 */
class NothingButAWipeDeletesAKeptCopyTest {
    private val wallet = "a".repeat(64)
    private val otherWallet = "b".repeat(64)

    // (1) Guard.
    @Test
    fun `1 - a copy that holds chats is byte for byte the same after everything a phone does`() {
        val phone = ChatCopyLaunches(ChatCopyPhoneFiles())
        val store = phone.store
        val files = phone.files

        fun put(
            name: String,
            entries: Map<String, Any?>,
        ) = (files.file(name) as RecordingPrefs).entries.putAll(entries)
        put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to wallet))
        put(store, ChatCopyLaunches.chats("live"))

        val kept =
            mapOf(
                "answered" to mapOf("handled_copy:" to true),
                "declined" to mapOf("handled_copy:" to true, "dismissed_copy:" to true),
                "gone" to mapOf("handled_copy:" to true),
                "held" to mapOf("restore_held:" to 1L, "restore_offer_after:" to 1L + ChatCopyLaunches.DAY_MS),
                "parked" to mapOf("restore_held:" to 1L, "restore_parked:" to true),
                "other wallet" to emptyMap(),
            ).entries.mapIndexed { index, (kind, marks) ->
                val name = "$store$UNREADABLE_STORE_COPY_MARKER${10 + index}"
                put(name, ChatCopyLaunches.chats(kind.replace(" ", "-")))
                val owner = if (kind == "other wallet") otherWallet else wallet
                put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, marks.mapKeys { it.key + name } + ("copy_owner:$name" to owner))
                Triple(kind, name, files.entries(name))
            }

        repeat(20) { phone.launch(Keystore.ANSWERS) }
        phone.outage()
        if (phone.bringOffer()) runBlocking { phone.repair().restoreChatCopy() }
        phone.outage()
        if (phone.bringOffer()) runBlocking { phone.repair().continueWithoutChatCopy() }
        ChatCopyBackoffApi.retryHeld(phone.copies())
        repeat(5) {
            put(store, ChatCopyLaunches.chats("outage-$it"))
            phone.outage()
            phone.launch(Keystore.ANSWERS)
        }
        files.advance(400 * ChatCopyLaunches.DAY_MS)
        repeat(3) { phone.launch(Keystore.ANSWERS) }

        for ((kind, name, entries) in kept) {
            assertEquals("the $kind copy changed or was deleted", entries, files.entries(name))
        }
    }

    // (2) Guard, source.
    @Test
    fun `2 - source - no rule deletes or empties a copy, and the one deleter is the wipe`() {
        val sources = StoreOpenerApi.mainSources()
        val opener = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        val row = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/RestoreOldChats.kt")
        val deletes = Regex("""deleteSharedPreferences\(|\.delete\(\)|deleteFile\(""")
        assertEquals("RestoreOldChats.kt deletes a file", 0, deletes.findAll(row).count())
        val openerDeletes = deletes.findAll(opener).map { it.range.first }.toList()
        val env = StoreOpenerApi.declaration(opener, "appStoreOpenEnv")
        assertTrue(
            "EncryptedStoreOpener.kt deletes a file outside the environment's remove of a refused new store (ZC-705)",
            openerDeletes.all { opener.indexOf(env) in 0..it && it < opener.indexOf(env) + env.length },
        )
        val limits = Regex("""\b(MAX|KEEP|LIMIT)_\w*(COPIES|COPY)\w*|\b\w*COPY_(AGE|COUNT|LIMIT)\w*""")
        for ((name, code) in listOf("EncryptedStoreOpener.kt" to opener, "RestoreOldChats.kt" to row)) {
            assertEquals("$name names a limit on kept copies", emptyList<String>(), limits.findAll(code).map { it.value }.toList())
        }
        // A function that lists the kept copies and deletes a file is a deleter of copies.
        val deleters =
            sources.flatMap { (path, code) ->
                val listing = StoreOpenerApi.calls(code, "unreadableStoreCopyNames").map { it.enclosingFunction }.toSet()
                StoreOpenerApi
                    .calls(code, "deleteSharedPreferences")
                    .map { it.enclosingFunction }
                    .filter { it in listing }
                    .map { "$path#$it" }
            }
        assertEquals(
            "the functions that delete kept copies",
            listOf("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt#clearAllStores"),
            deleters.distinct(),
        )
        val preferences = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt")
        val callers = StoreOpenerApi.calls(preferences, "unreadableStoreCopyNames").map { it.enclosingFunction }
        assertEquals("the copies are deleted outside the wipe of every store (clearAll)", listOf("clearAllStores"), callers)
    }

    // (3) FAILS today: every round adds files, and the first is one of many.
    @Test
    fun `3 - after rounds of restore and outage the surviving copy is the first one`() {
        val phone = ChatCopyLaunches(ChatCopyPhoneFiles())
        (phone.files.file(phone.store) as RecordingPrefs).entries.putAll(ChatCopyLaunches.chats("original"))
        phone.outage()
        val first = phone.copyFiles().single()
        repeat(3) {
            assertTrue(phone.bringOffer())
            assertTrue(runBlocking { phone.repair().restoreChatCopy() })
            phone.outage()
        }
        assertEquals(listOf(first), phone.copyFiles())
    }
}
