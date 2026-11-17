package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZchatReceiveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1463 (OT213b owner finding, 2 Nov 2026; lane L6): on My code, "Show Transparent Address" and then Back leaves the
 * screen for the chat list instead of returning to the shielded address.
 *
 * "Show Transparent Address" is not navigation: it is the `showingTransparent` flag inside [ZchatReceiveVM]. At
 * 669cf19e1 the state's `onBack` is always `navigationRouter.back()`, and the screen has no BackHandler, so Back pops
 * the whole My code route.
 *
 * What it must be: while the transparent address shows, Back (the top-bar arrow, the state's `onBack`, which the
 * screen's BackHandler also calls) returns to the shielded view and the router is not called; from the shielded view
 * Back leaves the screen as before. Harness: a real [ZchatReceiveVM] over fakes and a recording router. Synthetic values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyCodeBackFromTransparentReturnsToShieldedTest {
    private val vms = mutableListOf<ZchatReceiveVM>()
    private val router = BackRecorder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) { screen.viewModelScope.coroutineContext[Job]?.join() }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `Back from the transparent address returns to the shielded address and stays on My code`() =
        runBlocking {
            val vm = receiveVm()
            val keepAlive = launch(Dispatchers.Default) { vm.state.collect { } }
            try {
                val shielded = withTimeout(TIMEOUT_MS) { vm.state.first { it is ZchatReceiveState.Success } } as ZchatReceiveState.Success
                assertEquals(false, shielded.showingTransparent, "fixture sanity: My code opens on the shielded address")

                shielded.onShowTransparent()
                val transparent =
                    withTimeout(TIMEOUT_MS) {
                        vm.state.first { (it as? ZchatReceiveState.Success)?.showingTransparent == true }
                    } as ZchatReceiveState.Success

                transparent.onBack()

                assertEquals(
                    emptyList(),
                    router.calls.toList(),
                    "Back from the transparent address left My code (router called) instead of returning to the shielded address",
                )
                val after =
                    kotlinx.coroutines.withTimeoutOrNull(SETTLE_MS) {
                        vm.state.first { (it as? ZchatReceiveState.Success)?.showingTransparent == false }
                    } ?: vm.state.value
                assertTrue(after is ZchatReceiveState.Success, "My code is no longer showing an address: $after")
                assertEquals(false, after.showingTransparent, "Back from the transparent address did not return to the shielded address")
            } finally {
                keepAlive.cancel()
            }
        }

    /** Control, green before and after: from the shielded address Back leaves the screen once. */
    @Test
    fun `control - Back from the shielded address leaves My code`() =
        runBlocking {
            val vm = receiveVm()
            val keepAlive = launch(Dispatchers.Default) { vm.state.collect { } }
            try {
                val shielded = withTimeout(TIMEOUT_MS) { vm.state.first { it is ZchatReceiveState.Success } } as ZchatReceiveState.Success
                shielded.onBack()
                assertEquals(listOf("back"), router.calls.toList())
            } finally {
                keepAlive.cancel()
            }
        }

    // ------------------------------------------------------------------ fixture

    private fun receiveVm(): ZchatReceiveVM {
        val accounts = Zc101FakeAccountDataSource(runBlocking { syntheticZashiAccount() })
        return ZchatReceiveVM(
            observeSelectedWalletAccount = ObserveSelectedWalletAccountUseCase(accounts),
            getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(IdentityWallet(), GetSelectedWalletAccountUseCase(accounts)),
            copyToClipboard = CopyToClipboardUseCase(ContextWrapper(null)),
            navigationRouter = router,
            persistableWalletProvider = noWallet(),
            zchatPreferences = proxy<ZchatPreferences> { name, _ -> error("unexpected ZchatPreferences call $name") },
        ).also { vms += it }
    }

    /** No seed: the relay key is not derived, so the screen shows the address only (Open not offered). */
    private fun noWallet(): PersistableWalletProvider = proxy { name, _ -> throw IllegalStateException("no wallet in this test ($name)") }

    private inline fun <reified T> proxy(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, m, a ->
            when (m.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                else -> answer(m.name, a)
            }
        } as T

    private class IdentityWallet : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        private val answering =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, m, a ->
                when (m.name) {
                    "getUnifiedAddress" -> SHIELDED
                    "toString" -> "IdentityWallet"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.get(0)
                    else -> error("unexpected Synchronizer call ${m.name}")
                }
            } as Synchronizer

        override suspend fun getSynchronizer(): Synchronizer = answering

        override fun resetSynchronizer() = Unit
    }

    private class BackRecorder : NavigationRouter {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun forward(vararg routes: Any) {
            calls += "forward"
        }

        override fun replace(vararg routes: Any) {
            calls += "replace"
        }

        override fun replaceAll(vararg routes: Any) {
            calls += "replaceAll"
        }

        override fun back() {
            calls += "back"
        }

        override fun backTo(route: KClass<*>) {
            calls += "backTo"
        }

        override fun backToRoot() {
            calls += "backToRoot"
        }

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_000L
        const val SHIELDED = "u1synthetic-my-code-shielded"
    }
}
