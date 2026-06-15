package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ADDRESS_BOOK
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.HOMEPAGE
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.RESTORE_SEED
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.SEND
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ZCHAT
import co.electriccoin.zcash.ui.screen.send.Send

class OnZip321ScannedUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase,
    private val navigateToErrorUseCase: NavigateToErrorUseCase
) {
    suspend operator fun invoke(zip321: Zip321ParseUriValidation.Valid, scanArgs: ScanArgs) {
        when (scanArgs.flow) {
            ADDRESS_BOOK -> addressBookFlow(zip321)
            SEND ->
                if (scanArgs.isScanZip321Enabled) {
                    sendFlow(zip321)
                } else {
                    sendFlowWithDisabledZip321(zip321)
                }

            HOMEPAGE -> homepageFlow(zip321)
            ZCHAT -> {
                // ZIP321 is disabled for ZCHAT flow, so this shouldn't be reached
                // Just navigate back if it somehow is
                navigationRouter.back()
            }

            RESTORE_SEED -> {
                // RESTORE_SEED is handled directly in ScanZashiAddressVM before reaching here
                // Just navigate back if it somehow is
                navigationRouter.back()
            }
        }
    }

    private fun addressBookFlow(zip321: Zip321ParseUriValidation.Valid) {
        val payment =
            zip321.payment.payments.firstOrNull()
                ?: run {
                    // Malformed ZIP321 with no payments: nothing to prefill, just navigate back
                    navigationRouter.back()
                    return
                }
        navigationRouter.replace(
            AddZashiABContactArgs(
                payment.recipientAddress.value
            )
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun homepageFlow(zip321: Zip321ParseUriValidation.Valid) {
        try {
            val proposal =
                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        val result = keystoneProposalRepository.createZip321Proposal(zip321.zip321Uri)
                        keystoneProposalRepository.createPCZTFromProposal()
                        result
                    }

                    is ZashiAccount -> {
                        zashiProposalRepository.createZip321Proposal(zip321.zip321Uri)
                    }
                }

            prefillSend.request(
                PrefillSendData.All(
                    amount = proposal.amount,
                    address = proposal.destination.address,
                    fee = proposal.proposal.totalFeeRequired(),
                    memos =
                        proposal.memo.value
                            .takeIf { it.isNotEmpty() }
                            ?.let { listOf(it) }
                )
            )
            navigationRouter.replace(Send(), ReviewTransactionArgs)
        } catch (_: InsufficientFundsException) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            navigationRouter.replace(InsufficientFundsArgs)
        } catch (_: TransactionProposalNotCreatedException) {
            prefillSend.requestFromZip321(zip321.payment)
            navigationRouter.replace(Send())
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
        } catch (e: Exception) {
            navigateToErrorUseCase(ErrorArgs.General(e))
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendFlow(zip321: Zip321ParseUriValidation.Valid) {
        try {
            val proposal =
                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        val result = keystoneProposalRepository.createZip321Proposal(zip321.zip321Uri)
                        keystoneProposalRepository.createPCZTFromProposal()
                        result
                    }

                    is ZashiAccount -> {
                        zashiProposalRepository.createZip321Proposal(zip321.zip321Uri)
                    }
                }

            prefillSend.request(
                PrefillSendData.All(
                    amount = proposal.amount,
                    address = proposal.destination.address,
                    fee = proposal.proposal.totalFeeRequired(),
                    memos =
                        proposal.memo.value
                            .takeIf { it.isNotEmpty() }
                            ?.let { listOf(it) }
                )
            )
            navigationRouter.forward(ReviewTransactionArgs)
        } catch (_: InsufficientFundsException) {
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
            navigationRouter.forward(InsufficientFundsArgs)
        } catch (_: TransactionProposalNotCreatedException) {
            prefillSend.requestFromZip321(zip321.payment)
            navigationRouter.back()
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
        } catch (e: Exception) {
            navigateToErrorUseCase(ErrorArgs.General(e))
            zashiProposalRepository.clear()
            keystoneProposalRepository.clear()
        }
    }

    private fun sendFlowWithDisabledZip321(zip321: Zip321ParseUriValidation.Valid) {
        val payment = zip321.payment.payments.firstOrNull()
        if (payment != null) {
            prefillSend.request(
                PrefillSendData.FromAddressScan(
                    address = payment.recipientAddress.value
                )
            )
        }
        // Malformed ZIP321 with no payments: skip prefill and just navigate back
        navigationRouter.back()
    }
}
