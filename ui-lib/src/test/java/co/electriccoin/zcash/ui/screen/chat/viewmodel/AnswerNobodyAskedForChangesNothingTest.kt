package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.OLD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-599, ZC-941 (FIRST-CONTACT-SPEC revision 4a, 2.1, 5.4, 6.3) and the owner's W-62 — an answer is only ever an answer,
 * only a mined key exchange or answer is acted on, and the automatic paid answer to a key exchange that claims the
 * contact this phone is waiting for waits for the user's tap.
 *
 * Today the history pass maps a received answer's id to the address the answer claims and hands every answer to the
 * handler at once, mined or not ("mayActOnInboundHandshake = true"): an answer nobody asked for stores its key and turns
 * E2E on (routes T and S), so a stranger's key exchange with the same key later skips the chat-request hold and uses a
 * budget unit; an answer under an id that maps to F but claims P raises F's key-changed flag. A key exchange that is not
 * mined is acted on once this process read its own history (RKP "(!kexMined && ownHistoryRead)"), so a memo that never
 * mines draws the automatic answer (17,000 zat) at no cost to its sender. A key exchange that claims the contact this
 * phone began a secure chat with is answered and paid with no tap. And an answer with no key pair of ours spends a
 * budget unit before the sender finds it cannot be built.
 *
 * Wanted: an answer is used only when this phone asked (leg 1: an own-made id with our own mined key exchange under it;
 * leg 2: our own key exchange to that address mined at or before it, the address holding no other key; leg 3: mined at
 * or below the upgrade height), filed only under the address its id maps to; unmined handshakes wait; the awaited
 * contact's key exchange waits for Accept, which pays exactly one answer. The guards (f, h, k) are honest answers that
 * must stay taken. The real history pass over [HandshakePassFixture]. Every address, key, id and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerNobodyAskedForChangesNothingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

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

    private val a = SyntheticUnifiedAddress.mainnet(seed = 59901)
    private val b = SyntheticUnifiedAddress.mainnet(seed = 59902)

    private fun answerFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String {
        val payload = E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey)
        return ZMSGProtocol.createV4KEXAckMessage(convId, sender, payload)
    }

    private fun kexFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))

    // ---- (a) to (c): an answer nobody asked for --------------------------------------------------------------------

    @Test
    fun `(a) a new answer under an id this phone never used changes nothing and pays nothing`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            f.received("zc599-a", answerFrom(a, E2EEncryption.generateKeyPair(), "ZAAA1111"), minedAt = NEW + 10)

            f.pass()

            assertNull("an answer nobody asked for stored a contact key", f.prefs.e2ePeerKey[a])
            assertFalse("an answer nobody asked for turned E2E on", a in f.prefs.e2eEnabled)
            assertNull("an answer nobody asked for mapped its id", f.ids.conv("ZAAA1111"))
            assertNull("an answer nobody asked for gave the address an id", f.ids.peer(a))
            assertEquals(0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(b) a new answer nobody asked for at an address with a contact key raises no key-changed flag`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            f.prefs.e2ePeerKey[a] = E2EEncryption.generateKeyPair().publicKey
            f.open()
            f.received("zc599-b", answerFrom(a, E2EEncryption.generateKeyPair(), "ZBBB1111"), minedAt = NEW + 10)

            f.pass()

            assertFalse("the key-changed flag was raised by an answer nobody asked for", "setE2EKeyChanged" in f.prefs.unexpectedCalls)
            assertFalse("a refused key was recorded from an answer nobody asked for", "rememberRefusedKeyOffer" in f.prefs.unexpectedCalls)
        }

    @Test
    fun `(c) a new answer claiming this phone's own address stores nothing`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            f.received("zc599-c", answerFrom(HandshakePassFixture.SELF, E2EEncryption.generateKeyPair(), "ZCCC1111"), minedAt = NEW + 10)

            f.pass()

            assertNull(f.prefs.e2ePeerKey[HandshakePassFixture.SELF])
            assertEquals(0, f.seam.proposalRequests.get())
        }

    // ---- (d), (t): the stranger's key exchange still waits for Accept --------------------------------------------------

    @Test
    fun `(d) a stranger's key exchange and an answer with the same key in one pass still wait for Accept`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-d-kex", kexFrom(a, stranger, "ZDDD1111"), minedAt = NEW + 10)
            f.received("zc599-d-ack", answerFrom(a, stranger, "ZDDD1111"), minedAt = NEW + 11)

            f.pass()

            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
            assertEquals("a budget unit was used for a request that waits", 0, f.prefs.budgetConsumed.get())
            assertEquals(0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(t) an answer, then a key exchange with the same key in a later pass, waits for Accept, which pays one answer`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-t-ack", answerFrom(a, stranger, "ZTTT1111"), minedAt = NEW + 10)
            f.pass()
            f.received("zc599-t-kex", kexFrom(a, stranger, "ZTTT2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
            assertEquals("a budget unit was used", 0, f.prefs.budgetConsumed.get())
            assertEquals("an answer was paid before Accept", 0, f.seam.proposalRequests.get())

            f.vm.acceptInboundKexRequest(a)
            f.idle()
            assertEquals("Accept pays exactly one answer", 1, f.seam.proposalRequests.get())
            assertEquals("the answer came from the automatic budget", 0, f.prefs.budgetConsumed.get())
        }

    @Test
    fun `(t3) an old answer nobody asked for, then a new key exchange with the same key, waits for Accept and pays nothing`() =
        runBlocking {
            // ZC-599 (re-review 26 Oct, finding 1): leg 3 takes any answer mined at or below the upgrade height (what a
            // restored wallet reads back). Stored as asked with no claim mark and E2E on, its key let a stranger's later key
            // exchange with the same key skip the hold (no first key exchange, no W-62 wait: the key is the stored one) and
            // draw the automatic answer (17,000 zat) or a budget unit. The chat is a shielded one (the real default mode).
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-t3-ack", answerFrom(a, stranger, "ZT3A1111"), minedAt = OLD + 10)
            f.pass()
            f.received("zc599-t3-kex", kexFrom(a, stranger, "ZT3A2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    @Test
    fun `(t4) an old answer at an address we never began with, after our key exchange elsewhere, does not let a stranger skip Accept`() =
        runBlocking {
            // ZC-599 (money recheck 26 Oct, finding 1, scenario A): this wallet once mined a key exchange to another contact
            // (b). A stranger's old answer (leg 3) claims a, where this phone has no key pair, and is mined after that key
            // exchange. Our key exchange went to b, not a: it backs nothing at a. The stranger's later key exchange with the
            // same key must wait for Accept; nothing is paid and no budget unit is spent before the tap.
            val f = HandshakePassFixture(scheduler, a)
            val (ownB, _) = f.ids.store.getOrCreateConversationIdForMessage(b)
            f.open()
            f.ownKex("zc599-t4-own", b, ownB, minedAt = OLD + 1)
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-t4-ack", answerFrom(a, stranger, "ZT4A1111"), minedAt = OLD + 10)
            f.pass()
            f.received("zc599-t4-kex", kexFrom(a, stranger, "ZT4A2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    @Test
    fun `(t5) an old answer older than our own key exchange to that address does not let the stranger skip Accept`() =
        runBlocking {
            // ZC-599 (money recheck 26 Oct, finding 1, scenario B): this phone began at a (our key pair, our mined key
            // exchange to a). A stranger's old answer (leg 3) claiming a is mined BEFORE that key exchange: it cannot answer
            // it. Our key pair at a is no backing for it. The stranger's later key exchange with the same key must wait for
            // Accept; nothing is paid and no budget unit is spent before the tap.
            val f = HandshakePassFixture(scheduler, a)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-t5-ack", answerFrom(a, stranger, "ZT5A1111"), minedAt = OLD + 10)
            f.ownKex("zc599-t5-own", a, own, minedAt = OLD + 20)
            f.pass()
            f.received("zc599-t5-kex", kexFrom(a, stranger, "ZT5A2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    @Test
    fun `(t6) an old honest answer read before our own key exchange to that address in the same block keeps its asked record`() =
        runBlocking {
            // ZC-599 (money recheck 26 Oct, finding 2): a restored wallet reads its history back. The contact's old answer
            // (leg 3) and our own key exchange to that contact are in the same block, the answer first in the pass's order;
            // our key pair is not stored yet (the restore recovers it after the loop). The answer is judged against the whole
            // pass: our own key exchange to a at or below it backs it, so its key keeps the asked record, and the honest
            // contact's repeat key exchange with that key is not held as a stranger's request.
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            val ours = E2EEncryption.generateKeyPair()
            f.received("zc599-t6-ack", answerFrom(a, theirs, "ZT6A1111"), minedAt = OLD + 10)
            val ownKexMemo =
                ZMSGProtocol.createV4KEXMessage(
                    "ZT6O1111",
                    HandshakePassFixture.SELF,
                    E2EEncryption.createKEXPayload(HandshakePassFixture.SELF, ours.publicKey, ours.privateKey),
                )
            f.sent("zc599-t6-own", ownKexMemo, a, minedAt = OLD + 10)
            f.pass()

            assertEquals("the honest answer's key must be taken", theirs.publicKey, f.prefs.e2ePeerKey[a])
            assertEquals(
                "the honest answer backed by our own same-block key exchange lost its asked record",
                theirs.publicKey,
                f.prefs.askedKey[a],
            )

            f.received("zc599-t6-kex", kexFrom(a, theirs, "ZT6A2222"), minedAt = NEW + 12)
            f.pass()

            // W-62: no automatic paid answer without Accept. The honest contact and a stranger cannot be told apart here,
            // so the repeat waits for one Accept tap, as leg 2 does (W62b).
            assertEquals("the repeat key exchange was answered with no tap", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used before the tap", 0, f.prefs.budgetConsumed.get())
            assertEquals("the repeat must wait for Accept", theirs.publicKey, f.prefs.awaitingAccept[a])
        }

    @Test
    fun `(t7) an own key exchange to another contact whose recipients cannot be read does not back a stranger's old answer`() =
        runBlocking {
            // ZC-599 (round 3, GPT P1): this wallet mined a key exchange to contact b, read back after a restore with no
            // readable destination. A stranger's old answer (leg 3) claims a, where this phone never began, under an id
            // that is not our key exchange's, mined after it. Unreadable recipients alone back nothing: the stranger's later
            // key exchange with the same key must wait for Accept; nothing is paid and no budget unit is spent before the tap.
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            f.ownKexRestored("zc599-t7-own", keyFor = b, outputs = emptyList(), convId = "ZT7O1111", minedAt = OLD + 1)
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-t7-ack", answerFrom(a, stranger, "ZT7A1111"), minedAt = OLD + 10)
            f.pass()
            f.received("zc599-t7-kex", kexFrom(a, stranger, "ZT7A2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    @Test
    fun `(t8) an old honest answer under our own key exchange's id, sent to the contact's receiver form, keeps its asked record`() =
        runBlocking {
            // ZC-599 (round 3, recheck finding 2; ZC-1082 case d): a restored wallet names our own key exchange by the
            // contact's receiver form, which no single known address holds. The contact's old answer (leg 3) under OUR key
            // exchange's id (only its recipient could read it) is in the same block, read first. It is backed by the rule
            // leg 2 uses: the same id, and the destination's receivers sit within the address the answer is filed under.
            // So its key keeps the asked record and the contact's repeat key exchange with that key is not held.
            val peer = RestoredSendScan.contact(seed = 59908)
            val peerForm = RestoredSendScan.receiverForm(peer)
            val feeForm = RestoredSendScan.receiverForm(RestoredSendScan.CURRENT_FEE)
            val f = HandshakePassFixture(scheduler, peer)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            val ours = E2EEncryption.generateKeyPair()
            val own = "ZT8O1111"
            f.received("zc599-t8-ack", answerFrom(peer, theirs, own), minedAt = OLD + 10)
            val ownKexMemo =
                ZMSGProtocol.createV4KEXMessage(
                    own,
                    HandshakePassFixture.SELF,
                    E2EEncryption.createKEXPayload(HandshakePassFixture.SELF, ours.publicKey, ours.privateKey),
                )
            f.sentRestored("zc599-t8-own", ownKexMemo, outputs = listOf(feeForm, peerForm), recipient = null, minedAt = OLD + 10)
            f.pass()

            assertEquals("the honest answer's key must be taken", theirs.publicKey, f.prefs.e2ePeerKey[peer])
            assertEquals(
                "the honest answer under our own key exchange's id, sent to the contact's receiver form, lost its asked record",
                theirs.publicKey,
                f.prefs.askedKey[peer],
            )

            f.received("zc599-t8-kex", kexFrom(peer, theirs, "ZT8A2222"), minedAt = NEW + 12)
            f.pass()

            assertNull("the honest contact's repeat key exchange was held as a stranger's request", f.prefs.awaitingAccept[peer])
        }

    @Test
    fun `(t9) after a restart, a stranger's repeat key exchange with an unbacked old answer's key still waits for Accept`() =
        runBlocking {
            // ZC-599 (round 3, recheck finding 3): as (t5) (our key pair and our key exchange to a, mined after the
            // stranger's old answer), but the process restarts between the pass that read the answer and the pass that
            // reads the stranger's key exchange: what this process kept in memory is gone. The repeat must still wait for
            // Accept; nothing is paid and no budget unit is spent before the tap.
            val f = HandshakePassFixture(scheduler, a)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc599-t9-ack", answerFrom(a, stranger, "ZT9A1111"), minedAt = OLD + 10)
            f.ownKex("zc599-t9-own", a, own, minedAt = OLD + 20)
            f.pass()

            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            f.open()
            f.received("zc599-t9-kex", kexFrom(a, stranger, "ZT9A2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    /**
     * ZC-1121 — as (t9), but in the first pass after the restart the stranger's old answer is not read: the user hid it
     * ([hide] true) or its memo cannot be read in that pass. The pass rebuilds the in-memory unbacked set from the answers
     * it reads, so that key never enters it, and the stranger's repeat key exchange (where our key pair stands) skipped the
     * W-62 hold. Wanted: it waits for Accept; nothing is paid and no budget unit is spent before the tap.
     */
    private suspend fun unreadOldAnswerAfterRestart(
        label: String,
        hide: Boolean,
    ) {
        val f = HandshakePassFixture(scheduler, a)
        val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
        f.open()
        val stranger = E2EEncryption.generateKeyPair()
        f.received("$label-ack", answerFrom(a, stranger, "ZTBA1111"), minedAt = OLD + 10)
        f.ownKex("$label-own", a, own, minedAt = OLD + 20)
        f.pass()
        assertEquals("precondition: the unbacked old answer's key is stored", stranger.publicKey, f.prefs.e2ePeerKey[a])
        assertNull("precondition: the unbacked old answer's key has no asked record", f.prefs.askedKey[a])

        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        if (hide) f.hidden += "$label-ack" else f.unreadable += "$label-ack"
        f.open()
        f.received("$label-kex", kexFrom(a, stranger, "ZTBA2222"), minedAt = NEW + 12)
        f.pass()

        assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
        assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
        assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
    }

    @Test
    fun `(t11a) after a restart, with the stranger's old answer hidden, its repeat key exchange still waits for Accept`() =
        runBlocking { unreadOldAnswerAfterRestart("zc1121-t11a", hide = true) }

    @Test
    fun `(t11b) after a restart, with the stranger's old answer unreadable, its repeat key exchange still waits for Accept`() =
        runBlocking { unreadOldAnswerAfterRestart("zc1121-t11b", hide = false) }

    @Test
    fun `(t13) a key exchange queued by a pass that skipped an old answer is held even when a later pass clears the skip first`() =
        runBlocking {
            // ZC-1129: as (t11a), but the key offer handler pass X queued runs late. In the app the pass runs on a
            // background thread and the handler on the main thread, so the main thread may run X's handler while the next
            // pass Y is between writing its own skip flag (Y reads the old answer again: false) and putting the old
            // answer's key into the unbacked set. The handler reads the flag at run time and finds neither: the stranger's
            // repeat is paid with no Accept. Here the Main dispatcher queues the handler, and it runs at Y's first store read
            // after Y cleared the flag. Wanted: the handler judges by its own pass (X skipped an old answer): it waits for
            // Accept; nothing is paid and no budget unit is spent before the tap.
            val label = "zc1129-t13"
            val f = HandshakePassFixture(scheduler, a)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("$label-ack", answerFrom(a, stranger, "ZTD11111"), minedAt = OLD + 10)
            f.ownKex("$label-own", a, own, minedAt = OLD + 20)
            f.pass()
            assertEquals("precondition: the unbacked old answer's key is stored", stranger.publicKey, f.prefs.e2ePeerKey[a])
            assertNull("precondition: the unbacked old answer's key has no asked record", f.prefs.askedKey[a])

            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            Dispatchers.setMain(StandardTestDispatcher(scheduler))
            f.hidden += "$label-ack"
            val vm = f.open()
            f.received("$label-kex", kexFrom(a, stranger, "ZTD12222"), minedAt = NEW + 12)
            // Pass X: the old answer is hidden; its key offer handler is queued, not run.
            f.pass(settle = false)
            assertTrue("precondition: pass X skipped an old received transaction", skipFlag(vm))
            assertEquals("precondition: pass X's handler has not run yet", 0, f.seam.proposalRequests.get())
            assertNull("precondition: pass X's handler has not run yet", f.prefs.awaitingAccept[a])

            // Pass Y: the answer is shown again. At Y's first store read after Y cleared the flag, the main thread runs
            // what X queued.
            f.hidden -= "$label-ack"
            var ranInWindow = false
            f.extra = { _, _ ->
                if (!ranInWindow && !skipFlag(vm)) {
                    ranInWindow = true
                    scheduler.runCurrent()
                }
                ClaimMarkMaps.UNHANDLED
            }
            f.pass(times = 1, settle = false)
            f.extra = null
            f.idle()
            assertTrue("precondition: pass X's handler ran after pass Y cleared the flag", ranInWindow)

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    /**
     * ZC-1128 — a contact stored by ZChat 2.12.4: our key pair and their key from the first exchange, E2E on, no asked
     * record and no claim mark (2.12.4 wrote neither). Their repeat key exchange with that same key arrives after the
     * upgrade. With [hiddenChat] a received pre-upgrade transaction the user hid is in the history; it is a plain chat
     * message (no key exchange, no answer), so it brings no key and has nothing to do with this exchange.
     */
    private suspend fun honestRepeatOf2124Contact(
        label: String,
        hiddenChat: Boolean,
    ): HandshakePassFixture {
        val f = HandshakePassFixture(scheduler, a, firstContact = false)
        val theirs = E2EEncryption.generateKeyPair()
        f.prefs.e2ePeerKey[a] = theirs.publicKey
        f.open()
        if (hiddenChat) {
            f.received("$label-chat", ZMSGProtocol.createV4ReplyMessage("ZTE11111", a, "see you at noon"), minedAt = OLD + 5)
            f.hidden += "$label-chat"
        }
        f.received("$label-kex", kexFrom(a, theirs, "ZTE12222"), minedAt = NEW + 12)
        f.pass()
        return f
    }

    @Test
    fun `(t14) guard - an honest 2_12_4 contact's repeat key exchange with its stored key is not held`() =
        runBlocking {
            val f = honestRepeatOf2124Contact("zc1128-t14", hiddenChat = false)

            assertNull("the honest contact's repeat key exchange was held as a stranger's request", f.prefs.awaitingAccept[a])
        }

    @Test
    fun `(t15) a hidden old plain chat message does not hold an honest 2_12_4 contact's repeat key exchange`() =
        runBlocking {
            // ZC-1128: since ZC-1121 a pass that skips ANY received pre-upgrade transaction (hidden, or memo unreadable)
            // holds every same-key repeat at an address with our key pair and no asked record. A hidden transaction is
            // never shown, so the pass may still read its memo: here it is a plain chat message, not an answer, so it
            // could not have brought this key. The honest contact's repeat is not held, exactly as in (t14). (t11a, t11b:
            // a hidden or unreadable stranger answer still holds.)
            val f = honestRepeatOf2124Contact("zc1128-t15", hiddenChat = true)

            assertNull(
                "ZC-1128: a hidden old plain chat message held an honest 2.12.4 contact's repeat key exchange for Accept",
                f.prefs.awaitingAccept[a],
            )
        }

    /** ZC-1129: the view model's in-memory "last pass skipped an old received transaction" flag. */
    private fun skipFlag(vm: ChatViewModel): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("oldInboundUnreadInPass")
            .apply { isAccessible = true }
            .getBoolean(vm)

    @Test
    fun `(t12) an old stranger answer under a foreign id, read before our same-block key exchange, lets no key exchange skip Accept`() =
        runBlocking {
            // ZC-1123 (old history only): this phone began at a (our key pair, our own id, our key exchange to a). A
            // stranger's pre-upgrade answer claiming a, under an id that is not ours, is mined in the same block as our key
            // exchange and read before it. Judged after the loop it is backed exactly as a leg 2 answer under a foreign id
            // is, so it must carry what leg 2 gives it there (the claim mark, W62b): the stranger's later key exchange with
            // the same key waits for Accept; nothing is paid and no budget unit is spent before the tap. (t8, the answer
            // under our own key exchange's id, stays unclaimed.)
            val f = HandshakePassFixture(scheduler, a)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("zc1123-t12-ack", answerFrom(a, stranger, "ZTC21111"), minedAt = OLD + 10)
            f.ownKex("zc1123-t12-own", a, own, minedAt = OLD + 10)
            f.pass()
            f.received("zc1123-t12-kex", kexFrom(a, stranger, "ZTC22222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used for a stranger's request", 0, f.prefs.budgetConsumed.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[a])
        }

    // ---- legs: honest answers stay taken (guards), others are not ------------------------------------------------------

    @Test
    fun `(f) guard - leg 1, an answer under the id this phone made for the address, after our mined key exchange, is taken`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            f.ownKex("zc599-f-kex", a, own, minedAt = NEW + 10)
            f.received("zc599-f-ack", answerFrom(a, theirs, own), minedAt = NEW + 12)

            f.pass()

            assertEquals("the answer to our own key exchange must be taken", theirs.publicKey, f.prefs.e2ePeerKey[a])
            assertEquals(a, f.ids.conv(own))
        }

    @Test
    fun `(h) guard - leg 2, the honest answer under the responder's own id after our key exchange to that address, is taken`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            f.ownKex("zc599-h-kex", a, own, minedAt = NEW + 10)
            f.received("zc599-h-ack", answerFrom(a, theirs, "ZHHH2222"), minedAt = NEW + 12)

            f.pass()

            assertEquals("the honest answer under the responder's id must be taken", theirs.publicKey, f.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(j) leg 2 refused - our key exchange mined after the answer, or sent to another address`() =
        runBlocking {
            val later = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = later.ids.store.getOrCreateConversationIdForMessage(a)
            later.open()
            later.received("zc599-j1-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZJJJ2222"), minedAt = NEW + 10)
            later.ownKex("zc599-j1-kex", a, own, minedAt = NEW + 12)
            later.pass()
            assertNull("an answer older than our key exchange to that address was taken", later.prefs.e2ePeerKey[a])

            val other = HandshakePassFixture(scheduler, a, firstContact = false)
            val (ownB, _) = other.ids.store.getOrCreateConversationIdForMessage(b)
            other.open()
            other.ownKex("zc599-j2-kex", b, ownB, minedAt = NEW + 10)
            other.received("zc599-j2-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZJJJ3333"), minedAt = NEW + 12)
            other.pass()
            assertNull("an answer from an address our key exchange did not go to was taken", other.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(k) guard - leg 3, an old answer under another id is taken as before`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc599-k-ack", answerFrom(a, theirs, "ZKKK2222"), minedAt = OLD + 10)

            f.pass()

            assertEquals("an answer mined at or below the upgrade height must be read as before", theirs.publicKey, f.prefs.e2ePeerKey[a])
        }

    // ---- (q) the filing rule ----------------------------------------------------------------------------------------------

    @Test
    fun `(q) an answer under an id that maps to F but claims P is never filed under P and, unasked, changes nothing at F`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            f.prefs.e2ePeerKey[a] = E2EEncryption.generateKeyPair().publicKey
            f.ids.store.setConversationMapping("ZQQQ1111", a)
            f.open()
            f.received("zc599-q-ack", answerFrom(b, E2EEncryption.generateKeyPair(), "ZQQQ1111"), minedAt = NEW + 10)

            f.pass()

            assertNull("the answer was filed under the address it claims", f.prefs.e2ePeerKey[b])
            assertEquals(a, f.ids.conv("ZQQQ1111"))
            assertFalse("an unasked answer raised the key-changed flag at F", "setE2EKeyChanged" in f.prefs.unexpectedCalls)
        }

    // ---- (u) the budget ---------------------------------------------------------------------------------------------------

    @Test
    fun `(u) a key exchange at a chat with E2E on and no key pair of ours yet waits for Accept and pays nothing`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.prefs.e2eEnabled += a
            f.open()
            val claimed = E2EEncryption.generateKeyPair()
            f.received("zc599-u-kex", kexFrom(a, claimed, "ZUUU1111"), minedAt = NEW + 10)

            f.pass()

            // ZC-634 (W-62; review T7aD/T7a 19 Oct finding 1): E2E on with no key pair of ours yet is a chat this phone is
            // setting up (setE2EEnabled before the key pair, a Reset's wipe): a key exchange claiming it waits for Accept
            // like one at a chat whose key pair exists. Nothing is paid and no budget unit is spent before the tap.
            assertEquals(
                "no hold: a stranger's key exchange claiming a chat this phone is setting up was answered",
                claimed.publicKey,
                f.prefs.awaitingAccept[a],
            )
            assertEquals("an answer was proposed before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was spent before Accept", 0, f.prefs.budgetConsumed.get())
        }

    // ---- U1, U3: only a mined handshake is acted on (ZC-941) ------------------------------------------------------------

    @Test
    fun `(U1) a key exchange that is not mined, claiming the contact this phone began a chat with, changes nothing and pays nothing`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            f.open()
            f.received("zc599-u1-kex", kexFrom(a, E2EEncryption.generateKeyPair(), "ZUU11111"), minedAt = null)

            f.pass()

            assertNull("an unmined key exchange stored a key", f.prefs.e2ePeerKey[a])
            assertTrue("an unmined key exchange was marked processed", f.prefs.processedKexTx.isEmpty())
            assertNull("an unmined key exchange mapped its id", f.ids.conv("ZUU11111"))
            assertEquals("an unmined key exchange drew a payment", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(U3) an answer that is not mined is not taken or marked processed, even under an id this phone made`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            f.ownKex("zc599-u3-kex", a, own, minedAt = NEW + 10)
            f.received("zc599-u3-ack", answerFrom(a, E2EEncryption.generateKeyPair(), own), minedAt = null)

            f.pass()

            assertNull("an unmined answer stored a key", f.prefs.e2ePeerKey[a])
            assertTrue("an unmined answer was marked processed", f.prefs.processedKexTx.isEmpty())
        }

    // ---- W-62: the awaited contact's key exchange waits for the tap -----------------------------------------------------

    @Test
    fun `(W62) a key exchange that claims the contact this phone began a chat with waits for Accept, which pays one answer`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            f.ownKex("zc599-w62-own", a, own, minedAt = NEW + 5)
            val claimed = E2EEncryption.generateKeyPair()
            f.received("zc599-w62-kex", kexFrom(a, claimed, "ZW621111"), minedAt = NEW + 10)

            f.pass()

            assertEquals("the awaited contact's key exchange was answered with no tap", 0, f.seam.proposalRequests.get())
            assertEquals("it must wait for Accept", claimed.publicKey, f.prefs.awaitingAccept[a])
            assertEquals("a budget unit was used before the tap", 0, f.prefs.budgetConsumed.get())

            f.vm.acceptInboundKexRequest(a)
            f.idle()
            assertEquals("Accept pays exactly one answer", 1, f.seam.proposalRequests.get())
            assertEquals(claimed.publicKey, f.prefs.sentKexAck[a])
        }

    @Test
    fun `(W62b) a stranger's answer claiming the awaited contact, then its key exchange with the same key, still waits for Accept`() =
        runBlocking {
            // ZC-599 (re-review 26 Oct, finding 2): this phone began a secure chat with the contact and waits (our key pair, no
            // contact key). A stranger mines an answer under a fresh id claiming the contact after our key exchange: leg 2
            // takes it (claim mark, asked record, E2E on). Its key exchange with the same key then skipped the W-62 hold
            // (that wait needs no stored contact key) and was answered and paid with no tap. Shielded chat (the default).
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            f.ownKex("zc599-w62b-own", a, own, minedAt = NEW + 5)
            val claimed = E2EEncryption.generateKeyPair()
            f.received("zc599-w62b-ack", answerFrom(a, claimed, "ZW62AAAA"), minedAt = NEW + 8)
            f.pass()
            f.received("zc599-w62b-kex", kexFrom(a, claimed, "ZW62BBBB"), minedAt = NEW + 10)
            f.pass()

            assertEquals("the awaited contact's key exchange was answered with no tap", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used before the tap", 0, f.prefs.budgetConsumed.get())
            assertEquals("it must wait for Accept", claimed.publicKey, f.prefs.awaitingAccept[a])
        }
}
