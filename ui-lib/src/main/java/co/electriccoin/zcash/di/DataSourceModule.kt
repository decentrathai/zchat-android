package co.electriccoin.zcash.di

import android.content.Context
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
import co.electriccoin.zcash.ui.common.datasource.WalletSyncClock
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSourceImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AddressCacheImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.ai.AiImageStore
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiApiClient
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.ai.chatOwnerOfPhone
import co.electriccoin.zcash.ui.screen.ai.AiResetKeep
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedSetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.LiveChatStoresImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.PrefsChatOwnerRecord
import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.common.notification.InAppNotificationManager
import co.electriccoin.zcash.ui.common.notification.InAppNotificationManagerImpl
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManager
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManagerImpl
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.settings.datasource.AndroidAppLocaleApplier
import co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSource
import co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSourceImpl
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSource
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSourceImpl
import co.electriccoin.zcash.ui.screen.settings.datasource.appSettingsPreferences
import co.electriccoin.zcash.spackle.getPackageInfoCompat
import co.electriccoin.zcash.spackle.versionCodeCompat
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeNotice
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.SharedPreferencesPostUpgradeStore
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val dataSourceModule =
    module {
        singleOf(::AccountDataSourceImpl) bind AccountDataSource::class
        singleOf(::ZashiSpendingKeyDataSourceImpl) bind ZashiSpendingKeyDataSource::class
        // F2: every leg is submitted to the wallet's own server, read from the persisted wallet (PersistableWalletProvider).
        single<ProposalDataSource> {
            ProposalDataSourceImpl(synchronizerProvider = get(), persistableWalletProvider = get())
        }
        singleOf(::RestoreTimestampDataSourceImpl) bind RestoreTimestampDataSource::class
        singleOf(::MessageAvailabilityDataSourceImpl) bind MessageAvailabilityDataSource::class
        // SY-2: when the wallet last became fully synced in this process, shared by every ChatViewModel.
        single { WalletSyncClock() }
        singleOf(::WalletSnapshotDataSourceImpl) bind WalletSnapshotDataSource::class
        singleOf(::NearSwapDataSourceImpl) bind SwapDataSource::class
        singleOf(::ExchangeRateDataSourceImpl) bind ExchangeRateDataSource::class
        singleOf(::AddressCacheImpl) bind AddressCache::class
        singleOf(::ContactBookImpl) bind ContactBook::class
        singleOf(::AvatarStore)
        // R5-4: the words of an in-chat system note, in the phone's language (see ChatNoteCopy).
        single { ChatNoteCopy.from(get()) }
        singleOf(::ZchatPreferencesImpl) bind ZchatPreferences::class
        // F8 (ZC-112): the send-attempt ledger (in the encrypted held-reply store) and what a ViewModel records with.
        single<co.electriccoin.zcash.ui.common.sendattempt.SendAttemptLedger> { get<ZchatPreferencesImpl>().sendAttemptLedger }
        single<co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEnvironment> {
            co.electriccoin.zcash.ui.common.sendattempt
                .WalletSendAttemptEnvironment(get(), get())
        }
        singleOf(::IdentityManagerImpl) bind IdentityManager::class
        // Bridge 2: the version stamp, What's New once after an upgrade, and the catch-up flag.
        single {
            val context: Context = get()
            PostUpgradeNotice(
                store =
                    SharedPreferencesPostUpgradeStore(
                        context.getSharedPreferences(SharedPreferencesPostUpgradeStore.PREFS_NAME, Context.MODE_PRIVATE)
                    ),
                currentVersionCode = context.packageManager.getPackageInfoCompat(context.packageName, 0L).versionCodeCompat,
            )
        }
        // P2e: which wallet owns the chats on this phone; another wallet's chats are set aside, not shown.
        // ZC-718: the set-aside bundles are one object, handed to the ownership and to the Reset's keep step; the AI store
        // and the picture store are one object each (the AI store's holder is already one per process, ZC-733-A).
        single<SetAsideBundles> { EncryptedSetAsideBundles(get()) }
        factory { AiPreferences(get()) }
        single { AiImageStore(get()) }
        factory { AiResetKeep(get(), get(), get()) }
        // AI-KEY-S: the one place that decides about the AI access key; one object for the process (spec 4.1, 4.6).
        single {
            val koin = getKoin()
            val context: Context = get()
            val client = AiApiClient()
            AiAccountAccess(
                prefs = get(),
                seed = AiAccountAccess.seedReader(get()),
                candidates =
                    AiAccountAccess.candidateReader(
                        accounts = get(),
                        synchronizers = get(),
                        identities = { koin.get<ZchatPreferences>() },
                    ),
                ownerTag = { chatOwnerOfPhone(context) },
                scopeCounter = { koin.get<ZchatPreferences>().walletScopeGeneration() },
                walletSaved = { koin.get<PersistableWalletProvider>().getPersistableWallet() != null },
                clock = System::currentTimeMillis,
                send = client::request,
            )
        }
        single {
            ChatWalletOwnership(
                record = PrefsChatOwnerRecord(get()),
                bundles = get(),
                live =
                    LiveChatStoresImpl(
                        zchatPreferences = { get<ZchatPreferences>() },
                        contactBook = { get<ContactBookImpl>() },
                        avatarStore = { get<AvatarStore>() },
                        addressCache = { get<AddressCacheImpl>() },
                        identityManager = { get<IdentityManagerImpl>() },
                        furtherStores = { get<AiPreferences>().walletScopedStores() },
                    ),
                // D6: kept chat copies and restore marks that name no wallet are tagged when the owner changes.
                // ZC-718: with one side step: the flat AI pictures go to that wallet's folder (a failure is only logged).
                untaggedMarks = get<AiImageStore>().movingFlatPictures(get<KeptChatStoreCopies>()),
            )
        }
        singleOf(::ThemePreferenceDataSourceImpl) bind ThemePreferenceDataSource::class
        // ZC-1103: the app language, kept in the app-settings file beside the theme (a Reset keeps it, R17).
        single<AppLanguageDataSource> {
            val context: Context = get()
            AppLanguageDataSourceImpl(appSettingsPreferences(context), AndroidAppLocaleApplier(context))
        }
        singleOf(::InAppNotificationManagerImpl) bind InAppNotificationManager::class
        single {
            DestroyManager(
                context = get(),
                zchatPreferences = get(),
                walletCoordinator = get(),
                synchronizerProvider = get(),
                standardPreferenceProvider = get(),
                encryptedPreferenceProvider = get(),
                flexaRepository = get(),
                contactBook = get()
            )
        }
    }
