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
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 6; rule R10) — the safety number is computed from two keys, ours and theirs.
 * An accept bound only to the contact's key would adopt a pair the user never compared when our own key was replaced
 * (a Reset encryption) while the dialog was open.
 *
 * Must hold: the dialog's snapshot is (our key, the refused key) and Accept passes both; with our key replaced the
 * stale dialog's Accept fails closed (nothing wiped, asked or paid; both stored keys unchanged), also when the
 * replacement lands after the accept's first check and before it takes the per-contact lock: the check inside the lock
 * runs before the ratchet wipe. Control: with both keys unchanged the pair is adopted and its number is the number
 * shown.
 */
class TheAcceptIsBoundToOurKeyAsWellTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a stale dialog whose own key was replaced fails closed`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val refused = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, refused)
            val view = KeyAcceptApi.view(vm, PEER)
            assertNotNull("the dialog has no remembered key to show", view)
            val shownOurKey = KeyAcceptApi.get(view!!, "ourKey") as String
            // Our key pair for the contact is replaced while the dialog is open (what a Reset encryption does).
            val replaced = E2EEncryption.generateKeyPair()
            chat.impl.setE2EOurKeys(PEER, replaced.publicKey, replaced.privateKey)
            chat.writes.clear()

            val asked = chat.accept(vm, refused.publicKey, ourKey = shownOurKey)

            assertNull("a stale accept asked a price", asked)
            assertEquals(KeyAcceptApi.changedAgainText(), chat.error(vm))
            assertEquals("the contact's stored key changed", chat.oldContact.publicKey, chat.storedKey())
            assertEquals("our replaced key changed", replaced.publicKey, chat.impl.getE2EOurPublicKey(PEER))
            assertEquals("a stale accept deleted ratchet state", emptyList<String>(), chat.reset.ratchetDeletes.toList())
            assertEquals("a stale accept wiped something", emptyList<String>(), chat.reset.wipes.toList())
            assertEquals("a stale accept paid", 0, chat.seam.proposals())
            assertTrue("the banner must stay", chat.flag())
        }

    @Test
    fun `our key replaced after the first check is caught inside the lock before anything is wiped`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, mode = ConversationMode.TUNNEL)
            val vm = chat.viewModel()
            val refused = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, refused)
            assertNotNull("the refused key is not remembered", chat.record())
            val asked = chat.accept(vm, refused.publicKey, pay = false)
            assertEquals("a Tunnel accept asks ZC-535's question", "KEY_CHANGE_RECONNECT" to 2, asked)

            // A Reset holds the per-contact lock: Pay passes its own check and then waits for the lock.
            val lock = KeyAcceptApi.peerSessionLock(PEER)
            lock.lock()
            val replaced = E2EEncryption.generateKeyPair()
            try {
                vm.confirmPaidAction()
                HandshakeHarness.awaitTrue { PEER in KeyAcceptApi.reconnectsInFlight() }
                assertEquals("the accept wiped before it held the lock", emptyList<String>(), chat.reset.ratchetDeletes.toList())
                chat.impl.setE2EOurKeys(PEER, replaced.publicKey, replaced.privateKey)
            } finally {
                lock.unlock()
            }
            chat.settle(vm)

            assertEquals(KeyAcceptApi.changedAgainText(), chat.error(vm))
            assertEquals("the check inside the lock ran after the ratchet wipe", emptyList<String>(), chat.reset.ratchetDeletes.toList())
            assertEquals("nothing may be wiped", emptyList<String>(), chat.reset.wipes.toList())
            assertEquals("the contact's stored key changed", chat.oldContact.publicKey, chat.storedKey())
            assertEquals("nothing may be paid", 0, chat.seam.submitted.size)
            assertEquals("nothing may be owed", false, PEER in chat.owed.kexOwed)
            assertTrue("the banner must stay", chat.flag())
        }

    @Test
    fun `control - with both keys unchanged the pair is adopted and its number is the number shown`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val refused = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, refused, "synthetic-zc545-bound-ack")
            val view = KeyAcceptApi.view(vm, PEER)
            assertNotNull("the dialog has no remembered key to show", view)
            val shown = KeyAcceptApi.get(view!!, "safetyNumber") as String

            chat.accept(vm, KeyAcceptApi.get(view, "refusedKey") as String, ourKey = KeyAcceptApi.get(view, "ourKey") as String)

            chat.assertAdopted(refused, "after the accept")
            assertEquals("the adopted pair's number is not the number shown", shown, vm.computeSafetyNumber(PEER))
        }

    private companion object {
        const val PEER = "usyntheticzc545boundtoours01"
    }
}
