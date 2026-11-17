package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * ZC-1948 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026; register medium) — ZC-1909 made ShortfallReason.classify
 * follow the chat's first-message rule (SpendableCover: the cost exactly, or the cost + 5,000), and the wallet's own Send
 * reads the same classifier: with need + 5,000 .. 9,999 still confirming it opened the STILL_CONFIRMING sheet, whose text
 * promises that the wallet will cover the send once it confirms. Those notes each add an action fee the SDK's need did not
 * count yet, which is why the wallet kept a 10,000 allowance (CONFIRMING_NOTES_FEE).
 *
 * Wanted: the wallet's Send keeps the old margin (need + 10,000 before it says "still confirming"); the chat keeps the
 * ZC-1909 rule. Drives the real use case, repository and ProposalDataSourceImpl down to an SDK that refuses as SDK 3.0.0
 * does. All addresses are synthetic.
 */
class WalletSendKeepsTheConfirmingNotesMarginTest {
    @Test
    fun `a wallet send with need plus 5,000 confirming is not promised to be covered`() =
        runTest { assertEquals(InsufficientFundsReason.NOT_ENOUGH, reasonWithConfirming(NEED + 5_000)) }

    @Test
    fun `a wallet send with need plus 9,999 confirming is not promised to be covered`() =
        runTest { assertEquals(InsufficientFundsReason.NOT_ENOUGH, reasonWithConfirming(NEED + 9_999)) }

    @Test
    fun `control - a wallet send with need plus 10,000 confirming is still confirming`() =
        runTest { assertEquals(InsufficientFundsReason.STILL_CONFIRMING, reasonWithConfirming(NEED + 10_000)) }

    private suspend fun reasonWithConfirming(pending: Long): InsufficientFundsReason? {
        val accountDataSource = Zc101FakeAccountDataSource(account(pending))
        val router = RouteRecordingNavigationRouter()
        val useCase =
            CreateProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accountDataSource,
                        proposalDataSource = ProposalDataSourceImpl(ShortfallSynchronizerProvider()),
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ),
                accountDataSource = accountDataSource,
                navigationRouter = router,
            )
        useCase(
            zecSend =
                ZecSend(
                    destination = WalletAddress.Unified.new("usynthetic-zc1948-peer"),
                    amount = Zatoshi(SEND),
                    memo = Memo(""),
                    proposal = null,
                ),
            floor = false,
        )
        val sheet = router.forwarded.filterIsInstance<InsufficientFundsArgs>().singleOrNull()
        assertNotEquals(null, sheet, "the shortfall opened no sheet: ${router.forwarded}")
        return sheet!!.reason
    }

    private class RouteRecordingNavigationRouter : NavigationRouter {
        val forwarded = mutableListOf<Any>()

        override fun forward(vararg routes: Any) {
            forwarded.addAll(routes)
        }

        override fun replace(vararg routes: Any) {
            forwarded.addAll(routes)
        }

        override fun replaceAll(vararg routes: Any) {
            forwarded.addAll(routes)
        }

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    /** A synchronizer that refuses proposeTransfer as SDK 3.0.0 does; any other call is a fixture bug. */
    private class ShortfallSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

        private val refusing: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Zc1948ShortfallSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "proposeTransfer" ->
                        throw TransactionEncoderException.ProposalFromParametersException(RuntimeException(SDK_SHORTFALL_TEXT))
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(refusing)

        override suspend fun getSynchronizer(): Synchronizer = refusing

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val SEND = 2_000L
        const val NEED = 17_000L
        const val SDK_SHORTFALL_TEXT =
            "Error creating transaction proposal: Insufficient balance (have 0, need $NEED including fee)"

        private fun balance(
            available: Long,
            changePending: Long = 0,
        ) = WalletBalance(Zatoshi(available), Zatoshi(changePending), Zatoshi(0))

        /** Nothing spendable now, [pending] Ironwood still confirming, no Orchard. */
        suspend fun account(pending: Long) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-zc1948-self"),
                        balance(0),
                        balance(0, changePending = pending),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-zc1948-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-zc1948-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
