package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer
import co.electriccoin.zcash.ui.screen.chat.model.canAffordPayment
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.model.paymentOutlay
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1417 (QA2 lane k1; Fable note "quote 0.00017 against a need of 22,000"; register high-to-medium, money-path rule 1:
 * honest offers) — the dead zone between 17,001 and 21,999 zatoshi.
 *
 * The SDK refuses non-zero change below 5,000 zatoshi (Zip317.MARGINAL_FEE_ZATOSHI) and reports need = inputs + shortfall.
 * A wallet holding exactly 17,000 can send a message (no change), and one holding 22,000 or more can (change of at least
 * 5,000). One holding 17,001 to 21,999 cannot: 18,000 leaves 1,000 change, so the SDK asks for 22,000. The question
 * "Pay for this?" and the Payment dialog's Send were offered at 17,000 and failed after the tap with a text that still said
 * "at least 0.00017".
 *
 * Wanted:
 * 1. One rule at every pre-question check: affordable only when spendable == cost, or spendable >= cost +
 *    Zip317.MARGINAL_FEE_ZATOSHI. Checks pinned here: [canAffordPayment] (the Payment dialog's Send), [OpenChatTunnelOffer.canAccept]
 *    (the Tunnel offer's Accept and pay) and the handshake funds gate the "Pay for this?" question reads
 *    (ChatViewModel.handshakeWalletCanPayOne). Zero or below the cost is never affordable.
 * 2. The failure text: for ShortfallReason.NotEnough with the SDK's figure, shortfallNoticeFor shows what the wallet has
 *    and what the message needs (the new chat_send_insufficient_balance_have_need, EN and ES in Qa2K1CopyTest), not the
 *    static floor. The other reasons and a missing figure keep today's texts (controls).
 *
 * 3. QA2 k1b (ZC-1813, [test-change-ok: ZC-1813]: the test once named "the funds gate behind Pay for this" tests
 *    handshakeWalletCanPayOne and is renamed so): the first-message question itself. Over the real plan check
 *    (ProbeFirstMessagePlanUseCase, FirstMessagePlanFunding.decide) one 36,000 note pays the KEX (17,000) and leaves a
 *    19,000 change, which the SDK refuses for the 17,000 message (2,000 change). The question must never quote that plan
 *    and the KEX must never be paid. The KEX's change follows the same rule: exactly the message's cost, or the cost plus
 *    the marginal fee.
 *
 * Synthetic wallets; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendableMarginRuleTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
    }

    @After
    fun tearDown() {
        sendScopes.close()
    }

    private val margin = Zip317.MARGINAL_FEE_ZATOSHI

    /** [cost] is affordable at itself and from cost + margin up, and at no figure between, nor below. */
    private fun assertRule(
        label: String,
        cost: Long,
        affordable: (Long) -> Boolean,
    ) {
        assertTrue("$label: exactly the cost ($cost) must be affordable", affordable(cost))
        assertFalse("$label: below the cost must not be affordable", affordable(cost - 1))
        for (spendable in listOf(cost + 1, cost + 1_000, cost + margin / 2, cost + margin - 1)) {
            assertFalse(
                "$label: $spendable spendable against a cost of $cost leaves ${spendable - cost} change, which the SDK refuses",
                affordable(spendable),
            )
        }
        assertTrue("$label: cost + the marginal fee must be affordable", affordable(cost + margin))
        assertTrue("$label: well above the cost must be affordable", affordable(cost + 10 * margin))
        assertFalse("$label: nothing is never affordable", affordable(0))
    }

    @Test
    fun `the Payment dialog's Send follows the rule for a payment and for a long memo`() {
        for ((amount, chunks) in listOf(10_000L to 1, 100_000L to 1, 100_000L to 3)) {
            val cost = paymentOutlay(Zatoshi(amount), chunks).value
            assertRule("canAffordPayment($amount, $chunks chunks)", cost) { spendable ->
                canAffordPayment(Zatoshi(amount), chunks, Zatoshi(spendable))
            }
        }
    }

    @Test
    fun `the Tunnel offer's Accept and pay follows the rule`() {
        for (quote in listOf(17_000L, 22_000L, 34_000L)) {
            assertRule("OpenChatTunnelOffer.canAccept(quote $quote)", quote) { spendable ->
                OpenChatTunnelOffer.canAccept(spendable, quote)
            }
        }
    }

    @Test
    fun `the handshake funds gate (handshakeWalletCanPayOne) follows the rule for a one-memo message`() =
        runBlocking {
            val prefs = HandshakePrefs("usyntheticzc1417peer0000001", firstContact = false)
            val vm = HandshakeHarness.handshakeViewModel(prefs, HandshakeSeam("usyntheticzc1417peer0000001"))
            val method =
                ChatViewModel::class.java
                    .getDeclaredMethod("handshakeWalletCanPayOne", WalletAccount::class.java)
                    .apply { isAccessible = true }
            val base = syntheticZashiAccount()

            fun canPayOne(spendable: Long): Boolean {
                val account = base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(spendable), Zatoshi(0), Zatoshi(0))))
                return method.invoke(vm, account) as Boolean
            }

            assertEquals("fixture: the one-memo floor is 17,000", 17_000L, onChainSendMinimumCost().value)
            assertRule("handshakeWalletCanPayOne", onChainSendMinimumCost().value, ::canPayOne)
        }

    // ---- ZC-1813: the first-message question ---------------------------------------------------------------------------

    @Test
    fun `ZC-1813 - the plan rule applies the margin to the KEX's change`() {
        val note = FirstMessagePlanApi.outpoint(PLAN_TX, 0, 36_000)

        fun planWithChange(change: Long): String =
            FirstMessagePlanApi.nameOf(
                FirstMessagePlanApi.decide(
                    FirstMessagePlanApi.built(listOf(note), listOf(change)),
                    FirstMessagePlanApi.built(listOf(note), listOf(change)),
                    messageAlone = 17_000L,
                ),
            )
        assertEquals("control: a change of exactly the message's cost pays it", "Fundable", planWithChange(17_000))
        assertEquals("control: a change of the cost plus the marginal fee pays it", "Fundable", planWithChange(17_000 + margin))
        for (change in listOf(17_001L, 19_000L, 17_000 + margin - 1)) {
            assertEquals(
                "a $change change leaves ${change - 17_000} for the message's own change, which the SDK refuses",
                "NotFundable",
                planWithChange(change),
            )
        }
    }

    @Test
    fun `ZC-1813 - one 36,000 note - the first message is never quoted and its KEX is never paid`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            FirstMessagePlanChat.resetOwedKexState()
            try {
                val chat = FirstMessagePlanChat(PLAN_PEER)
                val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
                FirstMessagePlanApi.install(vm, realPlanCheck())

                chat.typeAndSend(vm, "synthetic zc1813 first words")
                val asked = chat.asked(vm)
                if (asked) {
                    vm.confirmPaidAction()
                    HandshakeHarness.awaitIdle(vm)
                }

                assertFalse("the question quoted a plan whose second payment cannot be built (19,000 change for a 17,000 message)", asked)
                assertEquals("the KEX was paid and the message after it cannot be built: 17,000 spent for nothing", 0, chat.proposals())
                assertFalse("the owed-KEX mark was written for a plan that cannot be paid", chat.owedMarkWritten())
            } finally {
                FirstMessagePlanChat.resetOwedKexState()
                AutoSpendVm.resetProcessState()
                Dispatchers.resetMain()
            }
        }

    /** The real ProbeFirstMessagePlanUseCase over the real use case and repository; both legs spend one 36,000 note. */
    private suspend fun realPlanCheck(): Any {
        val note = FirstMessagePlanApi.outpoint(PLAN_TX, 0, 36_000)
        // KEX: 36,000 - 2,000 outputs - 15,000 fee = 19,000 change. The message probe sees the same note.
        val probes =
            listOf(
                FirstMessagePlanApi.built(listOf(note), listOf(19_000)),
                FirstMessagePlanApi.built(listOf(note), listOf(19_000)),
            )
        val calls = AtomicInteger(0)
        val source =
            Proxy.newProxyInstance(
                ProposalDataSource::class.java.classLoader,
                arrayOf(ProposalDataSource::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "probeZip321Plan" -> probes[calls.getAndIncrement().coerceAtMost(1)]
                    "toString" -> "Zc1813PlanSeam"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected ProposalDataSource call: ${method.name}")
                }
            } as ProposalDataSource
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = source,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                isOwnAddress = { false },
            )
        return FirstMessagePlanApi
            .cls(FirstMessagePlanApi.USE_CASE)
            .constructors
            .single()
            .newInstance(useCase)
    }

    @Test
    fun `the failure text names what the wallet has and what the message needs`() {
        val notice =
            CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                reason = ShortfallReason.NotEnough(have = Zatoshi(18_000L), need = Zatoshi(22_000L), pending = Zatoshi(0L)),
                isValueTransfer = false,
            )

        assertTrue("the figure the wallet has (0.00018 ZEC) is not in: $notice", "0.00018" in notice)
        assertTrue("the figure the message needs (0.00022 ZEC, the SDK's) is not in: $notice", "0.00022" in notice)
        assertFalse("the text repeats the static floor 0.00017 beside a need of 0.00022: $notice", "0.00017" in notice)
        assertTrue(
            "the screen needs a resource for this text (chat_send_insufficient_balance_have_need) to show it in the phone's language",
            mainSources().any { "chat_send_insufficient_balance_have_need" in it },
        )
    }

    @Test
    fun `control - the other reasons and a missing figure keep today's texts`() {
        assertEquals(
            CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
            CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                ShortfallReason.StillConfirming(pending = Zatoshi(20_000L), need = Zatoshi(22_000L)),
                isValueTransfer = false,
            ),
        )
        assertEquals(
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
            CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                ShortfallReason.NotEnough(have = Zatoshi(18_000L), need = null, pending = Zatoshi(0L)),
                isValueTransfer = false,
            ),
        )
        assertEquals(
            CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE,
            CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                ShortfallReason.NotEnough(have = Zatoshi(18_000L), need = Zatoshi(22_000L), pending = Zatoshi(0L)),
                isValueTransfer = true,
            ),
        )
    }

    private companion object {
        const val PLAN_PEER = "usyntheticzc1813planpeer0001"
        const val PLAN_TX = "dd00000000000000000000000000000000000000000000000000000000001813"
    }

    private fun mainSources(): List<String> =
        File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
}
