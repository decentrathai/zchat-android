package co.electriccoin.zcash.ui.screen.chat.model

import android.net.Uri
import java.io.File

/**
 * QA2 B-5 (Q2-67, ZC-1653): a picture picked in the gallery, taken with the camera or shared into ZChat, waiting for
 * the preview's Send or Cancel. Nothing is read, uploaded, sent or paid while it waits.
 *
 * [shareInboxFile] is the share-inbox copy of a shared picture, deleted once the preview hands it off or is cancelled.
 */
data class PendingImageSend(
    val uri: Uri,
    val viewOnce: Boolean,
    val shareInboxFile: File? = null,
)
