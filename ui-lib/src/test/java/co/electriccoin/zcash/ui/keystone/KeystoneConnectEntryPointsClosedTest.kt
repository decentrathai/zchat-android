package co.electriccoin.zcash.ui.keystone

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.GetFlexaStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.GetKeystoneStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletRestoringStateUseCase
import co.electriccoin.zcash.ui.common.usecase.SelectWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.accountlist.model.AccountListItem
import co.electriccoin.zcash.ui.screen.accountlist.model.AccountListState
import co.electriccoin.zcash.ui.screen.accountlist.viewmodel.AccountListViewModel
import co.electriccoin.zcash.ui.screen.integrations.IntegrationsState
import co.electriccoin.zcash.ui.screen.integrations.IntegrationsVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * K0-1 (fix-all 2.13.0, train T1): connecting a new Keystone is closed in 2.13.0.
 *
 * Both routes to Connect Keystone start behind screens no ZChat build navigates to (K0 design §1), so this is
 * defence in depth: the Integrations list has no "Connect Keystone" row, and the account list has no Keystone promo
 * row (with its discount link) and no "Connect Hardware Wallet" button, while a ZChat account is the only account.
 * At HEAD both are there.
 *
 * Control: with a Keystone account already connected, the account list still lists both accounts, so the switch the
 * Wallet-tab chip opens keeps working.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneConnectEntryPointsClosedTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the Integrations list offers no Connect Keystone row`() =
        runBlocking {
            val accounts = TwoAccountDataSource.zashiOnly()
            val vm =
                IntegrationsVM(
                    getWalletRestoringState = GetWalletRestoringStateUseCase(walletRepository()),
                    getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts),
                    getFlexaStatus = GetFlexaStatusUseCase(configurationRepository(), accounts),
                    getKeystoneStatus = GetKeystoneStatusUseCase(accounts),
                    navigationRouter = RouteRecordingNavigationRouter(),
                )
            val state: IntegrationsState = withTimeout(TIMEOUT_MS) { vm.state.filterNotNull().first() }

            val keystoneRows = state.items.filter { it.title == stringRes(R.string.integrations_keystone) }
            assertTrue(
                "the Integrations list still offers Connect Keystone, which 2.13.0 closes",
                keystoneRows.isEmpty(),
            )
            // Sanity: the list was built (the More row is always there).
            assertTrue("the Integrations list is empty: fixture problem", state.items.isNotEmpty())
        }

    @Test
    fun `the account list offers no Keystone promo row and no Connect Hardware Wallet button`() =
        runBlocking {
            val accounts = TwoAccountDataSource.zashiOnly()
            val state = accountListState(accounts)

            assertNull(
                "the account list still offers Connect Hardware Wallet, which 2.13.0 closes",
                state.addWalletButton,
            )
            val promo = state.items.orEmpty().filterIsInstance<AccountListItem.Other>()
            assertTrue(
                "the account list still shows the Keystone promo row and its shop link, which 2.13.0 closes",
                promo.isEmpty(),
            )
            assertEquals(
                "the ZChat account is listed",
                1,
                state.items
                    .orEmpty()
                    .filterIsInstance<AccountListItem.Account>()
                    .size
            )
        }

    /** Control, green before and after: a Keystone connected earlier stays listed, so the user can switch. */
    @Test
    fun `control - with a Keystone connected earlier the account list lists both accounts`() =
        runBlocking {
            val accounts = TwoAccountDataSource.keystoneSelected()
            val state = accountListState(accounts)

            assertEquals(
                2,
                state.items
                    .orEmpty()
                    .filterIsInstance<AccountListItem.Account>()
                    .size
            )
            assertNull(state.addWalletButton)
        }

    private suspend fun accountListState(accounts: TwoAccountDataSource): AccountListState {
        val router = RouteRecordingNavigationRouter()
        val vm =
            AccountListViewModel(
                getWalletAccounts = GetWalletAccountsUseCase(accounts),
                selectWalletAccount = SelectWalletAccountUseCase(accounts, router),
                navigationRouter = router,
            )
        return withTimeout(TIMEOUT_MS) { vm.state.filterNotNull().first { !it.isLoading } }
    }

    private fun walletRepository(): WalletRepository {
        val restoring: StateFlow<WalletRestoringState> = MutableStateFlow(WalletRestoringState.NONE)
        return Proxy.newProxyInstance(
            WalletRepository::class.java.classLoader,
            arrayOf(WalletRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getWalletRestoringState" -> restoring
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "K0WalletRepository"
                else -> error("not used in this test: ${method.name}")
            }
        } as WalletRepository
    }

    private fun configurationRepository(): ConfigurationRepository {
        val flexa: StateFlow<Boolean?> = MutableStateFlow(false)
        return Proxy.newProxyInstance(
            ConfigurationRepository::class.java.classLoader,
            arrayOf(ConfigurationRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                // The property getter takes no argument; the suspend function of the same name takes a continuation.
                "isFlexaAvailable" -> if (args.isNullOrEmpty()) flexa else false
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "K0ConfigurationRepository"
                else -> error("not used in this test: ${method.name}")
            }
        } as ConfigurationRepository
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
