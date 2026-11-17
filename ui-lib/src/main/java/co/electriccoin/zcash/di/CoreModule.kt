package co.electriccoin.zcash.di

import androidx.biometric.BiometricManager
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.configuration.AndroidConfigurationFactory
import co.electriccoin.zcash.global.newInstance
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationRouterImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.openPhraseStoreGuarded
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val coreModule =
    module {
        single {
            WalletCoordinator.newInstance(
                context = get(),
                persistableWalletProvider = get(),
                isTorEnabledStorageProvider = get(),
                isExchangeRateEnabledStorageProvider = get()
            )
        }
        singleOf(::StandardPreferenceProvider)
        // ZC-705: the recovery-phrase store opens through the guard on its two key entries (KeysetGuard.kt).
        single { EncryptedPreferenceProvider(get(), open = ::openPhraseStoreGuarded) }
        single { BiometricManager.from(get()) }
        factory { AndroidConfigurationFactory.new() }
        singleOf(::NavigationRouterImpl) bind NavigationRouter::class
    }
