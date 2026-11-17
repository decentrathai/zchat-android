package co.electriccoin.zcash.ui.keystone

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.wallettab.WalletTabVM
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeNotice
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeRules
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeState
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * K0-3 (fix-all 2.13.0, train T1; K0 design findings 2 and 3) — the Wallet tab shows an account chip when the phone
 * holds more than one account, and tapping it opens the account list.
 *
 * Without it nobody can switch accounts: the only switcher (AccountList) opens from the Home screen, which no ZChat
 * build navigates to. So a Keystone-selected user can't get back to the ZChat account to chat (and the chat text
 * that tells them to switch can't be followed), and a Keystone account whose selection the Keystore lost is hidden
 * for good.
 *
 * - only the ZChat account (everyone in practice): no chip;
 * - ZChat + Keystone with Keystone selected: a chip for the Keystone account, whose tap goes forward to AccountList;
 * - ZChat + Keystone with ZChat selected (the lost-selection case): a chip for the ZChat account.
 *
 * Control: the Wallet tab's Chats button replaces the route, so the chat list comes back as a new screen that reads
 * the selected account's own chat identity (per-account identities: OwnChatAddressIsSavedTest).
 *
 * WalletTabVM.accountChip and the chip's fields are read by name, so this file compiles before they exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletTabAccountChipOnlyWithSecondAccountTest {
    private lateinit var collector: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        collector = CoroutineScope(Dispatchers.Main)
    }

    @After
    fun tearDown() {
        collector.cancel()
        // A cancelled collector still resumes on Main while it ends; resetting Main first leaks into another test.
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                collector.coroutineContext[kotlinx.coroutines.Job]?.join()
            }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `only the ZChat account shows no chip, and a Keystone account next to it does`() =
        runBlocking {
            val accounts = TwoAccountDataSource.zashiOnly()
            val router = RouteRecordingNavigationRouter()
            val chip = chipFlow(walletTab(accounts, router))

            assertNull("a phone with only the ZChat account must see no account chip", chip.value)

            accounts.addKeystone(selectIt = true)
            val shown = chip.value
            assertNotNull("ZChat + a selected Keystone account must show the chip", shown)
            assertEquals("the chip names the selected account", true, field(shown!!, "isKeystone"))

            @Suppress("UNCHECKED_CAST")
            (field(shown, "onClick") as () -> Unit).invoke()
            assertEquals(listOf("forward(AccountList)"), router.commands)
        }

    @Test
    fun `a Keystone account behind a selected ZChat account still shows the chip`() =
        runBlocking {
            val accounts = TwoAccountDataSource.zashiOnly().apply { addKeystone(selectIt = false) }
            val chip = chipFlow(walletTab(accounts, RouteRecordingNavigationRouter()))

            val shown = chip.value
            assertNotNull("a Keystone account the Keystore un-selected must stay reachable from the Wallet tab", shown)
            assertEquals("the chip names the selected account", false, field(shown!!, "isKeystone"))
        }

    /** Control, green before and after. */
    @Test
    fun `control - the Wallet tab's Chats button opens the chat list as a new screen`() {
        val router = RouteRecordingNavigationRouter()
        runBlocking { walletTab(TwoAccountDataSource.keystoneSelected(), router) }.onChatsTab()
        assertEquals(listOf("replace(ChatList)"), router.commands)
    }

    private fun chipFlow(vm: WalletTabVM): StateFlow<Any?> {
        @Suppress("UNCHECKED_CAST")
        val flow = WalletTabVM::class.java.getMethod("getAccountChip").invoke(vm) as StateFlow<Any?>
        collector.launch { flow.collect { } }
        return flow
    }

    private fun field(
        state: Any,
        name: String,
    ): Any? =
        // Kotlin names the getter of an `is…` property after the property itself (isKeystone()), others get…().
        state.javaClass.methods
            .first { it.parameterCount == 0 && (it.name == name || it.name == "get" + name.replaceFirstChar { c -> c.uppercase() }) }
            .invoke(state)

    /**
     * The real WalletTabVM, built through its constructor whatever its parameters are: the account sources and the
     * router are the fakes; the navigation and shield use cases, which these checks never call, are allocated
     * without their constructors.
     */
    private fun walletTab(
        accounts: AccountDataSource,
        router: NavigationRouter,
    ): WalletTabVM {
        val constructor = WalletTabVM::class.java.constructors.single()
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    NavigationRouter::class.java -> router
                    ObserveSelectedWalletAccountUseCase::class.java -> ObserveSelectedWalletAccountUseCase(accounts)
                    GetWalletAccountsUseCase::class.java -> GetWalletAccountsUseCase(accounts)
                    ZchatPreferences::class.java -> unusedPreferences()
                    // SY-1: the catch-up bar's sources (an interface cannot be allocated raw); no bar here.
                    PostUpgradeNotice::class.java -> noCatchUpNotice()
                    WalletSnapshotDataSource::class.java -> noWalletSnapshot()
                    else -> allocate(type)
                }
            }
        return constructor.newInstance(*args.toTypedArray()) as WalletTabVM
    }

    private fun noCatchUpNotice(): PostUpgradeNotice =
        PostUpgradeNotice(
            object : PostUpgradeStore {
                override fun read() = PostUpgradeState()

                override fun write(state: PostUpgradeState) = true
            },
            PostUpgradeRules.NOTICE_VERSION_CODE,
        )

    private fun noWalletSnapshot(): WalletSnapshotDataSource =
        object : WalletSnapshotDataSource {
            override fun observe(): StateFlow<WalletSnapshot?> = MutableStateFlow(null)
        }

    private fun unusedPreferences(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "K0UnusedPreferences"
                else -> error("not used in this test: ${method.name}")
            }
        } as ZchatPreferences

    private fun allocate(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return checkNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }
}
