package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.callSuspend
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.hex
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.resendKept
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two sends in two wall-clock seconds; longer than a second because the wall clock can step back by up to 1.4 s (ZC-482). */
private const val RETRY_IN_A_LATER_SECOND_MS = 2_500L

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.4 Z1 and Z3, §14.5 key decisions (1) and (2), §14.7): the same
 * message is the same rumor, even after a restart.
 *
 * At HEAD NostrInboxManager.send makes the rumor and its seal at one second, now: a Retry after a restart (the outbox is
 * empty, resendKept answers null) can only make a new rumor with a new id. Wanted: Nip17.send's new last parameter
 * rumorCreatedAtSec (only the rumor uses it; the seal and the wrap keep now), and NostrInboxManager.sendRumorAt(plaintext,
 * recipient, rumorAtSec, expectedRumorId = null): a rumor made at the given second inside a new seal and a new wrap made
 * now; with an expected id that the built rumor does not have, nothing is published and the answer is null. The wrap is
 * kept for resendKept like any other. Local relays (the fixture of KeptWrapIsResentNotRemadeTest); the receiving side is
 * an inbox over the same relays. sendRumorAt is reached by name. Every key and text is synthetic.
 */
class RemadeRumorKeepsItsIdTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val inboxes = mutableListOf<SharedRelayCountApi.Inbox>()
    private val clock = AtomicLong(1_790_000_000_000L)
    private val sender = SharedRelayCountApi.identity()
    private val recipientIdentity = SharedRelayCountApi.identity()
    private val recipient = hex(recipientIdentity.publicKey)

    @After
    fun tearDown() {
        inboxes.forEach { it.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private inner class Fixture {
        val all: List<HealthRelay>
        val urls: List<String>
        private val spareUrl: String

        init {
            val started = (0 until 4).map { HealthRelay(HealthRelay.Mode.SERVES).also { relays += it }.let { r -> r to r.start() } }
            all = started.take(3).map { it.first }
            urls = started.take(3).map { it.second }
            spareUrl = started[3].second
        }

        /** An inbox (a new pool: a fresh process) started as [identity]. */
        suspend fun start(identity: NOSTRIdentity): SharedRelayCountApi.Inbox {
            val pool =
                RelayHealthApi.pool(
                    urls,
                    scope,
                    null,
                    RelayHealthApi.config(listOf(urls[1], urls[2], spareUrl), clock),
                    RelayHealthApi.CountingSetStore().proxy,
                )
            val reqsBefore = all.map { it.inboxReqs().size }
            val inbox = SharedRelayCountApi.Inbox(pool, identity, scope).also { inboxes += it }
            assertTrue(HealthRelay.awaitTrue { all.indices.all { all[it].inboxReqs().size > reqsBefore[it] } }, "fixture: REQ")
            delay(500)
            return inbox
        }

        fun distinctIds(): Set<String> = all.flatMap { it.publishedIds }.toSet()
    }

    private suspend fun sendRumorAt(
        inbox: NostrInboxManager,
        text: String,
        rumorAtSec: Long,
        expected: String?,
    ): NostrInboxManager.SendResult? =
        callSuspend(inbox, "sendRumorAt", text, recipient, rumorAtSec, expected) as NostrInboxManager.SendResult?

    @Test
    fun `a restart and a re-make with the expected id give the same rumor in a new wrap`() =
        runBlocking {
            val f = Fixture()
            val first = f.start(sender)
            val t0 = System.currentTimeMillis() / 1000 - 600
            val made = assertNotNull(sendRumorAt(first.manager, "synthetic zc421 remade", t0, null), "the first send answered null")
            val id1 = made.rumorId
            first.stop()

            // A restart, ten minutes later: nothing is kept, the rumor is made again at its first second.
            val second = f.start(sender)
            assertNull(resendKept(second.manager, id1, recipient), "fixture: a new pool keeps nothing")
            val again = assertNotNull(sendRumorAt(second.manager, "synthetic zc421 remade", t0, id1), "the re-make answered null")
            assertEquals(id1, again.rumorId)

            // The contact reads the relays (they serve what they hold when it asks).
            val reader = f.start(recipientIdentity)
            assertTrue(HealthRelay.awaitTrue { reader.received.size >= 2 }, "the reader did not get both wraps")
            val got = reader.received.distinctBy { it.eventId }
            assertEquals(2, got.map { it.eventId }.toSet().size, "the two wraps have one event id")
            assertEquals(setOf(id1), got.map { it.rumorId }.toSet())
            assertEquals(setOf("synthetic zc421 remade"), got.map { it.content }.toSet())
            assertEquals(setOf(t0), got.map { it.createdAtSec }.toSet())
            val now = System.currentTimeMillis() / 1000
            val newest = got.maxOf { it.wrapCreatedAtSec }
            assertTrue(newest in (now - 48 * 3_600 - 60)..now, "the re-made wrap's time is not within 48 h before now: $newest")
            assertEquals(2, f.distinctIds().size)

            // The re-made wrap is kept: its resend publishes no third event.
            val resent = resendKept(second.manager, id1, recipient)
            assertNotNull(resent, "the re-made wrap was not kept")
            delay(300)
            assertEquals(2, f.distinctIds().size, "a third event was published")
        }

    @Test
    fun `an expected id the rumor does not have publishes nothing`() =
        runBlocking {
            val f = Fixture()
            val inbox = f.start(sender)
            val t0 = System.currentTimeMillis() / 1000 - 600
            val id1 = assertNotNull(sendRumorAt(inbox.manager, "synthetic zc421 checked", t0, null)).rumorId
            val before = f.distinctIds()

            assertNull(sendRumorAt(inbox.manager, "synthetic zc421 other text", t0, id1), "another content answered")
            inbox.stop()
            val other = f.start(SharedRelayCountApi.identity())
            assertNull(sendRumorAt(other.manager, "synthetic zc421 checked", t0, id1), "another identity key answered")
            delay(300)
            assertEquals(before, f.distinctIds(), "a wrap was published for an id that differed")
        }

    @Test
    fun `control - two sends are two rumors, and a call signal keeps its expiry and its own second`() =
        runBlocking {
            val f = Fixture()
            val inbox = f.start(sender)
            val a = inbox.manager.send("synthetic zc421 twice", recipient)
            delay(RETRY_IN_A_LATER_SECOND_MS)
            val b = inbox.manager.send("synthetic zc421 twice", recipient)
            assertNotEquals(a.rumorId, b.rumorId)

            val at = System.currentTimeMillis() / 1000
            val signal = Nip17.send(sender.privateKey, sender.publicKey, recipientIdentity.publicKey, "synthetic call", at, at + 60)
            assertTrue("\"expiration\",\"${at + 60}\"" in signal.giftWrapJson, "the call signal lost its expiry")
            assertEquals(at, Nip17.receive(signal.giftWrapJson, recipientIdentity.privateKey).createdAtSec)
        }
}
