package co.electriccoin.zcash.ui.keystone

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F2 (fix-all 2.13.0, train T2; PLAN.md cross-lane correction 4): a multi-transaction proposal is refused for Keystone
 * BEFORE a PCZT is built from it.
 *
 * A TEX payment is proposed as two transactions (leg 1 to an ephemeral transparent address, leg 2 from it to the
 * exchange). A Keystone signs one PCZT, which carries one transaction: signing and submitting it sends leg 1 only,
 * and the funds then sit at the ephemeral address while the exchange is never paid. Upstream (Zodl) maps the SDK's
 * MultiStepProposalUnsupportedException to TexUnsupportedOnKSException; SDK 3.0.0 has no such exception, and at HEAD
 * [KeystoneProposalRepositoryImpl.createPCZTFromProposal] builds a PCZT from whatever proposal it holds. Only the
 * Wallet → Send form checks for a `tex1` recipient first (SendViewModel); a scanned payment request, a swap deposit and
 * every other Keystone creator do not. K0 closes Keystone chat signing; this is the Wallet-tab defence in depth.
 *
 * Pinned: a 2-step proposal is refused with TexUnsupportedOnKSException (reached by name, so this file compiles
 * before it exists), and the data source's createPcztFromProposal is never called (nothing is built or stored).
 * Control: a 1-step proposal still gets its PCZT.
 *
 * The proposals are synthetic protobuf bytes (steps with no inputs or payments), read by the SDK's own parser.
 */
class KeystoneRefusesMultiTxTest {
    @Test
    fun `a two-transaction proposal is refused before a PCZT is built`() =
        runBlocking {
            val dataSource = RecordingPcztDataSource(proposalWithSteps(2))
            val repository = KeystoneProposalRepositoryImpl(keystoneAccounts(), dataSource.proxy)
            repository.createZip321Proposal(SCANNED_URI, allowMultiPayment = false)

            val refusal =
                runCatching { repository.createPCZTFromProposal() }.exceptionOrNull()
                    ?: fail(
                        "a Keystone PCZT was built from a 2-transaction proposal (calls: ${dataSource.calls}); " +
                            "signing it would send leg 1 only",
                    )

            assertEquals(REFUSAL, refusal.javaClass.simpleName, "the refusal names the case: $refusal")
            assertTrue(
                "createPcztFromProposal" !in dataSource.calls,
                "nothing may be built for it: ${dataSource.calls}",
            )
        }

    /** Control, green before and after. */
    @Test
    fun `control - a one-transaction proposal still gets its PCZT`() =
        runBlocking {
            val dataSource = RecordingPcztDataSource(proposalWithSteps(1))
            val repository = KeystoneProposalRepositoryImpl(keystoneAccounts(), dataSource.proxy)
            repository.createZip321Proposal(SCANNED_URI, allowMultiPayment = false)

            repository.createPCZTFromProposal()

            assertTrue("createPcztFromProposal" in dataSource.calls, "calls: ${dataSource.calls}")
            assertEquals(PCZT_BYTES.toList(), repository.getProposalPCZT()?.toByteArray()?.toList())
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /** Answers only what the Keystone repository asks for a scanned request and its PCZT; records every call. */
    private class RecordingPcztDataSource(
        private val proposal: Proposal,
    ) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val zip321 =
            runBlocking {
                Zip321TransactionProposal(
                    destination = WalletAddress.Unified.new("usynthetic-exchange"),
                    amount = Zatoshi(PAYMENT_ZAT),
                    memo = Memo(""),
                    proposal = proposal,
                )
            }

        val proxy: ProposalDataSource =
            Proxy.newProxyInstance(ProposalDataSource::class.java.classLoader, arrayOf(ProposalDataSource::class.java)) {
                proxy,
                method,
                args,
                ->
                when (method.name) {
                    "toString" -> "RecordingPcztDataSource"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> {
                        calls += method.name
                        when (method.name) {
                            "createZip321Proposal" -> zip321
                            "createPcztFromProposal", "addProofsToPczt" -> Pczt(PCZT_BYTES)
                            else -> throw UnsupportedOperationException("not used in this test: ${method.name}")
                        }
                    }
                }
            } as ProposalDataSource
    }

    private fun keystoneAccounts(): AccountDataSource {
        val account =
            runBlocking {
                val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
                KeystoneAccount(
                    sdkAccount = AccountFixture.new(),
                    unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone-self"), zero),
                    transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone-self"), Zatoshi(0)),
                    isSelected = true,
                )
            }
        return Proxy.newProxyInstance(AccountDataSource::class.java.classLoader, arrayOf(AccountDataSource::class.java)) {
            proxy,
            method,
            args,
            ->
            when (method.name) {
                "toString" -> "KeystoneAccounts"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getSelectedAccount" -> account
                else -> throw UnsupportedOperationException("not used in this test: ${method.name}")
            }
        } as AccountDataSource
    }

    private companion object {
        const val REFUSAL = "TexUnsupportedOnKSException"

        const val SCANNED_URI = "zcash:usynthetic-exchange?amount=0.05"

        const val PAYMENT_ZAT = 5_000_000L

        val PCZT_BYTES = byteArrayOf(0x51, 0x51, 0x51)

        /** Proposal protobuf: proto_version = 1, fee_rule = ZIP 317, and [steps] empty steps (field 4). */
        fun proposalWithSteps(steps: Int): Proposal {
            val bytes = byteArrayOf(0x08, 0x01, 0x10, 0x03) + ByteArray(steps * 2) { if (it % 2 == 0) 0x22 else 0x00 }
            return Proposal.fromByteArray(bytes).also {
                check(it.transactionCount() == steps) { "fixture: the SDK reads ${it.transactionCount()} steps" }
            }
        }
    }
}
