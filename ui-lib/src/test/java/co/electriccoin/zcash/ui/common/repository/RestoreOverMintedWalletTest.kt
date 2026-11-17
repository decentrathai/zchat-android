package co.electriccoin.zcash.ui.common.repository

import android.app.Application
import android.content.res.Resources
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
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test

/**
 * A3 "restore-over-mint" (plausible fund loss).
 *
 * Path on a real device: onboarding "Create wallet" mints seed A and persists it, the Identity screen
 * shows A's address (so A can already receive money, and the SDK data DB now holds A's account), the
 * user presses Android-back to the welcome screen and chooses "Import wallet" with a DIFFERENT seed B.
 *
 * [WalletRepositoryImpl.restoreWallet] then overwrote the persisted wallet with B ("if the mint wins,
 * restore intentionally replaces it"). Seed A was never shown to the user during onboarding, so it is
 * gone for good together with anything sent to A's address. On top of that, the SDK's data DB still
 * holds only A's account: zcash_client_sqlite 0.22 `init_wallet_db` (wallet/init.rs:653-664) rejects a
 * seed that is not relevant to any derived account with SeedNotRelevant, which SDK 3.0.0
 * TypesafeBackendImpl.initDataDb maps to InitializeException.SeedNotRelevant thrown out of
 * Synchronizer.new, so the app crash-loops on every launch until its data is cleared.
 *
 * The crash-loop half needs the native SDK and is left to the device campaign (D5). This JVM test pins
 * the half that loses the seed: restoring a DIFFERENT seed over a persisted wallet must never replace
 * it, and the restore screen must be told the import was refused. Restoring the SAME seed, or restoring
 * when no wallet exists, must keep working.
 */
class RestoreOverMintedWalletTest {
    private companion object {
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 10L
        const val BIRTHDAY = 3_000_000L
    }

    private val seedA = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "minted$it" })
    private val seedB = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "imported$it" })

    @Test
    fun `restoring a different seed over a created wallet keeps the created seed`() =
        runBlocking {
            val fixture = Fixture(persisted = mintedWallet(seedA))

            fixture.repository.restoreWallet(ZcashNetwork.Mainnet, seedB, BlockHeight.new(BIRTHDAY))
            fixture.settle()

            assertEquals(
                "restoreWallet() replaced the created, never-backed-up seed A with the imported seed B: " +
                    "seed A and any funds sent to its address are lost, and the SDK DB (A's account) no " +
                    "longer matches the persisted seed (SeedNotRelevant crash loop)",
                seedA,
                fixture.walletFlow.value?.seedPhrase,
            )
            assertNotEquals(
                "a refused import must not flip onboarding to READY",
                OnboardingState.READY.toNumber(),
                fixture.onboardingState(),
            )
        }

    @Test
    fun `the restore screen is told that importing over a created wallet was refused`() =
        runBlocking {
            val fixture = Fixture(persisted = mintedWallet(seedA))
            val useCase =
                RestoreWalletUseCase(
                    walletRepository = fixture.repository,
                    context = fixture.application,
                    isTorEnabledStorageProvider = unusedDependency<IsTorEnabledStorageProvider>(),
                )

            val outcome = runCatching { useCase(seedB, enableTor = false, birthday = BlockHeight.new(BIRTHDAY)) }
            fixture.settle()

            assertTrue(
                "RestoreWalletUseCase returned normally for an import that must be refused, so the restore " +
                    "screen shows no error and the user believes seed B was imported",
                outcome.isFailure,
            )
            assertEquals(seedA, fixture.walletFlow.value?.seedPhrase)
        }

    @Test
    fun `restoring the same seed over a created wallet still restores`() =
        runBlocking {
            val fixture = Fixture(persisted = mintedWallet(seedA))

            fixture.repository.restoreWallet(ZcashNetwork.Mainnet, seedA, BlockHeight.new(BIRTHDAY))
            fixture.awaitUntil { fixture.onboardingState() == OnboardingState.READY.toNumber() }

            assertEquals(seedA, fixture.walletFlow.value?.seedPhrase)
            assertEquals(WalletInitMode.RestoreWallet, fixture.walletFlow.value?.walletInitMode)
            assertEquals(OnboardingState.READY.toNumber(), fixture.onboardingState())
        }

    @Test
    fun `restoring with no wallet on the device still restores`() =
        runBlocking {
            val fixture = Fixture(persisted = null)

            fixture.repository.restoreWallet(ZcashNetwork.Mainnet, seedB, BlockHeight.new(BIRTHDAY))
            fixture.awaitUntil { fixture.onboardingState() == OnboardingState.READY.toNumber() }

            assertEquals(seedB, fixture.walletFlow.value?.seedPhrase)
            assertEquals(OnboardingState.READY.toNumber(), fixture.onboardingState())
        }

    /**
     * P2e (RD-1): after Delete Wallet (keep) of wallet A, restoring a DIFFERENT phrase showed A's chats.
     * The chats must be set aside before the new wallet is saved, so no chat code runs for it with A's data.
     */
    @Test
    fun `restoring a different phrase than the chats' owner sets those chats aside`() =
        runBlocking {
            val chats = chatsOwnedBy(seedA)
            val fixture = Fixture(persisted = null, chats = chats)

            fixture.repository.restoreWallet(ZcashNetwork.Mainnet, seedB, BlockHeight.new(BIRTHDAY))
            fixture.awaitUntil { fixture.onboardingState() == OnboardingState.READY.toNumber() }

            assertEquals(seedB, fixture.walletFlow.value?.seedPhrase)
            assertEquals(
                "wallet B was saved with wallet A's chats still in the live stores (RD-1)",
                emptyMap<String, Map<String, Any?>>(),
                chats.live.visible(),
            )
            assertEquals(
                "wallet A's chats must be set aside, not deleted",
                mapOf("messages" to mapOf("conv:1" to "July conversation")),
                chats.bundles.read(chatOwnerTag(seedA.split)),
            )
        }

    @Test
    fun `a wallet whose predecessor's chats cannot be set aside is not saved`() =
        runBlocking {
            val chats = chatsOwnedBy(seedA).also { it.bundles.failWrites = true }
            val fixture = Fixture(persisted = null, chats = chats)

            val outcome =
                runCatching { fixture.repository.restoreWallet(ZcashNetwork.Mainnet, seedB, BlockHeight.new(BIRTHDAY)) }
            fixture.settle()

            assertTrue("the restore must fail when the other wallet's chats cannot be set aside", outcome.isFailure)
            assertEquals("no wallet may be saved over chats that were not set aside", null, fixture.walletFlow.value)
            assertEquals(mapOf("messages" to mapOf("conv:1" to "July conversation")), chats.live.visible())
        }

    @Test
    fun `restoring the phrase that owns the chats keeps them`() =
        runBlocking {
            val chats = chatsOwnedBy(seedA)
            val fixture = Fixture(persisted = null, chats = chats)

            fixture.repository.restoreWallet(ZcashNetwork.Mainnet, seedA, BlockHeight.new(BIRTHDAY))
            fixture.awaitUntil { fixture.onboardingState() == OnboardingState.READY.toNumber() }

            assertEquals(mapOf("messages" to mapOf("conv:1" to "July conversation")), chats.live.visible())
        }

    private fun chatsOwnedBy(seed: SeedPhrase) =
        InMemoryChatOwnership(
            owner = chatOwnerTag(seed.split),
            stores = listOf(InMemoryStore("messages", mapOf("conv:1" to "July conversation"))),
        )

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────

    private fun mintedWallet(seed: SeedPhrase) =
        PersistableWallet(
            network = ZcashNetwork.Mainnet,
            endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
            birthday = BlockHeight.new(BIRTHDAY),
            seedPhrase = seed,
            walletInitMode = WalletInitMode.NewWallet,
        )

    private class Fixture(
        persisted: PersistableWallet?,
        val chats: InMemoryChatOwnership = InMemoryChatOwnership(),
    ) {
        val walletFlow = MutableStateFlow(persisted)
        private val prefs = InMemoryPreferenceProvider()

        // ZcashNetwork.fromResources() reads resources.getBoolean(zcash_is_testnet); the mockable
        // android.jar returns false, i.e. mainnet.
        val application: Application =
            object : Application() {
                private val res = unsafeAllocate(Resources::class.java) as Resources

                override fun getResources(): Resources = res
            }

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
                StandardPreferenceKeys.ONBOARDING_STATE.putValue(
                    preferenceProvider = prefs,
                    newValue = OnboardingState.ONBOARDING_IN_PROGRESS.toNumber(),
                )
            }
            repository =
                WalletRepositoryImpl(
                    configurationRepository = FakeConfigurationRepository(),
                    application = application,
                    lightWalletEndpointProvider = LightWalletEndpointProvider(application),
                    persistableWalletProvider = FakePersistableWalletProvider(walletFlow),
                    synchronizerProvider = NoSynchronizerProvider(),
                    standardPreferenceProvider = standardPreferenceProvider,
                    restoreTimestampDataSource = FakeRestoreTimestampDataSource(),
                    walletRestoringStateProvider = FakeWalletRestoringStateProvider(),
                    walletBackupFlagStorageProvider = FakeWalletBackupFlagStorageProvider(),
                    chatWalletOwnership = chats.ownership,
                )
        }

        suspend fun onboardingState(): Int = StandardPreferenceKeys.ONBOARDING_STATE.getValue(prefs)

        /** Gives a fire-and-forget restore time to land; a refused restore never changes anything. */
        suspend fun settle() {
            val initial = walletFlow.value
            awaitUntil(SETTLE_MS) { walletFlow.value != initial }
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

    /** No synchronizer is running (persistWalletInternal only closes one if present). */
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

    private class FakeWalletBackupFlagStorageProvider : WalletBackupFlagStorageProvider {
        private val value = MutableStateFlow(false)

        override suspend fun flip() {
            value.value = !value.value
        }

        override suspend fun get(): Boolean = value.value

        override suspend fun store(value: Boolean) {
            this.value.value = value
        }

        override fun observe(): Flow<Boolean> = value

        override suspend fun clear() {
            value.value = false
        }
    }

    private class FakeRestoreTimestampDataSource : RestoreTimestampDataSource {
        override suspend fun getOrCreate(): Instant = Instant.EPOCH

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
