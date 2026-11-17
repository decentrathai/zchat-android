package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * ZC-739 (HIGH; T7B-HANDSHAKE-SPEC rev 2, sections 1.2 and 2.1) — while a contact's "Security key changed" warning is
 * up (`isE2EKeyChanged`), no sender that needs no tap pays anything to that contact or sends any of the user's
 * messages: nothing is withdrawn or lost (the owed marks, the allowance and the rows stay), and the decision starts the
 * held work. The free signed rotation announce is the one exception, on purpose (holding it loses incoming messages).
 *
 * Each case runs on a funded wallet with the standing handshake consent given (nothing else stops a payment) and, where
 * a relay is in play, a recording publisher that answers 0 acks (so every ZBOOT would fall back on-chain):
 * - (a) a Tunnel chat whose delivered-identity marker is empty, one block tick;
 * - (b) the chat-open calls in AndroidChat's order, the key exchange incomplete;
 * - (c) an owed quoted ZBOOT, one block tick: both marks still on disk afterwards;
 * - (d) the old key received and never answered (responder): no answer, no budget unit used;
 * - (e) a key exchange owed with the reconnect reason, a chat open: the mark kept;
 * - (f) the state an interrupted accept leaves (an owed reconnect key exchange and its allowance, the flag still up),
 *   one block tick (modelled in the store: the accept's own kill points are KillAtEachStepOfTheAccept…'s);
 * - (g) the Call tap's forced bootstrap: nothing paid, and the tap says why;
 * - (h) a Shielded message queued for the next block when the warning rose: 0 proposals, the row FAILED with the
 *   key-change text, no allowance written for it;
 * - (i) a refused key remembered (the store keeps the flag on a plain clear), the contact's verified ZBOOT arrives: the
 *   flag stays, 0 ZBOOT proposals and 0 publishes follow;
 * - (j) Rotate key with one flagged Tunnel contact and 0 acks: 0 proposals for it, and its announce WAS published.
 *
 * Controls: without the flag the same tick, chat open and queued message pay as today (green before and after). Red
 * today, green after the fix: after the flag is taken down the next tick sends the held ZBOOT once (today the tick
 * paid it while the warning was up); after the Shielded × the held owed key exchange is paid once in the SAME view
 * model (today it was paid while the warning was up); a second contact with no flag gets its tick in the same block
 * (today the flagged contact used up the block's one pick).
 *
 * Every address, key and transaction id is synthetic.
 */
class NoAutomaticSetupPaymentWhileANewKeyWaitsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        LedgerTests.tearDown(sendScopes)
    }

    private fun funded() =
        Zc101FakeAccountDataSource(
            runBlocking {
                syntheticZashiAccount().let {
                    it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))))
                }
            },
        )

    private fun world(
        mode: ConversationMode,
        exchangeComplete: Boolean,
        flagged: Boolean = true,
    ): KeyWaitWorld =
        KeyWaitWorld(LedgerWorld(mode = mode, exchangeComplete = exchangeComplete, consent = true, accountSource = funded()))
            .also { if (flagged) it.flagged += it.w.peer }

    private fun proposals(k: KeyWaitWorld): Int = k.proposals()

    // ---- (a) the block tick of a Tunnel chat --------------------------------------------------------------------

    @Test
    fun `(a) a block tick pays no ZBOOT to a Tunnel contact whose new key waits`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = true)
            val vm = k.openVm()

            k.w.blockTick(vm, LedgerWorld.H + 1L)

            assertEquals("the block tick paid our ZBOOT to a contact whose new key waits for the user", 0, proposals(k))
            assertTrue("the warning went down by itself", k.w.peer in k.flagged)
        }

    @Test
    fun `control (a) - without the warning the same tick pays the Tunnel ZBOOT`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = true, flagged = false)
            val vm = k.openVm()

            k.w.blockTick(vm, LedgerWorld.H + 1L)

            assertEquals("fixture: the Tunnel ZBOOT was not paid by the tick", 1, proposals(k))
        }

    // ---- (b) the chat open, (g) the Call tap ------------------------------------------------------------------------

    private suspend fun chatOpenInAndroidChatOrder(
        k: KeyWaitWorld,
        vm: ChatViewModel,
    ) {
        vm.askAgainForHandshakeConsent()
        vm.retryKexAckIfResponder(k.w.peer)
        vm.retryOwedKeyExchange(k.w.peer)
        vm.repairUnconfirmedRelayIdentity(k.w.peer)
        vm.ensureNostrBootstrapSent(k.w.peer)
        awaitIdle(vm)
    }

    @Test
    fun `(b) the chat open pays no key exchange and no ZBOOT while a new key waits`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = k.openVm()

            chatOpenInAndroidChatOrder(k, vm)

            assertEquals("the chat open paid a set-up message to a contact whose new key waits", 0, proposals(k))
        }

    @Test
    fun `control (b) - without the warning the chat open pays the Tunnel key exchange`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = false, flagged = false)
            val vm = k.openVm()

            chatOpenInAndroidChatOrder(k, vm)

            assertEquals("fixture: the chat open did not pay the Tunnel key exchange", 1, proposals(k))
        }

    @Test
    fun `(g) the Call tap pays nothing while a new key waits and says why`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = k.openVm()

            // AndroidChat's Call callback: the forced bootstrap.
            vm.ensureNostrBootstrapSent(k.w.peer, force = true)
            awaitIdle(vm)

            assertEquals("the Call tap paid a key exchange to a contact whose new key waits", 0, proposals(k))
            assertEquals(
                "the Call tap did not say why nothing was sent",
                ChatSendCopy.KEY_CHANGED_VERIFY,
                (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message,
            )
        }

    // ---- (c) an owed quoted ZBOOT, (f) an interrupted accept ----------------------------------------------------------

    @Test
    fun `(c) an owed quoted ZBOOT is not paid by a block tick while a new key waits and both marks stay`() =
        runBlocking {
            val k = world(ConversationMode.VAULT, exchangeComplete = true)
            val vm = k.openVm()
            k.w.prefs.zbootQuoted += k.w.peer
            k.w.prefs.zbootOwed += k.w.peer

            k.w.blockTick(vm, LedgerWorld.H + 1L)

            assertEquals("the tick paid a quoted ZBOOT to a contact whose new key waits", 0, proposals(k))
            assertTrue("the allowance was withdrawn while it was held", k.w.peer in k.w.prefs.zbootQuoted)
            assertTrue("the owed mark was dropped while it was held", k.w.peer in k.w.prefs.zbootOwed)
        }

    @Test
    fun `(f) the owed key exchange an interrupted accept leaves is not paid by the next block`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = k.openVm()
            k.w.prefs.kexOwed += k.w.peer
            k.w.prefs.kexOwedReason[k.w.peer] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
            k.w.prefs.zbootQuoted += k.w.peer

            k.w.blockTick(vm, LedgerWorld.H + 1L)
            k.w.blockTick(vm, LedgerWorld.H + 2L)

            assertEquals("a block paid the key exchange of an accept that did not finish", 0, proposals(k))
            assertTrue("the owed mark was dropped", k.w.peer in k.w.prefs.kexOwed)
            assertTrue("the accept's allowance was dropped", k.w.peer in k.w.prefs.zbootQuoted)
        }

    // ---- (d) the old key received and never answered ----------------------------------------------------------------

    @Test
    fun `(d) the answer for the old key is not paid at a chat open while a new key waits and no budget is used`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = false)
            k.w.asResponder()
            val vm = k.openVm()

            vm.retryKexAckIfResponder(k.w.peer)
            awaitIdle(vm)

            assertEquals("the chat open paid an answer for the old key while the new one waits", 0, proposals(k))
            assertEquals("a budget unit was used for an answer that waits", 0, k.budgetUsed())
        }

    // ---- (e) a reconnect's owed key exchange ---------------------------------------------------------------------------

    @Test
    fun `(e) a key exchange owed with the reconnect reason is not paid while a new key waits and stays owed`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = k.openVm()
            k.w.prefs.kexOwed += k.w.peer
            k.w.prefs.kexOwedReason[k.w.peer] = ZchatPreferences.KEX_OWED_REASON_RECONNECT

            vm.retryOwedKeyExchange(k.w.peer)
            awaitIdle(vm)

            assertEquals("an owed key exchange was paid while a new key waits", 0, proposals(k))
            assertTrue("the owed mark was dropped while it was held", k.w.peer in k.w.prefs.kexOwed)
        }

    // ---- (h) a queued Shielded message ------------------------------------------------------------------------------

    private fun queuedRow(peer: String): ZchatPreferences.PendingMessageData =
        ZchatPreferences.PendingMessageData(
            id = "pending_zc739_queued",
            text = "synthetic queued message",
            timestampMillis = System.currentTimeMillis(),
            peerAddress = peer,
            sendAmountZatoshi = 1_000L,
        )

    private fun doSendMessage(
        vm: ChatViewModel,
        peer: String,
        text: String,
        existingPendingId: String?,
    ) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "doSendMessage",
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaObjectType,
                ).apply { isAccessible = true }
                .invoke(vm, peer, text, 1_000L, existingPendingId, 0, null)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** (h) on the real Shielded send path ([PaidChatFixture]: keys exchanged, the real use case over its seam). */
    private class QueuedSend(
        val fixture: PaidChatFixture,
        val row: ZchatPreferences.PendingMessageData?,
        val allowanceWrites: List<String>,
    ) {
        val proposals: Int get() = fixture.seam.proposalRequests.get()
    }

    private suspend fun queuedShieldedSend(flagged: Boolean): QueuedSend {
        val f = PaidChatFixture("usyntheticzc739queuedrow0001", ConversationMode.VAULT)
        f.keyChanged = flagged
        val row = queuedRow(f.peer)
        f.proxy.commitPendingMessage(row)
        val vm = f.viewModel()
        val allowanceWrites = java.util.Collections.synchronizedList(mutableListOf<String>())
        val recording =
            java.lang.reflect.Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                if (method.name == "setZbootQuoted" && args?.getOrNull(1) == true) allowanceWrites += method.name
                if (method.name == "restoreQuotedZbootAsOwed") allowanceWrites += method.name
                try {
                    method.invoke(f.proxy, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", recording)

        doSendMessage(vm, f.peer, row.text, row.id)
        awaitIdle(vm)
        return QueuedSend(f, f.proxy.getPendingMessages().firstOrNull { it.id == row.id }, allowanceWrites.toList())
    }

    @Test
    fun `(h) a queued Shielded message is not sent when the warning rose before its block and its row stays FAILED`() =
        runBlocking {
            val sent = queuedShieldedSend(flagged = true)

            assertEquals("a queued Shielded message was paid while a new key waits", 0, sent.proposals)
            assertEquals("the queued row is not kept FAILED", "FAILED", sent.row?.status)
            assertEquals("the row does not say why it was not sent", ChatSendCopy.KEY_CHANGED_VERIFY, sent.row?.failureReason)
            assertEquals("an allowance was written for a message that was not sent", emptyList<String>(), sent.allowanceWrites)
        }

    @Test
    fun `control (h) - without the warning the queued Shielded message is paid`() =
        runBlocking {
            val built = queuedShieldedSend(flagged = false).proposals

            assertTrue("fixture: the queued Shielded message was not paid ($built)", built >= 1)
        }

    // ---- (i) the contact's verified ZBOOT while a refused key is remembered --------------------------------------------

    @Test
    fun `(i) the contact's verified ZBOOT with a remembered refused key leaves the flag and pays and publishes nothing`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = true)
            k.recordKeepsFlag = true
            val contact = E2EEncryption.generateKeyPair()
            k.w.base.peerKeys[k.w.peer] = contact.publicKey
            k.registerPublisher()
            val vm = k.openVm()
            val relayKey = KeyWaitWorld.relayKey(739)

            val taken = k.routeIncomingBoot(vm, k.w.peer, k.contactZboot(k.w.peer, contact, relayKey))
            awaitIdle(vm)

            assertTrue("fixture: the contact's verified ZBOOT was not taken", taken)
            assertTrue("the warning went down although a refused key is remembered", k.w.peer in k.flagged)
            assertEquals("our reply ZBOOT was paid while the new key waits", 0, proposals(k))
            assertEquals("our reply ZBOOT was published while the new key waits", emptyList<String>(), k.publishesTo(relayKey))
        }

    // ---- (j) Rotate key ---------------------------------------------------------------------------------------------------

    @Test
    fun `(j) Rotate key pays no fallback ZBOOT to a flagged Tunnel contact and still publishes its announce`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = true)
            val relayKey = KeyWaitWorld.relayKey(7391)
            k.w.prefs.peerNostr[k.w.peer] = relayKey
            k.registerPublisher()
            val vm = k.openVm()

            vm.rotateNostrIdentity()
            awaitIdle(vm)

            assertEquals("the rotation's on-chain fallback was paid to a contact whose new key waits", 0, proposals(k))
            assertEquals("the free signed rotation announce was held (it must go)", 1, k.publishesTo(relayKey).size)
        }

    // ---- controls after the decision -------------------------------------------------------------------------------------

    @Test
    fun `control - once the warning is taken down the next tick sends the held ZBOOT once`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = true)
            val vm = k.openVm()
            k.w.blockTick(vm, LedgerWorld.H + 1L)
            val whileHeld = proposals(k)

            k.flagged -= k.w.peer
            k.w.blockTick(vm, LedgerWorld.H + 2L)
            k.w.blockTick(vm, LedgerWorld.H + 3L)

            assertEquals("the held ZBOOT was not paid exactly once after the decision", whileHeld + 1, proposals(k))
        }

    @Test
    fun `control - after the Shielded decision the held owed key exchange is paid once in the same view model`() =
        runBlocking {
            val k = world(ConversationMode.VAULT, exchangeComplete = false)
            val vm = k.openVm()
            k.w.prefs.kexOwed += k.w.peer
            k.w.prefs.kexOwedReason[k.w.peer] = ZchatPreferences.KEX_OWED_REASON_RESET

            vm.retryOwedKeyExchange(k.w.peer)
            awaitIdle(vm)
            assertEquals("the owed key exchange was paid while the new key waited", 0, proposals(k))

            vm.dismissE2EKeyChanged(k.w.peer)
            awaitIdle(vm)

            assertFalse("fixture: the Shielded × did not take the warning down", k.w.peer in k.flagged)
            assertEquals("the held owed key exchange was not paid exactly once after the decision", 1, proposals(k))
            assertFalse("the owed mark stayed after its key exchange was paid", k.w.peer in k.w.prefs.kexOwed)
        }

    @Test
    fun `control - a second contact with no warning gets its ZBOOT in the same block`() =
        runBlocking {
            val k = world(ConversationMode.TUNNEL, exchangeComplete = true)
            val other = "usyntheticzc739othercontact01"
            k.w.base.session(other, complete = true, mode = ConversationMode.TUNNEL)
            val vm = k.openVm()
            // The tick picks candidates[height mod 2] of the sorted list: choose the height that lands on the flagged one.
            val sorted = listOf(k.w.peer, other).sorted()
            val height = 2L * LedgerWorld.H + sorted.indexOf(k.w.peer)
            assertEquals("fixture: the height does not pick the flagged contact", k.w.peer, sorted[(height % 2L).toInt()])

            k.w.blockTick(vm, height)

            assertTrue("the flagged contact's ZBOOT was sent", k.w.base.sentNostrBoot[k.w.peer] == null)
            assertTrue("the contact with no warning lost its block to the flagged one", k.w.base.sentNostrBoot[other] != null)
        }
}
