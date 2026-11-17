package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.NothingBroadcastException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareGate
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareVersion
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import com.keystone.sdk.KeystoneSDK
import com.keystone.sdk.KeystoneZcashSDK
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
interface KeystoneProposalRepository {
    val transactionProposal: Flow<TransactionProposal?>

    val submitState: Flow<SubmitProposalState?>

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createProposal(zecSend: ZecSend)

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactInputSwapProposal(zecSend: ZecSend, quote: SwapQuote): ExactInputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactOutputSwapProposal(zecSend: ZecSend, quote: SwapQuote): ExactOutputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createZip321Proposal(zip321Uri: String, allowMultiPayment: Boolean = false): Zip321TransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createShieldProposal()

    @Throws(PcztException.CreatePcztFromProposalException::class, TexUnsupportedOnKSException::class)
    suspend fun createPCZTFromProposal()

    @Throws(IllegalStateException::class)
    suspend fun createPCZTEncoder(): UREncoder

    /** K0-4 (2.13.0): also refuses a signature from Keystone firmware below 3.0.2 before it is stored. */
    @Throws(ParsePCZTException::class, KeystoneFirmwareBelowMinimumException::class)
    suspend fun parsePCZT(ur: UR)

    /**
     * ZC-101: submits the send identified by the [proposal] PASSED BY THE CALLER — never re-read
     * from the shared [transactionProposal] cell, which a concurrent proposal creation can
     * overwrite between create and submit.
     *
     * MONEY-CRITICAL: never reports a broadcast transaction as failed. An exception out of the data
     * source is AMBIGUOUS — the broadcast happens first and the balance refresh runs after it — so
     * it becomes [SubmitResult.SentUnconfirmed], never [SubmitResult.Failure]. The pre-broadcast
     * refusals below (no PCZT, unbound PCZT) stay real failures: nothing was put on the wire.
     */
    suspend fun submit(proposal: TransactionProposal): SubmitResult

    fun clear()

    suspend fun getTransactionProposal(): TransactionProposal

    fun getProposalPCZT(): Pczt?
}

class ParsePCZTException : Exception()

/**
 * F2 (fix-all 2.13.0, PLAN.md cross-lane correction 4): the proposal is more than one transaction (a TEX payment:
 * leg 1 to an ephemeral transparent address, leg 2 from it to the exchange). A Keystone signs one PCZT, which carries
 * one transaction, so signing it would send leg 1 only and strand the funds at the ephemeral address. Thrown by
 * [KeystoneProposalRepository.createPCZTFromProposal] before a PCZT is built, so nothing is built, stored or sent.
 * Upstream (Zodl) maps the SDK's MultiStepProposalUnsupportedException to the same name; SDK 3.0.0 has no such check.
 */
class TexUnsupportedOnKSException(
    val transactionCount: Int,
) : Exception("A Keystone cannot sign a payment of $transactionCount transactions (TEX)")

/**
 * K0-4 (2.13.0): the signed PCZT came from Keystone firmware below KeystoneFirmwareVersion.MINIMUM_SUPPORTED (3.0.2).
 * [detected] is null when the firmware is too old to stamp its version. Thrown before the signature is stored for
 * submission, so nothing is sent.
 */
class KeystoneFirmwareBelowMinimumException(
    val detected: KeystoneFirmwareVersion?
) : Exception(
        "Keystone firmware ${detected ?: "unstamped"} is below the minimum supported " +
            "${KeystoneFirmwareVersion.MINIMUM_SUPPORTED}"
    )

sealed interface SubmitProposalState {
    data object Submitting : SubmitProposalState

    data class Result(
        val submitResult: SubmitResult
    ) : SubmitProposalState
}

@Suppress("TooManyFunctions")
class KeystoneProposalRepositoryImpl(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
) : KeystoneProposalRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val transactionProposal = MutableStateFlow<TransactionProposal?>(null)

    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    private val pcztWithProofs = MutableStateFlow(PcztState(isLoading = false, pczt = null))
    private var proposalPczt: Pczt? = null
    private var pcztWithSignatures: Pczt? = null

    /**
     * The proposal the live PCZT pair ([proposalPczt]/[pcztWithProofs]) was built FOR, captured by
     * [createPCZTFromProposal] and reset by [clear]. [submit] refuses (fail-closed, no broadcast)
     * unless the caller's proposal is this exact instance — see the binding comment at [submit].
     */
    private var pcztBoundProposal: TransactionProposal? = null

    private val keystoneSDK: KeystoneSDK by lazy { KeystoneSDK() }
    private val keystoneZcashSDK: KeystoneZcashSDK
        get() = keystoneSDK.zcash
    private var pcztWithProofsJob: Job? = null

    override suspend fun createProposal(zecSend: ZecSend) {
        createProposalInternal {
            proposalDataSource.createProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend
            )
        }
    }

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal =
        createProposalInternal {
            proposalDataSource.createExactInputProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal =
        createProposalInternal {
            proposalDataSource.createExactOutputProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createZip321Proposal(zip321Uri: String, allowMultiPayment: Boolean): Zip321TransactionProposal =
        createProposalInternal {
            proposalDataSource.createZip321Proposal(
                account = accountDataSource.getSelectedAccount(),
                zip321Uri = zip321Uri,
                allowMultiPayment = allowMultiPayment,
            )
        }

    override suspend fun createShieldProposal() {
        createProposalInternal {
            proposalDataSource.createShieldProposal(
                account = accountDataSource.getSelectedAccount(),
            )
        }
    }

    override suspend fun createPCZTFromProposal() {
        val boundProposal = getTransactionProposal()
        // F2: refuse a multi-transaction proposal before anything is built from it (see TexUnsupportedOnKSException).
        val transactionCount = boundProposal.proposal.transactionCount()
        if (transactionCount > 1) throw TexUnsupportedOnKSException(transactionCount)
        val result =
            proposalDataSource.createPcztFromProposal(
                account = accountDataSource.getSelectedAccount(),
                proposal = boundProposal.proposal
            )
        proposalPczt = result
        pcztBoundProposal = boundProposal
        // Rebinding invalidates any signatures already scanned for the PREVIOUS proposal.
        // Without this, the binding check alone is not enough (F-1 of the review of
        // ZCHAT-XF-2026-07-18-01): user scans A's signed QR -> a concurrent create rebinds to B ->
        // submit(B) passes the binding check (bound === B) and the seam receives B's proofs paired
        // with A's STALE hardware signatures -- the same chimera one step later. Signatures are
        // only ever valid for the PCZT they were produced from, so drop them on every rebind and
        // let submit fail closed on "PCZT with signatures is null" until the user signs THIS one.
        pcztWithSignatures = null
        addProofsToPczt(result)
    }

    private fun addProofsToPczt(proposalPczt: Pczt) {
        pcztWithProofsJob?.cancel()
        pcztWithProofsJob =
            scope.launch {
                pcztWithProofs.update { PcztState(isLoading = true, pczt = null) }
                try {
                    val result = proposalDataSource.addProofsToPczt(proposalPczt.clonePczt())
                    pcztWithProofs.update { PcztState(isLoading = false, pczt = result) }
                } catch (_: PcztException.AddProofsToPcztException) {
                    pcztWithProofs.update { PcztState(isLoading = false, pczt = null) }
                }
            }
    }

    @Suppress("UseCheckOrError")
    override suspend fun createPCZTEncoder(): UREncoder =
        withContext(Dispatchers.IO) {
            val pczt = proposalPczt ?: throw IllegalStateException("Proposal not created")
            val redactedPczt = proposalDataSource.redactPcztForSigner(pczt.clonePczt())
            keystoneZcashSDK.generatePczt(pczt = redactedPczt.toByteArray())
        }

    override suspend fun parsePCZT(ur: UR) =
        withContext(Dispatchers.IO) {
            val parsed =
                try {
                    keystoneZcashSDK.parsePczt(ur)
                } catch (_: Exception) {
                    throw ParsePCZTException()
                }
            acceptSignedPczt(parsed)
        }

    /**
     * K0-4 (2.13.0): stores a signed PCZT for submission only when the signing firmware is 3.0.2 or newer
     * (KeystoneFirmwareGate). A refused signature is never stored, so [submit] finds none and nothing can be sent with
     * it. Public for the JVM tests: the Keystone SDK's UR decoding can't run there.
     */
    @Throws(KeystoneFirmwareBelowMinimumException::class)
    fun acceptSignedPczt(signedPczt: ByteArray) {
        val version =
            try {
                KeystoneFirmwareGate.requireSupported(signedPczt)
            } catch (e: KeystoneFirmwareBelowMinimumException) {
                // No earlier signature may stand in for the refused one.
                pcztWithSignatures = null
                throw e
            }
        Twig.info { "Keystone signature from firmware $version accepted (minimum ${KeystoneFirmwareVersion.MINIMUM_SUPPORTED})" }
        pcztWithSignatures = Pczt(signedPczt)
    }

    // ZC-101: the caller passes the proposal its send created; submit must not re-read the shared
    // `transactionProposal` cell (observation-only), which a concurrent creation may have
    // overwritten. The settled bytes are the PCZT pair fields — still process-wide singletons —
    // so the same guarantee is enforced one layer down (ZCHAT-XF-2026-07-18-01 F2/F6/F5) by the
    // fail-closed BINDING check: the PCZT pair carries the proposal it was built for
    // (`pcztBoundProposal`), and submit refuses to broadcast — before touching the seam, and again
    // after awaiting proofs — unless that binding matches the caller's proposal exactly. On
    // mismatch or missing binding nothing is broadcast and no success is reported.
    @Suppress("UseCheckOrError", "ThrowingExceptionsWithoutMessageOrCause", "TooGenericExceptionCaught")
    override suspend fun submit(proposal: TransactionProposal): SubmitResult =
        scope
            .async {
                requirePcztBoundTo(proposal)

                val pcztWithSignatures = pcztWithSignatures

                if (pcztWithSignatures == null) {
                    submitState.update {
                        SubmitProposalState.Result(
                            SubmitResult.Failure(
                                txIds = emptyList(),
                                code = 0,
                                description = "PCZT with signatures is null"
                            )
                        )
                    }
                    throw IllegalStateException("PCZT with signatures is null")
                } else {
                    submitState.update { SubmitProposalState.Submitting }
                    val pcztWithProofs = pcztWithProofs.filter { !it.isLoading }.first().pczt
                    if (pcztWithProofs == null) {
                        submitState.update {
                            SubmitProposalState.Result(
                                SubmitResult.Failure(
                                    txIds = emptyList(),
                                    code = 0,
                                    description = "PCZT with proofs is null"
                                )
                            )
                        }
                        throw IllegalStateException("PCZT with proofs is null")
                    } else {
                        // Re-verify AFTER awaiting proofs: a concurrent create during the await
                        // rebinds the singletons, and the awaited proofs would then be foreign.
                        requirePcztBoundTo(proposal)
                        try {
                            val result =
                                proposalDataSource.submitTransaction(
                                    pcztWithProofs = pcztWithProofs,
                                    pcztWithSignatures = pcztWithSignatures
                                )
                            submitState.update { SubmitProposalState.Result(result) }
                            result
                        } catch (e: NothingBroadcastException) {
                            // NOT ambiguous: the SDK never handed an output to the network (see the
                            // Zashi lane). Nothing was signed onto the wire, so this stays the plain
                            // failure it always was.
                            val result = SubmitResult.Failure(txIds = emptyList(), code = 0, description = e.message)
                            submitState.update { SubmitProposalState.Result(result) }
                            result
                        } catch (e: Exception) {
                            // MONEY-CRITICAL: same ambiguity as the Zashi lane — what reaches here
                            // happens at or after the point where the SDK puts an output on the wire
                            // (the provably-pre-broadcast steps throw NothingBroadcastException,
                            // caught above) and the balance refresh runs after it, so this catch
                            // cannot mean "nothing was sent". Publishing a Failure showed "Send
                            // Failed / Try it again, please" over a payment the hardware wallet had
                            // already signed and the SDK had already broadcast.
                            val result = SubmitResult.SentUnconfirmed(txIds = emptyList(), description = e.message)
                            submitState.update { SubmitProposalState.Result(result) }
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            result
                        }
                    }
                }
            }.await()

    /**
     * Fail-closed PCZT/proposal binding: throws (after publishing the failure to [submitState])
     * unless the live PCZT pair was built for exactly [proposal]. Covers both a missing binding
     * (no [createPCZTFromProposal] / cleared session) and a foreign one (a concurrent Keystone
     * create overwrote the singletons). Nothing may be broadcast when this fails.
     */
    @Suppress("UseCheckOrError")
    private fun requirePcztBoundTo(proposal: TransactionProposal) {
        if (pcztBoundProposal !== proposal) {
            val message = "PCZT is not bound to the submitted proposal"
            submitState.update {
                SubmitProposalState.Result(
                    SubmitResult.Failure(
                        txIds = emptyList(),
                        code = 0,
                        description = message
                    )
                )
            }
            throw IllegalStateException(message)
        }
    }

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun getProposalPCZT(): Pczt? = proposalPczt

    override fun clear() {
        pcztWithProofsJob?.cancel()
        pcztWithProofsJob = null
        pcztWithProofs.update { PcztState(isLoading = false, pczt = null) }

        transactionProposal.update { null }
        submitState.update { null }
        proposalPczt = null
        pcztWithSignatures = null
        pcztBoundProposal = null
    }

    private inline fun <T : TransactionProposal> createProposalInternal(block: () -> T): T {
        val proposal =
            try {
                block()
            } catch (e: TransactionProposalNotCreatedException) {
                Twig.error(e) { "Unable to create proposal" }
                transactionProposal.update { null }
                throw e
            } catch (e: InsufficientFundsException) {
                Twig.error(e) { "Insufficient funds" }
                transactionProposal.update { null }
                throw e
            }
        transactionProposal.update { proposal }
        return proposal
    }
}

private data class PcztState(
    val isLoading: Boolean,
    val pczt: Pczt?
)
