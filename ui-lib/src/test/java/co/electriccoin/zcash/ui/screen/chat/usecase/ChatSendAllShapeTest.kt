package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.ProposalChange
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsContext
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat Send All shape (C-1, C-2, C-8) over [SendMaxSearch], and the send-time guard (C-9).
 *
 * The owner's phone held one 443,002-zatoshi Ironwood note and 139,000 of sealed Orchard. A New-Chat
 * Send All put 566,002 on output 0 (Orchard counted in, fee = 5,000 x (chunks + 2) whatever the notes).
 * Only Ironwood can pay a peer, and the fee grows with the notes spent, so the sweep is asked of the SDK:
 * output 0 + the dust continuations + the platform fee + the SDK's fee + what is kept == Ironwood.
 *
 * [zip317ChatPrice] stands in for the SDK's selector. All addresses are synthetic.
 */
class ChatSendAllShapeTest {
    @Test
    fun owner_state_one_chunk_sweeps_ironwood_minus_platform_and_real_fee() =
        runTest {
            val result = sweep(notes = listOf(IRONWOOD), chunks = 1)

            assertEquals(SendMaxResult.Max(Zatoshi(427_002), Zatoshi(15_000)), result)
            assertSweeps(result, chunks = 1, keeps = 0, total = IRONWOOD)
        }

    /** Whether the SDK counts a zero-value change output or not, the upward scan lands on the sweep. */
    @Test
    fun a_zero_change_output_that_is_not_charged_is_found_too() =
        runTest {
            val result = sweep(notes = listOf(IRONWOOD), chunks = 1, zeroChangeCounts = false)

            assertEquals(SendMaxResult.Max(Zatoshi(432_002), Zatoshi(10_000)), result)
            assertSweeps(result, chunks = 1, keeps = 0, total = IRONWOOD)
        }

    /** C-2: four notes cost four actions. The old Zip317(chunks + 2) reserve asked for 427,002 and failed. */
    @Test
    fun four_notes_pay_for_four_actions() =
        runTest {
            val result = sweep(notes = FOUR_NOTES, chunks = 1)

            assertEquals(SendMaxResult.Max(Zatoshi(422_002), Zatoshi(20_000)), result)
            assertSweeps(result, chunks = 1, keeps = 0, total = IRONWOOD)
        }

    @Test
    fun three_chunks_pay_two_dust_continuations_and_the_platform_fee() =
        runTest {
            val result = sweep(notes = listOf(IRONWOOD), chunks = 3)

            val max = result as SendMaxResult.Max
            assertEquals(IRONWOOD, max.amount.value + 2_000 + 1_000 + max.fee.value)
            assertEquals(25_000L, max.fee.value)
        }

    /** C-8: a new contact keeps the handshake quote back as change — exactly that, never dust. */
    @Test
    fun a_kept_handshake_reserve_is_left_behind_exactly() =
        runTest {
            val keeps = handshakeSpendQuote(peers = 1).value
            val result = sweep(notes = listOf(IRONWOOD), chunks = 1, keeps = keeps)

            assertEquals(SendMaxResult.Max(Zatoshi(410_002), Zatoshi(15_000)), result)
            assertSweeps(result, chunks = 1, keeps = keeps, total = IRONWOOD)
        }

    @Test
    fun a_kept_reserve_over_many_notes_still_leaves_exactly_the_reserve() =
        runTest {
            val keeps = handshakeSpendQuote(peers = 1).value
            val result = sweep(notes = FOUR_NOTES, chunks = 1, keeps = keeps)

            assertEquals(SendMaxResult.Max(Zatoshi(405_002), Zatoshi(20_000)), result)
            assertSweeps(result, chunks = 1, keeps = keeps, total = IRONWOOD)
        }

    @Test
    fun too_little_for_the_fees_and_the_reserve_is_below_fee_and_never_probes() =
        runTest {
            var probes = 0
            val result =
                SendMaxSearch.search(
                    recipient = SendMaxRecipient.SHIELDED,
                    readAccount = { account(ironwood = 20_000) },
                    probe = { _, _ ->
                        probes++
                        error("must not probe")
                    },
                    shape = CreateChunkedMessageProposalUseCase.sendAllShape(1, Zatoshi(17_000)),
                )

            assertEquals(
                SendMaxResult.Unavailable(SendMaxUnavailable.BelowFee(have = Zatoshi(20_000), fee = Zatoshi(33_000))),
                result,
            )
            assertEquals(0, probes)
        }

    @Test
    fun orchard_only_says_sealed_and_confirming_says_confirming() =
        runTest {
            val shape = CreateChunkedMessageProposalUseCase.sendAllShape(1, Zatoshi(0))
            assertEquals(
                SendMaxUnavailable.OrchardSealed(Zatoshi(139_000)),
                SendMaxSearch.precheck(account(ironwood = 0, orchard = 139_000), SendMaxRecipient.SHIELDED, shape),
            )
            assertEquals(
                SendMaxUnavailable.StillConfirming(Zatoshi(443_002)),
                SendMaxSearch.precheck(
                    account(ironwood = 0, ironwoodPending = 443_002, orchard = 139_000),
                    SendMaxRecipient.SHIELDED,
                    shape,
                ),
            )
        }

    /**
     * The use case prices with ZIP 321 probes shaped exactly like the send (output 0, one output per extra
     * chunk, the platform fee), and never through the shared proposal cell or a submit.
     */
    @Test
    fun price_send_all_probes_the_real_shape_and_submits_nothing() =
        runTest {
            val dataSource = ChatZip317ProposalDataSource(ironwoodNotes = listOf(IRONWOOD))
            val useCase = useCase(dataSource)

            val result = useCase.priceSendAll(PEER, chunkCount = 2, keeps = Zatoshi(0))

            val max = result as SendMaxResult.Max
            assertEquals(IRONWOOD, max.amount.value + 1_000 + 1_000 + max.fee.value)
            assertTrue(dataSource.probedUris.isNotEmpty())
            dataSource.probedUris.forEach { uri ->
                assertEquals(3, zip321Amounts(uri).size, "two memo outputs + the platform fee: $uri")
                assertTrue(uri.startsWith("zcash:$PEER?"), "output 0 goes to the peer: $uri")
            }
            assertTrue(dataSource.proposedUris.isEmpty(), "a probe is never a real proposal")
            assertTrue(dataSource.recording.submittedProposals.isEmpty(), "a probe is never submitted")
        }

    @Test
    fun an_unexpected_probe_failure_is_could_not_price() =
        runTest {
            // Zc101RecordingProposalDataSource's probe throws: the user is told to pick an amount.
            val useCase = useCase(dataSource = null)

            assertEquals(
                SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice),
                useCase.priceSendAll(PEER, chunkCount = 1, keeps = Zatoshi(0)),
            )
        }

    // ------------------------------------------------------------------ C-9 send-time guard

    @Test
    fun the_quoted_proposal_matches() {
        assertTrue(matches())
    }

    @Test
    fun any_moved_figure_is_a_mismatch() {
        assertFalse(matches(destination = "u1synthetic-other"), "another recipient")
        assertFalse(matches(amount = QUOTE.amount + Zatoshi(1)), "a larger output 0")
        assertFalse(matches(amount = Zatoshi(QUOTE.amount.value - 1)), "a smaller output 0")
        assertFalse(matches(fee = Zatoshi(20_000)), "a higher fee")
        assertFalse(matches(fee = Zatoshi(10_000)), "a lower fee")
        assertFalse(matches(memoCount = 2), "another memo count")
        assertFalse(matches(transactionCount = 2), "two transactions")
        assertFalse(matches(usesOrchardInputs = true), "an Orchard input")
    }

    // ------------------------------------------------------------------ C-4 / C-6 notices

    /**
     * The chat case the 2,000 buffer mislabelled: 10,000 of Ironwood and 139,000 of sealed Orchard,
     * nothing confirming. Waiting cannot help, so the notice must not carry "confirm on-chain" (the
     * handshake and invite retry loops would wait for blocks on it).
     */
    @Test
    fun a_shortfall_that_waiting_cannot_cover_is_not_confirm_on_chain() =
        runTest {
            val reason =
                ShortfallReason.classify(
                    account(ironwood = 10_000, orchard = 139_000),
                    need = CreateChunkedMessageProposalUseCase.minimumRequiredSpendable(1, Zatoshi(1_000)),
                )

            val notice = CreateChunkedMessageProposalUseCase.shortfallNoticeFor(reason, isValueTransfer = false)

            assertFalse(notice.contains("confirm on-chain", ignoreCase = true), notice)
        }

    @Test
    fun only_a_real_wait_is_confirm_on_chain() =
        runTest {
            val reason =
                ShortfallReason.classify(
                    account(ironwood = 0, ironwoodPending = 443_002),
                    need = CreateChunkedMessageProposalUseCase.minimumRequiredSpendable(1, Zatoshi(1_000)),
                )

            assertTrue(reason is ShortfallReason.StillConfirming)
            assertTrue(
                CreateChunkedMessageProposalUseCase
                    .shortfallNoticeFor(reason, isValueTransfer = true)
                    .contains("confirm on-chain"),
            )
        }

    /** C-6: a payment shortfall is a PAYMENT notice and never suggests Tunnel/Open. */
    @Test
    fun a_payment_shortfall_is_payment_copy_without_tunnel_advice() {
        val notice =
            CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                ShortfallReason.NotEnough(have = Zatoshi(10_000), need = Zatoshi(117_000), pending = Zatoshi(0)),
                isValueTransfer = true,
            )

        assertFalse(notice.contains("Tunnel", ignoreCase = true), notice)
        assertFalse(notice.contains("confirm on-chain", ignoreCase = true), notice)
        assertEquals(
            InsufficientFundsContext.PAYMENT,
            CreateChunkedMessageProposalUseCase.insufficientFundsContextFor(Zatoshi(100_000)),
        )
        assertEquals(
            InsufficientFundsContext.MESSAGE,
            CreateChunkedMessageProposalUseCase.insufficientFundsContextFor(Zatoshi(1_000)),
        )
    }

    /** The fallback "need" is the real two-output fee floor, not the old 2,000 buffer. */
    @Test
    fun the_fallback_need_carries_a_real_fee() {
        assertEquals(
            100_000L + 1_000 + 15_000,
            CreateChunkedMessageProposalUseCase.minimumRequiredSpendable(1, Zatoshi(100_000)).value,
        )
        assertEquals(
            100_000L + 2 * 1_000 + 1_000 + 25_000,
            CreateChunkedMessageProposalUseCase.minimumRequiredSpendable(3, Zatoshi(100_000)).value,
        )
    }

    // --------------------------------------------------------------------------------- harness

    private suspend fun sweep(
        notes: List<Long>,
        chunks: Int,
        keeps: Long = 0,
        zeroChangeCounts: Boolean = true,
    ): SendMaxResult =
        SendMaxSearch.search(
            recipient = SendMaxRecipient.SHIELDED,
            readAccount = { account(ironwood = notes.sum(), orchard = 139_000) },
            probe = { _, amount ->
                val outputs = listOf(amount.value) + List(chunks - 1) { 1_000L } + 1_000L
                zip317ChatPrice(notes, outputs, zeroChangeCounts)
            },
            shape = CreateChunkedMessageProposalUseCase.sendAllShape(chunks, Zatoshi(keeps)),
        )

    /** Everything that leaves the wallet, plus what is kept, is exactly [total]. */
    private fun assertSweeps(
        result: SendMaxResult,
        chunks: Int,
        keeps: Long,
        total: Long,
    ) {
        val max = result as SendMaxResult.Max
        assertEquals(total, max.amount.value + 1_000L * chunks + max.fee.value + keeps)
    }

    private suspend fun useCase(dataSource: ChatZip317ProposalDataSource?): CreateChunkedMessageProposalUseCase {
        val accountDataSource = Zc101FakeAccountDataSource(account(ironwood = IRONWOOD, orchard = 139_000))
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accountDataSource,
                    proposalDataSource = dataSource ?: Zc101RecordingProposalDataSource(),
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ),
            accountDataSource = accountDataSource,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = Zc101SuspendingBiometricRepository(),
        )
    }

    @Suppress("LongParameterList")
    private fun matches(
        destination: String = QUOTE.recipient,
        amount: Zatoshi = QUOTE.amount,
        fee: Zatoshi = QUOTE.fee,
        transactionCount: Int = 1,
        usesOrchardInputs: Boolean = false,
        memoCount: Int = QUOTE.chunkCount,
        // F9: the change the proposal reports; a Send All that keeps nothing needs it readable and without value.
        change: List<ProposalChange>? = emptyList(),
    ) = CreateChunkedMessageProposalUseCase.proposalMatchesSendAll(
        quote = QUOTE,
        destination = destination,
        amount = amount,
        fee = fee,
        transactionCount = transactionCount,
        usesOrchardInputs = usesOrchardInputs,
        memoCount = memoCount,
        change = change,
    )

    private companion object {
        const val IRONWOOD = 443_002L

        /** The same 443,002 in four notes. */
        val FOUR_NOTES = listOf(110_751L, 110_750L, 110_750L, 110_751L)

        /** Bare-address shaped (the URI builder refuses anything else). Synthetic, never a real address. */
        val PEER = "u1synthetic" + "peer".repeat(25)

        val QUOTE =
            ChatSendAllQuote(
                recipient = PEER,
                chunkCount = 1,
                keeps = Zatoshi(0),
                amount = Zatoshi(427_002),
                fee = Zatoshi(15_000),
            )

        private fun balance(
            available: Long,
            pending: Long = 0,
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        suspend fun account(
            ironwood: Long,
            orchard: Long = 0,
            ironwoodPending: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-self"),
                    balance(orchard),
                    balance(ironwood, pending = ironwoodPending),
                ),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
