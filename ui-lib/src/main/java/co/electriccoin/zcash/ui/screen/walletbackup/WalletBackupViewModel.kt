package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.SecretAccess
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.OnUserSavedWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.RemindWalletBackupLaterUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupData
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.SeedTextState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalletBackupViewModel(
    walletBackupMessageUseCase: WalletBackupMessageUseCase,
    getPersistableWallet: GetPersistableWalletUseCase,
    private val args: WalletBackup,
    private val navigationRouter: NavigationRouter,
    private val onUserSavedWalletBackup: OnUserSavedWalletBackupUseCase,
    private val remindWalletBackupLater: RemindWalletBackupLaterUseCase,
    private val biometricRepository: BiometricRepository
) : ViewModel() {
    private val lockoutDuration =
        walletBackupMessageUseCase
            .observe()
            .filterIsInstance<WalletBackupData.Available>()
            .take(1)
            .map { it.lockoutDuration }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private val isRevealed = MutableStateFlow(false)
    private val showQrCode = MutableStateFlow(false)

    // SEED-AUTH: the line under the words — "couldn't confirm it's you", or "this phone has no screen lock".
    private val securityNotice = MutableStateFlow<StringResource?>(null)
    private var revealJob: Job? = null

    private val isRemindMeLaterButtonVisible =
        isRevealed
            .map { isRevealed ->
                isRevealed && args.isOpenedFromSeedBackupInfo
            }

    // R2-3: the store with the phrase can fail to open (a phone-security error). Reading it used to throw out of
    // this screen's scope and crash ZCHAT; now the screen says the phrase cannot be read right now.
    private val savedWallet: Flow<Pair<PersistableWallet?, Boolean>> =
        getPersistableWallet
            .observe()
            .map<PersistableWallet?, Pair<PersistableWallet?, Boolean>> { it to false }
            .catch { e ->
                if (e is CancellationException) throw e
                Twig.warn { "WALLET - the recovery phrase cannot be read right now: ${e.javaClass.simpleName}" }
                emit(null to true)
            }

    val state =
        combine(
            isRevealed,
            isRemindMeLaterButtonVisible,
            savedWallet,
            lockoutDuration,
            combine(showQrCode, securityNotice, ::Pair)
        ) { isRevealed, isRemindMeLaterButtonVisible, (wallet, isUnreadable), lockoutDuration, (showQr, notice) ->
            // Generate QR data when revealed and wallet available
            val qrState = if (isRevealed && wallet != null) {
                val seedPhrase = wallet.seedPhrase.joinToString()
                val birthday = wallet.birthday?.value ?: 0L
                val qrData = SeedBackupQrData.encode(seedPhrase, birthday)
                QrState(qrData = qrData)
            } else {
                null
            }

            WalletBackupState(
                secondaryButton =
                    ButtonState(
                        text =
                            if (lockoutDuration != null) {
                                stringRes(R.string.general_remind_me_in, stringRes(lockoutDuration.res))
                            } else {
                                stringRes(R.string.general_remind_me_later)
                            },
                        onClick = ::onRemindMeLaterClick
                    ).takeIf { isRemindMeLaterButtonVisible },
                primaryButton =
                    ButtonState(
                        text =
                            when {
                                isRevealed && args.isOpenedFromSeedBackupInfo ->
                                    stringRes(R.string.seed_recovery_saved_button)

                                isRevealed -> stringRes(R.string.seed_recovery_hide_button)
                                else -> stringRes(R.string.seed_recovery_reveal_button)
                            },
                        onClick =
                            if (isRevealed && args.isOpenedFromSeedBackupInfo) {
                                { onWalletBackupSavedClick() }
                            } else {
                                { onRevealClick() }
                            },
                        isEnabled = wallet != null,
                        isLoading = wallet == null && !isUnreadable,
                        icon =
                            when {
                                isRevealed && args.isOpenedFromSeedBackupInfo -> null
                                isRevealed -> R.drawable.ic_seed_hide
                                else -> R.drawable.ic_seed_show
                            },
                        hapticFeedbackType = if (isRevealed) HapticFeedbackType.Confirm else null
                    ),
                info =
                    IconButtonState(
                        onClick = ::onInfoClick,
                        icon = R.drawable.ic_help
                    ),
                seed =
                    SeedTextState(
                        seed = wallet?.seedPhrase?.joinToString().orEmpty(),
                        isRevealed = isRevealed,
                    ),
                birthday =
                    SeedSecretState(
                        title = stringRes(R.string.seed_recovery_bday_title),
                        text =
                            stringRes(
                                wallet
                                    ?.birthday
                                    ?.value
                                    ?.toString()
                                    .orEmpty()
                            ),
                        isRevealed = isRevealed,
                        tooltip =
                            SeedSecretStateTooltip(
                                title = stringRes(R.string.seed_recovery_bday_tooltip_title),
                                message = stringRes(R.string.seed_recovery_bday_tooltip_message)
                            ),
                        onClick = null,
                    ),
                onBack = ::onBack,
                // QR code backup
                qrState = qrState,
                showQrCode = showQr,
                onShowQrClick = ::onShowQrClick,
                onHideQrClick = ::onHideQrClick,
                unreadableMessage = if (isUnreadable) stringRes(R.string.seed_recovery_unreadable) else null,
                securityNotice = notice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    private fun onRemindMeLaterClick() = viewModelScope.launch { remindWalletBackupLater(persistConsent = false) }

    private fun onWalletBackupSavedClick() =
        viewModelScope.launch {
            // Reset QR visibility on leave so a later revisit starts from the two-step reveal again.
            showQrCode.update { false }
            onUserSavedWalletBackup()
        }

    private fun onRevealClick() {
        if (!isRevealed.value) {
            // SEED-AUTH: every reveal asks the phone's own prompt through the strict gate, which never skips it on
            // a phone with a screen lock. A second tap while the prompt is up is ignored.
            if (revealJob?.isActive == true) return
            securityNotice.value = null
            revealJob =
                viewModelScope.launch {
                    try {
                        val access =
                            biometricRepository.requestSecretAccess(
                                BiometricRequest(
                                    message =
                                        stringRes(
                                            R.string.authentication_system_ui_subtitle,
                                            stringRes(R.string.authentication_use_case_seed_recovery)
                                        )
                                )
                            )
                        if (access == SecretAccess.NO_SCREEN_LOCK) {
                            securityNotice.value = stringRes(R.string.seed_recovery_no_screen_lock)
                        }
                        isRevealed.update { true }
                    } catch (_: BiometricsFailureException) {
                        securityNotice.value = stringRes(R.string.seed_recovery_auth_failed)
                    } catch (_: BiometricsCancelledException) {
                        securityNotice.value = stringRes(R.string.seed_recovery_auth_failed)
                    }
                }
        } else {
            isRevealed.update { false }
            // Hiding the seed must also collapse the seed QR — otherwise showQrCode stays true and
            // the plaintext-seed QR auto-reappears (with no explicit tap) the next time the seed is
            // revealed, silently skipping the deliberate two-step reveal.
            showQrCode.update { false }
            securityNotice.value = null
        }
    }

    /**
     * SEED-AUTH: the screen stopped (the app went to the background, the screen turned off, or another screen opened
     * on top). The words, the birthday and the QR are hidden, so the next showing needs a new confirmation. A reveal
     * whose prompt is still up is not cancelled: its result still counts.
     */
    fun onScreenStopped() {
        isRevealed.update { false }
        showQrCode.update { false }
        securityNotice.value = null
    }

    private fun onInfoClick() {
        navigationRouter.forward(SeedInfo)
    }

    private fun onShowQrClick() {
        showQrCode.update { true }
    }

    private fun onHideQrClick() {
        showQrCode.update { false }
    }

    private fun onBack() = navigationRouter.back()
}
