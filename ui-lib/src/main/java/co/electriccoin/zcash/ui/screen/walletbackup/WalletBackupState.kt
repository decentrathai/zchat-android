package co.electriccoin.zcash.ui.screen.walletbackup

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.SeedTextState
import co.electriccoin.zcash.ui.design.util.StringResource

data class WalletBackupState(
    val seed: SeedTextState,
    val birthday: SeedSecretState,
    val info: IconButtonState,
    val secondaryButton: ButtonState?,
    val primaryButton: ButtonState,
    val onBack: (() -> Unit)?,
    // QR code backup
    val qrState: QrState? = null,
    val showQrCode: Boolean = false,
    val onShowQrClick: (() -> Unit)? = null,
    val onHideQrClick: (() -> Unit)? = null
)

data class SeedSecretState(
    val title: StringResource,
    val text: StringResource,
    val isRevealed: Boolean,
    val tooltip: SeedSecretStateTooltip?,
    val onClick: (() -> Unit)?,
)

data class SeedSecretStateTooltip(
    val title: StringResource,
    val message: StringResource,
)
