package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * Keystone PCZT/proposal binding (task #16; confirmed by cross-family review
 * ZCHAT-XF-2026-07-18-01, findings F2/F6/F5) — RED regression.
 *
 * ZC-101's invariant — "settle exactly the proposal the caller passed" — was fixed for the ZASHI
 * lane ([ZashiProposalRepositoryToctouTest]) but NOT for Keystone:
 *
 *   * [KeystoneProposalRepositoryImpl.submit] accepts an explicit `proposal` argument and then
 *     NEVER USES IT — it settles the process-wide singleton fields `pcztWithProofs` /
 *     `pcztWithSignatures`;
 *   * [KeystoneProposalRepositoryImpl.createPCZTFromProposal] builds the PCZT from the SHARED
 *     `transactionProposal` cell, not from an explicit argument.
 *
 * So the same TOCTOU shape as ZC-101 lives one layer down: a concurrent Keystone proposal
 * creation (B) overwrites the cell AND the PCZT singletons between A's create and A's submit, and
 * `submit(A)` then broadcasts B's bytes while every caller-side record (ZC-101's captured
 * proposal, swap metadata attribution) says A. Not exploitable today (ZC-102 blocks the
 * background auto-ack vector; the Keystone hardware displays the true transaction) — this is a
 * defence-in-depth money-path invariant.
 *
 * INVARIANT ENCODED HERE (fail-closed binding): `submit(proposal)` must broadcast ONLY bytes that
 * were built FOR that proposal. If the live PCZT singletons belong to a different proposal, submit
 * must fail closed — no broadcast, no silent success — never settle whichever bytes happen to be
 * in the singletons.
 *
 * The test drives the REAL [KeystoneProposalRepositoryImpl] with a hand-written recording
 * [ProposalDataSource] (the settlement seam, stable across any fix) and asserts on WHAT REACHES
 * `submitTransaction(pcztWithProofs, pcztWithSignatures)` — a thrown exception alone is not
 * enough; the proof is that no foreign bytes were broadcast.
 *
 * JVM note: the only production writer of `pcztWithSignatures` is `parsePCZT(ur)`, which decodes
 * the hardware wallet's UR through the Keystone SDK — not drivable in a plain JVM unit test (the
 * ZC-102 test avoided it for the same reason). The "user scanned the signed QR" step is therefore
 * injected reflectively into that exact field; without it, submit() at HEAD fails early on
 * "PCZT with signatures is null" and the singleton settlement under test is unreachable.
 *
 * All addresses/memos/URIs/PCZT bytes are SYNTHETIC placeholders — nothing parses or validates.
 */
class KeystonePcztBindingToctouTest {

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private class Harness(
        val dataSource: RecordingKeystonePcztDataSource,
        val repository: KeystoneProposalRepositoryImpl,
    )

    private suspend fun harness(): Harness {
        val dataSource = RecordingKeystonePcztDataSource()
        val repository =
            KeystoneProposalRepositoryImpl(
                accountDataSource = FakeKeystoneAccountDataSource(syntheticKeystoneAccount()),
                proposalDataSource = dataSource,
            )
        return Harness(dataSource, repository)
    }

    /**
     * Creates proposal A and builds its PCZT — the state after which the user walks off to sign on
     * the hardware device. Awaits the background proofs job so the session is fully in flight.
     */
    private suspend fun Harness.openSigningSessionForA(): Zip321TransactionProposal {
        val created = repository.createZip321Proposal(URI_A, allowMultiPayment = true)
        assertSame("fixture sanity: repository must return proposal A", dataSource.proposalA, created)
        repository.createPCZTFromProposal()
        withTimeout(AWAIT_MS) { dataSource.proofsReadyA.await() }
        return created
    }

    /**
     * The user scans the Keystone device's signed-PCZT QR. Production sets the private
     * `pcztWithSignatures` field via `parsePCZT(ur)` -> KeystoneSDK UR decode, which cannot run on
     * the JVM — so the scan result is injected into that exact field (see class KDoc).
     */
    private fun Harness.scanHardwareSignature(signed: Pczt) {
        val field = KeystoneProposalRepositoryImpl::class.java.getDeclaredField("pcztWithSignatures")
        field.isAccessible = true
        field.set(repository, signed)
    }

    // ── The regression (RED at HEAD) ─────────────────────────────────────────────────────────────

    /**
     * RED at HEAD. The ZC-101 race replayed one layer down, at the Keystone PCZT singletons:
     *
     *   1. proposal A is created and its PCZT built (the confirm screen shows A; the user signs
     *      A's redacted PCZT on the hardware device and scans the signed QR back);
     *   2. a concurrent Keystone create builds proposal B and ITS PCZT on the SAME process-wide
     *      singletons — `transactionProposal`, `proposalPczt` and `pcztWithProofs` now hold B;
     *   3. the A-send calls `submit(A)` — the ZC-101 discipline: the caller passes exactly the
     *      proposal its send created.
     *
     * The invariant: A's submit must NOT broadcast B's bytes. At HEAD `submit(A)` ignores its
     * argument and settles the singletons, handing the settlement seam B's PCZT-with-proofs (with
     * A's signatures — a chimera that misattributes what actually got sent): RED.
     */
    @Test
    fun `submit(A) must not broadcast another proposal's PCZT bytes after a concurrent Keystone create`() =
        runBlocking {
            val h = harness()

            // 1. Proposal A: create -> PCZT -> proofs -> the user scans the device's signed QR.
            val proposalA = h.openSigningSessionForA()
            h.scanHardwareSignature(h.dataSource.signedA)

            // 2. A concurrent Keystone create (same singleton repository) builds proposal B and
            //    overwrites the shared cell AND the PCZT singletons — the ZC-101 shape, one layer
            //    down.
            h.repository.createZip321Proposal(URI_B, allowMultiPayment = true)
            h.repository.createPCZTFromProposal()
            withTimeout(AWAIT_MS) { h.dataSource.proofsReadyB.await() }
            assertSame(
                "mechanism sanity: the singleton cell now holds proposal B",
                h.dataSource.proposalB,
                h.repository.transactionProposal.value,
            )

            // 3. The A-send submits, passing ITS proposal (the ZC-101 caller discipline).
            val outcome = runCatching { h.repository.submit(proposalA) }
            val outcomeDescription =
                outcome.fold(
                    onSuccess = { "returned $it" },
                    onFailure = { "threw ${it::class.simpleName}: ${it.message}" },
                )

            // INVARIANT (fail-closed binding): nothing that is not A's may reach the broadcast
            // seam. Submit may refuse (throw / SubmitResult.Failure) — but it must not settle B's
            // bytes. Asserting on the recorded broadcasts, not merely on "it threw".
            val foreignBroadcasts =
                h.dataSource.broadcasts.filterNot { it.isEntirely(h.dataSource.signedA, h.dataSource.pcztA) }
            assertEquals(
                "submit(A) [outcome: $outcomeDescription] broadcast bytes NOT bound to proposal A — " +
                    "the Keystone singleton PCZT pair settled whatever a concurrent create left " +
                    "behind; submit must fail closed on a proposal/PCZT binding mismatch",
                emptyList<String>(),
                foreignBroadcasts.map { h.dataSource.describe(it) },
            )

            // And no SILENT success: if A's bytes were never broadcast, neither the return value
            // nor the observable submit state may claim success.
            val aWasBroadcast = h.dataSource.broadcasts.any { it.isEntirely(h.dataSource.signedA, h.dataSource.pcztA) }
            if (!aWasBroadcast) {
                assertFalse(
                    "submit(A) reported Success without broadcasting A's bytes (silent drop)",
                    outcome.getOrNull() is SubmitResult.Success,
                )
                val state = h.repository.submitState.value
                assertFalse(
                    "submitState claims Success although A's bytes were never broadcast",
                    state is SubmitProposalState.Result && state.submitResult is SubmitResult.Success,
                )
            }
        }

    // ── Control (GREEN at HEAD and after the fix) ────────────────────────────────────────────────

    /**
     * Control: the normal, un-interleaved Keystone flow — create A, build A's PCZT, scan A's
     * signature, submit(A) — broadcasts A's bytes and succeeds. Pins that the binding fix cannot
     * be satisfied by breaking Keystone submission outright, and proves the harness wiring (real
     * repository, PCZT seam, proofs await, signature injection) is sound.
     */
    @Test
    fun `control - without interleaving submit(A) broadcasts A's bytes and succeeds`() =
        runBlocking {
            val h = harness()

            val proposalA = h.openSigningSessionForA()
            h.scanHardwareSignature(h.dataSource.signedA)

            val result = h.repository.submit(proposalA)

            assertTrue("the normal Keystone submit must succeed, got $result", result is SubmitResult.Success)
            assertEquals("exactly one broadcast must reach the settlement seam", 1, h.dataSource.broadcasts.size)
            assertEquals(
                "the broadcast bytes must be A's PCZT pair",
                "proofs=${LABEL_PCZT_A}, signatures=${LABEL_SIGNED_A}",
                h.dataSource.describe(h.dataSource.broadcasts.single()),
            )
        }

    private companion object {
        const val AWAIT_MS = 10_000L
    }

    /**
     * F-1 companion (from the adversarial review of ZCHAT-XF-2026-07-18-01): the binding check
     * ALONE is not enough. Hardware signatures are only ever valid for the PCZT they were produced
     * from, so a rebind must invalidate them -- otherwise:
     *
     *   1. A's PCZT is built and the user scans A's signed QR (`pcztWithSignatures` = signedA);
     *   2. a concurrent Keystone create rebinds the singletons (and the binding) to B;
     *   3. the caller legitimately submits B -- the proposal the PCZT IS bound to -- so the
     *      binding check PASSES, and the seam receives B's proofs paired with A's STALE
     *      signatures: the same chimera, one step later.
     *
     * Because the submitted proposal matches the binding, nothing but the signature-staleness rule
     * can catch this one.
     */
    @Test
    fun `submit(B) must not broadcast B's proofs paired with A's stale hardware signatures`() =
        runBlocking {
            val h = harness()
            h.openSigningSessionForA()
            h.scanHardwareSignature(h.dataSource.signedA) // user signs A on the hardware device

            // A concurrent Keystone create rebinds the singletons (and the binding) to B.
            h.repository.createZip321Proposal(URI_B, allowMultiPayment = true)
            h.repository.createPCZTFromProposal()
            withTimeout(AWAIT_MS) { h.dataSource.proofsReadyB.await() }

            val outcome = runCatching { h.repository.submit(h.dataSource.proposalB) }

            val stale =
                h.dataSource.broadcasts
                    .filter {
                        it.pcztWithSignatures.toByteArray()
                            .contentEquals(h.dataSource.signedA.toByteArray())
                    }.map { h.dataSource.describe(it) }
            assertEquals(
                "submit(B) [outcome: $outcome] broadcast bytes carrying A's STALE hardware " +
                    "signatures -- a rebind must invalidate signatures scanned for the previous " +
                    "proposal, so submit fails closed until the user signs THIS PCZT",
                emptyList<String>(),
                stale
            )
        }
}

// ── Constants (all synthetic) ────────────────────────────────────────────────────────────────────

/** Proposal A: the Keystone send the user confirms and signs on the hardware device. */
private const val URI_A = "zcash:usynthetic-keystone-peer?amount=0.05&memo=c3ludGhldGljLXNpZ25pbmc"

/** Proposal B: the concurrent Keystone create that overwrites the singletons. */
private const val URI_B = "zcash:usynthetic-concurrent-peer?amount=0.00001&memo=c3ludGhldGljLWFjaw"

private const val LABEL_PCZT_A = "proposal-A's PCZT (the send the user authorized and signed)"
private const val LABEL_PCZT_B = "proposal-B's PCZT (the concurrent Keystone create)"
private const val LABEL_SIGNED_A = "A's hardware-signed PCZT"

// ── Fakes (hand-written; no mockk, no Robolectric) ───────────────────────────────────────────────

/**
 * The seam under the REAL [KeystoneProposalRepositoryImpl]. Maps each ZIP-321 URI to a pre-built
 * distinct proposal, each proposal to a distinct PCZT byte payload, and RECORDS every PCZT pair
 * that reaches the broadcast call. The repository clones PCZTs before proofing, so classification
 * is by byte CONTENT, not identity.
 */
private class RecordingKeystonePcztDataSource : ProposalDataSource {
    val proposalA = runBlocking { syntheticZip321Proposal("usynthetic-keystone-peer", amount = 5_000_000L, memo = "synthetic-user-payment") }
    val proposalB = runBlocking { syntheticZip321Proposal("usynthetic-concurrent-peer", amount = 1_000L, memo = "synthetic-ack") }

    /** Distinct byte payloads so the test can tell WHOSE bytes the singletons hold. */
    val pcztA = Pczt(byteArrayOf(0x11, 0x11, 0x11))
    val pcztB = Pczt(byteArrayOf(0x22, 0x22, 0x22))

    /** What the hardware device hands back for A (parsePCZT's would-be output). */
    val signedA = Pczt(byteArrayOf(0x1A, 0x1A, 0x1A))

    /** Completed when the repository's background proofs job has proofed the respective PCZT. */
    val proofsReadyA = CompletableDeferred<Unit>()
    val proofsReadyB = CompletableDeferred<Unit>()

    class Broadcast(val pcztWithProofs: Pczt, val pcztWithSignatures: Pczt) {
        fun isEntirely(signed: Pczt, proofed: Pczt): Boolean =
            pcztWithProofs.toByteArray().contentEquals(proofed.toByteArray()) &&
                pcztWithSignatures.toByteArray().contentEquals(signed.toByteArray())
    }

    val broadcasts = mutableListOf<Broadcast>()

    fun describe(broadcast: Broadcast): String =
        "proofs=${labelOf(broadcast.pcztWithProofs)}, signatures=${labelOf(broadcast.pcztWithSignatures)}"

    private fun labelOf(pczt: Pczt): String {
        val bytes = pczt.toByteArray()
        return when {
            bytes.contentEquals(pcztA.toByteArray()) -> LABEL_PCZT_A
            bytes.contentEquals(pcztB.toByteArray()) -> LABEL_PCZT_B
            bytes.contentEquals(signedA.toByteArray()) -> LABEL_SIGNED_A
            else -> "unknown PCZT bytes (fixture bug)"
        }
    }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal =
        when (zip321Uri) {
            URI_A -> proposalA
            URI_B -> proposalB
            else -> error("unexpected ZIP-321 URI in this test")
        }

    override suspend fun createPcztFromProposal(
        account: WalletAccount,
        proposal: Proposal,
    ): Pczt =
        when {
            proposal === proposalA.proposal -> pcztA
            proposal === proposalB.proposal -> pcztB
            else -> error("unexpected proposal in this test")
        }

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt {
        // The repository proofs a CLONE — match by content, not identity.
        when {
            pczt.toByteArray().contentEquals(pcztA.toByteArray()) -> proofsReadyA.complete(Unit)
            pczt.toByteArray().contentEquals(pcztB.toByteArray()) -> proofsReadyB.complete(Unit)
        }
        return pczt
    }

    override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult {
        broadcasts += Broadcast(pcztWithProofs = pcztWithProofs, pcztWithSignatures = pcztWithSignatures)
        return SubmitResult.Success(txIds = listOf("synthetic-txid"))
    }

    // Unused in this scenario.
    override suspend fun createProposal(account: WalletAccount, send: ZecSend): RegularTransactionProposal =
        error("not used in this test")

    override suspend fun createExactInputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal = error("not used in this test")

    override suspend fun createExactOutputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal = error("not used in this test")

    override suspend fun createShieldProposal(account: WalletAccount): ShieldTransactionProposal =
        error("not used in this test")

    override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
        error("not used in this test")

    override suspend fun probeFee(account: WalletAccount, send: ZecSend): FeeProbe =
        error("not used in this test")

    override suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe =
        error("not used in this test")

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult =
        error("not used in this test")

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = error("not used in this test")
}

/** Mirrors the (file-private) ZC-102 Keystone account fakes: a selected hardware-wallet account. */
private suspend fun syntheticKeystoneAccount(): KeystoneAccount {
    val zeroBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
    return KeystoneAccount(
        // The SDK's own public test fixture — all values are the fixture's synthetic defaults.
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone-self"), zeroBalance),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone-self"), Zatoshi(0)),
        isSelected = true,
    )
}

private class FakeKeystoneAccountDataSource(
    private val account: KeystoneAccount,
) : AccountDataSource {
    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
    override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
    override val zashiAccount: Flow<ZashiAccount?> = flowOf(null)

    override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

    override suspend fun getSelectedAccount(): WalletAccount = account

    override suspend fun getZashiAccount(): ZashiAccount = error("the KEYSTONE account is selected in this test")

    override suspend fun selectAccount(account: Account) = Unit

    override suspend fun selectAccount(account: WalletAccount) = Unit

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit

}
