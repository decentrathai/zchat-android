package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.TeamLabelText
import co.electriccoin.zcash.ui.screen.chat.model.contactDisplayName

/**
 * Round 8 P10 (PHONE_TRIAGE ES-7): the stored contact name as a screen shows it, with the old-Team label in the phone's
 * language (see the two-argument [contactDisplayName]). The stored value is not changed.
 */
@Composable
internal fun contactDisplayName(stored: String): String =
    contactDisplayName(
        stored,
        TeamLabelText(
            renamedDefault = stringResource(R.string.contact_team_old_renamed),
            // No arguments: the raw pattern with its %1$s, filled in by contactDisplayName.
            userNamedFormat = stringResource(R.string.contact_team_old_user_named),
        ),
    )
