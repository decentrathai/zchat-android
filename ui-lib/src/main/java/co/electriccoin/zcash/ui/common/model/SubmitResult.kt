package co.electriccoin.zcash.ui.common.model

sealed interface SubmitResult {
    data class Success(
        val txIds: List<String>
    ) : SubmitResult

    data class Failure(
        val txIds: List<String>,
        val code: Int,
        val description: String?
    ) : SubmitResult

    /**
     * MONEY-CRITICAL: NOT DELIVERED YET — this is not "nothing was sent".
     *
     * Every output failed with a gRPC/Tor transport error. SDK 3.0.0 does not ask the server whether
     * it already has the transaction on this path (`isTransactionKnownToServer` only runs when the
     * server answered with a code), the transaction is already stored in the wallet with its submit
     * plan, and the SDK re-broadcasts it on every sync until it is mined or expires. So it may land.
     *
     * Never re-arm an automatic sender or a "Pay" affordance on it, and never word it as a definite
     * failure: report it as submitted but not confirmed and let the next sync settle the truth.
     */
    data class GrpcFailure(
        val txIds: List<String>
    ) : SubmitResult

    /**
     * Some outputs were accepted and some were not. MONEY-CRITICAL: money HAS moved, so this is never "try again".
     *
     * [unacceptedLegsResent] (T3-first, fix-all 2.13.0): true when every output that was not accepted failed on the
     * transport (a gRPC-class failure). F2's one submit path stores every leg in the wallet before any submit and
     * hands each one to the server, so the SDK keeps rebroadcasting such a leg until it is mined or expires: the send
     * is sent and waiting for the network, never "Send Failed" (train T2 F2-3: a TEX leg 1 that failed on the
     * transport while leg 2 was accepted, both mined). False when an output was refused by the server itself, or was
     * never attempted, and for every Partial built without the flag.
     */
    data class Partial(
        val txIds: List<String>,
        val statuses: List<String>,
        val unacceptedLegsResent: Boolean = false,
    ) : SubmitResult

    /**
     * MONEY-CRITICAL: the outcome is UNKNOWN and money may already have left the wallet.
     *
     * Produced when settlement threw instead of returning a classified result, AND the throw was not
     * one of the provably-pre-broadcast ones (`NothingBroadcastException`: acquiring the synchronizer,
     * or deriving the spending key before SDK creation is invoked — those stay a [Failure]).
     * SDK creation can store transactions before returning its flow, so its errors are checked against the
     * wallet's own transaction list: new own spends give this result carrying their ids, none gives a
     * [Failure], and only an unreadable list gives this result with no ids.
     * In SDK 3.0.0 the network send happens while that flow is
     * COLLECTED, and each result is emitted only after `client.dispose()` and an encrypted-prefs write
     * in `finally` blocks, so a throw during collect — even one before anything was emitted — can
     * follow a transaction lightwalletd already accepted. `settleSubmitFlow` returns that case as this
     * result with [txIds] set to the ids collected before the throw (possibly empty: the id of the
     * transaction being sent is never emitted). A throw in the proposal repositories after the data
     * source returned is reported the same way, with no ids. None of it can be read as "nothing was
     * sent" — the transaction may well be on the wire.
     *
     * Therefore this is NOT a [Failure]: it must never be shown as "Send Failed / try again", and it
     * must never arm an automatic retry or re-arm a durable "not sent yet" marker. Report it as sent
     * but unconfirmed ("check your transaction history") and let the next sync settle the truth.
     */
    data class SentUnconfirmed(
        val txIds: List<String>,
        val description: String?
    ) : SubmitResult

    companion object {
        /**
         * Pure decision that maps a batch of per-output submit outcomes to a single [SubmitResult].
         *
         * MONEY-CRITICAL: this is what decides whether the ZCHAT direct-send flow reports "sent". A
         * PARTIAL or total FAILURE must NEVER be mislabeled a [Success] (that would show a false
         * "Message sent" for a payment that never fully left the wallet), and — conversely — a
         * [Partial] must NOT be treated as a total failure by the classifier (at least one output
         * DID land on-chain, so re-submitting the whole batch would double-charge that output).
         *
         * @param successCount number of outputs that submitted successfully
         * @param totalCount total number of outputs attempted (== txIds.size)
         * @param resubmittableFailures one flag for each non-success output: its grpcError flag for a
         *   failed output, and `false` for an output the SDK never attempted (it stays AwaitingPlan and
         *   the SDK never re-broadcasts it, e.g. leg 2 of a TEX send after leg 1 failed). When EVERY
         *   non-success output is a gRPC/network error the whole batch is a [GrpcFailure] (not delivered
         *   yet: the SDK has stored it and re-broadcasts it until mined or expired); a never-attempted
         *   output makes it a [Failure] that keeps every id
         * @param txIds tx ids for the batch
         * @param statuses human-readable per-output status strings (for [Partial])
         * @param errCode last non-grpc failure code (for [Failure])
         * @param errDesc last non-grpc failure description (for [Failure])
         */
        @Suppress("LongParameterList")
        fun classify(
            successCount: Int,
            totalCount: Int,
            resubmittableFailures: List<Boolean>,
            txIds: List<String>,
            statuses: List<String>,
            errCode: Int,
            errDesc: String?
        ): SubmitResult =
            when (successCount) {
                0 ->
                    if (resubmittableFailures.all { it }) {
                        GrpcFailure(txIds = txIds)
                    } else {
                        Failure(txIds = txIds, code = errCode, description = errDesc)
                    }

                totalCount -> Success(txIds = txIds)

                // successCount in 1..totalCount-1: at least one output already landed on-chain, so this
                // is a PARTIAL regardless of whether the remaining failures are retryable gRPC errors.
                // GrpcFailure is reserved for successCount==0 (nothing CONFIRMED delivered yet; the SDK
                // still re-broadcasts the stored transactions). It must never be read as "safe to
                // re-submit the whole batch" — that would DOUBLE-CHARGE whatever lands.
                else ->
                    Partial(
                        txIds = txIds,
                        statuses = statuses,
                        unacceptedLegsResent = resubmittableFailures.all { it },
                    )
            }
    }
}
