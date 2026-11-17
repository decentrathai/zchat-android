package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProcess
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent review point 3; SPECS §1.2 "K1 fast path"): a row with a marker and no id,
 * made by another process that ended, of this same ZCHAT account, in a wallet that holds this account's transactions
 * but none that could be it, stored nothing: after two rising passes it gets one Retry, without waiting for the
 * anchor. Made from another account, or with an empty wallet (a restore still reading its history), it waits for the
 * anchor + 52 like any other row with no id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class K1FastPathRequiresSameAccountAndNonEmptyDbTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4k1peer0000000001"
        const val OTHER = "usyntheticf4k1other000000001"
        const val ENDED_PROCESS = "synthetic-ended-process"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an ended process of the same account with a non-empty wallet and no candidate gets one Retry`() =
        runBlocking {
            val fx = Fixture(sameAccount = true, emptyWallet = false)
            fx.pass(H + 1)
            fx.pass(H + 2)
            assertTrue("Retry was refused although the ended attempt stored nothing", fx.vm.retryMessage(PEER, fx.rowId))
            awaitIdle(fx.vm)
            assertEquals(1, fx.seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), fx.prefs.unexpectedCalls)
        }

    @Test
    fun `another account waits for the anchor`() =
        runBlocking {
            val fx = Fixture(sameAccount = false, emptyWallet = false)
            fx.pass(H + 1)
            fx.pass(H + 2)
            fx.assertRefused()
        }

    @Test
    fun `an empty wallet waits for the anchor`() =
        runBlocking {
            val fx = Fixture(sameAccount = true, emptyWallet = true)
            fx.pass(H + 1)
            fx.pass(H + 2)
            fx.assertRefused()
            // The anchor is the first pass's network height, H + 1.
            fx.pass(H + 52)
            fx.assertRefused()
            fx.pass(H + 53)
            fx.pass(H + 54)
            assertTrue("Retry stayed refused after anchor + 52", fx.vm.retryMessage(PEER, fx.rowId))
            awaitIdle(fx.vm)
            assertEquals(1, fx.seam.proposalUris.size)
        }

    private inner class Fixture(
        sameAccount: Boolean,
        emptyWallet: Boolean,
    ) {
        val prefs = PaymentPrefs(PEER)
        val seam = PaymentSeam(PEER)
        val rowId = SendAttemptHarness.newRowId()
        val wallet =
            Wallet(
                scanned = H,
                txs = if (emptyWallet) emptyList() else listOf(ChainTx(31, OTHER, null, minedHeight = H - 700, expiryHeight = H - 660)),
            )
        val vm: ChatViewModel =
            runBlocking {
                SendAttemptHarness.openChat(prefs, seam, wallet).also { vm ->
                    val hash = if (sameAccount) accountHashOf(vm) else "ffffffffffffffff"
                    prefs.pending[rowId] =
                        SendAttemptHarness
                            .possiblySentRow(rowId, PEER, "synthetic k1 hello", emptyList(), H)
                            .copy(attemptProcessId = ENDED_PROCESS, attemptAccountHash = hash)
                    PaymentHarness.loadPendingMessagesFromPrefs(vm)
                }
            }

        suspend fun pass(tip: Long) {
            wallet.scanned = tip
            wallet.network = tip
            SendAttemptHarness.runPass(vm, wallet)
        }

        suspend fun assertRefused() {
            assertFalse("Retry was accepted without the K1 conditions", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals(0, seam.proposalUris.size)
        }
    }

    private fun accountHashOf(vm: ChatViewModel): String {
        val source =
            ChatViewModel::class.java
                .getDeclaredField("accountDataSource")
                .apply { isAccessible = true }
                .get(vm) as AccountDataSource
        val account = source.allAccounts.value!!.first() as ZashiAccount
        return SendAttemptProcess.accountHash(account.sdkAccount.accountUuid.value)
    }
}
