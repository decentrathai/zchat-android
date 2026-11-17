package co.electriccoin.zcash.ui.common.repository

import android.app.Application
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.configuration.model.map.Configuration
import co.electriccoin.zcash.preference.PreferenceHolder
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryChatOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryStore
import co.electriccoin.zcash.ui.screen.chat.datasource.chatOwnerTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/**
 * R2-3 (c), P2e #1 — creating a new wallet when the chats of the wallet before it cannot be set aside.
 *
 * P2e made persistWalletInternal set the previous wallet's chats aside BEFORE the new wallet is saved, and throw
 * (saving nothing) when that fails. createNewWalletForOnboarding and createNewWallet run that in the repository
 * scope (SupervisorJob, no exception handler), so the exception reached the uncaught-exception handler and
 * Android killed ZCHAT on "Create wallet"; createNewWallet had also already flipped onboarding to READY, so the
 * next launch opened the main app with no wallet.
 *
 * Required: no crash, an error the screen can show with a retry, nothing cleared, the new wallet not saved, and
 * a retry that works once the chats can be set aside.
 */
class WalletCreationSetAsideFailureTest {
    private companion object {
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 10L
        const val BIRTHDAY = 3_000_000L
    }

    private val seedA = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "earlier$it" })
    private val seedB = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "created$it" })
    private val chatsOfA = mapOf("messages" to mapOf("conv:1" to "July conversation"))

    private fun chatsOwnedByA() =
        InMemoryChatOwnership(
            owner = chatOwnerTag(seedA.split),
            stores = listOf(InMemoryStore("messages", mapOf("conv:1" to "July conversation"))),
        ).also { it.bundles.failWrites = true }

    @Test
    fun `creating a wallet in onboarding when the earlier chats cannot be set aside shows an error, not a crash`() =
        runBlocking {
            val chats = chatsOwnedByA()
            val fixture = Fixture(chats, OnboardingState.NONE)

            val crashes = fixture.recordingCrashes { fixture.repository.createNewWalletForOnboarding() }

            assertTrue("creating the wallet crashed ZCHAT: $crashes", crashes.isEmpty())
            assertTrue("the screen was not told that creating the wallet failed", fixture.repository.walletCreationFailed.value)
            assertNull("the new wallet was saved over chats that were not set aside", fixture.walletFlow.value)
            assertEquals("the earlier wallet's chats were changed", chatsOfA, chats.live.visible())
            assertEquals(chatOwnerTag(seedA.split), chats.record.value)

            // Try again once the chats can be set aside.
            chats.bundles.failWrites = false
            val retryCrashes = fixture.recordingCrashes { fixture.repository.createNewWalletForOnboarding() }

            assertTrue(retryCrashes.isEmpty())
            assertEquals("the retry did not create the wallet", seedB, fixture.walletFlow.value?.seedPhrase)
            assertFalse("the error is still shown after a successful retry", fixture.repository.walletCreationFailed.value)
            assertEquals("the earlier chats must be set aside, not deleted", chatsOfA, chats.bundles.read(chatOwnerTag(seedA.split)))
        }

    @Test
    fun `creating a wallet from the app when the earlier chats cannot be set aside does not open an empty wallet`() =
        runBlocking {
            val chats = chatsOwnedByA()
            val fixture = Fixture(chats, OnboardingState.NONE)

            val crashes = fixture.recordingCrashes { fixture.repository.createNewWallet() }

            assertTrue("creating the wallet crashed ZCHAT: $crashes", crashes.isEmpty())
            assertTrue("the screen was not told that creating the wallet failed", fixture.repository.walletCreationFailed.value)
            assertNull("the new wallet was saved over chats that were not set aside", fixture.walletFlow.value)
            assertNotEquals(
                "onboarding was marked READY with no wallet saved: the main app then opens without a wallet",
                OnboardingState.READY.toNumber(),
                fixture.onboardingState(),
            )
            assertEquals("the earlier wallet's chats were changed", chatsOfA, chats.live.visible())
        }

    // Pin (holds before and after the fix): with nothing to set aside, the wallet is created as before.
    @Test
    fun `creating a wallet with no earlier chats still works`() =
        runBlocking {
            val chats = InMemoryChatOwnership()
            val fixture = Fixture(chats, OnboardingState.NONE)

            fixture.repository.createNewWalletForOnboarding()
            fixture.awaitUntil { fixture.walletFlow.value != null }

            assertEquals(seedB, fixture.walletFlow.value?.seedPhrase)
            assertFalse(fixture.repository.walletCreationFailed.value)
        }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────

    private inner class Fixture(
        chats: InMemoryChatOwnership,
        onboarding: OnboardingState,
    ) {
        val walletFlow = MutableStateFlow<PersistableWallet?>(null)
        private val prefs = InMemoryPreferenceProvider()
        private val application = unsafeAllocate(Application::class.java) as Application

        val repository: WalletRepositoryImpl

        init {
            val standardPreferenceProvider =
                StandardPreferenceProvider(application).also { holder ->
                    PreferenceHolder::class.java
                        .getDeclaredField("preferenceProvider")
                        .apply { isAccessible = true }
                        .set(holder, prefs)
                }
            runBlocking {
                StandardPreferenceKeys.ONBOARDING_STATE.putValue(prefs, onboarding.toNumber())
            }
            repository =
                object : WalletRepositoryImpl(
                    configurationRepository = FakeConfigurationRepository(),
                    application = application,
                    lightWalletEndpointProvider = LightWalletEndpointProvider(application),
                    persistableWalletProvider = FakePersistableWalletProvider(walletFlow),
                    synchronizerProvider = NoSynchronizerProvider(),
                    standardPreferenceProvider = standardPreferenceProvider,
                    restoreTimestampDataSource = unusedDependency<RestoreTimestampDataSource>(),
                    walletRestoringStateProvider = FakeWalletRestoringStateProvider(),
                    walletBackupFlagStorageProvider = unusedDependency<WalletBackupFlagStorageProvider>(),
                    chatWalletOwnership = chats.ownership,
                ) {
                    override suspend fun mintNewWallet(): Pair<PersistableWallet, Boolean> =
                        PersistableWallet(
                            network = ZcashNetwork.Mainnet,
                            endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
                            birthday = BlockHeight.new(BIRTHDAY),
                            seedPhrase = seedB,
                            walletInitMode = WalletInitMode.NewWallet,
                        ) to true
                }
        }

        suspend fun onboardingState(): Int = StandardPreferenceKeys.ONBOARDING_STATE.getValue(prefs)

        /** Runs [action] (fire-and-forget), lets it land, and returns what reached the crash handler. */
        suspend fun recordingCrashes(action: () -> Unit): List<Throwable> {
            val crashes = CopyOnWriteArrayList<Throwable>()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> crashes += throwable }
            try {
                val walletBefore = walletFlow.value
                val failedBefore = repository.walletCreationFailed.value
                action()
                awaitUntil(SETTLE_MS) {
                    crashes.isNotEmpty() ||
                        walletFlow.value != walletBefore ||
                        repository.walletCreationFailed.value != failedBefore
                }
                // Let anything still in flight (the READY write of the settings path) land too.
                delay(200)
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
            return crashes
        }

        suspend fun awaitUntil(
            timeoutMs: Long = 5_000L,
            predicate: suspend () -> Boolean
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (predicate()) return true
                delay(POLL_MS)
            }
            return predicate()
        }
    }

    private class InMemoryPreferenceProvider : PreferenceProvider {
        private val strings = ConcurrentHashMap<String, String>()

        override suspend fun hasKey(key: PreferenceKey): Boolean = strings.containsKey(key.key)

        override suspend fun putString(
            key: PreferenceKey,
            value: String?
        ) {
            if (value == null) strings.remove(key.key) else strings[key.key] = value
        }

        override suspend fun putStringSet(
            key: PreferenceKey,
            value: Set<String>?
        ) = Unit

        override suspend fun putLong(
            key: PreferenceKey,
            value: Long?
        ) = Unit

        override suspend fun getLong(key: PreferenceKey): Long? = null

        override suspend fun getString(key: PreferenceKey): String? = strings[key.key]

        override suspend fun getStringSet(key: PreferenceKey): Set<String>? = null

        override fun observe(key: PreferenceKey): Flow<String?> = flowOf(strings[key.key])

        override suspend fun remove(key: PreferenceKey) {
            strings.remove(key.key)
        }

        override suspend fun clearPreferences(): Boolean {
            strings.clear()
            return true
        }
    }

    private class FakePersistableWalletProvider(
        private val walletFlow: MutableStateFlow<PersistableWallet?>
    ) : PersistableWalletProvider {
        override val persistableWallet: Flow<PersistableWallet?> = walletFlow

        override suspend fun store(persistableWallet: PersistableWallet) {
            walletFlow.value = persistableWallet
        }

        override suspend fun getPersistableWallet(): PersistableWallet? = walletFlow.value

        override suspend fun requirePersistableWallet(): PersistableWallet = checkNotNull(walletFlow.value)
    }

    private class NoSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = throw IllegalStateException("no synchronizer in this test")

        override fun resetSynchronizer() = Unit
    }

    private class FakeConfigurationRepository : ConfigurationRepository {
        override val configurationFlow: StateFlow<Configuration?> = MutableStateFlow(null)
        override val isFlexaAvailable: StateFlow<Boolean?> = MutableStateFlow(false)

        override suspend fun isFlexaAvailable(): Boolean = false
    }

    private class FakeWalletRestoringStateProvider : WalletRestoringStateProvider {
        override suspend fun get(): WalletRestoringState = WalletRestoringState.NONE

        override suspend fun store(value: WalletRestoringState) = Unit

        override fun observe(): Flow<WalletRestoringState> = flowOf(WalletRestoringState.NONE)

        override suspend fun clear() = Unit
    }
}

private fun unsafeAllocate(clazz: Class<*>): Any {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe =
        unsafeClass
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)!!
}

private inline fun <reified T : Any> unusedDependency(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "unused<${T::class.java.simpleName}>"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name}() is not used here")
        }
    } as T
