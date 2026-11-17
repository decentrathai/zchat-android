package co.electriccoin.zcash.ui.screen.invite.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InviteFriendVM(
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val shareQR: ShareQRUseCase,
    private val navigationRouter: NavigationRouter,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val zchatPreferences: ZchatPreferences,
    private val context: Context,
) : ViewModel() {

    // The invite MUST carry the canonical diversifier-0 UA — the SAME identity we KEX-sign and show on
    // the Receive screen. account.unified.address can be a DIFFERENT diversified address; sharing that
    // would make the invited friend store a drifted first-contact address (addr-drift, see #205 and the
    // "Do NOT use account.unified.address" note in ChatViewModel / ZchatReceiveVM).
    private val _userAddress = MutableStateFlow<String?>(null)
    val userAddress: StateFlow<String?> = _userAddress.asStateFlow()

    // The ZCHAT contact code (zchat:c1?z=…&n=…&r=…) — the payable address PLUS our seed-derived NOSTR
    // messaging key, so a friend who scans it gets our NOSTR key persisted and can start a FREE NOSTR
    // ("Open") chat from message #1 with NO on-chain handshake. Null until derived — or if the NOSTR key
    // isn't available — in which case the invite screen just shows the plain-address QR and offers no
    // second QR. Mirrors ZchatReceiveVM.deriveOurNostr + contactCodeText (kept money-safe: Open avoids
    // the on-chain TUNNEL ZBOOT).
    private val _contactCode = MutableStateFlow<String?>(null)
    val contactCode: StateFlow<String?> = _contactCode.asStateFlow()

    init {
        viewModelScope.launch {
            // QR-7: the selected account is read once, so the address and the Keystone check are the same account's.
            val account =
                try {
                    getSelectedWalletAccount()
                } catch (_: Exception) {
                    null
                }
            val address =
                try {
                    account?.let { getDefaultUnifiedAddress.forAccount(it) }?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
            _userAddress.value = address

            // QR-7: the code pairs the selected account's address with this phone's relay key (from the ZChat seed). For
            // a Keystone account the two are not one identity, so there is no code: no code QR and no share of it.
            if (account is KeystoneAccount) {
                _contactCode.value = null
                return@launch
            }

            if (address != null) {
                // Derive our NOSTR pubkey + relay from the wallet seed (same as ZchatReceiveVM.deriveOurNostr)
                // and build the contact code so the invite can also expose the free-Open messaging key.
                val nostr =
                    try {
                        val wallet = persistableWalletProvider.requirePersistableWallet()
                        val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                        val identity = co.electriccoin.zcash.ui.nostr.NOSTRIdentity.fromSeed(
                            seed,
                            zchatPreferences.getNostrRotationIndex()
                        )
                        val pubHex = identity.publicKey.joinToString("") { "%02x".format(it) }
                        pubHex to co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS.first()
                    } catch (e: Throwable) {
                        // Broaden to Throwable: a native secp256k1 load failure surfaces as an Error, not an
                        // Exception, and would otherwise escape. Degrade gracefully (no Open key → no second
                        // QR). Rethrow CancellationException so structured-concurrency cancellation propagates.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                _contactCode.value = ZchatContactCode(
                    zcashAddress = address,
                    nostrPubkeyHex = nostr?.first,
                    relayUrl = nostr?.second,
                ).takeIf { it.supportsOpen }?.serialize()
            }
        }
    }

    fun copyAddress(address: String) {
        copyToClipboard(address)
    }

    /**
     * ES-A: [text] is the invite the screen shows (invite_share_text, in the language the app shows) and
     * [chooserTitle] the system chooser's title, both read by the composable, so what is shared is what the preview
     * says.
     */
    fun shareInvite(
        text: String,
        chooserTitle: String,
    ) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** The QR still encodes [address] only; [text] and [pickerText] are the shown invite and the chooser's title. */
    fun shareQrCode(
        address: String,
        text: String,
        pickerText: String,
    ) {
        viewModelScope.launch {
            shareQR(
                qrData = address,
                shareText = text,
                sharePickerText = pickerText,
                filenamePrefix = "zchat_invite"
            )
        }
    }

    /**
     * QR-7: the free-chat code as a picture, the code QR the screen shows (drawn with the one margin every QR uses);
     * [text] and [pickerText] as in [shareQrCode].
     */
    fun shareContactCodeQr(
        code: String,
        text: String,
        pickerText: String,
    ) {
        viewModelScope.launch {
            shareQR(
                qrData = code,
                shareText = text,
                sharePickerText = pickerText,
                filenamePrefix = "zchat_free_chat"
            )
        }
    }

    fun goBack() {
        navigationRouter.back()
    }

    companion object {
        /** The download link in the invite text (invite_share_text's %1$s); never translated. */
        const val INVITE_DOWNLOAD_URL = "https://zsend.xyz/download"
    }
}
