package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.F2SubmitPath
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionDetailAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionsAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.newDistinctSdkProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T3b-2 item 2 (fix-all 2.13.0, train T3b; train T3 phone finding 7, T3-first check 4 on both phones; T3-first
 * REVIEW hand-off 5): a TEX send whose first step was accepted and whose second step the server refused (or that was
 * never handed over) shows "Send Failed … Your funds are safe but need to be recovered with assistance from our side …
 * Please use the button below to contact us". Since F3 (04eac15eb) that is not true: the TEX checker (TexSendMonitor)
 * hands an unexpired step to the server again once per process, and when the second step can no longer be mined it
 * looks up the ZEC at the one-time address (turning Tor on by itself when nothing else is in progress) and moves it
 * back into the wallet, with no question; the phones did this end to end (T3 F3 check 2 run 1). The screen sends the
 * user to support for what the app does by itself.
 *
 * What it must be, for a send to a TEX address: a title that does not say "Send Failed", a text that says what ZCHAT
 * does by itself (keeps checking; the exchange is paid if the rest still goes through; otherwise the ZEC comes back
 * into the wallet minus a network fee; Tor may be turned on for it; it can take an hour or more; do not send this
 * payment again; the transaction shows the status), and View Transaction opening the last step, whose detail shows
 * the checker's live status. Back goes to the history. A multi-transaction send to another kind of address, which the
 * TEX checker does not cover, keeps the support texts.
 *
 * Driven through F2's real submit path over the F0 [RecordingBroadcaster] into the real TransactionProgressVM. The
 * new texts and properties are reached BY NAME. Every id and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TexRefusedPartRecoversByItselfTest {
    private val vms = mutableListOf<TransactionProgressVM>()

    private val leg1 = RecordingBroadcaster.leg(seed = 0x51, expiryHeight = 3_100_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 0x52, expiryHeight = 3_100_040)
    private val id1 = leg1.txIdString()
    private val id2 = leg2.txIdString()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        endViewModelsThenResetMain(vms)
    }

    @Test
    fun `a TEX send whose second step was refused says ZCHAT moves the ZEC back by itself, not contact support`() =
        runBlocking {
            val result = texSend(leg2Answer = { F2SubmitPath.rejected(it) })
            assertIs<SubmitResult.Partial>(result)
            assertFalse(result.unacceptedLegsResent, "setup: a refused step is not a transport failure")

            val state = harness(result, texProposal()).state()

            assertIs<MultipleFailuresTransactionState>(state, "the part-not-sent screen")
            assertEquals(stringRes(string(TEX_TITLE)), state.property<StringResource>("title"))
            assertEquals(stringRes(string(TEX_TEXT_1)), state.property<StringResource>("text1"))
            assertEquals(stringRes(string(TEX_TEXT_2)), state.property<StringResource>("text2"))
        }

    @Test
    fun `a TEX send whose second step was refused opens the second step, and Back goes to the history`() =
        runBlocking {
            val harness = harness(texSend(leg2Answer = { F2SubmitPath.rejected(it) }), texProposal())
            val state = harness.state()
            assertIs<MultipleFailuresTransactionState>(state)

            val view = assertNotNull(state.property<(() -> Unit)?>("onViewTransactionClick"), "no View Transaction")
            view()
            assertEquals(listOf("replaceAll"), harness.router.commands, "View Transaction opens the stored step")
            assertEquals(listOf(id2), harness.opened, "it opens the last step, whose detail shows the TEX status")

            harness.router.commands.clear()
            state.onBack()
            assertEquals(listOf("backToRoot"), harness.router.commands, "Back goes to the history")
            assertTrue(state.showBackButton)
        }

    /** Control (keeps its support texts): a multi-transaction send to another kind of address is not a TEX send. */
    @Test
    fun `control - a refused part of a send to another kind of address keeps the support screen`() =
        runBlocking {
            val result = texSend(leg2Answer = { F2SubmitPath.rejected(it) })
            val proposal = syntheticZip321Proposal(PEER_UNIFIED, amount = 5_000_000L, memo = "synthetic")
            val state = harness(result, proposal).state()
            assertIs<MultipleFailuresTransactionState>(state, "a refused part keeps the support screen")
            state.onBack()
        }

    @Test
    fun `the TEX texts exist in English and Spanish and say what ZCHAT does by itself`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in listOf(TEX_TITLE, TEX_TEXT_1, TEX_TEXT_2)) {
            val english = en[key] ?: fail("values has no $key")
            val spanish = es[key] ?: fail("values-es has no $key")
            assertNotEquals(english.normalize(), spanish.normalize(), "$key is the English text in the Spanish file")
            assertFalse(Regex("fail|assistance", RegexOption.IGNORE_CASE).containsMatchIn(english), "$key: $english")
            assertFalse(Regex("error|fall|asistencia", RegexOption.IGNORE_CASE).containsMatchIn(spanish), "$key: $spanish")
        }
        val text = en.getValue(TEX_TEXT_2)
        for (phrase in listOf("by itself", "back into your wallet", "network fee", "Tor", "Do not send this payment again")) {
            assertTrue(phrase in text, "the English text does not say \"$phrase\": $text")
        }
        val texto = es.getValue(TEX_TEXT_2)
        for (phrase in listOf("por sí solo", "tu billetera", "comisión de red", "Tor", "No vuelvas a enviar este pago")) {
            assertTrue(phrase in texto, "the Spanish text does not say \"$phrase\": $texto")
        }
        // The old support texts stay for the sends the TEX checker does not cover, unchanged.
        assertTrue("recovered with assistance" in en.getValue("send_confirmation_multiple_trx_failure_text_1"))
    }

    // --------------------------------------------------------------- harness

    private suspend fun texProposal(): TransactionProposal =
        RegularTransactionProposal(
            destination = WalletAddress.Tex.new(PEER_TEX),
            amount = Zatoshi(5_000_000L),
            memo = Memo(""),
            proposal = newDistinctSdkProposal(),
        )

    private suspend fun texSend(leg2Answer: (CreatedTransaction) -> TransactionSubmitResult): SubmitResult {
        val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
        broadcaster.answerSubmit(id2) { leg2Answer(it) }
        val result = F2SubmitPath.createStoreAndSubmit(broadcaster)
        assertEquals(listOf(id1, id2), broadcaster.submitted(), "F2 hands every leg to the server")
        return result
    }

    private fun string(name: String): Int = ChatResourceText.stringId(name) ?: fail("there is no string '$name'")

    @Suppress("UNCHECKED_CAST")
    private fun <T> TransactionProgressState.property(name: String): T {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val method =
            javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?: throw AssertionError("${javaClass.simpleName} has no '$name'")
        return method.invoke(this) as T
    }

    /** Records every navigation command and the transaction each opened detail shows. */
    private class Router : NavigationRouter {
        val commands = mutableListOf<String>()
        val opened = mutableListOf<String>()

        override fun forward(vararg routes: Any) {
            commands += "forward"
        }

        override fun replace(vararg routes: Any) {
            commands += "replace"
        }

        override fun replaceAll(vararg routes: Any) {
            commands += "replaceAll"
            routes.forEach { (it as? TransactionDetailArgs)?.let { args -> opened += args.transactionId } }
        }

        override fun back() {
            commands += "back"
        }

        override fun backTo(route: KClass<*>) {
            commands += "backTo"
        }

        override fun backToRoot() {
            commands += "backToRoot"
        }

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    private class Harness(
        val vm: TransactionProgressVM,
        val router: Router,
    ) {
        val opened: List<String> get() = router.opened

        suspend fun state(): TransactionProgressState =
            withTimeout(TIMEOUT_MS) { vm.state.first { it != null && it !is SendingTransactionState } }!!
    }

    private suspend fun harness(
        result: SubmitResult,
        proposal: TransactionProposal,
    ): Harness {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val keystone = Zc101FakeKeystoneProposalRepository()
        val zashi = holdingRepository(proposal, SubmitProposalState.Result(result))
        val router = Router()
        val context = ContextWrapper(null)
        val detail = ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router)
        val vm =
            TransactionProgressVM(
                observeTransactionProposal = ObserveProposalUseCase(keystone, zashi, accounts),
                observeTransactionSubmitState = ObserveTransactionSubmitStateUseCase(keystone, zashi, accounts),
                getTransactionProposal = GetProposalUseCase(keystone, zashi, accounts),
                copyToClipboardUseCase = CopyToClipboardUseCase(context),
                sendEmailUseCase = SendEmailUseCase(context, GetSupportUseCase(context, unused<ConfigurationProvider>())),
                cancelKeystoneProposalFlow =
                    CancelProposalFlowUseCase(
                        zashiProposalRepository = zashi,
                        keystoneProposalRepository = keystone,
                        navigationRouter = router,
                        observeClearSend = ObserveClearSendUseCase(),
                        accountDataSource = accounts,
                        swapRepository = unused<SwapRepository>(),
                    ),
                viewTransactionsAfterSuccessfulProposal =
                    ViewTransactionsAfterSuccessfulProposalUseCase(keystone, zashi, router),
                viewTransactionDetailAfterSuccessfulProposal = detail,
            )
        vms += vm
        return Harness(vm, router)
    }

    private fun holdingRepository(
        proposal: TransactionProposal,
        submitState: SubmitProposalState,
    ): ZashiProposalRepository {
        val proposalFlow = MutableStateFlow<TransactionProposal?>(proposal)
        val stateFlow = MutableStateFlow<SubmitProposalState?>(submitState)
        return Proxy.newProxyInstance(
            ZashiProposalRepository::class.java.classLoader,
            arrayOf(ZashiProposalRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getTransactionProposal" -> proposalFlow
                "getSubmitState" -> stateFlow
                "clear" -> Unit
                "toString" -> "HoldingZashiProposalRepository"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected repository call ${method.name}")
            }
        } as ZashiProposalRepository
    }

    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, method, args ->
            when (method.name) {
                "toString" -> "Unused${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected call ${method.name}")
            }
        } as T

    private fun String.normalize() = replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val PEER_TEX = "tex1synthetictexrefusedpartpeer0001"
        const val PEER_UNIFIED = "usynthetictexrefusedpartpeer0002"
        const val TIMEOUT_MS = 10_000L
        const val TEX_TITLE = "send_confirmation_tex_part_not_sent_title"
        const val TEX_TEXT_1 = "send_confirmation_tex_part_not_sent_text_1"
        const val TEX_TEXT_2 = "send_confirmation_tex_part_not_sent_text_2"
    }
}
