package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-1947 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026; register medium) — a Retry whose user confirmed a new
 * price runs with its claim marker on the row (submitStartedAtMillis = the claim, accepted price and account on it). When
 * its build met confirming ZEC, the ZC-1909 park (parkWithConsent) took the row back to "waiting for its funds" and
 * cleared that marker: the run's own submit then lost its claim (RetryClaimLostException, "already being handled in
 * another chat screen"), and the row later failed as "waited too long". ZC-1396: a Retry may not wait for funds.
 *
 * Wanted: a run that holds a claim marker (a Retry) is never parked; the row keeps its claim for the run's own submit.
 * Through the ViewModel's own step (reached by name); every address and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimedRetryIsNeverParkedForFundsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1947claimedretry1"
        const val ROW_ID = "pending_zc1947"
        const val CLAIM = 1_700_000_001_947L
        const val CONFIRMED_PRICE = 51_000L
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
    fun `a claimed Retry with a newly confirmed price is not parked and keeps its claim marker`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val account = syntheticZashiAccount()
            PaymentHarness.setField(vm, "accountDataSource", ParkedFirstMessageChat.Wallet(account))
            // The row as retryMessage claims it after the user's Pay on the new figure (ZC-1308).
            world.chat.prefs.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = ROW_ID,
                    text = "synthetic retried first words",
                    timestampMillis = System.currentTimeMillis(),
                    peerAddress = PEER,
                    isPending = true,
                    status = MessageStatus.SENDING.name,
                    sendAmountZatoshi = 1_000L,
                    submitTracked = true,
                    submitStartedAtMillis = CLAIM,
                    acceptedPriceZatoshi = CONFIRMED_PRICE,
                    acceptedAccountKey = GetDefaultUnifiedAddressUseCase.accountKeyOf(account),
                ),
            )

            val parked = park(vm, ROW_ID, CLAIM, PEER)

            assertNotEquals("a claimed Retry was parked to wait for its funds", true, parked)
            val row =
                world.chat.prefs
                    .getPendingMessages()
                    .single { it.id == ROW_ID }
            assertEquals("the Retry's claim marker was cleared under its own run", CLAIM, row.submitStartedAtMillis)
        }

    /** ChatViewModel.parkWithConsent(pendingId, expectedSubmitMarker, accepted = null, peerAddress). */
    private suspend fun park(
        vm: ChatViewModel,
        id: String,
        marker: Long,
        peer: String,
    ): Any? {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "parkWithConsent" && it.parameterCount == 5 }
                .apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(vm, id, marker, null, peer, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
