package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SR-A (fix-all 2.13.0, train T5b; photos-privacy SPEC §1.3 / §1.4 step 3 with t5b SPECS §3 C-SRA-1): the send-time
 * guard of a quoted chat send. CreateChunkedMessageProposalUseCase.invoke(sendQuote = …) builds the proposal and, before
 * the spend auth, onSubmitStarting and the submit, refuses it (SendQuoteChangedException) unless it is exactly the
 * quoted transaction: recipient, memo count, amount per output, outputs + network fee = the quoted total, one
 * transaction, no Orchard input. An equal quote is submitted once. priceMessage asks the SDK's price of that shape with
 * placeholder memos and stores nothing. SendQuoteChangedException is neither a wait for funds nor a shortfall.
 *
 * Lands with the fix (it calls the new parameter); the same money guard is covered end to end by the RED commit's A-3
 * and A-10 (ShieldedAttachmentLinkGoesOnChainTest). All addresses are synthetic.
 */
class SendQuoteMustMatchProposalTest {
    private companion object {
        const val PEER = "usyntheticsendquotepeer00001"
        const val SELF = "usyntheticsendquoteself00001"
        const val FEE = 15_000L

        /** A value transfer, so the spend auth is asked (and must not be when the guard refuses). */
        val AMOUNT = Zatoshi(10_000L)
    }

    private class RecordingBiometrics : BiometricRepository {
        val asked = AtomicInteger(0)
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) {
            asked.incrementAndGet()
        }
    }

    private class Chokepoint(
        proposalFee: Long,
    ) {
        val seam = HandshakeSeam(PEER).apply { this.proposalFee = proposalFee }
        val biometrics = RecordingBiometrics()
        val submitStarts = AtomicInteger(0)

        suspend fun useCase(): CreateChunkedMessageProposalUseCase {
            val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
            return CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = biometrics,
            )
        }

        suspend fun send(quote: ChatSendQuote) {
            useCase().invoke(
                destinationAddress = PEER,
                senderAddress = SELF,
                message = "ZFILE|synthetic quoted link",
                isFirstMessage = false,
                amountPerOutput = AMOUNT,
                directSubmit = true,
                skipNavigation = true,
                rawMemo = true,
                onSubmitStarting = { submitStarts.incrementAndGet() },
                sendQuote = quote,
            )
        }
    }

    private fun quote(
        fee: Long = FEE,
        chunkCount: Int = 1,
        recipient: String = PEER,
        amount: Zatoshi = AMOUNT,
    ) = ChatSendQuote(
        recipient = recipient,
        chunkCount = chunkCount,
        amountPerOutput = amount,
        total = Zatoshi(CreateChunkedMessageProposalUseCase.totalCost(chunkCount, amount).value + fee),
        fee = Zatoshi(fee),
    )

    @Test
    fun `a proposal whose network fee differs from the quote is refused before the spend auth and the submit`() =
        runBlocking {
            val chokepoint = Chokepoint(proposalFee = 20_000L)

            assertFailsWith<SendQuoteChangedException> { chokepoint.send(quote()) }

            assertEquals(1, chokepoint.seam.proposalRequests.get(), "fixture sanity: the proposal was built")
            assertEquals(0, chokepoint.biometrics.asked.get(), "the spend auth was asked for a proposal that differs")
            assertEquals(0, chokepoint.submitStarts.get(), "the submit was marked as started")
            assertEquals(0, chokepoint.seam.submits.get(), "a proposal that differs was submitted")
        }

    @Test
    fun `a quote for another memo count, recipient or amount is refused before the spend auth`() =
        runBlocking {
            val wrongQuotes =
                listOf(
                    quote(chunkCount = 2),
                    quote(recipient = "usyntheticotherpeer000000001"),
                    quote(amount = Zatoshi(9_000L)),
                )
            for (wrong in wrongQuotes) {
                val chokepoint = Chokepoint(proposalFee = FEE)
                assertFailsWith<SendQuoteChangedException> { chokepoint.send(wrong) }
                assertEquals(0, chokepoint.biometrics.asked.get())
                assertEquals(0, chokepoint.seam.submits.get(), "a send that differs from $wrong was submitted")
            }
        }

    @Test
    fun `a proposal equal to the quote is submitted once`() =
        runBlocking {
            val chokepoint = Chokepoint(proposalFee = FEE)

            chokepoint.send(quote())

            assertEquals(1, chokepoint.biometrics.asked.get(), "the value transfer was not authorised once")
            assertEquals(1, chokepoint.submitStarts.get())
            assertEquals(1, chokepoint.seam.submits.get(), "the quoted send was not submitted once")
        }

    @Test
    fun `the guard refuses Orchard inputs and more than one transaction`() {
        val q = quote()

        fun matches(
            transactions: Int,
            orchard: Boolean,
        ) = CreateChunkedMessageProposalUseCase.proposalMatchesQuote(q, PEER, 1, AMOUNT, Zatoshi(FEE), transactions, orchard)
        assertTrue(matches(1, false))
        assertFalse(matches(2, false), "a two-transaction proposal passed the guard")
        assertFalse(matches(1, true), "a proposal with an Orchard input passed the guard")
        assertFalse(matches(0, false), "a proposal with no transaction passed the guard")
    }

    @Test
    fun `priceMessage asks the SDK once for the real shape and stores nothing`() =
        runBlocking {
            val chokepoint = Chokepoint(proposalFee = FEE)
            chokepoint.seam.feeProbe = { FeeProbe.Built(Zatoshi(FEE), usesOrchardInputs = false, transactionCount = 1) }

            val probe = chokepoint.useCase().priceMessage(PEER, 2, Zatoshi(1_000L))

            assertEquals(FeeProbe.Built(Zatoshi(FEE), usesOrchardInputs = false, transactionCount = 1), probe)
            assertEquals(1, chokepoint.seam.probes.get())
            assertEquals(0, chokepoint.seam.proposalRequests.get(), "pricing built a proposal")
            assertEquals(0, chokepoint.seam.submits.get(), "pricing submitted something")
        }

    @Test
    fun `a changed quote is neither a wait for funds nor a shortfall`() {
        val e = SendQuoteChangedException()
        assertFalse(isTransientWait(e), "a changed quote would be queued for the next block")
        assertFalse(CreateChunkedMessageProposalUseCase.isInsufficientFundsError(e), "a changed quote reads as a shortfall")
        assertFalse(e.message.orEmpty().contains("Insufficient balance"), "a changed quote reads as a shortfall in the send")
    }
}
