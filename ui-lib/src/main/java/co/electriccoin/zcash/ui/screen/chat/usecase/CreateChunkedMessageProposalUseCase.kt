package co.electriccoin.zcash.ui.screen.chat.usecase

import android.util.Base64
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.toCanonicalZecString
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs

/**
 * Thrown by the central pre-submit memo-size guard (#195) when a memo chunk would exceed Zcash's
 * 512-byte memo field. This is the single chokepoint EVERY on-chain ZCHAT memo passes through, so it
 * catches over-long memos from ANY producer — including the rawMemo control messages (KEX, ZBOOT,
 * GROUP_INVITE/KEY/KICK) that bypass the chunker and previously overflowed silently. Carries the
 * offending byte count + chunk index so the root cause is obvious instead of a cryptic SDK failure.
 */
class MemoTooLongException(message: String) : IllegalArgumentException(message)

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
    private val navigationRouter: NavigationRouter,
    private val biometricRepository: BiometricRepository
) {

    companion object {
        // Default amount per output (0.00001 ZEC = 1000 zatoshi)
        val DEFAULT_AMOUNT_PER_OUTPUT = Zatoshi(1000L)

        // Platform fee address from shared constants
        private val PLATFORM_FEE_ADDRESS = ZMSGConstants.PLATFORM_FEE_ADDRESS

        // Fee buffer for classifying "pending change vs truly insufficient" in chat UX.
        // This is intentionally conservative and only used for user-facing error classification.
        private const val ESTIMATED_NETWORK_FEE_BUFFER_ZATOSHI = 2000L
        // Shown when funds EXIST but aren't spendable yet — covers BOTH our own change maturing AND
        // ZEC we just RECEIVED that's still confirming. (Found in 2-device testing: replying right
        // after receiving funds surfaced the misleading "add ZEC" message.) The stable "confirm
        // on-chain" substring is what the chat UI + group-invite retry classify transient blocks on.
        private const val PENDING_BALANCE_WAIT_MESSAGE =
            "Please wait for your ZEC to confirm on-chain, then try again."
        private const val INSUFFICIENT_BALANCE_MESSAGE =
            "Insufficient balance for an on-chain (Vault) message. Add ZEC, or switch this chat to " +
                "Tunnel/Open in the ⋮ menu to message free over NOSTR."
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
     * @param conversationId The conversation ID for v4 format (new, reliable threading)
     * @param lastReceivedTxId The txid of the last message RECEIVED from this peer (for v3 REF threading, deprecated)
     */
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(
        destinationAddress: String,
        senderAddress: String,
        message: String,
        isFirstMessage: Boolean,
        amountPerOutput: Zatoshi = DEFAULT_AMOUNT_PER_OUTPUT,
        platformFeeAmount: Zatoshi = amountPerOutput,
        directSubmit: Boolean = false,
        skipNavigation: Boolean = false,
        rawMemo: Boolean = false,
        conversationId: String? = null,
        lastReceivedTxId: String? = null
    ) {
        var estimatedRequiredSpendable: Zatoshi? = null
        try {
            // Generate the memo chunks
            val memos = if (rawMemo) {
                // Use message as-is (for reactions, receipts, reply memos that are pre-formatted)
                listOf(message)
            } else if (conversationId != null) {
                // ZMSG v4 format - use conversation ID for reliable threading
                // Both INIT and REPLY now include sender info for fallback identification
                if (isFirstMessage) {
                    ZMSGProtocol.createChunkedV4InitMessages(conversationId, senderAddress, message)
                } else {
                    ZMSGProtocol.createChunkedV4ReplyMessages(conversationId, senderAddress, message)
                }
            } else if (isFirstMessage) {
                // Fallback to v3 INIT format
                ZMSGProtocol.createChunkedInitMessages(senderAddress, message)
            } else if (lastReceivedTxId != null) {
                // Fallback to v3 REF format for backward compatibility
                ZMSGProtocol.createChunkedRefMessages(senderAddress, message, lastReceivedTxId)
            } else {
                // Fallback to v3 hash-based replies (deprecated)
                ZMSGProtocol.createChunkedReplyMessages(senderAddress, message)
            }
            // CENTRAL PRE-SUBMIT MEMO-SIZE GUARD (#195). Every on-chain memo MUST fit Zcash's 512-byte
            // field. The chunked paths above already size each chunk to fit, but the rawMemo path
            // (KEX/ZBOOT/GROUP_*/reactions/receipts) passes its memo through UNCHUNKED — an over-long
            // control message there used to overflow silently and surface as a cryptic MemoTooLong deep
            // inside SDK proposal-building (the root cause of the group + first-contact KEX breakage).
            // Validate here, the single chokepoint every memo passes through, so any overflow fails fast
            // and names the offending producer (rawMemo flag + chunk index + byte count) instead of
            // failing opaquely or silently on-chain. UTF-8 bytes, not chars — emoji/multibyte count too.
            memos.forEachIndexed { index, memo ->
                val memoBytes = memo.toByteArray(Charsets.UTF_8).size
                if (memoBytes > ZMSGConstants.MAX_MEMO_SIZE) {
                    throw MemoTooLongException(
                        "Memo chunk ${index + 1}/${memos.size} is $memoBytes bytes, over the " +
                            "${ZMSGConstants.MAX_MEMO_SIZE}-byte Zcash memo limit (rawMemo=$rawMemo). " +
                            "This message type must be made compact before sending."
                    )
                }
            }
            estimatedRequiredSpendable = estimateRequiredSpendableBalance(memos.size, amountPerOutput)

            // Always use ZIP321 since we have message output(s) + platform fee output
            createMultiOutputProposal(destinationAddress, memos, amountPerOutput, platformFeeAmount)

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
                        // #248 — VALUE-TRANSFER SPEND AUTH. The directSubmit path auto-submits the on-chain
                        // spend WITHOUT the review screen, so it MUST enforce the SAME biometric/PIN
                        // authentication the main Send screen does (SubmitProposalUseCase) — otherwise an
                        // already-unlocked phone could move funds from ANY chat surface (in-chat payment,
                        // fulfill-request, new-chat "Send All"/custom amount, above-dust message) with no
                        // re-auth. Centralized here because this use case is the single chokepoint every
                        // on-chain ZCHAT send passes through, so no value path is missed (now or later).
                        // Dust message/reaction/receipt sends (amountPerOutput == the default 1000 zat)
                        // stay frictionless — only a VALUE TRANSFER (amount above dust) is gated. On cancel
                        // or failure we clear the created-but-unsubmitted proposal and abort (no spend).
                        if (amountPerOutput.value > DEFAULT_AMOUNT_PER_OUTPUT.value) {
                            try {
                                biometricRepository.requestBiometrics(
                                    BiometricRequest(
                                        message =
                                            stringRes(
                                                R.string.authentication_system_ui_subtitle,
                                                stringRes(R.string.authentication_use_case_send_funds)
                                            )
                                    )
                                )
                            } catch (_: BiometricsCancelledException) {
                                zashiProposalRepository.clear()
                                return
                            } catch (_: BiometricsFailureException) {
                                zashiProposalRepository.clear()
                                return
                            }
                        }
                        // Submit directly - pass skipNavigation so errors propagate to caller
                        submitZashiProposal(skipNavigation)
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

            val isInsufficientFunds = isInsufficientFundsError(e)

            // When skipNavigation=true, the caller handles ALL errors (including insufficient funds)
            // Navigating would contradict the caller's explicit request to stay on current screen
            if (skipNavigation) {
                if (isInsufficientFunds) {
                    val isLikelyPendingChange =
                        estimatedRequiredSpendable?.let { required ->
                            hasPendingShieldedBalanceBlockingSpend(required)
                        } ?: false
                    if (isLikelyPendingChange) {
                        throw InsufficientFundsException(PENDING_BALANCE_WAIT_MESSAGE)
                    }
                    throw InsufficientFundsException(INSUFFICIENT_BALANCE_MESSAGE)
                }
                throw e
            }

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
     *
     * @param skipNavigation If true, rethrow errors so the caller can handle them
     *   (since the user won't navigate to TransactionProgress screen to see errors)
     */
    private suspend fun submitZashiProposal(skipNavigation: Boolean = false) {
        try {
            zashiProposalRepository.submit()
        } catch (e: Exception) {
            if (skipNavigation) {
                // When skipNavigation=true (ZCHAT flow), errors must propagate
                // because the user stays on the chat screen and never sees
                // the TransactionProgress error screen
                throw e
            }
            // Otherwise error will be shown on TransactionProgress screen
        }
    }

    private suspend fun createMultiOutputProposal(
        destinationAddress: String,
        memos: List<String>,
        amountPerOutput: Zatoshi,
        platformFeeAmount: Zatoshi = amountPerOutput
    ) {
        // Build ZIP321 URI with multiple payments
        val zip321Uri = buildZip321Uri(destinationAddress, memos, amountPerOutput, platformFeeAmount)

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
        amountPerOutput: Zatoshi,
        platformFeeAmount: Zatoshi = amountPerOutput
    ): String {
        // ZIP-321 `amount` must use a canonical '.' decimal separator regardless of device
        // locale, otherwise non-English locales emit invalid URIs (e.g. amount=0,00001).
        val amountZec = amountPerOutput.toCanonicalZecString()
        val platformFeeZec = platformFeeAmount.toCanonicalZecString()
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

        // Platform fee output (last payment, no memo) - may differ from message amount
        params.append("&address.$paymentIndex=$PLATFORM_FEE_ADDRESS")
        params.append("&amount.$paymentIndex=$platformFeeZec")

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

    private fun estimateRequiredSpendableBalance(
        memoCount: Int,
        amountPerOutput: Zatoshi
    ): Zatoshi {
        val outputCount = memoCount + 1 // message outputs + platform fee output
        val base = amountPerOutput.value * outputCount
        return Zatoshi(base + ESTIMATED_NETWORK_FEE_BUFFER_ZATOSHI)
    }

    private suspend fun hasPendingShieldedBalanceBlockingSpend(required: Zatoshi): Boolean {
        val account = accountDataSource.getSelectedAccount()
        // Funds EXIST (total covers it) but aren't spendable yet → some pending balance is blocking,
        // and it resolves on confirmation. This must report "still confirming", NOT "add ZEC". Use the
        // TOTAL pending (pendingShieldedBalance = our change + ZEC we just received), not only change:
        // requiring changePending>0 missed the very common case of replying right after RECEIVING funds
        // (valuePending), which then surfaced a misleading shortfall ("add ZEC") even though the user
        // clearly has incoming ZEC maturing.
        return account.spendableShieldedBalance < required &&
            account.totalShieldedBalance >= required &&
            account.pendingShieldedBalance > Zatoshi(0)
    }

    private fun isInsufficientFundsError(throwable: Throwable): Boolean {
        if (throwable is InsufficientFundsException) return true

        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message ?: ""
            val isMatch =
                message.contains("Insufficient balance", ignoreCase = true) ||
                    message.contains("InsufficientFunds", ignoreCase = true) ||
                    message.contains("Insufficient amount of ZEC", ignoreCase = true) ||
                    message.contains("additional change output", ignoreCase = true)
            if (isMatch) return true
            current = current.cause
        }
        return false
    }
}
