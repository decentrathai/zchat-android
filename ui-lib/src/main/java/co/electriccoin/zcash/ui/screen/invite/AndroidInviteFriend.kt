package co.electriccoin.zcash.ui.screen.invite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.invite.view.InviteFriendView
import co.electriccoin.zcash.ui.screen.invite.viewmodel.InviteFriendVM
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidInviteFriend() {
    val viewModel = koinViewModel<InviteFriendVM>()
    val address by viewModel.userAddress.collectAsStateWithLifecycle()
    val contactCode by viewModel.contactCode.collectAsStateWithLifecycle()

    InviteFriendView(
        address = address,
        contactCode = contactCode,
        inviteText = address?.let { InviteFriendVM.buildInviteText(it) } ?: "",
        onShareInvite = { address?.let { viewModel.shareInvite(it) } },
        onShareQr = { address?.let { viewModel.shareQrCode(it) } },
        onCopyAddress = { address?.let { viewModel.copyAddress(it) } },
        onBack = { viewModel.goBack() }
    )
}
