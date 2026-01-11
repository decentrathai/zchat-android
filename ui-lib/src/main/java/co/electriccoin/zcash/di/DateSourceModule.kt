package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.AccountDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSource
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.NearSwapDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSourceImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AddressCacheImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSource
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSourceImpl
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val dataSourceModule =
    module {
        singleOf(::AccountDataSourceImpl) bind AccountDataSource::class
        singleOf(::ZashiSpendingKeyDataSourceImpl) bind ZashiSpendingKeyDataSource::class
        singleOf(::ProposalDataSourceImpl) bind ProposalDataSource::class
        singleOf(::RestoreTimestampDataSourceImpl) bind RestoreTimestampDataSource::class
        singleOf(::MessageAvailabilityDataSourceImpl) bind MessageAvailabilityDataSource::class
        singleOf(::WalletSnapshotDataSourceImpl) bind WalletSnapshotDataSource::class
        singleOf(::NearSwapDataSourceImpl) bind SwapDataSource::class
        singleOf(::ExchangeRateDataSourceImpl) bind ExchangeRateDataSource::class
        singleOf(::AddressCacheImpl) bind AddressCache::class
        singleOf(::ContactBookImpl) bind ContactBook::class
        singleOf(::ZchatPreferencesImpl) bind ZchatPreferences::class
        singleOf(::ThemePreferenceDataSourceImpl) bind ThemePreferenceDataSource::class
    }
