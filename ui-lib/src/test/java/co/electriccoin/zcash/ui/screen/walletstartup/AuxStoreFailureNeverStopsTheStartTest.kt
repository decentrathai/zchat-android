package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.ui.common.provider.SynchronizerStartupFailureGuard
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatStoreStart
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.security.KeyStoreException
import java.time.Instant
import javax.crypto.AEADBadTagException

/**
 * ZC-733-A (register ZC-733, medium; store-opener design, revision 2a, rule S11, section 3.6 items 4, 7 and 11; class
 * A3): a failure of the contact book, the picture index or the AI store never stops the start and never puts the
 * Try-again screen between the user and the wallet. The start-up check asks for the three stores after the chat
 * storage (the AI store only when its file exists), each in its own catch, so a store set aside at that ask is offered
 * in the same start; the picture index throws from no public function (today two reads stop the app at its first
 * screen); the holder throws `OtherStoreUnavailableException`, which the app's last-resort handler does not take for
 * the chat stores' screen.
 *
 * The start's own function is reached by name ([StoreOpenerApi.openStoresAtStart]); the three classes run over a fake
 * context and the stand-in library of [KeysetPhone]. All names are synthetic.
 */
class AuxStoreFailureNeverStopsTheStartTest {
    private val typed = EncryptedStoreUnavailableException("synthetic: the store did not open", KeyStoreException("synthetic"))

    private fun check(
        openChatStorage: () -> Unit = {},
        askContactBook: () -> Unit = {},
        askPictureIndex: () -> Unit = {},
        askAiStore: () -> Unit = {},
        keptCopies: () -> List<String> = { emptyList() },
    ): StartupStorageCheck.State {
        val check =
            StartupStorageCheck(
                openSeedStore = {},
                openChatStores = { StoreOpenerApi.openStoresAtStart(openChatStorage, askContactBook, askPictureIndex, askAiStore) },
                ioDispatcher = Dispatchers.Unconfined,
                keptChatCopies = keptCopies,
            )
        runBlocking { check.run() }
        return check.state.value
    }

    /** A context over [phone] with a files folder and a data folder of its own. */
    private class AppContext(
        private val phone: KeysetPhone,
        val root: File = Files.createTempDirectory("zchat-zc733-data").toFile().apply { deleteOnExit() },
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(
            name: String,
            mode: Int
        ): SharedPreferences = phone.plain(name)

        override fun deleteSharedPreferences(name: String): Boolean = phone.remove(name)

        override fun getPackageName(): String = "xyz.zsend.zchat.test"

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().also { it.dataDir = root.path }
    }

    /** The stand-in library: the Keystore answers, is out, or has lost the key of a file that exists. */
    private class Library(
        private val phone: KeysetPhone,
    ) {
        var out = false
        var keyGone = false
        val calls =
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ ->
                    if (out) throw KeyStoreException("synthetic: no answer")
                    phone.Library(name).open()
                },
                createByKeyName = { name, _ ->
                    if (out) throw KeyStoreException("synthetic: no answer")
                    if (keyGone) throw AEADBadTagException("synthetic: the key is gone")
                    phone.Library(name).open()
                },
            )
    }

    @Before
    fun newProcess() {
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
    }

    @After
    fun noStart() {
        ChatStoreStart.userVisible = false
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
    }

    // (1) FAILS today: the check never asks.
    @Test
    fun `1 - the start goes on whichever of the three throws the typed error`() {
        val asked = mutableListOf<String>()
        for (failing in listOf("contact book", "picture index", "AI store")) {
            val state =
                check(
                    askContactBook = {
                        asked += "contact book"
                        if (failing == "contact book") throw typed
                    },
                    askPictureIndex = {
                        asked += "picture index"
                        if (failing == "picture index") throw typed
                    },
                    askAiStore = {
                        asked += "AI store"
                        if (failing == "AI store") throw typed
                    },
                )
            assertEquals("$failing: the start stopped", StartupStorageCheck.State.Ready, state)
        }
        assertEquals("the check did not ask all three each time", 9, asked.size)
    }

    // (2)
    @Test
    fun `2 - a contact book set aside at the start's ask is offered in that same start`() {
        val phone = KeysetPhone()
        phone.put("zchat_contact_book_enc", Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val context = AppContext(phone)
        val library = Library(phone).apply { keyGone = true }
        val book = ContactBookImpl(context)
        StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(book), library.calls)

        val state =
            check(
                openChatStorage = { ChatStoreStart.userVisible = true },
                askContactBook = { StoreOpenerApi.ask(book, "askAtStart") },
                keptCopies = { KeptChatStoreCopies(plainPrefs = phone::plain, fileNames = phone::listing).offered() },
            )

        assertTrue("the set-aside contact book is not offered in that start: $state", state is StartupStorageCheck.State.ChatCopyKept)
    }

    // (3)
    @Test
    fun `3 - the AI store is asked only when its file exists`() {
        val none = AppContext(KeysetPhone())
        val noneLibrary = Library(KeysetPhone())
        StoreOpenerApi.useCalls(StoreOpenerApi.aiHolder(none), noneLibrary.calls)
        StoreOpenerApi.aiAskAtStartIfPresent(none)
        assertEquals("the AI store was asked although it has no file", emptyList<String>(), noneLibrary.calls.asked)
        assertFalse("a file was made", File(none.root, "shared_prefs/zchat_ai_prefs.xml").exists())

        val data = Files.createTempDirectory("zchat-zc733-ai").toFile().apply { deleteOnExit() }
        val phone = KeysetPhone(File(data, "shared_prefs"))
        phone.put("zchat_ai_prefs", Keysets.wrappedEntries() + ("AQ-ai" to "AU-ai"))
        val some = AppContext(phone, data)
        val library = Library(phone)
        StoreOpenerApi.useCalls(StoreOpenerApi.aiHolder(some), library.calls)
        StoreOpenerApi.aiAskAtStartIfPresent(some)
        assertEquals("the AI store with a file was not asked", 1, library.calls.asked.size)
    }

    // (4)
    @Test
    fun `4 - the chat storage first - when it cannot be built none of the three is asked`() {
        val asked = mutableListOf<String>()
        val state =
            check(
                openChatStorage = { throw typed },
                askContactBook = { asked += "contact book" },
                askPictureIndex = { asked += "picture index" },
                askAiStore = { asked += "AI store" },
            )
        assertTrue("not the chat stores' state: $state", state is StartupStorageCheck.State.ChatStoresUnavailable)
        assertEquals(emptyList<String>(), asked)
    }

    // (5)
    @Test
    fun `5 - another exception from an ask does not stop the start either`() {
        val state = check(askPictureIndex = { throw IllegalStateException("synthetic") })
        assertEquals(StartupStorageCheck.State.Ready, state)
    }

    // (6) FAILS today: selfAvatarRemovedAt() throws.
    @Test
    fun `6 - the picture index throws from no public function while its store is unavailable`() {
        val phone = KeysetPhone()
        phone.put("zchat_avatar_index_enc", Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val context = AppContext(phone)
        val avatars = AvatarStore(context)
        runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(avatars), Library(phone).apply { out = true }.calls) }

        val thrown = mutableListOf<String>()
        // askAtStart is the start's own ask: it throws what the opener throws, and the start function catches it (case 1).
        everyPublicFunction(AvatarStore::class.java).filterNot { it.name == "askAtStart" }.forEach { method ->
            try {
                method.invoke(avatars, *arguments(method.parameterTypes))
            } catch (e: InvocationTargetException) {
                thrown += "${method.name}: ${e.targetException.javaClass.simpleName}"
            }
        }
        assertEquals("a function of the picture index threw", emptyList<String>(), thrown)
        assertNull(avatars.selfAvatarRemovedAt())
        assertEquals(0L, avatars.getLastBroadcastSelfAt())
        assertNull(avatars.getSelfAvatar())
        assertEquals("the picture index was written", 0, phone.writesTo("zchat_avatar_index_enc"))
    }

    // (7) Guard: passes before and after.
    @Test
    fun `7 - the contact book throws from no function of ContactBook`() {
        val phone = KeysetPhone()
        phone.put("zchat_contact_book_enc", Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val book: ContactBook = ContactBookImpl(AppContext(phone))
        runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(book), Library(phone).apply { out = true }.calls) }

        val thrown = mutableListOf<String>()
        everyPublicFunction(ContactBook::class.java).forEach { method ->
            try {
                method.invoke(book, *arguments(method.parameterTypes))
            } catch (e: InvocationTargetException) {
                thrown += "${method.name}: ${e.targetException.javaClass.simpleName}"
            }
        }
        assertEquals("a function of the contact book threw", emptyList<String>(), thrown)
    }

    // (8) FAILS today: two reads without a catch.
    @Test
    fun `8 - source - the launch tick reads no function of the picture index that reads the store outside a catch`() {
        val chat = StoreOpenerApi.mainSource("ui/screen/chat/viewmodel/ChatViewModel.kt")
        val tick = "fun currentSelfAvatarVersion(" in chat && "fun observeSelfAvatarDelivery(" in chat
        assertTrue("the launch tick is not where it was", tick)
        val avatars = StoreOpenerApi.mainSource("ui/screen/chat/datasource/AvatarStore.kt")
        val unguarded =
            Regex("""\n    fun (\w+)\(""")
                .findAll(avatars)
                .map { it.groupValues[1] }
                .filter { name ->
                    val body = avatars.substringAfter("\n    fun $name(").substringBefore("\n    fun ").substringBefore("\n    // ----")
                    "prefs." in body && "try" !in body && "runCatching" !in body
                }.toList()
        assertEquals("functions of the picture index that read the store outside a catch", emptyList<String>(), unguarded)
    }

    // (9)
    @Test
    fun `9 - the holder throws OtherStoreUnavailableException with the opener's error as its cause`() {
        val phone = KeysetPhone()
        phone.put("zchat_contact_book_enc", Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val book = ContactBookImpl(AppContext(phone))
        val holder = StoreOpenerApi.holderOf(book)
        StoreOpenerApi.useCalls(holder, Library(phone).apply { out = true }.calls)

        for (function in listOf("get", "tryAgain")) {
            val error = runCatching { StoreOpenerApi.ask(holder, function) }.exceptionOrNull()
            assertEquals("$function: not the holder's error", "OtherStoreUnavailableException", error?.javaClass?.simpleName)
            assertTrue("$function: its cause is not the opener's typed error", error?.cause is EncryptedStoreUnavailableException)
        }
    }

    // (10)
    @Test
    fun `10 - the last-resort handler does not take the three stores' error for the chat stores' screen`() {
        val other = StoreOpenerApi.otherStoreError("synthetic", typed)
        assertFalse("the holder's error is taken for the chat stores' screen", SynchronizerStartupFailureGuard.isStorageUnavailable(other))
        assertFalse(SynchronizerStartupFailureGuard.isStorageUnavailable(IllegalStateException("wrapped", other)))
        assertTrue("the chat stores' error is no longer taken", SynchronizerStartupFailureGuard.isStorageUnavailable(typed))
        assertTrue(
            "the recovery-phrase store's error is no longer taken",
            SynchronizerStartupFailureGuard.isStorageUnavailable(
                EncryptedPreferencesUnavailableException("synthetic", KeyStoreException("x")),
            ),
        )
    }

    /**
     * (11) Source. CHANGED BY DESIGN BY ZC-718 (store-opener spec 3.7.1 item 1; reset spec 6.1): ZC-718 makes the AI
     * store one object of the dependency graph, so "AiPreferences( at its two places only" became "AiPreferences( is
     * built only where ZC-718 names it": the graph's one single (for the claim's hand-over and the Reset's keep step) and
     * the two readers; the half "one LazyEncryptedStore for zchat_ai_prefs" stays.
     */
    @Test
    fun `11 - source - nobody else opens the three stores`() {
        val sources = StoreOpenerApi.mainSources()
        val holders = sources.filter { (_, code) -> Regex("""\bLazyEncryptedStore\(""").containsMatchIn(code) }.keys
        assertEquals(
            setOf(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ContactBookImpl.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/AvatarStore.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiPreferences.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/LazyEncryptedStore.kt",
            ),
            holders,
        )
        val ai =
            sources
                .flatMap { (path, code) -> Regex("""\bAiPreferences\(""").findAll(code).map { path } }
                .filterNot { it.endsWith("/AiPreferences.kt") }
        assertEquals(
            listOf(
                "ui-lib/src/main/java/co/electriccoin/zcash/di/DataSourceModule.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AndroidAiTab.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt",
            ),
            ai.sorted(),
        )
    }

    private fun everyPublicFunction(type: Class<*>) =
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') }

    private fun arguments(types: Array<Class<*>>): Array<Any?> =
        types
            .map { type ->
                when (type) {
                    String::class.java -> "usyntheticaux"
                    ByteArray::class.java -> byteArrayOf(1, 2, 3)
                    java.lang.Long.TYPE -> 1L
                    Integer.TYPE -> 1
                    java.lang.Boolean.TYPE -> false
                    Collection::class.java, List::class.java -> emptyList<String>()
                    Contact::class.java -> Contact(address = "usyntheticaux", name = "Synthetic", addedAt = Instant.ofEpochMilli(1))
                    else -> null
                }
            }.toTypedArray()
}
