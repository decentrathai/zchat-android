package co.electriccoin.zcash.ui.screen.more

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.model.AddressType
import co.electriccoin.zcash.ui.BuildConfig
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
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSource
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSource
import co.electriccoin.zcash.ui.screen.settings.model.AppLanguage
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
    private val appLanguageDataSource: AppLanguageDataSource,
) : ViewModel() {
    private val _showThemeDialog = MutableStateFlow(false)
    private val _showSecurityDialog = MutableStateFlow(false)
    private val languageDialogShown = MutableStateFlow(false)
    private val _currentNotificationPrivacy = MutableStateFlow(zchatPreferences.getNotificationPrivacy())

    val state: StateFlow<MoreState> = combine(
        themePreferenceDataSource.themePreference,
        _showThemeDialog,
        _currentNotificationPrivacy,
        _showSecurityDialog,
        combine(appLanguageDataSource.appLanguage, languageDialogShown, ::Pair),
    ) { currentTheme, showThemeDialog, notifPrivacy, showSecurityDialog, (language, showLanguageDialog) ->
        createState(currentTheme, showThemeDialog, notifPrivacy, showSecurityDialog, language, showLanguageDialog)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        createState(
            currentTheme = ThemePreference.SYSTEM,
            showThemeDialog = false,
            notificationPrivacy = NotificationPrivacy.FULL_PREVIEW,
            language = appLanguageDataSource.appLanguage.value,
        )
    )

    private fun createState(
        currentTheme: ThemePreference,
        showThemeDialog: Boolean,
        notificationPrivacy: NotificationPrivacy,
        showSecurityDialog: Boolean = false,
        language: AppLanguage = AppLanguage.SYSTEM_DEFAULT,
        showLanguageDialog: Boolean = false,
    ) =
        MoreState(
            version = stringRes(R.string.settings_version, getVersionInfo().versionName),
            onBack = ::onBack,
            items =
                listOfNotNull(
                    ListItemState(
                        title = stringRes(R.string.more_row_theme, stringRes(currentTheme.displayNameRes())),
                        bigIcon = imageRes(R.drawable.ic_more_theme),
                        onClick = ::onThemeClick
                    ),
                    languageRow(language, ::onLanguageClick),
                    ListItemState(
                        title = stringRes(R.string.more_row_notifications, stringRes(notificationPrivacy.displayNameRes())),
                        bigIcon = imageRes(R.drawable.ic_more_notifications),
                        onClick = ::onNotificationPrivacyClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.more_row_backup_seed),
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
                        title = stringRes(R.string.more_row_security),
                        bigIcon = imageRes(R.drawable.ic_more_security),
                        onClick = ::onSecurityClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.identity_title),
                        bigIcon = imageRes(R.drawable.ic_more_identity),
                        onClick = ::onChangeIdentityClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_whats_new),
                        bigIcon = imageRes(R.drawable.ic_settings_whats_new),
                        onClick = ::onWhatsNewClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_support_zchat),
                        subtitle = stringRes(R.string.settings_support_zchat_subtitle),
                        bigIcon = imageRes(R.drawable.ic_settings_support),
                        onClick = ::onSupportClick
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
            currentLanguage = language,
            showLanguageDialog = showLanguageDialog,
            onLanguageDialogDismiss = { languageDialogShown.value = false },
            onLanguageSelected = ::onLanguageSelected,
        )

    private fun onVersionLongClick() = navigationRouter.forward(EphemeralHotfixArgs(address = null))

    private fun onVersionDoubleClick() = navigationRouter.forward(EnhancementHotfixArgs)

    private fun onBack() = navigationRouter.back()

    private fun onBackupSeedClick() = navigationRouter.forward(WalletBackup(isOpenedFromSeedBackupInfo = false))

    private fun onAdvancedSettingsClick() = navigationRouter.forward(AdvancedSettingsArgs)

    private fun onChangeIdentityClick() = navigationRouter.forward(ChangeIdentityArgs)

    private fun onAboutUsClick() = navigationRouter.forward(AboutArgs)

    // Opens the wallet Send screen with the zsend.xyz donation address filled in; the user picks the amount.
    private fun onSupportClick() =
        navigationRouter.forward(
            Send(
                recipientAddress = BuildConfig.ZCHAT_DONATION_ADDRESS,
                recipientAddressType = AddressType.UNIFIED,
                isScanZip321Enabled = false
            )
        )

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

    private fun onLanguageClick() {
        languageDialogShown.value = true
    }

    private fun onLanguageSelected(language: AppLanguage) {
        languageDialogShown.value = false
        appLanguageDataSource.setLanguage(language)
    }

    private fun onSecurityClick() {
        _showSecurityDialog.value = true
    }

    private fun onNotificationPrivacyClick() {
        navigationRouter.forward(NotificationSettingsArgs)
    }

    /**
     * Re-read the notification-privacy preference. It is a snapshot taken at construction, and this
     * VM is retained on the back stack while the user changes the setting on NotificationSettings, so
     * without this refresh the "Notifications: X" row would show a stale value after navigating back.
     */
    fun refreshNotificationPrivacy() {
        _currentNotificationPrivacy.value = zchatPreferences.getNotificationPrivacy()
    }
}

/**
 * Display name for notification privacy levels, as a string resource so the More row, the picker and
 * the Notifications screen all reach a Spanish phone in Spanish (round 4 report 3).
 */
@StringRes
fun NotificationPrivacy.displayNameRes(): Int = when (this) {
    NotificationPrivacy.FULL_PREVIEW -> R.string.notif_privacy_full_preview
    NotificationPrivacy.SENDER_ONLY -> R.string.notif_privacy_sender_only
    NotificationPrivacy.NEW_MESSAGE -> R.string.notif_privacy_new_message
    NotificationPrivacy.SILENT -> R.string.notif_privacy_silent
}
