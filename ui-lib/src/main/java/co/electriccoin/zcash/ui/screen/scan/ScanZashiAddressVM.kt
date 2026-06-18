package co.electriccoin.zcash.ui.screen.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.OnAddressScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.OnZip321ScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillRestoreSeedUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import co.electriccoin.zcash.ui.screen.walletbackup.SeedBackupQrData
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ScanZashiAddressVM(
    private val args: ScanArgs,
    private val synchronizerProvider: SynchronizerProvider,
    private val zip321ParseUriValidationUseCase: Zip321ParseUriValidationUseCase,
    private val onAddressScanned: OnAddressScannedUseCase,
    private val zip321Scanned: OnZip321ScannedUseCase,
    private val prefillRestoreSeed: PrefillRestoreSeedUseCase,
    private val navigationRouter: NavigationRouter,
    private val zchatPreferences: ZchatPreferences
) : ViewModel() {
    val state = MutableStateFlow(ScanValidationState.NONE)

    private val mutex = Mutex()

    private var hasBeenScannedSuccessfully = false

    init {
        co.electriccoin.zcash.spackle.Twig.debug { "ScanZashiAddressVM initialized with flow: ${args.flow}" }
    }

    fun onScanned(result: String) =
        viewModelScope.launch {
            co.electriccoin.zcash.spackle.Twig.debug { "ScanZashiAddressVM.onScanned called with flow=${args.flow}, result: ${result.take(50)}..." }
            mutex.withLock {
                if (!hasBeenScannedSuccessfully) {
                    // Handle RESTORE_SEED flow specially - pass raw QR data back
                    if (args.flow == ScanFlow.RESTORE_SEED) {
                        co.electriccoin.zcash.spackle.Twig.debug { "ScanZashiAddressVM: Processing RESTORE_SEED flow" }
                        onRestoreSeedScanned(result)
                        return@withLock
                    }

                    // Defensive: if QR data is a seed backup JSON, treat as restore even if flow doesn't match
                    val seedData = SeedBackupQrData.decode(result)
                    if (seedData != null && SeedBackupQrData.isValid(seedData)) {
                        co.electriccoin.zcash.spackle.Twig.debug { "ScanZashiAddressVM: Detected seed backup QR, treating as RESTORE_SEED (flow was ${args.flow})" }
                        onRestoreSeedScanned(result)
                        return@withLock
                    }

                    // ZCHAT contact code (zchat:c1?z=…&n=…&r=…): carries the peer's NOSTR key so the
                    // scanner can start a FREE NOSTR ("Open") chat from message #1. Only intercept the
                    // zchat: scheme so all existing Zcash-address / zip321 / payment scanning is untouched.
                    if (result.trimStart().startsWith("${ZchatContactCode.SCHEME}:")) {
                        val code = ZchatContactCode.parse(result)
                        val addrValidation = code?.let {
                            synchronizerProvider.getSynchronizer().validateAddress(it.zcashAddress)
                        }
                        if (code != null && addrValidation is AddressType.Valid) {
                            // Persist the peer's NOSTR key ONLY for the chat flow + only when the code
                            // actually carries it. A valid address with no key still works as a normal chat.
                            if (args.flow == ScanFlow.ZCHAT && code.supportsOpen) {
                                val existing = zchatPreferences.getPeerNostrPubkey(code.zcashAddress)
                                if (existing != null && !existing.equals(code.nostrPubkeyHex, ignoreCase = true)) {
                                    // Key change for an EXISTING contact: never silently overwrite a bound
                                    // (possibly verified) NOSTR identity — that would let an attacker QR
                                    // redirect future NOSTR DMs (MITM) while a stale "verified" badge lies.
                                    // Mirror routeIncomingBoot/applyKEXNostr/acceptMessageRequest: flag the
                                    // key-changed banner, clear verification, and DO NOT rebind. The user
                                    // resolves it in-chat.
                                    zchatPreferences.setE2EKeyChanged(code.zcashAddress, true)
                                    zchatPreferences.setE2EVerified(code.zcashAddress, false)
                                    co.electriccoin.zcash.spackle.Twig.debug {
                                        "ScanZashiAddressVM: peer NOSTR key for ${code.zcashAddress.take(12)}… CHANGED — flagged, NOT overwriting"
                                    }
                                } else {
                                    zchatPreferences.setPeerNostrPubkey(code.zcashAddress, code.nostrPubkeyHex)
                                    zchatPreferences.setPeerNostrRelay(code.zcashAddress, code.relayUrl)
                                }
                            }
                            onAddressScanned(code.zcashAddress, addrValidation)
                        } else {
                            onInvalidScan()
                        }
                        return@withLock
                    }

                    runCatching {
                        val zip321ValidationResult = zip321ParseUriValidationUseCase(result)
                        val addressValidationResult = synchronizerProvider.getSynchronizer().validateAddress(result)

                        when {
                            zip321ValidationResult is Zip321ParseUriValidation.Valid ->
                                onZip321Scanned(zip321ValidationResult)

                            zip321ValidationResult is Zip321ParseUriValidation.SingleAddress ->
                                onZip321SingleAddressScanned(zip321ValidationResult)

                            addressValidationResult is AddressType.Valid ->
                                onAddressScanned(result, addressValidationResult)

                            else -> onInvalidScan()
                        }
                    }.onFailure { e ->
                        co.electriccoin.zcash.spackle.Twig.error(e) { "Scan validation failed" }
                        onInvalidScan()
                    }
                }
            }
        }

    private fun onRestoreSeedScanned(result: String) {
        state.update { ScanValidationState.VALID }
        prefillRestoreSeed.request(result)
        hasBeenScannedSuccessfully = true
        navigationRouter.back()
    }

    private fun onInvalidScan() {
        hasBeenScannedSuccessfully = false
        state.update { ScanValidationState.INVALID }
    }

    private fun onAddressScanned(
        result: String,
        addressValidationResult: AddressType
    ) {
        state.update { ScanValidationState.VALID }
        onAddressScanned(result, addressValidationResult, args)
        hasBeenScannedSuccessfully = true
    }

    private suspend fun onZip321SingleAddressScanned(zip321ValidationResult: Zip321ParseUriValidation.SingleAddress) {
        val singleAddressValidation =
            synchronizerProvider
                .getSynchronizer()
                .validateAddress(zip321ValidationResult.address)
        if (singleAddressValidation is AddressType.Invalid) {
            hasBeenScannedSuccessfully = false
            state.update { ScanValidationState.INVALID }
        } else {
            state.update { ScanValidationState.VALID }
            onAddressScanned(zip321ValidationResult.address, singleAddressValidation, args)
            hasBeenScannedSuccessfully = true
        }
    }

    private suspend fun onZip321Scanned(zip321ValidationResult: Zip321ParseUriValidation.Valid) {
        state.update { ScanValidationState.VALID }
        zip321Scanned(zip321ValidationResult, args)
        hasBeenScannedSuccessfully = true
    }

    fun onScannedError() =
        viewModelScope.launch {
            mutex.withLock {
                if (!hasBeenScannedSuccessfully) {
                    state.update { ScanValidationState.INVALID }
                }
            }
        }
}
