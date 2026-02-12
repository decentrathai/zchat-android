package co.electriccoin.zcash.ui.screen.notificationsettings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class NotificationSettingsVM(
    private val context: Context,
    private val navigationRouter: NavigationRouter,
    private val zchatPreferences: ZchatPreferences,
) : ViewModel() {

    private val _showPrivacyDialog = MutableStateFlow(false)
    private val _currentPrivacy = MutableStateFlow(zchatPreferences.getNotificationPrivacy())
    private val _isSoundEnabled = MutableStateFlow(zchatPreferences.isNotificationSoundEnabled())
    private val _isVibrationEnabled = MutableStateFlow(zchatPreferences.isNotificationVibrationEnabled())
    private val _mutedConversations = MutableStateFlow(loadMutedConversations())
    private val _permissionRefreshTrigger = MutableStateFlow(0)

    val state: StateFlow<NotificationSettingsState> = combine(
        _currentPrivacy,
        _isSoundEnabled,
        combine(_isVibrationEnabled, _showPrivacyDialog, _mutedConversations, _permissionRefreshTrigger) { v, d, m, _ ->
            Triple(v, d, m)
        }
    ) { privacy, sound, (vibration, showDialog, muted) ->
        createState(privacy, sound, vibration, showDialog, muted)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        createState(
            _currentPrivacy.value,
            _isSoundEnabled.value,
            _isVibrationEnabled.value,
            false,
            _mutedConversations.value
        )
    )

    private fun createState(
        privacy: NotificationPrivacy,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        showPrivacyDialog: Boolean,
        mutedConversations: List<MutedConversationItem>,
    ) = NotificationSettingsState(
        onBack = ::onBack,
        currentPrivacy = privacy,
        isSoundEnabled = soundEnabled,
        isVibrationEnabled = vibrationEnabled,
        isNotificationPermissionGranted = checkNotificationPermission(),
        isBatteryOptimizationExempt = checkBatteryExemption(),
        mutedConversations = mutedConversations,
        showPrivacyDialog = showPrivacyDialog,
        onPrivacyClick = ::onPrivacyClick,
        onPrivacyDialogDismiss = ::onPrivacyDialogDismiss,
        onPrivacySelected = ::onPrivacySelected,
        onSoundToggle = ::onSoundToggle,
        onVibrationToggle = ::onVibrationToggle,
        onRequestNotificationPermission = ::onRequestNotificationPermission,
        onRequestBatteryExemption = ::onRequestBatteryExemption,
        onUnmuteConversation = ::onUnmuteConversation,
    )

    private fun onBack() = navigationRouter.back()

    private fun onPrivacyClick() {
        _showPrivacyDialog.value = true
    }

    private fun onPrivacyDialogDismiss() {
        _showPrivacyDialog.value = false
    }

    private fun onPrivacySelected(privacy: NotificationPrivacy) {
        zchatPreferences.setNotificationPrivacy(privacy)
        _currentPrivacy.value = privacy
        _showPrivacyDialog.value = false
    }

    private fun onSoundToggle(enabled: Boolean) {
        zchatPreferences.setNotificationSoundEnabled(enabled)
        _isSoundEnabled.value = enabled
    }

    private fun onVibrationToggle(enabled: Boolean) {
        zchatPreferences.setNotificationVibrationEnabled(enabled)
        _isVibrationEnabled.value = enabled
    }

    private fun onRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun onRequestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun onUnmuteConversation(address: String) {
        zchatPreferences.unmuteConversation(address)
        _mutedConversations.value = loadMutedConversations()
    }

    fun refreshPermissions() {
        _permissionRefreshTrigger.value++
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkBatteryExemption(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun loadMutedConversations(): List<MutedConversationItem> {
        return zchatPreferences.getMutedConversations().map { address ->
            MutedConversationItem(
                address = address,
                displayName = zchatPreferences.getDisplayName(address)
            )
        }
    }
}
