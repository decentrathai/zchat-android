package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.derivePrivacyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 8 D7 — the pure rules behind "a wallet with Orchard left can still send while its own change confirms".
 *
 * [isTransientWait] is the one rule the block-aware retries (group, automatic / time-lock / status, the 1:1 queue)
 * wait on: the "…confirm on-chain…" shortfall and the sealed-Orchard refusal while Ironwood confirms are waits;
 * a genuine shortfall, the "move your old balance" refusal and every other refusal are not. The first shortfall or
 * sealed-Orchard refusal in the cause chain decides, as the old per-ViewModel rule did for the shortfall.
 *
 * The Stranded funds copy (#10): "your first Ironwood funds have arrived" only for a deposit from someone else with
 * none of our own change pending; and the send gate lets a wallet whose only pending Ironwood is its own change
 * through to the queue (isWaitingForOwnChange). All amounts are synthetic.
 */
class TransientWaitRuleTest {
    private val orchard = Zatoshi(300_000)
    private val pending = Zatoshi(200_000)

    private fun stillConfirming() = InsufficientFundsException(CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE)

    @Test
    fun `the still-confirming shortfall and the sealed refusal with Ironwood pending are waits`() {
        assertTrue(isTransientWait(stillConfirming()))
        assertTrue(isTransientWait(OrchardSealedException(orchard, ironwoodPending = pending)))
        assertTrue(
            "a wrapped refusal is found down the cause chain",
            isTransientWait(IllegalStateException("repository", OrchardSealedException(orchard, ironwoodPending = pending))),
        )
        assertTrue(
            "D1: a shortfall found after the submit keeps its nothing-stored proof as the cause and is still a wait",
            isTransientWait(
                InsufficientFundsException(
                    CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
                    cause = SubmitRejectedException("Insufficient balance", nothingStored = true),
                ),
            ),
        )
    }

    @Test
    fun `a genuine shortfall, the move-your-balance refusal and other refusals are not waits`() {
        assertFalse(isTransientWait(InsufficientFundsException(CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE)))
        assertFalse(isTransientWait(InsufficientFundsException(CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE)))
        assertFalse(isTransientWait(InsufficientFundsException()))
        assertFalse(isTransientWait(OrchardSealedException(orchard)))
        assertFalse(isTransientWait(PublicCrossingException(orchard, Zatoshi(0))))
        assertFalse(isTransientWait(SubmitRejectedException("rejected", nothingStored = true)))
        assertFalse(isTransientWait(IllegalStateException("anything else")))
    }

    @Test
    fun `the first shortfall or sealed refusal in the chain decides`() {
        assertFalse(
            "an outer genuine shortfall is not turned into a wait by a wait further down",
            isTransientWait(
                InsufficientFundsException(
                    CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
                    cause = OrchardSealedException(orchard, ironwoodPending = pending),
                ),
            ),
        )
    }

    @Test
    fun `the Stranded funds copy says first Ironwood funds only for a deposit with none of our own change pending`() {
        assertEquals(
            ChatSendCopy.IRONWOOD_FIRST_FUNDS_CONFIRMING,
            SendMessageState.NeedsIronwoodMigration(orchard, ironwoodPending = pending).message,
        )
        assertEquals(
            OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE,
            SendMessageState.NeedsIronwoodMigration(orchard, ironwoodPending = pending, ironwoodChangePending = pending).message,
        )
        assertEquals(
            "a deposit and our own change together are not \"first funds\"",
            OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE,
            SendMessageState
                .NeedsIronwoodMigration(orchard, ironwoodPending = pending, ironwoodChangePending = Zatoshi(1_000))
                .message,
        )
        assertEquals(ChatSendCopy.ORCHARD_POOL_SEALED, SendMessageState.NeedsIronwoodMigration(orchard).message)
        assertTrue(
            "the own-change copy has a Spanish resource",
            ChatSendCopy.localizedResource(OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE) != null,
        )
    }

    @Test
    fun `only a stranded wallet whose pending Ironwood includes its own change waits for it`() {
        fun status(
            orchardAvailable: Zatoshi,
            ironwoodAvailable: Zatoshi,
            changePending: Zatoshi,
            valuePending: Zatoshi,
        ) = derivePrivacyStatus(
            orchardBalance = orchardAvailable,
            ironwoodBalance = ironwoodAvailable + changePending + valuePending,
            saplingBalance = Zatoshi(0),
            transparentBalance = Zatoshi(0),
            orchardAvailable = orchardAvailable,
            ironwoodAvailable = ironwoodAvailable,
            ironwoodChangePending = changePending,
        )
        val zero = Zatoshi(0)
        assertTrue(status(orchard, zero, pending, zero).isWaitingForOwnChange)
        assertFalse("a first deposit keeps the Stranded funds dialog", status(orchard, zero, zero, pending).isWaitingForOwnChange)
        assertFalse("nothing confirming: move the old balance", status(orchard, zero, zero, zero).isWaitingForOwnChange)
        assertFalse("Ironwood spendable: not stranded at all", status(orchard, pending, pending, zero).isWaitingForOwnChange)
        assertFalse("no Orchard: not stranded at all", status(zero, zero, pending, zero).isWaitingForOwnChange)
    }
}
