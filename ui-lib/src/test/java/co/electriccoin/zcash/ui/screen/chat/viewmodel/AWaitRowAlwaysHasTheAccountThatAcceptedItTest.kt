package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlan
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlanCheck
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1326 (money-path rule 2: automatic spends need their consent): the price question reads the selected account with a
 * time limit (selectedAccountOrNull). When that read gives nothing, the Pay was given by no known account, the wait row is
 * saved with no acceptedAccountKey, and resumeFundsWaitRowClaimed treats a null key as "any account": whichever account is
 * selected at the block pays.
 *
 * Must hold: with no known account at the question no row is saved and the typed text is handed back, whether the fix
 * refuses at the question or at Pay; no block pays. A row that names another account, or none, pays nothing at a block.
 * Real ChatViewModel send and block tick over [FundsConfirmingChat]. The account read fails by an exception, which reaches
 * the same null as the timeout. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AWaitRowAlwaysHasTheAccountThatAcceptedItTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1326noaccount001"
        const val TYPED = "synthetic first words with no known account"
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS
        const val ANOTHER_ACCOUNT = "synthetic-other-account-key"
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `funds confirming with no known account at the question saves no row and hands the text back`() =
        runBlocking { noAccountAtTheQuestion(planAtQuestion = "WaitForFunds") }

    @Test
    fun `funds that start confirming after a question with no known account save no row and hand the text back`() =
        runBlocking { noAccountAtTheQuestion(planAtQuestion = "Fundable") }

    private suspend fun noAccountAtTheQuestion(planAtQuestion: String) {
        val world = FundsConfirmingChat(PEER)
        val vm = world.screen()
        val accounts = AccountReadFailsOnce(Zc101FakeAccountDataSource(syntheticZashiAccount()))
        PaymentHarness.setField(vm, "accountDataSource", accounts)
        val atQuestion = AtomicBoolean(true)
        PaymentHarness.setField(
            vm,
            "firstMessagePlanCheck",
            FirstMessagePlanCheck { _, _, _ ->
                if (atQuestion.compareAndSet(true, false)) {
                    // The question reads the account right after this check.
                    accounts.failNext.set(true)
                    FirstMessagePlanApi.answer(planAtQuestion) as FirstMessagePlan
                } else {
                    FirstMessagePlanApi.answer(world.plan) as FirstMessagePlan
                }
            },
        )

        world.chat.typeAndSend(vm, TYPED)
        // The fix may refuse at the question or at Pay.
        if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) vm.confirmPaidAction()
        PaymentHarness.eventually(FundsConfirmingChat.QUIET_MS) { world.row() != null }

        val saved = world.row()
        assertNull(
            "a wait row was saved with no accepting account: acceptedPrice=${saved?.acceptedPriceZatoshi} " +
                "acceptedAccountKey=${saved?.acceptedAccountKey}",
            saved,
        )
        assertEquals("the typed text was not handed back", PEER to TYPED, FirstMessagePlanChat.restored(vm))
        assertEquals("something was paid", 0, world.seam.submits.get())

        world.plan = "Fundable"
        world.block(vm)
        assertTrue("a block paid a message no account accepted", world.staysAt(0))
    }

    @Test
    fun `a stored row with no accepting account pays nothing at a block`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            world.acceptWhileConfirming(vm, TYPED)
            world.chat.prefs.commitPendingMessage(world.row()!!.copy(acceptedAccountKey = null))

            world.plan = "Fundable"
            world.block(vm)
            assertTrue("the selected account paid a row no account accepted: ${world.seam.submits.get()}", world.staysAt(0))
        }

    @Test
    fun `control - a row another account accepted pays nothing at a block and stays waiting`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)
            assertNotNull("fixture sanity: the row names the account that accepted it", world.row()!!.acceptedAccountKey)
            world.chat.prefs.commitPendingMessage(world.row()!!.copy(acceptedAccountKey = ANOTHER_ACCOUNT))

            world.plan = "Fundable"
            world.block(vm)
            assertTrue("another account paid the accepted row: ${world.seam.submits.get()}", world.staysAt(0))
            assertTrue("the other account's row stopped waiting", world.isWaiting(row.id))
        }

    @Test
    fun `control - the account that accepted the row pays it at a block`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            world.acceptWhileConfirming(vm, TYPED)

            world.plan = "Fundable"
            world.block(vm)
            assertTrue(
                "fixture sanity: the accepted row did not send: ${world.seam.submits.get()}",
                PaymentHarness.eventually(SETTLE_MS) { world.seam.submits.get() >= 1 },
            )
        }

    /** The selected account can be read, except once when asked: the read fails, as selectedAccountOrNull's timeout ends. */
    private class AccountReadFailsOnce(
        private val inner: AccountDataSource,
    ) : AccountDataSource by inner {
        val failNext = AtomicBoolean(false)
        val failed = AtomicInteger(0)

        override suspend fun getSelectedAccount(): WalletAccount {
            if (failNext.compareAndSet(true, false)) {
                failed.incrementAndGet()
                throw IllegalStateException("synthetic: the account was not read in time")
            }
            return inner.getSelectedAccount()
        }
    }
}
