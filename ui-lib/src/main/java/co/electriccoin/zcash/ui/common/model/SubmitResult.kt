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

    data class GrpcFailure(
        val txIds: List<String>
    ) : SubmitResult

    data class Partial(
        val txIds: List<String>,
        val statuses: List<String>
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
         * @param resubmittableFailures grpcError flag for each non-success output; when EVERY
         *   non-success output is a retryable gRPC/network error the whole batch is a [GrpcFailure]
         *   (nothing landed, safe to retry)
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
                // GrpcFailure is reserved for successCount==0 ("nothing landed, safe to retry the whole
                // batch"); returning it here would let the direct-send flow (which throws on GrpcFailure
                // and re-submits) DOUBLE-CHARGE the output that already landed.
                else -> Partial(txIds = txIds, statuses = statuses)
            }
    }
}
