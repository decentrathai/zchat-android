package co.electriccoin.zcash.ui

import androidx.activity.ComponentActivity
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.spackle.FirebaseTestLabUtil
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.screen.error.AndroidErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.AndroidErrorDialog
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.onboarding.AndroidDestroyPinSetup
import co.electriccoin.zcash.ui.screen.onboarding.AndroidOnboardingGetZec
import co.electriccoin.zcash.ui.screen.onboarding.AndroidOnboardingHowItWorks
import co.electriccoin.zcash.ui.screen.onboarding.AndroidOnboardingIdentity
import co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetup
import co.electriccoin.zcash.ui.screen.onboarding.Onboarding
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingGetZec
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingHowItWorks
import co.electriccoin.zcash.ui.screen.onboarding.OnboardingIdentityCreated
import co.electriccoin.zcash.ui.screen.onboarding.persistExistingWalletWithSeedPhrase
import co.electriccoin.zcash.ui.screen.onboarding.view.Onboarding
import co.electriccoin.zcash.ui.screen.restore.date.RestoreBDDateArgs
import co.electriccoin.zcash.ui.screen.restore.date.RestoreBDDateScreen
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreBDEstimationArgs
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreBDEstimationScreen
import co.electriccoin.zcash.ui.screen.restore.height.AndroidRestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.height.RestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.info.AndroidSeedInfo
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import co.electriccoin.zcash.ui.screen.restore.seed.AndroidRestoreSeed
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeed
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorArgs
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorScreen
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressScreen
import co.electriccoin.zcash.ui.screen.scan.thirdparty.AndroidThirdPartyScan
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan

fun NavGraphBuilder.onboardingNavGraph(
    activity: ComponentActivity,
    navigationRouter: NavigationRouter,
    walletViewModel: WalletViewModel
) {
    navigation<OnboardingGraph>(
        startDestination = Onboarding,
    ) {
        composable<Onboarding> {
            Onboarding(
                onImportWallet = {
                    if (FirebaseTestLabUtil.isFirebaseTestLab(activity.applicationContext)) {
                        persistExistingWalletWithSeedPhrase(
                            activity.applicationContext,
                            walletViewModel,
                            SeedPhrase.Companion.new(WalletFixture.Alice.seedPhrase),
                            WalletFixture.Alice
                                .getBirthday(ZcashNetwork.Companion.fromResources(activity.applicationContext))
                        )
                    } else {
                        navigationRouter.forward(RestoreSeed)
                    }
                },
                onCreateWallet = {
                    if (FirebaseTestLabUtil.isFirebaseTestLab(activity.applicationContext)) {
                        persistExistingWalletWithSeedPhrase(
                            activity.applicationContext,
                            walletViewModel,
                            SeedPhrase.Companion.new(WalletFixture.Alice.seedPhrase),
                            WalletFixture.Alice.getBirthday(
                                ZcashNetwork.Companion.fromResources(
                                    activity.applicationContext
                                )
                            )
                        )
                    } else {
                        // Navigate to Destroy PIN setup first, then create wallet
                        navigationRouter.forward(DestroyPinSetup(isCreatingWallet = true))
                    }
                }
            )
        }
        composable<DestroyPinSetup> { backStackEntry ->
            val args = backStackEntry.toRoute<DestroyPinSetup>()
            AndroidDestroyPinSetup(
                onPinSetupComplete = {
                    if (args.isCreatingWallet) {
                        walletViewModel.createNewWalletForOnboarding()
                        // replace() (not forward()): the destroy-PIN step is a one-shot. With forward(),
                        // Android-back from the Identity screen re-mounts DestroyPinSetup, and re-running it
                        // would call createNewWalletForOnboarding() again / overwrite the just-saved PIN.
                        navigationRouter.replace(OnboardingIdentityCreated)
                    }
                    // For restore flow, the wallet creation happens in RestoreSeed flow
                },
                onSkip = {
                    if (args.isCreatingWallet) {
                        walletViewModel.createNewWalletForOnboarding()
                        // replace() (not forward()): the destroy-PIN step is a one-shot. With forward(),
                        // Android-back from the Identity screen re-mounts DestroyPinSetup, and re-running it
                        // would call createNewWalletForOnboarding() again / overwrite the just-saved PIN.
                        navigationRouter.replace(OnboardingIdentityCreated)
                    }
                    // For restore flow, the wallet creation happens in RestoreSeed flow
                }
            )
        }
        composable<OnboardingIdentityCreated> { AndroidOnboardingIdentity() }
        composable<OnboardingHowItWorks> { AndroidOnboardingHowItWorks() }
        composable<OnboardingGetZec> { AndroidOnboardingGetZec() }
        composable<RestoreSeed> { AndroidRestoreSeed() }
        composable<RestoreBDHeight> { AndroidRestoreBDHeight(it.toRoute()) }
        composable<RestoreBDDateArgs> { RestoreBDDateScreen(it.toRoute()) }
        composable<RestoreBDEstimationArgs> { RestoreBDEstimationScreen(it.toRoute()) }
        dialogComposable<SeedInfo> { AndroidSeedInfo() }
        composable<ScanArgs> { ScanZashiAddressScreen(it.toRoute()) }
        composable<ThirdPartyScan> { AndroidThirdPartyScan() }
        dialogComposable<ErrorDialog> { AndroidErrorDialog() }
        dialogComposable<ErrorBottomSheet> { AndroidErrorBottomSheet() }
        dialogComposable<RestoreTorArgs> { RestoreTorScreen(it.toRoute()) }
    }
}
