package co.electriccoin.zcash.ui.screen.invite.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InviteFriendVM(
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val shareQR: ShareQRUseCase,
    private val navigationRouter: NavigationRouter,
    private val context: Context,
) : ViewModel() {

    // The invite MUST carry the canonical diversifier-0 UA — the SAME identity we KEX-sign and show on
    // the Receive screen. account.unified.address can be a DIFFERENT diversified address; sharing that
    // would make the invited friend store a drifted first-contact address (addr-drift, see #205 and the
    // "Do NOT use account.unified.address" note in ChatViewModel / ZchatReceiveVM).
    private val _userAddress = MutableStateFlow<String?>(null)
    val userAddress: StateFlow<String?> = _userAddress.asStateFlow()

    init {
        viewModelScope.launch {
            _userAddress.value =
                try {
                    getDefaultUnifiedAddress().takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
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
