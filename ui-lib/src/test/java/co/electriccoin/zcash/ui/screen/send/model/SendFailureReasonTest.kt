package co.electriccoin.zcash.ui.screen.send.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import org.junit.Test
import kotlin.test.assertEquals

/**
 * RED pin for the wallet Send screen's failure copy while Ironwood funds are CONFIRMING.
 *
 * The chat surfaces distinguish "your funds are in the sealed Orchard pool — migrate" from "your
 * Ironwood funds have arrived and are still confirming — wait". The wallet Send screen maps the
 * refusal to a single reason key (`REASON_ORCHARD_SEALED`), so a user whose refill is minutes from
 * confirming is told to migrate. Observed on the Seeker on 2026-09-07 during the refill's
 * confirmation window. The mapping is lifted into a pure function so it can be pinned here.
 */
class SendFailureReasonTest {
    @Test
    fun orchard_only_refusal_maps_to_the_sealed_pool_reason() {
        assertEquals(SendStage.REASON_ORCHARD_SEALED, SendStage.reasonFor(OrchardSealedException(Zatoshi(811_000))))
    }

    @Test
    fun confirming_ironwood_refusal_maps_to_its_own_reason() {
        val e = OrchardSealedException(Zatoshi(811_000), ironwoodPending = Zatoshi(1_095_000))

        assertEquals(SendStage.REASON_IRONWOOD_CONFIRMING, SendStage.reasonFor(e))
    }

    @Test
    fun pre_existing_reasons_are_unchanged() {
        assertEquals(SendStage.REASON_INSUFFICIENT_FUNDS, SendStage.reasonFor(InsufficientFundsException()))
        assertEquals(
            SendStage.REASON_PROPOSAL_NOT_CREATED,
            SendStage.reasonFor(TransactionProposalNotCreatedException(IllegalStateException("x"))),
        )
        assertEquals(SendStage.REASON_GENERIC, SendStage.reasonFor(IllegalStateException("anything else")))
    }
}
