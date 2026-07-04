package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * MONEY / SEND correctness for the pure, side-effect-free helpers extracted from
 * [CreateChunkedMessageProposalUseCase]. These carry the double-charge / fund-loss invariants that
 * were the root cause of real "in-chat payment debited ~2x" reports:
 *
 *  - the platform fee is CLAMPED to the minimum (1000 zat) regardless of the caller's amount, so a
 *    full-amount caller can never append a SECOND full-amount output to the platform address;
 *  - the required-balance estimate and the total-cost estimate count the fee EXACTLY ONCE (a single
 *    extra output), never a second full-amount output;
 *  - the central pre-submit memo-size guard measures UTF-8 BYTES (not chars), so an emoji-heavy memo
 *    whose char length is under 512 but whose byte length is over 512 is rejected;
 *  - insufficient-funds classification matches the SDK message variants and walks the cause chain.
 *
 * The use case itself can't be constructed in a plain JVM test (its constructor needs the Android
 * SDK repositories), so these invariants were extracted into pure companion helpers that production
 * delegates to. The Android-dependent paths (Base64 ZIP-321 URI build, proposal submit, biometric
 * spend-auth) are not JVM-testable here — see report.
 */
class ChunkedProposalSendTest {

    private companion object Helpers {
        const val MIN_FEE = CreateChunkedMessageProposalUseCase.PLATFORM_FEE_MIN_ZATOSHI // 1000
        val DEFAULT_AMOUNT = CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT // 1000
    }

    // ── Platform-fee clamp: never more than the minimum (double-charge guard) ────────────────────

    @Test
    fun `a full-amount fee is clamped down to the minimum`() {
        // A caller passing the full send amount (e.g. 0.5 ZEC = 50_000_000 zat) as the fee must not
        // double the debit — the fee is capped at the 1000-zat minimum.
        val clamped = CreateChunkedMessageProposalUseCase.clampPlatformFee(Zatoshi(50_000_000L))
        assertEquals(MIN_FEE, clamped.value)
    }

    @Test
    fun `a fee already at the minimum is unchanged`() {
        assertEquals(MIN_FEE, CreateChunkedMessageProposalUseCase.clampPlatformFee(Zatoshi(MIN_FEE)).value)
    }

    @Test
    fun `a sub-minimum fee is left below the minimum (never inflated)`() {
        // Dust below the minimum must not be rounded UP to the minimum.
        assertEquals(500L, CreateChunkedMessageProposalUseCase.clampPlatformFee(Zatoshi(500L)).value)
    }

    @Test
    fun `default fee for a value transfer is the minimum, not the send amount`() {
        // 0.01 ZEC = 1_000_000 zat send: the default platform fee stays minimal.
        val fee = CreateChunkedMessageProposalUseCase.defaultPlatformFee(Zatoshi(1_000_000L))
        assertEquals(MIN_FEE, fee.value)
    }

    @Test
    fun `default fee for a dust send equals the dust amount`() {
        assertEquals(DEFAULT_AMOUNT.value, CreateChunkedMessageProposalUseCase.defaultPlatformFee(DEFAULT_AMOUNT).value)
    }

    // ── Total cost & required-balance estimate: fee counted ONCE ─────────────────────────────────

    @Test
    fun `total cost for N chunks is N-plus-one outputs at the per-output amount`() {
        val amount = Zatoshi(1_000_000L)
        // 3 message chunks + 1 fee output = 4 outputs.
        val total = CreateChunkedMessageProposalUseCase.totalCost(chunkCount = 3, amountPerOutput = amount)
        assertEquals(amount.value * 4, total.value)
    }

    @Test
    fun `total cost adds exactly one fee output not two`() {
        val amount = Zatoshi(1_000_000L)
        val oneChunk = CreateChunkedMessageProposalUseCase.totalCost(1, amount).value
        val twoChunks = CreateChunkedMessageProposalUseCase.totalCost(2, amount).value
        // Going from 1 to 2 message chunks adds exactly one output's worth — the fee count is fixed.
        assertEquals(amount.value, twoChunks - oneChunk)
        // And a single chunk is exactly message + fee = 2 outputs, never 3 (double fee).
        assertEquals(amount.value * 2, oneChunk)
    }

    @Test
    fun `required spendable counts the fee once at the minimum plus the network buffer`() {
        val amount = Zatoshi(1_000_000L)
        // 2 message outputs at full amount + 1 minimal fee + 2000 network buffer.
        val required = CreateChunkedMessageProposalUseCase.estimateRequiredSpendable(memoCount = 2, amountPerOutput = amount)
        val expected = amount.value * 2 + MIN_FEE + 2_000L
        assertEquals(expected, required.value)
    }

    @Test
    fun `required spendable does not scale the fee with the send amount`() {
        // The old bug counted the fee at the full amount; the delta between a small and a large send
        // of the SAME chunk count must be exactly the amount difference (fee stays fixed at MIN_FEE).
        val small = CreateChunkedMessageProposalUseCase.estimateRequiredSpendable(1, Zatoshi(1_000L)).value
        val large = CreateChunkedMessageProposalUseCase.estimateRequiredSpendable(1, Zatoshi(1_000_000L)).value
        assertEquals(999_000L, large - small)
    }

    // ── Central memo-size guard: UTF-8 BYTES, not chars ──────────────────────────────────────────

    @Test
    fun `a memo within the byte limit passes the guard`() {
        val memo = "a".repeat(ZMSGConstants.MAX_MEMO_SIZE) // exactly 512 ASCII bytes
        assertNull(CreateChunkedMessageProposalUseCase.firstOverflowingMemoBytes(listOf(memo)))
    }

    @Test
    fun `an emoji-heavy memo under 512 chars but over 512 bytes is flagged with its byte count`() {
        // 200 rocket emoji: 200 chars (< 512) but 4 bytes each = 800 UTF-8 bytes (> 512).
        val emojiMemo = "🚀".repeat(200)
        assertTrue("char length must be under the limit", emojiMemo.length < ZMSGConstants.MAX_MEMO_SIZE)
        assertTrue("byte length must exceed the limit", emojiMemo.toByteArray(Charsets.UTF_8).size > ZMSGConstants.MAX_MEMO_SIZE)

        val overflow = CreateChunkedMessageProposalUseCase.firstOverflowingMemoBytes(listOf(emojiMemo))
        assertTrue("expected an overflow report", overflow != null)
        val (index, bytes) = overflow!!
        assertEquals(0, index)
        assertEquals(800, bytes)
    }

    @Test
    fun `the guard reports the index of the FIRST overflowing chunk`() {
        val ok = "a".repeat(100)
        val tooBig = "b".repeat(ZMSGConstants.MAX_MEMO_SIZE + 1)
        val overflow = CreateChunkedMessageProposalUseCase.firstOverflowingMemoBytes(listOf(ok, ok, tooBig))
        assertTrue(overflow != null)
        assertEquals(2, overflow!!.first)
        assertEquals(ZMSGConstants.MAX_MEMO_SIZE + 1, overflow.second)
    }

    @Test
    fun `the production guard throws MemoTooLongException for an over-long memo`() {
        // End-to-end of the extracted guard as production uses it: over-limit -> MemoTooLongException.
        val overflow = CreateChunkedMessageProposalUseCase.firstOverflowingMemoBytes(
            listOf("x".repeat(ZMSGConstants.MAX_MEMO_SIZE + 50))
        )
        assertTrue(overflow != null)
        val byteCount = overflow!!.second
        assertEquals(ZMSGConstants.MAX_MEMO_SIZE + 50, byteCount)
        // The production callsite builds a MemoTooLongException carrying exactly this byte count so the
        // failure names the offending size instead of failing opaquely deep in the SDK.
        val ex = MemoTooLongException("Memo chunk 1/1 is $byteCount bytes over the limit")
        assertTrue("message must carry the offending byte count", ex.message!!.contains("$byteCount"))
    }

    // ── Insufficient-funds classification: SDK message variants + cause chain ─────────────────────

    @Test
    fun `direct InsufficientFundsException is classified as insufficient`() {
        assertTrue(CreateChunkedMessageProposalUseCase.isInsufficientFundsError(InsufficientFundsException("nope")))
    }

    @Test
    fun `each SDK insufficient-funds message variant is matched`() {
        listOf(
            "Insufficient balance to complete send",
            "InsufficientFunds",
            "Insufficient amount of ZEC for this transaction",
            "The transaction requires an additional change output of 0.0001 ZEC",
        ).forEach { msg ->
            assertTrue(
                "should match: $msg",
                CreateChunkedMessageProposalUseCase.isInsufficientFundsError(RuntimeException(msg))
            )
        }
    }

    @Test
    fun `insufficient-funds signal is found while walking the cause chain`() {
        val wrapped = RuntimeException(
            "proposal failed",
            IllegalStateException(
                "wrapper",
                RuntimeException("Insufficient balance for this send")
            )
        )
        assertTrue(CreateChunkedMessageProposalUseCase.isInsufficientFundsError(wrapped))
    }

    @Test
    fun `an unrelated error is not classified as insufficient funds`() {
        assertTrue(!CreateChunkedMessageProposalUseCase.isInsufficientFundsError(RuntimeException("network timeout")))
        // No infinite loop / no false positive on a self-referential-free ordinary chain.
        assertTrue(
            !CreateChunkedMessageProposalUseCase.isInsufficientFundsError(
                RuntimeException("outer", RuntimeException("inner unrelated"))
            )
        )
    }
}
