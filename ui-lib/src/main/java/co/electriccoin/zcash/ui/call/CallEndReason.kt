package co.electriccoin.zcash.ui.call

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R

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

    /**
     * TURN-CREDS: the call could not connect and this phone gathered no relay candidate, so ZChat's call relay was not
     * available and only a direct connection was tried (CallFailure). Sends "timeout", only where Timeout was sent
     * before (the setup timeout), so the other phone and ZChat 2.12.4 receive the same HANGUP as before.
     */
    data object NoRelay : CallEndReason

    /**
     * TURN-CREDS: the other phone answered but the call did not connect within the setup time (CallFailure). Sends
     * "timeout", exactly as Timeout did in this case before.
     */
    data object ConnectFailed : CallEndReason

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
            NoRelay -> "timeout"
            ConnectFailed -> "timeout"
        }

    /** Short label for the Ended toast (ui/common/values{,-es}/call_screen_strings.xml). */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            UserEnded -> R.string.call_ended
            Declined -> R.string.call_ended_declined
            Busy -> R.string.call_ended_busy
            PermissionDenied -> R.string.call_ended_mic_permission
            IceFailed -> R.string.call_ended_connection_failed
            Timeout -> R.string.call_ended_no_answer
            Glare -> R.string.call_ended_glare
            Shutdown -> R.string.call_ended
            BackPressed -> R.string.call_ended
            is RemoteHangup -> R.string.call_ended
            is SetupFailed -> R.string.call_ended_setup_failed
            NoRelay -> R.string.call_ended_no_relay
            ConnectFailed -> R.string.call_ended_connect_failed
        }

    /**
     * ZC-411: how long CallOverlay keeps this reason's notice on screen, in milliseconds. The rule, pinned by
     * EndedCallNoticeIsLongEnoughToReadTest: at least about 0.3 s per word of the [labelRes] text in every shipped
     * language, never under 2 s and never over 15 s. A reason whose text grows, and a new language, must keep it.
     * This is the overlay's time only: the call state stays Ended for VoiceCallManager's IDLE_RESET_DELAY_MS as before.
     */
    val noticeHoldMs: Long
        get() =
            when (this) {
                // 21 words in English, 31 in Spanish.
                NoRelay -> NO_RELAY_NOTICE_MS
                // Two to four words in English, 7 in Spanish.
                PermissionDenied, Glare -> SENTENCE_NOTICE_MS
                else -> DEFAULT_NOTICE_MS
            }

    companion object {
        /** The short notices (call_ended, call_ended_no_answer: up to 6 words in any language), as before ZC-411. */
        const val DEFAULT_NOTICE_MS = 2_000L

        /** A one-sentence notice of up to 10 words. */
        const val SENTENCE_NOTICE_MS = 3_000L

        /** The no-relay explanation: 31 words in Spanish need about 9.3 s. */
        const val NO_RELAY_NOTICE_MS = 10_000L
    }
}
