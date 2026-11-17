package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ZC-536 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §4) — the allowance of a quoted ZBOOT outlives the action that
 * quoted it.
 *
 * A confirmed Reset, key-change reconnect or first message writes the permission for one paid ZBOOT (the allowance) before
 * its key exchange. Only the stamp of a ZBOOT that pays uses it up. When the ZBOOT it counted settles without paying, the
 * allowance stays, and a later key reply or reply ZBOOT can pay one ZBOOT (about 17,000 zatoshi) on it without a fresh
 * question:
 * - our relay identity is already recorded as delivered to the contact (the sender returns at once);
 * - a ZBOOT for the same identity was paid within the hour (the sender settles as "already delivered");
 * - a Reset's key exchange settles because the contact's own key exchange completed it (no key reply follows).
 *
 * Must hold: each of these leaves no allowance. A withdrawal that the store does not save pays nothing in this process
 * either (ZC-590, the failed-write half). Controls, green before and after: a ZBOOT that only waits for a block keeps its
 * allowance and is paid once; a key exchange that settles because it was paid within the hour keeps the allowance the
 * pending key reply needs, and that reply pays the ZBOOT once; a ZBOOT marked owed after the end of the process (ZC-534)
 * keeps its allowance when a key reply arrives first; and the register's own scenario (a reconnect with a complete
 * exchange leaves no allowance), which ZC-535 closed.
 *
 * Drives the real ViewModel through the real use case and repository ([ResetOwedChat], [KeyChangeReconnectChat]). Every
 * address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotedZbootAllowanceNeverOutlivesItsActionTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc536allowance0001"
        val ZBOOT_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)
        val KEX_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.KEX, PEER)

        /** Process-wide ZBOOT bookkeeping an earlier test may have left (the names that exist in this build). */
        fun resetZbootProcessState() {
            for (name in listOf("nostrBootInFlight", "unsentZbootQuotes", "unsavedZbootWithdrawals", "reconnectsInFlight")) {
                val field =
                    ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
                        ?: continue
                field.isAccessible = true
                (field.get(null) as? MutableCollection<*>)?.clear()
                (field.get(null) as? MutableMap<*, *>)?.clear()
            }
        }
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootProcessState()
    }

    // ---- the three leaks -----------------------------------------------------------------------------------------

    @Test
    fun `a quoted ZBOOT whose identity is already recorded as delivered leaves no allowance`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()
            confirmResetAndPayBoth(chat, vm, contact)
            // A later confirmed question quoted our ZBOOT again while the identity it would carry is recorded as delivered.
            chat.zbootQuoted[PEER] = true
            assertTrue("fixture sanity: our identity is recorded as delivered", chat.store.getSentNostrBootPubkey(PEER) != null)

            chat.kexAckFromContact(vm, contact, txId = "synthetic-zc536-kexack-02")
            awaitIdle(vm)

            assertEquals("a ZBOOT was paid for an identity already delivered", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse(
                "the ZBOOT this allowance counted needs no payment (identity already delivered), but the allowance stays: " +
                    "a later trigger can pay one ZBOOT on it without a question",
                chat.zbootQuoted[PEER] == true,
            )
        }

    @Test
    fun `a quoted ZBOOT that settles as already paid within the hour leaves no allowance`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()
            confirmResetAndPayBoth(chat, vm, contact)
            assertTrue("fixture sanity: the paid ZBOOT left its paid record", chat.paidRecords[ZBOOT_SLOT] != null)
            // Quoted again, and the delivered-identity marker re-armed (as a Reconnect does): only the paid record of the
            // last hour says this ZBOOT needs no payment.
            chat.zbootQuoted[PEER] = true
            chat.store.setSentNostrBootPubkey(PEER, null)

            chat.kexAckFromContact(vm, contact, txId = "synthetic-zc536-kexack-03")
            awaitIdle(vm)

            assertEquals("a ZBOOT was paid twice within the hour", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse(
                "the ZBOOT this allowance counted settled as paid within the hour, but the allowance stays: " +
                    "a later trigger can pay one ZBOOT on it without a question",
                chat.zbootQuoted[PEER] == true,
            )
        }

    @Test
    fun `a Reset whose key exchange settles because the contact's own key exchange completed it leaves no allowance`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()
            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture sanity: the Reset's KEX waits for a block", 0, chat.seam.submittedOf("KEX"))
            assertEquals("fixture sanity: the Reset wrote its allowance", true, chat.zbootQuoted[PEER])
            assertTrue("fixture sanity: the Reset's KEX is owed", PEER in chat.kexOwed)

            // The contact's own key exchange arrives first and completes the exchange: our KEX is not needed any more,
            // and no key reply (KEXACK) will follow it.
            HandshakeHarness.handleKexMemo(vm, kexFromContact(E2EEncryption.generateKeyPair()), "synthetic-zc536-kex-04")
            assertTrue("fixture sanity: the contact's KEX completed the exchange", chat.store.isE2EKeyExchangeComplete(PEER))
            chat.heights.advance()
            awaitIdle(vm)

            assertEquals("the Reset's KEX was paid although the exchange was complete", 0, chat.seam.submittedOf("KEX"))
            assertFalse("fixture sanity: the settled KEX is still marked owed", PEER in chat.kexOwed)
            assertEquals("a ZBOOT was paid", 0, chat.seam.submittedOf("ZBOOT"))
            assertFalse(
                "the Reset's KEX settled unpaid and no key reply will follow, but its ZBOOT allowance stays: " +
                    "a later trigger can pay one ZBOOT on it without a question",
                chat.zbootQuoted[PEER] == true,
            )
        }

    /**
     * ZC-590 (the failed-write half): withdrawing an allowance can fail to be saved. It then still pays nothing in this
     * process, and is withdrawn for good as soon as the store saves again.
     */
    @Test
    fun `an allowance whose withdrawal is not saved pays no ZBOOT`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()
            confirmResetAndPayBoth(chat, vm, contact)
            chat.zbootQuoted[PEER] = true
            chat.refusedClears += "setZbootQuoted"

            // The identity is recorded as delivered: the allowance is withdrawn, and the store does not save that.
            chat.kexAckFromContact(vm, contact, txId = "synthetic-zc536-kexack-05")
            awaitIdle(vm)
            assertEquals("fixture sanity: the store kept the allowance", true, chat.zbootQuoted[PEER])

            // Something re-arms the ZBOOT after the hour (a failed delivery, a new trigger).
            chat.store.setSentNostrBootPubkey(PEER, null)
            chat.paidRecords.remove(ZBOOT_SLOT)
            chat.kexAckFromContact(vm, contact, txId = "synthetic-zc536-kexack-06")
            awaitIdle(vm)

            assertEquals(
                "a ZBOOT was paid on an allowance that had been withdrawn (the withdrawal was not saved)",
                1,
                chat.seam.submittedOf("ZBOOT"),
            )

            // The store saves again: the next look withdraws it for good.
            chat.refusedClears.clear()
            chat.kexAckFromContact(vm, contact, txId = "synthetic-zc536-kexack-07")
            awaitIdle(vm)
            assertEquals("a ZBOOT was paid on a withdrawn allowance", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse("the withdrawn allowance was never removed from the store", chat.zbootQuoted[PEER] == true)
        }

    // ---- controls (green before and after) -----------------------------------------------------------------------

    @Test
    fun `control - a ZBOOT waiting for a block keeps its allowance and is paid once`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val vm = chat.viewModel()
            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the Reset's KEX went", 1, chat.seam.submittedOf("KEX"))
            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc536-kexack-08")
            awaitTrue { chat.heights.isWaiting() }

            assertEquals("no ZBOOT went yet", 0, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the allowance of a ZBOOT that only waits for a block was withdrawn", true, chat.zbootQuoted[PEER])
            assertTrue("the waiting ZBOOT is not marked owed (ZC-534)", PEER in chat.zbootOwed)

            chat.heights.advance()
            awaitIdle(vm)
            assertEquals("the waiting ZBOOT was not paid once after the block", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse("the paid ZBOOT did not use up its allowance", chat.zbootQuoted[PEER] == true)
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
        }

    @Test
    fun `control - a KEX that settles because it was paid within the hour keeps the allowance a pending KEXACK needs`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()
            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture sanity: the Reset's KEX waits for a block", 0, chat.seam.submittedOf("KEX"))
            // Another attempt paid a KEX carrying the same new key meanwhile (its paid record, inside the hour).
            val ourKey = requireNotNull(chat.store.getE2EOurPublicKey(PEER)) { "fixture sanity: the Reset created our new key" }
            chat.paidRecords[KEX_SLOT] = HandshakePaidRecord.encode(ourKey, System.currentTimeMillis())

            chat.heights.advance()
            awaitIdle(vm)
            assertEquals("the KEX was paid again within the hour", 0, chat.seam.submittedOf("KEX"))
            assertFalse("fixture sanity: the settled KEX is still marked owed", PEER in chat.kexOwed)
            assertEquals(
                "the allowance was withdrawn although the contact's key reply to the paid KEX is still to come",
                true,
                chat.zbootQuoted[PEER],
            )

            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc536-kexack-09")
            awaitIdle(vm)
            assertEquals("the key reply did not pay the quoted ZBOOT once", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse("the paid ZBOOT did not use up its allowance", chat.zbootQuoted[PEER] == true)
        }

    /** ZC-534's owed ZBOOT: its delivered-identity marker is stale, so a key reply must not read it as "delivered". */
    @Test
    fun `control - a ZBOOT owed after the end of the process keeps its allowance when a key reply arrives first`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val first = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()
            first.resetSecureSession(PEER)
            awaitIdle(first)
            first.confirmPaidAction()
            awaitIdle(first)
            chat.kexAckFromContact(first, contact, txId = "synthetic-zc536-kexack-10")
            awaitTrue { chat.heights.isWaiting() }
            ResetOwedChat.kill(first)
            resetZbootProcessState()
            assertTrue("fixture sanity: the ZBOOT is marked owed", PEER in chat.zbootOwed)

            val next = chat.viewModel()
            chat.kexAckFromContact(next, contact, txId = "synthetic-zc536-kexack-11")
            awaitIdle(next)
            assertEquals("the allowance of an owed ZBOOT was withdrawn by a key reply", true, chat.zbootQuoted[PEER])
            assertTrue("the owed mark was withdrawn by a key reply", PEER in chat.zbootOwed)

            chat.tick(next)
            awaitIdle(next)
            assertEquals("the owed ZBOOT was not paid once by the next block", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse("the paid ZBOOT did not use up its allowance", chat.zbootQuoted[PEER] == true)
        }

    /** The register's own scenario. It leaked at the ZC-300 fix; ZC-535 closed it (the KEX is sent, the ZBOOT fired). */
    @Test
    fun `control - the reconnect with a complete exchange leaves no allowance`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAsked(vm, 2)
            vm.confirmPaidAction()
            var seen = -1
            while (seen != chat.seam.proposals()) {
                seen = chat.seam.proposals()
                awaitIdle(vm)
                awaitIdle(vm)
            }

            assertEquals("the reconnect's KEX was not paid once", 1, chat.seam.submittedOf("KEX"))
            assertEquals("the reconnect's ZBOOT was not paid once", 1, chat.seam.submittedOf("ZBOOT"))
            assertFalse("the reconnect left its ZBOOT allowance", chat.owed.zbootQuoted[PEER] == true)
            assertFalse("the reconnect left its KEX owed", PEER in chat.owed.kexOwed)
        }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** A confirmed Reset whose KEX and, after the contact's key reply, ZBOOT are both paid: 2 on-chain, nothing left. */
    private suspend fun confirmResetAndPayBoth(
        chat: ResetOwedChat,
        vm: ChatViewModel,
        contact: E2EKeyPair,
    ) {
        vm.resetSecureSession(PEER)
        awaitIdle(vm)
        chat.reset.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitIdle(vm)
        assertEquals("fixture sanity: the Reset's KEX went", 1, chat.seam.submittedOf("KEX"))
        chat.kexAckFromContact(vm, contact, txId = "synthetic-zc536-kexack-01")
        awaitIdle(vm)
        assertEquals("fixture sanity: the Reset's ZBOOT went", 1, chat.seam.submittedOf("ZBOOT"))
        assertFalse("fixture sanity: the paid ZBOOT used up the allowance", chat.zbootQuoted[PEER] == true)
    }

    /** The contact's own first-contact KEX carrying [keys]'s public key. */
    private fun kexFromContact(keys: E2EKeyPair): String =
        ZMSGProtocol.createV4KEXMessage(
            ResetFixture.CONV_ID,
            PEER,
            E2EEncryption.createKEXPayload(PEER, keys.publicKey, keys.privateKey),
        )
}
