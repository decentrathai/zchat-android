package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1394 (money-path rule 2: an automatic spend needs its consent): payOwedKex checks once, before its retry loop, that the
 * owed KEX belongs to the selected account. Its first attempt meets the transient "…confirm on-chain…" shortfall and waits
 * for the next block; the user switches to another account during that wait; the next attempt is built from the account
 * selected now, which never confirmed that KEX (and its memo names that account's address).
 *
 * Must hold: after the wait nothing is built or paid from the other account and the owed mark stays; with the confirming
 * account selected again, its KEX is paid once. Real ChatViewModel owed-KEX retry over [AccountSwitchMoneyChat].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwedKexAccountRecheckedAfterBlockWaitTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1394owedkexwait01"
        const val SETTLE_MS = 2_000L
        const val QUIET_MS = 300L
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
    fun `an owed KEX of account A is not paid by account B selected during its block wait, and A still pays it once`() =
        runBlocking {
            val chat = AccountSwitchMoneyChat(PEER)
            chat.prefs.kexOwed += PEER
            chat.prefs.owedAccount[PEER] = chat.keyA
            chat.failFirstKexTransiently.set(true)
            val vm = chat.viewModel()

            chat.openChat(vm)
            assertTrue(
                "fixture sanity: the owed KEX did not wait for a block after the transient shortfall",
                PaymentHarness.eventually(SETTLE_MS) { chat.heights.isWaiting() },
            )
            assertEquals("fixture sanity: nothing built before the wait", 0, chat.seam.builds.size)

            chat.accounts.selectOtherZchat()
            chat.heights.advance()
            PaymentHarness.eventually(QUIET_MS) { chat.seam.builds.isNotEmpty() }

            assertEquals(
                "account B paid the KEX account A confirmed, after the block wait: ${chat.seam.builds}",
                emptyList<AccountSwitchMoneyChat.Built>(),
                chat.builtBy(chat.keyB),
            )
            assertTrue("the owed mark of account A was used up by the switch", PEER in chat.prefs.kexOwed)

            chat.accounts.selectZchat()
            chat.tick(vm)
            assertTrue(
                "account A, selected again, did not pay its owed KEX: ${chat.seam.builds}",
                PaymentHarness.eventually(SETTLE_MS) { chat.builtBy(chat.keyA).any { it.kex } },
            )
            PaymentHarness.eventually(QUIET_MS) { chat.seam.builds.size > 1 }
            assertEquals("the owed KEX was paid more than once: ${chat.seam.builds}", 1, chat.seam.builds.count { it.kex })
        }

    @Test
    fun `control - with no switch the owed KEX is paid once by its account after the block wait`() =
        runBlocking {
            val chat = AccountSwitchMoneyChat(PEER)
            chat.prefs.kexOwed += PEER
            chat.prefs.owedAccount[PEER] = chat.keyA
            chat.failFirstKexTransiently.set(true)
            val vm = chat.viewModel()

            chat.openChat(vm)
            assertTrue("fixture sanity: no block wait", PaymentHarness.eventually(SETTLE_MS) { chat.heights.isWaiting() })
            chat.heights.advance()

            assertTrue(
                "the owed KEX was not paid after the block wait: ${chat.seam.builds}",
                PaymentHarness.eventually(SETTLE_MS) { chat.builtBy(chat.keyA).any { it.kex } },
            )
            assertEquals(listOf(AccountSwitchMoneyChat.Built(chat.keyA, kex = true)), chat.seam.builds.toList())
        }
}
