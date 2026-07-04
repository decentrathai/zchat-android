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
import co.electriccoin.zcash.ui.screen.onboarding.AndroidOnboardingGetZec
import co.electriccoin.zcash.ui.screen.onboarding.AndroidOnboardingHowItWorks
import co.electriccoin.zcash.ui.screen.onboarding.AndroidOnboardingIdentity
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
                        // Mint the wallet and go straight to the identity screen. The Destroy-PIN
                        // ("Emergency Data Wipe") step used to sit HERE — front-loading a scary
                        // "permanently deleted / have your backup ready" screen onto a newcomer who has
                        // no backup and hasn't sent a message. It now lives in Settings → Security
                        // instead. createNewWalletForOnboarding() is idempotent (#189 guard: it no-ops if
                        // a wallet already exists), so a back-then-retry can't double-mint.
                        walletViewModel.createNewWalletForOnboarding()
                        navigationRouter.forward(OnboardingIdentityCreated)
                    }
                }
            )
        }
        // Destroy-PIN ("Emergency Data Wipe") setup was relocated OUT of onboarding to
        // Settings → Advanced (registered in walletNavGraph). It must NOT be registered inside the
        // OnboardingGraph: once the wallet is READY, RootNavGraph pops the OnboardingGraph inclusive,
        // so a Settings entry pointing at an onboarding-graph route would be instantly ejected.
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
