package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository

class CreateIncreaseEphemeralGapLimitProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val ephemeralAddressRepository: EphemeralAddressRepository
) {
    /**
     * ZC-101: returns the proposal created FOR this gap-limit flow so callers can thread the exact
     * instance through to display and submission instead of re-reading the shared
     * `transactionProposal` cell later (which a concurrent creation may overwrite).
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber", "UseCheckOrError")
    suspend operator fun invoke(): TransactionProposal {
        val address =
            ephemeralAddressRepository.get()?.address
                ?: throw IllegalStateException("Ephemeral address is null")
        val normalized =
            ZecSend(
                destination = WalletAddress.Transparent.new(address),
                amount = Zatoshi(100),
                memo = Memo(""),
                proposal = null
            )
        try {
            return when (accountDataSource.getSelectedAccount()) {
                is KeystoneAccount -> {
                    keystoneProposalRepository.createProposal(normalized)
                    keystoneProposalRepository.createPCZTFromProposal()
                    // Read back immediately after creation, in the same call — no user-dwell
                    // window in between, so this is the proposal createProposal just stored.
                    keystoneProposalRepository.getTransactionProposal()
                }
                is ZashiAccount ->
                    zashiProposalRepository.createProposal(normalized)
            }
        } catch (e: Exception) {
            keystoneProposalRepository.clear()
            zashiProposalRepository.clear()
            throw e
        }
    }
}
