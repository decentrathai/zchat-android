package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.viewmodel.AutoSpendVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SavedContacts
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StrangerInvitePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-708 — deleting one group removes other groups' keys.
 *
 * A group key is stored under the group's id, an underscore and the key's number. Delete Group (also the Ignore of a
 * waiting invitation) removes every entry of the key store whose name starts with the id and an underscore. For a
 * record stored under an id that is the start of other ids, that is other groups' keys.
 *
 * Wanted: deleting a group removes only the entries named exactly its id, an underscore and a whole number (which may
 * be negative: an invitation's key number is read unchecked). Every other group's keys stay.
 *
 * The store is the real [ZchatPreferencesImpl] over in-memory files ([realPreferences]). The Ignore case drives the
 * real ChatViewModel.ignoreGroupInvite with the group calls answered by that store. All ids and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletingOneGroupNeverTouchesAnotherGroupsKeysTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `deleting a record stored under the id zgrp keeps the keys of every other group`() {
        val (store, files) = realPreferences()
        store.saveGroupInfo("zgrp", RECORD)
        seedTwoGroups(store)

        store.deleteGroup("zgrp")

        assertNull("the record itself must go", store.getGroupInfo("zgrp"))
        assertFourKeys(store, files, "after deleting the record stored under 'zgrp'")
    }

    @Test
    fun `Ignore on a waiting invitation stored under the id zgrp keeps the keys of every other group`() =
        runBlocking {
            val (store, files) = realPreferences()
            store.saveGroupInfo("zgrp", RECORD)
            assertTrue("fixture: the waiting mark is stored", store.setGroupInviteNeedsJoin("zgrp", WAITING))
            seedTwoGroups(store)
            val lenient = StrangerInvitePrefs(consent = true)
            val vm =
                HandshakeHarness.chatViewModelWith(groupCallsTo(store, lenient), HandshakeSeam(INVITER)).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
                }

            GroupInviteDriver.ignore(vm, "zgrp")

            assertNull("fixture: Ignore must delete the record", store.getGroupInfo("zgrp"))
            assertNull("fixture: Ignore must drop the waiting mark", store.getGroupInviteNeedsJoin("zgrp"))
            assertFourKeys(store, files, "after Ignore on the invitation stored under 'zgrp'")
        }

    @Test
    fun `guard - deleting a real group removes all of its keys, a negative number too, and no other group's`() {
        val (store, files) = realPreferences()
        seedTwoGroups(store)
        store.saveGroupKey(GROUP_A, -1, "synthetic-key-a-minus-1")

        store.deleteGroup(GROUP_A)

        val left = files.getValue(KEYS_FILE).snapshot()
        assertFalse("a key of the deleted group is still stored", left.keys.any { it.startsWith(GROUP_A) })
        assertEquals("synthetic-key-b-0", store.getGroupKey(GROUP_B, 0))
        assertEquals("synthetic-key-b-3", store.getGroupKey(GROUP_B, 3))
        assertEquals("the other group's keys must be all that is left", 2, left.size)
    }

    private fun seedTwoGroups(store: ZchatPreferencesImpl) {
        store.saveGroupInfo(GROUP_A, RECORD)
        store.saveGroupInfo(GROUP_B, RECORD)
        store.saveGroupKey(GROUP_A, 0, "synthetic-key-a-0")
        store.saveGroupKey(GROUP_A, 3, "synthetic-key-a-3")
        store.saveGroupKey(GROUP_B, 0, "synthetic-key-b-0")
        store.saveGroupKey(GROUP_B, 3, "synthetic-key-b-3")
    }

    private fun assertFourKeys(
        store: ZchatPreferencesImpl,
        files: Map<String, MemorySharedPreferences>,
        what: String,
    ) {
        assertEquals("$what: keys are missing from the key store", 4, files.getValue(KEYS_FILE).snapshot().size)
        assertEquals(what, "synthetic-key-a-0", store.getGroupKey(GROUP_A, 0))
        assertEquals(what, "synthetic-key-a-3", store.getGroupKey(GROUP_A, 3))
        assertEquals(what, "synthetic-key-b-0", store.getGroupKey(GROUP_B, 0))
        assertEquals(what, "synthetic-key-b-3", store.getGroupKey(GROUP_B, 3))
    }

    /** [lenient] for everything but the group record, key, waiting-mark and delete calls, which [store] answers. */
    private fun groupCallsTo(
        store: ZchatPreferencesImpl,
        lenient: StrangerInvitePrefs,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> "DeletingOneGroupStore"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        val target: Any = if (method.name in REAL_CALLS) store else lenient.proxy
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private companion object {
        const val GROUP_A = "zgrp_00000000000000000000708a"
        const val GROUP_B = "zgrp_00000000000000000000708b"
        const val INVITER = "usyntheticzc708inviter000001"
        const val KEYS_FILE = "groupKeysPrefs"
        const val RECORD = """{"id":"synthetic","name":"synthetic","active":true}"""
        const val WAITING =
            """{"inviter":"usyntheticzc708inviter000001","accepter":"usyntheticzc708self00000001","memo":"synthetic-memo"}"""
        val REAL_CALLS =
            setOf(
                "deleteGroup",
                "getGroupInfo",
                "saveGroupInfo",
                "getAllGroupIds",
                "getGroupKey",
                "saveGroupKey",
                "getGroupInviteNeedsJoin",
                "setGroupInviteNeedsJoin",
            )
    }
}
