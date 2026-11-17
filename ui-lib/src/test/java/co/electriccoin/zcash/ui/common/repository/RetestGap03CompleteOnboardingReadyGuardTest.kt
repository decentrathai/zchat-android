package co.electriccoin.zcash.ui.common.repository

import android.app.Application
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.configuration.model.map.Configuration
import co.electriccoin.zcash.preference.PreferenceHolder
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryChatOwnership
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test

/**
 * RETEST GAP 03 — RED regression pin for the onboarding ordering guard in
 * [WalletRepositoryImpl.completeOnboarding] (WalletRepository.kt ~line 251).
 *
 * FIX UNDER TEST (already present at HEAD):
 *
 *     override fun completeOnboarding() {
 *         scope.launch {
 *             persistableWalletProvider.persistableWallet.filterNotNull().first()   // <-- the guard
 *             persistOnboardingStateInternal(OnboardingState.READY)
 *         }
 *     }
 *
 * Onboarding can reach completeOnboarding() while the wallet mint's birthday-fetch is still in
 * flight (the "Continue" button is tappable during the spinner, or the user rushes through). If
 * READY is written before the wallet is actually persisted, MainAppGraph mounts with a null wallet
 * and its `checkNotNull` crashes in sync/chat — a crash-loop brick that only a data-clear escapes.
 * The `filterNotNull().first()` AWAIT makes READY strictly downstream of a persisted wallet.
 *
 * INVARIANT ENCODED HERE: completeOnboarding() must NOT advance the persisted ONBOARDING_STATE to
 * READY until [PersistableWalletProvider.persistableWallet] has emitted a non-null wallet.
 *
 * This drives the REAL [WalletRepositoryImpl]. The load-bearing seam observed is the persisted
 * ONBOARDING_STATE value (what `persistOnboardingStateInternal` writes) — stable across any fix.
 * A hand-written in-memory [PreferenceProvider] captures the writes; a [MutableStateFlow]-backed
 * [PersistableWalletProvider] fake lets the test hold the wallet null, then release it, and its
 * `subscriptionCount` provides a deterministic sync point (the guard's `.first()` collection bumps
 * it to 1 before it suspends). All the money-path constructor seams that completeOnboarding() never
 * touches are throwing dynamic-proxy fakes.
 *
 * If the `filterNotNull().first()` await were reverted, completeOnboarding() would write READY with
 * the wallet still null and ASSERT #1 below would fail (RED). No production code is modified.
 */
class RetestGap03CompleteOnboardingReadyGuardTest {

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }

    @Test
    fun `completeOnboarding withholds READY until a wallet is persisted`() =
        runBlocking {
            // A single throwaway Application instance for the params/providers completeOnboarding()
            // never invokes. allocateInstance builds it without a constructor or any Android runtime.
            val application = unsafeAllocate(Application::class.java) as Application

            // Inject an in-memory PreferenceProvider into a real StandardPreferenceProvider so
            // `standardPreferenceProvider()` returns the fake (create() is never reached).
            val prefs = InMemoryPreferenceProvider()
            val standardPreferenceProvider =
                StandardPreferenceProvider(application).also { holder ->
                    PreferenceHolder::class.java
                        .getDeclaredField("preferenceProvider")
                        .apply { isAccessible = true }
                        .set(holder, prefs)
                }

            // Onboarding is mid-flight: the mint has NOT persisted a wallet yet.
            StandardPreferenceKeys.ONBOARDING_STATE.putValue(
                preferenceProvider = prefs,
                newValue = OnboardingState.ONBOARDING_IN_PROGRESS.toNumber(),
            )

            // Wallet flow starts null and stays null until the test releases it.
            val walletFlow = MutableStateFlow<PersistableWallet?>(null)

            val repository =
                WalletRepositoryImpl(
                    configurationRepository = FakeConfigurationRepository(),
                    application = application,
                    lightWalletEndpointProvider = LightWalletEndpointProvider(application),
                    persistableWalletProvider = FakePersistableWalletProvider(walletFlow),
                    synchronizerProvider = unusedDependency(),
                    standardPreferenceProvider = standardPreferenceProvider,
                    restoreTimestampDataSource = unusedDependency(),
                    walletRestoringStateProvider = FakeWalletRestoringStateProvider(),
                    walletBackupFlagStorageProvider = unusedDependency(),
                    chatWalletOwnership = InMemoryChatOwnership().ownership,
                )

            // Act: "Continue" is tapped while the wallet is still null.
            repository.completeOnboarding()

            // Sync point: the fix makes completeOnboarding() SUBSCRIBE to the wallet flow and await a
            // non-null wallet (subscriptionCount -> 1) before writing READY. A reverted guard would
            // instead write READY immediately. Wait (bounded) for whichever actually happens.
            val engaged =
                awaitUntil {
                    walletFlow.subscriptionCount.value >= 1 || persistedOnboardingState(prefs) == readyNumber()
                }
            assertTrue(
                "fixture failure: completeOnboarding()'s coroutine neither subscribed to the wallet " +
                    "flow nor wrote ONBOARDING_STATE within ${TIMEOUT_MS}ms",
                engaged,
            )

            // ── ASSERT #1 — RED ON REVERT ────────────────────────────────────────────────────────
            // With the wallet still null, READY must NOT be persisted. Reverting the
            // `persistableWallet.filterNotNull().first()` await writes READY here with a null wallet
            // (the MainAppGraph checkNotNull crash-brick this guard exists to prevent).
            assertNotEquals(
                "completeOnboarding() persisted OnboardingState.READY before any wallet was " +
                    "persisted — the ordering guard (filterNotNull().first()) is gone",
                readyNumber(),
                persistedOnboardingState(prefs),
            )

            // The mint finishes: a wallet is persisted.
            walletFlow.value = unsafeAllocate(PersistableWallet::class.java) as PersistableWallet

            // ── ASSERT #2 — positive control (GREEN at HEAD) ─────────────────────────────────────
            // Only AFTER a non-null wallet exists does completeOnboarding() advance to READY. This
            // proves the fixture can reach READY, so ASSERT #1 reddens purely because of the guard.
            val advanced = awaitUntil { persistedOnboardingState(prefs) == readyNumber() }
            assertTrue(
                "once a wallet is persisted, completeOnboarding() must advance ONBOARDING_STATE to READY",
                advanced,
            )
            assertEquals(readyNumber(), persistedOnboardingState(prefs))
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun readyNumber() = OnboardingState.READY.toNumber()

    private suspend fun persistedOnboardingState(prefs: PreferenceProvider): Int =
        StandardPreferenceKeys.ONBOARDING_STATE.getValue(prefs)

    private suspend fun awaitUntil(predicate: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(POLL_MS)
        }
        return predicate()
    }

    // ── fakes (hand-written; no mockk, no Robolectric) ─────────────────────────────────────────────

    /** In-memory [PreferenceProvider]; only the string-backed reads/writes the repo exercises matter. */
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

    /** Backs [persistableWallet] with the caller's [MutableStateFlow] so the test controls emissions. */
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

    private class FakeConfigurationRepository : ConfigurationRepository {
        override val configurationFlow: StateFlow<Configuration?> = MutableStateFlow(null)
        override val isFlexaAvailable: StateFlow<Boolean?> = MutableStateFlow(false)

        override suspend fun isFlexaAvailable(): Boolean = false
    }

    /** `observe()` is read eagerly at repo construction; everything else is unused here. */
    private class FakeWalletRestoringStateProvider : WalletRestoringStateProvider {
        override suspend fun get(): WalletRestoringState = WalletRestoringState.NONE

        override suspend fun store(value: WalletRestoringState) = Unit

        override fun observe(): Flow<WalletRestoringState> = flowOf(WalletRestoringState.NONE)

        override suspend fun clear() = Unit
    }
}

/** allocateInstance builds an instance with no constructor call — used only for never-invoked seams. */
private fun unsafeAllocate(clazz: Class<*>): Any {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe =
        unsafeClass
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)!!
}

/** Dynamic proxy for a constructor seam completeOnboarding() must never touch; any call throws loudly. */
private inline fun <reified T : Any> unusedDependency(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "unused<${T::class.java.simpleName}>"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else ->
                throw UnsupportedOperationException(
                    "completeOnboarding() must not touch ${T::class.java.simpleName}.${method.name}()"
                )
        }
    } as T
