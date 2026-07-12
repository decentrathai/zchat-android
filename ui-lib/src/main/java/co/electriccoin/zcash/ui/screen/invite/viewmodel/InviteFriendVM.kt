package co.electriccoin.zcash.ui.screen.invite.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InviteFriendVM(
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
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
            val address =
                try {
                    getDefaultUnifiedAddress().takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
            _userAddress.value = address

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

    fun shareInvite(address: String) {
        val inviteText = buildInviteText(address)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, inviteText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Invite").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun shareQrCode(address: String) {
        viewModelScope.launch {
            shareQR(
                qrData = address,
                shareText = buildInviteText(address),
                sharePickerText = "Share QR Code",
                filenamePrefix = "zchat_invite"
            )
        }
    }

    fun goBack() {
        navigationRouter.back()
    }

    companion object {
        fun buildInviteText(address: String): String =
            "Join me on ZCHAT \u2014 private messaging that no one can read.\n" +
                "Download: https://zsend.xyz/download\n" +
                "My address: $address"
    }
}
