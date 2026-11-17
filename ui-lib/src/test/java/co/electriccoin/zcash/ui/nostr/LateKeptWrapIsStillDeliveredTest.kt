package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-420 / ZC-658 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.2 R3, §4.2, §11.1 R-8): a relay copy that only a public relay
 * took, and that reaches ZChat's relay hours later, is still read by a 2.13 receiver.
 *
 * A 2.13 receiver asks each relay only from its `since` line (an earlier end of stored events minus 49 h, RelaySinceLine)
 * and drops a wrap below it (RelayClient: "older than its since: dropped"). A wrap's created_at is back-dated by up to
 * 48 h (Nip17.jitterPastTimestamp). So the wrap the sender's outbox keeps, made hours before it reaches a relay the
 * receiver reads, can lie below the receiver's line, and then the receiver never reads it. Here: the sender's wrap was
 * made [HOURS_LATE] hours ago and only primal took it (ZChat's relay refused the write, nos.lol was down); the receiver
 * reads ZChat's relay and nos.lol only (what 2.12.4 reads) and completed two settled ends of stored events on ZChat's
 * relay, so its line is now − 49 h. ZChat's relay takes writes again, and the copy is delivered through
 * [deliverLate]: `NostrChatBridge.repairRelayCopy` for a mark of that age once the bridge has it, else today's
 * `resendKept` (the kept wrap, same event id). Wanted: the receiver reads the notice.
 *
 * "Hours later" is modelled by a wrap whose rumor and wrap times are those of a send [HOURS_LATE] hours ago (Nip17.send's
 * createdAtSec), published through the pool with the keep tag NostrInboxManager.send uses: the pool reports each end of
 * stored events at the wall clock (NostrRelayPool.wireClient), so the receiver's line cannot be moved hours ahead. The
 * back-date is random: the fixture makes wraps until one lies on the wanted side of the line (a bounded loop that fails
 * loudly), so no assertion depends on chance. Controls: a wrap above the line is read from the kept wrap; a mark younger
 * than one hour is confirmed from its kept wrap with no new publish; an invite mark of any age is confirmed from its kept
 * wrap with no new publish (a rebuilt invite is a new invitation). Local relays; every key is synthetic.
 */
class LateKeptWrapIsStillDeliveredTest {
    private companion object {
        const val HOURS_LATE = 20L
        const val HOUR_SEC = 3_600L
        const val HOUR_MS = HOUR_SEC * 1_000
        const val MAX_WRAP_TRIES = 400
        const val PEER = "usyntheticzc658latekeptwrappeer01"
        const val GROUP = "synthetic-zc658-group"
        const val MEMBER = "usyntheticzc658latekeptmember01"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val inboxes = mutableListOf<SharedRelayCountApi.Inbox>()
    private val clock = AtomicLong(System.currentTimeMillis())
    private val sender = SharedRelayCountApi.identity()
    private val receiverIdentity = SharedRelayCountApi.identity()
    private val recipient = hex(receiverIdentity.publicKey)
    private val bridgePublishes = AtomicInteger()

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        RelayCopyMarksApi.unregister()
        inboxes.forEach { it.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    /** The receiver's line store: a map that applies RelaySinceLine.nextStored at the wall clock (as the app's store). */
    private class LinePersistence : RelaySinceLine.Persistence {
        val stored = ConcurrentHashMap<String, Long>()

        override fun get(relayUrl: String): Long? = stored[relayUrl]

        override fun advance(
            relayUrl: String,
            sinceSec: Long,
        ): Boolean {
            val next = RelaySinceLine.nextStored(stored[relayUrl], sinceSec, System.currentTimeMillis() / 1000) ?: return false
            stored[relayUrl] = next
            return true
        }

        override fun scope(): Long = 0L
    }

    private fun relay(mode: HealthRelay.Mode): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private inner class Net {
        val own: HealthRelay
        val primal: HealthRelay
        val nosLol: HealthRelay
        private val ownUrl: String
        private val urls: List<String>
        private val spareUrl: String
        val persistence = LinePersistence()
        lateinit var senderInbox: SharedRelayCountApi.Inbox
        lateinit var receiverInbox: SharedRelayCountApi.Inbox
        lateinit var receiverPool: NostrRelayPool

        init {
            // ZChat's relay serves reads but refuses writes (the sender's publish misses it), nos.lol is down.
            val (o, urlOwn) = relay(HealthRelay.Mode.WRITE_BLOCKED)
            val (p, urlPrimal) = relay(HealthRelay.Mode.SERVES)
            val (n, urlNosLol) = relay(HealthRelay.Mode.DOWN_502)
            val (_, urlSpare) = relay(HealthRelay.Mode.SERVES)
            own = o
            primal = p
            nosLol = n
            ownUrl = urlOwn
            urls = listOf(urlOwn, urlPrimal, urlNosLol)
            spareUrl = urlSpare
        }

        private fun receiverReqsOnOwn(): List<String> = own.inboxReqs().filter { recipient in it }

        suspend fun start() {
            val senderPool =
                RelayHealthApi.pool(
                    urls,
                    scope,
                    null,
                    RelayHealthApi.config(listOf(urls[1], urls[2], spareUrl), clock),
                    RelayHealthApi.CountingSetStore().proxy,
                )
            senderInbox = SharedRelayCountApi.Inbox(senderPool, sender, scope).also { inboxes += it }
            val senderHex = hex(sender.publicKey)
            assertTrue(
                HealthRelay.awaitTrue {
                    own.inboxReqs().any { senderHex in it } && primal.inboxReqs().any { senderHex in it }
                },
                "fixture: the sender subscribed on ZChat's relay and primal",
            )
            // The receiver reads what 2.12.4 reads: ZChat's relay and nos.lol, with a since line per relay.
            val line = RelaySinceLine(persistence, isBacklogSettled = { true })
            receiverPool = NostrRelayPool(listOf(ownUrl, urls[2]), scope, line)
            receiverInbox = SharedRelayCountApi.Inbox(receiverPool, receiverIdentity, scope).also { inboxes += it }
            assertTrue(HealthRelay.awaitTrue { receiverReqsOnOwn().isNotEmpty() }, "fixture: the receiver subscribed on ZChat's relay")
            delay(500)
        }

        /** The receiver completes settled ends of stored events on ZChat's relay until its line is stored; returns the line. */
        suspend fun moveReceiverLine(): Long {
            var tries = 0
            while (persistence.stored[ownUrl] == null) {
                assertTrue(tries++ < 6, "fixture: the receiver's since line on ZChat's relay never moved")
                readAgain()
                HealthRelay.awaitTrue(3_000) { persistence.stored[ownUrl] != null }
            }
            val line = persistence.stored.getValue(ownUrl)
            // The next subscription carries it.
            readAgain()
            assertTrue(
                HealthRelay.awaitTrue { receiverReqsOnOwn().any { HealthRelay.sinceOf(it) != null } },
                "fixture: the receiver's REQ to ZChat's relay carries no since",
            )
            return line
        }

        /** The receiver asks ZChat's relay again (a re-subscription: what it holds from the receiver's since line). */
        suspend fun readAgain() {
            val before = receiverReqsOnOwn().size
            receiverPool.redeliverBacklog()
            assertTrue(HealthRelay.awaitTrue { receiverReqsOnOwn().size > before }, "fixture: the receiver did not ask again")
        }

        /**
         * A wrap of [memo] to the receiver made [hoursAgo] hours ago whose own created_at passes [side], published by the
         * sender's pool with the keep tag of NostrInboxManager.send: only primal takes it, and the outbox keeps it.
         */
        suspend fun keptWrap(
            memo: String,
            hoursAgo: Long,
            side: (Long) -> Boolean,
        ): Nip17.SentMessage {
            val madeAt = System.currentTimeMillis() / 1000 - hoursAgo * HOUR_SEC
            var wrap: Nip17.SentMessage? = null
            for (i in 1..MAX_WRAP_TRIES) {
                val candidate = Nip17.send(sender.privateKey, sender.publicKey, receiverIdentity.publicKey, memo, createdAtSec = madeAt)
                if (side(checkNotNull(HealthRelay.createdAt(candidate.giftWrapJson)))) {
                    wrap = candidate
                    break
                }
            }
            checkNotNull(wrap) { "fixture: no wrap on the wanted side of the line in $MAX_WRAP_TRIES tries" }
            val count = senderInbox.pool.publishCounted(wrap.giftWrapJson, tag = "${recipient.lowercase()}:${wrap.rumorId}")
            assertEquals(1, count.readable, "fixture: only primal took the wrap")
            assertEquals(0, count.shared, "fixture: neither ZChat's relay nor nos.lol took the wrap")
            return wrap
        }

        /** The bridge hooks the service installs: the publisher and the kept-wrap resender, on the sender's inbox. */
        fun installHooks() {
            NostrChatBridge.registerPublisher { text, to ->
                bridgePublishes.incrementAndGet()
                val r = senderInbox.manager.send(text, to)
                NostrChatBridge.PublishResult(r.acks, r.rumorId, r.readableAcks)
            }
            NostrChatBridge.registerKeptResender { id, to ->
                senderInbox.manager.resendKept(id, to)?.let { NostrChatBridge.PublishResult(it.acks, it.rumorId, it.readableAcks) }
            }
        }

        fun read(memo: String): List<NostrInboxManager.InboundDm> = receiverInbox.received.filter { it.content == memo }
    }

    /**
     * Delivers the copy of [slot] late: `NostrChatBridge.repairRelayCopy` for a mark [markAgeMs] old when the bridge has it,
     * else today's re-send of the kept wrap (`NostrChatBridge.resendKept`). Answers what ran.
     */
    private suspend fun deliverLate(
        net: Net,
        slot: () -> String,
        wrap: Nip17.SentMessage,
        markAgeMs: Long,
        extra: String,
        memo: String,
    ): String {
        net.installHooks()
        if (!RelayCopyMarksApi.available()) {
            val kept = NostrChatBridge.resendKept(wrap.rumorId, recipient)
            return "resendKept:${kept?.acks}"
        }
        val marks = RelayCopyMarksApi.MemoryMarks()
        RelayCopyMarksApi.register(marks)
        val s = slot()
        marks.map[s] = "${wrap.rumorId}|${System.currentTimeMillis() - markAgeMs}|$extra"
        return "repair:" + RelayCopyMarksApi.repair(s, recipient) { memo }
    }

    @Test
    fun `a copy whose kept wrap lies below the receiver's since line is still read once ZChat's relay is back`() =
        runBlocking {
            val net = Net().also { it.start() }
            val memo = "synthetic zc658 relay identity below the line"
            val line = net.moveReceiverLine()
            val wrap = net.keptWrap(memo, HOURS_LATE) { it < line - 60 }

            net.own.mode = HealthRelay.Mode.SERVES
            val ran =
                deliverLate(net, { RelayCopyMarksApi.Slots.identity(PEER) }, wrap, HOURS_LATE * HOUR_MS, hex(sender.publicKey), memo)
            net.readAgain()

            assertTrue(
                HealthRelay.awaitTrue { net.read(memo).isNotEmpty() },
                "the receiver never read the copy ($ran): its kept wrap reached ZChat's relay $HOURS_LATE h late, below the " +
                    "receiver's since line, and a receiver drops a wrap older than its since (ZC-658)",
            )
        }

    @Test
    fun `control - a kept wrap above the receiver's since line is read when it reaches ZChat's relay late`() =
        runBlocking {
            val net = Net().also { it.start() }
            val memo = "synthetic zc658 kept wrap above the line"
            val line = net.moveReceiverLine()
            val wrap = net.keptWrap(memo, HOURS_LATE) { it >= line + 600 }

            net.own.mode = HealthRelay.Mode.SERVES
            net.installHooks()
            val kept = NostrChatBridge.resendKept(wrap.rumorId, recipient)
            net.readAgain()

            assertEquals(1, kept?.acks, "fixture: ZChat's relay took the kept wrap")
            assertTrue(HealthRelay.awaitTrue { net.read(memo).isNotEmpty() }, "a kept wrap above the line was not read")
            assertEquals(ScriptedRelay.eventId(wrap.giftWrapJson), net.read(memo).first().eventId, "not the kept wrap")
        }

    @Test
    fun `control - a mark younger than one hour is confirmed from its kept wrap with no new publish`() =
        runBlocking {
            val net = Net().also { it.start() }
            val memo = "synthetic zc658 young mark"
            net.moveReceiverLine()
            val wrap = net.keptWrap(memo, 0L) { true }

            net.own.mode = HealthRelay.Mode.SERVES
            val ran =
                deliverLate(net, { RelayCopyMarksApi.Slots.identity(PEER) }, wrap, 30 * 60_000L, hex(sender.publicKey), memo)
            net.readAgain()

            assertTrue(ran == "resendKept:1" || ran == "repair:CONFIRMED", "the young mark was not confirmed from its kept wrap: $ran")
            assertEquals(0, bridgePublishes.get(), "a young mark made a new copy while its kept wrap was there")
            assertTrue(HealthRelay.awaitTrue { net.read(memo).isNotEmpty() }, "the kept wrap was not read")
            assertEquals(ScriptedRelay.eventId(wrap.giftWrapJson), net.read(memo).first().eventId, "not the kept wrap")
        }

    @Test
    fun `control - an invite mark of any age is confirmed from its kept wrap with no new publish`() =
        runBlocking {
            val net = Net().also { it.start() }
            val memo = "synthetic zc658 invite"
            net.moveReceiverLine()
            val wrap = net.keptWrap(memo, HOURS_LATE) { true }

            net.own.mode = HealthRelay.Mode.SERVES
            val ran =
                deliverLate(net, { RelayCopyMarksApi.Slots.groupInvite(GROUP, MEMBER) }, wrap, HOURS_LATE * HOUR_MS, "", memo)

            assertTrue(ran == "resendKept:1" || ran == "repair:CONFIRMED", "the invite mark was not confirmed from its kept wrap: $ran")
            assertEquals(0, bridgePublishes.get(), "an invite mark made a new invite")
            // The first publish was refused there (counted too); the kept wrap's re-send is the second.
            val keptId = ScriptedRelay.eventId(wrap.giftWrapJson)
            assertEquals(2, net.own.publishCount(keptId), "the kept invite wrap was not re-sent to ZChat's relay")
        }
}
