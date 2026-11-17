package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1104 (W-79): a group's unread count for the chat list's Groups row.
 *
 * The last-read mark of a group is the conversations' last-read marker (ZchatPreferences.setLastReadTimestamp, the
 * "lastread:" entries of the main ZCHAT preferences, so Reset and Destroy clear it with the rest) under the key
 * [markerKey]. It is set when the group screen shows the group (the same max(now, newest message) rule as a chat).
 *
 * Unread = the stored group messages from someone else, not held, newer than the mark and not in the future (a row
 * stored twice under two ids counts once), as the conversations count theirs.
 */
object GroupUnread {
    private const val MARKER_PREFIX = "group:"

    /** The last-read marker key of [groupId]; never a peer address. */
    fun markerKey(groupId: String): String = MARKER_PREFIX + groupId

    /**
     * The mark set once for a group the list sees with no mark (after the upgrade that added group marks, or a
     * restore): its newest stored message not after [nowMs], so its history counts as read and later messages count.
     * A group with nothing stored gets the time it was created ([createdAtMs], not after [nowMs]), so its first
     * message counts. Never below 1, so the mark exists.
     */
    fun firstMark(
        messagesJson: String?,
        createdAtMs: Long,
        nowMs: Long
    ): Long {
        var newest = minOf(createdAtMs, nowMs)
        val array =
            if (messagesJson.isNullOrBlank()) null else runCatching { org.json.JSONArray(messagesJson) }.getOrNull()
        if (array != null) {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val at = ZMSGGroupProtocol.groupTimeToInstant(row.optLong("timestamp")).toEpochMilli()
                if (at in (newest + 1)..nowMs) newest = at
            }
        }
        return maxOf(newest, 1L)
    }

    /**
     * The unread messages of one group, from its stored messages ([messagesJson], the group message store's array),
     * newer than [lastRead] and not after [nowMs]. [isSelf] tells our own addresses.
     */
    fun count(
        messagesJson: String?,
        lastRead: Long,
        nowMs: Long,
        isSelf: (String) -> Boolean
    ): Int {
        if (messagesJson.isNullOrBlank()) return 0
        val array = runCatching { org.json.JSONArray(messagesJson) }.getOrNull() ?: return 0
        val seen = HashSet<Triple<String, Long, Int>>()
        var unread = 0
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val sender = row.optString("sender")
            if (sender.isEmpty() || row.optString("held").isNotEmpty()) continue
            val at = ZMSGGroupProtocol.groupTimeToInstant(row.optLong("timestamp")).toEpochMilli()
            if (at <= lastRead || at > nowMs) continue
            if (!seen.add(Triple(sender, row.optLong("seq"), row.optInt("epoch")))) continue
            if (isSelf(sender)) continue
            unread++
        }
        return unread
    }

    /**
     * ZC-1227: the time a group's chat-list row shows: its newest stored message not after [nowMs] (held rows are not
     * shown, so they do not count), or null when it has none and the caller shows the creation time.
     */
    fun newestMessageMs(
        messagesJson: String?,
        nowMs: Long
    ): Long? {
        if (messagesJson.isNullOrBlank()) return null
        val array = runCatching { org.json.JSONArray(messagesJson) }.getOrNull() ?: return null
        var newest: Long? = null
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            if (row.optString("held").isNotEmpty()) continue
            val at = ZMSGGroupProtocol.groupTimeToInstant(row.optLong("timestamp")).toEpochMilli()
            if (at <= nowMs && (newest == null || at > newest)) newest = at
        }
        return newest
    }
}
