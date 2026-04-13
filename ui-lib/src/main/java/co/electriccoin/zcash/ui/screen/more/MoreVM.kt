package co.electriccoin.zcash.ui.screen.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationTargets.WHATS_NEW
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.usecase.NavigateToAddressBookUseCase
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.about.AboutArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
import co.electriccoin.zcash.ui.screen.changeidentity.ChangeIdentityArgs
import co.electriccoin.zcash.ui.screen.feedback.FeedbackArgs
import co.electriccoin.zcash.ui.screen.hotfix.enhancement.EnhancementHotfixArgs
import co.electriccoin.zcash.ui.screen.hotfix.ephemeral.EphemeralHotfixArgs
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.notificationsettings.NotificationSettingsArgs
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSource
import co.electriccoin.zcash.ui.screen.settings.model.ThemePreference
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class MoreVM(
    private val getVersionInfo: GetVersionInfoProvider,
    private val navigationRouter: NavigationRouter,
    private val navigateToAddressBook: NavigateToAddressBookUseCase,
    private val themePreferenceDataSource: ThemePreferenceDataSource,
    private val zchatPreferences: ZchatPreferences,
) : ViewModel() {
    private val _showThemeDialog = MutableStateFlow(false)
    private val _showSecurityDialog = MutableStateFlow(false)
    private val _currentNotificationPrivacy = MutableStateFlow(zchatPreferences.getNotificationPrivacy())

    val state: StateFlow<MoreState> = combine(
        themePreferenceDataSource.themePreference,
        _showThemeDialog,
        _currentNotificationPrivacy,
        _showSecurityDialog,
    ) { currentTheme, showThemeDialog, notifPrivacy, showSecurityDialog ->
        createState(currentTheme, showThemeDialog, notifPrivacy, showSecurityDialog)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, createState(ThemePreference.SYSTEM, false, NotificationPrivacy.FULL_PREVIEW, false))

    private fun createState(
        currentTheme: ThemePreference,
        showThemeDialog: Boolean,
        notificationPrivacy: NotificationPrivacy,
        showSecurityDialog: Boolean = false,
    ) =
        MoreState(
            version = stringRes(R.string.settings_version, getVersionInfo().versionName),
            onBack = ::onBack,
            items =
                listOfNotNull(
                    ListItemState(
                        title = stringRes("Theme: ${currentTheme.displayName()}"),
                        bigIcon = imageRes(R.drawable.ic_advanced_settings),
                        onClick = ::onThemeClick
                    ),
                    ListItemState(
                        title = stringRes("Notifications: ${notificationPrivacy.displayName()}"),
                        bigIcon = imageRes(R.drawable.ic_settings_info),
                        onClick = ::onNotificationPrivacyClick
                    ),
                    ListItemState(
                        title = stringRes("Backup Seed Phrase"),
                        bigIcon = imageRes(R.drawable.ic_info_backup),
                        onClick = ::onBackupSeedClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_address_book),
                        bigIcon = imageRes(R.drawable.ic_settings_address_book),
                        onClick = ::onAddressBookClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_advanced_settings),
                        bigIcon = imageRes(R.drawable.ic_advanced_settings),
                        onClick = ::onAdvancedSettingsClick
                    ),
                    ListItemState(
                        title = stringRes("Security"),
                        bigIcon = imageRes(R.drawable.ic_settings_info),
                        onClick = ::onSecurityClick
                    ),
                    ListItemState(
                        title = stringRes("Change Identity"),
                        bigIcon = imageRes(R.drawable.ic_advanced_settings),
                        onClick = ::onChangeIdentityClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_whats_new),
                        bigIcon = imageRes(R.drawable.ic_settings_whats_new),
                        onClick = ::onWhatsNewClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_about_us),
                        bigIcon = imageRes(R.drawable.ic_settings_info),
                        onClick = ::onAboutUsClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_feedback),
                        bigIcon = imageRes(R.drawable.ic_settings_feedback),
                        onClick = ::onSendUsFeedbackClick
                    ),
                ).toImmutableList(),
            onVersionLongClick = ::onVersionLongClick,
            onVersionDoubleClick = ::onVersionDoubleClick,
            currentTheme = currentTheme,
            showThemeDialog = showThemeDialog,
            onThemeDialogDismiss = ::onThemeDialogDismiss,
            onThemeSelected = ::onThemeSelected,
            showSecurityDialog = showSecurityDialog,
            onSecurityDialogDismiss = { _showSecurityDialog.value = false },
        )

    private fun onVersionLongClick() = navigationRouter.forward(EphemeralHotfixArgs(address = null))

    private fun onVersionDoubleClick() = navigationRouter.forward(EnhancementHotfixArgs)

    private fun onBack() = navigationRouter.back()

    private fun onBackupSeedClick() = navigationRouter.forward(WalletBackup(isOpenedFromSeedBackupInfo = false))

    private fun onAdvancedSettingsClick() = navigationRouter.forward(AdvancedSettingsArgs)

    private fun onChangeIdentityClick() = navigationRouter.forward(ChangeIdentityArgs)

    private fun onAboutUsClick() = navigationRouter.forward(AboutArgs)

    private fun onSendUsFeedbackClick() = navigationRouter.forward(FeedbackArgs)

    private fun onAddressBookClick() = viewModelScope.launch { navigateToAddressBook() }

    private fun onWhatsNewClick() = navigationRouter.forward(WHATS_NEW)

    private fun onThemeClick() {
        _showThemeDialog.value = true
    }

    private fun onThemeDialogDismiss() {
        _showThemeDialog.value = false
    }

    private fun onThemeSelected(theme: ThemePreference) {
        themePreferenceDataSource.setTheme(theme)
        _showThemeDialog.value = false
    }

    private fun onSecurityClick() {
        _showSecurityDialog.value = true
    }

    private fun onNotificationPrivacyClick() {
        navigationRouter.forward(NotificationSettingsArgs)
    }
}

/**
 * Display name for notification privacy levels.
 */
fun NotificationPrivacy.displayName(): String = when (this) {
    NotificationPrivacy.FULL_PREVIEW -> "Full Preview"
    NotificationPrivacy.SENDER_ONLY -> "Sender Only"
    NotificationPrivacy.NEW_MESSAGE -> "New Message"
    NotificationPrivacy.SILENT -> "Silent"
}
