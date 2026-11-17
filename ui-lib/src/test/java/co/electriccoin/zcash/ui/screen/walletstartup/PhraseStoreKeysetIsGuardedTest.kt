package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.Context
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.KeyStoreException
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-705 (store-opener design, revision 2a, sections 3.4.5, 4.1 and 5.2, class F): the store that holds the recovery
 * phrase (co.electriccoin.zcash.encrypted) gets the same guard, from ui-lib, through the open parameter its provider
 * already has; the preferences module is not edited.
 *
 *  - It EXISTS: nothing is ever removed, emptied or rewritten, whatever its key entries are (the phrase must not live
 *    or die with one Keystore key on the very phones whose Keystore already failed once). Destroy's record, the SHA-256
 *    of its two key entries, stays valid.
 *  - It is NEW (a first run): a file made in the clear is undone and made again, and after three tries the start-up
 *    screen says that the protected storage could not be set up: no wallet was saved and nothing was deleted, Try
 *    again, without "Do not uninstall" and without the backup button (there is no wallet).
 *
 * The internal function behind `openPhraseStoreGuarded` is driven with a stand-in open ([StoreOpenerApi]); the files
 * are maps ([KeysetPhone]), and a real folder for Destroy's hash. All names are synthetic.
 */
class PhraseStoreKeysetIsGuardedTest {
    private fun phoneEnv(phone: KeysetPhone): Map<String, Any?> =
        mapOf(
            "removeStoreFile" to { file: String -> phone.remove(file) },
            "storeFileExists" to { file: String -> phone.exists(file) },
        )

    private fun guarded(
        phone: KeysetPhone,
        filename: String,
        open: () -> SharedPreferences,
    ): SharedPreferences =
        StoreOpenerApi.openPhraseStoreGuarded(StoreOpenerApi.newProcess(), filename, phone::plain, phoneEnv(phone), open)

    /** One start of the app's provider over [phone], whose open is the guarded one. */
    private fun provider(
        phone: KeysetPhone,
        filename: String,
        open: () -> SharedPreferences,
    ): EncryptedPreferenceProvider =
        EncryptedPreferenceProvider(KeysetPhoneContext(phone), filename) { _, _ ->
            guarded(phone, filename, open)
        }

    private fun repair(): WalletStartupRepair =
        WalletStartupRepair(
            persistableWalletProvider = unused<PersistableWalletProvider>(),
            walletRestoringStateProvider = NoRestoringState(),
            walletDataDatabase =
                object : WalletDataDatabase {
                    override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
                },
            appRestarter = {},
        )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${method.name} must not be called")
        } as T

    // (1)
    @Test
    fun `1 - source - the provider is built with the guarded open and the preferences module is not edited`() {
        val core = source("ui-lib/src/main/java/co/electriccoin/zcash/di/CoreModule.kt")
        assertTrue(
            "CoreModule does not build the phrase store's provider with the guarded open",
            Regex("""EncryptedPreferenceProvider\(\s*get\(\)\s*,\s*open\s*=\s*::openPhraseStoreGuarded\s*\)""").containsMatchIn(core),
        )
        val module = File(repoRoot, "preference-impl-android-lib/src/main")
        val texts =
            module
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.readText() }
                .toList()
        assertTrue("fixture: the preferences module was not read", texts.isNotEmpty())
        texts.forEach { text ->
            assertFalse("the preferences module knows the guard", "KeysetGuard" in text || "openPhraseStoreGuarded" in text)
        }
        val provider = source("preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference/EncryptedPreferenceProvider.kt")
        assertTrue(
            "the provider's open parameter changed",
            "private val open: (Context, String) -> SharedPreferences = ::openEncryptedSharedPreferences" in provider,
        )
    }

    // (2)
    @Test
    fun `2 - a first run whose file comes out in the clear once - removed, made again, returned protected`() {
        val phone = KeysetPhone()
        val filename = phraseStore()
        val library = phone.Library(filename, plainLeft = 1)

        val store = guarded(phone, filename) { library.open() }

        assertEquals("the store made in the clear was returned", 2, (store as KeysetPhone.StoreHandle).serial)
        assertEquals("the refused file was not removed once", listOf(filename), phone.removed)
        assertFalse("an unprotected key entry is on disk", (phone.entries(filename)[Keysets.KEY_ENTRY] as String).startsWith("08"))
        assertFalse("an unprotected key entry is on disk", (phone.entries(filename)[Keysets.VALUE_ENTRY] as String).startsWith("08"))
    }

    // (3) and (4)
    @Test
    fun `3 and 4 - a first run that fails three times - the storage-not-set-up offer, no file left`() {
        val ways =
            listOf<Pair<String, (KeysetPhone, String) -> () -> SharedPreferences>>(
                "in the clear every time" to { phone, filename -> phone.Library(filename, plainLeft = 99)::open },
                "a Keystore error every time" to { _, _ -> { throw KeyStoreException("synthetic: the Keystore did not answer") } },
            )
        for ((way, makeOpen) in ways) {
            val phone = KeysetPhone()
            val filename = phraseStore()
            val thrown = runCatching { runBlocking { provider(phone, filename, makeOpen(phone, filename))() } }.exceptionOrNull()

            assertTrue("$way: not the phrase store's typed error: $thrown", thrown is EncryptedPreferencesUnavailableException)
            assertTrue(
                "$way: the chain does not hold NewStoreNotMadeException: $thrown",
                chain(thrown).any { it.javaClass.simpleName == "NewStoreNotMadeException" },
            )
            assertFalse("$way: a file is there", phone.exists(filename))

            val offer = runBlocking { repair().offerFor(thrown) }
            assertEquals("$way: not the new offer", "StorageNotSetUp", offer.javaClass.simpleName)
            val content = walletStartupFailedContent(offer)
            assertEquals(stringId("wallet_startup_storage_not_set_up_title"), content.title)
            assertEquals(stringId("wallet_startup_storage_not_set_up_message"), content.message)
            assertEquals(
                listOf(WalletStartupButton(stringId("wallet_startup_failed_try_again"), WalletStartupAction.TRY_AGAIN)),
                content.buttons,
            )
            val hasWallet =
                content.javaClass.methods
                    .firstOrNull { it.name == "getHasWallet" }
                    ?.invoke(content)
            assertEquals("$way: the screen shows the 'Do not uninstall' line and the backup button", false, hasWallet)
        }
    }

    // (5)
    @Test
    fun `5 - a file with a wallet is never removed or written, and an error on open is thrown on as today`() {
        for (keys in listOf("plain" to Keysets.plainEntries(), "protected" to Keysets.wrappedEntries())) {
            val phone = KeysetPhone()
            val filename = phraseStore()
            val withWallet = keys.second + ("AQ-wallet" to "AU-encrypted-wallet")
            phone.put(filename, withWallet)
            val library = phone.Library(filename)

            guarded(phone, filename) { library.open() }

            assertEquals("${keys.first}: removed", emptyList<String>(), phone.removed)
            assertEquals("${keys.first}: written", 0, phone.writesTo(filename))
            assertEquals("${keys.first}: changed", withWallet, phone.entries(filename))

            var opens = 0
            val thrown =
                runCatching {
                    runBlocking {
                        provider(phone, filename) {
                            opens++
                            throw KeyStoreException("synthetic: the master key exists but is unusable")
                        }()
                    }
                }.exceptionOrNull()
            assertTrue("${keys.first}: not the typed error: $thrown", thrown is EncryptedPreferencesUnavailableException)
            assertFalse(
                "${keys.first}: an existing store's error became the first-run one",
                chain(thrown).any { it.javaClass.simpleName == "NewStoreNotMadeException" },
            )
            assertEquals("${keys.first}: an existing store was tried more than once", 1, opens)
            assertEquals(WalletStartupRepair.Offer.SeedStoreUnreadable, runBlocking { repair().offerFor(thrown) })
            assertEquals("${keys.first}: removed after the error", emptyList<String>(), phone.removed)
            assertEquals("${keys.first}: changed after the error", withWallet, phone.entries(filename))
        }
    }

    // (6)
    @Test
    fun `6 - Destroy's record holds - the hash of the two key entries is the same before and after`() {
        val data = Files.createTempDirectory("zchat-zc705-phrase").toFile().apply { deleteOnExit() }
        val phone = KeysetPhone(File(data, "shared_prefs"))
        val filename = "co.electriccoin.zcash.encrypted"
        phone.put(filename, Keysets.plainEntries() + ("AQ-wallet" to "AU-encrypted-wallet"))
        val context = KeysetPhoneContext(phone, dataDir = data)
        val before = storeHash(context)
        assertNotEquals("fixture: no hash", "none", before)

        guarded(phone, filename) { phone.Library(filename).open() }

        assertEquals("the phrase store's key entries changed: a Destroy record for it no longer holds", before, storeHash(context))
    }

    // (7)
    @Test
    fun `7 - source - the function that removes or empties a refused file has exactly two callers`() {
        val sources = StoreOpenerApi.mainSources()
        val opener = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt"
        val guard = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/KeysetGuard.kt"
        val callers =
            sources
                .mapValues { (_, code) -> StoreOpenerApi.calls(code, "undoNewStoreFile").size }
                .filterValues { it > 0 }
        assertEquals("the files that call undoNewStoreFile", setOf(opener, guard), callers.keys)
        val openerBody = StoreOpenerApi.declaration(sources.getValue(opener), "openEncryptedStore")
        val inOpener = StoreOpenerApi.calls(openerBody, "undoNewStoreFile").size
        assertEquals("the opener calls it outside openEncryptedStore", callers[opener], inOpener)
        val inGuard =
            StoreOpenerApi.calls(StoreOpenerApi.declaration(sources.getValue(guard), "openPhraseStoreGuardedWith"), "undoNewStoreFile").size
        assertEquals("KeysetGuard.kt calls it outside the phrase store's guarded open", callers[guard], inGuard)
    }

    // (8)
    @Test
    fun `8 - the texts - the unreadable-phrase screen is true with or without a lost key, and the new screen exists`() {
        val english = strings("values")
        val spanish = strings("values-es")
        val title = "wallet_startup_seed_unreadable_title"
        val message = "wallet_startup_seed_unreadable_message"
        assertFalse("the English title still says 'right now'", english.getValue(title).contains("right now"))
        assertFalse("the Spanish title still says 'ahora'", spanish.getValue(title).contains("ahora"))
        for (phrase in listOf("only failed to answer", "has lost the key", "written recovery phrase")) {
            assertTrue("the English message does not say '$phrase'", english.getValue(message).contains(phrase))
        }
        assertTrue(
            "the Spanish message does not name the written phrase",
            spanish.getValue(message).contains("frase de recuperación escrita"),
        )
        for (key in listOf("wallet_startup_storage_not_set_up_title", "wallet_startup_storage_not_set_up_message")) {
            val en = english[key]
            val es = spanish[key]
            assertNotNull("no English $key", en)
            assertNotNull("no Spanish $key", es)
            assertNotEquals("$key: the Spanish text is the English one", en, es)
            assertFalse("$key holds a placeholder", en!!.contains("%") || es!!.contains("%"))
        }
    }

    private fun phraseStore(): String = "phrase-store-${UUID.randomUUID()}"

    private fun chain(error: Throwable?): List<Throwable> = generateSequence(error) { it.cause }.take(16).toList()

    private fun stringId(key: String): Int =
        Class
            .forName("co.electriccoin.zcash.ui.R\$string")
            .getField(key)
            .getInt(null)

    private fun storeHash(context: Context): String {
        val wipe = Class.forName("co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe")
        val method = wipe.methods.first { it.name == "storeHash" && it.parameterCount == 1 }
        return method.invoke(wipe.getField("INSTANCE").get(null), context) as String
    }

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        dir ?: error("repo root not found")
    }

    private fun source(path: String): String = StoreOpenerApi.stripComments(File(repoRoot, path).readText())

    private fun strings(qualifier: String): Map<String, String> {
        val file = File(repoRoot, "ui-lib/src/main/res/ui/error/$qualifier/strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private class NoRestoringState : WalletRestoringStateProvider {
        override suspend fun get(): WalletRestoringState = WalletRestoringState.NONE

        override suspend fun store(value: WalletRestoringState) = Unit

        override fun observe(): Flow<WalletRestoringState> = flowOf(WalletRestoringState.NONE)

        override suspend fun clear() = Unit
    }
}
