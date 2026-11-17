package co.electriccoin.zcash.ui.common.util

import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * QA test-infrastructure (debug-only): structured one-line payment-lifecycle events, emitted on the
 * `PaymentDiag` logcat tag so an on-device runner can machine-assert the ZC-101 invariant
 * (`submitted.pid == displayed.pid`) instead of trusting prose.
 *
 * Event vocabulary (all single-line, `event {k=v, ...}`):
 *  - `proposal_created {pid, kind, dest_fp, amountZat, outputCount}` — a proposal was built and
 *    stored in the shared repository cell.
 *  - `proposal_cell_overwritten {old_pid, new_pid, source}` — a NEW proposal replaced a non-null
 *    proposal in the shared cell. This is the ZC-101 hazard event.
 *  - `proposal_displayed {pid}` — a confirm screen bound this proposal instance into its UI state.
 *  - `biometric_shown {}` / `biometric_result {ok[, reason]}` — the spend-auth window.
 *  - `proposal_submitted {pid, result, txids}` — a proposal reached settlement.
 *
 * PROPOSAL IDENTITY (`pid`): a process-monotonic [AtomicLong] sequence is the PRIMARY identity,
 * corroborated by a short destination-hash prefix + amountZat + proposal kind, rendered as
 * `seq:destFp:amountZat:kind` (e.g. `3:9f2c41aa:1000:Zip321`). We deliberately do NOT use
 * `System.identityHashCode` as identity — identity hashes are not unique and can collide across
 * objects, which would forge a false pid match. Instead the pid is ATTACHED to the proposal object
 * at creation time in a small identity registry: a bounded deque of ([WeakReference], pid) pairs
 * matched by reference identity (`===`). Weak refs mean no proposal (which can be large) is kept
 * alive by diagnostics; `===` matching means a data-class `equals` collision between two distinct
 * proposals can never alias their pids; and because the SAME object reference flows
 * created -> displayed -> submitted (the ZC-101 fix pins the instance through the confirm
 * closures), lookups are stable across threads (the registry is lock-guarded).
 *
 * SECURITY: never logs a raw address, memo, full txid, or amount destination pairing beyond a
 * one-way 8-hex-char SHA-256 prefix of the destination (not reversible, not linkable on-chain);
 * txids go through [redactTxId]. Memos are never touched.
 *
 * RELEASE IMPACT: none. Every entry point is gated on [enabled] == `BuildConfig.DEBUG`; in release
 * builds every method is an immediate no-op (no registry writes, no hashing, no Log calls).
 * ReleaseHookAbsenceTest asserts this gate stays in place.
 */
object PaymentDiag {
    private const val TAG = "PaymentDiag"

    /**
     * pid returned for a NON-NULL proposal instance that was never registered / already evicted (or
     * in release builds): "we had a proposal but lost track of it". Distinct from [NONE_PID].
     */
    const val UNKNOWN_PID: String = "-"

    /**
     * pid emitted when a confirm screen deliberately binds NO proposal — the ZC-101 cross-type
     * fail-closed case (`proposal as? SwapTransactionProposal` was null after a non-swap overwrite).
     * DELIBERATELY DISTINCT from [UNKNOWN_PID] so a runner can tell "deliberate fail-closed display"
     * from "degraded tracking".
     */
    const val NONE_PID: String = "none"

    /**
     * Upper bound on tracked proposals. At capacity we evict CLEARED weak refs first and a live
     * entry only as a last resort (see [register]), and this bound is generous, so a soak /
     * injection storm cannot evict the live in-flight displayed proposal and degrade it to
     * [UNKNOWN_PID] (diagnostic data only).
     */
    private const val MAX_TRACKED = 256

    private const val DEST_FP_HEX_CHARS = 8

    /** Single debug gate — every public entry point returns immediately when this is false. */
    private val enabled: Boolean
        get() = BuildConfig.DEBUG

    private val seq = AtomicLong(0L)

    private class Entry(
        val ref: WeakReference<TransactionProposal>,
        val pid: String,
    )

    private val lock = Any()
    private val tracked = ArrayDeque<Entry>()

    // ── pid attachment ───────────────────────────────────────────────────────────────────────────

    /**
     * Attach a fresh pid to [proposal] (identity-keyed; see class doc). Returns the pid, or
     * [UNKNOWN_PID] in release builds. Safe to call from any thread.
     */
    fun register(proposal: TransactionProposal): String {
        if (!enabled) return UNKNOWN_PID
        val pid = buildPid(proposal)
        synchronized(lock) {
            tracked.addLast(Entry(WeakReference(proposal), pid))
            while (tracked.size > MAX_TRACKED) {
                // Evict a CLEARED weak ref first (its proposal is already gone, so nothing observable
                // is lost). Only if EVERY tracked entry is still live do we fall back to evicting the
                // oldest live one. This keeps an injection storm from evicting the live in-flight
                // displayed proposal — which would degrade its pid to UNKNOWN_PID mid-authorization.
                val clearedIndex = tracked.indexOfFirst { it.ref.get() == null }
                tracked.removeAt(if (clearedIndex >= 0) clearedIndex else 0)
            }
        }
        return pid
    }

    /**
     * The pid attached to this exact proposal INSTANCE (`===` match), or [UNKNOWN_PID] when the
     * instance was never registered / already evicted / null. Never invokes anything on
     * [proposal] itself, so it is safe against partially-constructed or test-double proposals.
     */
    fun pidOf(proposal: TransactionProposal?): String {
        if (!enabled || proposal == null) return UNKNOWN_PID
        synchronized(lock) {
            // Newest-first so a (theoretical) re-registration of the same instance resolves to the
            // most recent pid.
            for (i in tracked.indices.reversed()) {
                if (tracked[i].ref.get() === proposal) return tracked[i].pid
            }
        }
        return UNKNOWN_PID
    }

    // ── lifecycle events ─────────────────────────────────────────────────────────────────────────

    /** `proposal_created` — call right after the proposal is built and stored. */
    fun logProposalCreated(pid: String, proposal: TransactionProposal) {
        if (!enabled) return
        emit(
            "proposal_created {pid=$pid, kind=${kindOf(proposal)}, dest_fp=${destFpOf(proposal)}, " +
                "amountZat=${amountZatOf(proposal)}, outputCount=${outputCountOf(proposal)}}"
        )
    }

    /**
     * `proposal_cell_overwritten` — the ZC-101 hazard: a create replaced a NON-NULL proposal in the
     * shared observation cell. [source] names the creation path that caused the overwrite.
     */
    fun logCellOverwritten(oldPid: String, newPid: String, source: String) {
        if (!enabled) return
        emit("proposal_cell_overwritten {old_pid=$oldPid, new_pid=$newPid, source=$source}")
    }

    /**
     * `proposal_displayed` — the confirm screen bound this exact instance into its UI state. A NULL
     * [proposal] is the deliberate cross-type fail-closed case and emits the distinct [NONE_PID]
     * sentinel (not [UNKNOWN_PID], which means a non-null instance we lost track of).
     */
    fun logProposalDisplayed(proposal: TransactionProposal?) {
        if (!enabled) return
        val pid = if (proposal == null) NONE_PID else pidOf(proposal)
        emit("proposal_displayed {pid=$pid}")
    }

    /** `biometric_shown` — the spend-auth prompt was launched. */
    fun logBiometricShown() {
        if (!enabled) return
        emit("biometric_shown {}")
    }

    /** `biometric_result` — the spend-auth prompt resolved. */
    fun logBiometricResult(ok: Boolean, reason: String? = null) {
        if (!enabled) return
        emit("biometric_result {ok=$ok${reason?.let { ", reason=$it" } ?: ""}}")
    }

    /** `proposal_submitted` — [proposal] reached settlement with [result]. Txids are redacted. */
    fun logProposalSubmitted(proposal: TransactionProposal, result: SubmitResult) {
        if (!enabled) return
        val txIds =
            when (result) {
                is SubmitResult.Success -> result.txIds
                is SubmitResult.Failure -> result.txIds
                is SubmitResult.GrpcFailure -> result.txIds
                is SubmitResult.Partial -> result.txIds
                is SubmitResult.SentUnconfirmed -> result.txIds
            }
        emit(
            "proposal_submitted {pid=${pidOf(proposal)}, result=${result::class.java.simpleName}, " +
                "txids=[${txIds.joinToString(",") { it.redactTxId() }}]}"
        )
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** SINGLE emission chokepoint (ReleaseHookAbsenceTest pins the gate + the lone Log call). */
    private fun emit(event: String) {
        if (!enabled) return
        Log.i(TAG, event)
    }

    private fun buildPid(proposal: TransactionProposal): String =
        "${seq.incrementAndGet()}:${destFpOf(proposal)}:${amountZatOf(proposal)}:${kindOf(proposal)}"

    /** e.g. RegularTransactionProposal -> "Regular"; resilient to test doubles. */
    private fun kindOf(proposal: TransactionProposal): String =
        proposal.javaClass.simpleName
            .removeSuffix("TransactionProposal")
            .ifEmpty { "Unknown" }

    /**
     * One-way corroboration fingerprint of the destination: first [DEST_FP_HEX_CHARS] hex chars of
     * SHA-256(address). NEVER the address itself (LogRedaction rule: no raw addresses in logs).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun destFpOf(proposal: TransactionProposal): String =
        try {
            val destination = (proposal as? SendTransactionProposal)?.destination?.address
            if (destination.isNullOrEmpty()) {
                "none"
            } else {
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(destination.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(DEST_FP_HEX_CHARS)
            }
        } catch (_: Throwable) {
            // Diagnostics must never break a payment path.
            "err"
        }

    @Suppress("TooGenericExceptionCaught")
    private fun amountZatOf(proposal: TransactionProposal): Long =
        try {
            (proposal as? SendTransactionProposal)?.amount?.value ?: -1L
        } catch (_: Throwable) {
            -1L
        }

    /**
     * Corroboration only. The SDK's [cash.z.ecc.android.sdk.model.Proposal] does not expose a
     * per-output count — `transactionCount()` (number of proposed transactions) is the closest
     * observable, so that is what this field carries.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun outputCountOf(proposal: TransactionProposal): Int =
        try {
            proposal.proposal.transactionCount()
        } catch (_: Throwable) {
            -1
        }
}
