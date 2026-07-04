package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.onboarding.view.OnboardingIdentityView
import co.electriccoin.zcash.ui.screen.onboarding.viewmodel.OnboardingGuideVM
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidOnboardingIdentity() {
    val viewModel = koinViewModel<OnboardingGuideVM>()
    val address by viewModel.userAddress.collectAsStateWithLifecycle()
    val contactCode by viewModel.contactCode.collectAsStateWithLifecycle()

    OnboardingIdentityView(
        address = address,
        contactCode = contactCode,
        // Fall back to the bare address if the invite code hasn't derived yet (it's a separate flow that
        // lags the address by a frame or two) so a fast Copy/Share tap never silently no-ops.
        onCopy = { (contactCode ?: address)?.let { viewModel.copyContactCode(it) } },
        onShare = { (contactCode ?: address)?.let { viewModel.shareContactCode(it) } },
        onContinue = { viewModel.navigateToHowItWorks() }
    )
}
