package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 6.2a) — the compat pin, green before and after the fix: memos as ZChat
 * 2.12.4 builds them are filed in the same chats in a store 2.12.4 left (both directions, no own-id record), and a 2.12.4
 * contact that later writes under a new id for the same address is filed by its address.
 *
 * [Frozen2124] copies the builders from the tag (ZMSGProtocol.kt@v2.12.4 lines 144-149, 189-192, 202-206; the
 * constants ZMSGConstants.kt@v2.12.4 lines 44 and 71). All addresses and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdsOf2124StillRouteTest {
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

    /** ZChat 2.12.4's v4 builders, copied from the tag. */
    private object Frozen2124 {
        private const val PREFIX_V4 = "ZMSG|v4|" // ZMSGConstants.kt@v2.12.4 line 44
        private const val INIT_MARKER = "INIT|" // ZMSGConstants.kt@v2.12.4 line 71

        // ZMSGProtocol.kt@v2.12.4 lines 144-149
        private fun generateAddressHash(address: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(address.toByteArray())
            return hashBytes.take(8).joinToString("") { "%02x".format(it) }
        }

        // lines 189-192
        fun createV4InitMessage(
            convId: String,
            senderAddress: String,
            message: String,
        ): String = "$PREFIX_V4$convId|$INIT_MARKER$senderAddress|$message"

        // lines 202-206
        fun createV4ReplyMessage(
            convId: String,
            senderAddress: String,
            message: String,
        ): String = "$PREFIX_V4$convId|${generateAddressHash(senderAddress)}|$message"
    }

    private val me = SyntheticUnifiedAddress.mainnet(seed = 89301)
    private val old = SyntheticUnifiedAddress.mainnet(seed = 89302)

    @Test
    fun `memos as 2_12_4 built them are filed in the chats a 2_12_4 store names`() =
        runBlocking {
            val pass = FcIdPass(me)
            // The store 2.12.4 left: the contact's id in both directions, no own-id record.
            pass.ids.file
                .edit()
                .putString("conv:OLD21240", old)
                .putString("peer:$old", "OLD21240")
                .commit()
            pass.received("fcid-2124-reply", Frozen2124.createV4ReplyMessage("OLD21240", old, "synthetic 2.12.4 reply"), at = 100)
            pass.received("fcid-2124-newid", Frozen2124.createV4InitMessage("NEW21241", old, "synthetic 2.12.4 new id"), at = 200)

            val chats = pass.run()

            val chat = chats.single { it.peerAddress == old }
            assertEquals("both 2.12.4 memos are filed in the contact's one chat", 2, chat.messages.count { !it.isOutgoing })
            assertEquals(old, pass.ids.conv("OLD21240"))
            assertEquals("a new id from the same 2.12.4 contact is filed by its address", old, pass.ids.conv("NEW21241"))
            assertTrue("no own-id record is invented for ids a 2.12.4 store holds", pass.ids.ownKeys().isEmpty())
        }
}
