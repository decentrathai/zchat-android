package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Round 10a R10A-2 (F-R9P-3) — the id prefixes of the optimistic rows a photo, a file and a voice note show while
 * their encrypted blob uploads. Nothing is on the chain or on a relay yet while such a row is pending, whatever the
 * chat's mode; only the send that follows the upload replaces it with a real pending send.
 */
object AttachmentIds {
    const val PHOTO = "img-pending-"
    const val FILE = "file-pending-"
    const val VOICE = "audio-pending-"

    /** True for the row of a photo, file or voice note whose upload has not been handed to the send yet. */
    fun isUpload(id: String): Boolean = id.startsWith(PHOTO) || id.startsWith(FILE) || id.startsWith(VOICE)

    /**
     * R10A-2 (F-R9P-4): the FAILED label of an upload row, chosen when the bubble is drawn. The view model writes the
     * label into the row when the upload fails and survives a language change, so the written text can be in the
     * language the phone had then. Null for any other row.
     */
    @androidx.annotation.StringRes
    fun failedLabelRes(id: String): Int? =
        when {
            id.startsWith(PHOTO) -> co.electriccoin.zcash.ui.R.string.chat_photo_failed_label
            id.startsWith(FILE) -> co.electriccoin.zcash.ui.R.string.chat_file_failed_label
            id.startsWith(VOICE) -> co.electriccoin.zcash.ui.R.string.chat_voice_failed_label
            else -> null
        }
}
