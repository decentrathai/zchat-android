package co.electriccoin.zcash.ui.common.datasource

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RED pin for M-2: a shortfall on the ZIP 321 entry point is mislabelled as "couldn't be prepared".
 *
 * `ProposalDataSourceImpl.getOrThrow` recognises the SDK's "Insufficient balance" text only inside
 * `ProposalFromParametersException` (the `proposeSend` path). `createZip321Proposal` calls
 * `proposeFulfillingPaymentUri`, which wraps the SAME text in `ProposalFromUriException`, so the generic
 * catch turns a plain shortfall into `TransactionProposalNotCreatedException`. A scanned payment request
 * and every chat send (chat builds its own ZIP 321 URI) then show "try again" copy for a condition that no
 * retry can fix, and lose the SDK's have/need figures on the way.
 *
 * An instrumented test because `createZip321Proposal` parses the URI with org.zecdev.zip321, whose jar is
 * compiled for Java 21 while the JVM unit tests run on Java 17. All addresses are synthetic.
 */
class ProposalFailureMappingTest {
    private val dataSource = ProposalDataSourceImpl(ShortfallSynchronizerProvider())

    @Test
    @SmallTest
    fun uriShortfallIsInsufficientFunds() =
        runTest {
            assertFailsWith<InsufficientFundsException>(
                "the SDK said 'Insufficient balance (have $HAVE, need $NEED)' on the ZIP 321 path; that is a " +
                    "shortfall, not a proposal that could not be built",
            ) {
                dataSource.createZip321Proposal(
                    account = ownerAccount(),
                    zip321Uri = PAYMENT_URI,
                    allowMultiPayment = false,
                )
            }
        }

    /** The SDK's own figures ride on the exception, so the shortfall sheet can quote them. */
    @Test
    @SmallTest
    fun uriShortfallCarriesTheSdkFigures() =
        runTest {
            val e =
                assertFailsWith<InsufficientFundsException> {
                    dataSource.createZip321Proposal(
                        account = ownerAccount(),
                        zip321Uri = PAYMENT_URI,
                        allowMultiPayment = false,
                    )
                }
            assertEquals(Zatoshi(HAVE), e.have)
            assertEquals(Zatoshi(NEED), e.need)
        }

    /**
     * A synchronizer that refuses `proposeFulfillingPaymentUri` the way SDK 3.0.0 does when the wallet
     * cannot cover amount + fee. `validateAddress` answers Unified so the call reaches the proposal. Any
     * other call is a fixture bug and throws.
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
                    "validateAddress" -> AddressType.Unified
                    "proposeFulfillingPaymentUri" ->
                        throw TransactionEncoderException.ProposalFromUriException(RuntimeException(SDK_SHORTFALL_TEXT))
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(refusing)

        override suspend fun getSynchronizer(): Synchronizer = refusing

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val HAVE = 582_002L
        const val NEED = 587_002L

        /** The rust backend's shortfall text, as SDK 3.0.0 wraps it into the exception's root cause. */
        const val SDK_SHORTFALL_TEXT =
            "Error creating transaction proposal: Insufficient balance (have $HAVE, need $NEED including fee)"

        /** What Send all asked for on the owner's phone; the recipient is synthetic and never validated. */
        const val PAYMENT_URI = "zcash:u1syntheticshortfallpeer?amount=0.00572002"

        private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        /** The owner's phone after NU6.3: Ironwood 443,002 and sealed Orchard 139,000, nothing pending. */
        suspend fun ownerAccount() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(139_000),
                        balance(443_002),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
