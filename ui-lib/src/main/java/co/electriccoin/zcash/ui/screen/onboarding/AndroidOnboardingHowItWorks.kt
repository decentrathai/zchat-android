package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.screen.onboarding.view.OnboardingHowItWorksView
import co.electriccoin.zcash.ui.screen.onboarding.viewmodel.OnboardingGuideVM
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidOnboardingHowItWorks() {
    val viewModel = koinViewModel<OnboardingGuideVM>()

    OnboardingHowItWorksView(
        onIHaveZec = { viewModel.completeOnboarding() },
        onINeedZec = { viewModel.navigateToGetZec() },
        onWhatIsZec = { viewModel.navigateToGetZec() }
    )
}
