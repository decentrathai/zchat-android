package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.screen.onboarding.view.OnboardingGetZecView
import co.electriccoin.zcash.ui.screen.onboarding.viewmodel.OnboardingGuideVM
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidOnboardingGetZec() {
    val viewModel = koinViewModel<OnboardingGuideVM>()

    OnboardingGetZecView(
        onContinue = { viewModel.completeOnboarding() },
        onSkip = { viewModel.completeOnboarding() },
        onRequestFromFriend = { viewModel.completeOnboardingAndShowReceive() },
        onCentralizedExchange = { viewModel.completeOnboardingAndShowReceive() },
        onInAppSwap = { viewModel.completeOnboardingAndShowSwap() },
    )
}
