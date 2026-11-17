package co.electriccoin.zcash.ui.screen.onboarding.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.nostr.NostrRelayPool
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingHowItWorks
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingGetZec
import co.electriccoin.zcash.ui.screen.onboarding.TeamNostrKey
import co.electriccoin.zcash.ui.screen.onboarding.ZchatTeamConstants
import co.electriccoin.zcash.ui.screen.receive.ReceiveArgs
import co.electriccoin.zcash.ui.screen.swap.SwapArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingGuideVM(
    // Not read since Bridge 3: its unified address is the rotating Receive address, never an invite's.
    @Suppress("UNUSED_PARAMETER", "unused")
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val walletRepository: WalletRepository,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val zchatPreferences: ZchatPreferences,
    private val contactBook: ContactBook,
    private val context: Context,
    // ZC-1458: the one swap entry, which starts the asset load before it opens the swap screen. Koin always passes
    // it; null only where a test builds the screen without a swap path (the swap screen then opens without the load).
    private val navigateToSwap: NavigateToSwapUseCase? = null,
) : ViewModel() {

    // Bridge 3: the invite carries the chat identity (My Address), the address every chat message carries,
    // exactly as Invite Friend does. `account.unified.address` is the rotating Receive address: a friend who
    // added us from it stored an address our messages never come from, so our first reply opened a second,
    // separate chat on their side. Null until the identity is known (the wallet is still being created):
    // never the rotating address in the meantime.
    private val _userAddress = MutableStateFlow<String?>(null)
    val userAddress: StateFlow<String?> = _userAddress.asStateFlow()

    // Our seed-derived NOSTR pubkey (64-hex) + preferred relay — embedded in the invite code so a peer
    // can start a FREE NIP-17 ("Open") chat from message #1. Null until derived (or if seed isn't ready).
    private val ourNostr = MutableStateFlow<Pair<String, String>?>(null)

    init {
        loadIdentityAddress()
        deriveOurNostr()
    }

    /**
     * Reads the chat identity. It waits for the wallet and its account while onboarding creates them; a read
     * that fails (the wallet is still starting) is tried again a few times rather than leaving no invite.
     */
    private fun loadIdentityAddress() {
        viewModelScope.launch {
            repeat(IDENTITY_READ_ATTEMPTS) { attempt ->
                val address =
                    try {
                        getDefaultUnifiedAddress().takeIf { it.isNotEmpty() }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                if (address != null) {
                    _userAddress.value = address
                    return@launch
                }
                if (attempt < IDENTITY_READ_ATTEMPTS - 1) delay(IDENTITY_READ_RETRY_MS)
            }
        }
    }

    /** Derive our NOSTR pubkey + relay from the wallet seed (same as ZchatReceiveVM.deriveOurNostr). */
    private fun deriveOurNostr() {
        viewModelScope.launch {
            ourNostr.value = try {
                // Onboarding runs this WHILE the wallet is still minting (the birthday gRPC fetch is in
                // flight), so requirePersistableWallet() would throw and pin ourNostr=null forever —
                // leaving the invite code WITHOUT the NOSTR key, silently breaking the free-first flow.
                // Wait REACTIVELY for the wallet to be persisted, then derive.
                val wallet = persistableWalletProvider.persistableWallet.filterNotNull().first()
                // BIP39 toSeed() is PBKDF2-HMAC-SHA512 x2048 (CPU-bound); keep it (and the NOSTR key
                // derivation) off the Main dispatcher so the invite/QR screen doesn't jank on entry.
                withContext(Dispatchers.Default) {
                    val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                    val identity =
                        co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(
                            seed,
                            zchatPreferences.getNostrRotationIndex()
                        )
                    val pubHex = identity.publicKey.joinToString("") { "%02x".format(it) }
                    // M1: our own relay key never binds to a contact (a pasted own code binds nothing).
                    zchatPreferences.registerOwnNostrPubkey(pubHex)
                    pubHex to co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS.first()
                }
            } catch (e: Throwable) {
                // Broaden to Throwable (not just Exception): a native secp256k1 load failure surfaces as
                // ExceptionInInitializerError / UnsatisfiedLinkError — Errors, NOT Exceptions — which
                // catch(Exception) would let ESCAPE and hard-crash onboarding (the exact create/restore
                // crash we hit on release before extractNativeLibs=true). Degrade gracefully instead: the
                // invite code falls back to the bare address (Open not offered). Rethrow
                // CancellationException so structured-concurrency cancellation still propagates.
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }
    }

    /**
     * The shareable ZCHAT invite code (`zchat:c1?z=…&n=…&r=…`) — carries our address + seed-derived
     * NOSTR key so a friend who scans/pastes it can message us FOR FREE from message #1. A newcomer's
     * first action is to be reachable for a free chat, not to receive a payment, so this is what
     * onboarding Copy/Share/QR hand out. Falls back to the bare address until the NOSTR key derives.
     */
    val contactCode = combine(userAddress, ourNostr) { addr, nostr ->
        addr?.let {
            ZchatContactCode(
                zcashAddress = it,
                nostrPubkeyHex = nostr?.first,
                relayUrl = nostr?.second,
            ).serialize()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
        initialValue = null
    )

    /** R2-3: creating the wallet failed (nothing was saved); the screen shows an error with Try again. */
    val walletCreationFailed = walletRepository.walletCreationFailed

    fun retryWalletCreation() {
        walletRepository.createNewWalletForOnboarding()
    }

    fun copyContactCode(code: String) {
        copyToClipboard(code)
    }

    fun shareContactCode(code: String) {
        val shareText = "Message me privately on ZCHAT:\n$code"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share ZCHAT Invite").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun navigateToHowItWorks() {
        navigationRouter.forward(OnboardingHowItWorks)
    }

    fun navigateToGetZec() {
        navigationRouter.forward(OnboardingGetZec)
    }

    fun completeOnboarding() {
        // The ContactBook is Keystore-backed (EncryptedSharedPreferences init is ~hundreds of ms) and
        // MUST NOT be touched on the main thread — do the read/write on IO using the injected singleton
        // (not a freshly-constructed ContactBookImpl). Flip onboarding to READY only AFTER the ZCHAT-team
        // contact is persisted, so the READY-driven VM teardown can't cancel the write mid-flight.
        viewModelScope.launch(Dispatchers.IO) {
            if (!contactBook.hasContact(ZchatTeamConstants.ADDRESS)) {
                // ZC-733-B: goes on either way; a book that cannot be read is said by the chat list's line.
                contactBook.tryAddContact(
                    Contact(
                        address = ZchatTeamConstants.ADDRESS,
                        name = ZchatTeamConstants.NAME
                    )
                )
            }
            // ZC-1460: the Team contact gets the Team's relay key, so it can be reached free in Open. Off (nothing
            // read or written) while the build input is empty. A store that cannot be written never stops onboarding.
            runCatching {
                TeamNostrKey.bind(
                    prefs = zchatPreferences,
                    teamAddress = ZchatTeamConstants.ADDRESS,
                    pubkeyHex = ZchatTeamConstants.NOSTR_PUBKEY,
                    relayUrl = NostrRelayPool.DEFAULT_RELAYS.first(),
                )
            }
            walletRepository.completeOnboarding()
        }
    }

    /**
     * Finish onboarding then jump to the Receive screen. Used for both "request from friend"
     * and "centralized exchange" — both flows need the user's wallet QR + copyable address.
     */
    fun completeOnboardingAndShowReceive() {
        completeOnboarding()
        navigationRouter.forward(ReceiveArgs)
    }

    /** Finish onboarding then jump to the in-app Swap flow. */
    fun completeOnboardingAndShowSwap() {
        completeOnboarding()
        navigateToSwap?.invoke() ?: navigationRouter.forward(SwapArgs)
    }

    private companion object {
        const val IDENTITY_READ_ATTEMPTS = 10
        const val IDENTITY_READ_RETRY_MS = 2_000L
    }
}
