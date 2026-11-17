package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerStartupFailureGuard
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * R2-3 (b), P2c #1 — a temporary phone-security (Keystore) error while ZchatPreferences opens the encrypted chat
 * stores.
 *
 * openEncryptedStore keeps the data and throws an IllegalStateException for the first two launches in a row
 * (KR-1). That exception came out of the ZchatPreferences constructor wherever Koin first built it — mostly a
 * screen's view model on the main thread — so ZCHAT stopped with no message, up to two launches in a row.
 *
 * Required: the failure is typed, the start-up safety screen is shown for it with a clear message and Try again,
 * the main app (whose screens need the chat stores) is not opened over it, and the data stays untouched.
 */
class ChatStoreKeystoreErrorStartupTest {
    private val storeName = "zchat_e2e_keys_encrypted"

    private val chatKeysOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    /** The shared_prefs folder of one phone: the chat store plus the plain launch counter. */
    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }
    }

    private fun phone(): Files =
        Files().apply { (file(storeName) as RecordingPrefs).entries.putAll(chatKeysOnDisk) }

    private fun openChatStores(files: Files) {
        openEncryptedStore(
            name = storeName,
            open = { throw KeyStoreException("the master key exists but is unusable") },
            plainPrefs = files::file,
            sleep = {},
        )
    }

    @Test
    fun `a temporary Keystore error on the chat stores shows the start-up screen with Try again`() =
        runBlocking {
            val files = phone()
            // KR-1 stops the first two launches in a row; the third treats the error as permanent.
            repeat(2) { index ->
                val name = "launch ${index + 1}"
                val check =
                    StartupStorageCheck(
                        openSeedStore = {},
                        openChatStores = { openChatStores(files) },
                        ioDispatcher = Dispatchers.Unconfined,
                    )

                val outcome = runCatching { check.run() }

                assertTrue("$name: opening the chat stores stopped ZCHAT: ${outcome.exceptionOrNull()}", outcome.isSuccess)
                val state = check.state.value
                assertTrue(
                    "$name: expected ChatStoresUnavailable, got $state",
                    state is StartupStorageCheck.State.ChatStoresUnavailable
                )
                val cause = (state as StartupStorageCheck.State.ChatStoresUnavailable).cause
                assertTrue(
                    "$name: the chat-store failure is not typed: $cause",
                    cause.causeChain().any { it is EncryptedStoreUnavailableException },
                )
                assertSame("$name: the start-up screen is not shown", cause, startupFailureCause(null, state))
                assertEquals(
                    "$name: the start-up screen must explain the chat stores and offer Try again",
                    WalletStartupRepair.Offer.ChatStoresUnavailable,
                    repair().offerFor(cause),
                )
                assertEquals(
                    "$name: the main app (its screens need the chat stores) must not open",
                    SecretState.LOADING,
                    gatedSecretState(SecretState.READY, state),
                )
                assertEquals(
                    "$name: onboarding (its identity screen needs the chat stores) must not open",
                    SecretState.LOADING,
                    gatedSecretState(SecretState.NONE, state),
                )
                assertEquals("$name: the chat keys were changed", chatKeysOnDisk, (files.file(storeName) as RecordingPrefs).entries)
            }
        }

    @Test
    fun `a chat-store failure reaching the crash handler from a coroutine shows the start-up screen`() {
        val files = phone()
        val thrown = runCatching { openChatStores(files) }.exceptionOrNull()!!
        val passedOn = mutableListOf<Throwable>()
        val reported = mutableListOf<Throwable>()
        val guard =
            SynchronizerStartupFailureGuard(
                delegate = { _, e -> passedOn += e },
                isSynchronizerStarting = { false },
                onStartupFailure = { reported += it },
            )

        guard.uncaughtException(Thread.currentThread(), RuntimeException("Koin could not create ZchatPreferences", thrown))

        assertTrue("a chat-store failure went to Android's crash handler (the app is killed)", passedOn.isEmpty())
        assertEquals(1, reported.size)
        assertEquals(WalletStartupRepair.Offer.ChatStoresUnavailable, runBlocking { repair().offerFor(reported.single()) })
        assertEquals(chatKeysOnDisk, (files.file(storeName) as RecordingPrefs).entries)
    }

    // Pins (hold before and after the fix).
    @Test
    fun `stores that open start the app normally`() =
        runBlocking {
            val check = StartupStorageCheck(openSeedStore = {}, openChatStores = {}, ioDispatcher = Dispatchers.Unconfined)
            check.run()
            assertEquals(StartupStorageCheck.State.Ready, check.state.value)
            assertEquals(SecretState.READY, gatedSecretState(SecretState.READY, check.state.value))
            assertEquals(SecretState.NONE, gatedSecretState(SecretState.NONE, check.state.value))
            assertEquals(null, startupFailureCause(null, check.state.value))
        }

    @Test
    fun `a wallet start-up failure from the SDK is still shown`() {
        val sdkError = IllegalStateException("migration failed")
        assertSame(sdkError, startupFailureCause(SynchronizerError.Startup(sdkError), StartupStorageCheck.State.Ready))
    }

    private fun repair() =
        WalletStartupRepair(
            persistableWalletProvider = unusedDependency(),
            walletRestoringStateProvider = unusedDependency(),
            walletDataDatabase =
                object : WalletDataDatabase {
                    override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
                },
            appRestarter = {},
        )
}
