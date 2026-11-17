package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.nostr.NostrRelayPool
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R5 (fix-all 2.13.0, train T5; SPEC.md §3.1, §3.7.2): the seen list's lowest `since` counts the relays
 * the phone actually reads, plus nos.lol while it is out of slot 2.
 *
 * At HEAD ZchatPreferences.lowestNostrRelaySince() takes the minimum over NostrRelayPool.DEFAULT_RELAYS. Once the pool
 * swaps a relay, that is the wrong set: a relay the phone reads is left out (its lower `since` does not hold the prune
 * down, so the floor can pass wraps that relay still serves: a message it alone holds counts as seen and is lost), and
 * a relay it no longer reads keeps counting. Wanted: the saved relay set (zchat_relay_set: slot 0 ZChat's relay, the
 * saved slots 1 and 2) plus nos.lol while a fallback holds slot 2; with nothing saved, DEFAULT_RELAYS as today.
 *
 * Observed through the real ZchatPreferencesImpl (in-memory files): one prune step of handled wraps, some older than the
 * lowest since and some between it and the next, then whether a never-handled wrap between them counts as seen. The
 * last two cases are controls (today's behaviour with nothing saved) and pass before the fix too.
 */
class LowestSinceFollowsTheFloorSetTest {
    private companion object {
        const val DAY = 86_400L
        const val PRUNE_STEP = 256
        const val OWN = "wss://relay.zsend.xyz"
        const val NOS_LOL = "wss://nos.lol"
        const val MOM = "wss://nostr.mom"
        const val NOS_SOCIAL = "wss://relay.nos.social"
        const val OXTR = "wss://nostr.oxtr.dev"
    }

    private val nowSec = System.currentTimeMillis() / 1000
    private val low = nowSec - 20 * DAY

    private fun call(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw NoSuchMethodException("${target.javaClass.simpleName}.$name/${args.size}")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun relaySetState(
        slot1: String,
        slot2: String,
        compatOutSinceMs: Long?,
    ): Any =
        Class
            .forName("co.electriccoin.zcash.ui.nostr.RelaySetState")
            .constructors
            .first { it.parameterCount == 5 }
            .newInstance(slot1, slot2, emptyMap<String, Long>(), emptyList<Long>(), compatOutSinceMs)

    /** 128 handled wraps below [low] and 128 between [low] and [low] + 2 days: one prune step. */
    private fun markPruneStep(prefs: ZchatPreferencesImpl) {
        repeat(PRUNE_STEP / 2) { prefs.markNostrEventSeen("synthetic-r5-below-%04d".format(it), low - 3_600 - it) }
        repeat(PRUNE_STEP / 2) { prefs.markNostrEventSeen("synthetic-r5-between-%04d".format(it), low + 3_600 + it) }
    }

    @Test
    fun `with a saved set and nos lol out, the prune stops at nos lol's since`() {
        val (prefs, _) = realPreferences()
        // Slot 1 is nostr.mom, slot 2 a fallback (relay.nos.social), nos.lol left slot 2 a day ago.
        call(prefs, "saveRelaySetState", relaySetState(MOM, NOS_SOCIAL, System.currentTimeMillis() - DAY * 1000))
        prefs.advanceNostrRelaySince(OWN, low + 5 * DAY)
        prefs.advanceNostrRelaySince(MOM, low + 6 * DAY)
        prefs.advanceNostrRelaySince(NOS_SOCIAL, low + 7 * DAY)
        prefs.advanceNostrRelaySince(NOS_LOL, low)
        // A relay not in the set, with the lowest since of all: it must not count.
        prefs.advanceNostrRelaySince(OXTR, low - 5 * DAY)

        markPruneStep(prefs)

        assertFalse(
            prefs.hasSeenNostrEvent("synthetic-r5-never-handled", low + 60),
            "a wrap nos.lol still serves (at or after its since) counts as seen: the floor passed nos.lol's since",
        )
        assertTrue(
            prefs.hasSeenNostrEvent("synthetic-r5-never-handled-old", low - 3_600),
            "the prune did run: wraps below every relay's since in the set count as seen",
        )
    }

    @Test
    fun `with a saved set and nos lol back in slot 2, the set's lowest since decides and nothing else`() {
        val (prefs, _) = realPreferences()
        call(prefs, "saveRelaySetState", relaySetState(MOM, NOS_LOL, null))
        prefs.advanceNostrRelaySince(OWN, low + 5 * DAY)
        prefs.advanceNostrRelaySince(MOM, low)
        prefs.advanceNostrRelaySince(NOS_LOL, low + 6 * DAY)
        // relay.primal.net (a default, swapped out of slot 1) and a stranger relay have no since: they must not stop
        // the prune.
        markPruneStep(prefs)

        assertTrue(
            prefs.hasSeenNostrEvent("synthetic-r5-never-handled-old", low - 3_600),
            "the prune did not run: a relay outside the saved set (no since) still counts",
        )
        assertFalse(
            prefs.hasSeenNostrEvent("synthetic-r5-never-handled", low + 60),
            "the floor passed nostr.mom's since, and nostr.mom is in the set",
        )
    }

    @Test
    fun `with nothing saved, DEFAULT_RELAYS decide as today`() {
        val (prefs, _) = realPreferences()
        val defaults = NostrRelayPool.DEFAULT_RELAYS
        prefs.advanceNostrRelaySince(defaults[0], low + 5 * DAY)
        prefs.advanceNostrRelaySince(defaults[1], low + 6 * DAY)
        prefs.advanceNostrRelaySince(defaults[2], low)
        prefs.advanceNostrRelaySince(MOM, low - 5 * DAY)

        markPruneStep(prefs)

        assertTrue(prefs.hasSeenNostrEvent("synthetic-r5-never-handled-old", low - 3_600), "the prune ran")
        assertFalse(prefs.hasSeenNostrEvent("synthetic-r5-never-handled", low + 60), "the floor passed the lowest since")
    }

    @Test
    fun `with nothing saved and one default relay without a since, nothing is pruned`() {
        val (prefs, _) = realPreferences()
        val defaults = NostrRelayPool.DEFAULT_RELAYS
        prefs.advanceNostrRelaySince(defaults[0], low + 5 * DAY)
        prefs.advanceNostrRelaySince(defaults[2], low)

        markPruneStep(prefs)

        assertFalse(prefs.hasSeenNostrEvent("synthetic-r5-never-handled-old", low - 3_600), "a relay with no since")
        assertTrue(prefs.hasSeenNostrEvent("synthetic-r5-below-0000", low - 3_600), "handled wraps stay listed")
    }
}
