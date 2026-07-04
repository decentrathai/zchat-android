package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ResetZashiUseCase(
    private val walletCoordinator: WalletCoordinator,
    private val flexaRepository: FlexaRepository,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val biometricRepository: BiometricRepository,
    private val addressBookRepository: AddressBookRepository,
    private val metadataRepository: MetadataRepository,
    private val zchatPreferences: ZchatPreferences,
    private val contactBook: ContactBook,
    private val avatarStore: AvatarStore,
    private val navigateToError: NavigateToErrorUseCase
) {
    /**
     * Performs the wallet reset, gated behind a biometric prompt. Returns true ONLY when the wipe actually
     * completed; returns false when the user cancels/fails biometrics or the reset errors (so callers must
     * NOT show a "Success" UI on a false positive — see ChangeIdentityVM full-reset).
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend operator fun invoke(keepFiles: Boolean): Boolean {
        return try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    message =
                        stringRes(
                            R.string.authentication_system_ui_subtitle,
                            stringRes(R.string.authentication_use_case_delete_wallet)
                        )
                )
            )

            flexaRepository.disconnect()
            // Time-box the synchronizer close. closeFlow().first() can suspend forever if the
            // synchronizer is stuck/deadlocked mid-sync — and a hang (not an exception) is NOT caught
            // by the try/catch below, so the entire wallet deletion would block indefinitely with no
            // feedback. The actual data erase (clearSDK) is what matters; a slow/stuck close must not
            // hold the wipe hostage. Mirrors DestroyManager's defensive handling of the same call.
            withTimeoutOrNull(SYNCHRONIZER_CLOSE_TIMEOUT_MS) {
                (synchronizerProvider.getSynchronizer() as SdkSynchronizer).closeFlow().first()
            }
            if (!clearSDK()) throw ResetZashiException("Wallet deletion failed")
            if (!keepFiles) {
                addressBookRepository.delete()
                metadataRepository.delete()
                // ZCHAT data is keyed to the wallet seed (E2E/ratchet/NOSTR identity) and is just as
                // sensitive as the address book — without this, "Delete Wallet" left every ZCHAT message,
                // key, group and contact attached to the freshly-created wallet (privacy leak + broken
                // crypto state). Wipe it alongside the Zashi address book on a true delete. Contacts live
                // in their own encrypted store, so they need their own clear.
                zchatPreferences.clearAll()
                contactBook.clearAll()
                // Local avatars (contact/self/group photos) are wallet-scoped personal data — wipe
                // them with the other zchat_* stores. (DestroyManager needs no equivalent call: it
                // deletes filesDir + every SharedPreferences file wholesale.)
                avatarStore.clearAll()
            }
            if (!clearSharedPrefs()) throw ResetZashiException("Failed to clear shared preferences")
            clearInMemoryData()
            true // reset completed
        } catch (_: BiometricsFailureException) {
            false // user failed biometrics — nothing was reset
        } catch (_: BiometricsCancelledException) {
            false // user cancelled biometrics — nothing was reset
        } catch (e: ResetZashiException) {
            navigateToError.invoke(ErrorArgs.General(e))
            false
        } catch (e: Exception) {
            navigateToError.invoke(ErrorArgs.General(e))
            false
        }
    }

    private suspend fun clearSDK(): Boolean = walletCoordinator.deleteSdkDataFlow().first()

    private suspend fun clearSharedPrefs(): Boolean {
        val standardPrefsCleared = standardPreferenceProvider().clearPreferences()
        val encryptedPrefsCleared = encryptedPreferenceProvider().clearPreferences()
        return standardPrefsCleared && encryptedPrefsCleared
    }

    private fun clearInMemoryData() {
        homeMessageCacheRepository.reset()
    }
}

// Generous upper bound on the synchronizer close during wallet deletion — long enough for a real
// close to finish, short enough that a deadlocked synchronizer doesn't hang the wipe forever.
private const val SYNCHRONIZER_CLOSE_TIMEOUT_MS = 10_000L

private class ResetZashiException(
    message: String
) : Exception(message)
