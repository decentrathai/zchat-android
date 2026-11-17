package co.electriccoin.zcash.ui.nostr

/**
 * RELAY-HEALTH (2.13.0, W-11/W-15): when the app swaps a relay. Pure: every signal and question carries the time, so
 * the rules run on a fake clock in tests. [NostrRelayPool] feeds it what it already sees (connects, REQ → EOSE,
 * EVENTs, CLOSED reasons, the OK of real publishes) and asks it on each watchdog tick.
 *
 * - Three relays are active: slot 0 is `defaults[0]` (ZChat's relay: the relay field of every KEX, ZBOOT, invite and
 *   request, and one of the two relays 2.12.4 reads) and is never swapped; slots 1 and 2 may be.
 * - Slot 2 is the compat slot: its preferred relay `defaults[2]` (nos.lol, the only public relay 2.12.4 reads) takes it
 *   back after two probes in a row succeed, 30 minutes apart, while a fallback holds it.
 * - A relay is replaced only when it is unavailable (DOWN, READ_BLOCKED, WRITE_REFUSED, or SUSPECT for 30 minutes),
 *   never for a better score; never while the phone looks offline (no relay gave a good answer for 5 minutes); at most
 *   once per 10 minutes (2 while fewer than two relays work) and 6 times a day; by the first catalog relay that is not
 *   active and not cooling down (24 h after it was swapped out, 6 h after a failed probe), after a probe.
 *
 * [confirmOverrideMs] (debug seam RELAY_HEALTH_CONFIRM_SEC, null in release) replaces the DOWN and SUSPECT waits, both
 * swap spacings and the compat re-probe interval; cooldowns are unchanged.
 *
 * T5 fixup RH-X1: READ_BLOCKED lasts for the process, except for slot 0, which is never swapped: its read block ends as
 * soon as it answers a read again (an EOSE or an EVENT; the pool asks it again every [RelayHealthConfig.ownReadRetryMs]).
 */
class RelayHealthPolicy(
    private val defaults: List<String>,
    private val config: RelayHealthConfig,
    private val confirmOverrideMs: () -> Long? = { null },
) {
    init {
        require(defaults.size == 3) { "three relays: slot 0 (pinned), slot 1, slot 2 (compat)" }
    }

    private val own: String = defaults[0]
    private val compat: String = defaults[2]

    private class Tracker {
        var connected = false
        var everOpened = false
        var unreachableSinceMs: Long? = null
        var lastGoodMs: Long? = null
        val pendingReqsMs = ArrayDeque<Long>()
        val reqOutcomes = ArrayDeque<Boolean>() // true = EOSE in time
        val publishOutcomes = ArrayDeque<Boolean>() // true = answered
        var suspectSinceMs: Long? = null
        var readBlocked = false
        val refusalsMs = ArrayDeque<Long>()
        val outcomes = ArrayDeque<Pair<Long, Boolean>>() // for the score
    }

    private val trackers = HashMap<String, Tracker>()
    private var active: MutableList<String> = defaults.toMutableList()
    private val cooldownUntilMs = HashMap<String, Long>() // swapped out (saved)
    private val probeCooldownUntilMs = HashMap<String, Long>() // failed probe (memory only)
    private val swapTimesMs = ArrayList<Long>()
    private var compatOutSinceMs: Long? = null
    private var lastCompatProbeMs: Long? = null
    private var compatOkStreak = 0

    private fun t(url: String): Tracker = trackers.getOrPut(url) { Tracker() }

    private fun downAfter(): Long = confirmOverrideMs() ?: config.downAfterMs

    private fun suspectAfter(): Long = confirmOverrideMs() ?: config.suspectAfterMs

    private fun swapGap(fewWorking: Boolean): Long = confirmOverrideMs() ?: if (fewWorking) config.swapGapFewWorkingMs else config.swapGapMs

    private fun compatEvery(): Long = confirmOverrideMs() ?: config.compatProbeEveryMs

    // ---- the set ----

    /** Takes the saved set (checked against the catalog; null = the defaults) and returns the active set. */
    @Synchronized
    fun restore(
        stored: RelaySetState?,
        nowMs: Long,
    ): List<String> {
        active = RelaySetState.activeFrom(stored, defaults, config.catalog).toMutableList()
        cooldownUntilMs.clear()
        swapTimesMs.clear()
        if (stored != null) {
            stored.cooldownUntilMs.filter { it.value > nowMs }.forEach { (url, until) -> cooldownUntilMs[url] = until }
            swapTimesMs += stored.swapTimesMs.filter { nowMs - it < DAY_MS }
        }
        compatOutSinceMs = if (compat in active) null else stored?.compatOutSinceMs ?: nowMs
        // The first compat probe is due one interval after nos.lol left (not at once after a restart).
        lastCompatProbeMs = compatOutSinceMs
        compatOkStreak = 0
        return active.toList()
    }

    @Synchronized
    fun activeUrls(): List<String> = active.toList()

    /** The relays whose `since` bound the seen list: the active ones, plus nos.lol while it is out (under 30 days). */
    @Synchronized
    fun floorSetUrls(nowMs: Long): List<String> {
        if (compat in active) return active.toList()
        val out = compatOutSinceMs ?: return active.toList() + compat
        return if (nowMs - out < config.compatGiveUpMs) active.toList() + compat else active.toList()
    }

    // ---- signals ----

    /**
     * A socket opened. T5 fixup RH-X4: it ends the DOWN wait and keeps the network gate open, but it is not an answer:
     * SUSPECT and the REQ and publish windows are left as they are (only an EOSE in time, an EVENT or an OK true or
     * duplicate: clears them), so a relay that opens and drops before it answers anything is replaced like one that
     * never answers. Reads asked on an earlier socket cannot be answered on this one (a drop already counted them).
     */
    @Synchronized
    fun onConnectOk(
        url: String,
        nowMs: Long,
    ) {
        val t = t(url)
        t.connected = true
        t.everOpened = true
        t.unreachableSinceMs = null
        t.lastGoodMs = nowMs
        t.pendingReqsMs.clear()
    }

    @Synchronized
    fun onConnectFail(
        url: String,
        nowMs: Long,
    ) {
        val t = t(url)
        t.connected = false
        if (t.unreachableSinceMs == null) t.unreachableSinceMs = nowMs
        t.pendingReqsMs.clear()
        outcome(t, nowMs, false)
    }

    /** The socket dropped. T5 fixup RH-X4: a read it cut off before its EOSE counts as one read unanswered in time. */
    @Synchronized
    fun onDisconnected(
        url: String,
        nowMs: Long,
    ) {
        val t = t(url)
        t.connected = false
        if (t.unreachableSinceMs == null) t.unreachableSinceMs = nowMs
        if (t.pendingReqsMs.isNotEmpty()) {
            t.pendingReqsMs.clear()
            reqOutcome(t, false)
            outcome(t, nowMs, false)
            checkSuspect(t, nowMs)
        }
    }

    @Synchronized
    fun onReqSent(
        url: String,
        nowMs: Long,
    ) {
        val t = t(url)
        t.pendingReqsMs.addLast(nowMs)
        while (t.pendingReqsMs.size > MAX_PENDING) t.pendingReqsMs.removeFirst()
    }

    @Synchronized
    fun onEose(
        url: String,
        nowMs: Long,
    ) {
        val t = t(url)
        if (url == own) t.readBlocked = false // RH-X1: ZChat's relay answered a read again
        val sent = t.pendingReqsMs.removeFirstOrNull()
        val inTime = sent == null || nowMs - sent <= config.eoseTimeoutMs
        if (inTime) {
            reqOutcome(t, true)
            good(t, nowMs)
        } else {
            // A late EOSE still proves the relay and the network answer, but the REQ counts as unanswered in time.
            t.lastGoodMs = nowMs
            reqOutcome(t, false)
            outcome(t, nowMs, false)
            checkSuspect(t, nowMs)
        }
    }

    @Synchronized
    fun onEvent(
        url: String,
        nowMs: Long,
    ) {
        val t = t(url)
        if (url == own) t.readBlocked = false // RH-X1: ZChat's relay served a read again
        good(t, nowMs)
    }

    /**
     * A CLOSED of one of our reads. @return true when it made the relay READ_BLOCKED just now.
     *
     * T5 fixup RH-X3: a CLOSED for another reason ("rate-limited:", "error:" …) that ends a read still waiting for its
     * EOSE counts as that read unanswered in time, so a relay that closes every read becomes SUSPECT (two of the last
     * three) and is replaced after 30 minutes. One such CLOSED alone is no verdict, and a CLOSED of a read the relay
     * already answered (an idle subscription it ends) counts for nothing.
     */
    @Synchronized
    fun onClosed(
        url: String,
        reason: String,
        nowMs: Long,
    ): Boolean {
        val t = t(url)
        val waiting = t.pendingReqsMs.removeFirstOrNull()
        if (!isReadBlockReason(reason)) {
            if (waiting != null) {
                reqOutcome(t, false)
                outcome(t, nowMs, false)
                checkSuspect(t, nowMs)
            }
            return false
        }
        outcome(t, nowMs, false)
        if (t.readBlocked) return false
        t.readBlocked = true
        return true
    }

    /** The answer to a real publish: [ok] with [reason], or no answer in time ([reason] null and [ok] false). */
    @Synchronized
    fun onOk(
        url: String,
        ok: Boolean,
        reason: String?,
        nowMs: Long,
    ) {
        val t = t(url)
        if (!ok && reason == null) {
            publishOutcome(t, false)
            outcome(t, nowMs, false)
            checkSuspect(t, nowMs)
            return
        }
        publishOutcome(t, true)
        val r = reason.orEmpty().trim().lowercase()
        when {
            ok || r.startsWith("duplicate:") -> {
                t.refusalsMs.clear()
                good(t, nowMs)
            }
            REFUSALS.any { r.startsWith(it) } -> {
                t.refusalsMs.addLast(nowMs)
                while (t.refusalsMs.size > MAX_REFUSALS_KEPT) t.refusalsMs.removeFirst()
                outcome(t, nowMs, false)
            }
            else -> Unit // rate-limited: and other transient answers
        }
    }

    // ---- questions ----

    @Synchronized
    fun state(
        url: String,
        nowMs: Long,
    ): RelayHealthState {
        val t = trackers[url] ?: return RelayHealthState.OK
        return when {
            t.readBlocked -> RelayHealthState.READ_BLOCKED
            !t.connected && t.unreachableSinceMs?.let { nowMs - it >= downAfter() } == true -> RelayHealthState.DOWN
            t.refusalsMs.count { nowMs - it < config.windowMs } >= config.refusalsForWriteRefused ->
                RelayHealthState.WRITE_REFUSED
            t.suspectSinceMs != null -> RelayHealthState.SUSPECT
            else -> RelayHealthState.OK
        }
    }

    @Synchronized
    fun isUnavailable(
        url: String,
        nowMs: Long,
    ): Boolean =
        when (state(url, nowMs)) {
            RelayHealthState.OK -> false
            RelayHealthState.SUSPECT -> t(url).suspectSinceMs?.let { nowMs - it >= suspectAfter() } == true
            else -> true
        }

    /** 0–100: good outcomes over all in the last 30 minutes (100 with none), 0 while unavailable. For logs and order. */
    @Synchronized
    fun score(
        url: String,
        nowMs: Long,
    ): Int {
        if (isUnavailable(url, nowMs)) return 0
        val t = trackers[url] ?: return 100
        val recent = t.outcomes.filter { nowMs - it.first < config.windowMs }
        if (recent.isEmpty()) return 100
        return recent.count { it.second } * 100 / recent.size
    }

    /** One watchdog tick: what to do now. */
    @Synchronized
    fun tick(nowMs: Long): RelayHealthDecision {
        trackers.values.forEach { expireReqs(it, nowMs) }
        val networkWorks = active.any { url -> trackers[url]?.lastGoodMs?.let { nowMs - it <= config.networkWindowMs } == true }
        if (!networkWorks) {
            // Offline (airplane, Doze, captive portal): nothing is judged, and every wait starts again.
            trackers.values.forEach { t ->
                if (!t.connected && t.unreachableSinceMs != null) t.unreachableSinceMs = nowMs
                if (t.suspectSinceMs != null) t.suspectSinceMs = nowMs
                t.pendingReqsMs.clear()
            }
            return RelayHealthDecision.Offline
        }
        val unavailable = listOf(1, 2).filter { isUnavailable(active[it], nowMs) }.sortedBy { score(active[it], nowMs) }
        if (unavailable.isNotEmpty()) {
            if (!rateAllows(nowMs)) return RelayHealthDecision.Hold("rate limit")
            for (slot in unavailable) {
                val candidate = candidateFor(nowMs) ?: continue
                return RelayHealthDecision.Replace(slot, active[slot], candidate)
            }
            return RelayHealthDecision.Hold("no healthy candidate")
        }
        if (compatDue(nowMs)) return RelayHealthDecision.ProbeCompat(2, active[2])
        return RelayHealthDecision.Hold("healthy")
    }

    /**
     * The result of a probe the last [tick] asked for. For a replacement candidate: true = take it (a failed probe
     * gives it 6 h). For the compat relay, while nos.lol is not given up: true = two OK probes in a row, it takes slot 2
     * back now. ZC-458: once it is given up (30 days out) nos.lol is a replacement candidate like any other.
     */
    @Synchronized
    fun onProbeResult(
        url: String,
        ok: Boolean,
        nowMs: Long,
    ): Boolean {
        if (url == compat && compat !in active && !compatGivenUp(nowMs)) {
            lastCompatProbeMs = nowMs
            if (!ok) {
                compatOkStreak = 0
                return false
            }
            // The probe is the inbox filter's shape: it read kind 1059 without AUTH, so the old read block is over.
            t(compat).readBlocked = false
            compatOkStreak++
            return compatOkStreak >= COMPAT_OK_PROBES && rateAllows(nowMs)
        }
        if (!ok) probeCooldownUntilMs[url] = nowMs + config.failedProbeCooldownMs
        return ok
    }

    /** Puts [newUrl] into [slot] and returns the set to save. */
    @Synchronized
    fun commitSwap(
        slot: Int,
        newUrl: String,
        nowMs: Long,
    ): RelaySetState {
        require(slot == 1 || slot == 2) { "slot 0 is never swapped" }
        val old = active[slot]
        active[slot] = newUrl
        swapTimesMs += nowMs
        swapTimesMs.removeAll { nowMs - it >= DAY_MS }
        if (old == compat) {
            compatOutSinceMs = nowMs
            lastCompatProbeMs = nowMs
            compatOkStreak = 0
        } else {
            cooldownUntilMs[old] = nowMs + config.swappedOutCooldownMs
        }
        if (newUrl == compat) {
            compatOutSinceMs = null
            lastCompatProbeMs = null
            compatOkStreak = 0
        }
        // The new relay starts with a clean record (what it did before it left, if anything, is old news).
        trackers.remove(newUrl)
        return savedState(nowMs)
    }

    /** Back to the defaults, cooldowns and swap history cleared (the debug RESET_RELAY_SET action). */
    @Synchronized
    fun reset(): List<String> {
        active = defaults.toMutableList()
        cooldownUntilMs.clear()
        probeCooldownUntilMs.clear()
        swapTimesMs.clear()
        compatOutSinceMs = null
        lastCompatProbeMs = null
        compatOkStreak = 0
        trackers.clear()
        return active.toList()
    }

    private fun savedState(nowMs: Long): RelaySetState =
        RelaySetState(
            slot1 = active[1],
            slot2 = active[2],
            cooldownUntilMs = cooldownUntilMs.filter { it.value > nowMs },
            swapTimesMs = swapTimesMs.toList(),
            compatOutSinceMs = compatOutSinceMs,
        )

    // ---- rules ----

    private fun working(nowMs: Long): Int = active.count { !isUnavailable(it, nowMs) }

    private fun rateAllows(nowMs: Long): Boolean {
        val lastDay = swapTimesMs.filter { nowMs - it < DAY_MS }
        if (lastDay.size >= config.maxSwapsPerDay) return false
        val last = lastDay.maxOrNull() ?: return true
        return nowMs - last >= swapGap(working(nowMs) < MIN_WORKING)
    }

    // What a relay did before it left the set is not held against it once its cooldown is over: the probe decides.
    private fun candidateFor(nowMs: Long): String? =
        config.catalog.firstOrNull { url ->
            url !in active &&
                url != own &&
                // nos.lol comes back through its own probes (compat rule), not as a replacement, until it is given up.
                !(url == compat && !compatGivenUp(nowMs)) &&
                (cooldownUntilMs[url] ?: 0L) <= nowMs &&
                (probeCooldownUntilMs[url] ?: 0L) <= nowMs
        }

    private fun compatGivenUp(nowMs: Long): Boolean = compatOutSinceMs?.let { nowMs - it >= config.compatGiveUpMs } == true

    private fun compatDue(nowMs: Long): Boolean {
        if (compat in active) return false
        compatOutSinceMs ?: return false
        if (compatGivenUp(nowMs)) return false
        if (compatOkStreak >= COMPAT_OK_PROBES && rateAllows(nowMs)) return true
        val every = if (t(compat).readBlocked) config.compatBlockedReprobeMs else compatEvery()
        val last = lastCompatProbeMs ?: return true
        return nowMs - last >= every
    }

    private fun good(
        t: Tracker,
        nowMs: Long,
    ) {
        t.lastGoodMs = nowMs
        t.suspectSinceMs = null
        t.reqOutcomes.clear()
        t.publishOutcomes.clear()
        outcome(t, nowMs, true)
    }

    private fun outcome(
        t: Tracker,
        nowMs: Long,
        good: Boolean,
    ) {
        t.outcomes.addLast(nowMs to good)
        while (t.outcomes.size > MAX_OUTCOMES) t.outcomes.removeFirst()
    }

    private fun reqOutcome(
        t: Tracker,
        inTime: Boolean,
    ) {
        t.reqOutcomes.addLast(inTime)
        while (t.reqOutcomes.size > REQ_WINDOW) t.reqOutcomes.removeFirst()
    }

    private fun publishOutcome(
        t: Tracker,
        answered: Boolean,
    ) {
        t.publishOutcomes.addLast(answered)
        while (t.publishOutcomes.size > PUBLISH_WINDOW) t.publishOutcomes.removeFirst()
    }

    private fun expireReqs(
        t: Tracker,
        nowMs: Long,
    ) {
        var expired = false
        while (t.pendingReqsMs.isNotEmpty() && nowMs - t.pendingReqsMs.first() > config.eoseTimeoutMs) {
            t.pendingReqsMs.removeFirst()
            reqOutcome(t, false)
            outcome(t, nowMs, false)
            expired = true
        }
        if (expired) checkSuspect(t, nowMs)
    }

    private fun checkSuspect(
        t: Tracker,
        nowMs: Long,
    ) {
        val reqsBad = t.reqOutcomes.count { !it } >= REQ_BAD
        val publishesBad = t.publishOutcomes.count { !it } >= PUBLISH_BAD
        if ((reqsBad || publishesBad) && t.suspectSinceMs == null) t.suspectSinceMs = nowMs
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MIN_WORKING = 2
        private const val COMPAT_OK_PROBES = 2
        private const val REQ_WINDOW = 3
        private const val REQ_BAD = 2
        private const val PUBLISH_WINDOW = 5
        private const val PUBLISH_BAD = 3
        private const val MAX_PENDING = 10
        private const val MAX_OUTCOMES = 200
        private const val MAX_REFUSALS_KEPT = 20

        /** OK false reasons (NIP-01 prefixes) that mean the relay will not take our events. */
        private val REFUSALS = listOf("blocked:", "restricted:", "auth-required:", "pow:", "payment", "invalid:")

        private const val AUTH_REQUIRED = "auth-required"
        private val READ_BLOCK = Regex("""(^|[\s:"])(restricted|blocked):""")

        /**
         * A CLOSED reason that means this relay will not serve our reads. ZC-460: the one rule, for the pool's
         * read block and for READ_BLOCKED here. `restricted:` and `blocked:` count in the NIP-01 machine-readable
         * prefix form only. "auth-required" counts anywhere in the text, with or without the colon and in any case,
         * which is wider than NIP-01: T1b-2 stopped counting such a relay's acknowledgements as delivery for any CLOSED
         * that names it (we do not do NIP-42 AUTH, so it serves us nothing), and a narrower rule here left that relay
         * in the set.
         */
        fun isReadBlockReason(reason: String): Boolean {
            val r = reason.lowercase()
            return AUTH_REQUIRED in r || READ_BLOCK.containsMatchIn(r)
        }
    }
}
