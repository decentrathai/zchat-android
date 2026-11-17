package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-739-R2 (review of T7bH, 19 Oct 2026, finding 2, P1) — "Not their key" is free. An "Accept the new key" that wrote
 * its quoted owed key exchange (reason: reconnect, quoted for the REFUSED key) and then ended before its wipe (a kill, or
 * the ratchet wipe throwing) leaves the old key stored, the record and the flag up, and the owed mark. "Not their key"
 * then goes through (the old key is stored) and, since ZC-739, starts the held work: the owed key exchange quoted for
 * the key the user has just rejected is paid at once (17,000 zat), and ZC-535 keeps it from settling unpaid.
 *
 * Must hold: after "Not their key" nothing is paid for the rejected key: 0 transactions, the owed mark and any
 * allowance the accept wrote for it are gone, the old key stays. Shielded (one quoted key exchange) and Tunnel (the
 * reconnect's key exchange and ZBOOT). The kill point is found by walking the accept's writes (as
 * KillAtEachStepOfTheAcceptKeepsTheBannerUntilTheLastCommitTest does) until the owed mark stands with the old key still
 * stored. Our current relay identity counts as delivered before the key change (in the Tunnel case a chat whose
 * ZBOOT is still due would get it under the standing consent after any decision, which is not the accept's doing).
 * Every key and address is synthetic.
 */
class NotTheirKeyAfterAnInterruptedAcceptPaysNothingTest : KeyChangeAcceptTestBase() {
    private suspend fun interruptedAcceptThenNotTheirKey(mode: ConversationMode) {
        var n = 0
        while (true) {
            assertTrue("fixture: no kill point left the owed mark with the old key stored", n <= MAX_WRITES)
            val chat = KeyChangeAcceptChat(PEER, mode = mode, consent = true)
            val vm = chat.viewModel()
            // Our current relay identity was delivered before the key change, so no ZBOOT is due but the one an accept quotes.
            chat.owed.store.setSentNostrBootPubkey(PEER, ZbootSetup.currentIdentity(vm))
            val refused = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, refused, "synthetic-zc739r2-kex-$n")
            assertNotNull("fixture: the refused key is not remembered", chat.record())
            vm.resetSendState()
            KeyAcceptApi.accept(vm, PEER, refused.publicKey, chat.our.publicKey)
            chat.settle(vm)
            chat.killAfter(n)
            vm.confirmPaidAction()
            chat.settle(vm)
            val killed = chat.killed
            ResetOwedChat.kill(vm)
            chat.revive()
            if (!killed) {
                throw AssertionError("fixture: the accept finished before any kill left the owed mark with the old key stored")
            }
            val interruptedState =
                chat.record() != null && chat.storedKey() == chat.oldContact.publicKey && PEER in chat.owed.kexOwed
            if (!interruptedState) {
                n++
                continue
            }
            assertEquals("fixture: something was paid before the kill", 0, chat.seam.submitted.size)

            val later = chat.viewModel()
            later.resetSendState()
            KeyAcceptApi.dismiss(later, PEER, refused.publicKey)
            chat.settle(later)
            repeat(3) {
                chat.owed.tick(later)
                chat.settle(later)
            }
            chat.owed.openChat(later)
            chat.settle(later)

            assertFalse("fixture: Not their key did not take the warning down", chat.flag())
            assertEquals("the old key must stay after Not their key", chat.oldContact.publicKey, chat.storedKey())
            assertEquals(
                "Not their key paid what an interrupted accept quoted for the rejected key: ${chat.seam.submitted}",
                0,
                chat.seam.submitted.size,
            )
            assertFalse("the owed key exchange quoted for the rejected key stayed owed", PEER in chat.owed.kexOwed)
            assertFalse("the allowance quoted for the rejected key stayed", chat.owed.zbootQuoted[PEER] == true)
            ResetOwedChat.kill(later)
            return
        }
    }

    @Test
    fun `Not their key after a Shielded accept that ended before its wipe pays nothing and drops the owed key exchange`() =
        runBlocking { interruptedAcceptThenNotTheirKey(ConversationMode.VAULT) }

    @Test
    fun `Not their key after a Tunnel accept that ended before its wipe pays nothing and drops what it owed`() =
        runBlocking { interruptedAcceptThenNotTheirKey(ConversationMode.TUNNEL) }

    private companion object {
        const val PEER = "usyntheticzc739r2notthekey01"
        const val MAX_WRITES = 60
    }
}
