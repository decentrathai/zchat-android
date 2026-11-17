package co.electriccoin.zcash.ui.screen.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationTargets
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressState.ColorMode.DEFAULT
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressState.ColorMode.KEYSTONE
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressState.ColorMode.ZASHI
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType.Sapling
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType.Transparent
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType.Unified
import co.electriccoin.zcash.ui.screen.receive.info.ShieldedAddressInfoArgs
import co.electriccoin.zcash.ui.screen.receive.info.TransparentAddressInfoArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ReceiveVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    internal val state =
        observeSelectedWalletAccount
            .require()
            .map { account ->
                ReceiveState(
                    items =
                        listOfNotNull(
                            createAddressState(
                                account = account,
                                address = account.unified.address.address,
                                type = Unified,
                                // ZC-1719 (QA2): both cards stay open, so a tap on the transparent card no longer
                                // hides the shielded card's Copy, QR and Request.
                                isExpanded = true,
                                onClick = null
                            ),
                            createAddressState(
                                account = account,
                                address = account.transparent.address.address,
                                type = Transparent,
                                // QA2 Q2-07 (ZC-1700): the transparent card always shows Copy, QR and Request; it has
                                // no other cue that a tap opens them.
                                isExpanded = true,
                                onClick = null
                            ),
                        ),
                    isLoading = false,
                    onBack = ::onBack
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    ReceiveState(
                        items = null,
                        isLoading = true,
                        onBack = ::onBack
                    )
            )

    private fun onBack() = navigationRouter.back()

    @Suppress("CyclomaticComplexMethod")
    private fun createAddressState(
        account: WalletAccount,
        address: String,
        type: ReceiveAddressType,
        isExpanded: Boolean,
        onClick: (() -> Unit)?,
    ) = ReceiveAddressState(
        icon =
            when (account) {
                is KeystoneAccount -> co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone
                is ZashiAccount -> R.drawable.ic_zec_round_full
            },
        title =
            when (account) {
                is KeystoneAccount ->
                    if (type == Unified) {
                        stringRes(R.string.receive_wallet_address_shielded_keystone)
                    } else {
                        stringRes(R.string.receive_wallet_address_transparent_keystone)
                    }

                is ZashiAccount ->
                    if (type == Unified) {
                        stringRes(R.string.receive_wallet_address_shielded)
                    } else {
                        stringRes(R.string.receive_wallet_address_transparent)
                    }
            },
        subtitle = stringResByAddress(value = address, middle = true),
        isShielded = type == Unified,
        onCopyClicked = {
            copyToClipboard(
                value = address
            )
        },
        onQrClicked = { onQrCodeClick(type) },
        onRequestClicked = { onRequestClick(type) },
        onClick = onClick,
        isExpanded = isExpanded,
        colorMode =
            when (account) {
                is KeystoneAccount -> if (type == Unified) KEYSTONE else DEFAULT
                is ZashiAccount -> if (type == Unified) ZASHI else DEFAULT
            },
        infoIconButton =
            IconButtonState(
                when (type) {
                    Sapling,
                    Unified ->
                        when (account) {
                            is KeystoneAccount -> R.drawable.ic_receive_ks_shielded_info
                            is ZashiAccount -> R.drawable.ic_receive_zashi_shielded_info
                        }
                    Transparent -> R.drawable.ic_receive_zcash_info
                },
                // QA2 Q2-05 (ZC-1599)
                contentDescription =
                    when (type) {
                        Sapling,
                        Unified -> stringRes(R.string.receive_info_shielded_content_description)
                        Transparent -> stringRes(R.string.receive_info_transparent_content_description)
                    },
                onClick = { onAddressInfoClick(type) }
            )
    )

    private fun onRequestClick(addressType: ReceiveAddressType) =
        navigationRouter.forward("${NavigationTargets.REQUEST}/${addressType.ordinal}")

    private fun onQrCodeClick(addressType: ReceiveAddressType) =
        navigationRouter.forward("${NavigationTargets.QR_CODE}/${addressType.ordinal}")

    private fun onAddressInfoClick(type: ReceiveAddressType) {
        when (type) {
            Sapling,
            Unified -> navigationRouter.forward(ShieldedAddressInfoArgs)
            Transparent -> navigationRouter.forward(TransparentAddressInfoArgs)
        }
    }
}
