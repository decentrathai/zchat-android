package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2-1 (b), plan step p2-dabd323c2530: a scanned ZIP 321 request that asks for more than one payment is
 * refused in every scan flow, for Zashi and Keystone accounts, with a message that says why.
 *
 * Before: the home and Send flows asked the proposal builder, which refused the URI, and then quietly
 * pre-filled the Send form with payment 0 only; the address-book and Zip321-disabled Send flows took
 * payments.firstOrNull() the same way. The extra payments vanished without a word, so the payee was
 * under-paid and the user was never told. Now nothing is built or pre-filled and the error sheet
 * names the reason (ScannedMultiPaymentException).
 *
 * Same harness constraint as Zip321PrefillNetAmountTest: org.zecdev.zip321 is Java 21 bytecode, so the
 * scan result is allocated without its PaymentRequest and its payment count comes through the
 * OnZip321ScannedUseCase.paymentCountOf seam.
 */
class ScannedMultiPaymentRefusalTest {
    private val flows =
        listOf(
            ScanArgs(flow = ScanFlow.HOMEPAGE, isScanZip321Enabled = true),
            ScanArgs(flow = ScanFlow.SEND, isScanZip321Enabled = true),
            ScanArgs(flow = ScanFlow.SEND, isScanZip321Enabled = false),
            ScanArgs(flow = ScanFlow.ADDRESS_BOOK, isScanZip321Enabled = true),
        )

    @Test
    fun a_two_payment_request_is_refused_in_every_flow_for_every_account() =
        runBlocking {
            for (account in listOf(zashi(), keystone())) {
                for (args in flows) {
                    val case = "${account.javaClass.simpleName} ${args.flow} zip321=${args.isScanZip321Enabled}"
                    val harness = Harness(account)

                    harness.useCase.paymentCountOf = { 2 }
                    val outcome = runCatching { harness.useCase(scan(), args) }

                    assertEquals(emptyList(), harness.builderCalls, "$case: a 2-payment request reached the builder")
                    val shown = harness.errors.requireCurrentArgs()
                    assertIs<ErrorArgs.General>(shown, "$case: expected the refusal sheet, got $shown / $outcome")
                    assertTrue(
                        generateSequence<Throwable>(shown.exception) { it.cause }
                            .any { it.javaClass.simpleName == "ScannedMultiPaymentException" },
                        "$case: expected ScannedMultiPaymentException, got ${shown.exception} / $outcome",
                    )
                    assertNull(
                        withTimeoutOrNull(NO_PREFILL_MS) { harness.prefill().first() },
                        "$case: nothing may be pre-filled from a refused request",
                    )
                    assertTrue(harness.router.routes.none { it.contains("Review") }, "$case: ${harness.router.routes}")
                }
            }
        }

    /** Control: a one-payment request still goes to the builder and on to Review. */
    @Test
    fun a_one_payment_request_still_goes_to_review() =
        runBlocking {
            val harness = Harness(zashi())

            harness.useCase.paymentCountOf = { 1 }
            harness.useCase(scan(), ScanArgs(flow = ScanFlow.HOMEPAGE, isScanZip321Enabled = true))

            assertEquals(listOf("zashi.createZip321Proposal"), harness.builderCalls)
            assertTrue(harness.router.routes.any { it.contains("Review") }, "${harness.router.routes}")
        }

    // --------------------------------------------------------------------------------- harness

    private class Harness(
        account: WalletAccount
    ) {
        val builderCalls = mutableListOf<String>()
        val router = RouteRecordingRouter()
        val errors = NavigateToErrorUseCase(router)
        val prefill = PrefillSendUseCase()

        val useCase =
            OnZip321ScannedUseCase(
                keystoneProposalRepository = recording(KeystoneProposalRepository::class.java, "keystone"),
                zashiProposalRepository = recording(ZashiProposalRepository::class.java, "zashi"),
                accountDataSource = SelectedAccount(account),
                navigationRouter = router,
                prefillSend = prefill,
                navigateToErrorUseCase = errors,
            )

        /** Records every builder call. A one-payment Zashi build answers with a proposal; others refuse. */
        private fun <T : Any> recording(
            type: Class<T>,
            name: String
        ): T =
            checkNotNull(
                type.cast(
                    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                        when (method.name) {
                            "toString" -> name
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args!![0]
                            "clear" -> Unit
                            "createZip321Proposal" -> {
                                builderCalls += "$name.createZip321Proposal"
                                if (name == "zashi") {
                                    runBlocking { syntheticZip321Proposal("usynthetic-peer", 100_000, "") }
                                } else {
                                    throw TransactionProposalNotCreatedException(IllegalArgumentException("synthetic"))
                                }
                            }
                            else -> {
                                builderCalls += "$name.${method.name}"
                                throw TransactionProposalNotCreatedException(IllegalArgumentException("synthetic"))
                            }
                        }
                    },
                )
            )
    }

    private class RouteRecordingRouter : NavigationRouter {
        val routes = mutableListOf<String>()

        private fun record(
            command: String,
            vararg route: Any
        ) {
            routes += "$command(${route.joinToString { it.javaClass.simpleName }})"
        }

        override fun forward(vararg routes: Any) = record("forward", *routes)

        override fun replace(vararg routes: Any) = record("replace", *routes)

        override fun replaceAll(vararg routes: Any) = record("replaceAll", *routes)

        override fun back() = record("back")

        override fun backTo(route: KClass<*>) = record("backTo")

        override fun backToRoot() = record("backToRoot")

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    private class SelectedAccount(
        private val account: WalletAccount
    ) : AccountDataSource {
        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
        override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
        override val zashiAccount: Flow<ZashiAccount?> = flowOf(account as? ZashiAccount)

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

        override suspend fun getSelectedAccount(): WalletAccount = account

        override suspend fun getZashiAccount(): ZashiAccount = account as ZashiAccount

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
    }

    /**
     * A scan result without its PaymentRequest (see the class comment). Allocated without running the
     * constructor and without reflecting on its fields, which would load the Java 21 class.
     */
    private fun scan(): Zip321ParseUriValidation.Valid {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, Zip321ParseUriValidation.Valid::class.java) as Zip321ParseUriValidation.Valid
    }

    private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private suspend fun zashi(): WalletAccount = syntheticZashiAccount()

    private suspend fun keystone(): WalletAccount =
        KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone"), balance(0), balance(1_000_000)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone"), Zatoshi(0)),
            isSelected = true,
        )

    private companion object {
        const val NO_PREFILL_MS = 300L
    }
}
