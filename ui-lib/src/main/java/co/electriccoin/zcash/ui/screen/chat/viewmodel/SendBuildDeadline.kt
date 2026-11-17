package co.electriccoin.zcash.ui.screen.chat.viewmodel

import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1276: how long one build of a chat send may run before its submit starts. The build runs in NonCancellable, so a
 * parked build would otherwise hold its row live and Sending for good, with nothing paid.
 *
 * This stretch holds only the wallet's proposal and, for a payment, the user's spend authentication: proving, storing and
 * broadcasting all come after the submit started and are never timed. Fifteen minutes is far beyond a slow phone with a
 * large wallet. If it still cuts a build, the cost is one Retry tap: the late build pays nothing ([SendBuildDeadline]).
 * The timer runs on Dispatchers.Main, whose delay does not count while the phone is in deep sleep; the build stands
 * still then too, so a sleeping phone is not cut.
 */
internal const val SEND_BUILD_LIMIT_MS = 15L * 60L * 1_000L

/**
 * ZC-1290: how long one row may stay in flight before any submit of its message started, counted once from its first start
 * and carried across queue retries, note waits and its owed key exchange: an hour, about four times the real wait for a
 * deposit's ten confirmations. A row a restart finds waiting gets what is left of it by the row's own time.
 */
internal const val SEND_TOTAL_LIMIT_MS = 60L * 60L * 1_000L

/** ZC-1290: when the total passes while the owed key exchange's submit runs, how soon it looks again. */
internal const val SEND_TOTAL_RECHECK_MS = 60L * 1_000L

/**
 * ZC-1276: the one decision between a send's submit and its time limit, taken atomically across the build's thread and the
 * timer's. Exactly one side wins: a build that claimed the submit is never settled as not sent (it may have gone out; the
 * SendAttemptProcess rules hold), and a settled build never submits, however late it wakes up.
 */
internal class SendBuildDeadline {
    private val state = AtomicInteger(BUILDING)

    /** The limit won: the row was settled as not sent, and this run no longer owns the row, which Retry may have taken. */
    val expired: Boolean
        get() = state.get() == EXPIRED

    /** True when the submit may start; false when the limit won, and nothing may be stored or broadcast. */
    fun claimSubmit(): Boolean {
        state.compareAndSet(BUILDING, SUBMIT_CLAIMED)
        return state.get() == SUBMIT_CLAIMED
    }

    /** True when the limit won, so the caller settles the row; false when the submit was claimed first. */
    fun expire(): Boolean = state.compareAndSet(BUILDING, EXPIRED)

    /** ZC-1290: the message's own submit started; the limit no longer has any say. */
    val submitClaimed: Boolean
        get() = state.get() == SUBMIT_CLAIMED

    /**
     * ZC-1289: the owed key exchange's submit may start: false when the limit won. Unlike [claimSubmit] this ends
     * ([endLeg]), because the message's own build follows it; the limit waits while it holds.
     */
    fun claimLeg(): Boolean {
        state.compareAndSet(BUILDING, LEG_SUBMITTING)
        return state.get() == LEG_SUBMITTING
    }

    /** ZC-1289: the key exchange's submit ended, or its attempt ended with nothing sent. */
    fun endLeg() {
        state.compareAndSet(LEG_SUBMITTING, BUILDING)
    }

    /** ZC-1289: what a parked key exchange holds (its stamp, its lock), for the limit to let go of: that run may never end. */
    @Volatile var onExpiredLeg: (() -> Unit)? = null

    fun releaseExpiredLeg() {
        onExpiredLeg?.invoke()
        onExpiredLeg = null
    }

    private companion object {
        const val BUILDING = 0
        const val SUBMIT_CLAIMED = 1
        const val EXPIRED = 2
        const val LEG_SUBMITTING = 3
    }
}

/** ZC-1276: a build woke up after its time limit settled the row: nothing was stored or broadcast. */
internal class SendBuildExpiredException : IllegalStateException("The build woke up after its time limit")
