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
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RED pin for M-1: the wallet's shortfall sheet is chosen by exception type alone.
 *
 * `CreateProposalUseCase` maps every `InsufficientFundsException` to the bare PAYMENT sheet, whose copy
 * opens "Please wait – Your previous transaction is still confirming". It never looks at whether
 * anything is pending. On the owner's phone nothing was: 443,002 Ironwood and 139,000 sealed Orchard,
 * all confirmed, and Send all asked for 572,002 (have 582,002, need 587,002). The sheet told him to wait
 * for a confirmation that did not exist, so waiting never helped.
 *
 * Drives the real use case, the real Zashi repository and the real `ProposalDataSourceImpl` down to an
 * SDK that refuses exactly as SDK 3.0.0 did. The route it forwards must carry the reason (and so the
 * numbers), not the bare please-wait sheet.
 */
class CreateProposalShortfallNavTest {
    @Test
    fun shortfallIsNotBarePleaseWait() =
        runTest {
            val accountDataSource = Zc101FakeAccountDataSource(ownerAccount())
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
                        destination = WalletAddress.Unified.new("usynthetic-peer"),
                        amount = Zatoshi(SEND_ALL_FILL),
                        memo = Memo(""),
                        proposal = null,
                    ),
                floor = false,
            )

            assertTrue(router.forwarded.isNotEmpty(), "a shortfall must lead somewhere, got no navigation")
            router.forwarded.forEach { route ->
                assertNotEquals<Any>(
                    InsufficientFundsArgs(InsufficientFundsContext.PAYMENT),
                    route,
                    "have $HAVE, need $NEED and nothing pending: the bare PAYMENT sheet says 'your previous " +
                        "transaction is still confirming', which is false here",
                )
            }
        }

    /** Records the route objects themselves, so the test can see WHICH sheet was opened. */
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

    /**
     * A synchronizer that refuses `proposeTransfer` the way SDK 3.0.0 did on the owner's phone. Any other
     * call is a fixture bug and throws.
     */
    private class ShortfallSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

        private val refusing: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "ShortfallSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "proposeTransfer" ->
                        throw TransactionEncoderException.ProposalFromParametersException(
                            RuntimeException(SDK_SHORTFALL_TEXT)
                        )
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(refusing)

        override suspend fun getSynchronizer(): Synchronizer = refusing

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val IRONWOOD_AVAILABLE = 443_002L
        const val ORCHARD_AVAILABLE = 139_000L

        /** What Send all filled on the owner's phone: Orchard + Ironwood − the flat 10,000 reserve. */
        const val SEND_ALL_FILL = 572_002L
        const val HAVE = 582_002L
        const val NEED = 587_002L

        /** The rust backend's shortfall text, as SDK 3.0.0 wraps it into the exception's root cause. */
        const val SDK_SHORTFALL_TEXT =
            "Error creating transaction proposal: Insufficient balance (have $HAVE, need $NEED including fee)"

        private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        /** The owner's phone after NU6.3: Ironwood 443,002 and sealed Orchard 139,000, nothing pending. */
        suspend fun ownerAccount() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(ORCHARD_AVAILABLE),
                        balance(IRONWOOD_AVAILABLE),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
