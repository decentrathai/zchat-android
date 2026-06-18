package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import co.electriccoin.zcash.ui.screen.chat.model.ZchatReceiveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ZchatReceiveVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val zchatPreferences: ZchatPreferences
) : ViewModel() {

    private val showingTransparent = MutableStateFlow(false)

    // Store the default unified address (consistent after wallet restore)
    private val defaultUnifiedAddress = MutableStateFlow<String?>(null)

    // Our seed-derived NOSTR pubkey (64-hex) + preferred relay, for embedding in the contact code so a
    // peer can start a free NOSTR ("Open") chat. Null until derived (or if the seed isn't ready yet).
    private val ourNostr = MutableStateFlow<Pair<String, String>?>(null)

    // True once the default-address load has failed (exception or null/empty result).
    // Lets combine() emit Error instead of spinning on Loading forever.
    private val loadFailed = MutableStateFlow(false)

    init {
        loadDefaultAddress()
        deriveOurNostr()
    }

    /** Derive our NOSTR pubkey + relay from the wallet seed (same as ChatViewModel.getOurNostrPubkey). */
    private fun deriveOurNostr() {
        viewModelScope.launch {
            ourNostr.value = try {
                val wallet = persistableWalletProvider.requirePersistableWallet()
                val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                val identity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(seed, zchatPreferences.getNostrRotationIndex())
                val pubHex = identity.publicKey.joinToString("") { "%02x".format(it) }
                pubHex to co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS.first()
            } catch (_: Exception) {
                null // seed not ready → contact code falls back to bare address (Open not offered)
            }
        }
    }

    private fun loadDefaultAddress() {
        viewModelScope.launch {
            loadFailed.value = false
            try {
                val address = getDefaultUnifiedAddress()
                if (address.isEmpty()) {
                    loadFailed.value = true
                } else {
                    defaultUnifiedAddress.value = address
                }
            } catch (_: Exception) {
                loadFailed.value = true
            }
        }
    }

    val state = combine(
        observeSelectedWalletAccount.require(),
        showingTransparent,
        defaultUnifiedAddress,
        loadFailed,
        ourNostr
    ) { account, isShowingTransparent, defaultAddress, hasFailed, nostr ->
        if (hasFailed) {
            return@combine ZchatReceiveState.Error(
                message = "Couldn't load your address. Please try again.",
                onRetry = { loadDefaultAddress() },
                onBack = { navigationRouter.back() }
            )
        }

        // Wait for the default unified address to be loaded
        // This address (diversifier 0) is deterministic and consistent after wallet restore
        // Do NOT use the account.unified.address as fallback - it may be a different diversified address
        if (defaultAddress == null) {
            return@combine ZchatReceiveState.Loading
        }

        val transparentAddress = account.transparent.address.address

        // Guard against corrupted account data yielding blank addresses, which would
        // otherwise flow into a blank QR / Text / copy / share.
        if (defaultAddress.isEmpty() || transparentAddress.isEmpty()) {
            return@combine ZchatReceiveState.Error(
                message = "Couldn't load your address. Please try again.",
                onRetry = { loadDefaultAddress() },
                onBack = { navigationRouter.back() }
            )
        }

        // Build the ZCHAT contact code from the SHIELDED address + our NOSTR key (when derived). When
        // NOSTR isn't available, the code is just the bare address and the scanner won't get an Open key.
        val code = ZchatContactCode(
            zcashAddress = defaultAddress,
            nostrPubkeyHex = nostr?.first,
            relayUrl = nostr?.second,
        )
        // The QR carries the full code ONLY when it adds value (has the NOSTR key); otherwise fall back
        // to the bare shielded address so the QR stays a normal, widely-scannable Zcash address.
        val codeQr = if (code.supportsOpen) code.serialize() else defaultAddress
        val codeText = code.serialize()

        ZchatReceiveState.Success(
            shieldedAddress = defaultAddress,
            transparentAddress = transparentAddress,
            showingTransparent = isShowingTransparent,
            contactCodeQr = codeQr,
            contactCodeText = codeText,
            supportsOpen = code.supportsOpen,
            onCopyAddress = {
                val address = if (isShowingTransparent) {
                    transparentAddress
                } else {
                    defaultAddress
                }
                copyToClipboard(address)
            },
            onCopyContactCode = { copyToClipboard(codeText) },
            onShowTransparent = { showingTransparent.update { true } },
            onShowShielded = { showingTransparent.update { false } },
            onBack = { navigationRouter.back() }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
        initialValue = ZchatReceiveState.Loading
    )
}
