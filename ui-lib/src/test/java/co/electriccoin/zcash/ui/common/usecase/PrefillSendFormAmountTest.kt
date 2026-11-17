package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Z-1 / Z-2 companions to Zip321PrefillNetAmountTest.
 *
 * The Send form fills `PrefillSendData.All.sendFormAmount`. A fee is attached only by a resend from
 * transaction history, whose amount is the gross total spent, so only then does the fee come off. A fee
 * above the amount can only mean the amount is already net (upstream Zashi b4edd6f66), so it is kept.
 *
 * `PrefillSendUseCase.clear()` drops a prefill still parked on the rendezvous bus, so an already-paid
 * request cannot reappear the next time the Send screen collects.
 */
class PrefillSendFormAmountTest {
    @Test
    fun netRequestFillsTheRequestedAmount() {
        assertEquals(Zatoshi(REQUESTED), prefill(amount = REQUESTED, fee = null).sendFormAmount)
    }

    @Test
    fun resendFromHistorySubtractsTheFee() {
        assertEquals(Zatoshi(REQUESTED), prefill(amount = REQUESTED + FEE, fee = FEE).sendFormAmount)
    }

    @Test
    fun feeAboveTheAmountKeepsTheAmount() {
        assertEquals(Zatoshi(SMALL), prefill(amount = SMALL, fee = FEE).sendFormAmount)
    }

    @Test
    fun feeEqualToTheAmountFillsZero() {
        assertEquals(Zatoshi(0), prefill(amount = FEE, fee = FEE).sendFormAmount)
    }

    @Test
    fun clearDropsAParkedPrefill() =
        runBlocking {
            val prefillSend = PrefillSendUseCase()
            // No Send screen is collecting, so the request parks on the rendezvous bus.
            val parked = prefillSend.request(prefill(amount = REQUESTED, fee = null))

            withTimeout(AWAIT_MS) {
                while (!parked.isCompleted) {
                    prefillSend.clear()
                    delay(POLL_MS)
                }
            }
            assertTrue(parked.isCompleted, "clear() must take the parked request off the bus")

            val delivered = withTimeoutOrNull(NO_PREFILL_MS) { prefillSend().first() }
            assertNull(delivered, "a cleared prefill must never reach the next Send screen")
        }

    private fun prefill(
        amount: Long,
        fee: Long?
    ) = PrefillSendData.All(
        amount = Zatoshi(amount),
        address = "usynthetic-payee",
        fee = fee?.let { Zatoshi(it) },
        memos = null,
    )

    private companion object {
        const val REQUESTED = 100_000L
        const val FEE = 10_000L
        const val SMALL = 4_000L
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 10L
        const val NO_PREFILL_MS = 500L
    }
}
