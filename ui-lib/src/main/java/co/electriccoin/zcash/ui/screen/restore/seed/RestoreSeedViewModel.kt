package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.PrefillRestoreSeedUseCase
import co.electriccoin.zcash.ui.common.usecase.SeedValidationResult
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.height.RestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.walletbackup.SeedBackupQrData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RestoreSeedViewModel(
    private val navigationRouter: NavigationRouter,
    private val validateSeed: ValidateSeedUseCase,
    private val prefillRestoreSeed: PrefillRestoreSeedUseCase
) : ViewModel() {
    // QR scan error state
    private val scanError = MutableStateFlow<StringResource?>(null)

    // Pending birthday from QR scan (to navigate after seed is validated)
    private var pendingBirthday: Long? = null

    init {
        // Observe camera scan results from PrefillRestoreSeedUseCase
        viewModelScope.launch {
            prefillRestoreSeed.scannedQrData.collect { qrData ->
                android.util.Log.d("ZCHAT_RESTORE", "prefillRestoreSeed collected: qrData isNull=${qrData == null}")
                if (qrData != null) {
                    android.util.Log.d("ZCHAT_RESTORE", "prefillRestoreSeed: processing qrData length=${qrData.length}")
                    prefillRestoreSeed.consume() // Clear the data
                    processQrCode(qrData)
                }
            }
        }
    }

    private val suggestions =
        flow {
            val result = withContext(Dispatchers.IO) { Mnemonics.getCachedWords(Locale.ENGLISH.language) }
            emit(result)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    @Suppress("MagicNumber")
    private val seedWords =
        MutableStateFlow(
            (0..23).map { index ->
                SeedWordTextFieldState(
                    innerState =
                        SeedWordInnerTextFieldState(
                            ""
                        ),
                    onValueChange = { onValueChange(index, it) },
                    isError = false
                )
            }
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seedValidations =
        combine(seedWords, suggestions) { seedWords, suggestions ->
            seedWords to suggestions.orEmpty()
        }.mapLatest { (seedWords, suggestions) ->
            withContext(Dispatchers.Default) {
                seedWords.map { field ->
                    val trimmed =
                        field.innerState.value
                            .lowercase()
                            .trim()
                    val autocomplete = suggestions.filter { it.startsWith(trimmed) }
                    val validSuggestions =
                        when {
                            trimmed.isBlank() -> suggestions
                            suggestions.contains(trimmed) && autocomplete.size == 1 -> suggestions
                            else -> autocomplete
                        }
                    validSuggestions.isNotEmpty()
                }
            }
        }

    private val validSeed =
        seedWords
            .map { fields ->
                validateSeed(fields.map { it.innerState.value.trim() })
            }

    val state: StateFlow<RestoreSeedState?> =
        combine(seedWords, seedValidations, validSeed, scanError) { words, seedValidations, validation, error ->
            createState(words, seedValidations, validation, error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    /**
     * The complete word list that the user can choose from; useful for autocomplete
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestionsState =
        combine(validSeed, suggestions) { seed, suggestions ->
            seed to suggestions
        }.mapLatest { (seed, suggestions) ->
            RestoreSeedSuggestionsState(
                isVisible = seed == null && suggestions != null,
                suggestions = suggestions.orEmpty()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private fun createState(
        words: List<SeedWordTextFieldState>,
        seedValidations: List<Boolean>,
        seedPhrase: SeedPhrase?,
        error: StringResource?
    ) = RestoreSeedState(
        seed =
            SeedTextFieldState(
                values =
                    words
                        .mapIndexed { index, word ->
                            word.copy(isError = !seedValidations[index])
                        }
            ),
        onBack = ::onBack,
        dialogButton =
            IconButtonState(
                icon = R.drawable.ic_help,
                onClick = ::onInfoButtonClick
            ),
        nextButton =
            ButtonState(
                text = stringRes(R.string.restore_button),
                onClick = ::onNextClicked,
            ).takeIf { seedPhrase != null },
        // QR scan options - camera click is null, handled by AndroidRestore with ScanArgs
        onScanCameraClick = ::onScanCameraClick,
        onScanGalleryClick = null, // Set by AndroidRestore to launch gallery picker
        scanError = error,
        onDismissScanError = ::onDismissScanError
    )

    private fun onBack() {
        navigationRouter.back()
    }

    private fun onInfoButtonClick() {
        navigationRouter.forward(SeedInfo)
    }

    private fun onNextClicked() {
        viewModelScope.launch {
            val seed = validSeed.first() ?: return@launch
            // Use pendingBirthday if available (from QR scan)
            android.util.Log.d("ZCHAT_RESTORE", "onNextClicked: pendingBirthday=$pendingBirthday")
            navigationRouter.forward(RestoreBDHeight(seed.joinToString(), pendingBirthday))
        }
    }

    private fun onValueChange(
        index: Int,
        state: SeedWordInnerTextFieldState
    ) {
        if (BuildConfig.DEBUG) {
            val seed = validateSeed(state.value.trim().split(" "))
            if (seed != null) {
                prefillSeed(seed)
            } else {
                updateSeedWord(index, state)
            }
        } else {
            updateSeedWord(index, state)
        }
    }

    private fun updateSeedWord(
        index: Int,
        newState: SeedWordInnerTextFieldState
    ) {
        seedWords.update {
            val newSeedWords = it.toMutableList()
            newSeedWords[index] = newSeedWords[index].copy(innerState = newState.copy(value = newState.value.trim()))
            newSeedWords.toList()
        }
    }

    private fun prefillSeed(seed: SeedPhrase) {
        seedWords.update {
            val newSeedWords = it.toMutableList()
            seed.split.forEachIndexed { index, word ->
                val oldState = newSeedWords[index]
                newSeedWords[index] = oldState.copy(innerState = oldState.innerState.copy(value = word))
            }
            newSeedWords.toList()
        }
    }

    // QR Scan functions

    private fun onScanCameraClick() {
        navigationRouter.forward(ScanArgs(flow = ScanFlow.RESTORE_SEED, isScanZip321Enabled = false))
    }

    private fun onDismissScanError() {
        scanError.update { null }
    }

    /**
     * Called from AndroidRestore when gallery picker returns a result.
     * @param qrCode The decoded QR code string, or null if decoding failed
     */
    fun onGalleryResult(qrCode: String?) {
        android.util.Log.d("ZCHAT_RESTORE", "onGalleryResult called, qrCode isNull=${qrCode == null}")
        if (qrCode == null) {
            scanError.update { stringRes(R.string.restore_qr_scan_error_no_qr) }
            return
        }
        // SECURITY: the QR payload IS the BIP39 seed phrase on this screen — never log its
        // contents (logcat is readable via adb / READ_LOGS). Length only.
        android.util.Log.d("ZCHAT_RESTORE", "onGalleryResult: qrCode length=${qrCode.length}")
        processQrCode(qrCode)
    }

    /**
     * Called from navigation when camera scan returns a result.
     * This can be triggered by the Scan screen via a result callback.
     */
    fun onCameraScanResult(qrCode: String) {
        processQrCode(qrCode)
    }

    private fun processQrCode(qrCode: String) {
        // SECURITY: do not log qrCode contents — it carries the seed phrase. Length only.
        android.util.Log.d("ZCHAT_RESTORE", "processQrCode called, length=${qrCode.length}")

        // Try JSON format first (ZCHAT backup format)
        val seedData = SeedBackupQrData.decode(qrCode)
        if (seedData != null && SeedBackupQrData.isValid(seedData)) {
            android.util.Log.d("ZCHAT_RESTORE", "Parsed JSON QR: seed words=${seedData.seed.split(" ").size}, birthday=${seedData.birthday}")
            // The encoder writes birthday 0 when the wallet had no birthday height; treat that sentinel
            // as "absent" so the height screen shows an empty field instead of prefilling an invalid "0"
            // (which lands the user on a disabled "height too low" Restore button).
            processValidSeedData(seedData.seed, seedData.birthday.takeIf { it > 0 })
            return
        }

        // Try plain seed phrase format (24 words separated by spaces or newlines)
        android.util.Log.d("ZCHAT_RESTORE", "JSON parse failed, trying plain seed phrase format...")
        val plainWords = qrCode.trim().split("\\s+".toRegex())
        if (plainWords.size == 24) {
            when (val result = validateSeed.validate(plainWords)) {
                is SeedValidationResult.Valid -> {
                    android.util.Log.d("ZCHAT_RESTORE", "Parsed plain seed phrase: 24 words valid")
                    // No birthday in plain format - user will need to enter it manually
                    processValidSeedData(qrCode.trim(), null)
                    return
                }
                else -> {
                    // Format was correct (24 words) but validation failed — surface the specific reason.
                    scanError.update { result.toScanErrorMessage() }
                    return
                }
            }
        }

        // NOTE: a separate "multiline" split on Regex("[\\s\\n\\r]+") used to live here, but "\\s"
        // already matches \n and \r, so it produced exactly the same tokens as plainWords above and
        // could never fire when the plain 24-word check didn't. Removed as dead code.

        android.util.Log.e("ZCHAT_RESTORE", "Invalid QR data: not JSON and not valid 24-word seed (found ${plainWords.size} words)")
        scanError.update { stringRes(R.string.restore_qr_scan_error_invalid) }
    }

    private fun SeedValidationResult.toScanErrorMessage(): StringResource =
        when (this) {
            is SeedValidationResult.InvalidChecksum -> stringRes(R.string.restore_qr_scan_error_checksum)
            is SeedValidationResult.InvalidWords -> stringRes(R.string.restore_qr_scan_error_words)
            else -> stringRes(R.string.restore_qr_scan_error_invalid)
        }

    private fun processValidSeedData(seedString: String, birthday: Long?) {
        val words = seedString.trim().split("\\s+".toRegex())
        val result = validateSeed.validate(words)
        val seedPhrase = (result as? SeedValidationResult.Valid)?.seedPhrase
        if (seedPhrase == null) {
            android.util.Log.e("ZCHAT_RESTORE", "Invalid seed phrase validation failed")
            scanError.update { result.toScanErrorMessage() }
            return
        }

        // Store the birthday for navigation (may be null for plain seed phrases)
        pendingBirthday = birthday
        android.util.Log.d("ZCHAT_RESTORE", "Navigating to RestoreBDHeight with birthday=$birthday")

        // Prefill the seed words
        prefillSeed(seedPhrase)

        // Navigate directly to height screen with the birthday
        viewModelScope.launch {
            navigationRouter.forward(RestoreBDHeight(seedPhrase.joinToString(), birthday))
        }
    }
}
