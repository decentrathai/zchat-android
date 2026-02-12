package co.electriccoin.zcash.ui.screen.notificationsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.more.displayName
import co.electriccoin.zcash.ui.screen.settings.view.NotificationPrivacySelectorDialog

@Composable
fun NotificationSettingsView(state: NotificationSettingsState) {
    if (state.showPrivacyDialog) {
        NotificationPrivacySelectorDialog(
            currentPrivacy = state.currentPrivacy,
            onPrivacySelected = state.onPrivacySelected,
            onDismiss = state.onPrivacyDialogDismiss
        )
    }

    Scaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = "Notifications",
                showTitleLogo = false,
                navigationAction = {
                    ZashiTopAppBarBackNavigation(onBack = state.onBack)
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Privacy Level
            SectionHeader("Privacy Level")
            SettingsRow(
                title = "Notification Content",
                subtitle = state.currentPrivacy.displayName(),
                onClick = state.onPrivacyClick
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Sound
            SectionHeader("Sound & Vibration")
            SwitchRow(
                title = "Sound",
                subtitle = "Play notification sound for new messages",
                checked = state.isSoundEnabled,
                onCheckedChange = state.onSoundToggle
            )
            SwitchRow(
                title = "Vibration",
                subtitle = "Vibrate for new messages",
                checked = state.isVibrationEnabled,
                onCheckedChange = state.onVibrationToggle
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Permissions
            SectionHeader("Permissions")

            if (!state.isNotificationPermissionGranted) {
                PermissionRow(
                    icon = { Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = "Notification Permission",
                    subtitle = "Required to show message notifications",
                    buttonText = "Enable",
                    onClick = state.onRequestNotificationPermission
                )
            } else {
                Text(
                    text = "Notification permission granted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (!state.isBatteryOptimizationExempt) {
                PermissionRow(
                    icon = { Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = "Battery Optimization",
                    subtitle = "Exempt ZCHAT for reliable background sync",
                    buttonText = "Exempt",
                    onClick = state.onRequestBatteryExemption
                )
            } else {
                Text(
                    text = "Battery optimization exempted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Muted Conversations
            SectionHeader("Muted Conversations")
            if (state.mutedConversations.isEmpty()) {
                Text(
                    text = "No muted conversations",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                state.mutedConversations.forEach { muted ->
                    MutedConversationRow(
                        item = muted,
                        onUnmute = { state.onUnmuteConversation(muted.address) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PermissionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onClick) {
            Text(buttonText)
        }
    }
}

@Composable
private fun MutedConversationRow(
    item: MutedConversationItem,
    onUnmute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = Conversation.truncateAddress(item.address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onUnmute) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "Unmute",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
