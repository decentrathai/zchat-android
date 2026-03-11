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

    OnboardingIdentityView(
        address = address,
        onCopyAddress = { address?.let { viewModel.copyAddress(it) } },
        onShareAddress = { address?.let { viewModel.shareAddress(it) } },
        onContinue = { viewModel.navigateToHowItWorks() }
    )
}
