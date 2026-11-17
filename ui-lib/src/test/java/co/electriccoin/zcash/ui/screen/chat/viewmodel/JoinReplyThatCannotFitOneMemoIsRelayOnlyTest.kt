package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-517, wire-free half. Drives the same real invite/Join path as the original safe-money pin.
 * The claim probe observes the real process map during preference calls, including the group read immediately
 * after claiming. The 178-character control proves the probe sees the paid path. Zero proposals alone would miss
 * the bug: the central memo guard already refuses the oversized reply after the paid path starts.
 * No shared fixture is changed. All identities, keys and ids are synthetic; no phone or real money is used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinReplyThatCannotFitOneMemoIsRelayOnlyTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
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
        }
    }

    private class Invite(
        val phone: AnnouncePhone,
        val vm: ChatViewModel,
        val seam: HandshakeSeam,
        val memo: String,
        val claimObservations: AtomicInteger,
        val generation: AtomicLong,
        val refuseWaitingWrite: AtomicBoolean,
    )

    private suspend fun invite(length: Int): Invite {
        val phone = AnnouncePhone("u1" + "q".repeat(length - 2))
        phone.link(ADMIN)
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        val observations = AtomicInteger(0)
        val generation = AtomicLong(0)
        val refuseWaitingWrite = AtomicBoolean(false)
        val claimed = ChatViewModel::class.java.getDeclaredField("claimedGroupAccepts").apply { isAccessible = true }
        val observingStore =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val map = claimed.get(null) as Map<*, *>
                if (map.keys.any { (it as? Pair<*, *>)?.second == GID }) observations.incrementAndGet()
                when {
                    method.name == "walletScopeGeneration" -> generation.get()
                    method.name == "setGroupInviteNeedsJoin" && args?.get(1) != null && refuseWaitingWrite.get() -> false
                    method.name == "hashCode" -> System.identityHashCode(p)
                    method.name == "equals" -> p === args?.get(0)
                    method.name == "toString" -> "ZC517ObservingStore"
                    else ->
                        try {
                            method.invoke(phone.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
        AutoSpendVm.setField(vm, "zchatPreferences", observingStore)
        val memo = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 1)
        AnnounceDriver.receive(vm, memo, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(71))
        HandshakeHarness.awaitIdle(vm)
        val waiting = assertNotNull(phone.needsJoin[GID], "fixture: first invite must wait for Join")
        // [test-change-ok: W-137 ZC-922 every invite waits for Join] An invitation now waits for Join before any relay
        // try, so its record has no relay-failed flag yet. These tests start from the state after a try that failed (a
        // Join that came back to waiting); it is written here the way that return writes it (the flag, nothing else).
        val record =
            if (GroupInviteWaitRecord.relayFailed(waiting)) {
                waiting
            } else {
                JSONObject(waiting).put(GroupInviteWaitRecord.RELAY_FAILED, true).toString().also { phone.needsJoin[GID] = it }
            }
        assertTrue(GroupInviteWaitRecord.relayFailed(record), "fixture: reply must have failed its initial relay try")
        assertEquals(0, seam.proposalRequests.get(), "fixture: invitation proposed a payment before Join")
        assertEquals(0, seam.submits.get(), "fixture: invitation paid before Join")
        return Invite(phone, vm, seam, JSONObject(record).getString("memo"), observations, generation, refuseWaitingWrite)
    }

    private suspend fun join(i: Invite) {
        assertTrue(i.vm.joinGroupInvite(GID), "Join must release its own kept reply for the relay attempt")
        HandshakeHarness.awaitIdle(i.vm)
    }

    private fun canSendOnChain(record: String?): Boolean {
        val method =
            runCatching {
                GroupInviteWaitRecord::class.java.getDeclaredMethod("canSendOnChain", String::class.java)
            }.getOrNull()
        assertNotNull(method, "ZC-517: the banner has no memo-based canSendOnChain reading")
        return try {
            method.invoke(GroupInviteWaitRecord, record) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun assertKept(i: Invite) {
        val record = assertNotNull(i.phone.needsJoin[GID], "reply lost: no record waits for the next Join")
        assertEquals(i.memo, JSONObject(record).getString("memo"), "a failed Join replaced the signed reply")
        assertTrue(GroupInviteWaitRecord.relayFailed(record))
        assertFalse(i.phone.heldAccepts.containsKey(GID), "a second held copy stayed beside the waiting record")
        assertEquals(0, i.seam.proposalRequests.get(), "relay-only reply proposed a payment")
        assertEquals(0, i.seam.submits.get(), "relay-only reply submitted a payment")
    }

    @Test
    fun the_actual_213_character_reply_is_relay_only_through_the_banners_reading() =
        runBlocking {
            val i = invite(213)
            assertTrue(i.memo.toByteArray(Charsets.UTF_8).size > ZMSGConstants.MAX_MEMO_SIZE)
            assertFalse(canSendOnChain(i.phone.needsJoin[GID]), "oversized memo still reads as payable")
        }

    @Test
    fun the_actual_178_character_reply_is_payable_through_the_same_reading() =
        runBlocking {
            val i = invite(178)
            assertTrue(i.memo.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE)
            assertTrue(canSendOnChain(i.phone.needsJoin[GID]), "a fitting memo was incorrectly made relay-only")
        }

    @Test
    fun no_relay_on_an_oversized_join_never_enters_the_claimed_paid_step_and_keeps_the_same_reply() =
        runBlocking {
            val i = invite(213)
            join(i)
            assertKept(i)
            assertEquals(0, i.claimObservations.get(), "oversized Join entered the paid claim before the central size guard")
        }

    @Test
    fun an_oversized_join_with_no_relay_tells_the_screen_why_nothing_was_sent() =
        runBlocking {
            val i = invite(213)
            join(i)
            assertKept(i)
            val state = HandshakeHarness.sendMessageState(i.vm)
            assertTrue(state is SendMessageState.Error, "relay-only Join left no visible refusal state")
            val field =
                runCatching { ChatSendCopy::class.java.getDeclaredField("GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT") }.getOrNull()
            assertNotNull(field, "ChatSendCopy has no GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT")
            val copy = field.get(null) as String
            assertEquals(copy, state.message)
            assertNotNull(ChatSendCopy.localizedResource(copy), "relay-only refusal has no localized resource")
            Unit
        }

    @Test
    fun control_a_later_join_sends_the_same_oversized_reply_free_when_a_relay_takes_it() =
        runBlocking {
            val i = invite(213)
            join(i)
            assertKept(i)
            i.phone.nostrKeys[ADMIN] = "ab".repeat(32)
            val published = mutableListOf<String>()
            NostrChatBridge.registerPublisher { memo, _ ->
                published += memo
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-zc517-ack")
            }
            join(i)
            assertEquals(listOf(i.memo), published, "later Join must publish the same kept signed reply once")
            assertEquals(0, i.seam.proposalRequests.get(), "a relay-accepted reply proposed a payment")
            assertEquals(0, i.seam.submits.get(), "a relay-accepted reply paid on-chain")
            assertNull(i.phone.needsJoin[GID], "delivered reply still offers Join")
            assertFalse(i.phone.heldAccepts.containsKey(GID), "delivered reply still has a held copy")
        }

    @Test
    fun control_a_178_character_reply_still_enters_the_paid_path_and_submits_exactly_once() =
        runBlocking {
            val i = invite(178)
            join(i)
            assertTrue(i.claimObservations.get() > 0, "fixture: claim probe did not see the real paid path")
            assertEquals(1, i.seam.proposalRequests.get(), "fitting Join changed its one payment proposal")
            assertEquals(1, i.seam.submits.get(), "fitting Join changed its one payment submit")
            assertNull(i.phone.needsJoin[GID])
            assertFalse(i.phone.heldAccepts.containsKey(GID))
        }

    /** The screen's bound call; absent on baseline, reported as an assertion rather than a compilation error. */
    private fun joinPresented(i: Invite, record: String?): Boolean {
        val method =
            runCatching {
                ChatViewModel::class.java.getMethod("joinGroupInvite", String::class.java, String::class.java)
            }.getOrNull()
        assertNotNull(method, "ZC-517: Join has no presented-record-bound overload")
        return try {
            method.invoke(i.vm, GID, record) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A distinct real signed reply from the current builder, installed as an already accepted replacement offer. */
    private fun replacement(i: Invite, record: String, length: Int = 213): String {
        val accepter = "u1" + "q".repeat(length - 2)
        val memo =
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "groupAcceptMemo",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                .invoke(i.vm, GID, ADMIN, accepter, 2) as String
        return JSONObject(record).put("accepter", accepter).put("memo", memo).toString()
    }

    private data class Publication(
        val memo: String,
        val answer: CompletableDeferred<NostrChatBridge.PublishResult>,
        val job: Job,
    ) {
        suspend fun finish(acks: Int) {
            answer.complete(NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-zc517-controlled-ack"))
            withTimeout(HandshakeHarness.TIMEOUT_MS) { job.join() }
        }
    }

    private fun controlledRelays(i: Invite): Channel<Publication> {
        i.phone.nostrKeys[ADMIN] = "ab".repeat(32)
        val arrivals = Channel<Publication>(Channel.UNLIMITED)
        NostrChatBridge.registerPublisher { memo, _ ->
            val publication = Publication(memo, CompletableDeferred(), currentCoroutineContext()[Job]!!)
            arrivals.send(publication)
            publication.answer.await()
        }
        return arrivals
    }

    private suspend fun Channel<Publication>.next(): Publication = withTimeout(HandshakeHarness.TIMEOUT_MS) { receive() }

    private fun assertNoPayment(i: Invite) {
        assertEquals(0, i.seam.proposalRequests.get(), "relay-only/stale Join proposed a payment")
        assertEquals(0, i.seam.submits.get(), "relay-only/stale Join submitted a payment")
    }

    @Test
    fun a_no_price_banner_cannot_pay_a_fitting_reply_that_replaced_its_presented_record() =
        runBlocking {
            val i = invite(213)
            val shown = i.phone.needsJoin.getValue(GID)
            val current = replacement(i, shown, length = 178)
            i.phone.needsJoin[GID] = current
            assertBoundRefusalHasNoEffects(i, shown, "a no-price banner released a different payable reply")
            assertEquals(current, i.phone.needsJoin[GID], "stale tap changed the replacement offer")
            assertFalse(i.phone.heldAccepts.containsKey(GID), "stale tap claimed the replacement reply")
            assertEquals(0, i.claimObservations.get())
            assertNoPayment(i)
        }

    @Test
    fun a_priced_banner_cannot_join_an_oversized_reply_that_replaced_its_presented_record() =
        runBlocking {
            val i = invite(178)
            val shown = i.phone.needsJoin.getValue(GID)
            val current = replacement(i, shown, length = 213)
            i.phone.needsJoin[GID] = current
            assertBoundRefusalHasNoEffects(i, shown, "a stale priced banner released a different relay-only reply")
            assertEquals(current, i.phone.needsJoin[GID])
            assertFalse(i.phone.heldAccepts.containsKey(GID))
            assertEquals(0, i.claimObservations.get())
            assertNoPayment(i)
        }

    @Test
    fun a_null_presented_record_cannot_authorize_a_reply_that_arrived_before_the_tap() =
        runBlocking {
            val i = invite(178)
            val current = i.phone.needsJoin.getValue(GID)
            assertBoundRefusalHasNoEffects(i, null, "a tap with no presented invitation authorized a paid reply")
            assertEquals(current, i.phone.needsJoin[GID])
            assertNoPayment(i)
        }

    @Test
    fun control_an_older_relay_failure_does_not_publish_a_notice_for_the_later_joins_reply() =
        runBlocking {
            val i = invite(213)
            val initial = i.phone.needsJoin.getValue(GID)
            val arrivals = controlledRelays(i)
            assertTrue(i.vm.joinGroupInvite(GID))
            val first = arrivals.next()
            val nextRecord = replacement(i, initial)
            i.phone.needsJoin[GID] = nextRecord
            assertTrue(i.vm.joinGroupInvite(GID))
            val second = arrivals.next()
            val secondCopy = i.phone.heldAccepts.getValue(GID)
            first.finish(acks = 0)
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(i.vm), "older Join posted a stale refusal")
            assertEquals(secondCopy, i.phone.heldAccepts[GID], "older Join changed the later Join's durable reply")
            assertNull(i.phone.needsJoin[GID], "older Join wrote its reply over the pending later Join")
            assertNoPayment(i)
            second.finish(acks = 1)
            assertNoPayment(i)
            assertNull(i.phone.needsJoin[GID])
            assertFalse(i.phone.heldAccepts.containsKey(GID))
        }

    @Test
    fun control_a_newer_waiting_offer_is_neither_overwritten_nor_given_the_older_joins_notice() =
        runBlocking {
            val i = invite(213)
            val initial = i.phone.needsJoin.getValue(GID)
            val arrivals = controlledRelays(i)
            assertTrue(i.vm.joinGroupInvite(GID))
            val first = arrivals.next()
            val newer = replacement(i, initial)
            i.phone.needsJoin[GID] = newer
            first.finish(acks = 0)
            assertEquals(newer, i.phone.needsJoin[GID], "older return replaced a newer waiting offer")
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(i.vm), "newer offer received an older notice")
            assertNoPayment(i)
        }

    @Test
    fun control_a_wallet_generation_change_cannot_write_or_report_the_old_joins_reply() =
        runBlocking {
            val i = invite(213)
            val arrivals = controlledRelays(i)
            assertTrue(i.vm.joinGroupInvite(GID))
            val first = arrivals.next()
            val outgoingCopy = i.phone.heldAccepts.getValue(GID)
            i.generation.incrementAndGet()
            first.finish(acks = 0)
            assertNull(i.phone.needsJoin[GID], "old Join wrote a waiting record under the new generation")
            assertEquals(outgoingCopy, i.phone.heldAccepts[GID], "old Join changed its outgoing durable copy after generation changed")
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(i.vm), "old wallet posted a stale refusal")
            assertNoPayment(i)
        }

    @Test
    fun control_a_group_left_during_the_relay_try_gets_no_returned_record_or_relay_only_notice() =
        runBlocking {
            val i = invite(213)
            val arrivals = controlledRelays(i)
            assertTrue(i.vm.joinGroupInvite(GID))
            val first = arrivals.next()
            i.phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(i.phone.info(GID).copy(isActive = false))
            first.finish(acks = 0)
            assertNull(i.phone.needsJoin[GID], "a left group received a waiting reply again")
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(i.vm), "a left group received a relay-only notice")
            assertNoPayment(i)
        }

    @Test
    fun control_cancellation_during_the_relay_try_keeps_the_durable_reply_without_a_paid_step_or_notice() =
        runBlocking {
            val i = invite(213)
            val arrivals = controlledRelays(i)
            assertTrue(i.vm.joinGroupInvite(GID))
            val first = arrivals.next()
            first.job.cancel()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { first.job.join() }
            val copy = assertNotNull(i.phone.heldAccepts[GID], "cancelled relay try lost its durable reply")
            assertEquals(i.memo, JSONObject(copy).getString("memo"))
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(i.vm), "cancelled Join posted a false refusal")
            assertEquals(0, i.claimObservations.get())
            assertNoPayment(i)
        }

    @Test
    fun control_a_failed_waiting_record_commit_keeps_the_reply_but_posts_no_successful_return_notice() =
        runBlocking {
            val i = invite(213)
            val arrivals = controlledRelays(i)
            assertTrue(i.vm.joinGroupInvite(GID))
            val first = arrivals.next()
            i.refuseWaitingWrite.set(true)
            first.finish(acks = 0)
            assertNull(i.phone.needsJoin[GID], "fixture: the refused waiting record was saved")
            val copy = assertNotNull(i.phone.heldAccepts[GID], "failed return commit lost its durable reply")
            assertEquals(i.memo, JSONObject(copy).getString("memo"))
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(i.vm), "failed return commit reported successful waiting")
            assertNoPayment(i)
        }

    /** Armed only after invite setup: a refused stale tap must perform no durable write and no relay publish. */
    private suspend fun assertBoundRefusalHasNoEffects(i: Invite, presented: String?, reason: String) {
        val publications = AtomicInteger(0)
        i.phone.nostrKeys[ADMIN] = "ab".repeat(32)
        NostrChatBridge.registerPublisher { _, _ ->
            publications.incrementAndGet()
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-zc517-stale-ack")
        }
        i.phone.calls.clear()
        assertFalse(joinPresented(i, presented), reason)
        HandshakeHarness.awaitIdle(i.vm)
        val writes =
            setOf(
                "setHeldGroupAccept",
                "setGroupInviteNeedsJoin",
                "saveGroupInfo",
                "saveGroupMembers",
                "saveGroupMembersDurably",
                "saveGroupAnnounceState",
                "saveGroupKey",
                "setGroupKeyEpoch",
                "setGroupSelfCreated",
            )
        assertEquals(emptyList(), i.phone.calls.filter { it in writes }, "refused bound Join mutated durable state")
        assertEquals(0, publications.get(), "refused bound Join launched a relay publication")
    }

    @Test
    fun a_matching_presented_fitting_reply_still_proposes_and_submits_once() =
        runBlocking {
            val i = invite(178)
            assertTrue(joinPresented(i, i.phone.needsJoin.getValue(GID)), "matching paid Join was refused")
            HandshakeHarness.awaitIdle(i.vm)
            assertTrue(i.claimObservations.get() > 0, "fixture: matching fitting Join did not enter its paid path")
            assertEquals(1, i.seam.proposalRequests.get(), "matching fitting Join must propose its one payment")
            assertEquals(1, i.seam.submits.get(), "matching fitting Join must submit its one payment")
            assertNull(i.phone.needsJoin[GID])
            assertFalse(i.phone.heldAccepts.containsKey(GID))
        }

    @Test
    fun a_matching_presented_oversized_reply_returns_unchanged_with_its_visible_reason_and_no_paid_claim() =
        runBlocking {
            val i = invite(213)
            assertTrue(joinPresented(i, i.phone.needsJoin.getValue(GID)), "matching relay-only Join was refused")
            HandshakeHarness.awaitIdle(i.vm)
            assertKept(i)
            assertEquals(0, i.claimObservations.get(), "matching oversized Join entered a paid claim")
            val state = HandshakeHarness.sendMessageState(i.vm)
            assertTrue(state is SendMessageState.Error, "matching relay-only Join left no visible refusal state")
            val field =
                runCatching { ChatSendCopy::class.java.getField("GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT") }.getOrNull()
            assertNotNull(field, "ChatSendCopy has no relay-only refusal constant")
            assertEquals(field.get(null) as String, state.message)
        }

    @Test
    fun a_matching_later_presented_oversized_reply_sends_the_same_memo_free_when_relays_accept_it() =
        runBlocking {
            val i = invite(213)
            assertTrue(joinPresented(i, i.phone.needsJoin.getValue(GID)), "first matching relay-only Join was refused")
            HandshakeHarness.awaitIdle(i.vm)
            assertKept(i)
            i.phone.nostrKeys[ADMIN] = "ab".repeat(32)
            val published = mutableListOf<String>()
            NostrChatBridge.registerPublisher { memo, _ ->
                published += memo
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-zc517-matching-ack")
            }
            assertTrue(joinPresented(i, i.phone.needsJoin.getValue(GID)), "later matching relay-only Join was refused")
            HandshakeHarness.awaitIdle(i.vm)
            assertEquals(listOf(i.memo), published, "matching later Join must relay the same kept reply exactly once")
            assertNoPayment(i)
            assertEquals(0, i.claimObservations.get(), "matching relay-only Join entered a paid claim")
            assertNull(i.phone.needsJoin[GID])
            assertFalse(i.phone.heldAccepts.containsKey(GID))
        }
}
