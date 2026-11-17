package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.PlanProbe
import co.electriccoin.zcash.ui.common.model.ProposalInputOutpoint

/**
 * M2 (fix-all 2.13.0) — whether the wallet can pay both transactions of a first on-chain message to a contact we hold
 * no key for: our key exchange (KEX), then the message. The send pays the KEX first and alone; if the message cannot
 * follow, the user has paid for half a send (the Seeker paid 17,000 of a 34,000 quote and the message then failed).
 */
sealed interface FirstMessagePlan {
    /** Both legs can be paid: from different notes now, or the message from the KEX's change once it confirms. */
    data object Fundable : FirstMessagePlan

    /** The wallet's notes cannot pay both legs (or what they would spend could not be read). Adding ZEC clears it. */
    data object NotFundable : FirstMessagePlan

    /** A leg is short only because Ironwood is still confirming: wait for it, nothing to add. */
    data object WaitForFunds : FirstMessagePlan

    /** The check could not answer ([cause] when it threw). Fail closed: nothing is paid. */
    data class Unchecked(
        val cause: Throwable? = null,
    ) : FirstMessagePlan
}

/**
 * M2 — the one question the first-message send asks before it pays anything: the plan check for a first message of
 * [messageMemos] memos at [amountPerOutput] to [destination]. ChatViewModel gets it from Koin
 * (screen.chat.usecase.ProbeFirstMessagePlanUseCase); the courtesy check before the question and the gate in the send
 * ask the same one.
 */
fun interface FirstMessagePlanCheck {
    suspend fun check(
        destination: String,
        messageMemos: Int,
        amountPerOutput: Zatoshi,
    ): FirstMessagePlan
}

/** M2 — the pure rule over the two never-stored probes of the current wallet, one per leg. */
object FirstMessagePlanFunding {
    /**
     * Fundable only when both legs were built and either (1) their inputs are disjoint, so two different note sets pay
     * them now, or (2) one Ironwood change note of the KEX covers [messageAlone] (the message's own minimum) by
     * [spendableCovers] (ZC-1813: exactly, or with the marginal fee to spare), so it pays the message once it confirms
     * (the send waits up to 8 blocks for it; own change needs 3).
     *
     * Everything else is NotFundable (fail closed): a Short leg, unreadable inputs, a proposal that spends nothing, or
     * an input without txid or output index (it may be the same note as any other). Accepted false refusals: another
     * disjoint selection may exist, or the change plus another small note might pay the message; nothing is paid on a
     * refusal and adding ZEC always clears it.
     */
    fun decide(
        kex: PlanProbe,
        message: PlanProbe,
        messageAlone: Zatoshi,
    ): FirstMessagePlan {
        if (kex !is PlanProbe.Built || message !is PlanProbe.Built) return FirstMessagePlan.NotFundable
        val kexInputs = kex.inputs
        val messageInputs = message.inputs
        if (kexInputs.isNullOrEmpty() || messageInputs.isNullOrEmpty()) return FirstMessagePlan.NotFundable
        if (disjoint(kexInputs, messageInputs)) return FirstMessagePlan.Fundable
        val change = kex.change ?: return FirstMessagePlan.NotFundable
        // ZC-1813: the change follows the ZC-1417 rule (exactly the message's cost, or the cost plus the marginal fee): a
        // 17,001..21,999 change for a 17,000 message leaves the message a change the SDK refuses, so the KEX would be paid
        // for a message that cannot follow.
        return if (change.any { spendableCovers(it, messageAlone.value) }) FirstMessagePlan.Fundable else FirstMessagePlan.NotFundable
    }

    private fun disjoint(
        first: List<ProposalInputOutpoint>,
        second: List<ProposalInputOutpoint>,
    ): Boolean {
        val known = (first + second).all { it.txIdHex != null && it.index != null }
        if (!known) return false
        val firstNotes = first.mapTo(HashSet()) { Triple(it.txIdHex, it.index, it.pool) }
        return second.none { Triple(it.txIdHex, it.index, it.pool) in firstNotes }
    }
}
