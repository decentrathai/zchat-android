package co.electriccoin.zcash.ui.screen.balances

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T1 finding 5b (fix-all 2.13.0, train T1b, T1b-3): the "Spendable" button on the Send and Pay screens counts sealed
 * Orchard.
 *
 * `BalanceWidgetVM` puts `account.spendableShieldedBalance` on the balance button, and that total includes
 * `unified.balance.available`, the Orchard pool. NU6.3 sealed Orchard: it can only pay our own address (ZIP 258), so
 * it can pay no contact. The Send screen's own limits already use `peerSpendableBalance` (confirmed Ironwood), and the
 * button next to them says more. In T1 the Seeker's Send screen showed Orchard 135,000 zat as spendable.
 *
 * Wanted:
 * - `BalanceWidgetArgs.paysAnotherPerson`: set by the screens that pay someone else (Send, Pay), off elsewhere;
 * - with it on, the button shows `peerSpendableBalance`, and its conditions compare against it, so a wallet whose
 *   Orchard is spendable only to itself gets the button (which opens the Spendable sheet that explains Orchard);
 * - the headline is unchanged (it is a total, not a budget);
 * - with it off, nothing changes (TotalBalanceIncludesLockedTest keeps pinning that widget).
 *
 * The new argument is reached by name, so this file compiles before it exists; at HEAD the widget is built without it.
 * All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpendableExcludesSealedOrchardTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun the_send_button_shows_only_ironwood_when_orchard_is_sealed() =
        runBlocking {
            val account = zashi(ironwood = 1_000, orchard = 135_000)

            val button = sendWidget(account).button

            assertNotNull(button, "the Send screen shows no Spendable button although 135,000 zat of it can pay nobody")
            assertEquals(Zatoshi(1_000), button.amount, "the Send screen's Spendable counts sealed Orchard")
        }

    @Test
    fun with_a_lock_the_send_button_still_shows_only_ironwood() =
        runBlocking {
            val account = zashi(ironwood = 1_000, orchard = 135_000, ironwoodLocked = 50_000)

            val button = sendWidget(account).button

            assertNotNull(button)
            assertEquals(Zatoshi(1_000), button.amount, "the Send screen's Spendable counts sealed Orchard")
        }

    @Test
    fun an_orchard_only_wallet_gets_the_button_on_send() =
        runBlocking {
            val account = zashi(ironwood = 0, orchard = 135_000)

            val button = sendWidget(account).button

            assertNotNull(button, "an Orchard-only wallet reads as all spendable on the Send screen")
            assertNotEquals(Zatoshi(135_000), button.amount, "sealed Orchard is not a peer budget")
        }

    @Test
    fun sapling_is_not_counted_either() =
        runBlocking {
            val account = zashi(ironwood = 1_000, orchard = 0, sapling = 20_000)

            val button = sendWidget(account).button

            assertNotNull(button, "Sapling cannot pay a contact privately, and the Send screen counts it as spendable")
            assertEquals(account.peerSpendableBalance, button.amount)
        }

    /** Control: the headline is the account's total on every screen. */
    @Test
    fun the_headline_is_unchanged() =
        runBlocking {
            val account = zashi(ironwood = 1_000, orchard = 135_000)

            assertEquals(Zatoshi(136_000), sendWidget(account).totalBalance)
        }

    /** Control: all Ironwood, nothing locked: no button, as before. */
    @Test
    fun an_all_ironwood_wallet_shows_no_button() =
        runBlocking {
            assertNull(sendWidget(zashi(ironwood = 443_002, orchard = 0)).button)
        }

    /** Control: transparent dust below the shielding threshold still reads as all shielded. */
    @Test
    fun transparent_dust_alone_shows_no_button() =
        runBlocking {
            assertNull(sendWidget(zashi(ironwood = 443_002, orchard = 0, transparent = 3_000)).button)
        }

    /** Control: the Send screen's widget, all Ironwood, some confirming: the button shows what is confirmed. */
    @Test
    fun confirming_ironwood_is_not_counted() =
        runBlocking {
            val account = zashi(ironwood = 1_000, orchard = 0, ironwoodValuePending = 9_000)

            val button = sendWidget(account).button

            assertNotNull(button)
            assertEquals(Zatoshi(1_000), button.amount)
        }

    @Test
    fun send_and_pay_turn_it_on_and_the_wallet_headlines_do_not() {
        for (path in listOf(SEND, PAY)) {
            assertTrue(
                ARG_ON.containsMatchIn(ChatResourceText.source(path)),
                "${path.substringAfterLast('/')} must build its balance widget with paysAnotherPerson = true",
            )
        }
        for (path in listOf(HOME, WALLET_TAB)) {
            assertTrue(
                "paysAnotherPerson" !in ChatResourceText.source(path),
                "${path.substringAfterLast('/')} shows a headline, not a peer budget",
            )
        }
    }

    private fun sendWidget(account: ZashiAccount): BalanceWidgetState =
        BalanceWidgetVM(
            args = sendArgs(),
            accountDataSource = Zc101FakeAccountDataSource(account),
            exchangeRateRepository = Zc101FakeExchangeRateRepository(),
            navigationRouter = Zc101RecordingNavigationRouter(),
        ).state.value

    /** The Send screen's arguments; `paysAnotherPerson` is set by name when the class has it (not at HEAD). */
    private fun sendArgs(): BalanceWidgetArgs {
        val cls = BalanceWidgetArgs::class.java
        val hasFlag = cls.methods.any { it.name == "getPaysAnotherPerson" && it.parameterCount == 0 }
        if (!hasFlag) {
            return BalanceWidgetArgs(showDust = true, isBalanceButtonEnabled = true, isExchangeRateButtonEnabled = false)
        }
        // Declaration order: showDust, isBalanceButtonEnabled, isExchangeRateButtonEnabled, paysAnotherPerson.
        val ctor =
            cls.constructors.first { c ->
                c.parameterCount == 4 && c.parameterTypes.all { it == Boolean::class.javaPrimitiveType }
            }
        val args = ctor.newInstance(true, true, false, true) as BalanceWidgetArgs
        check(args.javaClass.getMethod("getPaysAnotherPerson").invoke(args) == true) { "paysAnotherPerson is not the 4th" }
        return args
    }

    private companion object {
        const val SEND = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/AndroidSend.kt"
        const val PAY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/pay/PayScreen.kt"
        const val HOME = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/home/AndroidHome.kt"
        const val WALLET_TAB = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/AndroidWalletTab.kt"
        val ARG_ON = Regex("""BalanceWidgetArgs\((?:[^()]|\([^()]*\))*paysAnotherPerson\s*=\s*true""")

        fun balance(
            available: Long,
            locked: Long = 0,
            valuePending: Long = 0,
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(valuePending), Zatoshi(locked))

        suspend fun zashi(
            ironwood: Long,
            orchard: Long,
            sapling: Long = 0,
            transparent: Long = 0,
            ironwoodLocked: Long = 0,
            ironwoodValuePending: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-self"),
                    balance(orchard),
                    balance(ironwood, ironwoodLocked, ironwoodValuePending),
                ),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(sapling)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(transparent)),
            isSelected = true,
        )
    }
}
