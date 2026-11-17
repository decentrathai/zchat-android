package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
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
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.WelcomeZec
import co.electriccoin.zcash.ui.screen.chat.model.paymentMemoChunks
import co.electriccoin.zcash.ui.screen.chat.model.paymentOutlay
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D-6 (fix-all 2.13.0, train T5b; t5b SPECS §5, owner W-10) — the three payment confirms (the Send Payment dialog, the
 * pay-request confirm and the Welcome ZEC confirm) quote a floor ("about", "at least"), and the payment then pays
 * whatever fee the proposal built after Pay carries: a wallet whose balance sits in several notes pays 5,000 zatoshi per
 * extra action that the user never saw. "At least" has no upper bound.
 *
 * Wanted:
 * - `ChatViewModel.quotePayment(peer, amountZatoshi, memoText, rawMemo)` (suspend) prices the payment with SR-A's
 *   `CreateChunkedMessageProposalUseCase.priceMessage` (chunks = the memo count `sendPayment` builds,
 *   `paymentMemoChunks(memo)`; 1 for a fulfil's raw `ZREQ_FULFILL|<id>` memo) and answers `PaymentQuote.Exact` (with
 *   the `ChatSendQuote` the send must match, the amount, the platform fee, the network fee and the exact total),
 *   `NotEnough(floor, spendable)`, `StillConfirming` or `Unavailable` (the probe threw, Orchard inputs, more than one
 *   transaction). No fallback to a floor: anything but Exact carries no quote.
 * - `sendPayment(peer, amountZec, memo, quote)` and `fulfillPaymentRequest(peer, amount, requestId, quote)` hand the
 *   quote to the chokepoint (`sendQuote`), whose SR-A guard refuses a proposal that is not the quoted transaction before
 *   the spend auth, `onSubmitStarting` and `onTransactionsStored`: nothing is submitted, no biometric prompt, the screen
 *   says `ChatSendCopy.PAYMENT_PRICE_CHANGED`; a fulfil writes no paid marker and no started record and releases its claim,
 *   so Pay stays.
 * - The same quote and the same proposal pay exactly once, with the amount on output 0, as today.
 *
 * Drives the real ChatViewModel, CreateChunkedMessageProposalUseCase and ZashiProposalRepositoryImpl ([PaymentHarness],
 * whose seam now answers the fee probe and builds proposals with a chosen network fee). New names are reached by
 * reflection, so this file compiles before they exist. All addresses and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentConfirmsQuoteExactPriceTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `D6-1 a one-memo payment of 1,000 zatoshi with a 15,000 fee is quoted exactly 17,000`() =
        runBlocking {
            val (vm, seam, _) = fixture("usyntheticd6quote0000000001")
            seam.feeProbe = { built(fee = 15_000) }

            val quote = quotePayment(vm, PEER_1, 1_000L, "", rawMemo = false)

            assertEquals("Exact", kind(quote), "a payable one-memo payment is not quoted exactly: $quote")
            assertEquals(Zatoshi(17_000L), get(quote, "total"), "the exact total")
            assertEquals(Zatoshi(15_000L), get(quote, "networkFee"), "the network fee the SDK priced")
            assertEquals(Zatoshi(1_000L), get(quote, "platformFee"), "the platform fee")
            assertEquals(Zatoshi(1_000L), get(quote, "amount"), "the amount")
            assertEquals(1, get(quote, "chunks"), "one memo")
            val sendQuote = assertNotNull(get(quote, "quote") as? ChatSendQuote, "Exact carries no ChatSendQuote")
            assertEquals(
                ChatSendQuote(
                    recipient = PEER_1,
                    chunkCount = 1,
                    amountPerOutput = Zatoshi(1_000L),
                    total = Zatoshi(17_000L),
                    fee = Zatoshi(15_000L),
                ),
                sendQuote,
            )
            assertEquals(1, seam.probes.get(), "the quote asks the SDK once")
            assertEquals(0, seam.proposalUris.size, "a quote builds no proposal")
            assertEquals(0, seam.submits.get(), "a quote submits nothing")
        }

    @Test
    fun `D6-2 Send Payment with a quote the proposal no longer matches pays nothing and says the price changed`() =
        runBlocking {
            val (vm, seam, biometrics) = fixture("usyntheticd6quote0000000002")
            seam.feeProbe = { built(fee = 15_000) }
            val quote = quotePayment(vm, PEER_2, VALUE_ZAT, "", rawMemo = false)
            val sendQuote = assertNotNull(get(quote, "quote") as? ChatSendQuote, "no exact quote: $quote")

            // Between the quote and Pay the wallet's notes changed: the real proposal costs 20,000.
            seam.proposalFee = 20_000L
            sendPayment(vm, PEER_2, VALUE_ZEC, "", sendQuote)
            awaitIdle(vm)

            assertEquals(1, seam.proposalUris.size, "fixture: the payment built its proposal")
            assertEquals(0, seam.submits.get(), "a payment whose price changed was submitted")
            assertEquals(0, biometrics.get(), "the spend auth was asked for a payment that cannot go out")
            assertEquals(
                SendMessageState.Error(priceChangedText()),
                PaymentHarness.sendMessageState(vm),
                "the screen does not say the price changed",
            )
        }

    @Test
    fun `D6-3 Send Payment with a quote the proposal matches pays exactly once, the amount on output 0`() =
        runBlocking { quotedPaymentPaysOnce(PEER_3, 1_000L, expectedSpendAuth = 0) }

    @Test
    fun `D6-3b a quoted value transfer pays exactly once after the unchanged spend auth`() =
        runBlocking { quotedPaymentPaysOnce(PEER_3B, VALUE_ZAT, expectedSpendAuth = 1) }

    private suspend fun quotedPaymentPaysOnce(
        peer: String,
        amountZat: Long,
        expectedSpendAuth: Int,
    ) {
        val (vm, seam, biometrics) = fixture(peer)
        seam.feeProbe = { built(fee = 15_000) }
        val quote = quotePayment(vm, peer, amountZat, "", rawMemo = false)
        val sendQuote = assertNotNull(get(quote, "quote") as? ChatSendQuote, "no exact quote: $quote")
        seam.proposalFee = 15_000L

        sendPayment(vm, peer, zecOf(amountZat), "", sendQuote)
        awaitIdle(vm)

        assertEquals(1, seam.submits.get(), "the quoted payment of $amountZat was not paid exactly once")
        assertEquals(PaymentHarness.zec(amountZat), PaymentHarness.firstOutputAmount(seam.proposalUris.single()))
        assertEquals(expectedSpendAuth, biometrics.get(), "the spend auth is unchanged")
    }

    @Test
    fun `D6-4 a fulfil whose price changed pays nothing, marks nothing and leaves Pay`() =
        runBlocking {
            val (vm, seam, biometrics) = fixture(PEER_4)
            val requestId = "synthetic-d6-request-4"
            seam.feeProbe = { built(fee = 15_000) }
            val quote = quotePayment(vm, PEER_4, VALUE_ZAT, "ZREQ_FULFILL|$requestId", rawMemo = true)
            assertEquals("Exact", kind(quote), "a payable request is not quoted exactly: $quote")
            assertEquals(1, get(quote, "chunks"), "a fulfil's raw memo is one memo")
            val sendQuote = get(quote, "quote") as ChatSendQuote
            assertEquals(Zatoshi(VALUE_ZAT + 1_000L + 15_000L), sendQuote.total)

            seam.proposalFee = 20_000L
            fulfil(vm, PEER_4, VALUE_ZAT, requestId, sendQuote)
            awaitIdle(vm)

            assertEquals(0, seam.submits.get(), "a fulfil whose price changed was submitted")
            assertEquals(0, biometrics.get(), "the spend auth was asked for a fulfil that cannot go out")
            val prefs = prefsOf(vm)
            assertFalse(requestId in prefs.paid, "the request is marked paid although nothing was paid")
            assertTrue(prefs.started.isEmpty(), "a FulfilStarted record was written although nothing was paid")
            assertFalse(requestId in paidRequestIds(vm), "Pay is hidden although nothing was paid")
            assertFalse(requestId in fulfilClaims(), "the fulfil kept its claim, so Pay cannot be tapped again")
            assertEquals(SendMessageState.Error(priceChangedText()), PaymentHarness.sendMessageState(vm))

            // Pay again at the new price: paid once.
            seam.feeProbe = { built(fee = 20_000) }
            val again = get(quotePayment(vm, PEER_4, VALUE_ZAT, "ZREQ_FULFILL|$requestId", rawMemo = true), "quote") as ChatSendQuote
            fulfil(vm, PEER_4, VALUE_ZAT, requestId, again)
            awaitIdle(vm)
            assertEquals(1, seam.submits.get(), "the re-quoted fulfil was not paid exactly once")
            assertTrue(requestId in prefs.paid)
            assertEquals(emptyList<String>(), prefs.unexpectedCalls, "fixture sanity")
        }

    @Test
    fun `D6-5 the Welcome quote counts the memos sendPayment builds and its quote passes the guard`() =
        runBlocking {
            val (vm, seam, _) = fixture(PEER_5)
            seam.feeProbe = { built(fee = 15_000) }
            val quote = quotePayment(vm, PEER_5, WelcomeZec.AMOUNT_ZATOSHI, WelcomeZec.MEMO, rawMemo = false)
            assertEquals("Exact", kind(quote), "the Welcome payment is not quoted exactly: $quote")
            assertEquals(paymentMemoChunks(WelcomeZec.MEMO), get(quote, "chunks"))
            assertEquals(Zatoshi(516_000L), get(quote, "total"), "500,000 + 1,000 + 15,000")
            val probeUri = seam.probeUris.single()
            // One memo parameter per memo output (the platform-fee output carries none).
            assertEquals(paymentMemoChunks(WelcomeZec.MEMO), Regex("""[?&]memo(\.\d+)?=""").findAll(probeUri).count())

            seam.proposalFee = 15_000L
            sendPayment(vm, PEER_5, WelcomeZec.AMOUNT_ZEC, WelcomeZec.MEMO, get(quote, "quote") as ChatSendQuote)
            awaitIdle(vm)
            assertEquals(1, seam.submits.get(), "the Welcome quote did not match the payment sendPayment builds")
            assertEquals(PaymentHarness.zec(WelcomeZec.AMOUNT_ZATOSHI), PaymentHarness.firstOutputAmount(seam.proposalUris.single()))
        }

    @Test
    fun `D6-6 no exact figure, no quote - a shortfall, a confirming wallet, a failed probe or Orchard inputs`() =
        runBlocking {
            val (vm, seam, _) = fixture(PEER_6)

            seam.feeProbe = { FeeProbe.Short }
            val short = quotePayment(vm, PEER_6, 1_000L, "", rawMemo = false)
            assertEquals("NotEnough", kind(short), "a wallet that cannot pay is not told so: $short")
            assertEquals(paymentOutlay(Zatoshi(1_000L), 1), get(short, "floor"), "NotEnough names the floor")
            assertEquals(Zatoshi(0L), get(short, "spendable"), "NotEnough names what can be spent")

            seam.feeProbe = { throw IllegalStateException("synthetic SDK failure") }
            assertEquals("Unavailable", kind(quotePayment(vm, PEER_6, 1_000L, "", rawMemo = false)))

            seam.feeProbe = { FeeProbe.Built(fee = Zatoshi(15_000L), usesOrchardInputs = true, transactionCount = 1) }
            assertEquals("Unavailable", kind(quotePayment(vm, PEER_6, 1_000L, "", rawMemo = false)), "Orchard inputs")

            seam.feeProbe = { FeeProbe.Built(fee = Zatoshi(15_000L), usesOrchardInputs = false, transactionCount = 2) }
            assertEquals("Unavailable", kind(quotePayment(vm, PEER_6, 1_000L, "", rawMemo = false)), "two transactions")

            // Ironwood still confirming would pay it.
            val confirming = syntheticZashiAccount()
            val pendingAccount =
                confirming.copy(
                    unified = confirming.unified.copy(ironwood = WalletBalance(Zatoshi(0), Zatoshi(500_000L), Zatoshi(0))),
                )
            PaymentHarness.setField(vm, "accountDataSource", Zc101FakeAccountDataSource(pendingAccount))
            seam.feeProbe = { FeeProbe.Short }
            assertEquals("StillConfirming", kind(quotePayment(vm, PEER_6, 1_000L, "", rawMemo = false)))

            assertEquals(0, seam.submits.get())
            assertEquals(0, seam.proposalUris.size, "a quote builds no proposal")
        }

    @Test
    fun `control - a payment with no quote is paid as today`() =
        runBlocking {
            val (vm, seam, _) = fixture(PEER_7)
            vm.sendPayment(PEER_7, zecOf(1_000L), "")
            awaitIdle(vm)
            assertEquals(1, seam.submits.get())
            assertNull((PaymentHarness.sendMessageState(vm) as? SendMessageState.Error)?.message)
        }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────

    private data class Fixture(
        val vm: ChatViewModel,
        val seam: PaymentSeam,
        val biometrics: AtomicInteger,
    )

    private val prefsByVm = HashMap<ChatViewModel, PaymentPrefs>()

    /** A PaymentHarness view model whose use case counts the spend-auth prompts. */
    private suspend fun fixture(peer: String): Fixture {
        val prefs = PaymentPrefs(peer)
        val seam = PaymentSeam(peer)
        val vm = paymentViewModel(prefs, seam)
        val biometrics = AtomicInteger(0)
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(SendHarnessScopes.current()::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = CountingBiometrics(biometrics),
            )
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
        PaymentHarness.setField(vm, "accountDataSource", accounts)
        prefsByVm[vm] = prefs
        return Fixture(vm, seam, biometrics)
    }

    private fun prefsOf(vm: ChatViewModel): PaymentPrefs = prefsByVm.getValue(vm)

    private class CountingBiometrics(
        private val count: AtomicInteger,
    ) : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) {
            count.incrementAndGet()
        }
    }

    private fun built(fee: Long): FeeProbe = FeeProbe.Built(fee = Zatoshi(fee), usesOrchardInputs = false, transactionCount = 1)

    // ── the new API, by name ────────────────────────────────────────────────────────────────────────

    private suspend fun quotePayment(
        vm: ChatViewModel,
        peer: String,
        amountZatoshi: Long,
        memoText: String,
        rawMemo: Boolean,
    ): Any {
        if (vm.javaClass.methods.none { it.name == "quotePayment" }) {
            fail("ChatViewModel.quotePayment(peer, amountZatoshi, memoText, rawMemo) does not exist (D-6)")
        }
        return assertNotNull(FirstMessagePlanApi.callSuspend(vm, "quotePayment", peer, amountZatoshi, memoText, rawMemo))
    }

    /** PaymentQuote's case: Exact, NotEnough, StillConfirming or Unavailable. */
    private fun kind(quote: Any?): String {
        val cls = quote?.javaClass ?: return "null"
        val base = cls.interfaces.map { it.name } + listOfNotNull(cls.superclass?.name)
        assertTrue(base.any { it == PAYMENT_QUOTE }, "${cls.name} is not a $PAYMENT_QUOTE")
        return cls.simpleName
    }

    private fun get(
        target: Any,
        property: String,
    ): Any? {
        val getter = "get" + property.replaceFirstChar { it.uppercaseChar() }
        val method =
            target.javaClass.methods.firstOrNull { (it.name == getter || it.name.startsWith("$getter-")) && it.parameterCount == 0 }
                ?: fail("${target.javaClass.simpleName} has no $property")
        val value = method.invoke(target)
        // A Zatoshi exposed as its inline Long (mangled getter).
        return if (method.name != getter && value is Long) Zatoshi(value) else value
    }

    private fun sendPayment(
        vm: ChatViewModel,
        peer: String,
        amountZec: Double,
        memo: String,
        quote: ChatSendQuote,
    ) {
        val method =
            ChatViewModel::class.java.methods.firstOrNull {
                it.name == "sendPayment" && it.parameterCount == 4 && it.parameterTypes[3] == ChatSendQuote::class.java
            } ?: fail("ChatViewModel.sendPayment(peer, amountZec, memo, quote: ChatSendQuote?) does not exist (D-6)")
        invoke { method.invoke(vm, peer, amountZec, memo, quote) }
    }

    private fun fulfil(
        vm: ChatViewModel,
        peer: String,
        amountZatoshi: Long,
        requestId: String,
        quote: ChatSendQuote,
    ) {
        val method =
            ChatViewModel::class.java.methods.firstOrNull {
                it.name == "fulfillPaymentRequest" && it.parameterCount == 4 && it.parameterTypes[3] == ChatSendQuote::class.java
            } ?: fail("ChatViewModel.fulfillPaymentRequest(peer, amountZatoshi, requestId, quote: ChatSendQuote?) does not exist (D-6)")
        invoke { method.invoke(vm, peer, amountZatoshi, requestId, quote) }
    }

    private fun invoke(block: () -> Unit) {
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun priceChangedText(): String =
        runCatching { ChatSendCopy::class.java.getField("PAYMENT_PRICE_CHANGED").get(null) as String }
            .getOrElse { fail("ChatSendCopy.PAYMENT_PRICE_CHANGED does not exist (D-6)") }

    @Suppress("UNCHECKED_CAST")
    private fun paidRequestIds(vm: ChatViewModel): Set<String> =
        (
            ChatViewModel::class.java
                .getDeclaredField("paidRequestIds")
                .apply { isAccessible = true }
                .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Set<String>>
        ).value

    @Suppress("UNCHECKED_CAST")
    private fun fulfilClaims(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("fulfilInFlight")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    private fun zecOf(zatoshi: Long): Double = BigDecimal.valueOf(zatoshi).movePointLeft(8).toDouble()

    private companion object {
        const val PAYMENT_QUOTE = "co.electriccoin.zcash.ui.screen.chat.model.PaymentQuote"
        const val PEER_1 = "usyntheticd6quote0000000001"
        const val PEER_2 = "usyntheticd6quote0000000002"
        const val PEER_3 = "usyntheticd6quote0000000003"
        const val PEER_3B = "usyntheticd6quote000000003b"
        const val PEER_4 = "usyntheticd6quote0000000004"
        const val PEER_5 = "usyntheticd6quote0000000005"
        const val PEER_6 = "usyntheticd6quote0000000006"
        const val PEER_7 = "usyntheticd6quote0000000007"

        /** A value transfer (above dust), so the spend auth is in play. */
        const val VALUE_ZAT = 50_000L
        const val VALUE_ZEC = 0.0005
    }
}
