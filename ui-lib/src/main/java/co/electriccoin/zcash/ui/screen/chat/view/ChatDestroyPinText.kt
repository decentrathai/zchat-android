package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.pluralStringRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

// Below two minutes the wait is named in seconds, so no text says "1 minutes".
private const val SECONDS_SHOWN_BELOW = 2 * SECONDS_PER_MINUTE

/**
 * FD-8a / FD-8c — what the chat list's Destroy All verify dialog says about a destroy-PIN check; null for a right PIN.
 * A lockout names the remaining wait rounded UP (seconds below two minutes, else whole minutes), so the text never
 * promises a retry earlier than the lockout allows.
 */
internal fun destroyPinVerifyErrorText(result: DestroyPinVerifyResult): StringResource? =
    when (result) {
        DestroyPinVerifyResult.Success -> null
        is DestroyPinVerifyResult.Failed ->
            if (result.attemptsRemaining > 0) {
                pluralStringRes(R.plurals.destroy_pin_incorrect_attempts, result.attemptsRemaining, result.attemptsRemaining)
            } else {
                stringRes(R.string.destroy_pin_incorrect)
            }
        is DestroyPinVerifyResult.LockedOut -> {
            val seconds = ((result.remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1L)
            if (seconds < SECONDS_SHOWN_BELOW) {
                stringRes(R.string.destroy_locked_seconds, seconds.toInt())
            } else {
                stringRes(R.string.destroy_locked_minutes, ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt())
            }
        }
    }
