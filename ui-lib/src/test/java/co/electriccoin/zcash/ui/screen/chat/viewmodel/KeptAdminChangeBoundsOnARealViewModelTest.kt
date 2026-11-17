package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-704, KI-25 pair 1b (R19); review of train T7a2 (13 Oct 2026), findings 2 and 3 (ZC-952) and 6 — CONTROLS on a
 * real ChatViewModel for the branches of the keep step (`ChatViewModel.keepRelayAdminChange`) that no view-model test
 * reached: the length limit, the full list, and a kept change whose own write the store refuses. Every case passes at
 * `e224d16ac`: each pins what the app does today, which is what KI-25-SPEC 3.6b says (R19 "bounds"; register ZC-704,
 * "what is still lost", item 2). Nothing here is a RED. ZC-952's decision changed (b), which its RED commit flipped.
 *
 * - (a) a removal whose payload has exactly 2,048 characters is kept, one of 2,049 is not.
 * - (b) the 48 places of a group are taken by three finished chats whose safety numbers were not compared. ZC-952
 *   (decision of 13 Oct night, W-57) changed what this case pinned: a removal from the admin's chat, which ranks no
 *   higher, now takes the oldest entry of another unverified chat instead of being lost. Once the user has compared
 *   the admin's safety number, the admin's next change takes a place from one of the three as before, and the first
 *   complete pass after the confirmation applies both: the removed member is no longer listed.
 * - (c) the store refuses the write of a kept removal. The relay event stays marked seen; the change is held by the
 *   store's memory (a refused `commit()` keeps the new value there) and the pass after the confirmation applies it.
 *   What a kill before the next successful write loses is not shown here: nothing would bring the change back.
 *
 * Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptAdminChangeBoundsOnARealViewModelTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    private val created: Instant = Instant.ofEpochMilli(T)
    private val relayTime: Instant = Instant.ofEpochMilli(T + 60_000)

    /** A drifted phone with the creating invite from Y in its history; the safety number is compared when [verified]. */
    private fun phone(verified: Boolean): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            if (verified) p.verified += Y
            p.base.nostrKeys[Y] = Y_NOSTR
        }

    private fun keptIds(p: AdminCheckPhone): List<String> = AdminCheck.keptChanges(p).map { it.getString("id") }

    private fun completePass(vm: ChatViewModel) {
        val pass = AdminCheck.beginPass(vm)
        AdminCheck.endPass(vm, pass, complete = true)
    }

    /** The admin's removal of a member whose address text is padded so that the payload has exactly [length] characters. */
    private fun kickWithPayloadOf(
        p: AdminCheckPhone,
        length: Int,
    ): String {
        // The signature's length varies by a character or two, so the padding is fitted to the memo it gives.
        var padding = 1_200
        val newKey = AnnounceDriver.newGroupKey()
        var memo = AdminCheck.kickNaming(p, naming = Y, kicked = "k".repeat(padding), newEpoch = 2, newKey = newKey)
        repeat(60) {
            val now = ZMSGGroupProtocol.parsePayload(memo)!!.length
            if (now != length) {
                padding += length - now
                memo = AdminCheck.kickNaming(p, naming = Y, kicked = "k".repeat(padding), newEpoch = 2, newKey = newKey)
            }
        }
        assertEquals("fixture: the payload has $length characters", length, ZMSGGroupProtocol.parsePayload(memo)!!.length)
        return memo
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - control - a removal with a payload of 2,048 characters is kept and one of 2,049 is not`() =
        runBlocking {
            phone(verified = true).let { p ->
                val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
                val memo = kickWithPayloadOf(p, 2_048)

                AdminCheck.relayDeliver(vm, memo, Y, KICK_1, relayTime)

                assertEquals("a removal at the length limit was not kept", listOf(KICK_1), keptIds(p))
                assertEquals(
                    "the payload was not kept as it came",
                    ZMSGGroupProtocol.parsePayload(memo),
                    AdminCheck.keptChanges(p).single().getString("p"),
                )
            }
            phone(verified = true).let { p ->
                val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

                AdminCheck.relayDeliver(vm, kickWithPayloadOf(p, 2_049), Y, KICK_1, relayTime)

                assertEquals("a removal one character over the length limit was kept", emptyList<String>(), keptIds(p))
                assertTrue("the relay event left the seen list", KICK_1 in p.seenEvents)
                assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)
            }
        }

    // ------------------------------------------------------------------ (b)

    /**
     * Sixteen entries of the finished chat [chat]: eight removals and eight key changes at the epochs 2 to 9, each a
     * real change signed with that chat's own key ([signer]), as the keep step stores one the gate refused.
     */
    private fun sixteenOf(
        p: AdminCheckPhone,
        chat: String,
        signer: E2EKeyPair,
        now: Long,
    ): List<JSONObject> =
        (2..9).flatMap { epoch ->
            val key = AnnounceDriver.newGroupKey()
            val removal = AdminCheck.kickNaming(p, chat, M, epoch, key, signer = signer, wrapFor = chat)
            val keyChange = AdminCheck.keyNaming(p, chat, epoch, key, signer = signer, wrapFor = chat)
            listOf("GK" to removal, "GY" to keyChange).map { (kind, memo) ->
                AdminCheck.keptEntry(
                    "$chat-$kind-$epoch",
                    kind,
                    epoch,
                    ZMSGGroupProtocol.parsePayload(memo)!!,
                    chat,
                    relayTime.toEpochMilli(),
                    now - 60_000,
                )
            }
        }

    @Test
    fun `b - control - a full list keeps the admin's removal (ZC-952), and once the safety number is compared the next change is kept`() =
        runBlocking {
            val p = phone(verified = false)
            val fillers = listOf(FILLER_A, FILLER_B, FILLER_C)
            val fillerKeys = fillers.associateWith { p.base.link(it) }
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            AdminCheck.putKept(p, fillers.flatMap { sixteenOf(p, it, fillerKeys.getValue(it), System.currentTimeMillis()) })
            val full = keptIds(p)
            assertEquals("fixture: the 48 places are taken", 48, full.size)

            // The admin removes M over the relays. Its chat ranks no higher than the three; ZC-952: it takes a place.
            val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
            AdminCheck.relayDeliver(vm, removal, Y, KICK_1, relayTime)

            assertTrue("ZC-952: the admin's removal was shut out of a list full of unverified chats", KICK_1 in keptIds(p))
            assertEquals("ZC-952: more than one entry gave its place up", 47, keptIds(p).count { it in full })
            assertTrue("the relay event of the removal left the seen list", KICK_1 in p.seenEvents)
            assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))

            // The user compares the admin's safety number: the admin's chat now ranks above the three.
            p.verified += Y
            val newKey = AnnounceDriver.newGroupKey()
            val keyChange = AdminCheck.keyNaming(p, naming = Y, newEpoch = 3, newKey = newKey)
            AdminCheck.relayDeliver(vm, keyChange, Y, KEY_3, relayTime.plusSeconds(60))

            assertEquals("the list holds more or fewer than its 48 places", 48, keptIds(p).size)
            assertTrue("a change of a verified chat was shut out by chats that are not verified", KEY_3 in keptIds(p))
            assertEquals("more than one entry gave its place up", 46, keptIds(p).count { it in full })

            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            AdminCheck.awaitOwnJobs(vm)
            completePass(vm)

            assertEquals("the kept key change was not applied after the confirmation", 3, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(3))
            assertEquals("the list is not empty after the run", emptyList<String>(), keptIds(p))
            // ZC-952: the loss ZC-704 named (item 2) is gone: the removal kept in the full list is applied too.
            assertEquals("the removal kept in the full list was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - control - a kept removal whose write was refused stays seen, is held in memory and is applied after Confirm`() =
        runBlocking {
            val p = phone(verified = true)
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())

            p.announceWritesRefused = true
            AdminCheck.relayDeliver(vm, removal, Y, KICK_1, relayTime)

            assertEquals("the store was not asked to write the kept removal", 1, p.calls.count { it == "saveGroupAnnounceState" })
            assertTrue("the relay event left the seen list", KICK_1 in p.seenEvents)
            assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)
            assertEquals("the store's memory does not hold the removal whose write was refused", listOf(KICK_1), keptIds(p))

            p.announceWritesRefused = false
            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            AdminCheck.awaitOwnJobs(vm)
            completePass(vm)

            assertEquals("the removal held in memory was not applied after the confirmation", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.epochs[GID])
            assertEquals(emptyList<String>(), keptIds(p))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    private companion object {
        const val KICK_1 = "synthetic-ki25-bounds-relay-kick-1"
        const val KEY_3 = "synthetic-ki25-bounds-relay-key-epoch-3"
        const val FILLER_A = "usyntheticki25fillera0000001"
        const val FILLER_B = "usyntheticki25fillerb0000001"
        const val FILLER_C = "usyntheticki25fillerc0000001"
    }
}
