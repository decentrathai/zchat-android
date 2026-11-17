package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Z-2: a scanned payment request must not come back after it was paid.
 *
 * A scan goes straight to Review, so the Send screen is not collecting when `OnZip321ScannedUseCase`
 * hands its prefill to the rendezvous bus. The prefill parks there so that Review → back → Send finds
 * the request. If the user pays instead, nothing used to take it off the bus, and the next Send screen
 * (any time later in the process) opened pre-filled with the address, the full amount and the memo of
 * the request that was already paid. Submitting must drop it; backing out of the biometric must not.
 *
 * Same harness constraint as Zip321PrefillNetAmountTest: the org.zecdev.zip321 jar is Java 21 and the
 * unit tests run on Java 17, so the scan result is allocated without its PaymentRequest and the scan's
 * repository is a Proxy that answers with a prepared proposal. All addresses are synthetic.
 */
class Zip321PrefillAfterSubmitTest {
    @Test
    fun reviewBackStillPrefillsTheFullRequest() =
        runBlocking {
            val prefillSend = PrefillSendUseCase()
            scanRequest(prefillSend)

            val sent =
                assertIs<PrefillSendData.All>(
                    withTimeout(AWAIT_MS) { prefillSend().first() },
                    "Review → back → Send must still find the scanned request",
                )
            assertEquals(REQUEST_ZATOSHI, sent.sendFormAmount.value, "the form is filled with the requested amount")
        }

    @Test
    fun paidRequestIsNotPrefilledAgain() =
        runBlocking {
            val prefillSend = PrefillSendUseCase()
            val payment = scanRequest(prefillSend)
            val biometrics = Zc101SuspendingBiometricRepository()
            val submit = submitProposalUseCase(prefillSend, biometrics)

            val job = launch(Dispatchers.Default) { submit.useCase(payment) }
            withTimeout(AWAIT_MS) { biometrics.promptShown.await() }
            biometrics.approve()
            job.join()
            withTimeout(AWAIT_MS) { submit.dataSource.firstSettlement.await() }

            assertNoPrefill(prefillSend)
        }

    @Test
    fun cancelledBiometricKeepsThePrefill() =
        runBlocking {
            val prefillSend = PrefillSendUseCase()
            val payment = scanRequest(prefillSend)
            val submit = submitProposalUseCase(prefillSend, CancellingBiometricRepository())

            submit.useCase(payment)

            assertIs<PrefillSendData.All>(
                withTimeout(AWAIT_MS) { prefillSend().first() },
                "nothing was paid, so going back to Send must still find the request",
            )
            assertEquals(0, submit.dataSource.submittedProposals.size, "a cancelled biometric submits nothing")
        }

    @Test
    fun paidKeystoneRequestIsNotPrefilledAgain() =
        runBlocking {
            val prefillSend = PrefillSendUseCase()
            val payment = scanRequest(prefillSend)
            val metadataRepository = Zc101FakeMetadataRepository()
            val useCase =
                SubmitKSProposalUseCase(
                    keystoneProposalRepository = answeringKeystoneRepository(payment),
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    swapRepository = Zc101FakeSwapRepository(),
                    processSwapTransaction =
                        ProcessSwapTransactionUseCase(
                            metadataRepository = metadataRepository,
                            swapDataSource = Zc101FakeSwapDataSource(),
                        ),
                    prefillSend = prefillSend,
                )

            useCase()

            assertNoPrefill(prefillSend)
        }

    private class SubmitHarness(
        val dataSource: Zc101RecordingProposalDataSource,
        val useCase: SubmitProposalUseCase,
    )

    /** The real submit path over the real Zashi repository, with the scanned payment prepared. */
    private suspend fun submitProposalUseCase(
        prefillSend: PrefillSendUseCase,
        biometrics: BiometricRepository,
    ): SubmitHarness {
        val account = syntheticZashiAccount()
        val dataSource = Zc101RecordingProposalDataSource()
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = Zc101FakeAccountDataSource(account),
                proposalDataSource = dataSource,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        dataSource.preparedByUri[ZC101_PAYMENT_URI] = syntheticPayment()
        repository.createZip321Proposal(ZC101_PAYMENT_URI, allowMultiPayment = true)
        val metadataRepository = Zc101FakeMetadataRepository()
        val useCase =
            SubmitProposalUseCase(
                navigationRouter = Zc101RecordingNavigationRouter(),
                accountDataSource = Zc101FakeAccountDataSource(account),
                zashiProposalRepository = repository,
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                biometricRepository = biometrics,
                swapRepository = Zc101FakeSwapRepository(),
                metadataRepository = metadataRepository,
                processSwapTransaction =
                    ProcessSwapTransactionUseCase(
                        metadataRepository = metadataRepository,
                        swapDataSource = Zc101FakeSwapDataSource(),
                    ),
                prefillSend = prefillSend,
            )
        return SubmitHarness(dataSource, useCase)
    }

    /**
     * Scans a request from the home screen: its prefill parks on [prefillSend] because no Send screen is
     * collecting yet. Returns the proposal Review would show.
     */
    private suspend fun scanRequest(prefillSend: PrefillSendUseCase): Zip321TransactionProposal {
        val payment = syntheticPayment()
        val router = Zc101RecordingNavigationRouter()
        OnZip321ScannedUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository = answeringZashiRepository(payment),
            accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
            navigationRouter = router,
            prefillSend = prefillSend,
            navigateToErrorUseCase = NavigateToErrorUseCase(router),
        ).invoke(scanResultWithoutPaymentRequest(), ScanArgs(flow = ScanFlow.HOMEPAGE, isScanZip321Enabled = true))
        return payment
    }

    private suspend fun assertNoPrefill(prefillSend: PrefillSendUseCase) {
        val stale = withTimeoutOrNull(NO_PREFILL_MS) { prefillSend().first() }
        assertNull(
            stale,
            "the request was paid; the next Send screen must open empty, not pre-filled with $stale",
        )
    }

    private suspend fun syntheticPayment() = syntheticZip321Proposal(REQUEST_RECIPIENT, REQUEST_ZATOSHI, "")

    /** See Zip321PrefillNetAmountTest: allocated without running the constructor. */
    private fun scanResultWithoutPaymentRequest(): Zip321ParseUriValidation.Valid {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, Zip321ParseUriValidation.Valid::class.java) as Zip321ParseUriValidation.Valid
    }

    private fun answeringZashiRepository(proposal: Zip321TransactionProposal): ZashiProposalRepository =
        Proxy.newProxyInstance(
            ZashiProposalRepository::class.java.classLoader,
            arrayOf(ZashiProposalRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "AnsweringZashiProposalRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "createZip321Proposal" -> proposal
                "clear" -> Unit
                else -> throw UnsupportedOperationException("unexpected ZashiProposalRepository call: ${method.name}")
            }
        } as ZashiProposalRepository

    /** A Keystone session whose signed proposal is [proposal]; submit succeeds. */
    private fun answeringKeystoneRepository(proposal: TransactionProposal): KeystoneProposalRepository =
        Proxy.newProxyInstance(
            KeystoneProposalRepository::class.java.classLoader,
            arrayOf(KeystoneProposalRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "AnsweringKeystoneProposalRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getTransactionProposal" -> proposal
                "submit" -> SubmitResult.Success(txIds = listOf("synthetic-txid"))
                "clear" -> Unit
                else -> throw UnsupportedOperationException("unexpected Keystone repository call: ${method.name}")
            }
        } as KeystoneProposalRepository

    /** The user backs out of the biometric prompt. */
    private class CancellingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest): Unit = throw BiometricsCancelledException()
    }

    private companion object {
        const val REQUEST_RECIPIENT = "usynthetic-payee"
        const val REQUEST_ZATOSHI = 100_000L
        const val AWAIT_MS = 10_000L
        const val NO_PREFILL_MS = 500L
    }
}
