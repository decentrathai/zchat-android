package co.electriccoin.zcash.ui.screen.notificationsettings

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy

@Immutable
data class NotificationSettingsState(
    val onBack: () -> Unit,
    val currentPrivacy: NotificationPrivacy,
    val isSoundEnabled: Boolean,
    val isVibrationEnabled: Boolean,
    val isNotificationPermissionGranted: Boolean,
    val isBatteryOptimizationExempt: Boolean,
    val mutedConversations: List<MutedConversationItem>,
    val showPrivacyDialog: Boolean,
    val onPrivacyClick: () -> Unit,
    val onPrivacyDialogDismiss: () -> Unit,
    val onPrivacySelected: (NotificationPrivacy) -> Unit,
    val onSoundToggle: (Boolean) -> Unit,
    val onVibrationToggle: (Boolean) -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onRequestBatteryExemption: () -> Unit,
    val onUnmuteConversation: (String) -> Unit,
)

@Immutable
data class MutedConversationItem(
    val address: String,
    val displayName: String,
)
