package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 5; rule R2) — an accept must name the WHOLE key the dialog showed. In a
 * Tunnel chat, where the legacy path would ask the reconnect's price and then clear the flag.
 *
 * Must hold: a key that is no longer remembered (dismissed while the dialog was open), a record holding another key,
 * a prefix or a digest of the remembered key: nothing is adopted, wiped, asked or paid, the user is told
 * (ChatSendCopy.KEY_CHANGE_CHANGED_AGAIN) and the legacy reconnect is never entered; the price question's Pay checks
 * again; a memo that no longer verifies fails before anything is wiped. Control: with no record the × is ZC-535's
 * reconnect.
 */
class AnAcceptForAKeyThatIsNotRememberedFailsClosedTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a key that is not the remembered whole key changes nothing`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val remembered = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, remembered)
            assertNotNull("the refused key is not remembered", chat.record())
            val other = E2EEncryption.generateKeyPair().publicKey
            val prefix = remembered.publicKey.dropLast(4)
            val digest = remembered.publicKey.take(16)

            for ((what, key) in listOf("another key" to other, "a prefix of the key" to prefix, "a digest of the key" to digest)) {
                val asked = chat.accept(vm, key)

                assertNull("an accept for $what asked a price", asked)
                assertEquals("an accept for $what must tell the user", KeyAcceptApi.changedAgainText(), chat.error(vm))
                assertTrue("the banner must stay after an accept for $what", chat.flag())
                assertEquals("the record must stay after an accept for $what", remembered.publicKey, chat.recordKey())
                chat.assertNothingAdopted("after an accept for $what")
            }
        }

    @Test
    fun `a key whose record went while the dialog was open changes nothing`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val remembered = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, remembered)
            assertNotNull("the refused key is not remembered", chat.record())
            // Another screen tapped "Not their key" meanwhile.
            assertTrue(KeyAcceptApi.dismissInStore(chat.impl, PEER))

            val asked = chat.accept(vm, remembered.publicKey)

            assertNull("an accept for a dismissed key asked a price (the legacy reconnect)", asked)
            assertEquals(KeyAcceptApi.changedAgainText(), chat.error(vm))
            chat.assertNothingAdopted("after an accept for a dismissed key")
            assertEquals("the relay key must stay", KeyChangeReconnectChat.OLD_RELAY, chat.reconnect.relayKey)
        }

    @Test
    fun `Pay checks again and a record that went meanwhile changes and pays nothing`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val remembered = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, remembered)
            assertNotNull("the refused key is not remembered", chat.record())

            val asked = chat.accept(vm, remembered.publicKey, pay = false)
            assertEquals("a Tunnel accept asks ZC-535's question", "KEY_CHANGE_RECONNECT" to 2, asked)
            assertTrue(KeyAcceptApi.dismissInStore(chat.impl, PEER))
            vm.confirmPaidAction()
            chat.settle(vm)

            assertEquals(KeyAcceptApi.changedAgainText(), chat.error(vm))
            chat.assertNothingAdopted("after Pay for a key that is no longer remembered")
            assertEquals("the relay key must stay", KeyChangeReconnectChat.OLD_RELAY, chat.reconnect.relayKey)
        }

    @Test
    fun `a remembered memo that no longer verifies fails before anything is wiped`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val remembered = E2EEncryption.generateKeyPair()
            val memo = chat.raiseBanner(vm, remembered)
            assertNotNull("the refused key is not remembered", chat.record())
            // The stored memo is damaged: same key, a signature that no longer verifies.
            val signature = memo.substringAfter("${remembered.publicKey}:").substringBefore(":")
            val damaged = memo.replace(signature, signature.reversed())
            assertTrue(KeyAcceptApi.remember(chat.impl, PEER, remembered.publicKey, damaged, "synthetic-zc545-damaged", 5L))

            val asked = chat.accept(vm, remembered.publicKey)

            assertNull("an accept whose memo does not verify asked a price", asked)
            assertEquals(KeyAcceptApi.changedAgainText(), chat.error(vm))
            assertTrue("the banner must stay", chat.flag())
            chat.assertNothingAdopted("after an accept whose memo does not verify")
        }

    @Test
    fun `control - with no remembered key the accept is the reconnect of ZC-535`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            chat.impl.setE2EKeyChanged(PEER, true)

            vm.dismissE2EKeyChanged(PEER)
            chat.settle(vm)

            assertEquals("the legacy × asks the reconnect's price", "KEY_CHANGE_RECONNECT" to 2, chat.question(vm))
            assertEquals("the old key stays", chat.oldContact.publicKey, chat.storedKey())
        }

    private companion object {
        const val PEER = "usyntheticzc545failsclosed01"
    }
}
