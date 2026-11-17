package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.SELF
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.CURRENT_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.PREVIOUS_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.mix
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.receiverForm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Out
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ZC-1082 (FIRST-CONTACT-SPEC N-13, R-14, flows 11 and 17; plan cases a, d, e, g, h) — one resolver files every own send.
 *
 * After a restore the wallet library names each output of an own send by the single receiver its note was paid to (a
 * short Unified Address with that receiver only), never by the contact's address text. Once the fee output is known by
 * its receivers (the batch's second pair), the send's recipient is the contact's receiver form, which no chat, contact or
 * conversation id has: the chat list files the send under a nameless chat, FC-ID ties this phone's own key exchange's id
 * to that form for good (with the own-id record that vouches for the answer's key: R-14 at the receiver), and a rebuilt
 * group lists its member under it.
 *
 * Wanted: (a) a receiver form held by exactly ONE address this phone knows (the contact book, a chat's address) is filed
 * under that address, in any output order; (d) held by several, it is kept as it is and nothing vouches for a key there
 * (no own-id record anywhere, no key stored without its claim mark); (e) FC-ID gives an own key exchange's id to the
 * known contact, and the contact's answer is filed under the contact (flows 11 and 17), never under the form; (g) a group
 * rebuilt from our own invite lists the member under its address, never a fee form or the member's receiver form; (h)
 * the control: full address texts (before a restore) are filed as they are. Every contact address is synthetic; the fee
 * addresses are the two the app paid. Failure messages name addresses by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredOwnSendIsFiledUnderItsContactTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val peer = contact(seed = 10823)
    private val peerForm = receiverForm(peer)
    private val other = contact(seed = 10824)
    private val otherForm = receiverForm(other)

    /** An address made of another wallet's Sapling receiver and the contact's Orchard receiver (case d). */
    private val forged = mix(contact(seed = 10825), 2, peer, 3)
    private val feeForm = receiverForm(CURRENT_FEE)
    private val previousFeeForm = receiverForm(PREVIOUS_FEE)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(a) a restored send to a known contact is filed under the contact's address in any output order, with no fee chat`() =
        runBlocking {
            val txs =
                listOf(
                    Tx(1, message("restored 1"), listOf(Out(feeForm), Out(peerForm))),
                    Tx(2, message("restored 2"), listOf(Out(peerForm), Out(feeForm))),
                    Tx(3, message("restored 3"), listOf(Out(previousFeeForm), Out(peerForm), Out(null, ownAccount = true))),
                    Tx(4, message("restored 4"), listOf(Out(feeForm), Out(otherForm))),
                )

            val conversations = RestoredSendScan(peer, known = listOf(peer, other)).scan(txs)

            assertEquals(
                "chats after a restore: ${rows(conversations)}",
                listOf("contact", "other contact"),
                chatsOf(conversations).sorted(),
            )
            assertEquals(3, conversations.single { it.peerAddress == peer }.messages.size)
        }

    @Test
    fun `(d) a receiver form two known addresses hold is kept as it is`() =
        runBlocking {
            val txs = listOf(Tx(1, message("ambiguous"), listOf(Out(feeForm), Out(peerForm))))

            val conversations = RestoredSendScan(peer, known = listOf(peer, forged)).scan(txs)

            assertEquals("chats after a restore: ${rows(conversations)}", listOf("contact form"), chatsOf(conversations))
        }

    @Test
    fun `(h) control - full address texts before a restore are filed as they are`() =
        runBlocking {
            val txs =
                listOf(
                    Tx(1, message("full 1"), listOf(Out(CURRENT_FEE), Out(peer))),
                    Tx(2, message("full 2"), listOf(Out(peer), Out(CURRENT_FEE), Out(null, ownAccount = true))),
                    Tx(3, message("full 3"), listOf(Out(other), Out(CURRENT_FEE))),
                )

            val known = RestoredSendScan(peer, known = listOf(peer)).scan(txs)
            val unknown = RestoredSendScan(peer).scan(txs)

            assertEquals("chats, contact known: ${rows(known)}", listOf("contact", "other contact"), chatsOf(known).sorted())
            assertEquals("chats, nobody known: ${rows(unknown)}", listOf("contact", "other contact"), chatsOf(unknown).sorted())
        }

    @Test
    fun `(e) FC-ID gives our own key exchange's id to the known contact, and its answer is filed under the contact`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            // The contact is known here, and vouched for by this phone: the user's contact book holds it.
            f.book += peer
            f.open()
            val own = "ZOWN1082"
            f.ownKexRestored("zc1082-e-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 5)
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc1082-e-ack", answerFrom(peer, theirs, own), minedAt = NEW + 7)

            f.pass()

            assertEquals("our own id is mapped to", "contact", label(f.ids.conv(own)))
            assertEquals("the own-id record names", "contact", label(f.ids.own(own)))
            assertEquals("the answer's key is not the contact's", theirs.publicKey, f.prefs.e2ePeerKey[peer])
            assertNull("a key is filed under the contact's form", f.prefs.e2ePeerKey[peerForm])
            assertNull("a key is filed under the fee's form", f.prefs.e2ePeerKey[feeForm])
            assertEquals("nothing is paid", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(d) with two known addresses holding the receiver nothing vouches for a key`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            f.ids.store.setConversationMapping("ZKNA1082", peer)
            f.ids.store.setConversationMapping("ZKNB1082", forged)
            f.open()
            val own = "ZAMB1082"
            // An ordinary own message under another id of ours, to the same receiver (FC-ID's message branch).
            f.sentRestored(
                "zc1082-d-msg",
                ZMSGProtocol.createV4InitMessage("ZMSG1082", SELF, "synthetic hello"),
                listOf(feeForm, peerForm),
                recipient = peerForm,
                minedAt = NEW + 2,
            )
            f.ownKexRestored("zc1082-d-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 5)
            f.received("zc1082-d-ack", answerFrom(peer, E2EEncryption.generateKeyPair(), own), minedAt = NEW + 7)

            f.pass()

            assertEquals("own-id records were written", emptySet<String>(), f.ids.ownKeys())
            for ((name, address) in listOf("contact" to peer, "forged" to forged, "contact form" to peerForm, "fee form" to feeForm)) {
                assertTrue(
                    "a key is stored at the $name with no claim mark",
                    f.prefs.e2ePeerKey[address] == null || f.prefs.claims[address] != null,
                )
            }
            assertEquals("nothing is paid", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(g) a group rebuilt from our own restored invite lists the member under its address, never a fee or receiver form`() =
        runBlocking {
            for ((variant, recipient) in listOf("history names the fee form" to feeForm, "history names the member form" to peerForm)) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val f = HandshakePassFixture(scheduler, peer)
                f.ids.store.setConversationMapping("ZMEM1082", peer)
                val saved = Collections.synchronizedList(mutableListOf<String>())
                f.extra = { method, a ->
                    if (method.name == "saveGroupMembers") {
                        saved += a[1] as String
                        null
                    } else {
                        ClaimMarkMaps.UNHANDLED
                    }
                }
                f.open()
                wireUnsetFields(f.vm)
                val invite =
                    ZMSGGroupProtocol.createGroupInviteCompact(
                        MA3.GID,
                        "Restored group",
                        SELF,
                        0,
                        "c3ludGhldGljLWdyb3VwLWtleS0xMDgyLTAwMDAwMDA=",
                        isSessionEncrypted = false,
                    )
                f.sentRestored("zc1082-g-$variant", invite, listOf(feeForm, peerForm), recipient = recipient, minedAt = NEW + 3)

                f.pass()

                val members =
                    saved
                        .lastOrNull()
                        ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                        .orEmpty()
                        .map { label(it.address) }
                // Owner W-86 (27 Oct 2026): M1 is known only from a chat it opened (not vouched: no contact, no own-id
                // record), so the rebuilt row goes under the form our invite actually paid until M1 is vouched.
                assertEquals("$variant: the rebuilt group's members", listOf("contact form", "self"), members.sorted())
            }
        }

    /**
     * The group path reads ViewModel fields the handshake fixture's constructor-free ViewModel leaves unset (its pending
     * invites, locks and counters): each unset map, set, lock or counter gets an empty one, as the constructor makes it.
     */
    private fun wireUnsetFields(vm: ChatViewModel) {
        for (field in ChatViewModel::class.java.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            if (field.get(vm) != null) continue
            val type = field.type
            val value: Any? =
                when {
                    type.isAssignableFrom(ConcurrentHashMap::class.java) -> ConcurrentHashMap<Any, Any>()
                    type.isAssignableFrom(java.util.LinkedHashSet::class.java) -> Collections.synchronizedSet(LinkedHashSet<Any>())
                    type.isAssignableFrom(java.util.ArrayList::class.java) -> Collections.synchronizedList(ArrayList<Any>())
                    type == kotlinx.coroutines.sync.Mutex::class.java -> kotlinx.coroutines.sync.Mutex()
                    type == AtomicBoolean::class.java -> AtomicBoolean()
                    type == AtomicLong::class.java -> AtomicLong()
                    type == AtomicInteger::class.java -> AtomicInteger()
                    type == Any::class.java -> Any()
                    else -> null
                }
            if (value != null) field.set(vm, value)
        }
    }

    private fun answerFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            sender,
            E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey),
        )

    private fun message(text: String) = ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text)

    private fun label(address: String?): String =
        when (address) {
            null -> "none"
            peer -> "contact"
            peerForm -> "contact form"
            other -> "other contact"
            otherForm -> "other contact form"
            forged -> "forged"
            SELF -> "self"
            CURRENT_FEE -> "current fee address"
            PREVIOUS_FEE -> "previous fee address"
            feeForm -> "current fee form"
            previousFeeForm -> "previous fee form"
            else -> "other (${address.length} chars)"
        }

    private fun chatsOf(conversations: List<Conversation>) = conversations.map { label(it.peerAddress) }

    private fun rows(conversations: List<Conversation>) = conversations.map { "${label(it.peerAddress)}: ${it.messages.size} rows" }
}
