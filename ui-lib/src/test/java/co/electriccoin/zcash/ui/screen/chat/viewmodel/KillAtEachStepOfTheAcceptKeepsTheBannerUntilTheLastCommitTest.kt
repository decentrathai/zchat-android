package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 10; rules R5, R7, R8) — the accept is a sequence of writes and the process
 * can end after any of them.
 *
 * Must hold for a kill after every N-th write of a Shielded accept that pays one key exchange, read back by a fresh
 * ViewModel over the same store: while the record exists the flag is up, sends are refused and the contact's stored
 * key is the old key, none or the remembered key, never another; once the record is gone the remembered key is stored
 * and the flag is down. After every N an attacker's KEX and KEXACK are never stored. "Not their key" is refused once
 * the old key is gone. Accepting again completes with our key unchanged, and both attempts together never pay more
 * than the one key exchange that was quoted. No work record or replay exists.
 */
class KillAtEachStepOfTheAcceptKeepsTheBannerUntilTheLastCommitTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a kill after any write leaves the banner until the last commit and a second accept completes`() =
        runBlocking {
            var n = 0
            var completedWithoutKill = false
            while (!completedWithoutKill) {
                assertTrue("the accept never completed within $MAX_WRITES writes", n <= MAX_WRITES)
                val chat = KeyChangeAcceptChat(PEER)
                val vm = chat.viewModel()
                val remembered = E2EEncryption.generateKeyPair()
                val attacker = E2EEncryption.generateKeyPair()
                chat.raiseBanner(vm, remembered, "synthetic-zc545-kill-$n")
                assertNotNull("the refused key is not remembered", chat.record())

                vm.resetSendState()
                KeyAcceptApi.accept(vm, PEER, remembered.publicKey, chat.our.publicKey)
                chat.settle(vm)
                assertEquals("write $n: the Shielded accept asks one message", "KEY_CHANGE_ACCEPT" to 1, chat.question(vm))
                chat.killAfter(n)
                vm.confirmPaidAction()
                chat.settle(vm)
                completedWithoutKill = !chat.killed
                ResetOwedChat.kill(vm)
                chat.revive()

                val later = chat.viewModel()
                val stored = chat.storedKey()
                if (chat.record() != null) {
                    assertTrue("write $n: the record exists and the banner is down", chat.flag())
                    assertTrue("write $n: the record exists and a send is not refused", chat.sendsRefused(later))
                    assertTrue(
                        "write $n: the stored key is neither the old key, none nor the remembered key",
                        stored == null || stored == chat.oldContact.publicKey || stored == remembered.publicKey,
                    )
                    assertEquals("write $n: the record is not the key the user was shown", remembered.publicKey, chat.recordKey())
                } else {
                    assertEquals("write $n: the record is gone and the remembered key is not stored", remembered.publicKey, stored)
                    assertFalse("write $n: the record is gone and the banner is still up", chat.flag())
                }
                assertEquals("write $n: our key changed", chat.our.publicKey, chat.impl.getE2EOurPublicKey(PEER))

                // An attacker's offers after the kill are never stored.
                val recordBefore = chat.record() != null
                chat.kexFromContact(later, attacker, "synthetic-zc545-kill-attacker-kex-$n")
                chat.kexAckFromContact(later, attacker, "synthetic-zc545-kill-attacker-ack-$n")
                assertFalse("write $n: an attacker's key was stored", chat.storedKey() == attacker.publicKey)
                assertTrue("write $n: an attacker's offer left the banner down", chat.flag())
                if (recordBefore) {
                    assertEquals("write $n: an attacker's offer replaced the record", remembered.publicKey, chat.recordKey())
                    if (stored != chat.oldContact.publicKey) {
                        // The old key is gone: "Not their key" must be refused, the flag has to stay.
                        later.resetSendState()
                        KeyAcceptApi.dismiss(later, PEER)
                        chat.settle(later)
                        assertEquals("write $n: Not their key was accepted with no old key left", remembered.publicKey, chat.recordKey())
                        assertTrue("write $n: Not their key took the banner down with no old key left", chat.flag())
                    }
                    // The user accepts again: it completes, and the key exchange is paid once in all.
                    chat.accept(later, remembered.publicKey)
                    chat.owed.openChat(later)
                    chat.owed.tick(later)
                    chat.settle(later)
                    chat.assertAdopted(remembered, "write $n: after accepting again")
                }
                assertTrue(
                    "write $n: two attempts paid more than the one key exchange quoted: ${chat.seam.submitted}",
                    chat.seam.submitted.size <= 1 && chat.seam.submittedOf("KEX") == chat.seam.submitted.size,
                )
                ResetOwedChat.kill(later)
                n++
            }
            assertTrue("fixture: the accept is more than a handful of writes ($n)", n >= MIN_WRITES)
        }

    @Test
    fun `there is no work record and no replay`() {
        val source = SourceScan.stripComments(ChatResourceText.source(CVM)) + SourceScan.stripComments(ChatResourceText.source(PREFS))
        for (word in listOf("adoptwork", "AdoptWork", "keyOfferCandidates", "pinnedKeyOffer", "acceptGeneration")) {
            assertFalse("the accept must not grow a $word", word in source)
        }
        assertTrue("the accept does not exist", "fun acceptRefusedKey(" in source)
    }

    private companion object {
        const val PEER = "usyntheticzc545killateach001"
        const val MAX_WRITES = 60
        const val MIN_WRITES = 6
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val PREFS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt"
    }
}
