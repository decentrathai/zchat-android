package co.electriccoin.zcash.ui.screen.advancedsettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationTargets
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.usecase.GetWalletRestoringStateUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToExportPrivateDataUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToResetWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToTaxExportUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToWalletBackupUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugArgs
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupWaiting
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.KeptNames
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerArgs
import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyOptionsArgs
import co.electriccoin.zcash.ui.screen.enhanceddestroy.EnhancedDestroyArgs
import co.electriccoin.zcash.ui.screen.enhanceddestroy.KeptNamesArgs
import co.electriccoin.zcash.ui.screen.exchangerate.settings.ExchangeRateSettingsArgs
import co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetup
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsArgs
import co.electriccoin.zcash.ui.screen.viewingkeyexport.ViewingKeyExportArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class AdvancedSettingsVM(
    getWalletRestoringState: GetWalletRestoringStateUseCase,
    private val navigationRouter: NavigationRouter,
    private val navigateToTaxExport: NavigateToTaxExportUseCase,
    private val navigateToWalletBackup: NavigateToWalletBackupUseCase,
    private val getVersionInfo: GetVersionInfoProvider,
    private val navigateToResetWallet: NavigateToResetWalletUseCase,
    private val navigateToExportPrivateData: NavigateToExportPrivateDataUseCase,
    private val restoreOldChats: RestoreOldChats,
    private val automaticSetupPayments: AutomaticSetupPayments,
    private val context: Context,
    private val destroyManager: DestroyManager,
) : ViewModel() {
    private val versionInfo by lazy { getVersionInfo() }

    // F10 (fix-all 2.13.0, chat copy #16): whether a kept chat copy is held back (read on IO, never in createState),
    // and whether the confirm before the restart is shown.
    private val showRestoreOldChatsRow = MutableStateFlow(false)
    private val restoreOldChatsConfirmShown = MutableStateFlow(false)

    // KI-23 (ZC-020): whether automatic setup payments are on (read on IO, never in createState; null until it is
    // read, and when it cannot be), and which dialog of the row is shown.
    private val automaticSetupPaymentsOn = MutableStateFlow<Boolean?>(null)
    private val automaticSetupPaymentsDialog = MutableStateFlow<AutomaticSetupPaymentsDialogState.Kind?>(null)

    // ZC1013-X1: what the turn-on confirm quotes (what waits now, what keeps asking), read at the row's tap.
    private val automaticSetupWaiting = MutableStateFlow<AutomaticSetupWaiting?>(null)

    // DESTROY-ADV-N (ZC-722): whether the kept-names list's file exists (a file check on IO; nothing is decrypted).
    private val showKeptNamesRow = MutableStateFlow(false)

    // DESTROY-ADV-C (ZC-722): whether the saved choice keeps the names (read on IO; false until it is read).
    private val destroyKeepsContacts = MutableStateFlow(false)

    init {
        viewModelScope.launch { showKeptNamesRow.value = withContext(Dispatchers.IO) { keptNamesFileExists() } }
        viewModelScope.launch { destroyKeepsContacts.value = withContext(Dispatchers.IO) { destroyChoiceKeepsContacts() } }
        viewModelScope.launch { showRestoreOldChatsRow.value = restoreOldChats.isAvailable() }
        viewModelScope.launch { automaticSetupPaymentsOn.value = automaticSetupPayments.isOn() }
    }

    val state: StateFlow<AdvancedSettingsState> =
        combine<Any?, AdvancedSettingsState>(
            getWalletRestoringState.observe(),
            showRestoreOldChatsRow,
            restoreOldChatsConfirmShown,
            automaticSetupPaymentsOn,
            automaticSetupPaymentsDialog,
            showKeptNamesRow,
            destroyKeepsContacts,
            automaticSetupWaiting,
        ) { values ->
            createState(
                walletRestoringState = values[0] as WalletRestoringState,
                showRestoreOldChats = values[1] as Boolean,
                confirmShown = values[2] as Boolean,
                autoSetupOn = values[3] as Boolean?,
                autoSetupDialog = values[4] as AutomaticSetupPaymentsDialogState.Kind?,
                showKeptNames = values[5] as Boolean,
                destroyKeepsContacts = values[6] as Boolean,
                autoSetupWaiting = values[7] as AutomaticSetupWaiting?,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(getWalletRestoringState.observe().value, showRestoreOldChats = false, confirmShown = false)
        )

    @Suppress("UNUSED_PARAMETER", "LongMethod")
    private fun createState(
        walletRestoringState: WalletRestoringState,
        showRestoreOldChats: Boolean,
        confirmShown: Boolean,
        autoSetupOn: Boolean? = null,
        autoSetupDialog: AutomaticSetupPaymentsDialogState.Kind? = null,
        showKeptNames: Boolean = false,
        destroyKeepsContacts: Boolean = false,
        autoSetupWaiting: AutomaticSetupWaiting? = null,
    ) = AdvancedSettingsState(
        onBack = ::onBack,
        items =
            listOfNotNull(
                ListItemState(
                    title = stringRes(R.string.advanced_settings_recovery),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_recovery),
                    onClick = ::onSeedRecoveryClick
                ),
                ListItemState(
                    title = stringRes(R.string.advanced_settings_export),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_export),
                    onClick = ::onExportPrivateDataClick
                ),
                ListItemState(
                    title = stringRes(R.string.advanced_settings_export_viewing_keys),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_export),
                    onClick = ::onViewingKeyExportClick
                ),
                // Hidden for now - Zashi feature, may be re-enabled later
                // ListItemState(
                //     title = stringRes(R.string.advanced_settings_tax),
                //     bigIcon =
                //         imageRes(
                //             if (walletRestoringState == WalletRestoringState.RESTORING) {
                //                 R.drawable.ic_advanced_settings_tax_disabled
                //             } else {
                //                 R.drawable.ic_advanced_settings_tax
                //             }
                //         ),
                //     isEnabled = walletRestoringState != WalletRestoringState.RESTORING,
                //     onClick = ::onTaxExportClick
                // ),
                // F10: shown only while a kept chat copy is held back (parked or paused).
                ListItemState(
                    title = stringRes(R.string.advanced_settings_restore_old_chats),
                    subtitle = stringRes(R.string.advanced_settings_restore_old_chats_subtitle),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_resync),
                    onClick = ::onRestoreOldChatsClick
                ).takeIf { showRestoreOldChats },
                // KI-23 (ZC-020): shows whether ZCHAT may pay chat setup transactions by itself; a tap can only turn
                // that off. No subtitle until the permission was read.
                ListItemState(
                    title = stringRes(R.string.advanced_settings_auto_setup_payments),
                    subtitle =
                        when (autoSetupOn) {
                            true -> stringRes(R.string.advanced_settings_auto_setup_payments_subtitle_on)
                            false -> stringRes(R.string.advanced_settings_auto_setup_payments_subtitle_off)
                            null -> null
                        },
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_currency_conversion),
                    onClick = ::onAutomaticSetupPaymentsClick
                ),
                ListItemState(
                    title = stringRes(R.string.advanced_settings_choose_server),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_choose_server),
                    onClick = ::onChooseServerClick
                ),
                // ListItemState(
                //     title = stringRes(R.string.advanced_settings_resync_wallet),
                //     bigIcon = imageRes(R.drawable.ic_advanced_settings_resync),
                //     onClick = ::onResyncWalletClick
                // ),
                // ZC-416: the Tor row is listed always, so Tor can be turned on again once it is off (F3's cases,
                // Tor on and a TEX check that needs Tor, are a subset); Currency Conversion stays hidden.
                ListItemState(
                    title = stringRes(R.string.advanced_settings_privacy),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_privacy),
                    onClick = ::onPrivacyClick
                ),
                // ListItemState(
                //     title = stringRes(R.string.advanced_settings_currency_conversion),
                //     bigIcon = imageRes(R.drawable.ic_advanced_settings_currency_conversion),
                //     onClick = ::onCurrencyConversionClick
                // ),
                ListItemState(
                    title = stringRes(R.string.advanced_settings_crash_reporting),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_crash_reporting),
                    onClick = ::onCrashReportingClick
                ).takeIf { versionInfo.distribution == DistributionDimension.STORE },
                ListItemState(
                    title = stringRes(R.string.advanced_settings_debug_menu),
                    // QA2 Q2-14 (ZC-1617): a settings gear; the (i) belongs to About only.
                    bigIcon = imageRes(R.drawable.ic_advanced_settings),
                    onClick = ::onDebugMenuClick
                ).takeIf { BuildConfig.DEBUG },
                ListItemState(
                    // Relocated here from onboarding (#2): the optional PIN that emergency-wipes the
                    // wallet. Sits next to Emergency Destroy since both are the emergency-wipe controls.
                    title = stringRes(R.string.advanced_settings_destroy_pin),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_destroy),
                    onClick = ::onDestroyPinClick
                ),
                // DESTROY-ADV-N (ZC-722): shown only while the list of names an earlier Destroy All kept exists.
                ListItemState(
                    title = stringRes(R.string.advanced_settings_kept_names),
                    subtitle = stringRes(R.string.advanced_settings_kept_names_sub),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_destroy),
                    onClick = ::onKeptNamesClick
                ).takeIf { showKeptNames },
                ListItemState(
                    title = stringRes(R.string.advanced_settings_emergency_destroy),
                    // Destructive action → distinct warning glyph, not the generic info icon
                    // (which is also used for benign informational items).
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_destroy),
                    onClick = ::onEnhancedDestroyClick
                ),
                // DESTROY-ADV-C (ZC-722): what Destroy All erases; the second line is the saved choice.
                ListItemState(
                    title = stringRes(R.string.advanced_settings_destroy_options),
                    subtitle =
                        stringRes(
                            if (destroyKeepsContacts) {
                                R.string.advanced_settings_destroy_options_keep_contacts
                            } else {
                                R.string.advanced_settings_destroy_options_all
                            }
                        ),
                    bigIcon = imageRes(R.drawable.ic_advanced_settings_destroy),
                    onClick = ::onDestroyOptionsClick
                ),
            ),
        deleteButton =
            ButtonState(
                text = stringRes(R.string.advanced_settings_delete_button),
                onClick = ::onResetWalletClick,
            ),
        restoreOldChatsConfirm =
            RestoreOldChatsConfirmState(
                onConfirm = ::onRestoreOldChatsConfirm,
                onDismiss = ::onRestoreOldChatsDismiss,
            ).takeIf { confirmShown },
        automaticSetupPaymentsDialog =
            autoSetupDialog?.let { kind ->
                AutomaticSetupPaymentsDialogState(
                    kind = kind,
                    onConfirm =
                        if (kind == AutomaticSetupPaymentsDialogState.Kind.CONFIRM_TURN_ON) {
                            { onAutomaticSetupPaymentsTurnOnConfirm() }
                        } else {
                            { onAutomaticSetupPaymentsTurnOffConfirm() }
                        },
                    onDismiss = ::onAutomaticSetupPaymentsDialogDismiss,
                    waiting = autoSetupWaiting.takeIf { kind == AutomaticSetupPaymentsDialogState.Kind.CONFIRM_TURN_ON },
                )
            },
    )

    private fun onPrivacyClick() = navigationRouter.forward(TorSettingsArgs)

    fun onBack() = navigationRouter.back()

    private fun onChooseServerClick() = navigationRouter.forward(ChooseServerArgs)

    private fun onCurrencyConversionClick() = navigationRouter.forward(ExchangeRateSettingsArgs)

    private fun onDebugMenuClick() = navigationRouter.forward(DebugArgs)

    private fun onCrashReportingClick() = navigationRouter.forward(NavigationTargets.CRASH_REPORTING_OPT_IN)

    private fun onTaxExportClick() = viewModelScope.launch { navigateToTaxExport() }

    private fun onSeedRecoveryClick() =
        viewModelScope.launch {
            navigateToWalletBackup(isOpenedFromSeedBackupInfo = false)
        }

    private fun onExportPrivateDataClick() = viewModelScope.launch { navigateToExportPrivateData() }

    private fun onResetWalletClick() = viewModelScope.launch { navigateToResetWallet() }

    private fun onEnhancedDestroyClick() = navigationRouter.forward(EnhancedDestroyArgs)

    private fun onKeptNamesClick() = navigationRouter.forward(KeptNamesArgs)

    private fun onDestroyOptionsClick() = navigationRouter.forward(DestroyOptionsArgs)

    private fun destroyChoiceKeepsContacts(): Boolean = DestroyWipe.KEEP_CONTACTS in destroyManager.reloadDestroyKeep()

    private fun keptNamesFileExists(): Boolean = KeptNames.of(context).listFileExists()

    private fun onDestroyPinClick() = navigationRouter.forward(DestroyPinSetup(isCreatingWallet = false))

    private fun onViewingKeyExportClick() = navigationRouter.forward(ViewingKeyExportArgs)

    // F10 (review break 7): the restart ends the running app, so a tap only asks to confirm first.
    private fun onRestoreOldChatsClick() {
        restoreOldChatsConfirmShown.value = true
    }

    private fun onRestoreOldChatsDismiss() {
        restoreOldChatsConfirmShown.value = false
    }

    private fun onRestoreOldChatsConfirm() =
        viewModelScope.launch {
            restoreOldChatsConfirmShown.value = false
            // On success ZCHAT restarts; getting here means nothing was changed, so the row is read again.
            if (!restoreOldChats.retryAndRestart()) showRestoreOldChatsRow.value = restoreOldChats.isAvailable()
        }

    // KI-23 (ZC-020): a tap reads the permission again (the question in a chat may have changed it since this screen
    // opened). On: ask before turning it off. Off (ZC-1013): ask before turning it back on, with the standing consent;
    // ZC1013-X1: and with what waits now and what keeps asking, read before the confirm is shown.
    private fun onAutomaticSetupPaymentsClick() =
        viewModelScope.launch {
            val on = automaticSetupPayments.isOn()
            automaticSetupPaymentsOn.value = on
            automaticSetupWaiting.value = if (on == false) automaticSetupPayments.waitingNow() else null
            automaticSetupPaymentsDialog.value =
                when (on) {
                    true -> AutomaticSetupPaymentsDialogState.Kind.CONFIRM_TURN_OFF
                    false -> AutomaticSetupPaymentsDialogState.Kind.CONFIRM_TURN_ON
                    null -> null
                }
        }

    private fun onAutomaticSetupPaymentsDialogDismiss() {
        automaticSetupPaymentsDialog.value = null
    }

    // The only caller of turnOff(). A write that did not reach the disk changes nothing: the row is read again and
    // the user is told.
    private fun onAutomaticSetupPaymentsTurnOffConfirm() =
        viewModelScope.launch {
            automaticSetupPaymentsDialog.value = null
            val saved = automaticSetupPayments.turnOff()
            automaticSetupPaymentsOn.value = automaticSetupPayments.isOn()
            if (!saved) automaticSetupPaymentsDialog.value = AutomaticSetupPaymentsDialogState.Kind.NOT_SAVED
        }

    // ZC-1013: the only caller of turnOn(). It writes the permission and pays nothing; a write that did not reach the disk
    // changes nothing: the row is read again and the user is told.
    private fun onAutomaticSetupPaymentsTurnOnConfirm() =
        viewModelScope.launch {
            automaticSetupPaymentsDialog.value = null
            val saved = automaticSetupPayments.turnOn()
            automaticSetupPaymentsOn.value = automaticSetupPayments.isOn()
            if (!saved) automaticSetupPaymentsDialog.value = AutomaticSetupPaymentsDialogState.Kind.NOT_SAVED
        }

    // private fun onResyncWalletClick() = navigationRouter.forward(ConfirmResyncArgs)
}
