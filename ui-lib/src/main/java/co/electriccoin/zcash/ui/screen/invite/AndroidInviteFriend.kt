package co.electriccoin.zcash.ui.screen.invite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.invite.view.InviteFriendView
import co.electriccoin.zcash.ui.screen.invite.viewmodel.InviteFriendVM
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidInviteFriend() {
    val viewModel = koinViewModel<InviteFriendVM>()
    val address by viewModel.userAddress.collectAsStateWithLifecycle()
    val contactCode by viewModel.contactCode.collectAsStateWithLifecycle()
    // ES-A: the invite text in the language the app shows; the preview and both shares use this same string.
    val inviteText = address?.let { stringResource(R.string.invite_share_text, InviteFriendVM.INVITE_DOWNLOAD_URL, it) }
    val shareInviteTitle = stringResource(R.string.invite_share_invite)
    val shareQrTitle = stringResource(R.string.invite_share_qr)
    val shareCodeQrTitle = stringResource(R.string.invite_share_code_qr)

    InviteFriendView(
        address = address,
        contactCode = contactCode,
        inviteText = inviteText ?: "",
        onShareInvite = { inviteText?.let { viewModel.shareInvite(it, shareInviteTitle) } },
        onShareQr = {
            val shown = inviteText
            address?.let { if (shown != null) viewModel.shareQrCode(it, shown, shareQrTitle) }
        },
        onShareCodeQr = {
            val shown = inviteText
            contactCode?.let { if (shown != null) viewModel.shareContactCodeQr(it, shown, shareCodeQrTitle) }
        },
        onCopyAddress = { address?.let { viewModel.copyAddress(it) } },
        onBack = { viewModel.goBack() }
    )
}
