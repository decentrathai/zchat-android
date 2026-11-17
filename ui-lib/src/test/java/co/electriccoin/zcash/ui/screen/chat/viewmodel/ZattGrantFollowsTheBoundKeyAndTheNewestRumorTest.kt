package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.Nip17
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ZC-1501 (OT213n B1d; FIX_PLAN REVISION 6; cross-family review of B1c, review-B1c/gpt.json + grok.json): the zatt1
 * grant (a contact reads a free attachment, so its file key may go to it in a ZATT) must not outlive what granted it.
 *
 * At e626e879d [ChatViewModel.recordPeerZattCap] stores `<1|0>:<atSec>` on the contact's address only: no relay key, the
 * rumor's created_at clamped to the local now, and a rumor replaces the record only when strictly newer. So:
 * - the bound relay key changes and the new key, which never advertised zatt1, still gets the ZATT (with the file key);
 * - a far-future rumor (created_at > now + 10 min) grants;
 * - on an equal created_at the untagged (older build) rumor loses, so a downgrade in the same second is ignored;
 * - a future-dated zatt1 rumor replayed after a downgrade was recorded is clamped to a fresh "now" and grants again.
 * A 2.12.4 phone shows a ZATT as text: file key next to the url, on screen, in the notification, in its store.
 *
 * Wanted (the question the send asks, [ChatViewModel.zattRecipientKey]): null in each case above. Guard: one zatt1
 * rumor from the bound key, written a minute ago, opens the free route to that key.
 * Real ChatViewModel over [RealModeStorePhone] (the real preferences store keeps the record and the bound key); the
 * rumor goes in through the reader's own recorder, as observeNostrInbound calls it. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZattGrantFollowsTheBoundKeyAndTheNewestRumorTest {
    private val sendScopes = SendHarnessScopes()
    private val peer = SyntheticUnifiedAddress.mainnet(15_010)
    private val boundKey = relayKey(15_011)
    private val otherKey = relayKey(15_012)
    private var event = 0

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun nowSec(): Long = Instant.now().epochSecond

    private suspend fun openChat(): Pair<RealModeStorePhone, ChatViewModel> {
        val phone = RealModeStorePhone(peer, ConversationMode.OPEN, relayKey = boundKey)
        return phone to phone.viewModel()
    }

    /** A relay rumor from the contact written at [createdAtSec], with or without zatt1 ([rumorId] stays the same on a replay). */
    private fun rumor(
        createdAtSec: Long,
        zatt1: Boolean,
        rumorId: String,
    ): NostrChatBridge.InboundChat =
        NostrChatBridge.InboundChat(
            peerAddress = peer,
            plaintext = "synthetic-b1d-text",
            createdAtSec = createdAtSec,
            eventId = "synthetic-b1d-event-${++event}",
            rumorId = rumorId,
            rumorTags = if (zatt1) listOf(Nip17.ZATT_CAPABILITY_TAG) else emptyList(),
        )

    private fun record(
        vm: ChatViewModel,
        chat: NostrChatBridge.InboundChat,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods.singleOrNull {
                it.name == "recordPeerZattCap" && it.parameterTypes.firstOrNull() == NostrChatBridge.InboundChat::class.java
            } ?: throw AssertionError("ChatViewModel.recordPeerZattCap(InboundChat) is gone (FIX_PLAN REVISION 6 names it)")
        method.isAccessible = true
        val args = arrayOfNulls<Any?>(method.parameterCount).also { it[0] = chat }
        try {
            method.invoke(vm, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun recipient(vm: ChatViewModel): String? {
        val method = ChatViewModel::class.java.getDeclaredMethod("zattRecipientKey", String::class.java).apply { isAccessible = true }
        return try {
            method.invoke(vm, peer) as String?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // ------------------------------------------------------------------ guard (passes before and after)

    @Test
    fun `guard - a zatt1 rumor from the bound key written a minute ago opens the free route to that key`() =
        runBlocking {
            val (_, vm) = openChat()
            record(vm, rumor(nowSec() - 60, zatt1 = true, rumorId = "r-guard"))
            assertEquals(boundKey, recipient(vm), "a fresh zatt1 rumor from the bound key did not open the free route")
        }

    // ------------------------------------------------------------------ RED at e626e879d

    @Test
    fun `a grant made under one bound relay key does not carry over to the next key`() =
        runBlocking {
            val (phone, vm) = openChat()
            record(vm, rumor(nowSec() - 60, zatt1 = true, rumorId = "r-key"))
            assertEquals(boundKey, recipient(vm), "setup: the zatt1 rumor did not grant")

            phone.real.setPeerNostrPubkey(peer, otherKey)

            assertNull(
                recipient(vm),
                "the zatt1 grant of the old relay key let a ZATT (file key inside) go to the new key, which never advertised zatt1",
            )
        }

    @Test
    fun `a zatt1 rumor dated more than ten minutes ahead does not grant`() =
        runBlocking {
            val (_, vm) = openChat()
            record(vm, rumor(nowSec() + 3_600, zatt1 = true, rumorId = "r-future"))
            assertNull(recipient(vm), "a zatt1 rumor dated an hour ahead granted the free route (its created_at was clamped to now)")
        }

    @Test
    fun `on an equal created_at the rumor without zatt1 wins`() =
        runBlocking {
            val (_, vm) = openChat()
            val at = nowSec() - 60
            record(vm, rumor(at, zatt1 = true, rumorId = "r-tie-tagged"))
            assertEquals(boundKey, recipient(vm), "setup: the zatt1 rumor did not grant")

            record(vm, rumor(at, zatt1 = false, rumorId = "r-tie-untagged"))

            assertNull(recipient(vm), "an untagged rumor written in the same second as the grant did not take the free route away")
        }

    @Test
    fun `a zatt1 rumor replayed after a downgrade was recorded never grants again`() =
        runBlocking {
            val (_, vm) = openChat()
            val start = nowSec()
            val tagged = rumor(start + 60, zatt1 = true, rumorId = "r-replayed")
            record(vm, tagged)
            // A second apart, so the clamped clock of e626e879d also sees the downgrade as newer (isolates the replay).
            Thread.sleep(1_100)
            record(vm, rumor(start + 120, zatt1 = false, rumorId = "r-downgrade"))
            assertNull(recipient(vm), "setup: the newer rumor without zatt1 did not take the free route away")
            Thread.sleep(1_100)

            record(vm, tagged.copy(eventId = "synthetic-b1d-event-replay"))

            assertNull(recipient(vm), "the old zatt1 rumor, replayed after the downgrade, turned the free route back on")
        }
}
