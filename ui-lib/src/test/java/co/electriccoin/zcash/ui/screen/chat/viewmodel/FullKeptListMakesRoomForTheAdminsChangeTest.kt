package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
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
import java.util.Collections

/**
 * ZC-952 (review of train T7a2, 13 Oct 2026, findings 2 and 3; decision of 13 Oct night under W-57, register "next
 * step") — on a real ChatViewModel, the kept list of a group that cannot confirm its admin (KI-25 R19):
 *
 * - (a) three ordinary contacts who know the group id fill the 48 places with changes they signed themselves; the real
 *   admin's removal, from a chat whose safety number is not compared yet, still takes a place: the oldest entry of
 *   another unverified chat gives its place up. After the user compares the admin's safety number and confirms, the
 *   first complete pass applies the removal: the member is removed and the group's key is the removal's new key, so
 *   the removed member reads nothing this phone writes later. Nothing is paid.
 * - (b) the eviction never touches a verified chat's entry: with a verified chat's sixteen entries in the full list
 *   (the oldest of all), the place comes from an unverified chat.
 * - (c) a list full of verified chats' entries keeps no change of an unverified chat; its relay event stays marked
 *   seen and nothing is un-marked (design rule O5 unchanged).
 * - (d) the store refuses the write of a kept removal. It stays in the view model's memory and its write is tried
 *   again at every history pass; once the store takes it, it is on the disk (a process killed then keeps it), the
 *   pass after the confirmation applies it, and it is applied and written exactly once. Nothing is un-marked.
 *
 * Before the fix (a) and (b) fail because the admin's change takes no place in a full list of chats of its rank, and
 * (d) because a refused write is never tried again. (c) holds before and after.
 *
 * All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullKeptListMakesRoomForTheAdminsChangeTest {
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

    /** A drifted phone with the creating invite from Y in its history; the admin's safety number is not compared. */
    private fun phone(): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.base.nostrKeys[Y] = Y_NOSTR
        }

    private fun keptIds(p: AdminCheckPhone): List<String> = AdminCheck.keptChanges(p).map { it.getString("id") }

    private fun completePass(vm: ChatViewModel) {
        val pass = AdminCheck.beginPass(vm)
        AdminCheck.endPass(vm, pass, complete = true)
    }

    private fun incompletePass(vm: ChatViewModel) {
        val pass = AdminCheck.beginPass(vm)
        AdminCheck.endPass(vm, pass, complete = false)
    }

    /** Sixteen entries of [chat], each a real change it signed with its own key ([signer]), kept at [keptAt]. */
    private fun sixteenOf(
        p: AdminCheckPhone,
        chat: String,
        signer: E2EKeyPair,
        keptAt: Long,
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
                    keptAt,
                )
            }
        }

    private fun fill(
        p: AdminCheckPhone,
        chats: List<String>,
        keptAt: (String) -> Long = { System.currentTimeMillis() - 60_000 },
    ) {
        val keys = chats.associateWith { p.base.link(it) }
        AdminCheck.putKept(p, chats.flatMap { sixteenOf(p, it, keys.getValue(it), keptAt(it)) })
        assertEquals("fixture: the 48 places are taken", 48, keptIds(p).size)
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - three contacts fill the list, the admin's removal still takes a place and applies after the confirmation`() =
        runBlocking {
            val p = phone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            fill(p, listOf(FILLER_A, FILLER_B, FILLER_C))
            val full = keptIds(p)

            val newKey = AnnounceDriver.newGroupKey()
            val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey)
            AdminCheck.relayDeliver(vm, removal, Y, KICK_1, relayTime)

            assertTrue("the admin's removal was shut out of a list full of three unverified chats' changes", KICK_1 in keptIds(p))
            assertEquals("the list holds more or fewer than its 48 places", 48, keptIds(p).size)
            assertEquals("more than one entry gave its place up", 47, keptIds(p).count { it in full })
            assertTrue("the relay event left the seen list", KICK_1 in p.seenEvents)
            assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)

            p.verified += Y
            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            AdminCheck.awaitOwnJobs(vm)
            completePass(vm)

            assertEquals("the admin's removal was not applied after the confirmation", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals("the group's key epoch is not the removal's", 2, p.base.epochs[GID])
            assertEquals("the group's key is not the removal's new key: the removed member could read on", newKey, p.groupKey(2))
            assertEquals("the list is not empty after the run", emptyList<String>(), keptIds(p))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - the place never comes from a verified chat's entry`() =
        runBlocking {
            val p = phone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            p.verified += VERIFIED
            val now = System.currentTimeMillis()
            fill(p, listOf(VERIFIED, FILLER_A, FILLER_B)) { chat -> if (chat == VERIFIED) now - 60 * DAY else now - 60_000 }
            val verifiedEntries = keptIds(p).filter { it.startsWith(VERIFIED) }

            val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
            AdminCheck.relayDeliver(vm, removal, Y, KICK_1, relayTime)

            assertTrue("the admin's removal was shut out of the full list", KICK_1 in keptIds(p))
            assertEquals(48, keptIds(p).size)
            assertTrue("an entry of a verified chat gave its place up", verifiedEntries.all { it in keptIds(p) })
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - a list full of verified chats' entries keeps no change of an unverified chat and un-marks nothing`() =
        runBlocking {
            val p = phone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            p.verified += listOf(VERIFIED, VERIFIED_2, VERIFIED_3)
            fill(p, listOf(VERIFIED, VERIFIED_2, VERIFIED_3))
            val full = keptIds(p)

            val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
            AdminCheck.relayDeliver(vm, removal, Y, KICK_1, relayTime)

            assertEquals("an unverified chat's change pushed a verified chat's entry out", full, keptIds(p))
            assertTrue("the relay event left the seen list", KICK_1 in p.seenEvents)
            assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - a refused write of a kept removal is tried again at every pass and, once written, applies exactly once`() =
        runBlocking {
            val p = phone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            // What the disk holds: the state of the last write the store took.
            val disk = Collections.synchronizedList(mutableListOf(p.base.state(GID)))
            val attempts = Collections.synchronizedList(mutableListOf<Pair<Boolean, AnnounceState>>())
            p.onCall = { name ->
                if (name == "saveGroupAnnounceState") {
                    val args = p.callLog.last().second
                    if (args[0] == GID) {
                        val state = args[1] as AnnounceState
                        val taken = !p.announceWritesRefused
                        attempts += taken to state
                        if (taken) disk += state
                    }
                }
            }

            fun holdsKick(state: AnnounceState): Boolean = state.keptAdminChanges.any { it.id == KICK_1 }

            p.announceWritesRefused = true
            val newKey = AnnounceDriver.newGroupKey()
            val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey)
            AdminCheck.relayDeliver(vm, removal, Y, KICK_1, relayTime)
            assertEquals("fixture: the keep step asked once and was refused", listOf(false), attempts.map { it.first })

            // A pass while the store still refuses: the write is tried again.
            incompletePass(vm)
            assertEquals(
                "the refused write of the kept removal was not tried again at the next history pass",
                listOf(false, false),
                attempts.filter { holdsKick(it.second) }.map { it.first },
            )

            // The store works again: the next pass writes it to the disk, once.
            p.announceWritesRefused = false
            incompletePass(vm)
            assertTrue("the kept removal never reached the disk once the store worked", holdsKick(disk.last()))
            incompletePass(vm)
            assertEquals(
                "the kept removal was written more than once after the store took it",
                1,
                attempts.count { it.first && holdsKick(it.second) },
            )
            assertTrue("the relay event left the seen list", KICK_1 in p.seenEvents)
            assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)

            // The process dies: the next one reads the disk only.
            p.onCall = null
            p.base.states[GID] = disk.last()
            AutoSpendVm.resetProcessState()
            AdminCheck.forgetConfirmedPasses()
            val next = AdminCheck.chatVm(p, seam)
            p.verified += Y
            assertEquals(CONFIRMED, AdminCheck.confirm(next, Y, p.admin.publicKey))
            AdminCheck.awaitOwnJobs(next)
            completePass(next)

            assertEquals("the removal written after the refusal was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(p))

            completePass(next)
            completePass(vm)
            assertEquals("the applied removal came back into the list", emptyList<String>(), keptIds(p))
            assertEquals(2, p.base.epochs[GID])
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    private companion object {
        const val KICK_1 = "synthetic-zc952-relay-kick-1"
        const val FILLER_A = "usyntheticzc952fillera000001"
        const val FILLER_B = "usyntheticzc952fillerb000001"
        const val FILLER_C = "usyntheticzc952fillerc000001"
        const val VERIFIED = "usyntheticzc952verified00001"
        const val VERIFIED_2 = "usyntheticzc952verified00002"
        const val VERIFIED_3 = "usyntheticzc952verified00003"
        const val DAY = 24L * 60 * 60 * 1_000
    }
}
