package co.electriccoin.zcash.ui.call

/**
 * Typed reason a voice call ended. Replaces the prior stringly-typed `reason: String`
 * so the UI can render an exhaustive `when` and the wire payload is centralized.
 *
 * [wireString] is the value carried in the HANGUP envelope payload — kept stable and
 * lowercase_snake so a remote peer (or a future protocol consumer) parses it predictably.
 */
sealed interface CallEndReason {
    data object UserEnded : CallEndReason
    data object Declined : CallEndReason
    data object Busy : CallEndReason
    data object PermissionDenied : CallEndReason
    data object IceFailed : CallEndReason
    data object Timeout : CallEndReason
    data object Glare : CallEndReason
    data object Shutdown : CallEndReason
    data object BackPressed : CallEndReason
    /** Peer sent us a HANGUP; [raw] is their (already-validated) payload string. */
    data class RemoteHangup(val raw: String) : CallEndReason
    /** Local media/SDP negotiation threw; [message] is the diagnostic. */
    data class SetupFailed(val message: String) : CallEndReason

    val wireString: String
        get() = when (this) {
            UserEnded -> "user_ended"
            Declined -> "declined"
            Busy -> "busy"
            PermissionDenied -> "permission_denied"
            IceFailed -> "ice_failed"
            Timeout -> "timeout"
            Glare -> "glare"
            Shutdown -> "shutdown"
            BackPressed -> "back_pressed"
            is RemoteHangup -> raw
            is SetupFailed -> "setup_failed"
        }

    /** Short human-facing label for the Ended toast. */
    val displayLabel: String
        get() = when (this) {
            UserEnded -> "Call ended"
            Declined -> "Call declined"
            Busy -> "Peer is busy"
            PermissionDenied -> "Microphone permission needed"
            IceFailed -> "Connection failed"
            Timeout -> "No answer"
            Glare -> "Call collision — please retry"
            Shutdown -> "Call ended"
            BackPressed -> "Call ended"
            is RemoteHangup -> "Call ended"
            is SetupFailed -> "Call setup failed"
        }
}
