package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The exact amount "Send all" can put in front of [destination]: Ironwood available minus the fee the
 * SDK really charges to sweep it. See [SendMaxSearch] for the scan and why its result leaves no change.
 *
 * Every probe is a real proposal that is priced and dropped; nothing is stored or submitted, so the
 * amount the user then reviews is proposed again from scratch by the normal send path. Runs off the main
 * thread and is cancellable between probes.
 */
class ComputeSendMaxUseCase(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(
        destination: WalletAddress,
        recipient: SendMaxRecipient,
    ): SendMaxResult =
        withContext(Dispatchers.Default) {
            try {
                SendMaxSearch.search(
                    recipient = recipient,
                    readAccount = { accountDataSource.getSelectedAccount() },
                    probe = { account, amount ->
                        proposalDataSource.probeFee(
                            account = account,
                            send =
                                ZecSend(
                                    destination = destination,
                                    amount = amount,
                                    memo = Memo(""),
                                    proposal = null,
                                ),
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A probe the SDK could not build for a reason other than a shortfall. Send all says it
                // could not price the sweep; the user can still enter an amount.
                Twig.debug { "Send all could not be priced: ${e.javaClass.simpleName}" }
                SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
            }
        }
}
