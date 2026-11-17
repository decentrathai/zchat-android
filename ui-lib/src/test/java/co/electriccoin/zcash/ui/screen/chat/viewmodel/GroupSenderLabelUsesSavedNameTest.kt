package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X2
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * GROUP-LABEL (fix-all 2.13.0, train T4b) — a group message names its sender by the first 8 characters of the address,
 * never by the name the 1-to-1 chat header uses for the same person.
 *
 * At HEAD the bubble header is `senderAddress.take(8) + "..."` and GroupConversation carries no names; the member list
 * in group settings shows only the roster row's snapshot of the contact name (taken when the row was written).
 *
 * Wanted: GroupConversation.senderNames and GroupSettingsState.Success.names, one resolver in the order of the 1-to-1
 * chat header (nickname, then the saved contact name, then the roster row's snapshot), with nicknames and contacts
 * keyed by the canonical address on BOTH sides (the alias runs invited address -> the address the member accepted
 * with), and a sender named ONLY when its address has a row in this group's roster, so an on-chain message that claims
 * the address of a contact who is not in the group cannot show that contact's name.
 *
 * Both new fields are read by name, so this file compiles before they exist. A map-backed ContactBook and a nickname
 * proxy over the phone's ZchatPreferences are test-local seams set into the GroupViewModel by reflection. Nothing is
 * proposed or paid. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSenderLabelUsesSavedNameTest {
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

    private class Phone(
        val phone: AnnouncePhone,
        val vm: ChatViewModel,
        val seam: HandshakeSeam,
    )

    /** A member's phone whose roster the creator announced (A X), holding one message from [sender]. */
    private suspend fun phoneWithMessage(
        sender: String = X,
        before: (AnnouncePhone) -> Unit = {},
    ): Phone {
        val phone = AnnouncePhone()
        val admin = AnnounceDriver.memberPhone(phone)
        before(phone)
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.receive(
            vm,
            AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
            Instant.ofEpochMilli(T + 1_000),
            AnnounceDriver.txId(1)
        )
        assertEquals("fixture: the verified announce did not add X", MemberStatus.ACTIVE, phone.status(GID, X))
        AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, sender, "hi from X"), Instant.ofEpochMilli(T + 2_000), AnnounceDriver.txId(2))
        assertEquals("fixture: the message was not stored", 1, phone.storedMessageIds(GID).size)
        return Phone(phone, vm, seam)
    }

    /** Rewrite X's roster row with [nickname] as its snapshot (null written explicitly). */
    private fun setRosterSnapshot(
        phone: AnnouncePhone,
        nickname: String?
    ) {
        val roster = phone.roster(GID).map { if (it.address == X) it.copy(nickname = nickname) else it }
        phone.rosters[GID] = ZMSGGroupProtocol.serializeGroupMembers(roster)
        assertEquals("fixture: X's roster snapshot", nickname, phone.roster(GID).first { it.address == X }.nickname)
    }

    private class MapContactBook(
        private val contacts: Map<String, String>,
        private val fails: Boolean = false,
    ) : ContactBook {
        private fun all(): List<Contact> {
            if (fails) throw IllegalStateException("synthetic contact book read failure")
            return contacts.map { (address, name) -> Contact(address = address, name = name) }
        }

        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? =
            all().firstOrNull {
                it.address.trim().lowercase() == address.trim().lowercase()
            }

        override fun getAllContacts(): List<Contact> = all()

        override fun hasContact(address: String): Boolean = getContact(address) != null

        override fun updateContactName(
            address: String,
            newName: String
        ) = Unit

        override fun clearAll() = Unit
    }

    /** [base] with the 1-to-1 nicknames of [nicknames]; every other call passes through. */
    private fun withNicknames(
        base: ZchatPreferences,
        nicknames: Map<String, String>
    ): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getNickname" -> nicknames[args!![0] as String]
                "getAllNicknames" -> HashMap(nicknames)
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private fun setVmField(
        vm: GroupViewModel,
        name: String,
        value: Any
    ) {
        GroupViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    private suspend fun groupVm(
        phone: AnnouncePhone,
        f: GroupPaidOnceFixture,
        contacts: Map<String, String>,
        nicknames: Map<String, String>,
        failingBook: Boolean,
    ): GroupViewModel {
        // After AnnounceDriver.groupVm, which sets the phone's own ZchatPreferences.
        val vm = AnnounceDriver.groupVm(phone, f)
        setVmField(vm, "zchatPreferences", withNicknames(phone.proxy, nicknames))
        setVmField(vm, "contactBook", MapContactBook(contacts, failingBook))
        return vm
    }

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@GroupSenderLabelUsesSavedNameTest.scheduler }

    private suspend fun load(
        phone: AnnouncePhone,
        contacts: Map<String, String> = emptyMap(),
        nicknames: Map<String, String> = emptyMap(),
        failingBook: Boolean = false,
    ): GroupConversation? {
        val f = fixture()
        return AnnounceDriver.loadedConversation(groupVm(phone, f, contacts, nicknames, failingBook), f)
    }

    private suspend fun loadSettings(
        phone: AnnouncePhone,
        contacts: Map<String, String> = emptyMap(),
        failingBook: Boolean = false,
    ): GroupSettingsState {
        val f = fixture()
        val vm = groupVm(phone, f, contacts, emptyMap(), failingBook)
        f.awaitNewJobs(vm) { vm.loadGroupSettings(GID) }
        return vm.groupSettingsState.value
    }

    /** A map field of [obj] read by name (so this file compiles before it exists); null when absent. */
    private fun mapField(
        obj: Any?,
        name: String
    ): Map<*, *>? =
        obj?.let { o ->
            runCatching {
                o.javaClass
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(o) as? Map<*, *>
            }.getOrNull()
        }

    private fun senderNames(conversation: GroupConversation?): Map<*, *> {
        assertNotNull("fixture: the group did not load", conversation)
        val names = mapField(conversation, "senderNames")
        assertNotNull("GroupConversation has no senderNames: the group screen names every sender by its address", names)
        return names!!
    }

    @Test
    fun `L1 a message from a member saved as contact Alice is labelled Alice`() =
        runBlocking {
            val p = phoneWithMessage()
            assertEquals("Alice", senderNames(load(p.phone, contacts = mapOf(X to "Alice")))[X])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L2 the 1-to-1 nickname comes before the contact name, like the chat header`() =
        runBlocking {
            val p = phoneWithMessage()
            assertEquals("Ali", senderNames(load(p.phone, contacts = mapOf(X to "Alice"), nicknames = mapOf(X to "Ali")))[X])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L3a a message under a drifted address of a saved contact is labelled with the contact name`() =
        runBlocking {
            val p = phoneWithMessage(sender = X2) { it.aliases[X2] = X }
            assertEquals("Alice", senderNames(load(p.phone, contacts = mapOf(X to "Alice")))[X2])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L3b a contact saved under the invited address names the member who posts from the address they accepted with`() =
        runBlocking {
            val p = phoneWithMessage()
            // What processGroupAccept does: the invited address (where the contact was saved) -> the live address.
            p.phone.proxy.setPeerAddressAlias(X0, X)
            assertEquals("fixture: the alias", X, p.phone.proxy.resolvePeerAddress(X0))
            assertEquals("fixture: the live address is canonical", X, p.phone.proxy.resolvePeerAddress(X))

            assertEquals("Alice", senderNames(load(p.phone, contacts = mapOf(X0 to "Alice")))[X])
            assertEquals("Ali", senderNames(load(p.phone, contacts = mapOf(X0 to "Alice"), nicknames = mapOf(X0 to "Ali")))[X])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L8 a saved contact who is not in the group's member list is not named on a message that claims them`() =
        runBlocking {
            val p = phoneWithMessage()
            AnnounceDriver.receive(
                p.vm,
                AnnounceDriver.gm(p.phone, Z, "hello from Z"),
                Instant.ofEpochMilli(T + 3_000),
                AnnounceDriver.txId(3)
            )
            assertEquals("fixture: Z's message was not stored", 2, p.phone.storedMessageIds(GID).size)
            assertNull("fixture: Z has a roster row", p.phone.status(GID, Z))

            val conversation = load(p.phone, contacts = mapOf(Z to "Mom", X to "Alice"))
            assertEquals("fixture: Z's message is not shown", setOf(X, Z), conversation?.messages?.map { it.senderAddress }?.toSet())
            val names = senderNames(conversation)
            assertFalse("a message claiming a contact outside the group shows that contact's name: $names", Z in names)
            assertEquals("Alice", names[X])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L4 a contact saved after the roster row was written wins over the roster snapshot`() =
        runBlocking {
            val p = phoneWithMessage()
            setRosterSnapshot(p.phone, "Old")

            assertEquals("Alice", senderNames(load(p.phone, contacts = mapOf(X to "Alice")))[X])
            assertEquals("Old", senderNames(load(p.phone))[X])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L5 a sender with no name has no entry, and a blank nickname counts as none`() =
        runBlocking {
            val p = phoneWithMessage()
            setRosterSnapshot(p.phone, null)

            val names = senderNames(load(p.phone, nicknames = mapOf(X to "  ")))
            assertFalse("a sender with no name got an entry: $names", X in names)
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L6 group settings name members the same way`() =
        runBlocking {
            val p = phoneWithMessage()
            val state = loadSettings(p.phone, contacts = mapOf(X to "Alice"))
            assertNotNull("fixture: the settings did not load: $state", state as? GroupSettingsState.Success)
            val names = mapField(state, "names")
            assertNotNull("GroupSettingsState.Success has no names: the member list shows only the roster snapshot", names)
            assertEquals("Alice", names!![X])
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L7 a name never changes who sent the message`() =
        runBlocking {
            val p = phoneWithMessage()
            val plain = load(p.phone)?.messages?.map { it.senderAddress to (it.senderAddress == p.phone.self) }
            val named =
                load(p.phone, contacts = mapOf(X to "Alice"), nicknames = mapOf(X to "Ali"))?.messages?.map {
                    it.senderAddress to (it.senderAddress == p.phone.self)
                }
            assertNotNull("fixture: the group did not load", plain)
            assertEquals(listOf(X to false), plain)
            assertEquals(plain, named)
            assertEquals(0, p.seam.proposalRequests.get())
        }

    @Test
    fun `L9 a contact book that cannot be read still loads the group and its settings`() =
        runBlocking {
            val p = phoneWithMessage()
            val conversation = load(p.phone, contacts = mapOf(X to "Alice"), failingBook = true)
            assertEquals("the group did not load its message", listOf(X), conversation?.messages?.map { it.senderAddress })
            val names = senderNames(conversation)
            assertFalse("a name came from a contact book that could not be read: $names", names[X] == "Alice")
            val state = loadSettings(p.phone, contacts = mapOf(X to "Alice"), failingBook = true)
            assertNotNull("the settings did not load: $state", state as? GroupSettingsState.Success)
            assertEquals(0, p.seam.proposalRequests.get())
        }

    private companion object {
        /** The address the member was invited under (synthetic), aliased to [X] when the member accepts from [X]. */
        const val X0 = "usyntheticgl8invitedx0000001"
    }
}
