package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.Nip17
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.4 Z1 and Z12 item 4, §14.7): the first try's wrap and the Retry's
 * wrap of one message reach a 2.13 receiver as one bubble; a retried payment request is one request with one Pay.
 *
 * At HEAD a Retry with nothing kept makes a new rumor (a new second, a new id), so the receiver files two rows under two
 * `nmsg-` ids: the text twice, or two payable cards. Wanted: the Retry re-makes the same rumor (Nip17.send's new last
 * parameter, rumorCreatedAtSec, keeps the first second while the seal and the wrap are made now), so both wraps carry one
 * rumor id and the receiver's existing skip of a known id shows one. Real Nip17 wraps with synthetic keys, decoded with
 * Nip17.receive and handed to a real ChatViewModel inbound reader through NostrChatBridge.dispatch, in both orders. The
 * helper that makes the Retry's wrap uses the new rumor time when it exists and today's Nip17.send when it does not.
 * Controls (pass before and after): the same wrap twice is one bubble; two different rumors with the same text are two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetriedRelayMessageShowsOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val sender = identity()
    private val recipient = identity()
    private val peer = SyntheticUnifiedAddress.mainnet(4_923)

    private companion object {
        const val HOUR_SEC = 60L * 60
        const val DAY_SEC = 24 * HOUR_SEC
        const val AMOUNT = 18_000_000L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private fun identity(): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64).also { SecureRandom().nextBytes(it) })

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** A wrap of [content] whose seal and wrap are made at [sealSec]; its rumor at [rumorSec] when Nip17 can, else at [sealSec]. */
    private fun wrap(
        content: String,
        rumorSec: Long,
        sealSec: Long,
    ): String {
        val withRumorTime = Nip17::class.java.methods.firstOrNull { it.name == "send" && it.parameterCount == 7 }
        val sent =
            try {
                if (withRumorTime != null) {
                    withRumorTime.invoke(Nip17, sender.privateKey, sender.publicKey, recipient.publicKey, content, sealSec, null, rumorSec)
                } else {
                    // Today: a Retry with nothing kept is a new send, its rumor made now.
                    Nip17.send(sender.privateKey, sender.publicKey, recipient.publicKey, content, sealSec)
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        return (sent as Nip17.SentMessage).giftWrapJson
    }

    private fun inbound(wrapJson: String): NostrInboxManager.InboundDm {
        val m = Nip17.receive(wrapJson, recipient.privateKey)
        return NostrInboxManager.InboundDm(
            senderPubkeyHex = hex(m.senderPubkey),
            content = m.content,
            createdAtSec = m.createdAtSec,
            eventId = m.eventId,
            rumorId = m.rumorId,
            recipientPubkeyHex = hex(recipient.publicKey),
            rumorTags = m.rumorTags,
            wrapCreatedAtSec = m.wrapCreatedAtSec,
        )
    }

    private class Receiver(
        val store: RelayKeyStore,
        val vm: ChatViewModel,
        val paid: MutableStateFlow<Set<String>>,
    )

    private suspend fun receiver(): Receiver {
        val store =
            RelayKeyStore().apply {
                pubkeys[peer] = hex(sender.publicKey)
                modes[peer] = ConversationMode.OPEN
            }
        val vm = RelayKeyChatScreen.chatViewModel(store)
        val paid = MutableStateFlow<Set<String>>(emptySet())
        AutoSpendVm.setField(vm, "paidRequestIds", paid)
        AutoSpendVm.setField(vm, "unsavedPaidMarks", ConcurrentHashMap.newKeySet<String>())
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        RelayKeyChatScreen.start(vm, "observeNostrInbound")
        return Receiver(store, vm, paid)
    }

    private suspend fun deliver(
        r: Receiver,
        wrapJson: String,
    ) {
        val before = r.store.seen.size
        NostrChatBridge.dispatch(inbound(wrapJson), r.store.proxy)
        // The reader runs on the unconfined Main dispatcher; give its store write a moment.
        repeat(50) {
            if (r.store.seen.size > before) return
            delay(10)
        }
        delay(50)
    }

    private fun bubbles(r: Receiver): List<ChatMessage> = RelayKeyChatScreen.rows(r.vm).filter { !it.isOutgoing && !it.isSystemNote }

    /** The first try at [t0] and the Retry's wrap made [later] seconds after it, delivered in [order]. */
    private suspend fun twoTries(
        content: String,
        t0: Long,
        later: Long,
        firstThenRetry: Boolean,
    ): Receiver {
        val first = wrap(content, rumorSec = t0, sealSec = t0)
        val retry = wrap(content, rumorSec = t0, sealSec = t0 + later)
        val r = receiver()
        if (firstThenRetry) {
            deliver(r, first)
            deliver(r, retry)
        } else {
            deliver(r, retry)
            deliver(r, first)
        }
        return r
    }

    @Test
    fun `the first try and the Retry of one message are one bubble and one stored row, in both orders`() =
        runBlocking {
            val t0 = System.currentTimeMillis() / 1000 - 600
            for (order in listOf(true, false)) {
                val r = twoTries("synthetic zc421 shown once", t0, later = 600, firstThenRetry = order)
                assertEquals("order first-then-retry=$order: ${bubbles(r).map { it.id }}", 1, bubbles(r).size)
                assertEquals("order first-then-retry=$order: stored rows ${r.store.rows.keys}", 1, r.store.rows.size)
                r.vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `a retried payment request is one request with one Pay, also 25 hours and 20 days later`() =
        runBlocking {
            val request = ZMSGProtocol.createPaymentRequest(AMOUNT, peer, "synthetic zc421 rent")
            for (age in listOf(600L, 25 * HOUR_SEC, 20 * DAY_SEC)) {
                for (order in listOf(true, false)) {
                    val t0 = System.currentTimeMillis() / 1000 - age
                    val r = twoTries(request, t0, later = age, firstThenRetry = order)
                    val cards = bubbles(r).filter { it.paymentRequest != null }
                    assertEquals("age $age, order $order: ${cards.map { it.id }}", 1, cards.size)
                    assertEquals("age $age, order $order: two Pay buttons", 1, cards.count { it.paymentRequest?.isPaid == false })
                    r.vm.viewModelScope.cancel()
                }
            }
        }

    @Test
    fun `a request paid before its Retry's copy arrives stays one request, shown as paid`() =
        runBlocking {
            val request = ZMSGProtocol.createPaymentRequest(AMOUNT, peer, "synthetic zc421 paid")
            val t0 = System.currentTimeMillis() / 1000 - 3_600
            val first = wrap(request, rumorSec = t0, sealSec = t0)
            val retry = wrap(request, rumorSec = t0, sealSec = t0 + 3_600)
            val r = receiver()
            deliver(r, first)
            val id = bubbles(r).single().id
            r.paid.value = setOf(id)

            deliver(r, retry)

            val cards = bubbles(r).filter { it.paymentRequest != null }
            assertEquals("${cards.map { it.id }}", listOf(id), cards.map { it.id })
            assertTrue("the one request is not the paid one", id in r.paid.value)
        }

    @Test
    fun `control - the same wrap twice is one bubble`() =
        runBlocking {
            val t0 = System.currentTimeMillis() / 1000 - 60
            val once = wrap("synthetic zc421 same wrap", rumorSec = t0, sealSec = t0)
            val r = receiver()
            deliver(r, once)
            deliver(r, once)
            assertEquals(1, bubbles(r).size)
        }

    @Test
    fun `control - two different rumors with the same text are two bubbles`() =
        runBlocking {
            val t0 = System.currentTimeMillis() / 1000 - 60
            val r = receiver()
            deliver(r, Nip17.send(sender.privateKey, sender.publicKey, recipient.publicKey, "ok", t0).giftWrapJson)
            deliver(r, Nip17.send(sender.privateKey, sender.publicKey, recipient.publicKey, "ok", t0 + 1).giftWrapJson)
            assertEquals(2, bubbles(r).size)
        }
}
