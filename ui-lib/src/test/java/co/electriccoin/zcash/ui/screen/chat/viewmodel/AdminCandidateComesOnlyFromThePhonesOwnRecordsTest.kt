package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.STRANGER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R7 and section 3.5; section 4.1 class 4) — the one chat a user may confirm as
 * a group's admin comes from this phone's own records, and from nowhere else. There is no list and no pick.
 *
 * The record: the invitation that created the group is still in the wallet's history. Its transaction's block time is
 * the group's stored creation time, to the millisecond, and its `k2` is the group key wrapped under one pairwise
 * session. The chat whose session unwraps that `k2` to exactly the key this phone stores for the group is the
 * candidate. A member who invites this phone later, a member the user has verified, a member in the list and a member
 * who sent signed controls are not: none of that ties a chat to the group's creation.
 *
 * At `00f1a349b` the phone has no such lookup (`GroupViewModel.loadGroupAdminCandidate` does not exist), and a group
 * whose admin it cannot match to a key offers nothing.
 *
 * Nothing is sent or paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCandidateComesOnlyFromThePhonesOwnRecordsTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val created: Instant = Instant.ofEpochMilli(T)
    private val later: Instant = Instant.ofEpochMilli(T + 86_400_000L)

    /**
     * What R7 gives on [p] now: the candidate (null: none), read by a fresh group screen. [beforeTheLookup] runs when
     * the screen's view model exists and has not looked yet.
     */
    private suspend fun candidateOn(
        p: AdminCheckPhone,
        beforeTheLookup: () -> Unit = {},
    ): Any? {
        val f = GroupPaidOnceFixture()
        f.scheduler = scheduler
        val vm = AdminCheck.groupVm(p, f)
        beforeTheLookup()
        val candidate = AdminCheck.loadCandidate(vm, f)
        assertEquals("looking for the candidate proposed a payment", 0, f.seam.proposalRequests.get())
        return candidate
    }

    private fun address(candidate: Any?): String? = candidate?.let { AdminCheck.property(it, "address") as String }

    private fun source(candidate: Any?): String? = candidate?.let { AdminCheck.property(it, "source").toString() }

    private fun name(candidate: Any?): String? = candidate?.let { AdminCheck.property(it, "name") as String? }

    /** A drifted phone whose history holds the creating invite, sent from the chat this phone holds under [Y]. */
    private fun phoneWithTheCreatingInvite(): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p -> p.creatingInvite(p.base.sessionWith(Y), created) }

    @Test
    fun `a - the chat whose session unwraps the creating invite to the group's key is the candidate`() =
        runBlocking {
            val p = phoneWithTheCreatingInvite()
            p.name(Y, "Synthetic Admin")

            val candidate = candidateOn(p)

            assertNotNull("the chat that sent the invitation which created the group is not offered", candidate)
            assertEquals(Y, address(candidate))
            assertEquals("INVITATION", source(candidate))
            assertEquals("the candidate is not named as the chat list names it", "Synthetic Admin", name(candidate))
        }

    @Test
    fun `b - with no invitation at the group's creation time there is no candidate`() =
        runBlocking {
            assertNull("a candidate was found in an empty history", candidateOn(AdminCheck.driftedPhone()))

            // A received transaction at the creation time that is no invitation for this group.
            val other = AdminCheck.driftedPhone()
            other.history.received(ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), created, n = 1)
            assertNull("a candidate was found without an invitation", candidateOn(other))
        }

    @Test
    fun `c - a later invite from a member is not the creating one and that member is not a candidate`() =
        runBlocking {
            // M knows the group key and invites this phone again, a day after the group was created.
            val onlyLater = AdminCheck.driftedPhone()
            onlyLater.base.link(M)
            onlyLater.creatingInvite(onlyLater.base.sessionWith(M), later, inviter = M, n = 2)
            assertNull("a member who invited this phone later is offered as the group's admin", candidateOn(onlyLater))

            // The same beside the creating invite: the candidate is still the chat of the creating one.
            val both = phoneWithTheCreatingInvite()
            both.base.link(M)
            both.creatingInvite(both.base.sessionWith(M), later, inviter = M, n = 2)
            assertEquals("a later invite from a member displaced the creating one", Y, address(candidateOn(both)))
        }

    @Test
    fun `d - two invitations at the creation time from two different contacts give no candidate`() =
        runBlocking {
            val p = phoneWithTheCreatingInvite()
            p.base.link(M)
            p.creatingInvite(p.base.sessionWith(M), created, inviter = M, n = 2)

            assertNull("one of two contacts with different keys was picked as the group's admin", candidateOn(p))
        }

    @Test
    fun `e - an invitation at the creation time that carries another key than the group's gives no candidate`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.creatingInvite(p.base.sessionWith(Y), created, groupKey = AnnounceDriver.newGroupKey())

            assertNull("a chat was offered whose invitation does not carry this group's key", candidateOn(p))
        }

    @Test
    fun `f - the admin's chat under two address texts with one key is one candidate`() =
        runBlocking {
            val p = phoneWithTheCreatingInvite()
            // The same chat under a second address text: the same contact key and the same key pair of ours.
            p.base.ourKeys[Y2] = p.base.ourKeys.getValue(Y)
            p.base.peerKeys[Y2] = p.base.peerKeys.getValue(Y)

            val candidate = candidateOn(p)

            assertNotNull("one contact under two address texts was taken for two contacts", candidate)
            assertTrue("the candidate is neither address text of the admin's chat", address(candidate) in setOf(Y, Y2))
            assertEquals("the candidate's chat does not hold the admin's key", p.admin.publicKey, p.base.peerKeys[address(candidate)])
            assertEquals("INVITATION", source(candidate))
        }

    @Test
    fun `g - the candidate does not depend on who is verified, who is in the list or who sent signed controls`() =
        runBlocking {
            val p = phoneWithTheCreatingInvite()
            assertEquals(Y, address(candidateOn(p)))

            // M: a finished chat, the safety number compared.
            val member = p.base.link(M)
            p.verified += M
            assertEquals("verifying a member changed the candidate", Y, address(candidateOn(p)))

            // M sent a signed control of its own, on the chain (refused: M is not the admin).
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            AnnounceDriver.receive(
                vm,
                AdminCheck.kickNaming(
                    p,
                    naming = M,
                    kicked = STRANGER,
                    newEpoch = 2,
                    newKey = AnnounceDriver.newGroupKey(),
                    signer = member,
                    wrapFor = M,
                ),
                later,
                AnnounceDriver.txId(7),
                minedHeight = HEIGHT + 7,
            )
            assertEquals("a member's signed control changed the candidate", Y, address(candidateOn(p)))

            // M is no longer in the member list.
            p.base.rosters[GID] = ZMSGGroupProtocol.serializeGroupMembers(p.base.roster(GID).filterNot { it.address == M })
            assertEquals("the member list changed the candidate", Y, address(candidateOn(p)))

            // And the admin's own chat is the candidate whether or not its safety number was compared.
            p.verified += Y
            assertEquals("comparing the admin's safety number changed the candidate", Y, address(candidateOn(p)))
        }

    @Test
    fun `g - a verified member in the list who sent signed controls and no invitation is never offered`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            val member = p.base.link(M)
            p.verified += M
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            AnnounceDriver.receive(
                vm,
                AdminCheck.kickNaming(
                    p,
                    naming = M,
                    kicked = STRANGER,
                    newEpoch = 2,
                    newKey = AnnounceDriver.newGroupKey(),
                    signer = member,
                    wrapFor = M,
                ),
                later,
                AnnounceDriver.txId(7),
                minedHeight = HEIGHT + 7,
            )

            assertNull("a member with no invitation from the group's creation is offered as its admin", candidateOn(p))
        }

    @Test
    fun `h - only the memos of transactions at the group's creation time are read`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.base.link(M)
            val creating = p.creatingInvite(p.base.sessionWith(Y), created, n = 1)
            p.history.received(ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), Instant.ofEpochMilli(T - 1_000), n = 2)
            p.history.received(ZMSGGroupProtocol.createGroupRosterQueryMessage(GID), Instant.ofEpochMilli(T + 1), n = 3)
            p.creatingInvite(p.base.sessionWith(M), later, inviter = M, n = 4)

            val candidate =
                candidateOn(p) {
                    assertEquals("fixture: nothing read a memo before the lookup", emptyList<String>(), p.history.memoReads.toList())
                }

            assertEquals(Y, address(candidate))
            assertEquals(
                "memos of transactions with another block time than the group's creation time were read",
                setOf(creating.id.txIdString()),
                p.history.memoReads.toSet(),
            )
        }

    @Test
    fun `h - a group whose admin is confirmed reads no history and has no candidate`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            p.creatingInvite(p.base.sessionWith(Y), created)

            val candidate =
                candidateOn(p) {
                    assertEquals("fixture: nothing read a memo before the lookup", emptyList<String>(), p.history.memoReads.toList())
                    assertEquals("fixture: nothing listed the history before the lookup", 0, p.history.listReads.get())
                }

            assertNull("a group whose admin key a chat holds offers a chat to confirm", candidate)

            assertEquals("the history was read for a group that needs no confirmation", emptyList<String>(), p.history.memoReads.toList())
            assertEquals("the history was listed for a group that needs no confirmation", 0, p.history.listReads.get())
        }
}
