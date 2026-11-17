package co.electriccoin.zcash.ui.screen.chat.datasource

import androidx.security.crypto.MasterKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/**
 * ZC-868 (register, high): a store file that does not exist yet is opened with the master-key call
 * (`MasterKey.Builder…build()`), and that call MAKES a new Keystore key when the Keystore answers "absent". Every store
 * of the app and the recovery-phrase store share the default key name, so on a false "absent" (a Keystore that is not
 * ready yet) a new default key replaces the shared one and every existing store, the phrase store included, can never
 * be read again. The rule: before any master-key call, when an encrypted store file that holds both key entries
 * already exists under that key name and the Keystore says the key is absent, ask once more; when it still says
 * absent, make no key and fail closed with the existing storage error (the start-up screen shows it). The same check
 * runs before the wallet starts (the SDK's own opener makes the same call at every wallet start). A true first run
 * (no such file) still makes the key; a Keystore that cannot be asked at all changes nothing.
 *
 * The guard's Keystore question is a replaceable call of `MasterKeyGuard` (reached by name: this class compiles
 * before the fix). Files are on a KeysetPhone with a folder, so they are on disk as the app would find them.
 */
class MasterKeyNeverMadeBesideExistingStoresTest {
    private val asked = mutableListOf<String>()
    private var answers = ArrayDeque<Boolean?>()

    @After
    fun tearDown() {
        runCatching { guardField(KEYSTORE_CALL).set(guardInstance(), pristine) }
    }

    private var pristine: Any? = null

    private fun phone(): Pair<KeysetPhone, KeysetPhoneContext> {
        val data = Files.createTempDirectory("zchat-zc868-phone").toFile().apply { deleteOnExit() }
        val files = KeysetPhone(File(data, "shared_prefs").apply { mkdirs() })
        return files to KeysetPhoneContext(files, dataDir = data)
    }

    /** The Keystore's answers to "is this key there?", in order (null: it cannot be asked); the last one repeats. */
    private fun keystore(vararg answer: Boolean?) {
        answers = ArrayDeque(answer.toList())
        val field = guardField(KEYSTORE_CALL)
        if (pristine == null) pristine = field.get(guardInstance())
        field.set(
            guardInstance(),
            { alias: String ->
                asked += alias
                if (answers.size > 1) answers.removeFirst() else answers.first()
            },
        )
    }

    private fun calls(files: KeysetPhone): StoreOpenerApi.FileCalls =
        StoreOpenerApi.FileCalls(
            createWithMasterKey = { name, _ -> files.Library(name).open() },
            createByKeyName = { name, _ -> files.Library(name).open() },
        )

    @Test
    fun `a - a new store beside an existing phrase store, the Keystore says absent twice - no key is made, the open fails closed`() {
        val (files, context) = phone()
        files.put(PHRASE_STORE, Keysets.wrappedEntries())
        keystore(false)
        val calls = calls(files)

        val thrown = runCatching { StoreOpenerApi.openEncryptedFile(context, NEW_STORE, DEFAULT_KEY, calls) }.exceptionOrNull()

        assertFalse("a master key was made beside the phrase store: ${calls.asked}", calls.asked.any { it.startsWith("masterKey:") })
        assertTrue("the open did not fail closed with the storage error: $thrown", thrown is EncryptedStoreUnavailableException)
        assertEquals("the Keystore was not asked twice", listOf(DEFAULT_KEY, DEFAULT_KEY), asked)
    }

    @Test
    fun `a2 - the second ask finds the key - the store is made as before`() {
        val (files, context) = phone()
        files.put(PHRASE_STORE, Keysets.wrappedEntries())
        keystore(false, true)
        val calls = calls(files)

        StoreOpenerApi.openEncryptedFile(context, NEW_STORE, DEFAULT_KEY, calls)

        assertTrue("the store was not made: ${calls.asked}", calls.asked.any { it.startsWith("masterKey:") })
    }

    @Test
    fun `b - before the wallet starts - the phrase store is there and the Keystore says absent twice - the start stops`() {
        val (files, context) = phone()
        files.put(PHRASE_STORE, Keysets.wrappedEntries())
        keystore(false)

        val thrown = runCatching { guard("checkBeforeWalletStart", context) }.exceptionOrNull()

        assertTrue("the wallet's start went ahead on a false 'absent': $thrown", thrown is EncryptedStoreUnavailableException)
        val wiring = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/di/UseCaseModule.kt").readText()
        val seed = wiring.substringAfter("openSeedStore = {", "").substringBefore("},")
        val check = seed.indexOf("MasterKeyGuard.checkBeforeWalletStart(")
        val read = seed.indexOf("getPersistableWallet(")
        assertTrue("the start-up check does not ask the guard before the wallet is read", check in 0 until read)
    }

    @Test
    fun `c - control - a true first run makes the key, and a key of its own name beside other stores too`() {
        val (files, context) = phone()
        keystore(false)
        // A first run: nothing on the phone yet, so the wallet's start goes ahead (it makes the key then) ...
        assertTrue("a first run's wallet start was stopped", runCatching { guard("checkBeforeWalletStart", context) }.isSuccess)
        // ... and the first store makes the key.
        val first = calls(files)
        StoreOpenerApi.openEncryptedFile(context, NEW_STORE, DEFAULT_KEY, first)
        assertTrue("a first run made no key: ${first.asked}", first.asked.any { it.startsWith("masterKey:") })

        // The AI store has a key of its own: the first AI store beside the other stores is a first run of that key.
        val (others, beside) = phone()
        others.put(PHRASE_STORE, Keysets.wrappedEntries())
        keystore(false)
        val ai = calls(others)
        StoreOpenerApi.openEncryptedFile(beside, AI_STORE, AI_KEY, ai)
        assertTrue("the first AI store made no key: ${ai.asked}", ai.asked.any { it.startsWith("masterKey:") })
    }

    @Test
    fun `d - a Keystore that cannot be asked changes nothing`() {
        val (files, context) = phone()
        files.put(PHRASE_STORE, Keysets.wrappedEntries())
        keystore(null)
        val calls = calls(files)
        StoreOpenerApi.openEncryptedFile(context, NEW_STORE, DEFAULT_KEY, calls)
        assertTrue(calls.asked.any { it.startsWith("masterKey:") })
        assertTrue(runCatching { guard("checkBeforeWalletStart", context) }.isSuccess)
    }

    // ------------------------------------------------------------------ MasterKeyGuard, by name

    private fun guardClass(): Class<*> =
        runCatching { Class.forName(GUARD) }.getOrNull() ?: throw AssertionError("ZC-868: there is no MasterKeyGuard")

    private fun guardInstance(): Any = checkNotNull(guardClass().getField("INSTANCE").get(null))

    private fun guardField(name: String) =
        (guardClass().declaredFields.firstOrNull { it.name == name } ?: throw AssertionError("MasterKeyGuard has no field $name"))
            .apply { isAccessible = true }

    private fun guard(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            guardClass().methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("MasterKeyGuard has no function $name")
        try {
            return method.invoke(guardInstance(), *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private companion object {
        const val GUARD = "co.electriccoin.zcash.ui.screen.chat.datasource.MasterKeyGuard"
        const val KEYSTORE_CALL = "keystoreHasKey"
        const val PHRASE_STORE = "co.electriccoin.zcash.encrypted"
        const val NEW_STORE = "zchat_contact_book_enc"
        const val AI_STORE = "zchat_ai_prefs"
        const val AI_KEY = "zchat_ai_master_key"
        const val DEFAULT_KEY = MasterKey.DEFAULT_MASTER_KEY_ALIAS
    }
}
