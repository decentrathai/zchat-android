package co.electriccoin.zcash.di

import android.content.Context
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.walletHoldsAddress
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.ApplyTransactionFiltersUseCase
import co.electriccoin.zcash.ui.common.usecase.ApplyTransactionFulltextFiltersUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ComputeSendMaxUseCase
import co.electriccoin.zcash.ui.common.usecase.ConfirmResyncUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateFlexaTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateIncreaseEphemeralGapLimitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateKeystoneAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateKeystoneProposalPCZTEncoderUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateOrUpdateTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteABContactUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteTransactionNoteUseCase
import co.electriccoin.zcash.ui.common.usecase.DeriveKeystoneAccountUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ExportTaxUseCase
import co.electriccoin.zcash.ui.common.usecase.FilterSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.FilterSwapBlockchainsUseCase
import co.electriccoin.zcash.ui.common.usecase.FixEnhancementUseCase
import co.electriccoin.zcash.ui.common.usecase.FixEphemeralAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.FlipTransactionBookmarkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetABContactByIdUseCase
import co.electriccoin.zcash.ui.common.usecase.GetABContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetABSwapContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetActivitiesUseCase
import co.electriccoin.zcash.ui.common.usecase.GetConfigurationUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetExchangeRateUseCase
import co.electriccoin.zcash.ui.common.usecase.GetFilteredActivitiesUseCase
import co.electriccoin.zcash.ui.common.usecase.GetFlexaStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.GetHomeMessageUseCase
import co.electriccoin.zcash.ui.common.usecase.GetKeystoneStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.GetORSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.GetProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTotalSpendableBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionDetailByIdUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionFiltersUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionMetadataUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletRestoringStateUseCase
import co.electriccoin.zcash.ui.common.usecase.GetZashiAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.IsEphemeralAddressLockedUseCase
import co.electriccoin.zcash.ui.common.usecase.IsRestoreSuccessDialogVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.IsScreenTimeoutDisabledDuringRestoreUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.MarkTxMemoAsReadUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToAddressBookUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToEstimateBlockHeightUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToExportPrivateDataUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToNearPayUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToRequestShieldedUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToResetWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectSwapBlockchainUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapInfoUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToTaxExportUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveFastestServersUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveZashiAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.OnAddressScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.OnUserSavedWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.OnZip321ScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateUseCase
import co.electriccoin.zcash.ui.common.usecase.ParseKeystonePCZTUseCase
import co.electriccoin.zcash.ui.common.usecase.ParseKeystoneSignInRequestUseCase
import co.electriccoin.zcash.ui.common.usecase.ParseKeystoneUrToZashiAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.PersistEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillRestoreSeedUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.PreselectSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.RefreshFastestServersUseCase
import co.electriccoin.zcash.ui.common.usecase.RemindWalletBackupLaterUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.RescanBlockchainUseCase
import co.electriccoin.zcash.ui.common.usecase.RescanQrUseCase
import co.electriccoin.zcash.ui.common.usecase.ResetTransactionFiltersUseCase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.SaveABContactUseCase
import co.electriccoin.zcash.ui.common.usecase.SaveORSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.SelectSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.SelectWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.SendSupportEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.SendTransactionAgainUseCase
import co.electriccoin.zcash.ui.common.usecase.SetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareImageUseCase
import co.electriccoin.zcash.ui.common.usecase.SharePCZTUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsFromMessageUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitIncreaseEphemeralGapLimitUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitKSProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.UpdateABContactUseCase
import co.electriccoin.zcash.ui.common.usecase.UpdateSwapActivityMetadataUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateGenericABContactNameUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSwapABContactAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateZashiABContactAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionDetailAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionsAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCaseImpl
import co.electriccoin.zcash.ui.common.usecase.Zip321BuildUriUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase
import co.electriccoin.zcash.ui.screen.advancedsettings.AutomaticSetupPayments
import co.electriccoin.zcash.ui.screen.advancedsettings.RestoreOldChats
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.db.ExecuteDebugDBQueryUseCase
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatStoreStart
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.MasterKeyGuard
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatPaymentExit
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlanCheck
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.ProbeFirstMessagePlanUseCase
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheck
import co.electriccoin.zcash.ui.screen.walletstartup.WalletStartupRepair
import co.electriccoin.zcash.ui.screen.walletstartup.openStoresAtStart
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val useCaseModule =
    module {
        factoryOf(::ObserveFastestServersUseCase)
        factoryOf(::GetSelectedEndpointUseCase)
        factoryOf(::RefreshFastestServersUseCase)
        factoryOf(::PersistEndpointUseCase)
        factoryOf(::ValidateEndpointUseCase)
        factoryOf(::GetConfigurationUseCase)
        factory {
            val koin = getKoin()
            GetDefaultUnifiedAddressUseCase(
                synchronizerProvider = get(),
                getSelectedWalletAccount = get(),
                identityStore = { koin.get<ZchatPreferences>() },
            )
        }
        factoryOf(::RescanBlockchainUseCase)
        factory { WalletStartupRepair(get(), get(), get(), get(), get()) }
        // F10: the Advanced Settings row "Restore old chats".
        factory { RestoreOldChats(get(), get()) }
        // KI-23: the Advanced Settings row "Automatic setup payments" (shows the standing permission, turns it off).
        factory { AutomaticSetupPayments(get<ZchatPreferences>()) }
        single {
            val koin = getKoin()
            StartupStorageCheck(
                openSeedStore = {
                    // ZC-868: the wallet does not start (the SDK's opener makes the default key's master-key call) on a
                    // false "absent" of the Keystore while stores made with that key exist.
                    MasterKeyGuard.checkBeforeWalletStart(koin.get<Context>())
                    koin.get<PersistableWalletProvider>().getPersistableWallet()
                },
                openChatStores = {
                    // ZC-733-A: the chat storage, then the start's ask of the three other stores (never stops the start).
                    openStoresAtStart(
                        openChatStorage = {
                            // R3-3: this open is the one whose failure the user sees (the safety screen), so it may count.
                            ChatStoreStart.userVisible = true
                            koin.get<ZchatPreferences>()
                        },
                        askContactBook = { koin.get<ContactBookImpl>().askAtStart() },
                        askPictureIndex = { koin.get<AvatarStore>().askAtStart() },
                        askAiStore = { AiPreferences.askAtStartIfPresent(koin.get<Context>()) },
                    )
                },
                // F10: only the copies that may be offered now (a paused or parked copy is held by its own marks).
                keptChatCopies = { koin.get<KeptChatStoreCopies>().offered() },
                // R4-5: a copy the user already put back that still cannot be read is said once, not offered again.
                chatCopyRestoreFailed = { koin.get<KeptChatStoreCopies>().restoreFailed() },
                // B5: a put-back copy the phone's security system did not answer for waits; it is not called lost.
                chatCopyRestorePaused = { koin.get<KeptChatStoreCopies>().restorePaused() },
                // ZC-692-S: what this process's start made of a Destroy All record, read before any store is opened.
                destroyStartOutcome = { DestroyWipe.startOutcome },
            )
        }
        factoryOf(::ValidateZashiABContactAddressUseCase)
        factoryOf(::ValidateGenericABContactNameUseCase)
        factoryOf(::SaveABContactUseCase)
        factoryOf(::UpdateABContactUseCase)
        factoryOf(::DeleteABContactUseCase)
        factoryOf(::GetABContactByIdUseCase)
        factoryOf(::ObserveContactByAddressUseCase)
        singleOf(::ObserveABContactPickedUseCase)
        factoryOf(::CopyToClipboardUseCase)
        factoryOf(::ShareImageUseCase)
        factoryOf(::Zip321BuildUriUseCase)
        factoryOf(::Zip321ParseUriValidationUseCase)
        factoryOf(::GetPersistableWalletUseCase)
        factoryOf(::GetSupportUseCase)
        factoryOf(::SendEmailUseCase)
        factoryOf(::SendSupportEmailUseCase)
        factoryOf(::GetWalletAccountsUseCase)
        factoryOf(::SelectWalletAccountUseCase)
        factoryOf(::ObserveSelectedWalletAccountUseCase)
        factoryOf(::ObserveZashiAccountUseCase)
        factoryOf(::GetZashiAccountUseCase)
        factoryOf(::CreateKeystoneAccountUseCase)
        factoryOf(::DeriveKeystoneAccountUnifiedAddressUseCase)
        factoryOf(::ParseKeystoneUrToZashiAccountsUseCase)
        factoryOf(::GetExchangeRateUseCase)
        factoryOf(::GetSelectedWalletAccountUseCase)
        singleOf(::ObserveClearSendUseCase)
        singleOf(::PrefillSendUseCase)
        singleOf(::PrefillZchatUseCase)
        singleOf(::PrefillRestoreSeedUseCase)
        factoryOf(::GetTransactionsUseCase)
        factoryOf(::GetFilteredActivitiesUseCase)
        factoryOf(::CreateProposalUseCase)
        factoryOf(::ComputeSendMaxUseCase)
        factoryOf(::OnZip321ScannedUseCase)
        factoryOf(::OnAddressScannedUseCase)
        factoryOf(::ParseKeystonePCZTUseCase)
        singleOf(::SubmitKSProposalUseCase)
        factoryOf(::ParseKeystoneSignInRequestUseCase)
        // ZC-1273: one mark, written by ChatViewModel's payment and read by the exits of the sending screen.
        single { ChatPaymentExit.shared }
        factoryOf(::CancelProposalFlowUseCase)
        factoryOf(::ObserveProposalUseCase)
        factoryOf(::SharePCZTUseCase)
        factoryOf(::CreateKeystoneProposalPCZTEncoderUseCase)
        // ZC-960: the swap repository is passed by name (the constructor's default exists only for tests).
        factory {
            ViewTransactionsAfterSuccessfulProposalUseCase(
                keystoneProposalRepository = get(),
                zashiProposalRepository = get(),
                navigationRouter = get(),
                swapRepository = get<SwapRepository>(),
            ).also { it.chatPaymentExit = get() }
        }
        factory {
            ViewTransactionDetailAfterSuccessfulProposalUseCase(
                keystoneProposalRepository = get(),
                zashiProposalRepository = get(),
                navigationRouter = get(),
            ).also { it.chatPaymentExit = get() }
        }
        factoryOf(::ObserveTransactionSubmitStateUseCase)
        factoryOf(::GetProposalUseCase)
        singleOf(::SubmitProposalUseCase)
        singleOf(::ProcessSwapTransactionUseCase)
        factoryOf(::GetWalletRestoringStateUseCase)
        factoryOf(::ApplyTransactionFiltersUseCase)
        factoryOf(::ResetTransactionFiltersUseCase)
        factoryOf(::ApplyTransactionFulltextFiltersUseCase)
        factoryOf(::GetTransactionFiltersUseCase)
        factoryOf(::GetTransactionDetailByIdUseCase)
        factoryOf(::SendTransactionAgainUseCase)
        factoryOf(::GetABContactsUseCase)
        factoryOf(::GetABSwapContactsUseCase)
        factoryOf(::NavigateToAddressBookUseCase)
        factoryOf(::NavigateToSelectRecipientUseCase)
        factoryOf(::GetTransactionMetadataUseCase)
        factoryOf(::FlipTransactionBookmarkUseCase)
        factoryOf(::DeleteTransactionNoteUseCase)
        factoryOf(::CreateOrUpdateTransactionNoteUseCase)
        factoryOf(::MarkTxMemoAsReadUseCase)
        factoryOf(::ExportTaxUseCase)
        factoryOf(::NavigateToTaxExportUseCase)
        factoryOf(::CreateFlexaTransactionUseCase)
        factoryOf(::IsRestoreSuccessDialogVisibleUseCase)
        factoryOf(::ValidateSeedUseCase)
        factoryOf(::RestoreWalletUseCase)
        factoryOf(::NavigateToWalletBackupUseCase)
        factoryOf(::GetKeystoneStatusUseCase)
        factoryOf(::GetFlexaStatusUseCase)
        factoryOf(::GetHomeMessageUseCase)
        factoryOf(::OnUserSavedWalletBackupUseCase)
        factoryOf(::RemindWalletBackupLaterUseCase)
        singleOf(::ShieldFundsUseCase)
        singleOf(::NavigateToErrorUseCase)
        factoryOf(::RescanQrUseCase)
        factoryOf(::ShieldFundsFromMessageUseCase)
        factoryOf(::NavigateToReceiveUseCase)
        factoryOf(::NavigateToRequestShieldedUseCase)
        factoryOf(::IsTorEnabledUseCase)
        factoryOf(::OptInExchangeRateUseCase)
        factoryOf(::OptInExchangeRateAndTorUseCase)
        factoryOf(::NavigateToSwapUseCase)
        factoryOf(::CancelSwapUseCase)
        factoryOf(::GetSelectedSwapAssetUseCase)
        factoryOf(::SelectSwapAssetUseCase)
        factoryOf(::GetSwapAssetsUseCase)
        factoryOf(::FilterSwapAssetsUseCase)
        factoryOf(::FilterSwapBlockchainsUseCase)
        factoryOf(::SetSlippageUseCase)
        factoryOf(::GetSlippageUseCase)
        factoryOf(::NavigateToSwapInfoUseCase)
        factoryOf(::GetTotalSpendableBalanceUseCase)
        factoryOf(::IsABContactHintVisibleUseCase)
        factoryOf(::RequestSwapQuoteUseCase)
        factoryOf(::CancelSwapQuoteUseCase)
        factoryOf(::NavigateToSwapQuoteIfAvailableUseCase)
        singleOf(::NavigateToScanGenericAddressUseCase)
        singleOf(::NavigateToSelectABSwapRecipientUseCase)
        singleOf(::NavigateToSelectSwapBlockchainUseCase)
        singleOf(::NavigateToEstimateBlockHeightUseCase)
        factoryOf(::ConfirmResyncUseCase)
        factoryOf(::ValidateSwapABContactAddressUseCase)
        factoryOf(::NavigateToNearPayUseCase)
        factoryOf(::SaveORSwapUseCase)
        factoryOf(::GetORSwapQuoteUseCase)
        factoryOf(::ShareQRUseCase)
        factoryOf(::GetActivitiesUseCase)
        factoryOf(::NavigateToExportPrivateDataUseCase)
        factoryOf(::NavigateToResetWalletUseCase)
        factoryOf(::IsScreenTimeoutDisabledDuringRestoreUseCase)
        singleOf(::UpdateSwapActivityMetadataUseCase)
        factoryOf(::WalletBackupMessageUseCaseImpl) bind WalletBackupMessageUseCase::class
        factoryOf(::ValidateAddressUseCase)
        singleOf(::FixEphemeralAddressUseCase)
        factoryOf(::FixEnhancementUseCase)
        factoryOf(::IsEphemeralAddressLockedUseCase)
        singleOf(::SubmitIncreaseEphemeralGapLimitUseCase)
        factoryOf(::CreateIncreaseEphemeralGapLimitProposalUseCase)
        factoryOf(::ResetZashiUseCase)
        factoryOf(::PreselectSwapAssetUseCase)
        factoryOf(::GetSwapStatusUseCase)
        factoryOf(::ExecuteDebugDBQueryUseCase)
        // T3-extra: the chat send chokepoint refuses a send to one of our own addresses (rule E). The store is read at
        // send time, as the other chat-store users above do, never while the graph is built. ZC-602: an address an
        // account of this wallet holds now (unified, Sapling or transparent) is our own too, whether or not the registry
        // ever saw it.
        factory {
            val koin = getKoin()
            val held = { koin.get<AccountDataSource>().allAccounts.value }
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = get(),
                zashiProposalRepository = get(),
                accountDataSource = get(),
                navigationRouter = get(),
                biometricRepository = get(),
                isOwnAddress = { koin.get<ZchatPreferences>().isSelfAddress(it) || walletHoldsAddress(held(), it) },
            )
        }
        // M2: the first-message plan check ChatViewModel asks before it pays a first message's KEX (no default in the
        // ViewModel: this binding is the only one).
        factoryOf(::ProbeFirstMessagePlanUseCase) bind FirstMessagePlanCheck::class
    }
