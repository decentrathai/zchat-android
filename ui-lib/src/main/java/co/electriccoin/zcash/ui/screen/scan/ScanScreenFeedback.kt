package co.electriccoin.zcash.ui.screen.scan

/**
 * SCAN-GALLERY: what the bottom of the address scanner says and offers, as a pure function of its state.
 *
 * - [lines]: the answer to the user's last picture or scan (a [ScanValidationState] other than NONE/VALID) on top, and
 *   the camera line (refused or failed) always under it, so neither hides the other. The validation state is never
 *   reset to NONE by the view models, so "last action wins" would hide the camera reason for the rest of the visit.
 * - [showGalleryButton]: "Choose from Gallery" whenever the camera is not running; while it runs the gallery icon
 *   under the frame stays the only entry (nothing new is drawn in Scanning, the bottom block sets the frame).
 * - [showPasteHint]: only where the flow has a paste field one screen back (New Chat, Send); never for the seed.
 */
object ScanScreenFeedback {
    enum class Feedback {
        PERMISSION,
        CAMERA_FAILED,
        INVALID,
        NO_CODE_IN_PICTURE,
        MANY_CODES_IN_PICTURE
    }

    fun lines(
        scanState: ScanScreenState,
        validation: ScanValidationState
    ): List<Feedback> =
        listOfNotNull(
            eventLine(validation),
            when (scanState) {
                ScanScreenState.Permission -> Feedback.PERMISSION
                ScanScreenState.Failed -> Feedback.CAMERA_FAILED
                ScanScreenState.Scanning -> null
            }
        )

    fun showGalleryButton(scanState: ScanScreenState): Boolean = scanState != ScanScreenState.Scanning

    fun showPasteHint(flow: ScanFlow): Boolean =
        when (flow) {
            ScanFlow.ZCHAT, ScanFlow.SEND -> true
            ScanFlow.HOMEPAGE, ScanFlow.ADDRESS_BOOK, ScanFlow.RESTORE_SEED -> false
        }

    private fun eventLine(validation: ScanValidationState): Feedback? =
        when (validation) {
            ScanValidationState.NONE, ScanValidationState.VALID -> null
            ScanValidationState.INVALID -> Feedback.INVALID
            ScanValidationState.NO_CODE_IN_PICTURE -> Feedback.NO_CODE_IN_PICTURE
            ScanValidationState.MANY_CODES_IN_PICTURE -> Feedback.MANY_CODES_IN_PICTURE
        }
}
