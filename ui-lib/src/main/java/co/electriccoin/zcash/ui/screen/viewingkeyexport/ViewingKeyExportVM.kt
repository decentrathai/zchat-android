package co.electriccoin.zcash.ui.screen.viewingkeyexport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ViewingKeyExportVM(
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val biometricRepository: BiometricRepository,
    private val context: Context
) : ViewModel() {

    private val isFvkRevealed = MutableStateFlow(false)
    private val isIvkRevealed = MutableStateFlow(false)
    private val isOvkRevealed = MutableStateFlow(false)
    private val showAdvanced = MutableStateFlow(false)
    private val snackbarMessage = MutableStateFlow<String?>(null)

    val state = combine(
        accountDataSource.selectedAccount.filterNotNull(),
        isFvkRevealed,
        isIvkRevealed,
        isOvkRevealed,
        showAdvanced,
        snackbarMessage
    ) { flows ->
        val account = flows[0] as co.electriccoin.zcash.ui.common.model.WalletAccount
        val fvkRevealed = flows[1] as Boolean
        val ivkRevealed = flows[2] as Boolean
        val ovkRevealed = flows[3] as Boolean
        val advanced = flows[4] as Boolean
        val snackbar = flows[5] as String?

        val ufvk = account.sdkAccount.ufvk ?: ""

        ViewingKeyExportState(
            onBack = ::onBack,
            isLoading = false,
            fvkState = ViewingKeyState(
                type = ViewingKeyType.FVK,
                title = stringRes("Full Viewing Key (FVK)"),
                description = stringRes("Allows viewing ALL transactions (incoming and outgoing). Share this to let someone audit your complete transaction history."),
                key = ufvk,
                isRevealed = fvkRevealed,
                onRevealClick = { onRevealFvk() },
                onCopyClick = { onCopyKey(ufvk, "FVK") }
            ),
            showAdvanced = advanced,
            onToggleAdvanced = ::onToggleAdvanced,
            ivkState = ViewingKeyState(
                type = ViewingKeyType.IVK,
                title = stringRes("Incoming Viewing Key (IVK)"),
                description = stringRes("Allows viewing INCOMING transactions only. Share this to let someone see payments received without revealing spending history."),
                key = ufvk, // Note: In a full implementation, IVK would be derived separately
                isRevealed = ivkRevealed,
                onRevealClick = { onRevealIvk() },
                onCopyClick = { onCopyKey(ufvk, "IVK") }
            ),
            ovkState = ViewingKeyState(
                type = ViewingKeyType.OVK,
                title = stringRes("Outgoing Viewing Key (OVK)"),
                description = stringRes("Allows viewing OUTGOING transactions only. Share this to let someone see your spending history without revealing incoming payments."),
                key = ufvk, // Note: In a full implementation, OVK would be derived separately
                isRevealed = ovkRevealed,
                onRevealClick = { onRevealOvk() },
                onCopyClick = { onCopyKey(ufvk, "OVK") }
            ),
            snackbarMessage = snackbar,
            onSnackbarDismiss = { snackbarMessage.update { null } }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
        initialValue = ViewingKeyExportState(
            onBack = ::onBack,
            isLoading = true
        )
    )

    private fun onBack() = navigationRouter.back()

    private fun onToggleAdvanced() {
        showAdvanced.update { !it }
    }

    private fun onRevealFvk() = viewModelScope.launch {
        toggleRevealWithBiometric(isFvkRevealed, "Full Viewing Key")
    }

    private fun onRevealIvk() = viewModelScope.launch {
        toggleRevealWithBiometric(isIvkRevealed, "Incoming Viewing Key")
    }

    private fun onRevealOvk() = viewModelScope.launch {
        toggleRevealWithBiometric(isOvkRevealed, "Outgoing Viewing Key")
    }

    private suspend fun toggleRevealWithBiometric(revealState: MutableStateFlow<Boolean>, keyName: String) {
        if (!revealState.value) {
            try {
                biometricRepository.requestBiometrics(
                    BiometricRequest(
                        message = stringRes("Authenticate to reveal $keyName")
                    )
                )
                revealState.update { true }
            } catch (_: BiometricsFailureException) {
                // do nothing
            } catch (_: BiometricsCancelledException) {
                // do nothing
            }
        } else {
            revealState.update { false }
        }
    }

    private fun onCopyKey(key: String, keyType: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Viewing Key", key)
        clipboardManager.setPrimaryClip(clip)
        snackbarMessage.update { "$keyType copied to clipboard" }
    }
}
