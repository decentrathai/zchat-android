package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 2; rules R1, R2, R7) — every clear of the key-changed flag would delete the
 * decision the user has not made yet: a verified ZBOOT clears it by itself, the legacy × clears it in a Shielded chat
 * and after the reconnect question elsewhere.
 *
 * Must hold while a refused key is remembered: setE2EKeyChanged(false) (what the verified ZBOOT, the Shielded × and
 * the reconnect call) changes nothing; the legacy dismissE2EKeyChanged never enters the reconnect and shows the
 * existing cue; only "Not their key" (while the old key is stored), Reset encryption and the accept's last write
 * remove the record, each together with the flag. Control: with no record the Shielded × clears the flag as before.
 */
class FlagClearsNeverDeleteTheRememberedKeyTest : KeyChangeAcceptTestBase() {
    @Test
    fun `the clear a verified ZBOOT or the legacy accept makes leaves the flag and the record`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            // What routeIncomingBoot does for a verified ZBOOT, and what both legacy accepts do.
            chat.store.setE2EKeyChanged(PEER, false)

            assertTrue("a flag clear took the banner down while a new key waits for the user", chat.flag())
            assertEquals("a flag clear deleted the remembered key", newKeys.publicKey, chat.recordKey())
        }

    @Test
    fun `the legacy accept with a remembered key changes nothing in a Shielded chat`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            vm.dismissE2EKeyChanged(PEER)
            chat.settle(vm)

            assertTrue("the legacy × cleared the flag although a new key waits", chat.flag())
            assertEquals("the legacy × deleted the remembered key", newKeys.publicKey, chat.recordKey())
            assertEquals("the legacy × shows the existing cue", ChatSendCopy.KEY_CHANGED_VERIFY, chat.error(vm))
            chat.assertNothingAdopted("after the legacy × with a remembered key")
        }

    @Test
    fun `the legacy accept with a remembered key never enters the reconnect in a Tunnel chat`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKeys)
            assertNotNull("the refused key is not remembered", chat.record())

            vm.dismissE2EKeyChanged(PEER)
            chat.settle(vm)
            assertNull("the legacy × asked the reconnect's price although a new key waits", chat.question(vm))
            vm.dismissE2EKeyChanged(PEER, costConfirmed = true)
            chat.settle(vm)

            assertTrue("the legacy reconnect cleared the flag", chat.flag())
            assertEquals("the legacy reconnect deleted the remembered key", newKeys.publicKey, chat.recordKey())
            assertEquals("the legacy reconnect dropped the relay key", KeyChangeReconnectChat.OLD_RELAY, chat.reconnect.relayKey)
            chat.assertNothingAdopted("after the legacy reconnect with a remembered key")
        }

    @Test
    fun `Not their key removes the record and the flag together and keeps the old key`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            chat.raiseBanner(vm, E2EEncryption.generateKeyPair())
            assertNotNull("the refused key is not remembered", chat.record())

            KeyAcceptApi.dismiss(vm, PEER)
            chat.settle(vm)

            assertNull("Not their key left the record", chat.record())
            assertFalse("Not their key left the banner up", chat.flag())
            assertFalse("after Not their key the user's sends go to the old key again", chat.sendsRefused(vm))
            chat.assertNothingAdopted("after Not their key")
        }

    @Test
    fun `Reset encryption removes the record and the flag`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            chat.raiseBanner(vm, E2EEncryption.generateKeyPair())
            assertNotNull("the refused key is not remembered", chat.record())

            vm.resetSecureSession(PEER)
            chat.settle(vm)
            assertEquals("Reset asks its own price first", "RESET_ENCRYPTION" to 2, chat.question(vm))
            vm.confirmPaidAction()
            chat.settle(vm)

            assertNull("a Reset left the remembered key", chat.record())
            assertFalse("a Reset left the flag", chat.flag())
            assertNull("a Reset clears the contact's key", chat.storedKey())
        }

    @Test
    fun `control - with no remembered key the Shielded accept clears the flag as before`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            // A flag raised before this build (or by a relay-key change): no record.
            chat.impl.setE2EKeyChanged(PEER, true)

            vm.dismissE2EKeyChanged(PEER)
            chat.settle(vm)

            assertFalse("the Shielded × with no remembered key must clear the flag", chat.flag())
            assertEquals("the old key stays", chat.oldContact.publicKey, chat.storedKey())
            assertEquals("nothing is paid", 0, chat.seam.proposals())
        }

    private companion object {
        const val PEER = "usyntheticzc545flagclears001"
    }
}
