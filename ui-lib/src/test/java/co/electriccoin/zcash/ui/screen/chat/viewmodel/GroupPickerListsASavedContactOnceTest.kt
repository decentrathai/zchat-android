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

/**
 * ZC-1221 — New Group listed the same contact twice on an upgraded wallet. GroupViewModel.loadAvailableContacts
 * only de-duplicated the key-exchange and relay peers against the saved list; the saved list itself
 * (`saved + kexPeers + relayPeers`) was passed on as it came from the address book, so two saved entries for one
 * address (or one that differs only in case) gave two rows.
 *
 * Wanted: one row per canonical (trimmed, lower-case) address; the first saved entry wins. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupPickerListsASavedContactOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val peer = SyntheticUnifiedAddress.mainnet(9_201)
    private val other = SyntheticUnifiedAddress.mainnet(9_202)

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
            when (method.name) {
                "resolvePeerAddress" -> args!![0] as String
                "isSelfAddress" -> args!![0] == HandshakeHarness.SELF
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
            newName: String,
        ) = Unit

        override fun clearAll() = Unit
    }

    private suspend fun picker(saved: List<Contact>): List<Contact> {
        val f = GroupPaidOnceFixture()
        val vm = f.groupViewModel()
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, prefs)
        GroupViewModel::class.java
            .getDeclaredField("contactBook")
            .apply { isAccessible = true }
            .set(vm, Saved(saved))
        f.awaitNewJobs(vm) { vm.loadAvailableContacts() }
        return vm.createGroupState.value.availableContacts
    }

    @Test
    fun two_saved_entries_with_the_same_address_are_one_row() =
        runBlocking {
            val listed =
                picker(
                    listOf(
                        Contact(address = peer, name = "Synthetic first"),
                        Contact(address = other, name = "Synthetic other"),
                        Contact(address = peer, name = "Synthetic first"),
                    ),
                )
            assertEquals(1, listed.count { it.address == peer }, "the same saved address was listed twice: $listed")
            assertEquals(2, listed.size, "one row per address expected: $listed")
        }

    @Test
    fun two_saved_entries_differing_only_in_case_are_one_row_and_the_first_wins() =
        runBlocking {
            val listed =
                picker(
                    listOf(
                        Contact(address = peer, name = "Synthetic first"),
                        Contact(address = peer.uppercase(), name = "Synthetic second"),
                    ),
                )
            assertEquals(1, listed.size, "the same canonical address was listed twice: $listed")
            assertEquals("Synthetic first", listed.single().name, "the first saved entry wins")
        }
}
