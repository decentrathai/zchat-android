package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ProposingSynchronizerProvider
import co.electriccoin.zcash.ui.common.model.ProposalChange
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.readProposalInputs
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.built
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.nameOf
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.outpoint
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanApi.short
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.firstMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.memoCount
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.2, §2.4 steps 1-3; t5b SPECS §2 C-M2-3..C-M2-5, C-M2-8): the real
 * first-message plan check, below the ViewModel.
 *
 * - The proposal reader gives each input's output index (proposal.proto ReceivedOutput field 3), so two notes of one
 *   transaction are two notes.
 * - ProposalDataSource.probeZip321Plan prices a ZIP 321 request like probeZip321Fee and also returns the inputs it
 *   spends and its Ironwood change; nothing is stored or submitted.
 * - ProbeFirstMessagePlanUseCase (the check Koin gives ChatViewModel) probes the KEX (one memo, 1,000 zat and the
 *   platform fee) and then the message (its memo count and amount), both as placeholders (the real message is never
 *   built or encrypted for a probe), and decides with FirstMessagePlanFunding. A leg Short only because Ironwood is
 *   still confirming (ShortfallReason.classify says StillConfirming) is WaitForFunds; a probe that throws is Unchecked;
 *   one of our own addresses is never probed.
 *
 * At HEAD none of this exists (reached by name). All addresses and txids are synthetic.
 */
class FirstMessagePlanProbeTest {
    private companion object {
        const val PEER = "usyntheticfirstmsgplanpeer01"
        const val SECRET = "synthetic words that must never be built for a probe"
    }

    @Test
    fun `the proposal reader gives each input's output index`() {
        val bytes =
            SweepProposalBytes.bytes(
                inputs =
                    listOf(
                        SweepProposalBytes.ironwood(20_000),
                        SweepProposalBytes.ironwood(5_000),
                        SweepProposalBytes.ironwood(1_000),
                    ),
                fee = 15_000,
            )
        val inputs = assertNotNull(readProposalInputs(bytes)).inputs
        val indexes =
            inputs.map { input ->
                val getter =
                    input.javaClass.methods.firstOrNull { it.name == "getIndex" && it.parameterCount == 0 }
                        ?: throw AssertionError("ProposalInputOutpoint has no output index (C-M2-5)")
                getter.invoke(input)
            }
        assertEquals(listOf<Any?>(0, 1, 2), indexes)
    }

    @Test
    fun `probeZip321Plan returns the fee, the inputs with their index and the change, and stores nothing`() =
        runBlocking {
            val proposal =
                SweepProposalBytes.proposal(
                    inputs = listOf(SweepProposalBytes.ironwood(40_000)),
                    fee = 15_000,
                    paymentPools = listOf(SweepProposalBytes.IRONWOOD, SweepProposalBytes.IRONWOOD),
                    change = listOf(ProposalChange(23_000, SweepProposalBytes.IRONWOOD)),
                )
            val sdk = ProposingSynchronizerProvider(proposal)
            val source = ProposalDataSourceImpl(sdk)

            val probe = FirstMessagePlanApi.callSuspend(source, "probeZip321Plan", syntheticZashiAccount(), "zcash:$PEER?amount=0.00001")

            assertEquals("Built", nameOf(probe))
            assertEquals(Zatoshi(15_000), get(probe!!, "getFee"))
            assertEquals(1, get(probe, "getTransactionCount"))
            assertEquals(listOf(23_000L), get(probe, "getChange"))
            val inputs = get(probe, "getInputs") as List<*>
            assertEquals(1, inputs.size)
            assertEquals(0, get(inputs.single()!!, "getIndex"))
            assertEquals(40_000L, get(inputs.single()!!, "getValue"))
            assertEquals(listOf("proposeFulfillingPaymentUri"), sdk.calls, "the probe asked the SDK for anything else")
        }

    @Test
    fun `the check probes the KEX and then the message with placeholder memos, and nothing else`() =
        runBlocking {
            val seam = fundableSeam()
            val plan = check(seam, memos = 3)

            assertEquals("Fundable", nameOf(plan))
            assertEquals(2, seam.uris.size, "two legs are probed")
            val (kex, message) = seam.uris
            assertEquals(1, memoCount(kex), "the KEX leg is one memo")
            assertEquals(3, memoCount(message), "the message leg carries every memo of the message")
            for (uri in seam.uris) {
                assertTrue(uri.startsWith("zcash:$PEER?"), "a leg was not probed to the contact")
                assertFalse(SECRET in firstMemo(uri), "the real message was built for a probe")
                assertTrue(uri.contains("&address."), "a leg was probed without the platform-fee output")
            }
            assertTrue("amount=0.00001&" in kex, "the KEX leg is 1,000 zatoshi")
            assertEquals(emptyList(), seam.unexpected, "the check asked the data source for something else")
        }

    @Test
    fun `the Seeker's notes are refused by the real check`() =
        runBlocking {
            val a = outpoint("aa", 0, 14_376)
            val seam =
                PlanSeam(
                    built(listOf(a, outpoint("cc", 0, 5_000)), listOf(2_376)),
                    built(listOf(a, outpoint("bb", 0, 10_000)), listOf(7_376)),
                )
            assertEquals("NotFundable", nameOf(check(seam, memos = 1)))
        }

    @Test
    fun `a leg short only because Ironwood is still confirming is WaitForFunds`() =
        runBlocking {
            val seam = PlanSeam(short(17_000), short(17_000))
            assertEquals("WaitForFunds", nameOf(check(seam, memos = 1, account = confirming(200_000))))
        }

    /**
     * ZC-1948 (Fable review of 4efd9a54f, 16 Nov): each leg alone is covered by what is confirming (17,000 + 8,000), but
     * not both legs together (34,000): the plan must not wait for funds that can never pay it, as the gate then promises.
     */
    @Test
    fun `two short legs that confirming Ironwood covers only one at a time are not WaitForFunds`() =
        runBlocking {
            val seam = PlanSeam(short(17_000), short(17_000))
            assertFalse(nameOf(check(seam, memos = 1, account = confirming(25_000))) == "WaitForFunds")
        }

    /**
     * ZC-1954 (Fable re-review of f58e91ef2, 16 Nov): the KEX leg builds from what is spendable, the message leg is short;
     * the confirming Ironwood covers the message alone but not both legs together, so waiting can never pay the plan.
     */
    @Test
    fun `a built KEX leg and a short message leg wait only when confirming Ironwood covers both`() =
        runBlocking {
            val seam = PlanSeam(built(listOf(outpoint("aa", 0, 20_000)), listOf(3_000)), short(17_000))
            assertFalse(nameOf(check(seam, memos = 1, account = confirming(25_000))) == "WaitForFunds")
        }

    @Test
    fun `a leg short with nothing confirming is NotFundable`() =
        runBlocking {
            val seam = PlanSeam(built(listOf(outpoint("aa", 0, 20_000)), listOf(3_000)), short(17_000))
            assertEquals("NotFundable", nameOf(check(seam, memos = 1)))
        }

    @Test
    fun `a probe that throws is Unchecked (fail closed)`() =
        runBlocking {
            val seam = PlanSeam(throwing = IllegalStateException("synthetic proposal failure"))
            assertEquals("Unchecked", nameOf(check(seam, memos = 1)))
        }

    @Test
    fun `one of our own addresses is never probed`() =
        runBlocking {
            val seam = fundableSeam()
            val plan = check(seam, memos = 1, ownAddress = true)

            assertFalse(nameOf(plan) == "Fundable", "a first message to our own address was found fundable")
            assertEquals(emptyList(), seam.uris, "our own address was probed")
        }

    @Test
    fun `the data source's default probe throws, so a source that cannot probe fails closed`() =
        runBlocking {
            // A data source written before M2 implements no plan probe; it inherits the interface's default.
            val bare = Zc101RecordingProposalDataSource()
            val thrown =
                runCatching {
                    FirstMessagePlanApi.callSuspend(bare, "probeZip321Plan", syntheticZashiAccount(), "zcash:$PEER?amount=0.00001")
                }.exceptionOrNull()
            assertNotNull(thrown, "a data source with no probe answered")
            assertFalse(thrown is AssertionError, "ProposalDataSource.probeZip321Plan does not exist: ${thrown.message}")
        }

    // --------------------------------------------------------------- harness

    /** Two legs on different notes: fundable. */
    private fun fundableSeam() =
        PlanSeam(
            built(listOf(outpoint("aa", 0, 20_000)), listOf(3_000)),
            built(listOf(outpoint("bb", 0, 30_000)), listOf(9_000)),
        )

    private fun get(
        target: Any,
        getter: String,
    ): Any? = target.javaClass.getMethod(getter).invoke(target)

    private suspend fun confirming(pending: Long): ZashiAccount {
        val base = syntheticZashiAccount()
        return base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(0), Zatoshi(pending), Zatoshi(0))))
    }

    /** ProbeFirstMessagePlanUseCase over the real use case and repository, with [seam] as the data source. */
    private suspend fun check(
        seam: PlanSeam,
        memos: Int,
        account: ZashiAccount? = null,
        ownAddress: Boolean = false,
    ): Any? {
        val accounts = Zc101FakeAccountDataSource(account ?: syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam.proxy,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                isOwnAddress = { ownAddress && it == PEER },
            )
        val check =
            FirstMessagePlanApi
                .cls(FirstMessagePlanApi.USE_CASE)
                .constructors
                .single()
                .newInstance(useCase)
        return FirstMessagePlanApi.callSuspend(check, "check", PEER, memos, Zatoshi(1_000))
    }

    /** A data source that answers only probeZip321Plan: [answers] in turn (the KEX, then the message). */
    private class PlanSeam(
        private vararg val answers: Any,
        private val throwing: Throwable? = null,
    ) {
        val uris: MutableList<String> = CopyOnWriteArrayList()
        val unexpected: MutableList<String> = CopyOnWriteArrayList()

        val proxy: ProposalDataSource =
            Proxy.newProxyInstance(
                ProposalDataSource::class.java.classLoader,
                arrayOf(ProposalDataSource::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "probeZip321Plan" -> {
                        uris += args!![1] as String
                        throwing?.let { throw it }
                        answers[(uris.size - 1).coerceAtMost(answers.size - 1)]
                    }
                    "toString" -> "PlanSeam"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> {
                        unexpected += method.name
                        throw UnsupportedOperationException("unexpected ProposalDataSource call: ${method.name}")
                    }
                }
            } as ProposalDataSource
    }
}
