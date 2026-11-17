package co.electriccoin.zcash.ui.screen.enhanceddestroy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationResult
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.authentication.AuthenticationUseCase
import co.electriccoin.zcash.ui.screen.enhanceddestroy.view.EnhancedDestroyView
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun AndroidEnhancedDestroy() {
    // ZC-164: the screen that sets up and runs the wipe is kept out of screenshots and the recent-apps preview,
    // like every other sensitive screen.
    if (shouldSecureScreen) {
        SecureScreen()
    }
    val activity = LocalContext.current as FragmentActivity
    val viewModel = koinViewModel<EnhancedDestroyVM>()
    val authViewModel = koinInject<AuthenticationViewModel>()
    val state by viewModel.state.collectAsState()
    val authResult by authViewModel.authenticationResult.collectAsState()

    // Handle biometric authentication result
    LaunchedEffect(authResult) {
        when (authResult) {
            is AuthenticationResult.Success -> {
                viewModel.handleBiometricSuccess()
                authViewModel.resetAuthenticationResult()
            }
            is AuthenticationResult.Error -> {
                val error = authResult as AuthenticationResult.Error
                // Already in the phone's language: it comes from the Android biometric prompt.
                viewModel.handleBiometricFailure(stringRes(error.errorMessage))
                authViewModel.resetAuthenticationResult()
            }
            AuthenticationResult.Canceled, AuthenticationResult.SystemCanceled -> {
                viewModel.handleBiometricFailure(stringRes(R.string.destroy_auth_cancelled))
                authViewModel.resetAuthenticationResult()
            }
            AuthenticationResult.Failed -> {
                viewModel.handleBiometricFailure(stringRes(R.string.destroy_auth_failed))
                authViewModel.resetAuthenticationResult()
            }
            AuthenticationResult.None -> {
                // Initial state, do nothing
            }
        }
    }

    // Create a state with biometric request handler
    val enhancedState = state.copy(
        onBiometricRequest = {
            authViewModel.authenticate(
                activity = activity,
                useCase = AuthenticationUseCase.SendFunds // Reusing existing use case
            )
        }
    )

    EnhancedDestroyView(state = enhancedState)
}
