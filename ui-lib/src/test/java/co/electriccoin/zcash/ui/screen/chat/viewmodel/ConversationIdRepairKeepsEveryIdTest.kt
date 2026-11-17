package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 6.2a case f): the start-up repair of the conversation ids
 * (validateAndRepairConvIdMappings) over a store with two ids for one address and a missing direction: no `conv:` entry
 * holds another address than before, the missing direction is filled, and no own-id record is added or lost. The real
 * repair over the real store functions ([ConversationIdStoreFixture]). Ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationIdRepairKeepsEveryIdTest {
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
    fun `(f) guard - the start-up repair moves no id, fills the missing direction and keeps the own-id records`() =
        runBlocking {
            val a = SyntheticUnifiedAddress.mainnet(seed = 89211)
            val b = SyntheticUnifiedAddress.mainnet(seed = 89212)
            val ids = ConversationIdStoreFixture()
            val (own, _) = ids.store.getOrCreateConversationIdForMessage(a)
            ids.file
                .edit()
                .putString("conv:FRGN8921", a)
                .putString("conv:BBBB8921", b)
                .commit()
            val convBefore = ids.file.all.filterKeys { it.startsWith("conv:") }
            val ownBefore = ids.ownKeys().associateWith { ids.file.getString(it, null) }
            val vm = HandshakeHarness.handshakeViewModel(HandshakePrefs(a), HandshakeSeam(a))
            PaymentHarness.setField(vm, "zchatPreferences", ids.store)
            // What the constructor gives the repair: the gate it opens when done (unset in the constructor-free harness).
            val repairDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            PaymentHarness.setField(vm, "repairComplete", repairDone)

            ChatViewModel::class.java
                .getDeclaredMethod("validateAndRepairConvIdMappings")
                .apply { isAccessible = true }
                .invoke(vm)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("the repair did not finish", true, repairDone.isCompleted)

            assertEquals("the repair moved a conv: entry", convBefore, ids.file.all.filterKeys { it.startsWith("conv:") })
            assertEquals("the address's own id was replaced", own, ids.peer(a))
            assertEquals("the missing direction was not filled", "BBBB8921", ids.peer(b))
            assertEquals("an own-id record was added or lost", ownBefore, ids.ownKeys().associateWith { ids.file.getString(it, null) })
        }
}
