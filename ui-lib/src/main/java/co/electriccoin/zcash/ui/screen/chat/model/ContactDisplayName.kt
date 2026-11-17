package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.TeamContactMigration
import java.util.Locale

/**
 * The phone-language texts of the old-Team label: [renamedDefault] is contact_team_old_renamed, [userNamedFormat] is
 * contact_team_old_user_named (one `%1$s`, the name the user chose).
 */
data class TeamLabelText(
    val renamedDefault: String,
    val userNamedFormat: String
)

/**
 * Round 8 P10 (PHONE_TRIAGE ES-7): the name a screen shows for a stored contact name.
 *
 * TeamContactMigration stores its label in English ([TeamContactMigration.RENAMED_DEFAULT], or the user's name plus
 * [TeamContactMigration.USER_NAME_LABEL]) and keeps matching those English constants, so the stored value is never
 * changed here: only what is shown follows the phone's language. Every other name is returned exactly as stored.
 */
fun contactDisplayName(
    stored: String,
    strings: TeamLabelText
): String {
    val name = stored.trim()
    if (name == TeamContactMigration.RENAMED_DEFAULT) return strings.renamedDefault
    val own = teamLabelledOwnName(name) ?: return stored
    return String.format(Locale.ROOT, strings.userNamedFormat, own)
}

/** [contactDisplayName] for view models that build a [StringResource] (the Address book). */
fun contactDisplayNameRes(stored: String): StringResource {
    val name = stored.trim()
    if (name == TeamContactMigration.RENAMED_DEFAULT) return stringRes(R.string.contact_team_old_renamed)
    val own = teamLabelledOwnName(name) ?: return stringRes(stored)
    return stringRes(R.string.contact_team_old_user_named, own)
}

/** The name the user chose, when [name] (trimmed) carries the migration's label after it; otherwise null. */
private fun teamLabelledOwnName(name: String): String? {
    val label = TeamContactMigration.USER_NAME_LABEL.trim()
    if (!name.endsWith(label)) return null
    return name.removeSuffix(label).trimEnd().ifEmpty { null }
}
