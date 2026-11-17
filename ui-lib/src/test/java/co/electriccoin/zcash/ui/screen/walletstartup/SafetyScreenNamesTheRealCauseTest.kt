package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.KeyStoreException

/**
 * FD-8b (fix-all 2.13.0, train T4b; destroy SPEC "FD-8b" with review items 10 and 11) — the start-up safety screen
 * names Koin's wrapper instead of the real error.
 *
 * On the Fold debug with the KEYSTORE_OUT seam the screen showed the right Keystore message but the line
 * "Error: InstanceCreationException": StartupStorageCheck opens the chat stores through koin.get<ZchatPreferences>(),
 * Koin wraps the constructor's EncryptedStoreUnavailableException in its own error, and WalletStartupFailedScreen
 * printed `cause?.javaClass?.simpleName` of that wrapper. On a release build R8 merged InstanceCreationException
 * away, so a release phone most likely shows another Koin class name there. Support cannot tell the cause from a
 * screenshot.
 *
 * Wanted (reached by name, so this file compiles before it exists): a top-level `startupErrorName(cause)` in
 * StartupStorageCheck.kt that names the first ZCHAT storage error in the cause chain (typed match on
 * EncryptedStoreUnavailableException / EncryptedPreferencesUnavailableException), else the first error that is
 * not Koin's (`org.koin.`), plus the chain's root cause when it differs; bounded to 16 links and cycle-safe, and
 * never an exception message. The screen passes it as `errorName`. No string changes. All names are synthetic.
 */
class SafetyScreenNamesTheRealCauseTest {
    private companion object {
        const val STORE_MESSAGE = "synthetic store zchat_fd8b_store cannot be opened"
        const val KEYSTORE_MESSAGE = "synthetic master key fd8b is unusable"
        const val KOIN_MESSAGE = "Could not create instance for synthetic ZchatPreferences fd8b"
        const val SEED_FILE = "synthetic_fd8b_seed_file"
        const val MAX_DEPTH = 16
    }

    @Test
    fun `the safety screen does not name Koin's wrapper`() {
        val screen = source("screen/walletstartup/WalletStartupFailedScreen.kt")
        assertFalse(
            "the safety screen still names the class of the cause itself (Koin's wrapper at start-up)",
            "cause?.javaClass?.simpleName" in screen
        )
        assertTrue(
            "the safety screen does not pass startupErrorName(cause) as its errorName",
            "errorName = startupErrorName(cause)" in screen
        )
    }

    @Test
    fun `a chat store error wrapped by Koin is named with its Keystore root cause`() {
        val cause =
            InstanceCreationException(
                KOIN_MESSAGE,
                EncryptedStoreUnavailableException(STORE_MESSAGE, KeyStoreException(KEYSTORE_MESSAGE))
            )

        assertEquals("EncryptedStoreUnavailableException / KeyStoreException", name(cause))
    }

    @Test
    fun `a chat store error behind a wrapper that is not Koin's is still named`() {
        val cause =
            RuntimeException(
                "w",
                EncryptedStoreUnavailableException(STORE_MESSAGE, KeyStoreException(KEYSTORE_MESSAGE))
            )

        assertEquals("EncryptedStoreUnavailableException / KeyStoreException", name(cause))
    }

    @Test
    fun `a seed store error is named with its Keystore root cause, also behind Koin`() {
        val seed = EncryptedPreferencesUnavailableException(SEED_FILE, KeyStoreException(KEYSTORE_MESSAGE))

        assertEquals("EncryptedPreferencesUnavailableException / KeyStoreException", name(seed))
        assertEquals(
            "EncryptedPreferencesUnavailableException / KeyStoreException",
            name(InstanceCreationException(KOIN_MESSAGE, seed))
        )
    }

    @Test
    fun `any other error behind Koin is named past Koin's wrappers`() {
        val cause =
            InstanceCreationException(
                KOIN_MESSAGE,
                InstanceCreationException("inner", IllegalStateException("x", IOException("disk")))
            )

        assertEquals("IllegalStateException / IOException", name(cause))
    }

    @Test
    fun `a chain of Koin errors only names the innermost one`() {
        val cause = InstanceCreationException(KOIN_MESSAGE, NoDefinitionFoundException("no definition"))

        assertEquals("NoDefinitionFoundException", name(cause))
    }

    @Test
    fun `a notice with no cause keeps its own name`() {
        assertEquals("ChatStoreCopyKeptNotice", name(ChatStoreCopyKeptNotice(listOf("a"))))
        assertEquals("ChatStoreCopyRestorePausedNotice", name(ChatStoreCopyRestorePausedNotice(listOf("a"))))
    }

    @Test
    fun `no cause, no name`() {
        assertNull(name(null))
    }

    @Test
    fun `a cause cycle stops`() {
        val first = IllegalStateException("a")
        val second = IOException("b")
        first.initCause(second)
        second.initCause(first)

        assertEquals("IllegalStateException / IOException", name(first))
    }

    @Test
    fun `a chain longer than 16 links is only read to its 16th link`() {
        var cause: Throwable = EncryptedStoreUnavailableException(STORE_MESSAGE, KeyStoreException(KEYSTORE_MESSAGE))
        repeat(MAX_DEPTH + 4) { cause = RuntimeException("w$it", cause) }

        assertEquals("RuntimeException", name(cause))
    }

    @Test
    fun `an anonymous error class is named by the last segment of its class name`() {
        val cause = object : IllegalStateException("anonymous") {}
        assertTrue(cause.javaClass.simpleName.isEmpty())

        assertEquals(cause.javaClass.name.substringAfterLast('.'), name(cause))
    }

    @Test
    fun `no exception message is ever part of the name`() {
        val causes =
            listOf(
                InstanceCreationException(
                    KOIN_MESSAGE,
                    EncryptedStoreUnavailableException(STORE_MESSAGE, KeyStoreException(KEYSTORE_MESSAGE))
                ),
                EncryptedPreferencesUnavailableException(SEED_FILE, KeyStoreException(KEYSTORE_MESSAGE)),
                InstanceCreationException(KOIN_MESSAGE, IllegalStateException(STORE_MESSAGE, IOException(KEYSTORE_MESSAGE))),
            )
        causes.forEach { cause ->
            val label = name(cause)!!
            listOf(STORE_MESSAGE, KEYSTORE_MESSAGE, KOIN_MESSAGE, SEED_FILE, "fd8b").forEach {
                assertFalse("the name \"$label\" holds an exception message", label.contains(it))
            }
            assertTrue("the name \"$label\" is not made of class names", label.matches(Regex("[A-Za-z0-9_$]+( / [A-Za-z0-9_$]+)?")))
        }
    }

    private fun name(cause: Throwable?): String? =
        try {
            startupErrorName().invoke(null, cause) as String?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun startupErrorName(): Method {
        val cls = Class.forName("co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheckKt")
        val method =
            cls.declaredMethods.firstOrNull {
                it.name == "startupErrorName" && it.parameterTypes.contentEquals(arrayOf(Throwable::class.java))
            }
        return checkNotNull(method) { "StartupStorageCheck.kt has no startupErrorName(Throwable?)" }
    }

    private fun source(path: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
            .resolve("src/main/java/co/electriccoin/zcash/ui/$path")
            .readText()
}
