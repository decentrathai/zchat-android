package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin: the wallet has no way to move its sealed Orchard balance to itself (sweep spec §2).
 *
 * After NU6.3 an Orchard note can only pay the wallet's own address. Every [ProposalDataSource] funnel
 * takes a destination and refuses Orchard funding (OrchardSealedException / PublicCrossingException), so
 * value received before 28 July cannot leave Orchard at all. SDK 3.0.0 has the crossing:
 * `Synchronizer.proposeOrchardToIronwoodMigration(account)`, one transaction that spends every Orchard
 * note to the wallet's own Ironwood address with no change. The app never calls it.
 *
 * The funnel `createOrchardSweepProposal(account)` must take nothing but the account (no destination,
 * amount, memo or URI), call that SDK method exactly once, and return the very proposal the SDK built.
 * The proposal is checked before it is returned: one step, Orchard inputs only, one Ironwood payment, no
 * change, fee = totalFeeRequired(), and more than 5,000 zatoshi left to arrive (anything smaller lands
 * as a note too small to spend). Amount = the sum of the inputs, the figure that becomes public.
 *
 * The funnel is found by reflection so this compiles before it exists. Proposals are hand-encoded
 * protobuf (SDK 3.0.0 proposal.proto): field numbers and pool values as the spec lists them. Accounts
 * are the owner's HONOR phone (139,000 zatoshi of Orchard, with and without 443,002 of Ironwood). The
 * peer funnels' own guards are pinned by WalletSpendOrchardSealedGateTest and PublicCrossingRefusalTest.
 * All addresses are synthetic.
 */
class OrchardSweepFunnelTest {
    @Test
    fun the_sweep_asks_the_sdk_for_the_own_wallet_crossing_and_returns_its_proposal() =
        runTest {
            val cases =
                listOf(account(orchard = 139_000, ironwood = 0), account(orchard = 139_000, ironwood = 443_002))
                    .map { it to RecordingSynchronizerProvider(sweepProposal(listOf(orchard(139_000)), fee = 20_000)) }
            val funnel = sweepFunnel()

            cases.forEach { (account, sdk) ->
                val result = funnel.call(ProposalDataSourceImpl(sdk), account)

                assertEquals(listOf(MIGRATION_CALL), sdk.calls.map { it.name }, "exactly one SDK call")
                val call = sdk.calls.single()
                assertEquals(account.sdkAccount, call.args.first(), "the selected account")
                assertSame(sdk.proposal, result.proposal, "the proposal the SDK built, not a copy")
                assertEquals(Zatoshi(139_000), result.zatoshi("getAmount"), "amount = the sum of the inputs")
                assertEquals(Zatoshi(20_000), result.zatoshi("getFee"))
                assertEquals(Zatoshi(119_000), result.zatoshi("getArrives"))
            }
        }

    @Test
    fun the_sweep_funnel_takes_only_the_account() {
        val funnel = sweepFunnel()

        assertEquals(
            listOf(WalletAccount::class.java, Continuation::class.java),
            funnel.parameterTypes.toList(),
            "no destination, amount, memo or URI can reach the sweep",
        )
    }

    @Test
    fun a_sweep_that_leaves_just_over_the_marginal_fee_is_accepted() =
        runTest {
            val sdk = RecordingSynchronizerProvider(sweepProposal(listOf(orchard(25_001)), fee = 20_000))
            val funnel = sweepFunnel()

            val result = funnel.call(ProposalDataSourceImpl(sdk), account(orchard = 25_001, ironwood = 0))

            assertEquals(Zatoshi(5_001), result.zatoshi("getArrives"))
        }

    @Test
    fun a_proposal_that_is_not_an_own_wallet_orchard_sweep_is_refused() =
        runTest {
            val notASweep =
                mapOf(
                    "an Ironwood input" to sweepProposal(listOf(orchard(129_000), input(IRONWOOD, 10_000)), fee = 25_000),
                    "two steps" to sweepProposal(listOf(orchard(139_000)), fee = 20_000, steps = 2),
                    "a change output" to sweepProposal(listOf(orchard(139_000)), fee = 20_000, change = listOf(10_000)),
                    "an Orchard payment" to sweepProposal(listOf(orchard(139_000)), fee = 20_000, paymentPool = ORCHARD),
                )
            val funnel = sweepFunnel()

            notASweep.forEach { (case, proposal) ->
                val refusal = funnel.refusal(RecordingSynchronizerProvider(proposal), account(139_000, 0))
                assertTrue(
                    refusal is TransactionProposalNotCreatedException,
                    "$case: expected TransactionProposalNotCreatedException, got $refusal",
                )
            }
        }

    /** Three notes cost 25,000: from 30,000 exactly 5,000 would arrive, an unspendable note. */
    @Test
    fun a_sweep_that_would_land_an_unspendable_note_is_too_small() =
        runTest {
            val proposal = sweepProposal(List(3) { orchard(10_000) }, fee = 25_000)
            val funnel = sweepFunnel()

            val refusal = funnel.refusal(RecordingSynchronizerProvider(proposal), account(orchard = 30_000, ironwood = 0))

            assertTrue(
                generateSequence(refusal) { it.cause }.any { it.javaClass.simpleName == "OrchardSweepTooSmallException" },
                "expected OrchardSweepTooSmallException in the cause chain, got $refusal",
            )
        }

    // --------------------------------------------------------------------------------- harness

    private fun sweepFunnel(): Method =
        assertNotNull(
            ProposalDataSource::class.java.methods.firstOrNull { it.name == FUNNEL },
            "no own-address sweep funnel: ProposalDataSource has no $FUNNEL(account), so sealed Orchard can " +
                "never be moved",
        )

    private suspend fun Method.call(
        dataSource: ProposalDataSource,
        account: WalletAccount
    ): TransactionProposal =
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                invoke(dataSource, account, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as TransactionProposal

    /** The exception the funnel refused with; returning a proposal is a failure. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun Method.refusal(
        sdk: RecordingSynchronizerProvider,
        account: WalletAccount
    ): Throwable {
        val returned =
            try {
                call(ProposalDataSourceImpl(sdk), account)
            } catch (e: Exception) {
                return e
            }
        fail("the funnel returned a proposal it must refuse: $returned")
    }

    private fun TransactionProposal.zatoshi(getter: String): Zatoshi = javaClass.getMethod(getter).invoke(this) as Zatoshi

    /** Answers the migration call with [proposal]; records every call. Any other call is a fixture bug. */
    private class RecordingSynchronizerProvider(
        val proposal: Proposal
    ) : SynchronizerProvider {
        data class Call(
            val name: String,
            val args: List<Any?>
        )

        val calls = mutableListOf<Call>()

        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

        private val recording: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "RecordingSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> {
                        calls += Call(method.name, args.orEmpty().filterNot { it is Continuation<*> })
                        if (method.name == MIGRATION_CALL) {
                            proposal
                        } else {
                            throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                        }
                    }
                }
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(recording)

        override suspend fun getSynchronizer(): Synchronizer = recording

        override fun resetSynchronizer() = Unit
    }

    private data class Input(
        val pool: Int,
        val value: Long
    )

    private companion object {
        const val FUNNEL = "createOrchardSweepProposal"
        const val MIGRATION_CALL = "proposeOrchardToIronwoodMigration"

        const val ORCHARD = 3
        const val IRONWOOD = 4

        // proposal.proto (SDK 3.0.0) field numbers.
        const val PROPOSAL_PROTO_VERSION = 1
        const val PROPOSAL_FEE_RULE = 2
        const val PROPOSAL_STEPS = 4
        const val STEP_PAYMENT_POOLS = 2
        const val STEP_INPUTS = 4
        const val STEP_BALANCE = 5
        const val PAYMENT_INDEX = 1
        const val PAYMENT_VALUE_POOL = 2
        const val INPUT_RECEIVED_OUTPUT = 1
        const val RECEIVED_TXID = 1
        const val RECEIVED_VALUE_POOL = 2
        const val RECEIVED_INDEX = 3
        const val RECEIVED_VALUE = 4
        const val BALANCE_CHANGE = 1
        const val BALANCE_FEE = 2
        const val CHANGE_VALUE = 1
        const val CHANGE_VALUE_POOL = 2
        const val FEE_RULE_ZIP317 = 3

        const val WIRE_VARINT = 0
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_TYPE_BITS = 3
        const val VARINT_PAYLOAD = 0x7FL
        const val VARINT_MORE = 0x80L
        const val VARINT_SHIFT = 7
        const val TXID_BYTES = 32

        fun orchard(value: Long) = Input(ORCHARD, value)

        fun input(
            pool: Int,
            value: Long
        ) = Input(pool, value)

        /** A proposal as the SDK serializes it: [steps] identical steps spending [inputs]. */
        fun sweepProposal(
            inputs: List<Input>,
            fee: Long,
            paymentPool: Int = IRONWOOD,
            change: List<Long> = emptyList(),
            steps: Int = 1,
        ): Proposal {
            val step =
                message {
                    bytes(
                        STEP_PAYMENT_POOLS,
                        message {
                            varint(PAYMENT_INDEX, 0)
                            varint(PAYMENT_VALUE_POOL, paymentPool.toLong())
                        },
                    )
                    inputs.forEachIndexed { index, input ->
                        val received =
                            message {
                                bytes(RECEIVED_TXID, ByteArray(TXID_BYTES) { (index + 1).toByte() })
                                varint(RECEIVED_VALUE_POOL, input.pool.toLong())
                                varint(RECEIVED_INDEX, index.toLong())
                                varint(RECEIVED_VALUE, input.value)
                            }
                        bytes(STEP_INPUTS, message { bytes(INPUT_RECEIVED_OUTPUT, received) })
                    }
                    bytes(
                        STEP_BALANCE,
                        message {
                            change.forEach { value ->
                                bytes(
                                    BALANCE_CHANGE,
                                    message {
                                        varint(CHANGE_VALUE, value)
                                        varint(CHANGE_VALUE_POOL, IRONWOOD.toLong())
                                    },
                                )
                            }
                            varint(BALANCE_FEE, fee)
                        },
                    )
                }
            val bytes =
                message {
                    varint(PROPOSAL_PROTO_VERSION, 1)
                    varint(PROPOSAL_FEE_RULE, FEE_RULE_ZIP317.toLong())
                    repeat(steps) { bytes(PROPOSAL_STEPS, step) }
                }
            return Proposal.fromByteArray(bytes).also {
                // Fixture self-check against the SDK's own reading of the bytes.
                check(it.transactionCount() == steps) { "fixture: ${it.transactionCount()} steps" }
                check(it.totalFeeRequired() == Zatoshi(fee * steps)) { "fixture: fee ${it.totalFeeRequired()}" }
                check(it.usesOrchardInputs() == inputs.any { input -> input.pool == ORCHARD }) { "fixture: pools" }
            }
        }

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(
            orchard: Long,
            ironwood: Long
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), balance(ironwood)),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

        /** A tiny protobuf writer: enough for the fields above. */
        class Wire {
            val out = ByteArrayOutputStream()

            fun varint(
                field: Int,
                value: Long
            ) {
                raw(tag(field, WIRE_VARINT))
                raw(encodeVarint(value))
            }

            fun bytes(
                field: Int,
                value: ByteArray
            ) {
                raw(tag(field, WIRE_LENGTH_DELIMITED))
                raw(encodeVarint(value.size.toLong()))
                raw(value)
            }

            private fun raw(value: ByteArray) = out.write(value, 0, value.size)

            private fun tag(
                field: Int,
                wireType: Int
            ) = encodeVarint((field.toLong() shl WIRE_TYPE_BITS) or wireType.toLong())

            private fun encodeVarint(value: Long): ByteArray {
                val bytes = ByteArrayOutputStream()
                var rest = value
                while (rest and VARINT_PAYLOAD.inv() != 0L) {
                    bytes.write(((rest and VARINT_PAYLOAD) or VARINT_MORE).toInt())
                    rest = rest ushr VARINT_SHIFT
                }
                bytes.write(rest.toInt())
                return bytes.toByteArray()
            }
        }

        fun message(build: Wire.() -> Unit): ByteArray = Wire().apply(build).out.toByteArray()
    }
}
