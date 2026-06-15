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
                // SECURITY: separate incoming-only keys can't be derived on the current SDK, so this
                // still exports the FULL viewing key. The description MUST NOT claim "incoming only /
                // without revealing spending" — that would mislead a user into exposing their entire
                // history while believing they're sharing less. Keep the warning until real UIVK
                // derivation lands (see backlog item D).
                description = stringRes("⚠️ Separate incoming-only keys aren't available yet — this still exports your FULL viewing key, revealing BOTH incoming and outgoing transactions. Only share if you intend to expose your complete history."),
                key = ufvk,
                isRevealed = ivkRevealed,
                onRevealClick = { onRevealIvk() },
                // Toast label names the section the user tapped (IVK); the honest "this is the FULL
                // key" warning lives in the description above, not the transient snackbar.
                onCopyClick = { onCopyKey(ufvk, "IVK") }
            ),
            ovkState = ViewingKeyState(
                type = ViewingKeyType.OVK,
                title = stringRes("Outgoing Viewing Key (OVK)"),
                // SECURITY: see the IVK note above — this exports the FULL viewing key, not an
                // outgoing-only key, so the copy is the FVK and the text says so plainly.
                description = stringRes("⚠️ Separate outgoing-only keys aren't available yet — this still exports your FULL viewing key, revealing BOTH outgoing and incoming transactions. Only share if you intend to expose your complete history."),
                key = ufvk,
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
