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
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingHowItWorks
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingGetZec
import co.electriccoin.zcash.ui.screen.onboarding.ZchatTeamConstants
import co.electriccoin.zcash.ui.screen.receive.ReceiveArgs
import co.electriccoin.zcash.ui.screen.swap.SwapArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingGuideVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val walletRepository: WalletRepository,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val zchatPreferences: ZchatPreferences,
    private val context: Context,
) : ViewModel() {

    val userAddress = observeSelectedWalletAccount()
        .map { account -> account?.unified?.address?.address }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    // Our seed-derived NOSTR pubkey (64-hex) + preferred relay — embedded in the invite code so a peer
    // can start a FREE NIP-17 ("Open") chat from message #1. Null until derived (or if seed isn't ready).
    private val ourNostr = MutableStateFlow<Pair<String, String>?>(null)

    init {
        deriveOurNostr()
    }

    /** Derive our NOSTR pubkey + relay from the wallet seed (same as ZchatReceiveVM.deriveOurNostr). */
    private fun deriveOurNostr() {
        viewModelScope.launch {
            ourNostr.value = try {
                val wallet = persistableWalletProvider.requirePersistableWallet()
                val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                val identity =
                    co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(seed, zchatPreferences.getNostrRotationIndex())
                val pubHex = identity.publicKey.joinToString("") { "%02x".format(it) }
                pubHex to co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS.first()
            } catch (_: Exception) {
                null // seed not ready → code falls back to the bare address (Open not offered)
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
        val contactBook = ContactBookImpl(context)
        if (!contactBook.hasContact(ZchatTeamConstants.ADDRESS)) {
            contactBook.addContact(
                Contact(
                    address = ZchatTeamConstants.ADDRESS,
                    name = ZchatTeamConstants.NAME
                )
            )
        }
        walletRepository.completeOnboarding()
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
        navigationRouter.forward(SwapArgs)
    }
}
