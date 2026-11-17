package co.electriccoin.zcash.ui.screen.settings.view

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.more.displayNameRes
import co.electriccoin.zcash.ui.screen.settings.model.ThemePreference

@Composable
fun ThemeSelectorDialog(
    currentTheme: ThemePreference,
    onThemeSelected: (ThemePreference) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.theme_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                ThemePreference.entries.forEach { theme ->
                    ThemeOption(
                        theme = theme,
                        isSelected = currentTheme == theme,
                        onClick = {
                            onThemeSelected(theme)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    theme: ThemePreference,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(theme.displayNameRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(themeDescriptionRes(theme)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.theme_option_selected),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@StringRes
private fun themeDescriptionRes(theme: ThemePreference): Int {
    return when (theme) {
        ThemePreference.SYSTEM -> R.string.theme_desc_system
        ThemePreference.LIGHT -> R.string.theme_desc_light
        ThemePreference.DARK -> R.string.theme_desc_dark
        ThemePreference.ZYPHERPUNK -> R.string.theme_desc_nightwire_dark
        ThemePreference.NIGHTWIRE_LIGHT -> R.string.theme_desc_nightwire_light
    }
}

// ==========================================
// NOTIFICATION PRIVACY DIALOG
// ==========================================

@Composable
fun NotificationPrivacySelectorDialog(
    currentPrivacy: co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy,
    onPrivacySelected: (co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.notif_privacy_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy.entries.forEach { privacy ->
                    NotificationPrivacyOption(
                        privacy = privacy,
                        isSelected = currentPrivacy == privacy,
                        onClick = {
                            onPrivacySelected(privacy)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationPrivacyOption(
    privacy: co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(privacy.displayNameRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(privacyDescriptionRes(privacy)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.theme_option_selected),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@StringRes
private fun privacyDescriptionRes(privacy: co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy): Int {
    return when (privacy) {
        co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy.FULL_PREVIEW -> R.string.notif_privacy_desc_full_preview
        co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy.SENDER_ONLY -> R.string.notif_privacy_desc_sender_only
        co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy.NEW_MESSAGE -> R.string.notif_privacy_desc_new_message
        co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy.SILENT -> R.string.notif_privacy_desc_silent
    }
}
