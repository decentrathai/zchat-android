package co.electriccoin.zcash.ui.screen.chat.datasource

import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import java.security.MessageDigest

/**
 * Bridge 4 (FINAL_PLAN 2.2, OD6 b/c): the contact entry for the OLD ZChat Team address, whose keys the team
 * does not hold, is renamed so nobody writes to it expecting an answer. It only renames a contact book entry:
 * it sends nothing and touches no chat.
 *
 * The old address is matched by its SHA-256 (hex) so the address itself is not repeated in the code. The
 * match is by address (trimmed, lowercased, as ContactBookImpl keys it), never by name, so the entry for the
 * current Team address is never touched. A name the user chose is kept and labelled.
 *
 * A one-off (R4-6, round 3 report 4 item 15): [runAtStart] makes the pass once per install and records it,
 * so a label the user takes off afterwards stays off. [run] itself is still idempotent — an entry that
 * already carries the label is left alone — and a pass that could not read the contact book at all is not
 * recorded, so the next start tries again. Trade-off the owner should know: a contact list restored AFTER
 * the recorded pass is not labelled.
 */
object TeamContactMigration {
    /** SHA-256 of the old mainnet ZChat Team address (the entry shipped before c1275ecbb). */
    const val OLD_TEAM_ADDRESS_SHA256 = "c5de4d11214ac43f58be3d013f76eff593e63c79da4d1429dfc7cce06f163f79"

    const val DEFAULT_NAME = "ZChat Team"
    const val RENAMED_DEFAULT = "ZChat Team (old address, not monitored)"
    const val USER_NAME_LABEL = " (old ZChat Team address, not monitored)"

    private const val TAG = "ZCHAT_MIGRATE"

    /**
     * The persisted "this has already been done" flag the start-up pass keeps, so the rename is a one-off
     * instead of something every start redoes. Backed by ZchatPreferences in the app; an in-memory pair in
     * tests. Kept as a seam so [TeamContactMigration] stays a plain JVM object with no Android storage.
     */
    interface RenameOnce {
        fun done(): Boolean

        fun markDone()
    }

    /**
     * [run] with the debug-build start marker, once per install; never throws.
     *
     * R4-6 (round 3 report 4 item 15): this is a migration of entries shipped before c1275ecbb, not a rule
     * about what the entry must be called. It used to run on every start, so a user who took the label off
     * again got it back at the next start — their own contact list, overruled. Once the pass has been made
     * it is never made again.
     */
    fun runAtStart(
        contacts: ContactBook,
        once: RenameOnce,
        targetSha256: String = OLD_TEAM_ADDRESS_SHA256,
    ): Boolean {
        if (once.done()) return false
        if (BuildConfig.DEBUG) Log.i(TAG, "team-contact-rename: start")
        // null = the contact book could not be read; nothing was checked, so the pass is not recorded and the
        // next start tries again. false/true = the book WAS read, so this one-off pass is done either way.
        val changed = renameOrNull(contacts, targetSha256)
        // ZC-733-B (ZC-128): a book whose list could not be read answers an empty list; that is no read.
        if (changed != null && !contacts.unreadable.value) once.markDone()
        if (BuildConfig.DEBUG) Log.i(TAG, "team-contact-rename: done, changed=$changed")
        return changed == true
    }

    /** Renames the old Team entry if present and not yet labelled; returns true when it changed a name. */
    fun run(
        contacts: ContactBook,
        targetSha256: String = OLD_TEAM_ADDRESS_SHA256,
    ): Boolean = renameOrNull(contacts, targetSha256) == true

    /** [run], but null when the contact book could not be read at all (so a caller can tell the two apart). */
    private fun renameOrNull(
        contacts: ContactBook,
        targetSha256: String,
    ): Boolean? =
        runCatching {
            val entry =
                contacts.getAllContacts().firstOrNull { sha256(it.address.trim().lowercase()) == targetSha256 }
                    ?: return@runCatching false
            val current = entry.name.trim()
            if (current == RENAMED_DEFAULT || current.endsWith(USER_NAME_LABEL.trim())) return@runCatching false
            val renamed = if (current.isEmpty() || current == DEFAULT_NAME) RENAMED_DEFAULT else current + USER_NAME_LABEL
            contacts.updateContactName(entry.address, renamed)
            true
        }.getOrNull()

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
