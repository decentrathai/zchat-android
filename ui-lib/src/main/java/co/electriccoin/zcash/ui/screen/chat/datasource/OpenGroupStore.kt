@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import java.io.File

/**
 * KI-25 D1, condition 2 (ZC-598) — the encrypted group store as it was opened, and whether this call created its file:
 * [isNew] is true only when neither `<name>.xml` nor `<name>.xml.bak` was in the folder just before [prefs] was
 * opened. Nothing else is decided here.
 */
class OpenedGroupStore(
    val prefs: SharedPreferences,
    val isNew: Boolean,
)

/**
 * KI-25 D1, condition 2 (ZC-598) — opens the encrypted group store through [open] and tells whether this call created
 * its file in [folder]. Its last step before [open] is the test that `<name>.xml` and `<name>.xml.bak` are both
 * absent ([exists] is the file test; one that throws counts as "present", so the store is not new). A store object is
 * never returned without its file on the disk: when `<name>.xml` is not there after [open] returned,
 * [EncryptedStoreUnavailableException] is thrown, the store's constructor fails and the start-up check shows the safety
 * screen. Called once, where the store opens its group store, after the key store is open.
 */
@Suppress("TooGenericExceptionCaught")
fun openGroupStoreAndTellIfNew(
    folder: File,
    name: String,
    open: () -> SharedPreferences,
    exists: (File) -> Boolean = File::exists,
): OpenedGroupStore {
    val absent =
        try {
            !exists(File(folder, "$name.xml")) && !exists(File(folder, "$name.xml.bak"))
        } catch (e: Exception) {
            false
        }
    val prefs = open()
    val present =
        try {
            exists(File(folder, "$name.xml"))
        } catch (e: Exception) {
            true
        }
    if (!present) {
        throw EncryptedStoreUnavailableException(
            "The group store was opened and its file is not on the disk",
            IllegalStateException("no $name.xml after the open"),
        )
    }
    return OpenedGroupStore(prefs, absent)
}
