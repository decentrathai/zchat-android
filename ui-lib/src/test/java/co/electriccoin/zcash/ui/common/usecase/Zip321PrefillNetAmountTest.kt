package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * RED pin for Z-1: a scanned ZIP 321 request is prefilled with its fee attached, and the Send form
 * subtracts that fee from the amount the REQUEST asked for.
 *
 * `OnZip321ScannedUseCase` (SEND and HOMEPAGE flows) hands the Send screen
 * `PrefillSendData.All(amount = proposal.amount, fee = proposal.totalFeeRequired())`. `proposal.amount`
 * is already the net amount the payee asked for, but `AndroidSend` treats a non-null fee as "this amount
 * includes the fee" and fills `amount - fee`. Review → back → Send then pays the payee less than
 * requested, by exactly the fee. Upstream Zashi fixed the same code in b4edd6f66 by prefilling
 * `fee = null`: only a resend from transaction history, whose amount is gross, carries a fee.
 *
 * The synthetic SDK proposal has no steps, so its fee is 0 — still non-null, which is the defect.
 *
 * Harness constraint: the org.zecdev.zip321 jar (PaymentRequest, ZIP321) is compiled for Java 21, and the
 * JVM unit tests run on Java 17, so this test must never load it. The scan result is therefore allocated
 * without running its constructor (no PaymentRequest, no URI), and the Zashi repository is a dynamic Proxy
 * that answers the scan with a prepared proposal. The success path reads neither field of the scan
 * result; it only forwards the URI to the repository.
 */
class Zip321PrefillNetAmountTest {
    @Test
    fun scannedPrefillCarriesNoFee() = runBlocking { assertScannedPrefillCarriesNoFee(ScanFlow.SEND) }

    @Test
    fun scannedPrefillFromHomepageCarriesNoFee() = runBlocking { assertScannedPrefillCarriesNoFee(ScanFlow.HOMEPAGE) }

    private suspend fun assertScannedPrefillCarriesNoFee(flow: ScanFlow) {
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val router = Zc101RecordingNavigationRouter()
        val prefillSend = PrefillSendUseCase()
        val useCase =
            OnZip321ScannedUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    answeringZashiRepository(syntheticZip321Proposal(REQUEST_RECIPIENT, REQUEST_ZATOSHI, "")),
                accountDataSource = accountDataSource,
                navigationRouter = router,
                prefillSend = prefillSend,
                navigateToErrorUseCase = NavigateToErrorUseCase(router),
            )

        withTimeout(PREFILL_TIMEOUT_MS) {
            // The prefill bus is a rendezvous channel: the Send screen must already be listening.
            val prefill = async(Dispatchers.Default) { prefillSend().first() }
            yield()
            useCase(scanResultWithoutPaymentRequest(), ScanArgs(flow = flow, isScanZip321Enabled = true))
            val sent = assertIs<PrefillSendData.All>(prefill.await(), "the scan must prefill the Send form")

            assertEquals(REQUEST_ZATOSHI, sent.amount.value, "the prefill amount is the payee's requested amount")
            assertNull(
                sent.fee,
                "a scanned request's amount is NET; prefilling fee=${sent.fee} makes the Send form subtract it " +
                    "and underpay the payee after Review → back → Send",
            )
        }
    }

    /**
     * A `Zip321ParseUriValidation.Valid` with both fields unset. Its constructor would require a
     * PaymentRequest, and reflecting on its fields would load that Java 21 class, so it is allocated
     * through sun.misc.Unsafe instead.
     */
    private fun scanResultWithoutPaymentRequest(): Zip321ParseUriValidation.Valid {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, Zip321ParseUriValidation.Valid::class.java) as Zip321ParseUriValidation.Valid
    }

    /** Answers any ZIP 321 proposal request with [proposal]; the scan's URI is not inspected. */
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

    private companion object {
        const val REQUEST_RECIPIENT = "usynthetic-payee"
        const val REQUEST_ZATOSHI = 100_000L
        const val PREFILL_TIMEOUT_MS = 10_000L
    }
}
