package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.design.util.StringResource

/** The two choices of the block (W-41: by default Destroy All destroys everything). */
enum class DestroyChoice {
    EVERYTHING,
    KEEP_SOME
}

/**
 * DESTROY-ADV-C (ZC-722; spec revision 2c, 5.1): the screen "What Destroy All erases".
 *
 * [hasPin] false: no Save, the line that the choice is saved together with a Destroy PIN ([needsPin]) and one action
 * to the set-up. [summary] is the string id of what is saved (Advanced Settings' second line). [notice] is a string id
 * shown once after Save; [keyFailed] says the list's key could not be made and nothing was saved.
 */
data class DestroyOptionsState(
    val choice: DestroyChoice = DestroyChoice.EVERYTHING,
    val contactsTicked: Boolean = false,
    val advancedOpen: Boolean = false,
    val hasPin: Boolean = false,
    val summary: Int,
    val existingList: Boolean = false,
    val pinDialogShown: Boolean = false,
    val pinInput: String = "",
    val pinError: StringResource? = null,
    val pinChecking: Boolean = false,
    val notice: Int? = null,
    val keyFailed: Boolean = false,
    // ZC-1016: the store refused the write of the choice; nothing was changed.
    val saveFailed: Boolean = false,
) {
    val tickEnabled: Boolean get() = choice == DestroyChoice.KEEP_SOME
    val saveShown: Boolean get() = hasPin
    val needsPin: Boolean get() = !hasPin
    val existingListShown: Boolean get() = existingList && choice == DestroyChoice.EVERYTHING
}
