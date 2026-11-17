package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * KI-24 fixup, G-FX-1 (train T4 cross review of the fixup, REVIEW_CROSS_FIXUP.md): a message handed to a chat reader and
 * never handled keeps the relay `since` line at or below its wrap time until its own age limit, also after it was
 * pushed out of the bridge's bounded list.
 *
 * At c194f764a [NostrChatBridge] keeps at most 4,096 handed-over, unhandled entries by id; past that the oldest keep only
 * one sticky minimum wrap time, and [NostrChatBridge.lowestUnhandledWrapSec] clears that minimum as soon as it is older
 * than 30 days + 49 h. Every younger wrap that was pushed out with it is forgotten then. An unattributed ZBOOT from an
 * unknown key is handed over and never marked seen, so a stranger can fill the list: one back-dated ZBOOT, then the
 * contact's DM (handed to a reader that is cancelled before it reads it: the #9 case), then 4,096 more ZBOOTs. The DM
 * is pushed out, the minimum is the stranger's old wrap, and once that wrap is past the age limit the value no longer
 * covers the DM: the line moves past it, and after a restart no relay serves it again (lost).
 *
 * Wanted: every dropped wrap holds the value at or below it until that wrap itself is past the age limit.
 *
 * The test runs its own clock 400 days ahead of the wall clock, so nothing another test left in the singleton bridge is
 * inside its window; each rumor is dated now, because dispatch's 30-day window reads the wall clock. Keys, ids and
 * addresses are synthetic.
 */
class DroppedUnhandledWrapsKeepTheirOwnAgeTest {
    private companion object {
        const val SENDER = "5e0d3a2b1c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f"
        const val STRANGER = "8b3a6c5d4e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b"
        const val PEER = "usyntheticfx1droppedwrappeer01"
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val JITTER = 48 * HOUR
        const val UNHANDLED_CAP = 4_096
        const val MAX_AGE = 30 * DAY + 49 * HOUR
        const val CHUNK = 128
    }

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = "synthetic-fx1-${UUID.randomUUID()}"
    private val wallNow = System.currentTimeMillis() / 1000
    private val received = ConcurrentHashMap.newKeySet<String>()

    @After
    fun tearDown() {
        readerScope.cancel()
    }

    private val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "findPeerByNostrPubkey" -> if (args!![0] == SENDER) PEER else null
                "hasSeenNostrEvent" -> false
                "toString" -> "FX1DroppedWrapPrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args!![0]
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
        name: String,
        wrapCreatedAtSec: Long,
        sender: String,
        content: String,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = sender,
        content = content,
        createdAtSec = wallNow,
        eventId = "$tag-$name",
        rumorId = "rumor-$tag-$name",
        wrapCreatedAtSec = wrapCreatedAtSec,
    )

    private fun boot(
        name: String,
        wrapCreatedAtSec: Long,
    ) = dm(name, wrapCreatedAtSec, sender = STRANGER, content = "ZBOOT|synthetic-fx1-$name")

    /** Dispatches [dms] in chunks the reader's buffer (256) always takes, each chunk read before the next is sent. */
    private suspend fun handOver(dms: List<NostrInboxManager.InboundDm>) {
        dms.chunked(CHUNK).forEach { chunk ->
            chunk.forEach { NostrChatBridge.dispatch(it, prefs) }
            assertTrue(
                "fixture: the reader received every message of the chunk",
                ScriptedRelay.awaitTrue(5_000) { chunk.all { it.eventId in received } },
            )
        }
    }

    @Test
    fun `a DM pushed out of the unhandled list by a ZBOOT flood keeps the value at or below its wrap until its own age limit`() =
        runBlocking {
            // A reader that receives every message and never finishes or marks one: the DM is the #9 case (its reader
            // is cancelled before it handles it), the ZBOOTs are unattributed and never marked seen.
            // If a message comes before the reader subscribed, it is held and handed over (and tracked) when it does.
            val reader = readerScope.launch { NostrChatBridge.inbound.collect { received += it.eventId } }
            val now0 = wallNow + 400 * DAY
            val oldWrap = now0 - 32 * DAY
            val dmWrap = now0 - JITTER

            handOver(listOf(boot("old-boot", oldWrap)))
            val contactDm = dm("contact-dm", dmWrap, sender = SENDER, content = "synthetic fx1 contact message")
            handOver(listOf(contactDm))
            handOver((0 until UNHANDLED_CAP).map { i -> boot("flood-$i", now0) })
            reader.cancelAndJoin()

            // 4,096 entries were handed over after the DM, so the DM (and the old ZBOOT) left the list by id.
            // Two hours later the stranger's old wrap is past the age limit, the DM's is not.
            assertTrue("fixture: at the start the old wrap is within the age limit", oldWrap >= now0 - MAX_AGE)
            val later = now0 + 2 * HOUR
            assertTrue("fixture: the old wrap is past the age limit", oldWrap < later - MAX_AGE)
            assertTrue("fixture: the DM's wrap is not", dmWrap >= later - MAX_AGE)
            val value = NostrChatBridge.lowestUnhandledWrapSec(later)
            assertTrue(
                "the value is $value once the stranger's old wrap aged out, above the wrap $dmWrap of a DM a reader was " +
                    "handed and never handled: the since line passes it and after a restart no relay serves it again",
                value != null && value <= dmWrap,
            )
        }
}
