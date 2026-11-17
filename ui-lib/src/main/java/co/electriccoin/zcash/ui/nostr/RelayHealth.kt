package co.electriccoin.zcash.ui.nostr

// RELAY-HEALTH (2.13.0, W-11/W-15): the types of the relay health rules. Pure (no Android, no network), so the rules
// are unit-tested on a fake clock; RelayHealthPolicy applies them and NostrRelayPool feeds and follows it.

/** A relay's health, from what the pool already sees (§3.2 of the RELAY-HEALTH spec). */
enum class RelayHealthState {
    /** An open + EOSE, an EVENT or an OK true since the last failure (or nothing known yet). */
    OK,

    /** 2 of the last 3 REQs without an EOSE in time, or 3 of the last 5 publishes without an answer. */
    SUSPECT,

    /** No successful open for the confirm time (10 min) while the network works. */
    DOWN,

    /** The relay CLOSED a read with auth-required, restricted: or blocked: (isReadBlockReason; for this process). */
    READ_BLOCKED,

    /** 3 publish refusals within 30 min, until an OK true. */
    WRITE_REFUSED,
}

/** What a health tick decided. */
sealed class RelayHealthDecision {
    /** No relay gave a good answer for 5 minutes: the phone is offline. No swap; the DOWN timers restart. */
    object Offline : RelayHealthDecision() {
        override fun toString(): String = "Offline"
    }

    /** Nothing to do now ([reason] is for the log). */
    data class Hold(
        val reason: String,
    ) : RelayHealthDecision()

    /** Probe [candidate]; if it answers, it takes [slot] (1 or 2) from [old]. */
    data class Replace(
        val slot: Int,
        val old: String,
        val candidate: String,
    ) : RelayHealthDecision()

    /** Probe the compat relay (nos.lol) while [current] holds slot [slot]; two OK probes in a row bring it back. */
    data class ProbeCompat(
        val slot: Int,
        val current: String,
    ) : RelayHealthDecision()
}

/**
 * The saved relay set (the app-scope "zchat_relay_set" file). Slot 0 is always ZChat's relay and is not saved.
 * [cooldownUntilMs]: relays swapped out and until when they may not come back; [swapTimesMs]: the swaps of the last
 * 24 h (the daily limit survives a restart); [compatOutSinceMs]: when the compat relay (nos.lol) left slot 2, or null
 * while it holds it.
 */
data class RelaySetState(
    val slot1: String,
    val slot2: String,
    val cooldownUntilMs: Map<String, Long> = emptyMap(),
    val swapTimesMs: List<Long> = emptyList(),
    val compatOutSinceMs: Long? = null,
) {
    companion object {
        /** After this long out of slot 2 the compat relay is no longer probed and leaves the floor set. */
        const val COMPAT_GIVE_UP_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * The active set a saved state gives, checked against [catalog]: slot 0 is always `defaults[0]`; a saved slot
         * that is not in the catalog (or is slot 0's relay) gets its default back; two equal slots get both defaults.
         */
        fun activeFrom(
            stored: RelaySetState?,
            defaults: List<String>,
            catalog: List<String>,
        ): List<String> {
            if (stored == null) return defaults
            val own = defaults[0]
            val slot1 = stored.slot1.takeIf { it in catalog && it != own } ?: defaults[1]
            val slot2 = stored.slot2.takeIf { it in catalog && it != own } ?: defaults[2]
            return if (slot1 == slot2) defaults else listOf(own, slot1, slot2)
        }

        /**
         * The relays whose `since` bound the seen list (ZchatPreferences.lowestNostrRelaySince): the active set, plus
         * the compat relay (`defaults[2]`) while a fallback holds slot 2 and it has been out less than 30 days, so its
         * older `since` holds the prune down until it can come back and replay from there.
         */
        fun floorSetFrom(
            stored: RelaySetState?,
            defaults: List<String>,
            catalog: List<String>,
            nowMs: Long,
        ): List<String> {
            val active = activeFrom(stored, defaults, catalog)
            val compat = defaults[2]
            if (compat in active) return active
            val out = stored?.compatOutSinceMs ?: return active + compat
            return if (nowMs - out < COMPAT_GIVE_UP_MS) active + compat else active
        }
    }
}

/** Where the pool keeps [RelaySetState] (ZchatPreferences in the app). Written only on a swap. */
interface RelaySetStore {
    fun load(): RelaySetState?

    /** Null clears the saved set (the debug RESET_RELAY_SET action). */
    fun save(state: RelaySetState?)
}

/**
 * The numbers and inputs of the relay health rules. A class with `var`s (and a no-argument constructor) so a test can
 * set a fake clock, local relay urls and short waits; the app uses the defaults ([NostrRelayPool] may shorten the waits
 * on a debug build through the RELAY_HEALTH_CONFIRM_SEC seam).
 */
class RelayHealthConfig {
    /** The public relays slots 1 and 2 may use, in order of preference. */
    var catalog: List<String> = RelayCatalog.PUBLIC

    var nowMs: () -> Long = { System.currentTimeMillis() }

    /** The seen list's floor (seconds, 0 = none): a relay swapped in starts at floor + 1. */
    var seenFloorSec: () -> Long = { 0L }

    var downAfterMs: Long = 10 * MIN
    var suspectAfterMs: Long = 30 * MIN
    var eoseTimeoutMs: Long = 15 * SEC
    var networkWindowMs: Long = 5 * MIN
    var swapGapMs: Long = 10 * MIN
    var swapGapFewWorkingMs: Long = 2 * MIN
    var maxSwapsPerDay: Int = 6
    var swappedOutCooldownMs: Long = 24 * HOUR
    var failedProbeCooldownMs: Long = 6 * HOUR
    var compatProbeEveryMs: Long = 30 * MIN
    var compatBlockedReprobeMs: Long = 24 * HOUR
    var compatGiveUpMs: Long = RelaySetState.COMPAT_GIVE_UP_MS
    var refusalsForWriteRefused: Int = 3
    var windowMs: Long = 30 * MIN
    var probeTimeoutMs: Long = 10 * SEC
    var firstCheckDelayMs: Long = 60 * SEC

    /**
     * T5 fixup RH-X1: while ZChat's relay (slot 0, never swapped) refuses our reads, the pause before it is asked again
     * (one REQ per subscription). The read block ends as soon as it answers a read.
     */
    var ownReadRetryMs: Long = 5 * MIN

    /**
     * ZC-406: a CLOSED of one of our reads (not a read block) that comes sooner than this after the last re-subscribe
     * on that relay gets one deferred re-subscribe at the end of this window. The window doubles for each such deferral
     * before the relay answers a read again, up to [resubMaxMs], and is back to this after an EOSE or EVENT from it.
     */
    var resubDebounceMs: Long = 15 * SEC
    var resubMaxMs: Long = 5 * MIN

    /** ZC-406: the pause before the re-subscribe that follows a CLOSED outside the window (3 s, as before). */
    var resubBackoffMs: Long = 3 * SEC

    /**
     * ZC-482: the clock the re-subscribe window ([resubDebounceMs], [resubMaxMs]) is measured on, in ms: monotonic
     * (System.nanoTime), the clock of the coroutine delay that waits the window out, so a wall-clock step (NTP, NITZ, the
     * user) neither shortens nor stretches it. Unlike [nowMs] it is no wall clock; a test may set a virtual-time clock.
     */
    var resubNowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }

    var outboxKeepMs: Long = 24 * HOUR
    var outboxMaxEntries: Int = 256
    var outboxMaxChars: Int = 1_000_000
    var outboxPerTick: Int = 20
    var outboxGapMinMs: Long = 1_000L
    var outboxGapMaxMs: Long = 3_000L

    companion object {
        const val SEC = 1_000L
        const val MIN = 60 * SEC
        const val HOUR = 60 * MIN
        private const val NANOS_PER_MS = 1_000_000L
    }
}
