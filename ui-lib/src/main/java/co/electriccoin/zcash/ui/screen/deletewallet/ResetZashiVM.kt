package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiResetLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ResetZashiVM(
    private val navigationRouter: NavigationRouter,
    private val aiAccountAccess: AiAccountAccess
) : ViewModel() {
    private val isKeepFilesChecked = MutableStateFlow(true)

    // AI-KEY-T (spec 5.3): the stored state decides at once; the one check of this screen can only change it afterwards.
    private val aiLine = MutableStateFlow(AiResetLine.PLAIN)

    private val aiCheck = viewModelScope.launch { aiAccountAccess.resetScreenCheck { aiLine.value = it } }

    private val confirmWaiting = AtomicBoolean(false)

    val state: StateFlow<ResetZashiState> =
        combine(isKeepFilesChecked, aiLine) { keep, line ->
            createState(
                isKeepFilesChecked = keep,
                aiLine = line
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    isKeepFilesChecked = isKeepFilesChecked.value,
                    aiLine = aiLine.value,
                )
        )

    private fun createState(
        isKeepFilesChecked: Boolean,
        aiLine: AiResetLine
    ) =
        ResetZashiState(
            onBack = { navigationRouter.back() },
            checkboxState =
                CheckboxState(
                    title = stringRes(R.string.delete_wallet_checkbox_title),
                    subtitle = stringRes(keepDataWarning(isKeepFilesChecked, aiLine)),
                    isChecked = isKeepFilesChecked,
                    onClick = ::onCheckboxToggled
                ),
            buttonState =
                ButtonState(
                    text = stringRes(R.string.delete_wallet_button),
                    // Confirm is ALWAYS enabled. The checkbox is an OPTIONAL "keep encrypted metadata
                    // backup" toggle (default on) — NOT a mandatory acknowledgement. Gating Confirm on
                    // it would block reset for anyone who legitimately unchecks it to wipe metadata too.
                    // Round 3: the tap carries the line of the very state the button was drawn with.
                    onClick = { onConfirmClicked(aiLine.takeUnless { isKeepFilesChecked }) }
                ),
            staysText = keepDataStays(isKeepFilesChecked),
        )

    private fun onCheckboxToggled() = isKeepFilesChecked.update { !it }

    /**
     * W-97: with the box unticked Confirm waits for the check, which has its own limit. Round 3: the Reset is told the
     * unreadable warning was shown only when it was on the screen at the tap ([lineOnTheScreen]) and still stands. One that
     * arrives after the tap has been seen by nobody: the screen stays with it, for a second tap.
     */
    private fun onConfirmClicked(lineOnTheScreen: AiResetLine?) {
        if (isKeepFilesChecked.value) {
            forwardToTheSheet(unreadableShown = false)
        } else if (confirmWaiting.compareAndSet(false, true)) {
            viewModelScope.launch {
                try {
                    aiCheck.join()
                    val unreadable = !isKeepFilesChecked.value && aiLine.value == AiResetLine.UNREADABLE
                    if (!unreadable || lineOnTheScreen == AiResetLine.UNREADABLE) forwardToTheSheet(unreadable)
                } finally {
                    confirmWaiting.set(false)
                }
            }
        }
    }

    private fun forwardToTheSheet(unreadableShown: Boolean) {
        navigationRouter.forward(
            ResetZashiConfirmationArgs(
                keepFiles = isKeepFilesChecked.value,
                aiUnreadableWarningShown = unreadableShown
            )
        )
    }
}

/** ZC-090-R: the paragraph of what stays after a Reset, shown under the warning with the box unticked only. */
@androidx.annotation.StringRes
internal fun keepDataStays(isChecked: Boolean): Int? = if (isChecked) null else R.string.delete_wallet_stays_unchecked

/** P2h — the warning under the "keep app data" checkbox for its current state. */
@androidx.annotation.StringRes
internal fun keepDataWarning(isChecked: Boolean): Int =
    if (isChecked) R.string.delete_wallet_checkbox_warning_checked else R.string.delete_wallet_checkbox_warning_unchecked

/** AI-KEY-T (spec 5.3): the same warning, with the box unticked the one of five that fits. */
@androidx.annotation.StringRes
internal fun keepDataWarning(
    isChecked: Boolean,
    aiLine: AiResetLine
): Int =
    when {
        isChecked -> keepDataWarning(true)
        aiLine == AiResetLine.UNREADABLE -> R.string.delete_wallet_checkbox_warning_unchecked_ai_unreadable
        aiLine == AiResetLine.KEPT -> R.string.delete_wallet_checkbox_warning_unchecked_ai_kept
        aiLine == AiResetLine.KEPT_PARKED -> R.string.delete_wallet_checkbox_warning_unchecked_ai_kept_parked
        aiLine == AiResetLine.LINKED -> R.string.delete_wallet_checkbox_warning_unchecked_ai_linked
        else -> keepDataWarning(false)
    }

// Spec 5.3: the linking try or the re-check, at most 8 seconds; Confirm stays enabled.
internal const val AI_CHECK_LIMIT_MS = 8_000L
