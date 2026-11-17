package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.call.CallController
import co.electriccoin.zcash.ui.call.VoiceCallManager
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepository
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.TransactionFilterRepository
import co.electriccoin.zcash.ui.common.repository.TransactionFilterRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepository
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.tex.SdkTexWallet
import co.electriccoin.zcash.ui.common.tex.SharedPrefsTexRecoveryStore
import co.electriccoin.zcash.ui.common.tex.StoredTexTor
import co.electriccoin.zcash.ui.common.tex.TexIdleRule
import co.electriccoin.zcash.ui.common.tex.TexRecoveryNotifier
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val repositoryModule =
    module {
        singleOf(::WalletRepositoryImpl) bind WalletRepository::class
        singleOf(::ConfigurationRepositoryImpl) bind ConfigurationRepository::class
        singleOf(::ExchangeRateRepositoryImpl) bind ExchangeRateRepository::class
        singleOf(::FlexaRepositoryImpl) bind FlexaRepository::class
        singleOf(::BiometricRepositoryImpl) bind BiometricRepository::class
        singleOf(::KeystoneProposalRepositoryImpl) bind KeystoneProposalRepository::class
        // ZC-1273: named, as the constructor's default exists only for tests.
        single { TransactionRepositoryImpl(get(), get(), androidContext()) } bind TransactionRepository::class
        singleOf(::TransactionFilterRepositoryImpl) bind TransactionFilterRepository::class
        singleOf(::ZashiProposalRepositoryImpl) bind ZashiProposalRepository::class
        singleOf(::HomeMessageCacheRepositoryImpl) bind HomeMessageCacheRepository::class
        singleOf(::WalletSnapshotRepositoryImpl) bind WalletSnapshotRepository::class
        singleOf(::ApplicationStateRepositoryImpl) bind ApplicationStateRepository::class
        singleOf(::SwapRepositoryImpl) bind SwapRepository::class
        singleOf(::EphemeralAddressRepositoryImpl) bind EphemeralAddressRepository::class
        // F3 (fix-all 2.13.0): the TEX checker and its automatic recovery; started by SyncForegroundService.
        single {
            val keystone: KeystoneProposalRepository = get()
            TexSendMonitor(
                wallet =
                    SdkTexWallet(
                        synchronizerProvider = get(),
                        accountDataSource = get(),
                        proposalDataSource = get(),
                        spendingKeyDataSource = get(),
                        persistableWalletProvider = get(),
                    ),
                tor = StoredTexTor(isTorEnabledStorageProvider = get(), synchronizerProvider = get()),
                idle =
                    TexIdleRule(
                        callLive = ::isCallLive,
                        keystonePcztOpen = { keystone.getProposalPCZT() != null },
                    ),
                store = SharedPrefsTexRecoveryStore(androidContext()),
                notice = TexRecoveryNotifier(androidContext()),
            )
        }
    }

/** F3: a call is ringing, dialling, connecting or in progress (the TEX recovery waits for it to end). */
private fun isCallLive(): Boolean {
    val state =
        CallController.current.value
            ?.state
            ?.value ?: return false
    return state !is VoiceCallManager.CallState.Idle && state !is VoiceCallManager.CallState.Ended
}
