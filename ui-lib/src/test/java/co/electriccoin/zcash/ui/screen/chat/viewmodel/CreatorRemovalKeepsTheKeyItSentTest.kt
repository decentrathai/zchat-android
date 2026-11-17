package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ZC-778 (review of 12 Oct 2026, finding 1) — the group's creator must never abandon a key it already sent.
 *
 * A removal sends the new group key to the remaining members (a signed kick, paid on-chain for a member with no relay
 * route) and only afterwards saved it on the creator's own phone. When the roster save in between was refused, the
 * removal returned before the key and its number were saved and showed nothing: the members moved to a key the creator
 * did not hold, the creator could not read them, and a second try sent a different key under the same number, which
 * the members refuse as a replay.
 *
 * The real GroupViewModel runs a confirmed removal of X from a group of the creator C, A and X. A has no relay route,
 * so its key memo is paid; X has one, so its notice is free. The creator's store is the MA3 fake behind a wrapper that
 * can refuse three writes, each without delegating it:
 * - the combined roster and removal-record save (reports false);
 * - every write of the key number;
 * - every write of a group key made before anything was sent.
 * A refused write that returns a Boolean returns false; one that returns nothing throws, as a store does whose
 * `commit()` failed.
 *
 * What a remaining member holds is read from the paid memo itself: A unwraps the key with its own session, and A's
 * real ChatViewModel takes the memos the way the chain scan hands them over. Keys and memos are compared, never
 * printed in a failure message. All identities, keys and payments are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreatorRemovalKeepsTheKeyItSentTest {
    private val scopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        scopes.close()
        MA3.resetRuntime()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a refused roster save after the key memos leaves the creator on the key the members got`() =
        onDefault {
            val w = world()
            try {
                w.faults.roster = true
                w.removeX()

                val sent = w.keysPaidToA()
                assertEquals("setup: exactly one key memo was paid before the save was refused", 1, sent.size)
                assertEquals("setup: the key went out under number 2", 2, sent.single().number)
                assertEquals("setup: the removal reached its combined save once", 1, w.faults.rosterSaves.get())
                assertTrue("the creator does not hold the key it sent to A", sent.single().key == w.admin.base.keys["$GID|2"])
                assertEquals("the creator did not move to the key number it sent", 2, w.admin.base.epochs[GID])
                assertEquals(
                    "the refused save was not told with its own notice (ZC-791: the key memo was paid, the member stays here)",
                    GroupSendResult.RemovalIncomplete(GID, pendingMembers = 0, refusal = RemovalNotSavedNotice.EN),
                    w.vm.groupSendEvent.value,
                )
                // Money: the one quoted key memo, once. No notice, no announce, no second question by itself.
                assertEquals("the refused save paid for more than the one quoted key memo", 1, w.seam.proposals.size)
                assertTrue("a memo was submitted twice", w.seam.proposals.toList() == w.seam.submitted.toList())
                assertTrue("the refused save still sent a notice or ran a relay pass", w.net.published.isEmpty())
                assertEquals("the refused save asked again by itself", null, w.vm.paidActionConfirmation.value)
                assertEquals("the refused save changed the roster", MemberStatus.ACTIVE, w.admin.status(X))

                // A now writes under the key it was sent. The creator's own ChatViewModel must read it.
                val chat = MA3.chatVm(w.admin)
                MA3.chain(chat, messageFromA(w, sent.single(), TEXT), tx = 41)
                assertTrue(
                    "the creator cannot read A's message under the key it sent: ${storedTexts(w.admin)}",
                    TEXT in storedTexts(w.admin),
                )
            } finally {
                w.close()
            }
        }

    @Test
    fun `a removal retried after refused saves never sends a second key under the same number`() =
        onDefault {
            val w = world()
            try {
                // First try: the key memo is paid, then the phone refuses the roster and the key number.
                w.faults.roster = true
                w.faults.number = true
                w.removeX()
                assertEquals("setup: the first try paid exactly one key memo", 1, w.keysPaidToA().size)
                assertEquals("setup: the first key went out under number 2", 2, w.keysPaidToA().single().number)
                assertEquals("setup: the refused number write must not reach the store", 1, w.admin.base.epochs[GID])

                // The phone saves again. The user removes X again: a new question, then its fan-out.
                w.faults.roster = false
                w.faults.number = false
                w.vm.consumeGroupSendEvent()
                w.removeX()

                val sent = w.keysPaidToA()
                assertEquals("the retry did not pay exactly one more key memo", 2, sent.size)
                val first = sent[0]
                val second = sent[1]
                assertTrue(
                    "a retried removal sent a second, different key under number ${first.number}: A keeps the first and " +
                        "refuses the second as a replay",
                    second.number != first.number || second.key == first.key,
                )
                assertTrue("the retry's key number ${second.number} is not above ${first.number}", second.number > first.number)
                assertTrue("the creator lost the first key it sent", first.key == w.admin.base.keys["$GID|${first.number}"])
                assertEquals("the creator is not on the retry's key number", second.number, w.admin.base.epochs[GID])
                assertTrue("the creator does not hold the retry's key", second.key == w.admin.base.keys["$GID|${second.number}"])
                assertEquals("the retry did not take X out", MemberStatus.LEFT, w.admin.status(X))
                assertEquals("the retry did not raise the removal floor to its number", second.number, w.admin.base.floors[GID])

                // Money: each try was asked; the retry paid its two quoted memos (A's key, R X to A), each once.
                val paid = w.seam.proposals.toList()
                assertEquals("the two tries paid for something that was not quoted", 3, paid.size)
                assertTrue("a memo was submitted twice", paid == w.seam.submitted.toList())
                assertEquals("a memo was paid twice", 3, paid.distinct().size)

                // A takes both memos in order, as its chain scan hands them over, and ends on the creator's key.
                val chatA = MA3.chatVm(w.phoneA)
                w.kickMemosPaidToA().forEachIndexed { i, memo -> MA3.chain(chatA, memo, tx = 21 + i) }
                assertEquals(
                    "A is not on the creator's key number",
                    w.admin.base.epochs[GID],
                    w.phoneA.base.epochs[GID],
                )
                assertTrue(
                    "A and the creator hold different keys under the current number",
                    w.admin.base.keys["$GID|${second.number}"] == w.phoneA.base.keys["$GID|${second.number}"],
                )
            } finally {
                w.close()
            }
        }

    @Test
    fun `a refused key save before the fan-out sends and pays nothing`() =
        onDefault {
            val w = world()
            try {
                val rosterBefore = w.roster()
                val stateBefore = w.admin.state()
                val keysBefore = w.keys()
                w.faults.keyBeforeFirstMemo = true
                w.removeX()

                assertEquals("a removal whose new key could not be saved still paid for memos", 0, w.seam.proposals.size)
                assertEquals("something was submitted", 0, w.seam.submitted.size)
                assertTrue("a notice, a sealed key or an announce went over the relays", w.net.published.isEmpty())
                assertEquals("the key save was not tried exactly once before anything was sent", 1, w.faults.keyRefusals.get())
                assertEquals("the roster save ran although the key was not saved", 0, w.faults.rosterSaves.get())
                assertEquals("the roster changed", rosterBefore, w.roster())
                assertEquals("the removal record changed", stateBefore, w.admin.state())
                assertTrue("a key was adopted", keysBefore == w.keys())
                assertEquals("the key number moved", 1, w.admin.base.epochs[GID])
                assertEquals("the removal floor moved", null, w.admin.base.floors[GID])
                assertEquals(
                    "the user was not told with the storage notice",
                    GroupSendResult.RemovalIncomplete(GID, pendingMembers = 0, refusal = ChatSendCopy.STORAGE_PAUSED_PAID_STEP),
                    w.vm.groupSendEvent.value,
                )
                assertEquals("the removal asked again by itself", null, w.vm.paidActionConfirmation.value)
            } finally {
                w.close()
            }
        }

    @Test
    fun `a removal that goes through has its key in the store when the first key memo is paid`() =
        onDefault {
            val w = world()
            try {
                val keyAtFirstMemo = AtomicReference<String?>()
                val numberAtFirstMemo = AtomicReference<Int?>()
                w.seam.onProposal = { n, _ ->
                    if (n == 1) {
                        keyAtFirstMemo.set(w.admin.base.keys["$GID|2"])
                        numberAtFirstMemo.set(w.admin.base.epochs[GID])
                    }
                }
                w.removeX()

                val sent = w.keysPaidToA().single()
                assertEquals("setup: the key went out under number 2", 2, sent.number)
                assertTrue(
                    "the new key was not in the creator's store when its first memo was paid (a kill there loses it)",
                    sent.key == keyAtFirstMemo.get(),
                )
                assertEquals("the key number moved before the removal was saved", 1, numberAtFirstMemo.get())
                assertEquals("the removal did not take X out", MemberStatus.LEFT, w.admin.status(X))
                assertEquals("the creator is not on the new key number", 2, w.admin.base.epochs[GID])
                assertTrue("the creator's key is not the one A got", sent.key == w.admin.base.keys["$GID|2"])
                assertEquals("the removal floor did not move to the new number", 2, w.admin.base.floors[GID])
                assertEquals("the removal did not pay exactly its two quoted memos", 2, w.seam.proposals.size)
                assertTrue("a memo was submitted twice", w.seam.proposals.toList() == w.seam.submitted.toList())
                assertEquals("a clean removal showed a problem", null, w.vm.groupSendEvent.value)
            } finally {
                w.close()
            }
        }

    // ------------------------------------------------------------------ harness

    private fun onDefault(block: suspend () -> Unit) =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            // The fixture polls with a real timeout on Default; runTest drives Main and reports unhandled VM failures.
            withContext(Dispatchers.Default) { block() }
        }

    /** The writes the creator's phone refuses, and how often each was met. */
    private class Faults {
        @Volatile var roster = false

        @Volatile var number = false

        @Volatile var keyBeforeFirstMemo = false
        val rosterSaves = AtomicInteger()
        val keyRefusals = AtomicInteger()
    }

    private class World(
        val admin: AnnouncerPhone,
        val phoneA: AnnouncerPhone,
        val net: RelayNet,
        val seam: AnnounceSeam,
        val fixture: GroupPaidOnceFixture,
        val vm: GroupViewModel,
        val faults: Faults,
    ) {
        /** The creator's stored roster, as its JSON. */
        fun roster(): String? = admin.base.rosters[GID]

        /** Every group key the creator holds, by "group|number". */
        fun keys(): Map<String, String> = admin.base.keys.toMap()

        suspend fun close() {
            vm.viewModelScope.cancel()
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                vm.viewModelScope.coroutineContext.job
                    .join()
            }
        }
    }

    /** One key A was paid: the number it came under and the key A unwraps from the memo with its own session. */
    private class SentKey(
        val number: Int,
        val key: String,
    )

    private suspend fun world(): World {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE))
        val phoneA = MA3.memberPhone(A, admin)
        MA3.memberPhone(X, admin)
        admin.base.nostrKeys[X] = NPUB.getValue(X)
        MA3.alreadyAnnounced(admin, listOf(A, X))
        val net = RelayNet().also { it.register() }
        val seam = AnnounceSeam()
        val fixture = GroupPaidOnceFixture()
        val faults = Faults()
        val store =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                val combinedSave = method.name == "saveGroupMembersDurably" && args!!.size == 3
                if (combinedSave) faults.rosterSaves.incrementAndGet()
                when {
                    combinedSave && faults.roster -> false
                    method.name == "setGroupKeyEpoch" && faults.number -> refuse(method)
                    method.name == "saveGroupKey" && faults.keyBeforeFirstMemo && seam.proposals.isEmpty() -> {
                        faults.keyRefusals.incrementAndGet()
                        refuse(method)
                    }
                    else ->
                        try {
                            method.invoke(admin.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
        val vm = MA3.groupVm(admin, fixture, seam)
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, store)
        MA3.load(vm, fixture)
        // Leave real free announcement debt after the load: a relay pass run by mistake would send A to X.
        admin.ledgers[GID] = admin.ledger().filterNot { it.rAddr == X && it.sAddr == A }
        net.clear()
        assertEquals("setup: the group starts at key number 1", 1, admin.base.epochs[GID])
        return World(admin, phoneA, net, seam, fixture, vm, faults)
    }

    /** What a store whose write failed does: false from a write that reports, an exception from one that does not. */
    private fun refuse(method: Method): Any =
        if (method.returnType == java.lang.Boolean.TYPE) false else throw IOException("synthetic ZC-778 store fault")

    /** Tap Remove for X, check the price question, and confirm it. */
    private suspend fun World.removeX() {
        val paidBefore = seam.proposals.size
        fixture.awaitNewJobs(vm) { vm.kickMember(GID, X) }
        val quote = vm.paidActionConfirmation.value
        assertNotNull("setup: the removal did not ask its price question", quote)
        assertEquals(PaidAction.GROUP_REMOVE_MEMBER, quote!!.action)
        assertEquals("setup: the question counts A's key memo and R X to A; X's notice is free", 2, quote.onChainMessages)
        assertEquals("setup: asking paid something", paidBefore, seam.proposals.size)
        MA3.confirm(vm, fixture)
    }

    /** The signed kicks A was paid, in order. */
    private fun World.kickMemosPaidToA(): List<String> =
        seam.submitted
            .toList()
            .filter { it.recipient == A && ZMSGGroupProtocol.parseMessageType(it.memo) == GroupMessageType.GROUP_KICK }
            .map { it.memo }

    private fun World.keysPaidToA(): List<SentKey> =
        kickMemosPaidToA().map { memo ->
            val payload = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
            assertEquals("setup: the kick names X", X, payload.getString("kicked"))
            SentKey(
                number = payload.getInt("new_epoch"),
                key =
                    checkNotNull(E2EEncryption.decrypt(payload.getString("enc_key"), phoneA.base.sessionWith(C))) {
                        "A cannot unwrap the key it was sent"
                    },
            )
        }

    /** A group message A writes under [sent], signed with A's key for the creator. */
    private fun messageFromA(
        w: World,
        sent: SentKey,
        text: String,
    ): String =
        ZMSGGroupProtocol.createGroupMsgMessage(
            GID,
            1L,
            sent.number,
            A,
            text,
            ZMSGGroupProtocol.decodeGroupKey(sent.key),
        ) { data -> E2EEncryption.sign(MA3.keyFor(w.phoneA, C).privateKey, data) }

    private fun storedTexts(phone: AnnouncerPhone): List<String> =
        phone.base.messages[GID]
            ?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("decrypted") } } }
            .orEmpty()

    private companion object {
        const val TEXT = "A writes under the key it was sent"
    }
}
