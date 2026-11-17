package co.electriccoin.zcash.ui.common.usecase

import android.content.ContextWrapper
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorVM
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R2-6 (a), p2-cfef5773a20d, shielding half: a shielding whose outcome is [SubmitResult.SentUnconfirmed] must
 * tell the user so.
 *
 * At 566b866d2 ShieldFundsUseCase does nothing at all for SentUnconfirmed ("do nothing", like a success): the
 * shield button simply comes back and the user, told nothing, shields again. The settlement threw after the
 * SDK may have broadcast, so the honest answer is the same "Sent — Not Confirmed" the Send screen gives —
 * never the "Unable to shield — try again" error.
 *
 * Drives the real ShieldFundsUseCase and the real ErrorVM that renders what it navigates to. The Zashi
 * repository is a fake that builds a synthetic shield proposal and reports the settlement outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldSentUnconfirmedIsShownTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a shielding that is sent but not confirmed says so, and never invites shielding again`() =
        runBlocking {
            val router = Zc101RecordingNavigationRouter()
            val navigateToError = NavigateToErrorUseCase(router)
            val unconfirmed = SubmitResult.SentUnconfirmed(emptyList(), "synthetic refresh failure")

            shield(unconfirmed, router, navigateToError)

            val args = navigateToError.requireCurrentArgs()
            assertEquals(
                ErrorArgs.ShieldingError(unconfirmed),
                args,
                "a shielding that may be on the wire was answered with nothing (the user shields again)",
            )
            val state = errorVm(args, router, navigateToError).state.value
            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_title), state.title)
            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_subtitle), state.message)
            assertFalse(
                state.title == stringRes(R.string.error_shielding_title),
                "'Unable to shield' invites shielding again",
            )
        }

    /** Control, green before and after: a confirmed shielding shows no error. */
    @Test
    fun `control - a confirmed shielding shows nothing`() =
        runBlocking {
            val router = Zc101RecordingNavigationRouter()
            val navigateToError = NavigateToErrorUseCase(router)

            shield(SubmitResult.Success(listOf("synthetic-shield-txid")), router, navigateToError)

            assertTrue(navigateToError.requireCurrentArgs() is ErrorArgs.General, "no error was recorded")
            assertFalse("forward" in router.commands)
        }

    // --------------------------------------------------------------- harness

    private suspend fun shield(
        outcome: SubmitResult,
        router: Zc101RecordingNavigationRouter,
        navigateToError: NavigateToErrorUseCase,
    ) {
        val repository = SettlingShieldRepository(outcome)
        ShieldFundsUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository = repository.proxy,
            navigationRouter = router,
            accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
            navigateToError = navigateToError,
            messageAvailabilityDataSource = NoMessages,
        )(closeCurrentScreen = false)
        val done =
            withTimeoutOrNull(TIMEOUT_MS) {
                while (!repository.cleared) delay(5)
                true
            }
        assertEquals(true, done, "fixture sanity: the shielding ran to the end")
        assertEquals(1, repository.submits, "fixture sanity: one shielding was submitted")
    }

    private fun errorVm(
        args: ErrorArgs,
        router: Zc101RecordingNavigationRouter,
        navigateToError: NavigateToErrorUseCase,
    ): ErrorVM {
        val context = ContextWrapper(null)
        return ErrorVM(
            args = args,
            navigateToErrorBottom = navigateToError,
            navigationRouter = router,
            sendEmailUseCase = SendEmailUseCase(context, GetSupportUseCase(context, unused<ConfigurationProvider>())),
            optInExchangeRateAndTor =
                OptInExchangeRateAndTorUseCase(
                    router,
                    unused<IsExchangeRateEnabledStorageProvider>(),
                    unused<IsTorEnabledStorageProvider>(),
                ),
        )
    }

    /** Builds a synthetic shield proposal and settles it with [outcome]. */
    private class SettlingShieldRepository(
        private val outcome: SubmitResult,
    ) {
        @Volatile var submits = 0

        @Volatile var cleared = false

        val proxy: ZashiProposalRepository =
            Proxy.newProxyInstance(
                ZashiProposalRepository::class.java.classLoader,
                arrayOf(ZashiProposalRepository::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "createShieldProposal" -> ShieldTransactionProposal(newDistinctSdkProposal())
                    "submit" -> {
                        submits++
                        outcome
                    }
                    "clear" -> {
                        cleared = true
                        Unit
                    }
                    "toString" -> "SettlingShieldRepository"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected repository call ${method.name}")
                }
            } as ZashiProposalRepository
    }

    private object NoMessages : MessageAvailabilityDataSource {
        override val canShowMessage: Flow<Boolean> = flowOf(false)
        override val canShowShieldMessage: Flow<Boolean> = flowOf(false)

        override fun onMessageShown() = Unit

        override fun onShieldingInitiated() = Unit
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
