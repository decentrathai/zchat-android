package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G3 8d (fix-all 2.13.0, train T4b; groups SPEC §3.4, §3.5 J6) — the
 * Create Group picker leaves out a contact you only chat with in Open mode.
 *
 * GroupViewModel.loadAvailableContacts lists saved contacts and conversation peers with an E2E key. A peer of an
 * accepted Open (relay-only) chat has no E2E key, so it is not listed, although inviting it is free (the plaintext
 * invite goes over the relays). The same is true in 2.12.4.
 *
 * Wanted: a conversation peer with a relay key that routes to that chat (findPeerByNostrPubkey gives it back) is listed
 * once, by its chat's display name. Not listed: an unaccepted message request (it has no chat), a peer whose key offer
 * still waits for Accept, our own address, and a second address a relay key is bound to but does not route to. Saved
 * contacts and key-exchange peers stay as they are. All addresses, keys and names are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupPickerOffersRelayChatPeersTest {
    private val sendScopes = SendHarnessScopes()

    private val openPeer = SyntheticUnifiedAddress.mainnet(9_101)
    private val openPeerCopy = SyntheticUnifiedAddress.mainnet(9_102)
    private val kexPeer = SyntheticUnifiedAddress.mainnet(9_103)
    private val savedPeer = SyntheticUnifiedAddress.mainnet(9_104)
    private val awaitingAccept = SyntheticUnifiedAddress.mainnet(9_105)
    private val ownOtherAddress = SyntheticUnifiedAddress.mainnet(9_106)
    private val requestOnly = SyntheticUnifiedAddress.mainnet(9_107)

    private val relayKeys =
        linkedMapOf(
            openPeer to "a1".repeat(32),
            // A broken copy bound to the same key (a pre-M1 store): the key routes to openPeer only.
            openPeerCopy to "a1".repeat(32),
            savedPeer to "a3".repeat(32),
            awaitingAccept to "a4".repeat(32),
            ownOtherAddress to "a5".repeat(32),
        )

    private val displayNames =
        mapOf(
            openPeer to "Synthetic Fold chat",
            openPeerCopy to "Synthetic copy",
            kexPeer to "Synthetic KEX peer",
            awaitingAccept to "Synthetic stranger",
            ownOtherAddress to "Synthetic own",
        )

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun s(i: Int) = args!![i] as String
            when (method.name) {
                "getAllPeerToConvIdMappings" ->
                    listOf(openPeer, openPeerCopy, kexPeer, savedPeer, awaitingAccept, ownOtherAddress)
                        .associateWith { "%08X".format(it.hashCode()) }
                "getAllConversationPeerAddresses" ->
                    setOf(openPeer, openPeerCopy, kexPeer, savedPeer, awaitingAccept, ownOtherAddress)
                "resolvePeerAddress" -> s(0)
                "isSelfAddress" -> s(0) == ownOtherAddress || s(0) == HandshakeHarness.SELF
                "getE2EPeerPublicKey" -> if (s(0) == kexPeer) "synthetic-e2e-public-key" else null
                "getPeerNostrPubkey" -> relayKeys[s(0)]
                "findPeerByNostrPubkey" -> relayKeys.entries.firstOrNull { it.value.equals(s(0), ignoreCase = true) }?.key
                "getInboundKexAwaitingAccept" -> if (s(0) == awaitingAccept) "synthetic-held-kex" else null
                "getMessageRequests" ->
                    listOf(
                        ZchatPreferences.MessageRequest(
                            senderNostrPubkeyHex = "a7".repeat(32),
                            senderAddress = requestOnly,
                            relayUrl = "wss://relay.synthetic.example",
                            firstMessage = "synthetic hello",
                            timestampMillis = 1L,
                            eventId = "synthetic-request-event",
                        ),
                    )
                "getDisplayName" -> displayNames[s(0)] ?: s(0).take(10)
                "toString" -> "PickerPrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        List::class.java -> emptyList<Any>()
                        Set::class.java -> emptySet<Any>()
                        Map::class.java -> emptyMap<Any, Any>()
                        else -> null
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

    private suspend fun picker(): List<Contact> {
        val f = GroupPaidOnceFixture()
        val vm = f.groupViewModel()
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, prefs)
        GroupViewModel::class.java
            .getDeclaredField("contactBook")
            .apply { isAccessible = true }
            .set(vm, Saved(listOf(Contact(address = savedPeer, name = "Synthetic saved"))))
        f.awaitNewJobs(vm) { vm.loadAvailableContacts() }
        return vm.createGroupState.value.availableContacts
    }

    @Test
    fun an_accepted_open_chat_peer_is_listed_once_by_its_chat_name() =
        runBlocking {
            val listed = picker()
            val open = listed.filter { it.address == openPeer }
            assertEquals(1, open.size, "the accepted Open chat's peer must be listed once: ${listed.map { it.name }}")
            assertEquals("Synthetic Fold chat", open.single().name, "listed by its chat's display name")
        }

    @Test
    fun strangers_our_own_address_and_a_copy_the_key_does_not_route_to_are_not_listed() =
        runBlocking {
            val addresses = picker().map { it.address }
            assertFalse(requestOnly in addresses, "an unaccepted message request must not be listed")
            assertFalse(awaitingAccept in addresses, "a peer whose key offer waits for Accept must not be listed")
            assertFalse(ownOtherAddress in addresses, "our own address must not be listed")
            assertFalse(openPeerCopy in addresses, "a second address of one relay key (not the one it routes to) was listed")
        }

    @Test
    fun control_saved_contacts_and_key_exchange_peers_stay_listed_once() =
        runBlocking {
            val listed = picker()
            assertEquals(1, listed.count { it.address == savedPeer }, "the saved contact must be listed once")
            assertEquals("Synthetic saved", listed.single { it.address == savedPeer }.name, "a saved contact keeps its saved name")
            assertEquals(1, listed.count { it.address == kexPeer }, "the key-exchange peer must be listed once")
            assertTrue(listed.map { it.address }.distinct().size == listed.size, "no address may be listed twice: $listed")
        }
}
