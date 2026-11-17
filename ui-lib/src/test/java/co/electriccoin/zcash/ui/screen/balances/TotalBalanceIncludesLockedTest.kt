package co.electriccoin.zcash.ui.screen.balances

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F9 (fix-all 2.13.0, train T1): value the wallet has locked must stay in the headline total.
 *
 * SDK 3.0.0's `WalletBalance` has a fourth bucket, `locked`, next to available, change pending and value pending, and
 * its `total` leaves it out (total = available + changePending + valuePending). Notes are locked while a Keystone PCZT
 * is open and while the SDK's private Orchard move holds a proved transfer. `WalletAccount.totalBalance` adds up the
 * pools' `total`, so the headline drops by the locked value while it is locked, and the balance looks missing.
 *
 * Wanted:
 * - `WalletAccount.lockedShieldedBalance`: the locked value of every shielded pool (Orchard, Ironwood, and Sapling on
 *   the ZChat account);
 * - `WalletAccount.displayTotalBalance` = `totalBalance` + `lockedShieldedBalance`, for display only;
 * - `totalBalance` itself unchanged, so the "pending", "all shielded" and spendable checks do not fire on a lock;
 * - the wallet headline (BalanceWidgetVM) and the chat list headline show `displayTotalBalance`;
 * - where the widget has a balance button (Send, Pay), a lock shows the button, which leads to the Spendable sheet that
 *   says what is held (SpendableBalanceHeldRowTest).
 *
 * The account list shows no balance at HEAD, so there is nothing to change there. The new properties are reached by
 * name, so this file compiles before they exist. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TotalBalanceIncludesLockedTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun the_display_total_counts_locked_value_in_every_shielded_pool() =
        runBlocking {
            val zashi = zashi(orchardLocked = 400_000, ironwoodLocked = 50_000, saplingLocked = 7_000)
            assertEquals(Zatoshi(457_000), zatoshi(zashi, "lockedShieldedBalance"))
            assertEquals(Zatoshi(zashi.totalBalance.value + 457_000), zatoshi(zashi, "displayTotalBalance"))

            val keystone = keystone(orchardLocked = 400_000, ironwoodLocked = 50_000)
            assertEquals(Zatoshi(450_000), zatoshi(keystone, "lockedShieldedBalance"))
            assertEquals(Zatoshi(keystone.totalBalance.value + 450_000), zatoshi(keystone, "displayTotalBalance"))
        }

    /** Control: a lock is not "pending", and every existing check keeps reading totalBalance, which leaves it out. */
    @Test
    fun total_balance_and_the_pending_checks_ignore_locked_value() =
        runBlocking {
            val locked = zashi(ironwoodLocked = 50_000)

            assertEquals(Zatoshi(IRONWOOD + ORCHARD), locked.totalBalance)
            assertEquals(Zatoshi(IRONWOOD + ORCHARD), locked.totalShieldedBalance)
            assertFalse(locked.isShieldedPending, "a lock must not read as a pending transaction")
            assertTrue(locked.isAllShielded, "isAllShielded reads totalBalance and is unchanged")
        }

    @Test
    fun the_wallet_headline_shows_the_display_total() =
        runBlocking {
            val account = zashi(orchardLocked = 400_000, ironwoodLocked = 50_000)

            val state = widget(account, isBalanceButtonEnabled = false)

            assertEquals(
                Zatoshi(IRONWOOD + ORCHARD + 450_000),
                state.totalBalance,
                "the headline dropped by the locked 450,000 zat",
            )
        }

    @Test
    fun without_a_lock_the_headline_is_the_total_balance() =
        runBlocking {
            val account = zashi()

            assertEquals(account.totalBalance, widget(account, isBalanceButtonEnabled = false).totalBalance)
            assertEquals(null, widget(account, isBalanceButtonEnabled = true).button, "all spendable: no button")
        }

    @Test
    fun a_lock_shows_the_balance_button_with_what_can_be_spent() =
        runBlocking {
            val account = zashi(ironwoodLocked = 50_000)

            val button = widget(account, isBalanceButtonEnabled = true).button

            assertNotNull(button, "the headline counts 50,000 zat that can't be spent, and nothing on screen says so")
            assertEquals(account.spendableShieldedBalance, button.amount)
        }

    @Test
    fun the_chat_list_headline_shows_the_display_total() {
        val vm = ChatResourceText.source(CHAT_VM_PATH)
        assertTrue(
            "val balance = walletAccount?.displayTotalBalance ?: Zatoshi(0)" in vm,
            "the chat list headline must show walletAccount.displayTotalBalance",
        )
        assertTrue(
            "val peerSendableBalance = walletAccount?.peerSpendableBalance ?: Zatoshi(0)" in vm,
            "the Payment dialog's budget stays Ironwood available",
        )
    }

    private fun widget(
        account: ZashiAccount,
        isBalanceButtonEnabled: Boolean
    ): BalanceWidgetState =
        BalanceWidgetVM(
            args =
                BalanceWidgetArgs(
                    showDust = true,
                    isBalanceButtonEnabled = isBalanceButtonEnabled,
                    isExchangeRateButtonEnabled = false,
                ),
            accountDataSource = Zc101FakeAccountDataSource(account),
            exchangeRateRepository = Zc101FakeExchangeRateRepository(),
            navigationRouter = Zc101RecordingNavigationRouter(),
        ).state.value

    private fun zatoshi(
        account: WalletAccount,
        property: String
    ): Zatoshi {
        val getter = "get" + property.replaceFirstChar { it.uppercase() }
        val method =
            account.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?: fail("${account.javaClass.simpleName} has no $property")
        return method.invoke(account) as Zatoshi
    }

    private companion object {
        const val IRONWOOD = 443_002L
        const val ORCHARD = 139_000L
        const val CHAT_VM_PATH = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"

        fun balance(
            available: Long,
            locked: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0), Zatoshi(locked))

        suspend fun zashi(
            orchardLocked: Long = 0,
            ironwoodLocked: Long = 0,
            saplingLocked: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-self"),
                    balance(ORCHARD, orchardLocked),
                    balance(IRONWOOD, ironwoodLocked),
                ),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0, saplingLocked)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

        suspend fun keystone(
            orchardLocked: Long = 0,
            ironwoodLocked: Long = 0,
        ) = KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-keystone-self"),
                    balance(ORCHARD, orchardLocked),
                    balance(IRONWOOD, ironwoodLocked),
                ),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
