package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Pure expiry rule for disappearing messages (B17) — no Android deps, unit-tested. Returns the epoch-millis
 * at which a message should disappear, or null if it is exempt (never expires under this TTL).
 *
 * Both devices compute the SAME boundary from the SYNCED (ttlSeconds, effectiveSinceMillis), so a chat
 * disappears symmetrically. Non-retroactive: messages older than effectiveSinceMillis are never affected.
 */
object DisappearPolicy {
    fun expireAtMillis(
        msgTimestampMillis: Long,
        nowMillis: Long,
        ttlSeconds: Long,
        effectiveSinceMillis: Long,
        isPending: Boolean,
        isFailed: Boolean,
        isSystemNote: Boolean,
        isCallLog: Boolean,
        // For time-locked messages: null = not a lock; false = still locked (exempt); true = unlocked.
        timeLockUnlocked: Boolean?,
        // SCHEDULED locks start their TTL clock at the scheduled unlock time.
        scheduledUnlockMillis: Long?,
        // PAY/CND/BLK locks start at the first-observed-unlock anchor (persisted, may lag between devices).
        unlockAnchorMillis: Long?,
    ): Long? {
        if (ttlSeconds <= 0L) return null // Off
        if (isPending || isFailed || isSystemNote || isCallLog) return null // exempt
        if (msgTimestampMillis < effectiveSinceMillis) return null // NOT retroactive
        if (timeLockUnlocked == false) return null // locked content never expires unread-able
        val start = scheduledUnlockMillis ?: unlockAnchorMillis ?: minOf(msgTimestampMillis, nowMillis)
        return start + ttlSeconds * 1000L
    }
}
