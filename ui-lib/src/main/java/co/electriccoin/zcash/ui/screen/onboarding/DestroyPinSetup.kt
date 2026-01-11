package co.electriccoin.zcash.ui.screen.onboarding

import kotlinx.serialization.Serializable

/**
 * Navigation route for the Destroy PIN setup screen during onboarding.
 * @param isCreatingWallet true if user is creating a new wallet, false if restoring
 */
@Serializable
data class DestroyPinSetup(
    val isCreatingWallet: Boolean
)
