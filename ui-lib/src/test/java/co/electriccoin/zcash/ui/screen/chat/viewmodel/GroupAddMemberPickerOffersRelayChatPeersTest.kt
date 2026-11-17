package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1179 — the Add-member picker of an existing group leaves out a contact you only chat with in Open mode, also after
 * that contact was removed.
 *
 * GroupViewModel.loadAddMemberCandidates lists saved contacts and conversation peers with an E2E key, minus ACTIVE and
 * INVITED members. A peer of an accepted Open (relay-only) chat has no E2E key, so it is not listed, although the Create
 * Group picker (loadAvailableContacts, G3 8d) lists it and inviting it is free.
 *
 * Wanted, as in the Create Group picker: a conversation peer whose relay key routes back to it is listed once by its chat's
 * display name when it is not in the group or is LEFT. Not listed: an ACTIVE or INVITED member (also under another form of
 * its address), our own address, a peer whose key offer waits for Accept, an unaccepted message request, a second address
 * a relay key is bound to but does not route to. The exclusion tests also want the Open peer listed, so they cannot pass on
 * an empty relay list.
 *
 * Money (skill money-path): the peer picked from the list is invited over the relays with no price question and nothing
 * proposed. The invite is today's compact form with no session (skill compat-2124: no new wire element). The controls
 * pin what a free quote and a down relay mean for this peer: no relay took it, nothing is paid; no relay route, the price
 * is asked first and nothing is proposed before Confirm. All addresses, keys and names are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAddMemberPickerOffersRelayChatPeersTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val worlds = mutableListOf<GroupCopyWorld>()

    private val openPeer = SyntheticUnifiedAddress.mainnet(9_201)
    private val openPeerCopy = SyntheticUnifiedAddress.mainnet(9_202)
    private val kexPeer = SyntheticUnifiedAddress.mainnet(9_203)
    private val savedPeer = SyntheticUnifiedAddress.mainnet(9_204)
    private val awaitingAccept = SyntheticUnifiedAddress.mainnet(9_205)
    private val ownOtherAddress = SyntheticUnifiedAddress.mainnet(9_206)
    private val requestOnly = SyntheticUnifiedAddress.mainnet(9_207)
    private val activePeer = SyntheticUnifiedAddress.mainnet(9_208)
    private val activePeerForm = SyntheticUnifiedAddress.mainnet(9_209)
    private val invitedPeer = SyntheticUnifiedAddress.mainnet(9_210)

    private val openPeerKey = "d1".repeat(32)
    private val relayKeys =
        linkedMapOf(
            openPeer to openPeerKey,
            // A broken copy bound to the same key (a pre-M1 store): the key routes to openPeer only.
            openPeerCopy to openPeerKey,
            kexPeer to "d3".repeat(32),
            savedPeer to "d4".repeat(32),
            awaitingAccept to "d5".repeat(32),
            ownOtherAddress to "d6".repeat(32),
            requestOnly to "d7".repeat(32),
            activePeer to "d8".repeat(32),
            invitedPeer to "d9".repeat(32),
        )

    /** The conversations of C's phone; [requestOnly] has none, it only asked. */
    private val conversationPeers = relayKeys.keys - requestOnly

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        worlds.forEach { it.close() }
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** C's phone: the group with A, every conversation above, and only [kexPeer] with a finished key exchange. */
    private fun world(): GroupCopyWorld =
        GroupCopyWorld(listOf(MA3.A to MemberStatus.ACTIVE)).also { w ->
            worlds += w
            w.admin.base.nostrKeys
                .putAll(relayKeys)
            w.admin.base.ourKeys[kexPeer] = E2EEncryption.generateKeyPair()
            w.admin.base.peerKeys[kexPeer] = E2EEncryption.generateKeyPair().publicKey
            w.admin.base.awaitingAccept[awaitingAccept] = "synthetic-held-kex"
        }

    private fun GroupCopyWorld.inGroup(
        address: String,
        status: MemberStatus
    ) = editRoster { it + GroupCopyWorld.memberRow(address, status) }

    /** [GroupCopyWorld.prefs] with this test's conversations, routing, names and our own second address. */
    private fun storeOf(w: GroupCopyWorld): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            fun s(i: Int) = args!![i] as String
            when (method.name) {
                "getAllPeerToConvIdMappings" -> conversationPeers.associateWith { "%08X".format(it.hashCode()) }
                "findPeerByNostrPubkey" -> relayKeys.entries.firstOrNull { it.value.equals(s(0), ignoreCase = true) }?.key
                "resolvePeerAddress" -> if (s(0) == activePeerForm) activePeer else s(0)
                "isSelfAddress" -> s(0) == ownOtherAddress || s(0) == MA3.C
                "getDisplayName" -> if (s(0) == openPeer) OPEN_CHAT_NAME else "Synthetic ${s(0).takeLast(6)}"
                "getMessageRequests" ->
                    listOf(
                        ZchatPreferences.MessageRequest(
                            senderNostrPubkeyHex = relayKeys.getValue(requestOnly),
                            senderAddress = requestOnly,
                            relayUrl = "wss://relay.synthetic.example",
                            firstMessage = "synthetic hello",
                            timestampMillis = 1L,
                            eventId = "synthetic-request-event",
                        ),
                    )
                else ->
                    try {
                        method.invoke(w.prefs, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private class Saved(
        private val contacts: List<Contact>,
    ) : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = contacts.firstOrNull { it.address == address }

        override fun getAllContacts(): List<Contact> = contacts

        override fun hasContact(address: String): Boolean = contacts.any { it.address == address }

        override fun updateContactName(
            address: String,
            newName: String
        ) = Unit

        override fun clearAll() = Unit
    }

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@GroupAddMemberPickerOffersRelayChatPeersTest.scheduler }

    private suspend fun screen(
        w: GroupCopyWorld,
        f: GroupPaidOnceFixture
    ): GroupViewModel {
        val vm = w.groupVm(f)

        fun field(name: String) = GroupViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("zchatPreferences").set(vm, storeOf(w))
        field("contactBook").set(vm, Saved(listOf(Contact(address = savedPeer, name = "Synthetic saved"))))
        return vm
    }

    private suspend fun candidates(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture
    ): List<Contact> {
        f.awaitNewJobs(vm) { vm.loadAddMemberCandidates(MA3.GID) }
        return vm.addMemberCandidates.value
    }

    private fun assertOpenPeerListedOnce(listed: List<Contact>) {
        val open = listed.filter { it.address == openPeer }
        assertEquals(1, open.size, "the accepted Open chat's peer must be listed once: ${listed.map { it.name }}")
        assertEquals(OPEN_CHAT_NAME, open.single().name, "listed by its chat's display name")
    }

    /** The invite the relays carried to [openPeer], which is today's compact form with no session. */
    private fun assertOneFreeInvite(
        w: GroupCopyWorld,
        vm: GroupViewModel
    ) {
        assertNull(vm.paidActionConfirmation.value, "adding a peer reachable over the relays asked a price")
        assertEquals(emptyList(), w.seam.proposals.toList(), "adding a peer reachable over the relays made an on-chain proposal")
        assertEquals(emptyList(), w.seam.submitted.toList(), "adding a peer reachable over the relays paid")
        val invites = w.net.ofType(GroupMessageType.GROUP_INVITE, openPeerKey)
        assertEquals(1, invites.size, "the relays must carry exactly one invite")
        val payload = JSONObject(ZMSGGroupProtocol.parsePayload(invites.single())!!)
        assertTrue(payload.has("group_key"), "an invite with no key exchange carries group_key")
        assertFalse(payload.has("k2"), "an invite with no key exchange has no session-wrapped key")
        val rows = w.roster().filter { it.address == openPeer }
        assertEquals(1, rows.size, "the peer must have one roster row")
        assertEquals(MemberStatus.INVITED, rows.single().status)
        assertEquals(InviteStatus.SENT, rows.single().inviteStatus)
    }

    @Test
    fun `an accepted Open peer who is not in the group is listed once by its chat name`() =
        runBlocking {
            val w = world()
            val f = fixture()

            assertOpenPeerListedOnce(candidates(screen(w, f), f))
        }

    @Test
    fun `an accepted Open peer who was removed is listed again`() =
        runBlocking {
            val w = world()
            w.inGroup(openPeer, MemberStatus.LEFT)
            val f = fixture()

            assertOpenPeerListedOnce(candidates(screen(w, f), f))
        }

    @Test
    fun `an ACTIVE or INVITED relay peer is not listed, also when its row holds another form of the address`() =
        runBlocking {
            val w = world()
            w.inGroup(activePeerForm, MemberStatus.ACTIVE)
            w.inGroup(invitedPeer, MemberStatus.INVITED)
            val f = fixture()

            val listed = candidates(screen(w, f), f)

            assertOpenPeerListedOnce(listed)
            val addresses = listed.map { it.address }
            assertFalse(activePeer in addresses, "an ACTIVE member was listed")
            assertFalse(invitedPeer in addresses, "an INVITED member was listed")
        }

    @Test
    fun `our own address, a peer awaiting Accept, a message request and a copy the key does not route to are not listed`() =
        runBlocking {
            val w = world()
            val f = fixture()

            val listed = candidates(screen(w, f), f)

            assertOpenPeerListedOnce(listed)
            val addresses = listed.map { it.address }
            assertFalse(ownOtherAddress in addresses, "our own address was listed")
            assertFalse(awaitingAccept in addresses, "a peer whose key offer waits for Accept was listed")
            assertFalse(requestOnly in addresses, "an unaccepted message request was listed")
            assertFalse(openPeerCopy in addresses, "a second address of one relay key (not the one it routes to) was listed")
        }

    @Test
    fun `control - a saved contact and a key exchange peer stay listed once, also with a relay key`() =
        runBlocking {
            val w = world()
            val f = fixture()

            val listed = candidates(screen(w, f), f)

            assertEquals(1, listed.count { it.address == savedPeer }, "the saved contact must be listed once")
            assertEquals("Synthetic saved", listed.single { it.address == savedPeer }.name, "a saved contact keeps its saved name")
            assertEquals(1, listed.count { it.address == kexPeer }, "the key-exchange peer must be listed once")
        }

    @Test
    fun `the Open peer picked from the list is invited over the relays and nothing is paid`() =
        runBlocking {
            val w = world()
            val f = fixture()
            val vm = screen(w, f)
            assertOpenPeerListedOnce(candidates(vm, f))

            f.awaitNewJobs(vm) { vm.addMemberToGroup(MA3.GID, openPeer) }

            assertOneFreeInvite(w, vm)
        }

    @Test
    fun `the removed Open peer picked from the list is invited over the relays on its old row and nothing is paid`() =
        runBlocking {
            val w = world()
            w.inGroup(openPeer, MemberStatus.LEFT)
            val f = fixture()
            val vm = screen(w, f)
            assertOpenPeerListedOnce(candidates(vm, f))

            f.awaitNewJobs(vm) { vm.addMemberToGroup(MA3.GID, openPeer) }

            assertOneFreeInvite(w, vm)
        }

    @Test
    fun `control - adding the Open peer without the list already pays nothing`() =
        runBlocking {
            val w = world()
            val f = fixture()
            val vm = screen(w, f)

            f.awaitNewJobs(vm) { vm.addMemberToGroup(MA3.GID, openPeer) }

            assertOneFreeInvite(w, vm)
        }

    @Test
    fun `control - adding the removed Open peer without the list already pays nothing`() =
        runBlocking {
            val w = world()
            w.inGroup(openPeer, MemberStatus.LEFT)
            val f = fixture()
            val vm = screen(w, f)

            f.awaitNewJobs(vm) { vm.addMemberToGroup(MA3.GID, openPeer) }

            assertOneFreeInvite(w, vm)
        }

    @Test
    fun `control - the invite quoted as free that no relay took is failed and never paid`() =
        runBlocking {
            val w = world()
            w.net.answer(openPeerKey, 0, 0)
            val f = fixture()
            val vm = screen(w, f)

            f.awaitNewJobs(vm) { vm.addMemberToGroup(MA3.GID, openPeer) }

            assertNull(vm.paidActionConfirmation.value, "the add was quoted as free and asked a price")
            assertEquals(emptyList(), w.seam.proposals.toList(), "an invite quoted as free made an on-chain proposal")
            assertEquals(InviteStatus.FAILED, w.row(openPeer).inviteStatus)
        }

    @Test
    fun `control - with no relay route the price is asked first and nothing is proposed before Confirm`() =
        runBlocking {
            val w = world()
            w.net.uninstall()
            val f = fixture()
            val vm = screen(w, f)

            f.awaitNewJobs(vm) { vm.addMemberToGroup(MA3.GID, openPeer) }

            val question = assertNotNull(vm.paidActionConfirmation.value, "a paid invite must ask its price first")
            assertEquals(PaidAction.GROUP_ADD_MEMBER, question.action)
            assertEquals(emptyList(), w.seam.proposals.toList(), "an on-chain proposal came before the price question")

            vm.dismissPaidAction()

            assertEquals(emptyList(), w.seam.proposals.toList(), "a declined add made an on-chain proposal")
            assertFalse(w.roster().any { it.address == openPeer }, "a declined add wrote a roster row")
        }

    private companion object {
        const val OPEN_CHAT_NAME = "Synthetic Open chat"
    }
}
