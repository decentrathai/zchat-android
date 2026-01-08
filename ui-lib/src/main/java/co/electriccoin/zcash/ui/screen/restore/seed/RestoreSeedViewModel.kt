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
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
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
    private val scanError = MutableStateFlow<String?>(null)

    // Pending birthday from QR scan (to navigate after seed is validated)
    private var pendingBirthday: Long? = null

    init {
        // Observe camera scan results from PrefillRestoreSeedUseCase
        viewModelScope.launch {
            prefillRestoreSeed.scannedQrData.collect { qrData ->
                if (qrData != null) {
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
        error: String?
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
            navigationRouter.forward(RestoreBDHeight(seed.joinToString()))
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
        if (qrCode == null) {
            scanError.update { "No QR code found in the selected image." }
            return
        }
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
        val seedData = SeedBackupQrData.decode(qrCode)
        if (seedData == null || !SeedBackupQrData.isValid(seedData)) {
            scanError.update { "Invalid QR code. Please scan a valid seed backup QR code." }
            return
        }

        // Parse the seed words
        val words = seedData.seed.trim().split("\\s+".toRegex())
        val seedPhrase = validateSeed(words)
        if (seedPhrase == null) {
            scanError.update { "Invalid seed phrase in QR code." }
            return
        }

        // Store the birthday for navigation
        pendingBirthday = seedData.birthday

        // Prefill the seed words
        prefillSeed(seedPhrase)

        // Navigate directly to height screen with the birthday
        viewModelScope.launch {
            navigationRouter.forward(RestoreBDHeight(seedPhrase.joinToString(), seedData.birthday))
        }
    }
}
