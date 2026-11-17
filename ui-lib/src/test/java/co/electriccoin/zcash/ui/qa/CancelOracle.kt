package co.electriccoin.zcash.ui.qa

/**
 * Cancel-oracle — JVM/oracle harness for paid-send cancellation safety.
 *
 * Mirrors the PaymentDiag / TestHooks contract used by the on-device runner
 * (`tools/qa/runner/oracles.py::shakedown_oracle` + at-most-one-submit per logical
 * send) so reviewers can SHA-bind cancel evidence WITHOUT phones.
 *
 * Invariant (money): one logical send may produce at most one `proposal_submitted`
 * identity. User cancel before settlement must yield ZERO submissions. Retry after
 * a pre-submit cancel may authorize the SAME logical send once — never a second
 * broadcast. A deliberately double-submitted stream MUST fail the oracle
 * (negative control).
 *
 * Soft-fail note: missing biometric_result after cancel is an INFRA/harness issue
 * (Honor OEM Back), not fund_loss — see CANCEL_ORACLE_SOFT_FAIL.md. Fund_loss is
 * only when capture-healthy evidence shows a second submit / rebroadcast.
 *
 * This type lives in test source (harness), not production. Do not move into main.
 */
object CancelOracle {
    const val UNKNOWN_PID: String = "-"
    const val NONE_PID: String = "none"

    enum class Verdict {
        PASSED,
        FAILED,
        INFRA,
    }

    data class Event(
        val name: String,
        val pid: String? = null,
        val ok: Boolean? = null,
        val reason: String? = null,
        /** Optional logical-send key (amountZat or durable request id). */
        val logicalSendId: String? = null,
        val ts: Double = 0.0,
    )

    data class Assertion(
        val id: String,
        val description: String,
        val verdict: Verdict,
        val detail: String? = null,
    )

    fun combine(assertions: List<Assertion>): Verdict {
        if (assertions.any { it.verdict == Verdict.FAILED }) return Verdict.FAILED
        if (assertions.any { it.verdict == Verdict.INFRA }) return Verdict.INFRA
        return Verdict.PASSED
    }

    /**
     * Cancel-before-submit (SHAKEDOWN-shaped): created + displayed + bio shown,
     * cancel (`biometric_result ok=false`), and NO `proposal_submitted`.
     */
    fun evaluateCancelBeforeSubmit(
        events: List<Event>,
        captureHealthy: Boolean = true,
        requireBioResult: Boolean = true,
    ): List<Assertion> {
        val out = mutableListOf<Assertion>()

        val created = events.lastOrNull { it.name == "proposal_created" }
        out += requireEvent("co_created", "proposal_created observed", created, captureHealthy)

        val displayed = events.lastOrNull { it.name == "proposal_displayed" }
        out += requireEvent("co_displayed", "proposal_displayed observed", displayed, captureHealthy)
        if (displayed != null) {
            out +=
                check(
                    "co_displayed_pid",
                    "displayed pid is a known pid",
                    isKnownPid(displayed.pid),
                    detailBad = "displayed pid=${displayed.pid}",
                )
        }

        val shown = events.lastOrNull { it.name == "biometric_shown" }
        out += requireEvent("co_bio_shown", "biometric_shown observed", shown, captureHealthy)

        if (shown != null) {
            val result =
                events
                    .filter { it.name == "biometric_result" && it.ts >= shown.ts }
                    .minByOrNull { it.ts }
            if (requireBioResult) {
                out += requireEvent("co_bio_result", "biometric_result after prompt", result, captureHealthy)
                if (result != null) {
                    out +=
                        check(
                            "co_bio_cancelled",
                            "biometric_result.ok == false (cancelled)",
                            result.ok == false,
                            detailBad = "ok=${result.ok} reason=${result.reason}",
                        )
                }
            } else if (result == null && captureHealthy) {
                // Soft-fail path: Honor Back may leave no biometric_result — INFRA, not FAILED.
                out +=
                    Assertion(
                        id = "co_bio_result",
                        description = "biometric_result after prompt",
                        verdict = Verdict.INFRA,
                        detail = "missing biometric_result (soft-fail / OEM dismiss); not fund_loss alone",
                    )
            }
        }

        val submitted = events.filter { it.name == "proposal_submitted" }
        out +=
            if (captureHealthy) {
                check(
                    "co_no_submit",
                    "NO proposal_submitted (cancel before settlement)",
                    submitted.isEmpty(),
                    detailBad = "${submitted.size} proposal_submitted event(s)",
                )
            } else {
                Assertion(
                    id = "co_no_submit",
                    description = "NO proposal_submitted (cancel before settlement)",
                    verdict = Verdict.INFRA,
                    detail = "capture blind — cannot certify absence",
                )
            }

        return out
    }

    /**
     * Cancel before any proposal exists: empty created/submitted graph must stay empty.
     */
    fun evaluateCancelBeforeCreate(
        events: List<Event>,
        captureHealthy: Boolean = true,
    ): List<Assertion> {
        val created = events.filter { it.name == "proposal_created" }
        val submitted = events.filter { it.name == "proposal_submitted" }
        return listOf(
            if (captureHealthy) {
                check(
                    "co_precreate_no_created",
                    "no proposal_created before user ever started a send",
                    created.isEmpty(),
                    detailBad = "${created.size} proposal_created",
                )
            } else {
                Assertion(
                    "co_precreate_no_created",
                    "no proposal_created before user ever started a send",
                    Verdict.INFRA,
                    "capture blind",
                )
            },
            if (captureHealthy) {
                check(
                    "co_precreate_no_submit",
                    "no proposal_submitted when cancel precedes create",
                    submitted.isEmpty(),
                    detailBad = "${submitted.size} proposal_submitted",
                )
            } else {
                Assertion(
                    "co_precreate_no_submit",
                    "no proposal_submitted when cancel precedes create",
                    Verdict.INFRA,
                    "capture blind",
                )
            },
        )
    }

    /**
     * At most one `proposal_submitted` for a logical send. When [logicalSendId] is set,
     * only events tagged with that id (or whose pid has a field that is exactly the id) count.
     *
     * Round 7 (GPT Q5, Grok Q5, H5): the oracle never certifies what it could not see.
     *  - Two or more counted submits are proof of a violation: FAILED, even on a blind capture.
     *  - A blind capture ([captureHealthy] false) with zero or one counted submit cannot exclude an
     *    unobserved second one: INFRA, never PASSED.
     *  - A submit that carries no correlation at all (no logicalSendId tag, and a pid that does not name
     *    the id) may belong to this send. When counting it in could exceed one, the verdict is INFRA.
     *    A submit tagged with ANOTHER logical send's id is that send's and is not counted.
     */
    fun evaluateAtMostOneSubmit(
        events: List<Event>,
        logicalSendId: String? = null,
        captureHealthy: Boolean = true,
    ): List<Assertion> {
        val allSubmitted = events.filter { it.name == "proposal_submitted" }
        val submitted = allSubmitted.filter { e -> logicalSendId == null || isCorrelated(e, logicalSendId) }
        val uncorrelated =
            if (logicalSendId == null) {
                emptyList()
            } else {
                allSubmitted.filter { e -> e.logicalSendId == null && !isCorrelated(e, logicalSendId) }
            }
        val distinctPids = submitted.mapNotNull { it.pid }.distinct()
        return listOf(
            when {
                submitted.size > 1 ->
                    Assertion(
                        "co_one_submit",
                        "at most one proposal_submitted per logical send",
                        Verdict.FAILED,
                        "${submitted.size} submission(s) pids=$distinctPids",
                    )
                !captureHealthy ->
                    Assertion(
                        "co_one_submit",
                        "at most one proposal_submitted per logical send",
                        Verdict.INFRA,
                        "capture blind — ${submitted.size} observed, a further unobserved submit cannot be excluded",
                    )
                submitted.size + uncorrelated.size > 1 ->
                    Assertion(
                        "co_one_submit",
                        "at most one proposal_submitted per logical send",
                        Verdict.INFRA,
                        "${uncorrelated.size} submission(s) name no logical send; with ${submitted.size} " +
                            "correlated, at-most-one for $logicalSendId cannot be certified",
                    )
                else ->
                    Assertion("co_one_submit", "at most one proposal_submitted per logical send", Verdict.PASSED, null)
            },
            check(
                "co_one_submit_identity",
                "submitted pids collapse to a single identity when present",
                distinctPids.size <= 1,
                detailBad = "distinct pids=$distinctPids",
            ),
        )
    }

    /**
     * Tagged with [logicalSendId], or with a pid one of whose ':'-separated fields (seq:dest_fp:amountZat:kind)
     * IS the id. Round 7c (R6-4 review finding 20): a field that only contains the id is another send (amount
     * 100000 is not amount 10000); the substring match counted it in, which could fake a double submit or make an
     * uncorrelated submit look like this send's. The one rule for every evaluator.
     */
    private fun isCorrelated(
        e: Event,
        logicalSendId: String,
    ): Boolean = e.logicalSendId == logicalSendId || (e.pid != null && logicalSendId in e.pid.split(':'))

    /**
     * Retry-after-cancel: a cancel (`biometric_result ok=false`) may be followed by
     * at most one later `proposal_submitted` for the same logical send. Two or more
     * post-cancel submissions = rebroadcast / double-pay → FAILED.
     */
    fun evaluateRetryAfterCancel(
        events: List<Event>,
        logicalSendId: String? = null,
        captureHealthy: Boolean = true,
    ): List<Assertion> {
        val cancel =
            events.lastOrNull { it.name == "biometric_result" && it.ok == false }
                ?: return listOf(
                    if (captureHealthy) {
                        Assertion(
                            "co_retry_cancel_marker",
                            "cancel biometric_result present before retry analysis",
                            Verdict.FAILED,
                            "no biometric_result ok=false in stream",
                        )
                    } else {
                        Assertion(
                            "co_retry_cancel_marker",
                            "cancel biometric_result present before retry analysis",
                            Verdict.INFRA,
                            "capture blind / no cancel marker",
                        )
                    },
                )

        val postCancelSubs =
            events.filter { it.name == "proposal_submitted" && it.ts >= cancel.ts }.filter { e ->
                logicalSendId == null || isCorrelated(e, logicalSendId)
            }

        return listOf(
            check(
                "co_retry_at_most_one",
                "retry after cancel yields at most one proposal_submitted",
                postCancelSubs.size <= 1,
                detailBad = "${postCancelSubs.size} post-cancel submission(s)",
            ),
        ) + evaluateAtMostOneSubmit(events, logicalSendId, captureHealthy)
    }

    /**
     * Negative control helper: a stream that deliberately contains two submissions
     * for one logical send MUST combine to FAILED.
     */
    fun evaluateNegativeDoubleSubmit(
        events: List<Event>,
        logicalSendId: String? = null,
    ): List<Assertion> = evaluateAtMostOneSubmit(events, logicalSendId, captureHealthy = true)

    fun isKnownPid(pid: String?): Boolean =
        pid != null && pid != UNKNOWN_PID && pid != NONE_PID && pid.contains(':')

    private fun requireEvent(
        id: String,
        description: String,
        event: Event?,
        captureHealthy: Boolean,
    ): Assertion =
        when {
            event != null -> Assertion(id, description, Verdict.PASSED, "observed ${event.name}")
            captureHealthy -> Assertion(id, description, Verdict.FAILED, "missing event")
            else -> Assertion(id, description, Verdict.INFRA, "capture blind — event absent")
        }

    private fun check(
        id: String,
        description: String,
        ok: Boolean,
        detailBad: String,
    ): Assertion =
        if (ok) {
            Assertion(id, description, Verdict.PASSED, null)
        } else {
            Assertion(id, description, Verdict.FAILED, detailBad)
        }
}
