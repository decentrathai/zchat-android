package co.electriccoin.zcash.ui.screen.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-733-A (register ZC-733, medium; store-opener design, revision 2a, rules S2 and S11, section 3.6 item 7; class
 * A4): the AI tab says when its store cannot be opened, instead of the app stopping or the tab starting empty. Every
 * use of the store in the view model goes through one guard: the state gets `storeUnavailable` and one fixed message;
 * the start's two reads end there, so NO sign-up is sent (a failed read is never taken for "no access key": a sign-up
 * would ask the server for a new key while the old one sits in the store that could not be opened); every button that
 * reads or writes the store does nothing; the message stays until the card's Retry opens the store, which loads the
 * saved chats and uses the saved key. Today the store's error escapes the view model's scope.
 *
 * The AI store is the real AiPreferences over a fake context; the library is the stand-in of [KeysetPhone]; the AI
 * server is a recording mock. Access keys and account ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiTabSaysWhenItsStoreCannotBeOpenedTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val escaped = CopyOnWriteArrayList<Throwable>()
    private lateinit var main: ExecutorCoroutineDispatcher

    private val data: File = Files.createTempDirectory("zchat-zc733-aitab").toFile().apply { deleteOnExit() }
    private val phone = KeysetPhone()
    private var keystoreOut = true
    private val context = AppContext(phone, data)
    private val calls =
        StoreOpenerApi.FileCalls(
            createWithMasterKey = { name, _ ->
                if (keystoreOut) throw KeyStoreException("synthetic: no answer")
                phone.Library(name).open()
            },
            createByKeyName = { name, _ ->
                if (keystoreOut) throw KeyStoreException("synthetic: no answer")
                phone.Library(name).open()
            },
        )

    private class AppContext(
        private val phone: KeysetPhone,
        private val root: File,
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

    @Before
    fun setUp() {
        main =
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable).apply { setUncaughtExceptionHandler { _, e -> escaped += e } }
                }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty().substringBefore('?')
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
                }
            }
        server.start()
        phone.put(
            STORE,
            Keysets.wrappedEntries() +
                mapOf(
                    "ai_token" to ACCESS_KEY,
                    "ai_user_id" to USER_ID,
                    "ai_conversations" to CONVERSATIONS,
                ),
        )
        runCatching { StoreOpenerApi.useCalls(StoreOpenerApi.aiHolder(context), calls) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun client(): AiApiClient {
        val client = AiApiClient("https://ai.synthetic.invalid")
        AiApiClient::class.java
            .getDeclaredField("baseUrl")
            .apply { isAccessible = true }
            .set(client, server.url("/").toString().trimEnd('/'))
        return client
    }

    private fun tab(): AiTabVM {
        val client = client()
        val store = AiPreferences(context)
        return AiTabVM(store, AiImageStore(context), client, AiTabAccessFixture.access(store, client))
    }

    private fun storeUnavailable(tab: AiTabVM): Boolean =
        tab.state.value.javaClass.methods
            .firstOrNull { it.name == "getStoreUnavailable" }
            ?.invoke(tab.state.value) as? Boolean ?: false

    private fun saved(tab: AiTabVM): List<AiConversation> = tab.state.value.conversations

    private fun fixedMessage(): String =
        AiTabVM::class.java.fields
            .firstOrNull { it.name == "AI_STORE_UNAVAILABLE_MESSAGE" }
            ?.get(null) as? String
            ?: throw AssertionError("ZC-733-A: AiTabVM has no AI_STORE_UNAVAILABLE_MESSAGE")

    private suspend fun await(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
            true
        } ?: false

    private suspend fun end(tab: AiTabVM) {
        withTimeoutOrNull(WAIT_MS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
    }

    // (1) FAILS today: the store's error escapes the view model's scope.
    @Test
    fun `1 - no crash - the state says the store is unavailable and carries the fixed message`() =
        runBlocking {
            val tab = tab()
            try {
                assertTrue("the state never said the store is unavailable: ${tab.state.value}", await { storeUnavailable(tab) })
                assertEquals(fixedMessage(), tab.state.value.error)
                delay(SETTLE_MS)
                assertEquals("an error escaped the view model's scope", emptyList<Throwable>(), escaped.toList())
            } finally {
                end(tab)
            }
        }

    /**
     * (2) AI-KEY-S (store-opener spec 3.7.1 item 1) removed the sign-up call: no sign-in that creates an account is
     * sent either while the store cannot be opened.
     */
    @Test
    fun `2 - no sign-up, no balance and no models while the store is unavailable`() =
        runBlocking {
            val tab = tab()
            try {
                assertTrue("the state never said the store is unavailable", await { storeUnavailable(tab) })
                delay(SETTLE_MS)
                assertEquals("the AI server was asked while the store cannot be opened", emptyList<String>(), requests.toList())
            } finally {
                end(tab)
            }
        }

    // (3)
    @Test
    fun `3 - every button that reads or writes the store does nothing and throws nothing`() =
        runBlocking {
            val tab = tab()
            try {
                assertTrue("the state never said the store is unavailable", await { storeUnavailable(tab) })
                val before = tab.state.value
                listOf<() -> Unit>(
                    { tab.setRetention(7) },
                    { tab.renameConversation("c1", "Renamed") },
                    { tab.selectModel("venice-uncensored-1-2") },
                    { tab.setImageAspect("16:9") },
                    { tab.deleteConversation("c1") },
                    { tab.clearCurrentChat() },
                    { tab.deleteImage("i1") },
                    { tab.clearAllImages() },
                    { tab.send("hello") },
                    { tab.generateImage("a picture") },
                ).forEach { button -> button() }
                delay(SETTLE_MS)
                assertEquals("the AI store was written", 0, phone.writesTo(STORE))
                assertTrue("the field was dropped", storeUnavailable(tab))
                assertEquals("the message was dropped", before.error, tab.state.value.error)
                assertEquals("an error escaped", emptyList<Throwable>(), escaped.toList())
            } finally {
                end(tab)
            }
        }

    // (4)
    @Test
    fun `4 - the message stays - clearError leaves it, and the card offers no Dismiss while the field is set`() =
        runBlocking {
            val tab = tab()
            try {
                assertTrue("the state never said the store is unavailable", await { storeUnavailable(tab) })
                tab.clearError()
                assertEquals("clearError dropped the message", fixedMessage(), tab.state.value.error)
                assertTrue(storeUnavailable(tab))
            } finally {
                end(tab)
            }
            val view = StoreOpenerApi.mainSource("ui/screen/ai/AiTabView.kt")
            val card = view.substringAfter("if (state.error != null) {").substringBefore("if (state.mode == AiMode.Chat)")
            assertTrue("the error card does not hide Dismiss while the store is unavailable", "storeUnavailable" in card)
        }

    // (5)
    @Test
    fun `5 - Retry opens the store - the field and the message go, the chats are back, the saved key is used`() =
        runBlocking {
            val tab = tab()
            try {
                assertTrue("the state never said the store is unavailable", await { storeUnavailable(tab) })
                keystoreOut = false
                tab.refreshModels()
                assertTrue("Retry did not end the state", await { !storeUnavailable(tab) && tab.state.value.error == null })
                val back = await { saved(tab).any { it.id == "c1" } }
                assertTrue("the saved chats are not back", back)
                assertTrue("the saved key is not used", await { tab.state.value.token == ACCESS_KEY })
                delay(SETTLE_MS)
                assertFalse("a sign-up was sent", requests.any { it.endsWith("/ai/auth/register") })
                assertFalse("a sign-in was sent over the saved key", requests.any { it == AiTabAccessFixture.SIGN_IN_PATH })
            } finally {
                end(tab)
            }
        }

    // (6)
    @Test
    fun `6 - the words - the fixed message becomes ai_store_unavailable, in English and Spanish`() {
        val localize =
            Class
                .forName("co.electriccoin.zcash.ui.screen.ai.AiUserMessagesKt")
                .declaredMethods
                .firstOrNull { it.name == "localizeAiMessage" && it.parameterCount == 5 }
                ?: throw AssertionError("ZC-733-A: localizeAiMessage takes no text for the store")
        assertEquals("localized", localize.invoke(null, fixedMessage(), "a", "b", "c", "localized"))

        val english = strings("values")["ai_store_unavailable"] ?: throw AssertionError("no English ai_store_unavailable")
        val spanish = strings("values-es")["ai_store_unavailable"] ?: throw AssertionError("no Spanish ai_store_unavailable")
        assertNotEquals(english, spanish)
        assertFalse(english.contains("%") || spanish.contains("%"))
        assertTrue(english.contains("could not open"))
        assertTrue(spanish.contains("no pudo abrir"))
    }

    /**
     * (7) Guard, source. AI-KEY-S (spec 3.7.1 item 1) moved the key's reads into AiAccountAccess: the shortcut's typed
     * error never becomes "no key" there. No catch stands between the shortcut's start and the key, and AiAccountAccess
     * catches the store's error nowhere.
     */
    @Test
    fun `7 - source - the chat's AI shortcut asks for the key inside one try, and no one turns the store's error into no key`() {
        val chat = StoreOpenerApi.mainSource("ui/screen/chat/viewmodel/ChatViewModel.kt")
        val body = chat.substringAfter("val prefs = co.electriccoin.zcash.ui.screen.ai.AiPreferences(")
        val read = body.indexOf("keyForCharge(")
        assertTrue("the shortcut no longer asks for the key", read >= 0)
        assertFalse("a catch before it turns an error into 'no key'", "catch" in body.substring(0, read))
        val access = StoreOpenerApi.mainSource("ui/screen/ai/AiAccountAccess.kt")
        assertFalse("AiAccountAccess takes the store's error for 'no key'", "EncryptedStoreUnavailableException" in access)
    }

    /**
     * (8) Source. AI-KEY-S (spec 3.7.1 item 1) moved the uses of the store that decide about the key into
     * AiAccountAccess; the view model asks for them through its one guard.
     */
    @Test
    fun `8 - source - every use of the store in AiTabVM sits inside the one guard`() {
        val vm = StoreOpenerApi.mainSource("ui/screen/ai/AiTabVM.kt")
        val direct = Regex("""\bprefs\.(get|set|save|clear)\w*\(""").findAll(vm).map { it.value }.toList()
        assertEquals("uses of the store outside the guard", emptyList<String>(), direct)
        val guards = Regex("""catch \(e: EncryptedStoreUnavailableException\)""").findAll(vm).count()
        assertEquals("not one guard that catches the store's error", 1, guards)
    }

    private fun strings(qualifier: String): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val file = File(dir ?: error("repo root not found"), "ui-lib/src/main/res/ui/common/$qualifier/ai_strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private companion object {
        const val STORE = "zchat_ai_prefs"
        const val ACCESS_KEY = "synthetic-access-key"
        const val USER_ID = "acct_SYNTHETIC-0733"
        const val CONVERSATIONS =
            """[{"id":"c1","title":"Saved","model":"venice-uncensored-1-2","updatedAt":1700000000000,""" +
                """"turns":[{"role":"user","content":"hi","ts":1700000000000}]}]"""
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 500L
    }
}
