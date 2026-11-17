package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.F2SubmitPath
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
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T3-first (fix-all 2.13.0, train T3; train T2 phone finding F2-3, seen in both runs): a TEX send whose leg 1 failed
 * on the transport while leg 2 was accepted is shown as "Send Failed … Contact Support", with Back blocked, although
 * BOTH legs were paid, exactly as quoted.
 *
 * What the phones did (HONOR run 1, Seeker run 2): leg 1's first submit failed (the debug seam
 * SUBMIT_LEG1_GRPC_FAILURE, a gRPC-class failure), the SDK's resubmit loop rebroadcast the stored leg 1 from its
 * recorded submit endpoint, and the app's submit of leg 2 was then accepted. F2's one submit path reads that as one
 * success and one gRPC failure, and [SubmitResult.classify] gives a [SubmitResult.Partial]. At ba3814167
 * TransactionProgressVM maps every Partial to MultipleFailuresTransactionState: "Send Failed … your funds need to be
 * recovered with our assistance … Contact Support", and Back does nothing until Contact Support is tapped. Both legs
 * were mined. A user who reads "Send Failed" may pay again.
 *
 * What it must be: every leg of the send is stored in the wallet (F2 records the ids before any submit) and every leg
 * was handed to the server, so the SDK keeps rebroadcasting a leg that was not accepted. When every leg that was not
 * accepted failed on the transport, the send is sent and waiting for the network: a warning with its own title and
 * text (never "Failed", never the success screen), View Transaction opens the stored transaction, and Back and Close
 * go to the transaction history (the proposal is cleared and the Send form is left), never back to a filled Send
 * form. A part the server itself refused after every earlier part was accepted keeps the support screen, but Back
 * works there too and goes to the history, never to the Send form.
 *
 * The send is driven through F2's real submit path ([F2SubmitPath], over the F0 [RecordingBroadcaster]) and its result
 * is shown by the real TransactionProgressVM over the real observe/view use cases. The new texts are looked up BY NAME
 * so this file compiles before they exist. Every id and address here is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TexPaidPartNeverSaysFailedTest {
    private val vms = mutableListOf<TransactionProgressVM>()

    private val leg1 = RecordingBroadcaster.leg(seed = 0x41, expiryHeight = 3_100_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 0x42, expiryHeight = 3_100_040)
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

    /** The T2 phone case: leg 1 failed on the transport, leg 2 was accepted. Both were paid. */
    @Test
    fun `leg 1 failed on the transport and leg 2 was accepted - the screen says sent and waiting, never failed`() =
        runBlocking {
            val result = texSend(leg1Answer = { RecordingBroadcaster.grpcFailure(it) })
            assertIs<SubmitResult.Partial>(result, "the phone case classifies as one accepted leg and one gRPC failure")

            val state = harness(result).state()

            assertWaitingNotFailed(state)
            assertEquals(stringRes(string(PARTLY_SENT_TITLE)), state.property<StringResource>("title"))
            assertEquals(stringRes(string(PARTLY_SENT_TEXT)), state.property<StringResource>("text"))
        }

    @Test
    fun `leg 1 failed on the transport and leg 2 was accepted - Back and Close go to the history, View opens the send`() =
        runBlocking {
            val harness = harness(texSend(leg1Answer = { RecordingBroadcaster.grpcFailure(it) }))
            val state = harness.state()
            assertWaitingNotFailed(state)

            state.onBack()
            assertEquals(listOf("backToRoot"), harness.router.commands, "Back must leave the screen for the history")

            harness.router.commands.clear()
            state.property<() -> Unit>("onCloseClick")()
            assertEquals(listOf("backToRoot"), harness.router.commands, "Close must leave the screen for the history")

            harness.router.commands.clear()
            state.property<() -> Unit>("onViewTransactionClick")()
            assertEquals(listOf("replaceAll"), harness.router.commands, "View Transaction opens the stored send")

            assertFalse("back" in harness.router.commands, "an exit returned to the filled Send form")
            assertTrue(harness.zashiCleared > 0, "the proposal must be cleared on the way out")
        }

    /** Leg 1 was accepted and leg 2 failed on the transport: leg 2 is stored with its endpoint and rebroadcast. */
    @Test
    fun `leg 1 accepted and leg 2 failed on the transport - also sent and waiting, never failed`() =
        runBlocking {
            val result = texSend(leg2Answer = { RecordingBroadcaster.grpcFailure(it) })
            assertIs<SubmitResult.Partial>(result)

            val harness = harness(result)
            val state = harness.state()

            assertWaitingNotFailed(state)
            assertEquals(stringRes(string(PARTLY_SENT_TITLE)), state.property<StringResource>("title"))
            state.onBack()
            assertEquals(listOf("backToRoot"), harness.router.commands)
        }

    /**
     * A part the server refused after every earlier part was accepted keeps the support screen (F3 replaces it with the
     * automatic recovery), but Back is no longer a dead key: it goes to the history and never to the Send form.
     */
    @Test
    fun `a part the server refused keeps the support screen, and Back goes to the history`() =
        runBlocking {
            val result = texSend(leg2Answer = { F2SubmitPath.rejected(it) })
            assertIs<SubmitResult.Partial>(result)

            val harness = harness(result)
            val state = harness.state()
            assertIs<MultipleFailuresTransactionState>(state, "a refused part keeps the support screen")

            state.onBack()
            assertEquals(listOf("backToRoot"), harness.router.commands, "Back must work before Contact Support")
            assertFalse("back" in harness.router.commands, "Back returned to the filled Send form")
            assertTrue(state.showBackButton, "the back arrow must be offered at once")
        }

    /** The new texts exist in English and Spanish, never call the send failed, and say not to pay again. */
    @Test
    fun `the sent-and-waiting texts are in English and Spanish and never say failed`() {
        val english = strings("values")
        val spanish = strings("values-es")
        for (key in listOf(PARTLY_SENT_TITLE, PARTLY_SENT_TEXT)) {
            val en = english[key] ?: fail("values has no $key")
            val es = spanish[key] ?: fail("values-es has no $key")
            assertNotEquals(en.normalize(), es.normalize(), "$key is the English text in the Spanish file")
            assertFalse(Regex("fail", RegexOption.IGNORE_CASE).containsMatchIn(en), "$key says failed: $en")
            assertFalse(Regex("error|fall", RegexOption.IGNORE_CASE).containsMatchIn(es), "$key says failed: $es")
            assertFalse(Regex("support", RegexOption.IGNORE_CASE).containsMatchIn(en), "$key sends to support: $en")
        }
        assertTrue(
            english.getValue(PARTLY_SENT_TEXT).contains("not send this payment again", ignoreCase = true),
            "the English text must tell the user not to pay again",
        )
        assertTrue(
            spanish.getValue(PARTLY_SENT_TEXT).contains("No vuelvas a enviar este pago", ignoreCase = true),
            "the Spanish text must tell the user not to pay again",
        )
        assertNotEquals(
            english["send_confirmation_success_title"]?.normalize(),
            english.getValue(PARTLY_SENT_TITLE).normalize(),
            "the waiting title must not be the success title",
        )
    }

    /** Control, green before and after: both legs accepted is still the success screen. */
    @Test
    fun `control - both legs accepted is still a success`() =
        runBlocking {
            val result = texSend()
            assertIs<SubmitResult.Success>(result)
            assertTrue(harness(result).state() is SuccessfulTransactionState)
        }

    /** Control, green before and after: nothing accepted on the transport is still the connection-issue screen. */
    @Test
    fun `control - no leg accepted on the transport is still the connection-issue screen`() =
        runBlocking {
            val result =
                texSend(
                    leg1Answer = { RecordingBroadcaster.grpcFailure(it) },
                    leg2Answer = { F2SubmitPath.missingInputs(it) },
                )
            assertIs<SubmitResult.GrpcFailure>(result)
            assertTrue(harness(result).state() is GrpcFailureTransactionState)
        }

    // --------------------------------------------------------------- harness

    private suspend fun texSend(
        leg1Answer: ((CreatedTransaction) -> TransactionSubmitResult)? = null,
        leg2Answer: ((CreatedTransaction) -> TransactionSubmitResult)? = null,
    ): SubmitResult {
        val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
        leg1Answer?.let { answer -> broadcaster.answerSubmit(id1) { answer(it) } }
        leg2Answer?.let { answer -> broadcaster.answerSubmit(id2) { answer(it) } }
        val result = F2SubmitPath.createStoreAndSubmit(broadcaster)
        assertEquals(listOf(id1, id2), broadcaster.submitted(), "F2 hands every leg to the server")
        return result
    }

    private fun assertWaitingNotFailed(state: TransactionProgressState) {
        assertFalse(state is MultipleFailuresTransactionState, "a send whose every leg may be out reads 'Send Failed'")
        assertFalse(state is FailureTransactionState, "a send whose every leg may be out reads as a failure")
        assertFalse(state is SuccessfulTransactionState, "nothing confirmed every leg yet: not the success screen")
        assertFalse(state is SendingTransactionState)
    }

    private fun string(name: String): Int =
        try {
            R.string::class.java.getField(name).getInt(null)
        } catch (e: NoSuchFieldException) {
            throw AssertionError("there is no string '$name' for a multi-transaction send that is sent and waiting", e)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> TransactionProgressState.property(name: String): T {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val method =
            javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?: throw AssertionError("${javaClass.simpleName} has no '$name'")
        return method.invoke(this) as T
    }

    private class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
        private val cleared: () -> Int,
    ) {
        val zashiCleared: Int get() = cleared()

        suspend fun state(): TransactionProgressState =
            withTimeout(TIMEOUT_MS) { vm.state.first { it != null && it !is SendingTransactionState } }!!
    }

    private suspend fun harness(result: SubmitResult): Harness {
        val proposal = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic")
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val keystone = Zc101FakeKeystoneProposalRepository()
        var clears = 0
        val zashi = holdingRepository(proposal, SubmitProposalState.Result(result)) { clears++ }
        val router = Zc101RecordingNavigationRouter()
        val context = ContextWrapper(null)
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
                viewTransactionDetailAfterSuccessfulProposal =
                    ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router),
            )
        vms += vm
        return Harness(vm, router) { clears }
    }

    /** The Zashi repository as the progress screen sees it: the proposal and its settled submit state. */
    private fun holdingRepository(
        proposal: TransactionProposal,
        submitState: SubmitProposalState,
        onClear: () -> Unit,
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
                "clear" -> onClear()
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

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/send_confirmation/$qualifier/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val PEER = "usynthetictexprogresspeer001"
        const val TIMEOUT_MS = 10_000L
        const val PARTLY_SENT_TITLE = "send_confirmation_partly_sent_title"
        const val PARTLY_SENT_TEXT = "send_confirmation_partly_sent_subtitle"

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/send_confirmation").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
