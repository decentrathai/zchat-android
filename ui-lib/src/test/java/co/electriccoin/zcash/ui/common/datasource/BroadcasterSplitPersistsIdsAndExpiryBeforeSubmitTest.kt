package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F2 (fix-all 2.13.0, train T2): the created ids and their expiry heights are handed to the caller BEFORE anything is
 * submitted (maybe-sent design 3, review item 2; "ids lost").
 *
 * Today the chat and wallet senders learn a transaction id only from the submit flow's results, one at a time, as each
 * leg comes back from lightwalletd. A throw while that flow is collected returns only the ids emitted before it, and
 * no id at all exists anywhere until the network answered, so a send killed or failing mid-submit cannot be tracked by
 * id (it stays "maybe sent" forever). SDK 3.0.0's [cash.z.ecc.android.sdk.Broadcaster] returns every created
 * transaction (txid + expiry height) before the first submit.
 *
 * Pinned here:
 *  - the order is create, then `onStored(every id + expiry)`, then one submit per leg;
 *  - a record that cannot be saved (onStored throws) submits NOTHING, and the send is "maybe sent" carrying every id
 *    (the stored transactions stay in the wallet), never a Failure that invites a second payment;
 *  - a create that throws never reaches onStored or a submit, and is still settled from the wallet's own list
 *    (settleCreateFailure keeps that job only for a create that throws);
 *  - the callback is plumbed through ZashiProposalRepository.submit(proposal, onStored) to
 *    ProposalDataSource.submitTransaction(proposal, usk, onStored), and through the chat chokepoint's new
 *    `onTransactionsStored` argument (next to onSubmitStarting). A caller that passes none keeps today's call.
 *
 * Every id, address and memo is synthetic.
 */
class BroadcasterSplitPersistsIdsAndExpiryBeforeSubmitTest {
    private val leg1 = RecordingBroadcaster.leg(seed = 0x41, expiryHeight = 3_200_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 0x42, expiryHeight = 3_200_041)
    private val id1 = leg1.txIdString()
    private val id2 = leg2.txIdString()

    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
    }

    @After
    fun tearDown() {
        sendScopes.close()
    }

    @Test
    fun `every id and expiry is recorded after create and before the first submit`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            var recorded: List<Any> = emptyList()

            val result =
                F2SubmitPath.createStoreAndSubmit(
                    broadcaster,
                    onStored = { stored ->
                        recorded = stored
                        broadcaster.events +=
                            "stored:${F2SubmitPath.storedIds(stored).joinToString(",")}" +
                            "@${F2SubmitPath.storedExpiries(stored).joinToString(",")}"
                    },
                )

            assertEquals(
                listOf("create", "stored:$id1,$id2@3200040,3200041", "submit:$id1", "submit:$id2"),
                broadcaster.events.toList(),
                "the ids must be on record before anything can reach the network",
            )
            assertEquals(listOf(id1, id2), F2SubmitPath.storedIds(recorded))
            assertEquals(listOf(3_200_040L, 3_200_041L), F2SubmitPath.storedExpiries(recorded), "each leg's own expiry")
            assertEquals(SubmitResult.Success(txIds = listOf(id1, id2)), result)
        }

    @Test
    fun `a record that cannot be saved submits nothing, and the send stays maybe-sent with every id`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))

            val result =
                F2SubmitPath.createStoreAndSubmit(
                    broadcaster,
                    onStored = { throw IllegalStateException("synthetic prefs commit failed") },
                )

            assertEquals(emptyList(), broadcaster.submitted(), "an unrecorded send must not be submitted")
            assertTrue(
                result !is SubmitResult.Failure,
                "the transactions are stored in the wallet; a Failure invites a second payment: $result",
            )
            assertIs<SubmitResult.SentUnconfirmed>(result)
            assertEquals(listOf(id1, id2), result.txIds)
        }

    @Test
    fun `a create that throws and stored nothing reaches neither the record nor a submit`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            broadcaster.createFailure = IllegalStateException("synthetic proving failure")
            var recordCalls = 0

            assertFailsWith<NothingBroadcastException>("the wallet's own list shows no new spend") {
                F2SubmitPath.createStoreAndSubmit(
                    broadcaster,
                    onStored = { recordCalls++ },
                    ownSpends = { OLDER_SPENDS },
                )
            }
            assertEquals(0, recordCalls)
            assertEquals(emptyList(), broadcaster.submitted())
        }

    @Test
    fun `a create that throws after storing is still settled from the wallet's own list`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            broadcaster.createFailure = IllegalStateException("synthetic submit-plan write failure after storage")
            var reads = 0

            val result =
                F2SubmitPath.createStoreAndSubmit(
                    broadcaster,
                    onStored = { fail("a create that threw has nothing to record") },
                    ownSpends = {
                        reads++
                        if (reads == 1) OLDER_SPENDS else OLDER_SPENDS + id1
                    },
                )

            assertIs<SubmitResult.SentUnconfirmed>(result, "found in the wallet's own list: stored, so maybe sent")
            assertEquals(listOf(id1), result.txIds, "tracked by exactly this attempt's new id")
            assertEquals(emptyList(), broadcaster.submitted())
        }

    @Test
    fun `the repository hands the record callback to the data source`() =
        runBlocking<Unit> {
            val dataSource = RecordingDataSource()
            val repository = zashiRepository(dataSource)
            val onStored: suspend (List<Any>) -> Unit = { }

            val submit =
                ZashiProposalRepositoryImpl::class.java.methods.firstOrNull {
                    it.name == "submit" && it.parameterCount == 3
                } ?: fail("ZashiProposalRepository has no submit(proposal, onStored)")
            val result = callSuspend(submit, repository, syntheticZip321Proposal(PEER, 5_000_000L, "memo"), onStored)

            assertEquals(listOf("submitTransaction/3"), dataSource.calls.toList())
            assertSame(onStored, dataSource.onStored, "the caller's callback, not a stand-in")
            assertIs<SubmitResult.Success>(result)
        }

    @Test
    fun `the chat chokepoint hands onTransactionsStored to the repository`() =
        runBlocking<Unit> {
            val dataSource = RecordingDataSource()
            val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
            val useCase =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository = zashiRepository(dataSource),
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = ApprovingBiometricRepository,
                )
            val onStored: suspend (List<Any>) -> Unit = { }
            val invoke =
                CreateChunkedMessageProposalUseCase::class.java.methods.firstOrNull {
                    it.name == "invoke" && it.parameterCount == USE_CASE_PARAMETERS_WITH_ON_STORED
                } ?: fail("CreateChunkedMessageProposalUseCase has no onTransactionsStored argument")
            val amount = Zatoshi(PAYMENT_ZAT)

            callSuspend(
                invoke,
                useCase,
                PEER,
                SELF,
                "ZREQ_FULFILL|synthetic-request-id",
                false,
                amount,
                CreateChunkedMessageProposalUseCase.defaultPlatformFee(amount),
                true,
                true,
                true,
                null,
                null,
                null,
                null,
                onStored,
                // SR-A: the use case's last parameter, sendQuote (no exact quote on this send).
                null,
                // The use case's last parameter, kexAnswerPrice (no answer price on this send).
                null,
            )

            assertEquals(listOf("submitTransaction/3"), dataSource.calls.toList())
            assertSame(onStored, dataSource.onStored)
        }

    /** Control, green before and after: a caller that passes no callback keeps today's two-argument call. */
    @Test
    fun `control - a submit without a callback keeps the two-argument call`() =
        runBlocking<Unit> {
            val dataSource = RecordingDataSource()

            val result = zashiRepository(dataSource).submit(syntheticZip321Proposal(PEER, 5_000_000L, "memo"))

            assertEquals(listOf("submitTransaction/2"), dataSource.calls.toList())
            assertIs<SubmitResult.Success>(result)
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private fun zashiRepository(dataSource: RecordingDataSource) =
        ZashiProposalRepositoryImpl(
            accountDataSource = Zc101FakeAccountDataSource(runBlocking { syntheticZashiAccount() }),
            proposalDataSource = dataSource.proxy,
            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
        ).also(sendScopes::own)

    /**
     * A [ProposalDataSource] that records which submitTransaction overload is called, by argument count (a Proxy, so
     * it needs no override of the three-argument overload before it exists).
     */
    private class RecordingDataSource {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var onStored: Any? = null

        private val prepared = runBlocking { syntheticZip321Proposal(PEER, amount = PAYMENT_ZAT, memo = "synthetic-memo") }

        val proxy: ProposalDataSource =
            Proxy.newProxyInstance(ProposalDataSource::class.java.classLoader, arrayOf(ProposalDataSource::class.java)) {
                proxy,
                method,
                args,
                ->
                val values = args.orEmpty()
                when (method.name) {
                    "toString" -> "RecordingDataSource"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === values[0]
                    "createZip321Proposal" -> prepared
                    "submitTransaction" -> {
                        // The last argument is the continuation; proposal + usk (+ onStored).
                        val arity = values.size - 1
                        calls += "submitTransaction/$arity"
                        if (arity == 3) onStored = values[2]
                        SubmitResult.Success(txIds = listOf(SYNTHETIC_TXID))
                    }
                    else -> throw UnsupportedOperationException("not used in this test: ${method.name}")
                }
            } as ProposalDataSource
    }

    /** The user authenticates: the value-transfer spend-auth gate is not what this file pins. */
    private object ApprovingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) = Unit
    }

    private companion object {
        /** Bare-alphanumeric and >= 20 chars so the ZIP-321 builder's address-shape guard accepts it. */
        const val PEER = "usyntheticf2recordpeer000000"

        const val SELF = "usyntheticf2recordself000000"

        const val PAYMENT_ZAT = 5_000_000L

        const val SYNTHETIC_TXID = "synthetic-f2-txid"

        /** The use case's 16 arguments (onTransactionsStored after onSubmitStarting, then sendQuote, then kexAnswerPrice) + the continuation. */
        const val USE_CASE_PARAMETERS_WITH_ON_STORED = 17

        /** Spends the wallet already held before the attempt. */
        val OLDER_SPENDS = listOf("f".repeat(64), "e".repeat(64))

        suspend fun callSuspend(
            method: Method,
            receiver: Any,
            vararg args: Any?,
        ): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(receiver, *args, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
    }
}
