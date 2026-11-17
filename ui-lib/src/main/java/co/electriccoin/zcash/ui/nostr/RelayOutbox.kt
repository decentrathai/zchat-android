package co.electriccoin.zcash.ui.nostr

import java.util.concurrent.ConcurrentHashMap

/**
 * RELAY-HEALTH (2.13.0, W-15 item 4): our own gift-wraps, kept in memory for up to 24 h after their first publish so
 * the pool can put each on every active relay that lacks it, ZChat's relay first of all (the one relay every ZChat
 * version reads). Only wraps at least one relay acknowledged (a send no relay took is FAILED for the caller, and its
 * Retry makes a new wrap); never an expiration-tagged wrap (call signals); at most [RelayHealthConfig.outboxMaxEntries]
 * wraps and [RelayHealthConfig.outboxMaxChars] characters, the oldest dropped first. A restart empties it.
 *
 * ZC-393: a wrap may carry a tag (recipient:rumorId), so a Retry of a message only a public relay took finds the kept
 * wrap and re-sends it instead of making a second one ([findByTag]). Once every active relay holds a tagged wrap the
 * entry leaves, and a completed record (the tag, the relays that held it, its first publish time; no json) stays for
 * the same 24 h and entry limit, so a Retry after the repair still finds the message delivered ([completedFor]).
 */
internal class RelayOutbox {
    class Entry(
        val id: String,
        val json: String,
        val firstPublishMs: Long,
        // ZC-393: "<recipient pubkey hex>:<rumor id>", or null (an untagged publish).
        val tag: String? = null,
    ) {
        /** The relays that acknowledged it with a readable OK (true, or "duplicate:"). */
        val acked: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Its first publish is still waiting for the relays' answers: not re-sent yet. */
        @Volatile var inFlight: Boolean = true
    }

    /** ZC-393: a tagged wrap every active relay held when it left the outbox. */
    class Completed(
        val acked: Set<String>,
        val firstPublishMs: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var chars = 0L
    private val completed = LinkedHashMap<String, Completed>()

    /** Keeps [json] (first publish at [nowMs]) under [tag]; an id already kept keeps its first tag. @return whether it is kept. */
    @Synchronized
    fun add(
        json: String,
        nowMs: Long,
        config: RelayHealthConfig,
        tag: String? = null,
    ): Boolean {
        if (EXPIRATION.containsMatchIn(json)) return false
        val id = eventId(json) ?: return false
        if (entries.containsKey(id)) return true
        if (json.length > config.outboxMaxChars) return false
        entries[id] = Entry(id, json, nowMs, tag)
        chars += json.length
        while (entries.size > config.outboxMaxEntries || chars > config.outboxMaxChars) {
            val oldest = entries.keys.firstOrNull() ?: break
            remove(oldest)
        }
        return true
    }

    @Synchronized
    fun remove(id: String) {
        entries.remove(id)?.let { chars -= it.json.length }
    }

    /** The first publish of [json] has its answers: taken out again when [acks] is 0, else ready to be re-sent. */
    @Synchronized
    fun published(
        json: String,
        acks: Int,
    ) {
        val id = eventId(json) ?: return
        if (acks == 0) remove(id) else entries[id]?.inFlight = false
    }

    @Synchronized
    fun acked(
        id: String,
        relayUrl: String,
    ) {
        entries[id]?.acked?.add(relayUrl)
    }

    /**
     * ZC-393: [id] is on every active relay and leaves the outbox; a tagged one leaves a completed record (at most
     * [RelayHealthConfig.outboxMaxEntries], the oldest dropped first).
     */
    @Synchronized
    fun complete(
        id: String,
        config: RelayHealthConfig,
    ) {
        val entry = entries[id] ?: return
        remove(id)
        val tag = entry.tag ?: return
        completed.remove(tag)
        completed[tag] = Completed(entry.acked.toSet(), entry.firstPublishMs)
        while (completed.size > config.outboxMaxEntries) {
            val oldest = completed.keys.firstOrNull() ?: break
            completed.remove(oldest)
        }
    }

    /** ZC-393: the live wrap kept under [tag], or null. */
    @Synchronized
    fun findByTag(tag: String): Entry? = entries.values.lastOrNull { it.tag == tag }

    /** ZC-393: the relays that held the wrap of [tag] when it left the outbox, or null. */
    @Synchronized
    fun completedFor(tag: String): Set<String>? = completed[tag]?.acked

    /** Drops every wrap (and completed record) first published [keepMs] or longer ago, whatever its created_at. */
    @Synchronized
    fun prune(
        nowMs: Long,
        keepMs: Long,
    ) {
        val expired = entries.values.filter { nowMs - it.firstPublishMs >= keepMs }
        expired.forEach { remove(it.id) }
        completed.entries.removeAll { nowMs - it.value.firstPublishMs >= keepMs }
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.values.toList()

    private companion object {
        val EXPIRATION = Regex("""\[\s*"expiration"\s*,""")

        fun eventId(json: String): String? {
            val needle = "\"id\":\""
            val start = json.indexOf(needle)
            if (start < 0) return null
            val from = start + needle.length
            val end = json.indexOf('"', from)
            return if (end > from) json.substring(from, end) else null
        }
    }
}
