package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1349 (review of ZC-1337): a relay message from a key a held ZBOOT names is never handled, so it stays in the bridge's
 * unhandled list and [NostrChatBridge.lowestUnhandledWrapSec] answers its wrap until the wrap is past the relay window. Anyone
 * who can get a ZBOOT held (a claim-key stranger) therefore stops the relay `since` line for about a month with one message.
 *
 * Must hold: a kept message holds the line for hours, counted from when it arrived (a wrap's time is up to two days in the
 * past by design); it stops when its key is blocked or no held ZBOOT names it any more. A message a reader simply has not
 * finished keeps its long limit. Every key and id is synthetic.
 */
class HeldConnectionNeverPinsTheRelayLineTest {
    private companion object {
        const val PEER = "usyntheticzc1349heldpeer"
        const val OTHER_PEER = "usyntheticzc1349boundpeer"
        const val HOUR = 3_600L
        const val HOURS_IT_MAY_HOLD = 24L
        const val FAR_FUTURE_SEC = 10L * 365 * 24 * HOUR
        val serial = AtomicInteger(0)
    }

    private val run = serial.incrementAndGet().toString().padStart(4, '0')
    private val heldKey = "a$run".padEnd(64, 'b')
    private val boundKey = "c$run".padEnd(64, 'd')
    private val handled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = System.currentTimeMillis() / 1000

    @Volatile private var namedByHeldZboot = true

    @Volatile private var blocked = false

    @Volatile private var bound = false

    private val store: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "findPeerByNostrPubkey" ->
                    when {
                        args!![0] == boundKey -> OTHER_PEER
                        args[0] == heldKey && bound -> PEER
                        else -> null
                    }
                "findPeerByHeldBootNostrPubkey" -> if (args!![0] == heldKey && namedByHeldZboot) PEER else null
                "isNostrPubkeyBlocked" -> args!![0] == heldKey && blocked
                "toString" -> "HeldConnectionStore"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
            }
        } as ZchatPreferences

    private fun dm(
        id: String,
        wrapSec: Long,
        sender: String = heldKey,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = sender,
        content = "synthetic message",
        createdAtSec = now,
        eventId = "zc1349-$run-$id",
        rumorId = "r-$run-$id",
        wrapCreatedAtSec = wrapSec,
    )

    private fun eventually(check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(5)
        }
        return check()
    }

    /** Forgets what other tests left in the process-wide bridge: held entries reach this reader, old ones age out. */
    private fun clearTheLine() {
        eventually {
            NostrChatBridge.consumePendingRedelivery()
            NostrChatBridge.isRelayBacklogSettled()
        }
        NostrChatBridge.lowestUnhandledWrapSec(now + FAR_FUTURE_SEC)
    }

    @Before
    fun setUp() {
        scope.launch { NostrChatBridge.inboundReader.collect { handled += it.eventId } }
        clearTheLine()
        handled.clear()
    }

    @After
    fun tearDown() {
        clearTheLine()
        scope.cancel()
    }

    @Test
    fun `control - a message a reader has not finished keeps its long limit`() {
        // A ZBOOT is unattributed and never marked seen, so a finished reader does not take it off the list.
        val wrap = now - HOUR
        NostrChatBridge.dispatch(
            NostrInboxManager.InboundDm(
                senderPubkeyHex = "e$run".padEnd(64, 'f'),
                content = "ZBOOT|synthetic-zc1349-$run",
                createdAtSec = now,
                eventId = "zc1349-$run-boot",
                rumorId = "r-$run-boot",
                wrapCreatedAtSec = wrap,
            ),
            store,
        )

        assertEquals(
            "fixture: an ordinary unhandled entry lost its long limit",
            wrap,
            NostrChatBridge.lowestUnhandledWrapSec(now + (HOURS_IT_MAY_HOLD + 1) * HOUR),
        )
    }

    @Test
    fun `a kept message holds the line for now and not a day later`() {
        val wrap = now - HOUR
        NostrChatBridge.dispatch(dm("ages", wrap), store)

        assertEquals("fixture: the kept message does not hold the line yet", wrap, NostrChatBridge.lowestUnhandledWrapSec(now))
        assertNull(
            "one kept message from a held key still holds the relay line a day later (it would for a month)",
            NostrChatBridge.lowestUnhandledWrapSec(now + (HOURS_IT_MAY_HOLD + 1) * HOUR),
        )
    }

    @Test
    fun `the hours count from its arrival, not from a wrap time two days back`() {
        val wrap = now - 40 * HOUR
        NostrChatBridge.dispatch(dm("jitter", wrap), store)

        assertEquals(
            "a wrap is dated up to 48 h back: a fresh message aged out the moment it arrived",
            wrap,
            NostrChatBridge.lowestUnhandledWrapSec(now + HOUR),
        )
        assertNull(
            "a jittered kept message still holds the relay line a day after it arrived",
            NostrChatBridge.lowestUnhandledWrapSec(now + (HOURS_IT_MAY_HOLD + 1) * HOUR),
        )
    }

    @Test
    fun `blocking the key frees the line at once`() {
        NostrChatBridge.dispatch(dm("blocked", now - HOUR), store)
        assertEquals("fixture", now - HOUR, NostrChatBridge.lowestUnhandledWrapSec(now))

        blocked = true

        assertNull("a blocked contact's kept message still holds the relay line", NostrChatBridge.lowestUnhandledWrapSec(now))
    }

    @Test
    fun `a deleted contact, whose held ZBOOT is gone, frees the line at once`() {
        NostrChatBridge.dispatch(dm("deleted", now - HOUR), store)
        assertEquals("fixture", now - HOUR, NostrChatBridge.lowestUnhandledWrapSec(now))

        namedByHeldZboot = false

        assertNull("a deleted contact's kept message still holds the relay line", NostrChatBridge.lowestUnhandledWrapSec(now))
    }

    @Test
    fun `a bound key asks one redelivery, and its message still holds the line until it comes back`() {
        val wrap = now - HOUR
        NostrChatBridge.dispatch(dm("bound", wrap), store)
        assertFalse("fixture: a redelivery was asked before the hold ended", NostrChatBridge.consumePendingRedelivery())

        namedByHeldZboot = false
        bound = true

        assertTrue("the hold ended and no redelivery brings the kept message back", NostrChatBridge.consumePendingRedelivery())
        assertFalse("a second redelivery for the same hold", NostrChatBridge.consumePendingRedelivery())
        assertEquals("the line moved before the redelivered copy was handled", wrap, NostrChatBridge.lowestUnhandledWrapSec(now))
    }

    @Test
    fun `a live reader never handles a kept message and a contact's message still reaches it`() {
        NostrChatBridge.dispatch(dm("skip", now - HOUR), store)
        NostrChatBridge.dispatch(dm("contact", now - HOUR, boundKey), store)

        assertTrue("fixture: the live reader was not reached", eventually { "zc1349-$run-contact" in handled })
        assertFalse("a reader handled a message from a held key: it may be shown or marked seen", "zc1349-$run-skip" in handled)
    }
}
