package co.electriccoin.zcash.ui.screen.onboarding.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingHowItWorks
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingGetZec
import co.electriccoin.zcash.ui.screen.onboarding.ZchatTeamConstants
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingGuideVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val walletRepository: WalletRepository,
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

    fun shareAddress(address: String) {
        val shareText = "My ZCHAT address:\n$address"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Address").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun shareQrCode(address: String) {
        viewModelScope.launch {
            shareQR(
                qrData = address,
                shareText = "My ZCHAT address: $address",
                sharePickerText = "Share QR Code",
                filenamePrefix = "zchat_address"
            )
        }
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
}
