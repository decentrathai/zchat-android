package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisappearPolicyTest {
    private val since = 1_000_000L
    private val now = 2_000_000L

    private fun at(
        ts: Long, ttl: Long = 3600, isPending: Boolean = false, isFailed: Boolean = false,
        isSystemNote: Boolean = false, isCallLog: Boolean = false, unlocked: Boolean? = null,
        scheduled: Long? = null, anchor: Long? = null,
    ) = DisappearPolicy.expireAtMillis(ts, now, ttl, since, isPending, isFailed, isSystemNote, isCallLog, unlocked, scheduled, anchor)

    @Test fun ttlOff_neverExpires() = assertNull(at(now, ttl = 0))
    @Test fun retroactive_messagesBeforeSince_exempt() = assertNull(at(since - 1))
    @Test fun normal_expiresAtStartPlusTtl() = assertEquals(now + 3600_000L, at(now))
    @Test fun futureDated_startsAtNow_notFutureTimestamp() = assertEquals(now + 3600_000L, at(now + 999_999L))
    @Test fun pending_exempt() = assertNull(at(now, isPending = true))
    @Test fun failed_exempt() = assertNull(at(now, isFailed = true))
    @Test fun systemNote_exempt() = assertNull(at(now, isSystemNote = true))
    @Test fun callLog_exempt() = assertNull(at(now, isCallLog = true))
    @Test fun stillLocked_exempt() = assertNull(at(now, unlocked = false))
    @Test fun scheduled_clockStartsAtUnlock() = assertEquals(now + 500_000L + 3600_000L, at(now, scheduled = now + 500_000L))
    @Test fun payLock_clockStartsAtAnchor() = assertEquals(now + 100_000L + 3600_000L, at(now, unlocked = true, anchor = now + 100_000L))
}
