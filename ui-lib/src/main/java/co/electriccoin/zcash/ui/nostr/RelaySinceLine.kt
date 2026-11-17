package co.electriccoin.zcash.ui.nostr

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * KI-24 part B — where each relay's inbox subscription may start (the NIP-01 `since` of its REQ). [NostrRelayPool]
 * asks [sinceFor] each time it builds a REQ for a relay, and reports each end of stored events ([onEose]).
 */
interface RelaySinceStore {
    /** The `since` (seconds) the next REQ to [relayUrl] carries, or null for none (the relay replays all it holds). */
    fun sinceFor(relayUrl: String): Long?

    /** The subscription on [relayUrl] reached the end of the stored events (EOSE) at [eoseWallSec]. */
    fun onEose(
        relayUrl: String,
        eoseWallSec: Long,
    )

    /**
     * RELAY-HEALTH: [relayUrl] was just swapped in; its first REQ may start at [sinceSec] (the seen list's floor + 1:
     * every wrap at or below the floor counts as seen anyway). A stored value only rises. Default: nothing.
     */
    fun seed(
        relayUrl: String,
        sinceSec: Long,
    ) = Unit
}

/**
 * KI-24 part B — the per-relay `since` line.
 *
 * Without it the inbox asks every relay for everything addressed to us, on every fresh subscription, and only the
 * bounded seen list stops old events. With it each relay is asked only from a point it has already served in full,
 * minus the NIP-59 jitter: a gift-wrap's created_at is back-dated by up to 48 h, so a wrap stored after that point may
 * carry a time up to 48 h before it. The point is EOSE wall time − 49 h ([MARGIN_SEC]).
 *
 * The line must never pass a message this app received but has not handled yet, or a restart would never get it
 * back: the relay replay is the only copy of a message no chat screen took (T1b-2: NostrChatBridge holds such messages
 * in memory and drops the oldest when full, and the watchdog then asks for a replay). So:
 *  - an EOSE while [isBacklogSettled] is false (a message held, one dropped and waiting for redelivery, or one still
 *    between the socket and the bridge) moves nothing, and forgets the earlier candidate;
 *  - a settled EOSE does not move the line to its own point at once. It keeps that point as a candidate, and the
 *    NEXT settled EOSE on the same relay stores the earlier candidate. The events that arrived before the first EOSE
 *    have by then gone through the whole path (the watchdog re-subscribes every two minutes when idle);
 *  - the stored value only rises and is never above now − 49 h ([Persistence.advance] enforces both);
 *  - the stored value is never above the lowest wrap time of a message handed to a chat screen's reader that no reader
 *    has handled and marked seen yet ([lowestUnhandledWrapSec], NostrChatBridge.lowestUnhandledWrapSec): a reader
 *    cancelled before it read a message, an unattributed ZBOOT, a group invite unmarked to handle later (fixup
 *    G-KI24-4). A relay keeps a wrap AT its `since`, so the line may stand at that wrap time;
 *  - a candidate from another wallet is never stored ([Persistence.scope]).
 * A relay with no stored value gets no `since` (the first run behaves as before).
 */
class RelaySinceLine(
    private val persistence: Persistence,
    private val isBacklogSettled: () -> Boolean,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val lowestUnhandledWrapSec: (nowSec: Long) -> Long? = { null },
) : RelaySinceStore {
    /** Where the line is kept (the "zchat_nostr_seen" file, with the seen list, so a wipe or a wallet move takes both). */
    interface Persistence {
        fun get(relayUrl: String): Long?

        /** Stores [sinceSec] for [relayUrl] if it is higher than the stored value and not above now − 49 h. */
        fun advance(
            relayUrl: String,
            sinceSec: Long,
        ): Boolean

        /** Changes whenever the wallet's stores are swapped (ZchatPreferences.walletScopeGeneration). */
        fun scope(): Long
    }

    private data class Candidate(
        val sinceSec: Long,
        val scope: Long,
    )

    private val candidates = ConcurrentHashMap<String, Candidate>()

    override fun sinceFor(relayUrl: String): Long? = runCatching { persistence.get(relayUrl) }.getOrNull()

    // RELAY-HEALTH: Persistence.advance only raises the value and never stores above now − 49 h.
    override fun seed(
        relayUrl: String,
        sinceSec: Long,
    ) {
        val stored = runCatching { persistence.advance(relayUrl, sinceSec) }.getOrDefault(false)
        if (stored) Log.d(TAG, "since line for $relayUrl seeded at $sinceSec (the seen list's floor + 1)")
    }

    override fun onEose(
        relayUrl: String,
        eoseWallSec: Long,
    ) {
        val settled = runCatching { isBacklogSettled() }.getOrDefault(false)
        if (!settled) {
            if (candidates.remove(relayUrl) != null) {
                Log.d(TAG, "since line for $relayUrl held: a relay message is not handled yet")
            }
            return
        }
        val now = nowSec()
        val scope = runCatching { persistence.scope() }.getOrDefault(Long.MIN_VALUE)
        val point = minOf(eoseWallSec, now) - MARGIN_SEC
        val earlier = candidates.put(relayUrl, Candidate(point, scope)) ?: return
        if (earlier.scope != scope) return
        // G-KI24-4: never past a message a reader was handed but has not handled (unknown: the line stays).
        val unhandled = runCatching { lowestUnhandledWrapSec(now) }.getOrElse { return } ?: Long.MAX_VALUE
        val proposed = minOf(earlier.sinceSec, now - MARGIN_SEC, unhandled)
        val stored = runCatching { persistence.advance(relayUrl, proposed) }.getOrDefault(false)
        if (stored) Log.d(TAG, "since line for $relayUrl moved to $proposed")
    }

    companion object {
        private const val TAG = "NostrSince"

        /** The 48 h NIP-59 jitter (Nip17.JITTER_WINDOW_SEC) plus one hour. */
        const val MARGIN_SEC = 49L * 60 * 60

        /**
         * The rule a [Persistence] applies: the value to store for a relay holding [stored] when [proposed] comes in,
         * or null to keep [stored]. The line only rises, and never above now − 49 h.
         */
        fun nextStored(
            stored: Long?,
            proposed: Long,
            nowSec: Long,
        ): Long? {
            val capped = minOf(proposed, nowSec - MARGIN_SEC)
            return if (capped > 0 && (stored == null || capped > stored)) capped else null
        }
    }
}
