package co.electriccoin.zcash.ui.screen.invite.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InviteFriendVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val shareQR: ShareQRUseCase,
    private val navigationRouter: NavigationRouter,
    private val context: Context,
) : ViewModel() {

    val userAddress = observeSelectedWalletAccount()
        .map { account -> account?.unified?.address?.address }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

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
