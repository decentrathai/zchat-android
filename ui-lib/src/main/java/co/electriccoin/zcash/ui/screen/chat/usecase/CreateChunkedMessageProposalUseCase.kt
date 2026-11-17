package co.electriccoin.zcash.ui.screen.chat.usecase

import android.util.Base64
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.toCanonicalZecString
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.KeystoneAvailability
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.PlanProbe
import co.electriccoin.zcash.ui.common.model.ProposalChange
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.SendMaxShape
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.model.orchardSealedRefusal
import co.electriccoin.zcash.ui.common.model.readSweepShape
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlan
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlanFunding
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsContext
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs

/**
 * P2a — the direct submit reported [co.electriccoin.zcash.ui.common.model.SubmitResult.Failure].
 * [nothingStored] relies on the repository's restricted Failure producers: empty IDs come only from
 * proven pre-SDK failures. Empty IDs from an arbitrary SDK exception do not establish this property;
 * those errors are SentUnconfirmed, since stored transactions may still be broadcast and mined. The
 * two-argument constructor is deliberate: coroutine stack-trace recovery only copies exceptions with a
 * standard constructor, and a copy would lose [nothingStored].
 */
class SubmitRejectedException(
    message: String,
    val nothingStored: Boolean,
    // P2a: the IDs of transactions the SDK stored before the rejection. It may still broadcast them, so the
    // caller links them to its row; once one is mined, reconciliation can retire the row.
    val txIds: List<String> = emptyList(),
) : RuntimeException(message)

/**
 * Thrown by the central pre-submit memo-size guard (#195) when a memo chunk would exceed Zcash's
 * 512-byte memo field. This is the single chokepoint EVERY on-chain ZCHAT memo passes through, so it
 * catches over-long memos from ANY producer — including the rawMemo control messages (KEX, ZBOOT,
 * GROUP_INVITE/KEY/KICK) that bypass the chunker and previously overflowed silently. Carries the
 * offending byte count + chunk index so the root cause is obvious instead of a cryptic SDK failure.
 */
class MemoTooLongException(message: String) : IllegalArgumentException(message)

/**
 * Thrown when the #248 value-transfer spend-auth (biometric / device-credential) is cancelled or fails
 * for a directSubmit on-chain send. Previously the use case SILENTLY returned on cancel, after which the
 * caller (e.g. sendPayment) still set SendMessageState.Success — so a cancelled/failed auth looked like a
 * successful payment while NO ZEC moved ("Send Payment does nothing" report). Throwing instead lets the
 * caller's catch render a clear "payment cancelled" state instead of a phantom success.
 */
class SpendAuthCancelledException(message: String) : Exception(message)

/**
 * A priced chat Send All (C-1, C-2, C-8, C-9): exactly what the New-Chat screen shows and what the send
 * proposes. [amount] rides output 0; [chunkCount] memo outputs, the platform fee and [fee] come on top,
 * and [keeps] stays in the wallet for the handshake that follows. It is priced by
 * [CreateChunkedMessageProposalUseCase.priceSendAll] and checked again, against the real proposal, at
 * send time.
 */
data class ChatSendAllQuote(
    val recipient: String,
    val chunkCount: Int,
    val keeps: Zatoshi,
    val amount: Zatoshi,
    val fee: Zatoshi,
)

/**
 * The proposal built when the user tapped Send no longer matches the Send All the screen showed: the
 * fee, the memo count or the pools moved in between. Nothing was signed or submitted. The screen prices
 * again and asks for a new tap; it never sends a different amount on its own.
 */
class SendAllAmountChangedException :
    Exception(
        CreateChunkedMessageProposalUseCase.SEND_ALL_AMOUNT_UPDATED_MESSAGE
    )

/**
 * SR-A (fix-all 2.13.0, ZC-105; t5b SPECS §3 C-SRA-1) — the exact price of ONE chat transaction the user was asked about
 * and paid: [chunkCount] memo outputs to [recipient] at [amountPerOutput] (the amount on the first, dust on the others),
 * the platform fee, and the network [fee] the SDK priced ([CreateChunkedMessageProposalUseCase.priceMessage]); [total] is
 * all of it. [transactions] is how many transactions the question counted (2 when our owed key exchange, [kexLeg], goes
 * first). The send checks its proposal against this before the spend auth and pays nothing when they differ. D-6 reuses
 * it for the payment confirms.
 */
data class ChatSendQuote(
    val recipient: String,
    val chunkCount: Int,
    val amountPerOutput: Zatoshi,
    val total: Zatoshi,
    val fee: Zatoshi,
    val transactions: Int = 1,
    val kexLeg: ChatSendQuote? = null,
)

/**
 * SR-A — the proposal built at send time is not the transaction the user was quoted: another network fee, memo count,
 * amount or recipient, more than one transaction, or an Orchard input. Thrown before the spend auth, before anything is
 * signed, stored or submitted. Neither a wait for funds nor a shortfall: the caller shows its own "the price changed"
 * text and the user asks again.
 */
class SendQuoteChangedException : Exception(CreateChunkedMessageProposalUseCase.SEND_QUOTE_CHANGED_MESSAGE)

/**
 * ZC-1233 (money-path rule 1) — the price a key-exchange answer may be paid at: [figure] is the whole transaction, its
 * outputs and network fee. A proposal above it is never paid; with [exact] (a figure the user confirmed on a question that
 * says "You pay exactly this") any other figure is not paid either.
 */
data class KexAnswerPrice(
    val figure: Zatoshi,
    val exact: Boolean = false,
)

/** ZC-1233 round 2 — Pay on a true-price question: the [price] it confirmed and the contact's [key] it asked about. */
data class ConfirmedKexAnswer(
    val price: KexAnswerPrice,
    val key: String,
)

/**
 * ZC-1233 — the proposal of a key-exchange answer is not at its [KexAnswerPrice]; [actual] is what it would cost. Thrown
 * before the spend auth, before anything is signed, stored or submitted. Neither a wait for funds nor a shortfall.
 */
class KexAnswerPriceChangedException(
    val actual: Zatoshi,
) : Exception(CreateChunkedMessageProposalUseCase.KEX_ANSWER_PRICE_CHANGED_MESSAGE)

/**
 * A payment shortfall whose explanation is already on screen: the use case opened the PAYMENT-context
 * insufficient-funds sheet before throwing this. The caller must still treat the payment as NOT sent,
 * but must not add a second notice of its own (C-6).
 */
class ShortfallAlreadyShownException(
    cause: Throwable
) : Exception(cause)

/**
 * T1b-1 (fix-all 2.13.0) — the ZC-102/K0 refusal of a chat send on a Keystone account, named so a caller can tell it
 * from a wait for funds: it is final for that account (a KEX the user confirmed on it is not kept for a later retry).
 * Still an UnsupportedOperationException with [CreateChunkedMessageProposalUseCase.KEYSTONE_AUTO_SEND_REFUSED_MESSAGE],
 * so the group banner and the chat error mapper read it as before.
 */
class KeystoneChatSendRefusedException :
    UnsupportedOperationException(CreateChunkedMessageProposalUseCase.KEYSTONE_AUTO_SEND_REFUSED_MESSAGE)

/**
 * T3-extra (fix-all 2.13.0) — rule (E), "never pay one of our own addresses", at the one door every on-chain chat memo
 * passes through: a send whose destination the self registry (ZchatPreferences.isSelfAddress) recognises is refused
 * before anything is built, signed or stored, so it proves nothing was sent (memoFailureProvesNothingSent). This covers
 * whatever the user agreed to earlier and is paid later (a queued message, a confirmed control, an owed or quoted setup,
 * a held group reply, a group memo consent) as well as a new tap. The chat error mapper names it by type.
 */
class OwnAddressChatSendRefusedException :
    IllegalStateException("Chat send to one of our own addresses refused before a proposal was built; nothing was sent")

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
    private val biometricRepository: BiometricRepository,
    // T3-extra: whether an address is one of our own (the app passes ZchatPreferences.isSelfAddress, UseCaseModule). The
    // default answers "no" for the tests that build the chokepoint without a store; the app never uses it.
    private val isOwnAddress: (String) -> Boolean = { false },
) {
    companion object {
        // Default amount per output (0.00001 ZEC = 1000 zatoshi)
        val DEFAULT_AMOUNT_PER_OUTPUT = Zatoshi(1000L)

        // The platform fee is ALWAYS minimal (0.00001 ZEC). CRITICAL: the previous default was
        // `platformFeeAmount = amountPerOutput`, so a Send-Payment / fulfil-request / unlock-payment
        // (which never pass platformFeeAmount) appended a SECOND full-amount output to the platform
        // address — every in-chat payment silently debited ~2x the entered amount. Capping the default
        // at this minimum makes the platform fee minimal regardless of the send amount.
        const val PLATFORM_FEE_MIN_ZATOSHI = 1000L

        // Platform fee address from shared constants
        private val PLATFORM_FEE_ADDRESS = ZMSGConstants.PLATFORM_FEE_ADDRESS

        // The old fee buffer for classifying "pending change vs truly insufficient". Only
        // estimateRequiredSpendable still uses it; shortfalls are now classified with
        // minimumRequiredSpendable and ShortfallReason (C-4), because 2,000 is below any real fee.
        private const val ESTIMATED_NETWORK_FEE_BUFFER_ZATOSHI = 2000L

        // Shown when funds EXIST but aren't spendable yet — covers BOTH our own change maturing AND
        // ZEC we just RECEIVED that's still confirming. (Found in 2-device testing: replying right
        // after receiving funds surfaced the misleading "add ZEC" message.) The stable "confirm
        // on-chain" substring is what the chat UI + group-invite retry classify transient blocks on.
        // Exposed (internal) so the single-source-of-truth error mapper (Throwable.toZchatUserMessage)
        // returns the SAME ZCHAT-voiced copy rather than a divergent duplicate string.
        internal const val PENDING_BALANCE_WAIT_MESSAGE =
            "Please wait for your ZEC to confirm on-chain, then try again."

        /**
         * ZC-102 — the refusal of a background send (directSubmit + skipNavigation) on a Keystone account, which can
         * never be signed there. Thrown before anything is stored. Round 8 D8 (F3): named, so a group banner can say
         * "a Keystone account can't send group messages" instead of "check your balance". Same text as before.
         * K0-2 (2.13.0): thrown for every chat send on a Keystone account; the user reads ChatSendCopy.KEYSTONE_CHATS_CLOSED
         * (toZchatUserMessage) or GROUP_KEYSTONE_REFUSED, never this internal text.
         */
        internal const val KEYSTONE_AUTO_SEND_REFUSED_MESSAGE =
            "Keystone hardware-wallet signing isn't available for this send — open the transaction to sign it."

        // R2-7 (c): it used to promise free messages after switching to Tunnel/Open, but Tunnel needs a paid
        // handshake and Open works only with a contact whose chat key you have. ChatSendCopy translates it.
        internal const val INSUFFICIENT_BALANCE_MESSAGE =
            "Insufficient balance for a Shielded message, which costs at least 0.00017 ZEC. Add ZEC and try " +
                "again. Open chats are free, but only with a contact whose chat key you have (⋮ → Conversation mode)."

        /**
         * C-6: a VALUE transfer that falls short. Switching the chat to Tunnel/Open cannot make a payment
         * free, so this copy does not offer it. It must not contain the words the error mapper keys on
         * ("Insufficient balance", "pending", "network", …), or the toast layer would swap it for another
         * message; ZchatErrorMessage matches it by [INSUFFICIENT_PAYMENT_MARKER].
         */
        internal const val INSUFFICIENT_PAYMENT_MARKER = "this payment plus its fees"
        internal const val INSUFFICIENT_PAYMENT_MESSAGE =
            "Not enough ZEC that can go to others for $INSUFFICIENT_PAYMENT_MARKER. Lower the amount or " +
                "add ZEC, then try again."

        /** C-9: the Send All the screen showed no longer matches what the wallet would build. */
        internal const val SEND_ALL_AMOUNT_UPDATED_MESSAGE = "Amount updated – tap Send again"

        /**
         * The memo every Send All probe carries, once per memo output. The fee depends on how many outputs
         * there are, never on what they say, so a probe needs the real COUNT but none of the real text.
         * The real message is never built for a probe: building it could run the E2E ratchet
         * (encryptOutgoing / cacheOwnOutgoingPlaintext) for a send that never happens.
         */
        private const val SEND_ALL_PROBE_MEMO = "ZCHAT send-all fee probe"

        /** M2: the memo every first-message plan probe carries (the fee depends on the memo count, not the text). */
        private const val PLAN_PROBE_MEMO = "ZCHAT first-message plan probe"

        /** SR-A: the memo every price probe carries (the fee depends on the memo count, not the text). */
        private const val PRICE_PROBE_MEMO = "ZCHAT message price probe"

        /** SR-A: the proposal is not the quoted transaction (the caller maps it to its own text). */
        internal const val SEND_QUOTE_CHANGED_MESSAGE = "The price of this send changed before it went out; nothing was paid"

        /** ZC-1233: the answer's build is not at its price (the caller asks or holds; this text is never shown). */
        internal const val KEX_ANSWER_PRICE_CHANGED_MESSAGE = "This key exchange answer is not at its price; nothing was paid"

        /**
         * ZC-1233 — whether a proposal of [memoCount] memo outputs at [amountPerOutput] with network [fee] may be paid at
         * [price]: null when it may, else what it would cost.
         */
        fun kexAnswerOverPrice(
            price: KexAnswerPrice,
            memoCount: Int,
            amountPerOutput: Zatoshi,
            fee: Zatoshi,
        ): Zatoshi? {
            val total = Zatoshi(totalCost(memoCount, amountPerOutput).value + fee.value)
            val refused = total.value > price.figure.value || (price.exact && total != price.figure)
            return total.takeIf { refused }
        }

        /**
         * SR-A (C-SRA-1) — whether the proposal built at send time is the transaction [quote] priced: the same recipient,
         * memo count and amount per output, outputs + network fee equal to the quoted total, one transaction and no
         * Orchard input. Anything else pays nothing.
         */
        @Suppress("LongParameterList")
        fun proposalMatchesQuote(
            quote: ChatSendQuote,
            destination: String,
            memoCount: Int,
            amountPerOutput: Zatoshi,
            fee: Zatoshi,
            transactionCount: Int,
            usesOrchardInputs: Boolean,
        ): Boolean =
            destination == quote.recipient &&
                memoCount == quote.chunkCount &&
                amountPerOutput == quote.amountPerOutput &&
                fee == quote.fee &&
                Zatoshi(totalCost(memoCount, amountPerOutput).value + fee.value) == quote.total &&
                transactionCount == 1 &&
                !usesOrchardInputs

        // ── Pure, side-effect-free money/send helpers (extracted for JVM unit testing) ──
        // These carry the fund-loss / double-charge invariants; the instance methods below delegate
        // to them so production behavior is unchanged while the logic stays testable without the
        // Android SDK repositories the constructor requires.

        /**
         * Clamp any caller-supplied platform fee to the minimum, so no path (even one that explicitly
         * passes the full send amount) can ever double-charge by appending a second full-amount output
         * to the platform address. This is the defense-in-depth clamp applied to [invoke]'s
         * platformFeeAmount regardless of what the caller passed.
         */
        fun clampPlatformFee(requestedFee: Zatoshi): Zatoshi =
            Zatoshi(minOf(requestedFee.value, PLATFORM_FEE_MIN_ZATOSHI))

        /** Default platform fee for a given per-output amount: minimal, never the full amount. */
        fun defaultPlatformFee(amountPerOutput: Zatoshi): Zatoshi =
            Zatoshi(minOf(amountPerOutput.value, PLATFORM_FEE_MIN_ZATOSHI))

        /** Dust carried by CONTINUATION outputs — the transfer amount rides the FIRST output ONLY (see
         * buildZip321Uri). Continuations exist only to carry extra memo chunks. */
        private fun continuationAmount(amountPerOutput: Zatoshi): Long =
            minOf(amountPerOutput.value, DEFAULT_AMOUNT_PER_OUTPUT.value)

        /**
         * Estimated spendable balance required to fund a send: the amount on the FIRST output, DUST on each
         * continuation memo output, a single minimal platform-fee output, and the network-fee buffer. Must
         * match buildZip321Uri's distribution — the amount is NOT replicated per chunk — so a long-memo
         * payment isn't misclassified as "add ZEC".
         */
        fun estimateRequiredSpendable(memoCount: Int, amountPerOutput: Zatoshi): Zatoshi {
            val base =
                amountPerOutput.value +
                    continuationAmount(amountPerOutput) * (memoCount - 1).coerceAtLeast(0) +
                    PLATFORM_FEE_MIN_ZATOSHI
            return Zatoshi(base + ESTIMATED_NETWORK_FEE_BUFFER_ZATOSHI)
        }

        /**
         * Total on-chain output cost of a send: the amount on the FIRST output, DUST on each of the other
         * [chunkCount]-1 memo outputs, plus exactly ONE minimal platform-fee output. The amount is NEVER
         * replicated per chunk (the money-overpay fix) and the fee is a SINGLE output (the double-charge
         * guard).
         */
        fun totalCost(chunkCount: Int, amountPerOutput: Zatoshi): Zatoshi =
            Zatoshi(
                amountPerOutput.value +
                    continuationAmount(amountPerOutput) * (chunkCount - 1).coerceAtLeast(0) +
                    defaultPlatformFee(amountPerOutput).value
            )

        /**
         * The smallest wallet balance a send of [amountPerOutput] over [memoCount] memo outputs can need:
         * its outputs plus the ZIP 317 fee for them and a change output. Used as the shortfall's "need"
         * when the SDK gave no figure; the old 2,000 buffer made "still confirming" fire when waiting
         * could never cover the fee (C-4).
         */
        fun minimumRequiredSpendable(memoCount: Int, amountPerOutput: Zatoshi): Zatoshi =
            Zatoshi(
                totalCost(memoCount.coerceAtLeast(1), amountPerOutput).value +
                    Zip317.conventionalFeeZatoshi(memoCount.coerceAtLeast(1) + 2)
            )

        /**
         * The transaction shape of a chat Send All for [SendMaxSearch]: [chunkCount] memo outputs to the
         * peer, the platform-fee output, and a change output whenever [keeps] is held back. Output 0 takes
         * the swept amount; every other output carries dust (see [buildZip321Uri]), so the sweep also pays
         * the platform fee plus one dust output per extra chunk. The scan starts at the fewest actions that
         * shape can be charged for (memo outputs + platform fee, + change when something is kept) and only
         * moves up, whatever the SDK does with a zero-value change output. The amount is never below dust:
         * under it the continuation outputs would shrink with it and the shape would no longer hold.
         */
        fun sendAllShape(chunkCount: Int, keeps: Zatoshi): SendMaxShape {
            val chunks = chunkCount.coerceAtLeast(1)
            val changeOutputs = if (keeps > Zatoshi(0)) 1 else 0
            return SendMaxShape(
                startingActions = maxOf(Zip317.GRACE_ACTIONS, chunks + 1 + changeOutputs),
                alsoPays = Zatoshi(PLATFORM_FEE_MIN_ZATOSHI + DEFAULT_AMOUNT_PER_OUTPUT.value * (chunks - 1)),
                keeps = keeps,
                minimumAmount = DEFAULT_AMOUNT_PER_OUTPUT,
            )
        }

        /**
         * Whether the proposal built at send time is the Send All [quote] the screen showed (C-9): same
         * recipient, same output 0, same fee, same number of memo outputs, one transaction and no Orchard
         * input. Anything else means the wallet moved between the quote and the tap.
         *
         * When the quote keeps a handshake reserve, [change] (the proposal's own change outputs, null when
         * unreadable) must also be that reserve as one Ironwood note (C-8), or the send stops.
         */
        @Suppress("LongParameterList")
        fun proposalMatchesSendAll(
            quote: ChatSendAllQuote,
            destination: String,
            amount: Zatoshi,
            fee: Zatoshi,
            transactionCount: Int,
            usesOrchardInputs: Boolean,
            memoCount: Int,
            change: List<ProposalChange>? = null,
        ): Boolean =
            destination == quote.recipient &&
                amount == quote.amount &&
                fee == quote.fee &&
                memoCount == quote.chunkCount &&
                transactionCount == 1 &&
                !usesOrchardInputs &&
                SendMaxSearch.keepsWhole(
                    priced = FeeProbe.Built(fee, usesOrchardInputs, transactionCount, change),
                    shape = sendAllShape(quote.chunkCount, quote.keeps),
                )

        /**
         * The words a caller that stays on screen (skipNavigation) shows for a shortfall (C-4, C-6).
         * "…confirm on-chain…" ONLY when waiting really covers the send: the handshake and group-invite
         * retry loops wait for a block on exactly that text, so a mislabelled shortfall used to retry for
         * nothing. A value transfer gets payment copy without the Tunnel/Open advice.
         */
        fun shortfallNoticeFor(
            reason: ShortfallReason,
            isValueTransfer: Boolean,
        ): String =
            when {
                reason is ShortfallReason.StillConfirming -> PENDING_BALANCE_WAIT_MESSAGE
                isValueTransfer -> INSUFFICIENT_PAYMENT_MESSAGE
                // ZC-1417: the SDK's figure, not the static floor (a wallet of 17,001..21,999 zatoshi needs 22,000).
                reason is ShortfallReason.NotEnough && reason.need != null && reason.need.value > reason.have.value ->
                    co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
                        .messageShortfall(reason.have, reason.need)
                else -> INSUFFICIENT_BALANCE_MESSAGE
            }

        /** The insufficient-funds sheet's context: a value transfer is a PAYMENT, a dust memo a MESSAGE. */
        fun insufficientFundsContextFor(amountPerOutput: Zatoshi): InsufficientFundsContext =
            if (amountPerOutput.value > DEFAULT_AMOUNT_PER_OUTPUT.value) {
                InsufficientFundsContext.PAYMENT
            } else {
                InsufficientFundsContext.MESSAGE
            }

        /** ZC-1233: what a build refused at its price would cost, anywhere in [throwable]'s cause chain. */
        internal fun kexAnswerActualPrice(throwable: Throwable): Zatoshi? =
            generateSequence(throwable) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .filterIsInstance<KexAnswerPriceChangedException>()
                .firstOrNull()
                ?.actual

        /** The SDK's "need R including fee" anywhere in [throwable]'s cause chain, if it gave one. */
        internal fun sdkShortfallNeed(throwable: Throwable): Zatoshi? =
            generateSequence(throwable) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .filterIsInstance<InsufficientFundsException>()
                .firstNotNullOfOrNull { it.need }

        private const val MAX_CAUSE_DEPTH = 20

        /**
         * Central pre-submit memo-size guard (#195). Returns the byte length of the FIRST memo chunk
         * that overflows Zcash's 512-byte memo field (UTF-8 bytes, not chars — emoji/multibyte count),
         * or null if every chunk fits. Callers throw [MemoTooLongException] with this count.
         */
        fun firstOverflowingMemoBytes(memos: List<String>): Pair<Int, Int>? {
            memos.forEachIndexed { index, memo ->
                val memoBytes = memo.toByteArray(Charsets.UTF_8).size
                if (memoBytes > ZMSGConstants.MAX_MEMO_SIZE) {
                    return index to memoBytes
                }
            }
            return null
        }

        /**
         * True if [throwable] (or anything in its cause chain) is an insufficient-funds signal —
         * matching the SDK message variants. Walks the whole cause chain so a wrapped SDK error is
         * still classified correctly.
         */
        fun isInsufficientFundsError(throwable: Throwable): Boolean {
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
     *
     * @return the settlement outcome when this call actually submitted (the directSubmit Zashi
     *   path), so a caller that words its own confirmation can tell a confirmed send from a
     *   [SubmitResult.SentUnconfirmed] one and stop claiming more than it knows; null when nothing
     *   was settled here (review-screen flow, Keystone hand-off). Ignorable — callers that only need
     *   "it didn't throw" are unchanged.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(
        destinationAddress: String,
        senderAddress: String,
        message: String,
        isFirstMessage: Boolean,
        amountPerOutput: Zatoshi = DEFAULT_AMOUNT_PER_OUTPUT,
        // Minimal by default — NEVER the full send amount (that double-charged every payment). A caller
        // may still pass an explicit fee, but it is clamped to the minimum below so no path can exceed it.
        platformFeeAmount: Zatoshi = defaultPlatformFee(amountPerOutput),
        directSubmit: Boolean = false,
        skipNavigation: Boolean = false,
        rawMemo: Boolean = false,
        conversationId: String? = null,
        lastReceivedTxId: String? = null,
        // C-9: set only by a Send All. The proposal built below must match it or nothing is sent.
        sendAllQuote: ChatSendAllQuote? = null,
        // P2a: run right before the SDK is asked to store and broadcast this send (after the spend auth). A
        // caller records here that the send may go out from now on; if it throws, nothing is submitted.
        onSubmitStarting: (suspend () -> Unit)? = null,
        // F2: run once the SDK has stored every transaction of this send and before the first one is submitted, with
        // their ids and expiry heights. If it throws, nothing is submitted. Null keeps today's call.
        onTransactionsStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored? = null,
        // SR-A (C-SRA-1): set when the user was asked an exact price. The proposal built below must be that transaction,
        // or nothing is signed, stored or sent (SendQuoteChangedException).
        sendQuote: ChatSendQuote? = null,
        // ZC-1233: set for a key-exchange answer. A proposal not at this price is not signed, stored or sent
        // (KexAnswerPriceChangedException names what it would cost).
        kexAnswerPrice: KexAnswerPrice? = null,
    ): SubmitResult? {
        // ZC-102 GUARD — protect the in-flight Keystone hardware-signing session from background
        // auto-sends. A directSubmit + skipNavigation send is the frictionless background path
        // (read receipts, KEX acks, group control memos — fired by INBOUND traffic with no user
        // interaction). On a Keystone-selected account it can NEVER complete: there is no Keystone
        // auto-sign, and skipNavigation suppresses the interactive signing screen, so the branch
        // below always threw UnsupportedOperationException — but only AFTER createMultiOutputProposal
        // had already OVERWRITTEN the process-wide singleton Keystone session (createZip321Proposal +
        // createPCZTFromProposal) and the catch below had clear()ed it, destroying whatever
        // proposal/PCZT the user was mid-signing on the hardware device (SubmitKSProposalUseCase then
        // hangs on getTransactionProposal() or dies with "PCZT with proofs is null"). Fail fast HERE,
        // BEFORE the try/catch, so neither the overwrite nor the clear() can touch the user's session.
        // Net behavior for the caller is unchanged — this send failed with this exact exception
        // before; it just no longer takes the user's signing session down with it.
        // TODO(ZC-102 follow-up): thread a per-send SigningSession through the Keystone flow instead
        // of the singleton repository cell (mirroring the ZC-101 per-send proposal approach) so
        // concurrent sends cannot interact through shared state at all.
        //
        // K0-2 (fix-all 2.13.0): widened to EVERY chat send while Keystone chat signing is closed
        // (KeystoneAvailability.CHAT_SIGNING), whatever directSubmit and skipNavigation say. The Pay and payment-request
        // shapes (skipNavigation = false) used to pass: they built a Keystone proposal + PCZT over the Wallet tab's
        // signing session and opened TransactionProgress, which never shows the signing QR and hangs. Same exception
        // (the group banner matches it; the chat error mapper names it), still BEFORE the try so the catch's clear()
        // can never wipe a Wallet-tab Keystone session. Wallet → Send does not come through here.
        if (!KeystoneAvailability.CHAT_SIGNING && accountDataSource.getSelectedAccount() is KeystoneAccount) {
            Twig.warn {
                "ZC-102/K0: rejecting a chat send on a Keystone account before it can touch the hardware-signing " +
                    "session (directSubmit=$directSubmit, skipNavigation=$skipNavigation)"
            }
            throw KeystoneChatSendRefusedException()
        }
        // T3-extra, rule (E): never a chat send to one of our own addresses, whoever asks for it and whenever it was
        // agreed to. Before the try, like the Keystone refusal: nothing is built, signed or stored.
        if (isOwnAddress(destinationAddress)) {
            Twig.warn { "T3-extra: a chat send to one of our own addresses refused before a proposal was built" }
            throw OwnAddressChatSendRefusedException()
        }
        NetworkUpgradeGuard.refusal()?.let { throw it }
        // NU6.3 sealed Orchard: an Orchard Action may only pay its own address (ZIP 258), so a wallet
        // whose shielded value is ALL in Orchard cannot reach a peer — and the SDK's input selector,
        // which draws on Orchard last but does draw on it, would fund the attempt as a turnstile
        // crossing with a PUBLIC amount (ZIP 318). Refuse here, at the one chokepoint every on-chain
        // memo passes through, so no sender path (user message, reaction, receipt, or an automatic
        // KEX/KEXACK/ZBOOT handshake) can cross by accident. Gated on SPENDABLE Ironwood (see
        // orchardSealedRefusal): pending-only Ironwood is still stranded, and is reported as the
        // "wait for confirmation" variant rather than "migrate".
        orchardSealedRefusal(accountDataSource.getSelectedAccount().unified)?.let { throw it }
        // Defense-in-depth: clamp the platform fee to the minimum regardless of what any caller passed,
        // so no path (including ones that explicitly pass the full amount) can ever double-charge.
        val platformFee = clampPlatformFee(platformFeeAmount)
        var estimatedRequiredSpendable: Zatoshi? = null
        // C4 (B1 review item 4): set right before the SDK is asked to store and broadcast this send. From then on a
        // failure is judged only by what it proves was stored, never by its text (see the catch below).
        var submitStarted = false
        try {
            // Generate the memo chunks
            val memos =
                if (rawMemo) {
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
            firstOverflowingMemoBytes(memos)?.let { (index, memoBytes) ->
                throw MemoTooLongException(
                    "Memo chunk ${index + 1}/${memos.size} is $memoBytes bytes, over the " +
                        "${ZMSGConstants.MAX_MEMO_SIZE}-byte Zcash memo limit (rawMemo=$rawMemo). " +
                        "This message type must be made compact before sending."
                )
            }
            estimatedRequiredSpendable = minimumRequiredSpendable(memos.size, amountPerOutput)

            // Always use ZIP321 since we have message output(s) + platform fee output.
            // ZC-101: capture the proposal created for THIS send — submit below must settle exactly
            // this object, even if a concurrent send overwrites the repository's shared cell while
            // this send is parked on the biometric prompt.
            val proposal = createMultiOutputProposal(destinationAddress, memos, amountPerOutput, platformFee)

            // C-9 SEND-TIME GUARD. A Send All is priced when the screen shows it and proposed again here,
            // for the displayed output 0. If that proposal is not the one the user saw (another fee, another
            // memo count, Orchard inputs, more than one transaction), stop BEFORE the spend-auth and the
            // submit: the screen re-prices and asks for a new tap. Never send a different amount on its own.
            if (sendAllQuote != null &&
                !proposalMatchesSendAll(
                    quote = sendAllQuote,
                    destination = destinationAddress,
                    amount = proposal.amount,
                    fee = proposal.proposal.totalFeeRequired(),
                    transactionCount = proposal.proposal.transactionCount(),
                    usesOrchardInputs = proposal.proposal.usesOrchardInputs(),
                    memoCount = memos.size,
                    change = readSweepShape(proposal.proposal.toByteArray())?.change,
                )
            ) {
                throw SendAllAmountChangedException()
            }

            // SR-A SEND-TIME GUARD (C-SRA-1), next to C-9 and before the spend auth, onSubmitStarting and
            // onTransactionsStored: a send the user was asked an exact price for goes out only as exactly that
            // transaction. Never pay a figure the user did not see, more or less.
            if (sendQuote != null &&
                !proposalMatchesQuote(
                    quote = sendQuote,
                    destination = destinationAddress,
                    memoCount = memos.size,
                    amountPerOutput = amountPerOutput,
                    fee = proposal.proposal.totalFeeRequired(),
                    transactionCount = proposal.proposal.transactionCount(),
                    usesOrchardInputs = proposal.proposal.usesOrchardInputs(),
                )
            ) {
                Twig.warn { "SR-A: the proposal is not the quoted transaction; nothing signed, stored or sent" }
                throw SendQuoteChangedException()
            }

            // ZC-1233, same place: a key-exchange answer never pays above the figure the user was shown.
            kexAnswerPrice
                ?.let { kexAnswerOverPrice(it, memos.size, amountPerOutput, proposal.proposal.totalFeeRequired()) }
                ?.let { actual ->
                    Twig.warn { "ZC-1233: a key exchange answer's build is not at its price; nothing signed, stored or sent" }
                    throw KexAnswerPriceChangedException(actual)
                }

            return if (directSubmit) {
                // Auto-submit for ZCHAT (smooth UX after user has acknowledged cost)
                when (accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        // Keystone requires manual signing, so we navigate to the signing flow —
                        // nothing is settled here, hence no outcome to report back.
                        if (!skipNavigation) {
                            navigationRouter.forward(TransactionProgressArgs)
                            null
                        } else {
                            // Normally unreachable since the ZC-102 guard at the top of invoke() rejects
                            // directSubmit + skipNavigation on Keystone BEFORE any proposal is created.
                            // Kept as defense-in-depth (fail loud, never phantom-"delivered") in case a
                            // future path reaches here, e.g. the selected account changing mid-send.
                            // directSubmit + skipNavigation is the frictionless chat/group send path, but
                            // there is NO auto-submit for Keystone here and skipNavigation suppresses the
                            // interactive signing screen — so nothing gets signed or broadcast. Silently
                            // returning would make the caller (sendGroupMemoWithRetry / doSendMessage) report
                            // the message as SENT and leave the created PCZT dangling. Throw so it surfaces as
                            // a real failure (the outer catch clears the un-submitted proposal), not a phantom
                            // "delivered".
                            throw KeystoneChatSendRefusedException()
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
                                // Don't silently return — the caller would report Success with no spend.
                                // Throw so it surfaces as a clear "cancelled" state (outer catch clears the
                                // unsubmitted proposal). skipNavigation true/false both re-throw it below.
                                throw SpendAuthCancelledException("Payment cancelled — authentication wasn't completed.")
                            } catch (_: BiometricsFailureException) {
                                throw SpendAuthCancelledException("Couldn't verify it's you — payment not sent. Try again.")
                            }
                        }
                        // Submit directly - pass skipNavigation so errors propagate to caller.
                        // ZC-101: pass the proposal created for THIS send — the biometric above
                        // authorized IT, not whatever now sits in the shared cell.
                        // ZC-1472: what THIS proposal pays, for a caller that reports it after the send (ProposalPriceSink).
                        kotlin.coroutines.coroutineContext[ProposalPriceSink]?.onPriced?.invoke(
                            runCatching {
                                Zatoshi(
                                    amountPerOutput.value + (memos.size - 1) * continuationAmount(amountPerOutput) +
                                        platformFee.value + proposal.proposal.totalFeeRequired().value,
                                )
                            }.getOrNull(),
                        )
                        onSubmitStarting?.invoke()
                        submitStarted = true
                        val outcome = submitZashiProposal(proposal, skipNavigation, onTransactionsStored)
                        // Only navigate if not skipping (for smooth chat flow, stay on chat screen)
                        if (!skipNavigation) {
                            navigationRouter.forward(TransactionProgressArgs)
                        }
                        outcome
                    }
                }
            } else {
                // Legacy flow: go to review screen for confirmation
                if (!skipNavigation) {
                    navigationRouter.forward(TransactionProgressArgs)
                }
                null
            }
        } catch (e: Exception) {
            keystoneProposalRepository.clear()
            zashiProposalRepository.clear()

            // C4 (B1 review item 4): once the submit started, a failure that does not prove nothing was stored
            // (above all SubmitRejectedException(nothingStored = false), whose message embeds the server's text)
            // reaches the caller as it is, with its txids. Classifying it as a shortfall by that text threw a NEW
            // cause-less InsufficientFundsException: the txids were lost, and a caller that reads an
            // insufficient-funds error as "nothing was sent" could retry a transaction the SDK may still broadcast.
            if (submitStarted && !memoFailureProvesNothingSent(e, submitStarted = true)) throw e

            val isInsufficientFunds = isInsufficientFundsError(e)
            // C-4: WHY it fell short is judged from the wallet (ShortfallReason), with the SDK's own "need"
            // when it gave one. It used to be a 2,000-zatoshi fee buffer over Orchard-inclusive totals,
            // which said "please wait to confirm" when no confirmation could ever cover the send.
            val shortfall =
                if (isInsufficientFunds) {
                    ShortfallReason.classify(
                        account = accountDataSource.getSelectedAccount(),
                        need = sdkShortfallNeed(e) ?: estimatedRequiredSpendable,
                    )
                } else {
                    null
                }

            // When skipNavigation=true, the caller handles ALL errors (including insufficient funds)
            // Navigating would contradict the caller's explicit request to stay on current screen
            if (skipNavigation) {
                if (shortfall != null) {
                    // D1 (REVIEW_7C F1): the failure it was found in stays the cause. After the submit started, only
                    // a SubmitRejectedException(nothingStored = true) reaches here (C4 rethrew every other one above),
                    // and it is the callers' only proof that nothing was stored: a cause-less shortfall made every
                    // caller read a proven "nothing sent" as "may have been sent".
                    throw InsufficientFundsException(
                        message =
                            shortfallNoticeFor(
                                reason = shortfall,
                                isValueTransfer = amountPerOutput.value > DEFAULT_AMOUNT_PER_OUTPUT.value,
                            ),
                        cause = e,
                    )
                }
                throw e
            }

            if (shortfall != null) {
                // C-6: ONE notice, in the right context. A payment used to open the MESSAGE sheet ("your
                // previous message is still being shielded") while the caller ALSO showed an error. The
                // sheet now names the reason and its numbers, and the typed exception tells the caller the
                // explanation is already on screen.
                navigationRouter.forward(
                    InsufficientFundsArgs.of(
                        context = insufficientFundsContextFor(amountPerOutput),
                        shortfall = shortfall,
                    )
                )
                throw ShortfallAlreadyShownException(e)
            }
            // ALWAYS rethrow — including insufficient-funds. Previously this branch swallowed the
            // exception after navigating, so the caller (sendPayment / doSendMessage / fulfil) fell
            // through to SendMessageState.Success → a "Message sent" toast for a payment that NEVER
            // sent. The user believed a real-world debt was paid. Now the caller sets a failure state.
            throw e
        }
    }

    /**
     * The exact New-Chat Send All to [destinationAddress] (C-1, C-2, C-8): Ironwood available minus the
     * platform fee, the dust continuations for [chunkCount] memo outputs, the SDK's real fee and [keeps].
     * Asked of the SDK with the same [SendMaxSearch] the wallet's Send all uses, over this send's shape.
     *
     * Every probe is a ZIP 321 request built exactly like the real send ([buildZip321Uri]), except that
     * each memo output carries a placeholder: the real message is never built or encrypted here, so the
     * E2E ratchet cannot move. Nothing is stored or submitted. Any failure other than a shortfall is
     * [SendMaxUnavailable.CouldNotPrice]; the user can still pick another amount.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun priceSendAll(
        destinationAddress: String,
        chunkCount: Int,
        keeps: Zatoshi,
    ): SendMaxResult {
        NetworkUpgradeGuard.refusal()?.let { throw it }
        return try {
            val placeholderMemos = List(chunkCount.coerceAtLeast(1)) { SEND_ALL_PROBE_MEMO }
            SendMaxSearch.search(
                recipient = SendMaxRecipient.SHIELDED,
                readAccount = { accountDataSource.getSelectedAccount() },
                probe = { account, amount ->
                    zashiProposalRepository.probeZip321Fee(
                        account = account,
                        zip321Uri =
                            buildZip321Uri(
                                destinationAddress = destinationAddress,
                                memos = placeholderMemos,
                                amountPerOutput = amount,
                                platformFeeAmount = clampPlatformFee(defaultPlatformFee(amount)),
                            ),
                    )
                },
                shape = sendAllShape(chunkCount, keeps),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.debug { "Chat Send All could not be priced: ${e.javaClass.simpleName}" }
            SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
        }
    }

    /**
     * ZC-602 — the own-address answer of this door ([invoke] and [priceMessage] refuse on it), for a form that must say
     * so before a tap: the self registry, or an address an account of this wallet holds now.
     */
    fun refusesAsOwnAddress(address: String): Boolean = isOwnAddress(address)

    /**
     * SR-A (fix-all 2.13.0, C-SRA-1) — the network fee the selected wallet would pay for one chat transaction of
     * [chunkCount] memo outputs to [destinationAddress] at [amountPerOutput] (and the platform fee), asked of the SDK
     * with a ZIP 321 request built exactly like the real send ([buildZip321Uri]) and placeholder memos: the real message
     * is never built or encrypted, nothing is stored or submitted. The Keystone and own-address refusals come first, as in
     * [invoke]; any SDK failure other than a shortfall throws. [FeeProbe.Short]: the wallet cannot pay it now.
     */
    suspend fun priceMessage(
        destinationAddress: String,
        chunkCount: Int,
        amountPerOutput: Zatoshi,
    ): FeeProbe {
        if (!KeystoneAvailability.CHAT_SIGNING && accountDataSource.getSelectedAccount() is KeystoneAccount) {
            throw KeystoneChatSendRefusedException()
        }
        if (isOwnAddress(destinationAddress)) throw OwnAddressChatSendRefusedException()
        NetworkUpgradeGuard.refusal()?.let { throw it }
        return zashiProposalRepository.probeZip321Fee(
            account = accountDataSource.getSelectedAccount(),
            zip321Uri =
                buildZip321Uri(
                    destinationAddress = destinationAddress,
                    memos = List(chunkCount.coerceAtLeast(1)) { PRICE_PROBE_MEMO },
                    amountPerOutput = amountPerOutput,
                    platformFeeAmount = clampPlatformFee(defaultPlatformFee(amountPerOutput)),
                ),
        )
    }

    /**
     * M2 (fix-all 2.13.0) — can the selected wallet pay both transactions of a first message to [destinationAddress]:
     * our KEX (one memo at [DEFAULT_AMOUNT_PER_OUTPUT] and the platform fee, as the KEX is sent), then the message
     * ([messageMemos] memos at [amountPerOutput] and the platform fee, as [invoke] builds it)? Each leg is priced on the
     * current wallet as a ZIP 321 request built exactly like the real send ([buildZip321Uri]), with placeholder memos:
     * the real message is never built or encrypted here, nothing is stored or submitted and no key is created.
     *
     * The Keystone and own-address refusals come first, as in [invoke] (nothing is probed; the caller reads them as
     * Unchecked and shows their own text). A leg Short only because Ironwood is still confirming
     * ([ShortfallReason.classify] says StillConfirming, with the SDK's "need" or the leg's minimum) is
     * [FirstMessagePlan.WaitForFunds]; otherwise [FirstMessagePlanFunding.decide]. Any other failure throws.
     */
    suspend fun probeFirstMessagePlan(
        destinationAddress: String,
        messageMemos: Int,
        amountPerOutput: Zatoshi,
    ): FirstMessagePlan {
        if (!KeystoneAvailability.CHAT_SIGNING && accountDataSource.getSelectedAccount() is KeystoneAccount) {
            throw KeystoneChatSendRefusedException()
        }
        if (isOwnAddress(destinationAddress)) throw OwnAddressChatSendRefusedException()
        NetworkUpgradeGuard.refusal()?.let { throw it }
        val memos = messageMemos.coerceAtLeast(1)
        val account = accountDataSource.getSelectedAccount()
        val kexUri =
            buildZip321Uri(
                destinationAddress = destinationAddress,
                memos = listOf(PLAN_PROBE_MEMO),
                amountPerOutput = DEFAULT_AMOUNT_PER_OUTPUT,
                platformFeeAmount = clampPlatformFee(defaultPlatformFee(DEFAULT_AMOUNT_PER_OUTPUT)),
            )
        val messageUri =
            buildZip321Uri(
                destinationAddress = destinationAddress,
                memos = List(memos) { PLAN_PROBE_MEMO },
                amountPerOutput = amountPerOutput,
                platformFeeAmount = clampPlatformFee(defaultPlatformFee(amountPerOutput)),
            )
        val kex = zashiProposalRepository.probeZip321Plan(account = account, zip321Uri = kexUri)
        val message = zashiProposalRepository.probeZip321Plan(account = account, zip321Uri = messageUri)
        val messageAlone = minimumRequiredSpendable(memos, amountPerOutput)
        val shorts =
            listOfNotNull(
                (kex as? PlanProbe.Short)?.let { it.need ?: minimumRequiredSpendable(1, DEFAULT_AMOUNT_PER_OUTPUT) },
                (message as? PlanProbe.Short)?.let { it.need ?: messageAlone },
            )
        // ZC-1948, ZC-1954: the confirming Ironwood must cover every leg together (a built leg at its minimum), not each
        // short one alone.
        val kexNeed = (kex as? PlanProbe.Short)?.need ?: minimumRequiredSpendable(1, DEFAULT_AMOUNT_PER_OUTPUT)
        val messageNeed = (message as? PlanProbe.Short)?.need ?: messageAlone
        if (shorts.isNotEmpty() &&
            ShortfallReason.classify(account, kexNeed + messageNeed) is ShortfallReason.StillConfirming
        ) {
            return FirstMessagePlan.WaitForFunds
        }
        return FirstMessagePlanFunding.decide(kex = kex, message = message, messageAlone = messageAlone)
    }

    /**
     * Submit the Zashi proposal directly without user confirmation.
     * Used for ZCHAT direct send after user has acknowledged message costs.
     *
     * @param skipNavigation If true, rethrow errors so the caller can handle them
     *   (since the user won't navigate to TransactionProgress screen to see errors)
     *
     * @return the settled [SubmitResult], or null when the outcome is being shown on the
     *   TransactionProgress screen instead. Returned so a caller wording its own confirmation can
     *   tell a [SubmitResult.SentUnconfirmed] or a [SubmitResult.GrpcFailure] (stored, not delivered
     *   yet, re-broadcast by the SDK) from a real Success rather than claiming "sent".
     */
    private suspend fun submitZashiProposal(
        proposal: Zip321TransactionProposal,
        skipNavigation: Boolean = false,
        onTransactionsStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored? = null,
    ): SubmitResult? {
        val result =
            try {
                if (onTransactionsStored == null) {
                    zashiProposalRepository.submit(proposal)
                } else {
                    zashiProposalRepository.submit(proposal, onTransactionsStored)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (skipNavigation) {
                    // When skipNavigation=true (ZCHAT flow), errors must propagate
                    // because the user stays on the chat screen and never sees
                    // the TransactionProgress error screen
                    throw e
                }
                // Otherwise error will be shown on TransactionProgress screen
                return null
            }
        // submit() reports every outcome as a RETURN VALUE (Failure / GrpcFailure / Partial /
        // SentUnconfirmed). In the ZCHAT direct-send flow the user never sees the
        // TransactionProgress error screen, so a rejected result must be turned into a thrown error
        // the caller can act on — otherwise a tx that was created+signed but REJECTED by lightwalletd
        // is silently reported as "sent" (false-success on group invites, group messages, 1:1).
        // Throw ONLY when nothing landed: a Failure. On that path the server ANSWERED with a
        // non-zero code and SDK 3.0.0 then asked it whether it already knew the transaction
        // (isTransactionKnownToServer) before reporting failure, so retrying is safe.
        // A GrpcFailure is NOT that. It is a gRPC/Tor transport failure: the SDK never asked the
        // server whether the transaction landed, the transaction is already stored with its submit
        // plan, and CompactBlockProcessor re-broadcasts it on every sync until it is mined or
        // expires. It means "not delivered YET", and money may still move. Throwing on it made the
        // automatic senders (ZBOOT / KEX / KEXACK / time-lock / GROUP_ACCEPT / group memos) clear
        // their already-sent markers and pay the same leg again, and made fulfillPaymentRequest skip
        // markRequestPaid so the live "Pay" button re-sent the full amount.
        // A Partial means at least ONE output already landed on-chain — money HAS moved. Throwing on
        // Partial would make fulfillPaymentRequest treat a partially-paid request as unpaid
        // (markRequestPaid + the paidRequestIds idempotency backstop only run on the no-throw path),
        // leaving the "Pay" affordance live so the user re-taps and OVERPAYS. So treat Partial like
        // Success on this direct-submit path: the caller marks it submitted/paid and arms idempotency;
        // the partial nuance is not re-charged.
        // If the SDK's re-broadcast expires unmined, the handshake's markers still say "sent". F8 (ZC-112) owns that gap:
        // the attempt's ledger entry is proven not sent by the history pass, and the chat shows "Key exchange not sent"
        // with a quoted Retry (ChatViewModel.reconcileSendAttemptLedger, retryHandshake). Nothing is paid again by itself.
        if (skipNavigation) {
            when (result) {
                is co.electriccoin.zcash.ui.common.model.SubmitResult.Failure ->
                    throw SubmitRejectedException(
                        "Transaction submit failed (code=${result.code}${result.description?.let { " $it" } ?: ""})",
                        // P2a: only the proven pre-SDK NothingBroadcastException produces empty-ID Failure.
                        // With a txid the transaction is stored, and the SDK may broadcast it again.
                        nothingStored = result.txIds.isEmpty(),
                        txIds = result.txIds,
                    )
                is co.electriccoin.zcash.ui.common.model.SubmitResult.GrpcFailure,
                is co.electriccoin.zcash.ui.common.model.SubmitResult.Partial,
                is co.electriccoin.zcash.ui.common.model.SubmitResult.SentUnconfirmed,
                is co.electriccoin.zcash.ui.common.model.SubmitResult.Success -> {
                    // money moved or may still move — caller treats as submitted; do NOT re-charge.
                    // SentUnconfirmed joins them: the settlement call threw AFTER the broadcast, so the
                    // transaction may be on the wire. GrpcFailure joins them: the SDK keeps
                    // re-broadcasting the stored transaction. Reporting either as failed here is what
                    // made the handshake/group retries re-arm their sent-markers and pay the same leg
                    // again, and made fulfillPaymentRequest skip markRequestPaid so the live "Pay"
                    // button re-sent the full amount. The next sync settles the truth; nothing here
                    // re-charges. The result is returned so the caller can word "not confirmed yet".
                }
            }
        }
        return result
    }

    /**
     * ZC-101: returns the proposal created for THIS send so the caller can pass it to submit —
     * settlement must never depend on the repositories' shared observation cell.
     */
    private suspend fun createMultiOutputProposal(
        destinationAddress: String,
        memos: List<String>,
        amountPerOutput: Zatoshi,
        platformFeeAmount: Zatoshi = defaultPlatformFee(amountPerOutput)
    ): Zip321TransactionProposal {
        // Build ZIP321 URI with multiple payments
        val zip321Uri = buildZip321Uri(destinationAddress, memos, amountPerOutput, platformFeeAmount)

        return when (accountDataSource.getSelectedAccount()) {
            is KeystoneAccount -> {
                // Trusted app-generated chat-memo URI (memo output(s) + a minimal platform-fee output),
                // so it is exempt from the scanned-URI single-payment guard in createZip321Proposal.
                val proposal = keystoneProposalRepository.createZip321Proposal(zip321Uri, allowMultiPayment = true)
                keystoneProposalRepository.createPCZTFromProposal()
                proposal
            }
            is ZashiAccount -> {
                zashiProposalRepository.createZip321Proposal(zip321Uri, allowMultiPayment = true)
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
        platformFeeAmount: Zatoshi = defaultPlatformFee(amountPerOutput)
    ): String {
        // MONEY-SAFETY (fail closed): destinationAddress reaches here from attacker-influenceable
        // sources — notably the GROUP_INVITE "inviter" JSON, which ChatViewModel adopts UNAUTHENTICATED
        // (`authenticatedSender ?: json.getString("inviter")`), plus scanned QRs and pasted contact
        // codes — and is interpolated RAW into the URI below. Reject anything that is not a bare
        // alphanumeric address instead of trusting the ZIP-321 parser to reject it for us. No-op for
        // every legitimate address; the IllegalArgumentException surfaces as a normal, loud send failure.
        require(co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants.isBareAddressShaped(destinationAddress)) {
            "Refusing to build ZIP321 URI for non-canonical address"
        }
        // ZIP-321 `amount` must use a canonical '.' decimal separator regardless of device
        // locale, otherwise non-English locales emit invalid URIs (e.g. amount=0,00001).
        val amountZec = amountPerOutput.toCanonicalZecString()
        // MONEY-SAFETY: the transfer amount rides the FIRST output ONLY. Continuation outputs exist solely to
        // carry ADDITIONAL memo chunks of a long message, so they carry DUST. Otherwise a value transfer
        // (Send-Payment / fulfil-request / unlock-payment — all pass the full amount as amountPerOutput) whose
        // memo spans N chunks would replicate the full amount on EACH of the N outputs and debit ~N× the
        // intended amount. minOf keeps it ≤ the per-output amount for a sub-dust amountPerOutput.
        val continuationZec = Zatoshi(minOf(amountPerOutput.value, DEFAULT_AMOUNT_PER_OUTPUT.value)).toCanonicalZecString()
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
            params.append("&amount.$paymentIndex=$continuationZec")
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
        return totalCost(chunkCount, amountPerOutput)
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
