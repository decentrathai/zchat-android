package co.electriccoin.zcash.ui.screen.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.onboarding.viewmodel.OnboardingGuideVM
import co.electriccoin.zcash.ui.screen.swap.SwapArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals

/**
 * ZC-1458 (a) (OT213b owner finding, Fold, 2 Nov 2026; lane L1): onboarding "Swap in the app" opens the swap screen
 * without ever starting the asset load.
 *
 * Every other swap entry goes through [NavigateToSwapUseCase], which calls `SwapRepository.requestRefreshAssets()`
 * before it navigates. `OnboardingGuideVM.completeOnboardingAndShowSwap` at 669cf19e1 calls
 * `navigationRouter.forward(SwapArgs)` directly, so the repository stays at `SwapAssetsData()` (nothing loading, no
 * error) and the token picker shows "Something went wrong" for a request that was never made.
 *
 * What it must be: the onboarding path starts exactly one asset load and opens the swap screen once.
 *
 * Harness: the screen is built through Koin from the same `factoryOf(::OnboardingGuideVM)` shape the app's module uses,
 * over fakes, so the test compiles before the fix and keeps working whichever swap dependency the fix injects
 * ([NavigateToSwapUseCase] or [SwapRepository]). The wallet is never persisted and the identity read never completes, so
 * the screen's start-up readers stay parked. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSwapStartsTheAssetLoadTest {
    private val vms = mutableListOf<OnboardingGuideVM>()
    private val refreshes = AtomicInteger(0)
    private val router = RouteRecorder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `onboarding swap starts exactly one asset load before it opens the swap screen`() {
        val vm = onboardingVm()

        vm.completeOnboardingAndShowSwap()

        assertEquals(1, router.forwards.count { it == SwapArgs }, "fixture sanity: the swap screen opens once: ${router.forwards}")
        assertEquals(
            1,
            refreshes.get(),
            "onboarding opened Swap without starting the asset load (requestRefreshAssets calls); the picker then " +
                "shows an error for a request that was never made",
        )
    }

    // ------------------------------------------------------------------ fixture

    private fun onboardingVm(): OnboardingGuideVM {
        val accounts = Zc101FakeAccountDataSource(kotlinx.coroutines.runBlocking { syntheticZashiAccount() })
        val context: Context = ContextWrapper(null)
        val swapRepository = swapRepository()
        val koin =
            koinApplication {
                modules(
                    module {
                        single { ObserveSelectedWalletAccountUseCase(accounts) }
                        single { GetDefaultUnifiedAddressUseCase(ParkedWallet(), GetSelectedWalletAccountUseCase(accounts)) }
                        single { walletRepository() }
                        single { CopyToClipboardUseCase(context) }
                        single<NavigationRouter> { router }
                        single { persistableWalletProvider() }
                        single { zchatPreferences() }
                        single { contactBook() }
                        single { context }
                        single { swapRepository }
                        factoryOf(::NavigateToSwapUseCase)
                        factoryOf(::OnboardingGuideVM)
                    }
                )
            }.koin
        return koin.get<OnboardingGuideVM>().also { vms += it }
    }

    private fun swapRepository(): SwapRepository =
        proxy { name, _ ->
            when (name) {
                "requestRefreshAssets" -> {
                    refreshes.incrementAndGet()
                    Unit
                }
                "getAssets" -> MutableStateFlow(SwapAssetsData())
                else -> error("unexpected SwapRepository call $name")
            }
        }

    /** The creation-failed flag and the onboarding flip are all the screen asks of the wallet here. */
    private fun walletRepository(): WalletRepository =
        proxy { name, _ ->
            when (name) {
                "getWalletCreationFailed" -> MutableStateFlow(false)
                "completeOnboarding" -> Unit
                else -> error("unexpected WalletRepository call $name")
            }
        }

    /** No wallet is persisted yet. */
    private fun persistableWalletProvider(): PersistableWalletProvider =
        proxy { name, args ->
            when {
                name == "getPersistableWallet" && args.isNullOrEmpty() -> MutableStateFlow(null)
                else -> error("unexpected PersistableWalletProvider call $name")
            }
        }

    private fun zchatPreferences(): ZchatPreferences = proxy { name, _ -> error("unexpected ZchatPreferences call $name") }

    /** The Team contact is already saved, so finishing onboarding writes nothing to the book. */
    private fun contactBook(): ContactBook =
        proxy { name, _ ->
            when (name) {
                "hasContact" -> true
                else -> error("unexpected ContactBook call $name")
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, m, a ->
            when (m.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                else -> answer(m.name, a)
            }
        } as T

    /** A wallet whose chat identity is never readable during the test (onboarding is still creating it). */
    private class ParkedWallet : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    /** Records every route passed to forward. */
    private class RouteRecorder : NavigationRouter {
        val forwards: MutableList<Any> = Collections.synchronizedList(mutableListOf())

        override fun forward(vararg routes: Any) {
            forwards.addAll(routes)
        }

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }
}
