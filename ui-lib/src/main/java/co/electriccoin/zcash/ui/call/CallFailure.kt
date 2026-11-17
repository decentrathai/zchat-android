package co.electriccoin.zcash.ui.call

/**
 * TURN-CREDS (fix-all 2.13.0): why a call that ended early ended, told honestly (pure; CallFailureReasonTest).
 *
 * - A setup timeout before the other phone answered is "No answer" ([CallEndReason.Timeout]), as before.
 * - A setup timeout after the answer is not "No answer": it is [CallEndReason.ConnectFailed], or
 *   [CallEndReason.NoRelay] when this phone gathered no relay candidate (ZChat's call relay was not available, for
 *   any reason: no credential, relay down, rejected credential), so only a direct connection was tried.
 * - ICE failing before the call is up is [CallEndReason.IceFailed], or [CallEndReason.NoRelay] without a relay
 *   candidate. ICE failing or lost in a live call is [CallEndReason.IceFailed].
 *
 * The HANGUP sent to the other phone does not change: NoRelay and ConnectFailed send "timeout" and are sent only by the
 * setup timeout, where Timeout is sent today; the ICE paths send no HANGUP.
 */
object CallFailure {
    /** The early end the call manager saw. */
    enum class Kind {
        /** The 45 s setup timeout fired while dialling, ringing or connecting. */
        SETUP_TIMEOUT,

        /** ICE reported FAILED. */
        ICE_FAILED,

        /** ICE stayed DISCONNECTED past the grace period of a live call. */
        ICE_LOST,
    }

    /** The call's stage when it ended. */
    enum class Stage {
        DIALLING,
        RINGING,
        CONNECTING,
        IN_CALL,
    }

    fun reasonFor(
        kind: Kind,
        stage: Stage,
        relayCandidateSeen: Boolean,
    ): CallEndReason =
        when (kind) {
            // Every setup-timeout answer sends "timeout", as Timeout always did.
            Kind.SETUP_TIMEOUT ->
                when {
                    stage == Stage.DIALLING || stage == Stage.RINGING -> CallEndReason.Timeout
                    relayCandidateSeen -> CallEndReason.ConnectFailed
                    else -> CallEndReason.NoRelay
                }
            Kind.ICE_FAILED, Kind.ICE_LOST ->
                if (stage == Stage.IN_CALL || relayCandidateSeen) CallEndReason.IceFailed else CallEndReason.NoRelay
        }
}
