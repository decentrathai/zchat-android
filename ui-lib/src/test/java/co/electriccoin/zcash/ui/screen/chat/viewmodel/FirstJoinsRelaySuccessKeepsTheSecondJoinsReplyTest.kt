package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.heldInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.SELF
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.TIMEOUT_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.PASS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.joinNoWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-646 — a late Join acts only on its own reply when a later Join for the same group has replaced it.
 *
 * Both taps enter the real joinGroupInvite path. Each relay publish waits for the test's ack. The later waiting
 * record models an already accepted offer, rather than repeating removal/re-invite receiver tests. Its signed memo
 * uses the current groupAcceptMemo builder and a later key epoch; its paid-reply flag exists before the second tap.
 * The identical-fields variant models separate offers whose held reply contains no distinguishing invite field.
 * No existing fixture or production handler is changed. All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstJoinsRelaySuccessKeepsTheSecondJoinsReplyTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        try {
            sendScopes.close()
        } finally {
            NostrChatBridge.unregisterPublisher()
            Dispatchers.resetMain()
            AutoSpendVm.resetProcessState()
            GroupInviteDriver.resetKeystoneExplanation()
        }
    }

    private class Publication(
        val memo: String,
        val recipient: String,
    ) {
        val answer = CompletableDeferred<NostrChatBridge.PublishResult>()
    }

    private class Relays {
        val calls = AtomicInteger(0)
        val arrivals = Channel<Publication>(Channel.UNLIMITED)

        fun install() {
            NostrChatBridge.registerPublisher { memo, recipient ->
                calls.incrementAndGet()
                val publication = Publication(memo, recipient)
                arrivals.send(publication)
                publication.answer.await()
            }
        }

        suspend fun next(): Publication = withTimeout(TIMEOUT_MS) { arrivals.receive() }
    }

    private data class JoinAttempt(
        val publication: Publication,
        val job: Job,
    )

    private class Phone(
        seed: Int,
    ) {
        val inviter = SyntheticUnifiedAddress.mainnet(seed)
        val groupId = "GJOIN646$seed"
        val prefs =
            StrangerInvitePrefs(consent = false).apply {
                e2eEnabled += inviter
                base.session(inviter, complete = true)
            }
        val relayKey = AtomicBoolean(false)
        val store: ZchatPreferences =
            JoinHeldCopy.intercepting(prefs.proxy) { name, args ->
                if (name == "getPeerNostrPubkey" && relayKey.get() && args[0] == inviter) {
                    JoinHeldCopy.RELAY_KEY
                } else {
                    PASS
                }
            }
        val heldOnDisk get() = prefs.base.heldGroupAccepts
    }

    private class Scenario(
        val phone: Phone,
        val seam: HandshakeSeam,
        val accounts: SelectedAccountFeed,
        val vm: ChatViewModel,
        val relays: Relays,
        val storedAtSubmit: MutableList<Boolean>,
        val heights: HeightFeed,
    )

    /** Every failure path cancels and joins process sends before Main or static state can be reset. */
    private fun scenario(
        seed: Int,
        failFirstProposalWith: Exception? = null,
        onFirstAttempt: (Scenario.() -> Unit)? = null,
        check: suspend Scenario.() -> Unit,
    ) =
        runBlocking {
            try {
                val phone = Phone(seed)
                val storedAtSubmit = Collections.synchronizedList(mutableListOf<Boolean>())
                lateinit var activeScenario: Scenario
                val seam =
                    HandshakeSeam(
                        phone.inviter,
                        failFirstProposalWith = failFirstProposalWith,
                        onFirstAttempt = onFirstAttempt?.let { callback -> { callback(activeScenario) } },
                        onSubmit = { storedAtSubmit += phone.heldOnDisk.containsKey(phone.groupId) },
                    )
                val heights = HeightFeed()
                val accounts = SelectedAccountFeed.create()
                val vm = chatViewModelWith(phone.store, seam, heights, accountSource = accounts).also(AutoSpendVm::wireBootstrapFields)
                GroupInviteDriver.processInvite(vm, phone.groupId, GroupInviteDriver.plaintextInvite(phone.inviter))
                awaitIdle(vm)
                val waiting = phone.prefs.needsJoin.getValue(phone.groupId)
                // [test-change-ok: W-137 ZC-922 every invite waits for Join] An invitation now waits for Join before any
                // relay try, so its record has no price flag yet. These tests start from the state after a try that
                // failed (a Join that came back to waiting); it is written the way that return writes it (the flag).
                val initial =
                    if (GroupInviteWaitRecord.relayFailed(waiting)) {
                        waiting
                    } else {
                        JSONObject(waiting)
                            .put(GroupInviteWaitRecord.RELAY_FAILED, true)
                            .toString()
                            .also { phone.prefs.needsJoin[phone.groupId] = it }
                    }
                assertTrue("fixture: the first offer carries the paid-reply price flag", GroupInviteWaitRecord.relayFailed(initial))
                assertSignedCurrentReply(phone, memo(initial), epoch = 0)
                assertEquals("fixture: nothing is proposed before either Join", 0, seam.proposalRequests.get())
                assertFalse("fixture: standing handshake consent is absent", phone.prefs.base.consent)
                val info =
                    ZMSGGroupProtocol.deserializeGroupInfo(
                        phone.prefs.base.groupInfo
                            .getValue(phone.groupId),
                    )
                assertTrue("fixture: the joined group is active", info?.isActive == true)
                assertTrue("fixture: no earlier reply is held", phone.heldOnDisk.isEmpty())
                val relays = Relays()
                phone.relayKey.set(true)
                relays.install()
                activeScenario = Scenario(phone, seam, accounts, vm, relays, storedAtSubmit, heights)
                activeScenario.check()
            } finally {
                withContext(NonCancellable) {
                    val jobs =
                        HandshakeHarness
                            .heldAcceptScope()
                            ?.coroutineContext
                            ?.job
                            ?.children
                            ?.toList()
                            .orEmpty()
                    jobs.forEach { it.cancel() }
                    withTimeout(TIMEOUT_MS) { jobs.joinAll() }
                }
            }
        }

    private fun memo(record: String): String = JSONObject(record).getString("memo")

    private fun assertSignedCurrentReply(phone: Phone, reply: String, epoch: Int) {
        assertEquals(GroupMessageType.GROUP_ACCEPT, ZMSGGroupProtocol.parseMessageType(reply))
        val payload = JSONObject(reply.substringAfter(":" + phone.groupId + ":"))
        assertEquals(SELF, payload.getString("accepter"))
        assertEquals(epoch, payload.getInt("key_epoch"))
        val publicKey = phone.store.getE2EOurPublicKey(phone.inviter)!!
        assertEquals(publicKey, payload.getString("accepter_pub"))
        assertTrue(
            "fixture: the current reply's accepter signature verifies",
            E2EEncryption.verify(
                publicKey,
                ZMSGGroupProtocol.groupAcceptSignedData(phone.groupId, SELF, publicKey),
                payload.getString("sig"),
            ),
        )
        assertTrue("fixture: this reply fits the paid single-memo path", reply.toByteArray(Charsets.UTF_8).size <= 512)
    }

    /**
     * Materialize the accepted later offer in today's HeldGroupAccept.encode format. The production memo builder
     * signs it; no invented suffix or field changes the memo. The offer/removal receiver pipeline is outside scope.
     * With identicalFields the later offer deliberately carries the same held-reply fields.
     */
    private fun Scenario.secondOffer(identicalFields: Boolean = false): String {
        val first = phone.heldOnDisk.getValue(phone.groupId)
        val reply =
            if (identicalFields) {
                memo(first)
            } else {
                try {
                    ChatViewModel::class.java
                        .getDeclaredMethod(
                            "groupAcceptMemo",
                            String::class.java,
                            String::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType,
                        ).apply { isAccessible = true }
                        .invoke(vm, phone.groupId, phone.inviter, SELF, 1) as String
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        assertSignedCurrentReply(phone, reply, if (identicalFields) 0 else 1)
        if (!identicalFields) assertNotEquals("fixture: the later offer has its own memo", memo(first), reply)
        val record =
            JSONObject()
                .put("inviter", phone.inviter)
                .put("accepter", SELF)
                .put("memo", reply)
                .put(GroupInviteWaitRecord.RELAY_FAILED, true)
                .toString()
        assertTrue("fixture: the later offer's waiting record commits", phone.store.setGroupInviteNeedsJoin(phone.groupId, record))
        assertTrue("fixture: the later offer carries the paid-reply price flag", GroupInviteWaitRecord.relayFailed(record))
        return reply
    }

    private suspend fun Scenario.tapJoin(): JoinAttempt {
        val scope = checkNotNull(HandshakeHarness.heldAcceptScope())
        val previous =
            scope.coroutineContext.job.children
                .toSet()
        val waiting = phone.prefs.needsJoin.getValue(phone.groupId)
        assertTrue("fixture: the real Join released this waiting offer", joinNoWait(vm, phone.groupId))
        val publication = relays.next()
        val job =
            scope.coroutineContext.job.children
                .filter { it !in previous }
                .single()
        assertEquals("fixture: this Join published its own waiting memo", memo(waiting), publication.memo)
        assertEquals("fixture: the reply goes to the inviter's relay key", JoinHeldCopy.RELAY_KEY, publication.recipient)
        assertEquals("fixture: the tap kept its own reply on disk", publication.memo, memo(phone.heldOnDisk.getValue(phone.groupId)))
        assertTrue("fixture: the tap owns an in-memory held reply", heldInMemory(phone.groupId))
        assertFalse("fixture: the tap cleared only its waiting record", phone.prefs.needsJoin.containsKey(phone.groupId))
        assertEquals("fixture: both publishes remain free and held", 0, seam.proposalRequests.get())
        return JoinAttempt(publication, job)
    }

    private data class ReplacementJoin(
        val memo: String,
        val copy: String,
        val job: Job,
    )

    /** Only synchronous Join work runs here: this also works inside the old proposal's callback. */
    private fun Scenario.startReplacementJoin(): ReplacementJoin {
        val scope = checkNotNull(HandshakeHarness.heldAcceptScope())
        val previous =
            scope.coroutineContext.job.children
                .toSet()
        val reply = secondOffer()
        assertTrue("fixture: the later real Join released its waiting offer", joinNoWait(vm, phone.groupId))
        val job =
            scope.coroutineContext.job.children
                .filter { it !in previous }
                .single()
        val copy = phone.heldOnDisk.getValue(phone.groupId)
        assertEquals("fixture: the later real Join kept its own memo", reply, memo(copy))
        assertTrue("fixture: the later real Join owns a held entry", heldInMemory(phone.groupId))
        assertFalse("fixture: the later real Join consumed its waiting record", phone.prefs.needsJoin.containsKey(phone.groupId))
        assertEquals("fixture: only the older reply has been proposed", 1, seam.proposalRequests.get())
        assertEquals("fixture: the older proposal has not submitted yet", 0, seam.submits.get())
        return ReplacementJoin(reply, copy, job)
    }

    private suspend fun Scenario.awaitReplacement(replacement: ReplacementJoin): JoinAttempt {
        val publication = relays.next()
        assertEquals("fixture: the later Join publishes its own memo", replacement.memo, publication.memo)
        assertEquals("fixture: the later reply uses the inviter's relay key", JoinHeldCopy.RELAY_KEY, publication.recipient)
        assertFalse("fixture: the later relay answer is still held", publication.answer.isCompleted)
        return JoinAttempt(publication, replacement.job)
    }

    private suspend fun JoinAttempt.finish(acks: Int) {
        assertTrue(
            "fixture: the publish was released only once",
            publication.answer.complete(NostrChatBridge.PublishResult(acks, null)),
        )
        withTimeout(TIMEOUT_MS) { job.join() }
        assertFalse("fixture: the Join sender failed or was cancelled", job.isCancelled)
    }

    private fun Scenario.assertPaidOwnReplyOnce(expected: String) {
        assertEquals("the later Join must propose its own reply exactly once", 1, seam.proposalRequests.get())
        assertEquals("the proposed memo must belong to the later Join", expected, firstMemo(seam.proposalUris.single()))
        assertEquals("the later Join must submit exactly once", 1, seam.submits.get())
        assertEquals("the kept copy must be gone before the submit", listOf(false), storedAtSubmit.toList())
        assertTrue("a submitted reply leaves no held copy", phone.heldOnDisk.isEmpty())
        assertFalse("a submitted reply leaves no held entry", heldInMemory(phone.groupId))
        assertNull("a submitted reply does not wait for Join", phone.prefs.needsJoin[phone.groupId])
    }

    private suspend fun Scenario.successThenLaterFailure(identicalFields: Boolean = false) {
        val first = tapJoin()
        val secondMemo = secondOffer(identicalFields)
        val second = tapJoin()
        val secondCopy = phone.heldOnDisk.getValue(phone.groupId)
        first.finish(acks = 1)
        val copyAfterFirst = phone.heldOnDisk[phone.groupId]
        val entryAfterFirst = heldInMemory(phone.groupId)
        val recordAfterFirst = phone.prefs.needsJoin[phone.groupId]
        second.finish(acks = 0)
        assertTrue("the first Join's ack removed the later Join's kept copy", secondCopy == copyAfterFirst)
        assertTrue("the first Join's ack removed the later Join's held entry", entryAfterFirst)
        assertNull("the first Join's ack must not invent a waiting record", recordAfterFirst)
        assertPaidOwnReplyOnce(secondMemo)
    }

    @Test
    fun `the first Join's relay ack keeps the second Join's reply until its own paid submit`() =
        scenario(6461) { successThenLaterFailure() }

    @Test
    fun `separate Joins with identical held fields still have separate ownership`() =
        scenario(6462) { successThenLaterFailure(identicalFields = true) }

    @Test
    fun `the first Join's return to waiting cannot replace the second Join's copy or write its older record`() =
        scenario(6463) {
            val first = tapJoin()
            val secondMemo = secondOffer()
            val second = tapJoin()
            val secondCopy = phone.heldOnDisk.getValue(phone.groupId)
            accounts.sealedOrchard()
            first.finish(acks = 0)
            val copyAfterFirst = phone.heldOnDisk[phone.groupId]
            val entryAfterFirst = heldInMemory(phone.groupId)
            val recordAfterFirst = phone.prefs.needsJoin[phone.groupId]
            accounts.ironwoodSpendable()
            second.finish(acks = 0)
            assertTrue("the old return-to-waiting deleted or overwrote the later kept copy", secondCopy == copyAfterFirst)
            assertTrue("the old return-to-waiting removed the later held entry", entryAfterFirst)
            assertNull("the old return-to-waiting wrote its older waiting record", recordAfterFirst)
            assertPaidOwnReplyOnce(secondMemo)
        }

    @Test
    fun `the first Join's relay failure cannot pay the second Join while its own relay attempt is still open`() =
        scenario(6464) {
            val first = tapJoin()
            secondOffer()
            val second = tapJoin()
            first.finish(acks = 0)
            val proposalsWhileSecondOpen = seam.proposalRequests.get()
            val submitsWhileSecondOpen = seam.submits.get()
            assertFalse("fixture: the later Join's relay try is still open", second.publication.answer.isCompleted)
            second.finish(acks = 1)
            assertEquals("an older Join proposed a reply while the later Join could still send it free", 0, proposalsWhileSecondOpen)
            assertEquals("an older Join submitted the later Join's reply", 0, submitsWhileSecondOpen)
            assertEquals("both replies went free or were replaced; neither may be proposed", 0, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(heldInMemory(phone.groupId))
            assertNull(phone.prefs.needsJoin[phone.groupId])
        }

    @Test
    fun `control - one Join whose relays refuse pays its own reply once`() =
        scenario(6465) {
            val only = tapJoin()
            assertSignedCurrentReply(phone, only.publication.memo, epoch = 0)
            only.finish(acks = 0)
            assertPaidOwnReplyOnce(only.publication.memo)
            assertFalse("a paid Join cannot be tapped again without another waiting offer", joinNoWait(vm, phone.groupId))
            assertEquals(1, relays.calls.get())
            assertEquals(1, seam.proposalRequests.get())
        }

    @Test
    fun `control - a second tap without another waiting offer sends nothing extra`() =
        scenario(6466) {
            val only = tapJoin()
            assertFalse("a second tap without a waiting record must refuse", joinNoWait(vm, phone.groupId))
            assertEquals("the second tap must not publish", 1, relays.calls.get())
            assertEquals("the second tap must not propose a payment", 0, seam.proposalRequests.get())
            only.finish(acks = 1)
            assertEquals(1, relays.calls.get())
            assertEquals(0, seam.proposalRequests.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(heldInMemory(phone.groupId))
            assertNull(phone.prefs.needsJoin[phone.groupId])
        }

    @Test
    fun `an older claimed Join waiting for a block cannot submit or erase the later Join's reply`() =
        scenario(6467, failFirstProposalWith = stillConfirming()) {
            accounts.onlyConfirmingIronwood()
            val first = tapJoin()
            val firstCopy = phone.heldOnDisk.getValue(phone.groupId)
            assertTrue(
                "fixture: the older relay refuses exactly once",
                first.publication.answer.complete(NostrChatBridge.PublishResult(0, null)),
            )
            withTimeout(TIMEOUT_MS) { heights.height.subscriptionCount.first { it > 0 } }
            assertTrue("fixture: the older Join is still waiting for the next block", first.job.isActive)
            assertFalse("fixture: the old reply has been claimed from memory", heldInMemory(phone.groupId))
            assertTrue("fixture: the claimed old copy is kept during the block wait", firstCopy == phone.heldOnDisk[phone.groupId])
            assertEquals("fixture: the first Join proposed once before its block wait", 1, seam.proposalRequests.get())
            assertEquals(
                "fixture: the first proposal belongs to the older Join",
                first.publication.memo,
                firstMemo(
                    seam.proposalUris
                        .single(),
                ),
            )
            assertEquals("fixture: nothing submitted before the block wait", 0, seam.submits.get())
            accounts.ironwoodSpendable()
            val replacement = startReplacementJoin()
            val second = awaitReplacement(replacement)
            heights.advance()
            withTimeout(TIMEOUT_MS) { first.job.join() }
            assertFalse("fixture: the older Join sender failed or was cancelled", first.job.isCancelled)
            val submitsAfterFirst = seam.submits.get()
            val copyAfterFirst = phone.heldOnDisk[phone.groupId]
            val entryAfterFirst = heldInMemory(phone.groupId)
            val recordAfterFirst = phone.prefs.needsJoin[phone.groupId]
            assertFalse("fixture: the later Join still has an open free relay try", second.publication.answer.isCompleted)
            second.finish(acks = 1)
            assertEquals("an older claimed Join submitted after a later Join replaced it during its block wait", 0, submitsAfterFirst)
            assertTrue("the older block-wait claim erased the later Join's kept copy", replacement.copy == copyAfterFirst)
            assertTrue("the older block-wait claim removed the later Join's held entry", entryAfterFirst)
            assertNull("the older block-wait claim wrote an obsolete waiting record", recordAfterFirst)
            assertEquals("the later free relay ack must leave only the older initial proposal", 1, seam.proposalRequests.get())
            assertEquals("neither replaced nor free reply may submit", 0, seam.submits.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(heldInMemory(phone.groupId))
            assertNull(phone.prefs.needsJoin[phone.groupId])
        }

    @Test
    fun `a later Join created inside the older proposal cannot be erased or paid by the old pre-submit`() {
        var replacement: ReplacementJoin? = null
        scenario(6468, onFirstAttempt = { replacement = startReplacementJoin() }) {
            val first = tapJoin()
            first.finish(acks = 0)
            val pending = checkNotNull(replacement) { "fixture: the old proposal callback created the later Join" }
            assertEquals(
                "fixture: the built proposal belongs to the older Join",
                first.publication.memo,
                firstMemo(
                    seam.proposalUris
                        .single(),
                ),
            )
            val copyAfterFirst = phone.heldOnDisk[phone.groupId]
            val entryAfterFirst = heldInMemory(phone.groupId)
            val recordAfterFirst = phone.prefs.needsJoin[phone.groupId]
            val submitsAfterFirst = seam.submits.get()
            // The callback proved the replacement existed before the old proposal returned. Its publisher may enter
            // only after that callback; this test does not claim that the free publisher was already suspended then.
            val second = awaitReplacement(pending)
            second.finish(acks = 1)
            assertEquals("the older pre-submit paid after a later real Join replaced its reply", 0, submitsAfterFirst)
            assertTrue("the older pre-submit erased the later Join's kept copy", pending.copy == copyAfterFirst)
            assertTrue("the older pre-submit removed the later Join's held entry", entryAfterFirst)
            assertNull("the older pre-submit wrote an obsolete waiting record", recordAfterFirst)
            assertEquals("the later free relay ack must leave only the older built proposal", 1, seam.proposalRequests.get())
            assertEquals("neither replaced nor free reply may submit", 0, seam.submits.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(heldInMemory(phone.groupId))
            assertNull(phone.prefs.needsJoin[phone.groupId])
        }
    }
}
