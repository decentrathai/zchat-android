package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.STRANGER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R5 and R6; section 4.1 class 3) — a group whose admin this phone cannot match
 * to a key says so, and a healthy group says nothing.
 *
 * At `00f1a349b` a member's phone that holds no key for its group's admin drops every change of the admin and shows
 * nothing: there is no state to read and no line in the group. Wanted: `groupAdminKeyState(groupId)` computes the
 * state from the phone's own data and writes nothing, and `GroupViewModel.groupAdminNotice(groupId)` is the check
 * line while the state is UNKNOWN.
 *
 * The rows pinned here (R5, as pair 1 ships it):
 * - no stored creator key and no key at the creator address: UNKNOWN;
 * - a stored creator key that a contact holds: OK, also when our own key pair for that contact is gone, and also
 *   when the only holder is a saved contact with no conversation (one address set for every lookup);
 * - a group this phone created, an inactive group, a group whose invite still waits for Join, and a group joined with
 *   the creator's plaintext relay invite: NOT_APPLICABLE.
 *
 * All addresses, keys and ids are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSaysWhenItCannotConfirmItsAdminTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        val WRITERS = listOf("save", "set", "delete", "clear", "remove", "mark", "record", "put", "increment", "import")
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    private suspend fun stateOf(p: AdminCheckPhone): String = AdminCheck.keyState(AdminCheck.chatVm(p, HandshakeSeam(Y)))

    @Test
    fun `a drifted group reads UNKNOWN and reading the state writes nothing`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val before = p.snapshot()
            val callsBefore = p.calls.size

            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))

            assertEquals("reading the admin state changed a store", before, p.snapshot())
            val writes = p.calls.drop(callsBefore).filter { call -> WRITERS.any { call.startsWith(it) } }
            assertEquals("reading the admin state made store writes", emptyList<String>(), writes)
        }

    @Test
    fun `a group whose stored creator key a chat holds reads OK`() =
        runBlocking {
            assertEquals("OK", stateOf(AdminCheck.pinnedPhone()))
        }

    @Test
    fun `it still reads OK after our own key pair for the admin's chat is gone`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            // The chat stays on the phone when our key pair for it is lost. This fake derives its conversation list
            // from the key pairs, so the chat is kept in the address set as a saved contact.
            p.name(Y, "Synthetic Admin")
            p.base.ourKeys.remove(Y)

            assertEquals("losing our own key pair read as the creator's key being gone", "OK", stateOf(p))
        }

    @Test
    fun `a stored creator key held only by a saved contact with no conversation reads OK`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            // The admin's key is held under a saved contact's address only: no chat, no key pair of ours.
            p.base.ourKeys.clear()
            p.base.peerKeys.clear()
            p.base.peerKeys[STRANGER] = p.admin.publicKey
            p.savedContacts += Contact(address = STRANGER, name = "Synthetic Saved Contact", addedAt = Instant.ofEpochMilli(T))

            assertEquals("the saved contacts are not part of the address set the lookups walk", "OK", stateOf(p))
        }

    @Test
    fun `a group this phone created is not applicable`() =
        runBlocking {
            val marked = AdminCheck.driftedPhone()
            marked.base.selfCreated += GID
            assertEquals("NOT_APPLICABLE", stateOf(marked))

            val byAddress = AdminCheck.driftedPhone()
            byAddress.base.infos[GID] =
                ZMSGGroupProtocol.serializeGroupInfo(byAddress.base.info(GID).copy(creatorAddress = byAddress.self))
            assertEquals("NOT_APPLICABLE", stateOf(byAddress))
        }

    @Test
    fun `an inactive group is not applicable`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(isActive = false))

            assertEquals("NOT_APPLICABLE", stateOf(p))
        }

    @Test
    fun `a group whose invite still waits for Join is not applicable`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.base.needsJoin[GID] = "synthetic-held-accept-record"

            assertEquals("NOT_APPLICABLE", stateOf(p))
        }

    @Test
    fun `a group joined with the creator's plaintext relay invite is not applicable`() =
        runBlocking {
            assertEquals("NOT_APPLICABLE", stateOf(AdminCheck.plainKeyPhone()))
        }

    @Test
    fun `the group's notice is the check line for UNKNOWN and nothing for OK`() =
        runBlocking {
            val drifted = AdminCheck.driftedPhone()
            val driftedVm = AdminCheck.groupVm(drifted, GroupPaidOnceFixture())
            assertEquals(AdminCheck.NOTICE_CHECK_LINE, AdminCheck.notice(driftedVm))
            assertEquals("the group screen and the chat screen read different states", "UNKNOWN", AdminCheck.keyState(driftedVm))

            // A healthy group: the admin's member list has reached this phone, so no catch-up line is due either.
            val pinned = AdminCheck.pinnedPhone()
            pinned.base.states[GID] = pinned.base.state(GID).copy(rosterAnnounced = true)
            val pinnedVm = AdminCheck.groupVm(pinned, GroupPaidOnceFixture())
            assertNull("a group whose admin is confirmed shows an admin line", AdminCheck.notice(pinnedVm))
        }
}
