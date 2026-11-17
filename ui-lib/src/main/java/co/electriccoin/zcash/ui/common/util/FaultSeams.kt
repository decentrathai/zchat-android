package co.electriccoin.zcash.ui.common.util

import co.electriccoin.zcash.ui.BuildConfig
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * QA test-infrastructure (fix-all 2.13.0, F0): the fault seams the 2.13.0 phone checks arm on a DEBUG build.
 *
 * Each entry names the batch whose phone check needs it. The seams marked "wired at F0" are read at their
 * production site now; the others are armable already and are read by their batch when it lands (F2, F3, F4).
 */
enum class FaultSeam {
    /**
     * F2, read by the one submit path (createStoreAndSubmit): the first submit of leg 1 hands the server a cut,
     * unreadable copy of it and reads as a gRPC failure. The SDK still records the leg's submit endpoint and
     * rebroadcasts the real stored bytes on its next sync; the later legs are submitted as usual. Every send while
     * armed.
     */
    SUBMIT_LEG1_GRPC_FAILURE,

    /** F2, read by createStoreAndSubmit: the process ends after create, before onStored and before any submit. */
    KILL_AFTER_CREATE_BEFORE_ON_STORED,

    /** F2, read by createStoreAndSubmit: every leg after the first is never submitted, so it stays AwaitingPlan. */
    KEEP_LEG2_AWAITING_PLAN,

    /** F4: the process is killed between the send-attempt marker and create (wired by F4). */
    KILL_AFTER_MARKER_BEFORE_CREATE,

    /** F4: the resolver's status lookup never answers (wired by F4). */
    BLOCK_GET_STATUS,

    /** F3: a TEX send is treated as stuck at its ephemeral address (wired by F3). */
    TEX_FORCE_STUCK,

    /** F3: turning Tor on fails (wired by F3's automatic Tor). */
    TOR_BOOTSTRAP_FAILS,

    /** F10, wired at F0: every open of an encrypted chat store fails as if the phone's Keystore did not answer. */
    KEYSTORE_OUT,

    /**
     * ZC-733-A, read beside KEYSTORE_OUT in the opener: every open of the contact book (value 1), the picture index (2),
     * the AI store (3), or of all three (any other value) fails as if the phone's Keystore did not answer. The chat
     * stores are not affected.
     */
    KEYSTORE_OUT_OTHER_STORES,

    /** KI-22, wired at F0: every group memo send waits this many milliseconds before it starts. */
    GROUP_SEND_DELAY_MS,

    /** KI-23, wired at F0: no relay publish is acked and no relay event is delivered. */
    BLOCK_RELAYS,

    /** KI-24, wired at F0: the relay-event dedup set keeps at most this many ids (release: 2000). */
    SEEN_NOSTR_CAP,

    /** KI-24, wired at F0: the on-chain KEX dedup set keeps at most this many txids (release: 500). */
    SEEN_KEX_CAP,

    /**
     * T1b-2: the relay at this position in the pool's list (0 = the first) is down: nothing published there counts as
     * an ack and nothing it sends is delivered. With DEFAULT_RELAYS, 2 is wss://nos.lol.
     */
    BLOCK_ONE_RELAY,

    /**
     * RELAY-HEALTH, read by NostrRelayPool: a bit mask over [slot 0's relay] + RelayCatalog.PUBLIC (bit 0
     * relay.zsend.xyz, bit 1 relay.primal.net, bit 2 nos.lol, bit 3 nostr.mom, …). Each relay it names cannot be
     * reached: its connects fail, a probe of it fails, an open socket to it is closed at the next health tick. It
     * follows the relay, not the slot.
     */
    RELAY_UNREACHABLE,

    /**
     * RELAY-HEALTH, read by NostrRelayPool: this many seconds replace DOWN's 10 minutes, SUSPECT's 30 minutes, both
     * swap spacings and the compat re-probe interval (cooldowns and the daily limit unchanged).
     */
    RELAY_HEALTH_CONFIRM_SEC,

    /**
     * TURN-CREDS, read by TurnCredentialsClient.get(): no call-relay credential. get() drops a cached credential and
     * returns null without a fetch, so a call placed or answered while armed gets no relay (STUN and direct only).
     * Value 1.
     */
    TURN_CREDS_UNAVAILABLE,

    /**
     * TURN-CREDS, read by VoiceCallManager.buildPeerConnection: ICE uses the relay only (IceTransportsType.RELAY), so a
     * call connects only through ZChat's call relay. Value 1.
     */
    TURN_RELAY_ONLY,
}

/**
 * The one registry of [FaultSeam]s. Every function returns at once unless [BuildConfig.DEBUG]: a release build
 * reads "not armed" everywhere.
 *
 * Only the `debug` source set arms or disarms a seam (the TestHooks receiver, and the start-up loader that re-arms
 * what was set before a restart). DebugFaultSeamsReleaseAbsenceTest pins that no release source does, that every
 * function here starts with the DEBUG gate, and that the switches are registered only in the debug manifest.
 */
object FaultSeams {
    private val armed = ConcurrentHashMap<FaultSeam, Long>()

    /** Stands in for a hard kill in JVM tests; on a phone it ends the process with no clean-up, as a kill does. */
    @Volatile
    internal var killer: () -> Unit = { Runtime.getRuntime().halt(KILLED_EXIT_CODE) }

    fun isArmed(seam: FaultSeam): Boolean {
        if (!BuildConfig.DEBUG) return false
        return armed.containsKey(seam)
    }

    /** The value [seam] was armed with, or null when it is not armed. */
    fun valueOf(seam: FaultSeam): Long? {
        if (!BuildConfig.DEBUG) return null
        return armed[seam]
    }

    /** Waits for the armed number of milliseconds, if [seam] is armed with a positive value. */
    suspend fun delayIfArmed(seam: FaultSeam) {
        if (!BuildConfig.DEBUG) return
        val millis = armed[seam] ?: return
        if (millis > 0) delay(millis)
    }

    /** Ends the process here, as a kill would, if [seam] is armed. */
    fun killHereIfArmed(seam: FaultSeam) {
        if (!BuildConfig.DEBUG) return
        if (armed.containsKey(seam)) killer()
    }

    /** Debug source set and tests only. */
    fun arm(
        seam: FaultSeam,
        value: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        armed[seam] = value
    }

    /** Debug source set and tests only. */
    fun disarm(seam: FaultSeam) {
        if (!BuildConfig.DEBUG) return
        armed.remove(seam)
    }

    /** Debug source set and tests only. */
    fun disarmAll() {
        if (!BuildConfig.DEBUG) return
        armed.clear()
    }

    /** What is armed now (for the receiver's result line). */
    fun snapshot(): Map<FaultSeam, Long> {
        if (!BuildConfig.DEBUG) return emptyMap()
        return armed.toMap()
    }

    private const val KILLED_EXIT_CODE = 137
}
