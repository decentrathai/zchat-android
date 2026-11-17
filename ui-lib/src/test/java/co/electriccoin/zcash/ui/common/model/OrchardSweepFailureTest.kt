package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import org.junit.Test
import kotlin.test.assertEquals

/** How a sweep that could not be prepared is explained (sweep spec §2.4, classifyOrchardSweepFailure). */
class OrchardSweepFailureTest {
    @Test
    fun a_too_small_sweep_keeps_its_figures() {
        assertEquals(
            OrchardSweepFailure.TooSmall(Zatoshi(30_000), Zatoshi(25_000)),
            classifyOrchardSweepFailure(OrchardSweepTooSmallException(Zatoshi(30_000), Zatoshi(25_000))),
        )
        assertEquals(
            OrchardSweepFailure.TooSmall(Zatoshi(20_000), null),
            classifyOrchardSweepFailure(
                TransactionProposalNotCreatedException(OrchardSweepTooSmallException(Zatoshi(20_000), null))
            ),
        )
    }

    /** The SDK's own shortfall: it needs the fee plus one zatoshi to arrive, so the fee is need - 1. */
    @Test
    fun an_sdk_shortfall_is_too_small_with_the_fee_it_implies() {
        assertEquals(
            OrchardSweepFailure.TooSmall(Zatoshi(24_000), Zatoshi(25_000)),
            classifyOrchardSweepFailure(InsufficientFundsException(have = Zatoshi(24_000), need = Zatoshi(25_001))),
        )
        assertEquals(
            OrchardSweepFailure.TooSmall(Zatoshi(24_000), Zatoshi(25_000)),
            classifyOrchardSweepFailure(
                TransactionProposalNotCreatedException(
                    IllegalStateException("Insufficient balance (have 24000, need 25001 including fee)")
                )
            ),
        )
        assertEquals(
            OrchardSweepFailure.TooSmall(null, null),
            classifyOrchardSweepFailure(InsufficientFundsException()),
        )
    }

    @Test
    fun a_sweep_in_flight_is_pending() {
        assertEquals(
            OrchardSweepFailure.Pending,
            classifyOrchardSweepFailure(TransactionProposalNotCreatedException(OrchardSweepPendingException())),
        )
    }

    @Test
    fun the_scanner_not_being_ready_is_wait_for_sync() {
        listOf(
            "Note is ineligible for spending",
            "Sync required before proposing",
            "Must scan blocks first",
            "Wallet has not yet scanned any blocks",
        ).forEach { text ->
            assertEquals(
                OrchardSweepFailure.WaitForSync,
                classifyOrchardSweepFailure(TransactionProposalNotCreatedException(IllegalStateException(text))),
                text,
            )
        }
    }

    @Test
    fun anything_else_is_an_error() {
        listOf(
            "The Ironwood pool is not active yet",
            "The account has no Orchard full viewing key",
            "Proposal step 2 is not a sweep",
        ).forEach { text ->
            assertEquals(
                OrchardSweepFailure.Error,
                classifyOrchardSweepFailure(TransactionProposalNotCreatedException(IllegalStateException(text))),
                text,
            )
        }
        assertEquals(
            OrchardSweepFailure.Error,
            classifyOrchardSweepFailure(TransactionProposalNotCreatedException(OrchardSweepRefusedException("x"))),
        )
    }
}
