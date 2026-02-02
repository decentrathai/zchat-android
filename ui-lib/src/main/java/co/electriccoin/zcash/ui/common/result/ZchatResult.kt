package co.electriccoin.zcash.ui.common.result

/**
 * Result type for operations that can fail.
 * Based on Boris Cherny's TypeScript principles - explicit error handling.
 *
 * Usage:
 * ```kotlin
 * suspend fun sendMessage(content: String): ZchatResult<TransactionId, SendError> {
 *     return try {
 *         val txid = synchronizer.send(content)
 *         ZchatResult.Success(txid)
 *     } catch (e: Exception) {
 *         ZchatResult.Failure(SendError.fromException(e))
 *     }
 * }
 *
 * // Caller handles both cases
 * sendMessage(content).fold(
 *     onSuccess = { txid -> showSuccess("Sent: $txid") },
 *     onFailure = { error -> showError(error.message) }
 * )
 * ```
 */
sealed class ZchatResult<out T, out E> {
    /**
     * Represents a successful operation with data.
     */
    data class Success<T>(val data: T) : ZchatResult<T, Nothing>()

    /**
     * Represents a failed operation with an error.
     */
    data class Failure<E>(val error: E) : ZchatResult<Nothing, E>()

    /**
     * Returns true if this is a Success.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns true if this is a Failure.
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Fold over the result, handling both success and failure cases.
     * This is the primary way to handle results - ensures exhaustive handling.
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (E) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(error)
    }

    /**
     * Transform the success value, leaving failures unchanged.
     */
    inline fun <R> map(transform: (T) -> R): ZchatResult<R, E> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    /**
     * Transform the error value, leaving successes unchanged.
     */
    inline fun <R> mapError(transform: (E) -> R): ZchatResult<T, R> = when (this) {
        is Success -> this
        is Failure -> Failure(transform(error))
    }

    /**
     * Chain operations that return Results.
     */
    inline fun <R> flatMap(transform: (T) -> ZchatResult<R, @UnsafeVariance E>): ZchatResult<R, E> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }

    /**
     * Get the success value or null if failure.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    /**
     * Get the success value or a default if failure.
     */
    inline fun getOrElse(default: () -> @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> default()
    }

    /**
     * Get the error or null if success.
     */
    fun errorOrNull(): E? = when (this) {
        is Success -> null
        is Failure -> error
    }

    /**
     * Execute action only on success.
     */
    inline fun onSuccess(action: (T) -> Unit): ZchatResult<T, E> {
        if (this is Success) action(data)
        return this
    }

    /**
     * Execute action only on failure.
     */
    inline fun onFailure(action: (E) -> Unit): ZchatResult<T, E> {
        if (this is Failure) action(error)
        return this
    }

    companion object {
        /**
         * Create a success result.
         */
        fun <T> success(data: T): ZchatResult<T, Nothing> = Success(data)

        /**
         * Create a failure result.
         */
        fun <E> failure(error: E): ZchatResult<Nothing, E> = Failure(error)

        /**
         * Wrap a nullable value into a Result.
         * Returns Failure with the provided error if null.
         */
        fun <T, E> fromNullable(value: T?, error: () -> E): ZchatResult<T, E> =
            if (value != null) Success(value) else Failure(error())

        /**
         * Run a block and catch exceptions, converting to Result.
         */
        inline fun <T> runCatching(block: () -> T): ZchatResult<T, Throwable> =
            try {
                Success(block())
            } catch (e: Throwable) {
                Failure(e)
            }

        /**
         * Run a suspending block and catch exceptions, converting to Result.
         */
        suspend inline fun <T> runCatchingSuspend(crossinline block: suspend () -> T): ZchatResult<T, Throwable> =
            try {
                Success(block())
            } catch (e: Throwable) {
                Failure(e)
            }
    }
}

/**
 * Combine two results. If both succeed, apply the transform.
 * If either fails, return the first failure.
 */
inline fun <T1, T2, R, E> ZchatResult<T1, E>.zip(
    other: ZchatResult<T2, E>,
    transform: (T1, T2) -> R
): ZchatResult<R, E> = when (this) {
    is ZchatResult.Success -> when (other) {
        is ZchatResult.Success -> ZchatResult.Success(transform(this.data, other.data))
        is ZchatResult.Failure -> other
    }
    is ZchatResult.Failure -> this
}

/**
 * Convert a list of Results into a Result of list.
 * Fails fast on first error.
 */
fun <T, E> List<ZchatResult<T, E>>.sequence(): ZchatResult<List<T>, E> {
    val results = mutableListOf<T>()
    for (result in this) {
        when (result) {
            is ZchatResult.Success -> results.add(result.data)
            is ZchatResult.Failure -> return result
        }
    }
    return ZchatResult.Success(results.toList())
}
