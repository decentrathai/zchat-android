package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProof
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.PlainSendVerdict
import co.electriccoin.zcash.ui.common.tex.TexApi
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.TexSameBlockFixture
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.txid
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
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-769: reaching expiry plus F4's reorg margin is not proof that a stored Wallet send was never mined.
 * One real checker can publish Waiting at that boundary more than once at the same tip. The open progress
 * screen must keep its Waiting state and safe exits, but cannot show the numeric copy that invites another send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletProgressWaitsForProofBeforeResendCopyTest {
    private val vms = mutableListOf<TransactionProgressVM>()
    private val collectors = CoroutineScope(Dispatchers.Default + Job())

    private companion object {
        const val E = 7_000L
        const val TIMEOUT_MS = 10_000L
        const val PEER = "usyntheticzc769peer0001"
        val CONTINUING: String = txid(0xA6)
        val FIRST_LATE: String = txid(0xAA)
    }

    @Before
    fun setUp() {
        resetProcessCheck()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        try {
            endViewModelsThenResetMain(vms, collectors)
        } finally {
            resetProcessCheck()
        }
    }

    @Test
    fun numeric_waiting_becomes_checking_at_dead_block_until_a_higher_tip_proves_not_sent() =
        runBlocking {
            val wallet = wallet(CONTINUING)
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
            wallet.tip = E - 20
            TexApi.pass(monitor)
            val screen = harness(CONTINUING)
            val minutes = SendAttemptRules.minutesUntilDead(E, E - 20)
            val numeric =
                StringResource.ByResource(
                    R.string.send_confirmation_waiting_subtitle,
                    listOf(SendAttemptRules.deadBlock(E).toString(), minutes),
                )
            val before = screen.awaitBody(numeric)
            assertEquals(stringRes(R.string.send_confirmation_waiting_title), before.title)

            wallet.tip = SendAttemptRules.deadBlock(E)
            TexApi.pass(monitor)
            assertEquals(
                PlainSendVerdict.Waiting(E, wallet.tip),
                (TexSameBlockFixture.check(monitor) as TexCheck).plainSends[CONTINUING],
            )
            val first = screen.awaitChangedFrom(before.text)
            assertEquals(stringRes(R.string.send_confirmation_waiting_title), first.title)
            assertEquals(stringRes(R.string.transaction_detail_checking_body), first.text)
            assertNoInvitation(first.text)

            // A second pass at the same fully scanned tip is not F4's proof.
            TexApi.pass(monitor)
            assertEquals(
                PlainSendVerdict.Waiting(E, wallet.tip),
                (TexSameBlockFixture.check(monitor) as TexCheck).plainSends[CONTINUING],
            )
            delay(50)
            val repeated = screen.vm.state.value as SentUnconfirmedTransactionState
            assertEquals(stringRes(R.string.send_confirmation_waiting_title), repeated.title)
            assertEquals(stringRes(R.string.transaction_detail_checking_body), repeated.text)
            repeated.onBack()
            repeated.onCloseClick()
            assertEquals(listOf("backToRoot", "backToRoot"), screen.router.commands)
            assertEquals(0, screen.clears.get(), "Waiting cleared the Send form before proof")

            wallet.tip = SendAttemptRules.deadBlock(E) + 1
            TexApi.pass(monitor)
            assertEquals(
                PlainSendVerdict.NotSent,
                (TexSameBlockFixture.check(monitor) as TexCheck).plainSends[CONTINUING],
            )
            val proven = screen.awaitBody(stringRes(R.string.send_confirmation_not_sent_subtitle))
            assertEquals(stringRes(R.string.send_confirmation_not_sent_title), proven.title)
        }

    @Test
    fun first_observation_after_dead_block_is_still_checking_without_any_prior_proof() =
        runBlocking {
            val wallet = wallet(FIRST_LATE)
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
            wallet.tip = SendAttemptRules.deadBlock(E) + 5
            TexApi.pass(monitor)
            assertEquals(
                PlainSendVerdict.Waiting(E, wallet.tip),
                (TexSameBlockFixture.check(monitor) as TexCheck).plainSends[FIRST_LATE],
            )
            val screen = harness(FIRST_LATE)
            val first = screen.awaitWaiting()
            assertEquals(stringRes(R.string.send_confirmation_waiting_title), first.title)
            assertEquals(stringRes(R.string.transaction_detail_checking_body), first.text)
            assertNoInvitation(first.text)
            first.onBack()
            assertEquals(listOf("backToRoot"), screen.router.commands)
            assertEquals(0, screen.clears.get())
        }

    private fun wallet(txId: String): FakeTexWallet =
        FakeTexWallet().apply {
            transactions =
                listOf(
                    TexApi.facts(
                        txId = txId,
                        mined = null,
                        expiry = E,
                        outputs = listOf(TexApi.output(PEER, isOwn = false, kind = "OTHER")),
                    ),
                )
        }

    private fun assertNoInvitation(body: StringResource) {
        assertEquals(stringRes(R.string.transaction_detail_checking_body), body)
        val copy = ChatResourceText.strings("values").getValue("transaction_detail_checking_body").lowercase()
        assertTrue("do not send it again" in copy)
        assertFalse("you can then send it again" in copy)
    }

    private fun resetProcessCheck() {
        SendAttemptProof.clearAll()
        val field = TexSendMonitor::class.java.getDeclaredField("mutableLatestCheck").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val flow = field.get(null) as MutableStateFlow<TexCheck?>
        flow.value = null
    }

    private inner class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
        val clears: AtomicInteger,
    ) {
        init {
            collectors.launch { vm.state.collect { } }
        }

        suspend fun awaitBody(body: StringResource): SentUnconfirmedTransactionState =
            withTimeout(TIMEOUT_MS) {
                vm.state.first { it is SentUnconfirmedTransactionState && it.text == body } as SentUnconfirmedTransactionState
            }

        suspend fun awaitChangedFrom(previous: StringResource): SentUnconfirmedTransactionState =
            withTimeout(TIMEOUT_MS) {
                vm.state.first { it is SentUnconfirmedTransactionState && it.text != previous } as SentUnconfirmedTransactionState
            }

        suspend fun awaitWaiting(): SentUnconfirmedTransactionState =
            withTimeout(TIMEOUT_MS) {
                vm.state.first {
                    it is SentUnconfirmedTransactionState && it.title == stringRes(R.string.send_confirmation_waiting_title)
                } as SentUnconfirmedTransactionState
            }
    }

    private suspend fun harness(txId: String): Harness {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val keystone = Zc101FakeKeystoneProposalRepository()
        val proposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic")
        val zashi =
            holdingRepository(
                proposal,
                SubmitProposalState.Result(SubmitResult.Failure(listOf(txId), code = -26, description = "synthetic")),
            )
        val router = Zc101RecordingNavigationRouter()
        val context = ContextWrapper(null)
        val observeClearSend = ObserveClearSendUseCase()
        val clears = AtomicInteger()
        collectors.launch { observeClearSend().collect { clears.incrementAndGet() } }
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
                        observeClearSend = observeClearSend,
                        accountDataSource = accounts,
                        swapRepository = unused<SwapRepository>(),
                    ),
                viewTransactionsAfterSuccessfulProposal = ViewTransactionsAfterSuccessfulProposalUseCase(keystone, zashi, router),
                viewTransactionDetailAfterSuccessfulProposal =
                    ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router),
            )
        vms += vm
        return Harness(vm, router, clears)
    }

    private fun holdingRepository(
        proposal: co.electriccoin.zcash.ui.common.datasource.TransactionProposal,
        submitState: SubmitProposalState,
    ): ZashiProposalRepository {
        val proposalFlow = MutableStateFlow<co.electriccoin.zcash.ui.common.datasource.TransactionProposal?>(proposal)
        val stateFlow = MutableStateFlow<SubmitProposalState?>(submitState)
        return Proxy.newProxyInstance(
            ZashiProposalRepository::class.java.classLoader,
            arrayOf(ZashiProposalRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getTransactionProposal" -> if (args?.lastOrNull() is Continuation<*>) proposal else proposalFlow
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
}
