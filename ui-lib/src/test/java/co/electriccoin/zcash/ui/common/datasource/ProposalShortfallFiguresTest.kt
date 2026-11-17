package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The SDK's shortfall figures survive the proposal funnel (M-2).
 *
 * `getOrThrow` threw a bare `InsufficientFundsException()`, dropping "have 582002, need 587002", so the
 * shortfall sheet could not say how short the send was. The figures now ride on the exception while its
 * message stays null: the chat send paths key on a blank message to tell an SDK shortfall from their
 * own worded ones. Send all's probe reads the same text as "too short", not as an error. The ZIP 321
 * path is pinned by the instrumented ProposalFailureMappingTest (its parser needs Java 21). All
 * addresses are synthetic.
 */
class ProposalShortfallFiguresTest {
    private val dataSource = ProposalDataSourceImpl(RefusingSynchronizerProvider(SDK_SHORTFALL_TEXT))

    @Test
    fun a_shortfall_carries_the_sdk_have_and_need_with_no_message() =
        runTest {
            val e =
                assertFailsWith<InsufficientFundsException> {
                    dataSource.createProposal(ownerAccount(), payment())
                }

            assertEquals(Zatoshi(582_002), e.have)
            assertEquals(Zatoshi(587_002), e.need)
            assertNull(e.message, "the chat send paths tell an SDK shortfall apart by its blank message")
        }

    @Test
    fun a_shortfall_without_figures_is_still_a_shortfall() =
        runTest {
            val dataSource =
                ProposalDataSourceImpl(
                    RefusingSynchronizerProvider("The transaction requires an additional change output of 5000")
                )

            val e =
                assertFailsWith<InsufficientFundsException> {
                    dataSource.createProposal(ownerAccount(), payment())
                }

            assertNull(e.have)
            assertNull(e.need)
        }

    @Test
    fun a_send_all_probe_reads_a_shortfall_as_too_short() =
        runTest {
            assertEquals(FeeProbe.Short, dataSource.probeFee(ownerAccount(), payment()))
        }

    @Test
    fun a_send_all_probe_does_not_hide_other_failures() =
        runTest {
            val dataSource = ProposalDataSourceImpl(RefusingSynchronizerProvider("Note selection failed"))

            assertFailsWith<TransactionProposalNotCreatedException> {
                dataSource.probeFee(ownerAccount(), payment())
            }
        }

    /** A synchronizer whose `proposeTransfer` refuses with [rootCauseText]; any other call is a fixture bug. */
    private class RefusingSynchronizerProvider(
        rootCauseText: String
    ) : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

        private val refusing: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "RefusingSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "proposeTransfer" ->
                        throw TransactionEncoderException.ProposalFromParametersException(
                            RuntimeException(rootCauseText)
                        )
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(refusing)

        override suspend fun getSynchronizer(): Synchronizer = refusing

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val SDK_SHORTFALL_TEXT =
            "Error creating transaction proposal: Insufficient balance (have 582002, need 587002 including fee)"

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun ownerAccount() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), balance(443_002)),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )

        suspend fun payment() =
            ZecSend(
                destination = WalletAddress.Unified.new("usynthetic-peer"),
                amount = Zatoshi(572_002),
                memo = Memo(""),
                proposal = null,
            )
    }
}
