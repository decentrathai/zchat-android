package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyKeepSave
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the Destroy PIN set-up shows (spec 5.3): the step and its inputs. */
data class DestroyPinSetupUiState(
    val step: Int = 1,
    val hasPin: Boolean = false,
    val choice: DestroyChoice = DestroyChoice.EVERYTHING,
    val contactsTicked: Boolean = false,
    val advancedOpen: Boolean = false,
    val pin: String = "",
    val confirm: String = "",
    val message: Int? = null,
    val proofNeeded: Boolean = false,
    val saving: Boolean = false,
    val notice: Int? = null,
    val finished: Boolean = false,
    val left: Boolean = false,
) {
    val saveEnabled: Boolean get() = pin.isNotEmpty() && confirm.isNotEmpty() && !saving
}

/**
 * DESTROY-ADV-P (register ZC-722; owner W-52: every choice about Destroy All is made when the Destroy PIN is set up;
 * spec revision 2c, 5.3 to 5.5): the Destroy PIN set-up in three steps, as a plain state holder a JVM test can drive.
 *
 *  1. what Destroy All does (step 1); 2. what it erases, the choice block (step 2); 3. the PIN (step 3).
 *  - S2: step 3 cannot be reached, and Save writes nothing, unless step 2 was shown in this visit.
 *  - S3: ONE write: DestroyManager makes the list's key for a kept choice (nothing is written when it cannot), then
 *    ONE setDestroySetup(pin, keep), then holds the choice with the current wallet-scope generation.
 *  - S5 (B2): a FIRST Save that carries the tick asks for the phone's screen lock when the phone has one: the holder
 *    reports [DestroyPinSetupUiState.proofNeeded] and writes nothing until [onProofResult]. A default first set-up and
 *    a change of an existing PIN (whose screen lock was asked before anything was shown) ask nothing here.
 *  - S6: after Save the set-up is finished and returns; it never leads into a Destroy.
 *  - S8: Save is offered as soon as both PIN fields hold something; a short PIN and two different PINs are said.
 */
class DestroyPinSetupFlow(
    private val zchatPreferences: ZchatPreferences,
    private val destroyManager: DestroyManager,
    private val phoneHasScreenLock: () -> Boolean,
) {
    private val mutableState = MutableStateFlow(DestroyPinSetupUiState())
    val state: StateFlow<DestroyPinSetupUiState> = mutableState.asStateFlow()

    // The step and whether the choice (step 2) was shown in this visit: the rule S2 reads these, not the UI state.
    private var step = 1
    private var choiceShown = false
    private var savedKeepsContacts = false

    // ZC-1043: set once the user touched the choice (step 2); a read of the saved choice that answers later never undoes it.
    @Volatile
    private var choiceTouched = false

    /** Reads whether a PIN exists and the saved choice (off the main thread). A read that throws shows "everything". */
    fun load() {
        val hasPin = runCatching { zchatPreferences.hasDestroyPin() }.getOrDefault(false)
        savedKeepsContacts = hasPin && CONTACTS in destroyManager.reloadDestroyKeep()
        update {
            if (choiceTouched) {
                it.copy(hasPin = hasPin)
            } else {
                it.copy(
                    hasPin = hasPin,
                    choice = if (savedKeepsContacts) DestroyChoice.KEEP_SOME else DestroyChoice.EVERYTHING,
                    contactsTicked = savedKeepsContacts,
                )
            }
        }
    }

    /** Step 1 -> step 2 ("Set Up PIN Now" / "Change the Destroy PIN"). */
    fun onSetUp() {
        if (step != 1) return
        step = 2
        choiceShown = true
        update { it.copy(advancedOpen = savedKeepsContacts, message = null) }
    }

    /** Step 2 -> step 3 ("Continue"). */
    fun onContinue() {
        if (step != 2 || !choiceShown) return
        step = 3
        update { it.copy(message = null) }
    }

    /** System Back and "Cancel": one step back; from step 1 the set-up is left. Nothing is written. */
    fun onBack() {
        when (step) {
            1 -> update { it.copy(left = true) }
            else -> {
                step -= 1
                update { it.copy(message = null, proofNeeded = false, pin = "", confirm = "") }
            }
        }
    }

    fun onCancel() = onBack()

    /** "Skip for Now": leaves, writes nothing. */
    fun onSkip() = update { it.copy(left = true) }

    fun onToggleAdvanced() = update { it.copy(advancedOpen = !it.advancedOpen) }

    fun onChooseEverything() {
        choiceTouched = true
        update { it.copy(choice = DestroyChoice.EVERYTHING) }
    }

    fun onChooseKeep() {
        choiceTouched = true
        update { it.copy(choice = DestroyChoice.KEEP_SOME) }
    }

    /** The tick is usable only under "Keep some things". */
    fun onTickContacts(ticked: Boolean) {
        choiceTouched = true
        update { if (it.choice == DestroyChoice.KEEP_SOME) it.copy(contactsTicked = ticked) else it }
    }

    fun onPinChange(pin: String) {
        if (pin.length <= MAX_PIN && pin.all { it.isDigit() }) update { it.copy(pin = pin, message = null) }
    }

    fun onConfirmChange(pin: String) {
        if (pin.length <= MAX_PIN && pin.all { it.isDigit() }) update { it.copy(confirm = pin, message = null) }
    }

    fun onNoticeShown() = update { it.copy(notice = null) }

    /**
     * Save at step 3. True when the PIN and the choice were written. False when Save is refused (before the choice
     * was shown, a short or a mismatched PIN), when a proof is needed first (B2: [onProofResult] follows), or when
     * nothing could be written (the message says so).
     */
    suspend fun save(): Boolean {
        val current = mutableState.value
        if (step != 3 || !choiceShown || current.saving) return false
        when {
            current.pin.length < MIN_PIN -> {
                update { it.copy(message = R.string.destroy_pin_too_short) }
                return false
            }
            current.pin != current.confirm -> {
                update { it.copy(message = R.string.chat_destroy_pin_mismatch) }
                return false
            }
        }
        val keep = keepOf(current)
        if (!current.hasPin && CONTACTS in keep && phoneHasScreenLock()) {
            update { it.copy(proofNeeded = true, message = null) }
            return false
        }
        return write(current.pin, keep)
    }

    /** B2: the screen lock asked for a first Save with the tick. Confirmed: the save goes on; else nothing is written. */
    suspend fun onProofResult(confirmed: Boolean): Boolean {
        val current = mutableState.value
        if (!current.proofNeeded || step != 3 || !choiceShown) return false
        update { it.copy(proofNeeded = false) }
        if (!confirmed) {
            update { it.copy(message = R.string.destroy_pin_setup_lock_needed) }
            return false
        }
        return write(current.pin, keepOf(current))
    }

    private suspend fun write(
        pin: String,
        keep: Set<String>,
    ): Boolean {
        update { it.copy(saving = true, message = null) }
        val saved = destroyManager.saveDestroyKeep(keep) { zchatPreferences.setDestroySetup(pin, it) }
        update {
            when (saved) {
                DestroyKeepSave.SAVED ->
                    it.copy(
                        saving = false,
                        pin = "",
                        confirm = "",
                        hasPin = true,
                        notice = if (CONTACTS in keep) R.string.destroy_options_saved_keep_contacts else R.string.destroy_options_saved_all,
                        finished = true,
                    )
                DestroyKeepSave.KEY_FAILED -> it.copy(saving = false, message = R.string.destroy_options_key_failed)
                DestroyKeepSave.NOT_SAVED -> it.copy(saving = false, message = R.string.destroy_pin_setup_save_failed)
            }
        }
        return saved == DestroyKeepSave.SAVED
    }

    private fun keepOf(state: DestroyPinSetupUiState): Set<String> =
        if (state.choice == DestroyChoice.KEEP_SOME && state.contactsTicked) setOf(CONTACTS) else emptySet()

    // ZC-1043: one atomic update, so a read that answers on another thread never loses a change made meanwhile.
    private fun update(change: (DestroyPinSetupUiState) -> DestroyPinSetupUiState) {
        mutableState.update { change(it).copy(step = step) }
    }

    private companion object {
        const val CONTACTS = "contacts"
        const val MIN_PIN = 4
        const val MAX_PIN = 8
    }
}
