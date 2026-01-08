package co.electriccoin.zcash.ui.screen.chat.usecase

import android.util.Base64
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.toZecStringFull
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Use case for creating transaction proposals with chunked messages.
 *
 * For messages that exceed the 512-byte memo limit, this creates a multi-output
 * transaction where each output contains one chunk of the message.
 *
 * Uses ZIP321 payment URIs to create proposals with multiple outputs.
 */
class CreateChunkedMessageProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        // Default amount per output (0.00001 ZEC = 1000 zatoshi)
        val DEFAULT_AMOUNT_PER_OUTPUT = Zatoshi(1000L)

        // Platform fee address - receives the same amount as message outputs
        // This supports the ZCHAT platform development and infrastructure
        private const val PLATFORM_FEE_ADDRESS =
            "u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4t2r8wf3up8" +
            "cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g86zn8h4uzvdp9q3px2f6c" +
            "lxd46fvcllsphyndl7tvkjzwal68eccq7p4w53"
    }

    /**
     * Create a proposal for sending a message that may be chunked across multiple outputs.
     *
     * @param destinationAddress The recipient's address
     * @param senderAddress The sender's address (for ZMSG protocol)
     * @param message The message to send
     * @param isFirstMessage True if this is the first message to this recipient
     * @param amountPerOutput The amount of ZEC to send per output (default: 1000 zatoshi)
     * @param directSubmit If true, automatically submit without review screen (for ZCHAT)
     * @param skipNavigation If true, don't navigate after submit (for smooth chat flow)
     * @param rawMemo If true, use message as-is without ZMSG formatting (for reactions, receipts, etc.)
     */
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(
        destinationAddress: String,
        senderAddress: String,
        message: String,
        isFirstMessage: Boolean,
        amountPerOutput: Zatoshi = DEFAULT_AMOUNT_PER_OUTPUT,
        directSubmit: Boolean = false,
        skipNavigation: Boolean = false,
        rawMemo: Boolean = false
    ) {
        try {
            // Generate the memo chunks
            val memos = if (rawMemo) {
                // Use message as-is (for reactions, receipts, reply memos that are pre-formatted)
                listOf(message)
            } else if (isFirstMessage) {
                ZMSGProtocol.createChunkedInitMessages(senderAddress, message)
            } else {
                ZMSGProtocol.createChunkedReplyMessages(senderAddress, message)
            }

            // Always use ZIP321 since we have message output(s) + platform fee output
            createMultiOutputProposal(destinationAddress, memos, amountPerOutput)

            if (directSubmit) {
                // Auto-submit for ZCHAT (smooth UX after user has acknowledged cost)
                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        // Keystone requires manual signing, so we still need to navigate
                        if (!skipNavigation) {
                            navigationRouter.forward(TransactionProgressArgs)
                        }
                    }
                    is ZashiAccount -> {
                        // Submit directly
                        submitZashiProposal()
                        // Only navigate if not skipping (for smooth chat flow, stay on chat screen)
                        if (!skipNavigation) {
                            navigationRouter.forward(TransactionProgressArgs)
                        }
                    }
                }
            } else {
                // Legacy flow: go to review screen for confirmation
                if (!skipNavigation) {
                    navigationRouter.forward(TransactionProgressArgs)
                }
            }
        } catch (e: Exception) {
            keystoneProposalRepository.clear()
            zashiProposalRepository.clear()
            // Check if this is an insufficient funds error (various exception types)
            val isInsufficientFunds = e is InsufficientFundsException ||
                e.message?.contains("Insufficient balance", ignoreCase = true) == true ||
                e.message?.contains("InsufficientFunds", ignoreCase = true) == true ||
                e.cause?.message?.contains("Insufficient balance", ignoreCase = true) == true
            if (isInsufficientFunds) {
                navigationRouter.forward(InsufficientFundsArgs)
            } else {
                throw e
            }
        }
    }

    /**
     * Submit the Zashi proposal directly without user confirmation.
     * Used for ZCHAT direct send after user has acknowledged message costs.
     */
    private fun submitZashiProposal() {
        scope.launch {
            try {
                zashiProposalRepository.submit()
            } catch (_: Exception) {
                // Error will be shown on TransactionProgress screen
            }
        }
    }

    private suspend fun createMultiOutputProposal(
        destinationAddress: String,
        memos: List<String>,
        amountPerOutput: Zatoshi
    ) {
        // Build ZIP321 URI with multiple payments
        val zip321Uri = buildZip321Uri(destinationAddress, memos, amountPerOutput)

        when (accountDataSource.getSelectedAccount()) {
            is KeystoneAccount -> {
                keystoneProposalRepository.createZip321Proposal(zip321Uri)
                keystoneProposalRepository.createPCZTFromProposal()
            }
            is ZashiAccount -> {
                zashiProposalRepository.createZip321Proposal(zip321Uri)
            }
        }
    }

    /**
     * Build a ZIP321 URI for multiple payments including message outputs and platform fee.
     *
     * ZIP321 format for multiple payments uses indexed parameters:
     * zcash:<addr>?amount=<amt>&memo=<memo>&address.1=<addr>&amount.1=<amt>&memo.1=<memo>...
     *
     * Includes:
     * - Message output(s) to the recipient with ZMSG memo chunks
     * - Platform fee output to PLATFORM_FEE_ADDRESS (same amount, no memo)
     *
     * Note: ZIP321 uses base64url encoding for memos.
     */
    private fun buildZip321Uri(
        destinationAddress: String,
        memos: List<String>,
        amountPerOutput: Zatoshi
    ): String {
        val amountZec = amountPerOutput.toZecStringFull()
        val params = StringBuilder()

        // First payment (index 0) - no index suffix
        val firstMemo = memos.first()
        val firstEncodedMemo = Base64.encodeToString(
            firstMemo.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        params.append("zcash:$destinationAddress?amount=$amountZec&memo=$firstEncodedMemo")

        // Subsequent message payments (index 1, 2, ...)
        var paymentIndex = 1
        for (i in 1 until memos.size) {
            val encodedMemo = Base64.encodeToString(
                memos[i].toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            params.append("&address.$paymentIndex=$destinationAddress")
            params.append("&amount.$paymentIndex=$amountZec")
            params.append("&memo.$paymentIndex=$encodedMemo")
            paymentIndex++
        }

        // Platform fee output (last payment, no memo)
        params.append("&address.$paymentIndex=$PLATFORM_FEE_ADDRESS")
        params.append("&amount.$paymentIndex=$amountZec")

        return params.toString()
    }

    /**
     * Get the total cost of sending a chunked message (includes platform fee).
     */
    fun getTotalCost(
        message: String,
        isFirstMessage: Boolean,
        amountPerOutput: Zatoshi = DEFAULT_AMOUNT_PER_OUTPUT
    ): Zatoshi {
        val chunkCount = ZMSGProtocol.calculateChunkCount(message, isFirstMessage)
        // Total = (message chunks * amount) + (1 platform fee * amount)
        val totalOutputs = chunkCount + 1
        return Zatoshi(amountPerOutput.value * totalOutputs)
    }

    /**
     * Check if a message needs to be chunked.
     */
    fun needsChunking(message: String, isFirstMessage: Boolean, senderAddress: String): Boolean {
        return ZMSGProtocol.needsChunking(message, isFirstMessage, senderAddress)
    }

    /**
     * Get the number of chunks needed for a message.
     */
    fun getChunkCount(message: String, isFirstMessage: Boolean): Int {
        return ZMSGProtocol.calculateChunkCount(message, isFirstMessage)
    }
}
