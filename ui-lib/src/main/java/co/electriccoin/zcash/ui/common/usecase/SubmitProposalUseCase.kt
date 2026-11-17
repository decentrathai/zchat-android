package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SubmitProposalUseCase(
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val biometricRepository: BiometricRepository,
    private val swapRepository: SwapRepository,
    private val metadataRepository: MetadataRepository,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    private val prefillSend: PrefillSendUseCase,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // HIGH-008 (money): global in-flight latch. The confirm screens (ReviewTransactionVM,
    // SwapQuoteVM) each latch their own click job, but this singleton is the single choke point
    // in front of zashiProposalRepository.submit(proposal): two overlapping invocations would
    // both submit the SAME proposal twice (double spend). The CAS makes every concurrent second
    // caller a no-op; the finally releases it on every exit path (success, biometric
    // cancel/failure, cancellation). ZC-101 threads the DISPLAYED proposal in from the confirm
    // screen (see [invoke]), so a concurrent creation can no longer swap WHICH proposal settles —
    // this latch still guards against the same proposal settling TWICE.
    private val inFlight = AtomicBoolean(false)

    /**
     * Submit the given [proposal] and navigate to the Transaction Progress screen, or navigate to
     * the Keystone PCZT flow.
     *
     * ZC-101 (money): [proposal] MUST be the exact instance the confirm screen DISPLAYED, captured
     * by the calling VM when it rendered the confirmation UI — i.e. BEFORE this use case suspends
     * on the biometric prompt. This use case deliberately never reads the shared
     * `transactionProposal` cell: an inbound auto-send (KEX-ack, read receipt, group control memo)
     * can overwrite that cell while the user is inside the biometric dialog, and a post-biometric
     * cell read would settle THAT proposal while the UI reports success for the user's payment.
     * Threading the displayed instance makes "what the biometric authorizes" identical to "what
     * the confirm screen showed".
     *
     * QA2 Q2-16 (ZC-1619): returns [Outcome.CANCELLED] when the user cancelled the system Send Funds prompt, so the
     * confirm screen can say that nothing was sent or paid.
     */
    suspend operator fun invoke(proposal: TransactionProposal): Outcome {
        if (!inFlight.compareAndSet(false, true)) return Outcome.NOT_STARTED
        try {
            biometricRepository.requestBiometrics(
                request =
                    BiometricRequest(
                        message =
                            stringRes(
                                R.string.authentication_system_ui_subtitle,
                                stringRes(R.string.authentication_use_case_send_funds)
                            )
                    )
            )
            if (proposal is SwapTransactionProposal) {
                val selectedSwapAsset = proposal.quote.destinationAsset
                metadataRepository.addSwapAssetToHistory(
                    tokenTicker = selectedSwapAsset.tokenTicker,
                    chainTicker = selectedSwapAsset.chainTicker
                )
                // D12 (money): persist the swap PENDING record durably BEFORE broadcast +
                // navigation. The broadcast path (submitZashiProposal) runs in a detached scope
                // and the later ProcessSwapTransactionUseCase.saveSwapToMetadata is fire-and-
                // forget, so process death in that window would otherwise drop swap tracking
                // (the deposit would render as a plain send). markTxAsSwap is an idempotent
                // replaceOrAdd-by-depositAddress upsert, so the later async write just
                // refreshes this record.
                metadataRepository.markTxAsSwapAwait(
                    depositAddress = proposal.destination.address,
                    provider = proposal.quote.provider,
                    origin = proposal.quote.originAsset,
                    destination = proposal.quote.destinationAsset,
                    totalFees = proposal.totalFees,
                    totalFeesUsd = proposal.totalFeesUsd,
                    amountOutFormatted = proposal.quote.amountOutFormatted,
                    mode = proposal.quote.mode,
                    status = SwapStatus.PENDING
                )
            }
            when (accountDataSource.getSelectedAccount()) {
                is KeystoneAccount -> navigationRouter.replace(SignKeystoneTransactionArgs)
                is ZashiAccount -> {
                    swapRepository.clear()
                    // A scanned request's prefill waits for the next Send screen; this payment settles it.
                    prefillSend.clear()
                    submitZashiProposal(proposal)
                    navigationRouter.replace(TransactionProgressArgs)
                }
            }
            return Outcome.STARTED
        } catch (_: BiometricsFailureException) {
            // do nothing
            return Outcome.NOT_STARTED
        } catch (_: BiometricsCancelledException) {
            // QA2 Q2-16: the prompt was cancelled before anything was submitted; nothing was paid.
            return Outcome.CANCELLED
        } finally {
            inFlight.set(false)
        }
    }

    /** What [invoke] did with the proposal. */
    enum class Outcome {
        /** Submitted (or handed to Keystone for signing). */
        STARTED,

        /** The user cancelled the Send Funds prompt; nothing was submitted. */
        CANCELLED,

        /** Nothing was submitted: another submit was in flight, or the prompt failed. */
        NOT_STARTED,
    }

    private fun submitZashiProposal(proposal: TransactionProposal) {
        scope.launch {
            try {
                // ZC-101: settle exactly the proposal the confirm screen displayed — snapshotted by
                // the VM before the biometric window ever opened and threaded through invoke() —
                // never whatever the shared cell holds now.
                val result = zashiProposalRepository.submit(proposal)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (_: Exception) {
                // do nothing
            }
        }
    }
}
