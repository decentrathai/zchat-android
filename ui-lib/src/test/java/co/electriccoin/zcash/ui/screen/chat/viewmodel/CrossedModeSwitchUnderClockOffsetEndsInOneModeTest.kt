package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
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

/**
 * ZC-1505 (B1a cross-family review gpt-1 / grok-7; VERIFY_B1a.md items 1-2; FIX_PLAN REVISION 7).
 *
 * W-122 / M3: of two crossed mode switches, both phones end in ONE mode. At e626e879d a phone answering an older crossed
 * switch (ModeAdoption.Decision.SendOursAgain) re-sends its mode with the SAME since it sent before; the contact's phone
 * already recorded that since (it adopted that switch), so its monotonic guard (advancePeerModeChangeSince: since <=
 * stored) drops the copy and the phones stay split for good. A steady clock offset is enough; no clock step is needed.
 *
 * The walk-through (B's clock 2 min ahead of A's):
 *  1. B switches the chat to Shielded at B-time T+2min; A adopts it (A records B's since T+2min).
 *  2. A then switches to Open at A-time T (later in real time, earlier by the clocks); B receives ZMODE Open/T.
 *  3. B's own switch (T+2min) is newer by the clocks: B keeps Shielded and sends Shielded again.
 *  4. A must follow that copy (or B must follow A): any order works as long as both phones end in the same mode.
 *
 * Two phones with real mode stores ([RealModeStorePhone]); each ZMODE one phone publishes is handed to the other's
 * handler (the authenticated-caller boundary) until neither publishes a new one. B's own switch is written as
 * changeConversationMode writes it (mode, pin, own since), because the fixture cannot move B's clock. A mode switch never
 * spends: zero proposals on both phones. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossedModeSwitchUnderClockOffsetEndsInOneModeTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1505crossedmode01"
        val PEER_RELAY_KEY: String = "c1505".padEnd(64, 'e')
        const val B_CLOCK_AHEAD_MS = 2L * 60 * 1000
    }

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

    private class Side(
        val phone: RealModeStorePhone,
        val vm: ChatViewModel,
    ) {
        var read = 0

        /** The ZMODEs this phone published since the last call, as (mode, since). */
        fun newZmodes(): List<Pair<ConversationMode, Long>> {
            val all = phone.published.map { it.first }
            val fresh = all.drop(read)
            read = all.size
            return fresh.mapNotNull { ZMSGProtocol.parseModeChange(it) }.map { it.mode to it.sinceMillis }
        }
    }

    private suspend fun side(): Side {
        val phone =
            RealModeStorePhone(PEER, ConversationMode.TUNNEL, consent = true, exchangeComplete = true, relayKey = PEER_RELAY_KEY)
        phone.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
        return Side(phone, phone.viewModel())
    }

    /** Hand [zmode] to [to]'s handler, with [to]'s relays up (so whatever it sends back is recorded on [to]). */
    private suspend fun deliver(
        to: Side,
        zmode: Pair<ConversationMode, Long>,
    ) {
        to.phone.relaysUp()
        invoke(to.vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(zmode.first, zmode.second, PEER))
        awaitIdle(to.vm)
    }

    @Test
    fun `crossed switches under a steady clock offset end in one mode on both phones, and spend nothing`() =
        runBlocking {
            val a = side()
            val b = side()

            // 1. B (clock ahead) switches to Shielded; A adopts it.
            val sinceB = System.currentTimeMillis() + B_CLOCK_AHEAD_MS
            b.phone.real.setConversationMode(PEER, ConversationMode.VAULT)
            b.phone.real.setConversationModeExplicit(PEER)
            b.phone.real.setOwnModeChangeSince(PEER, sinceB)
            deliver(a, ConversationMode.VAULT to sinceB)
            assertEquals("fixture: A adopts B's switch to Shielded", ConversationMode.VAULT, a.phone.mode())

            // 2. A switches to Open (A's clock: earlier than B's since).
            a.phone.relaysUp()
            a.vm.changeConversationMode(PEER, ConversationMode.OPEN)
            awaitIdle(a.vm)
            val fromA = a.newZmodes()
            assertEquals("fixture: A's switch went over the relays", listOf(ConversationMode.OPEN), fromA.map { it.first })
            assertTrue("fixture: A's since is earlier than B's by the clocks", fromA.single().second < sinceB)

            // 3-4. The relays carry every ZMODE across until neither phone sends a new one.
            var pending = fromA
            var toB = true
            var rounds = 0
            while (pending.isNotEmpty() && rounds < 8) {
                val target = if (toB) b else a
                pending.forEach { deliver(target, it) }
                pending = target.newZmodes()
                toB = !toB
                rounds++
            }

            assertEquals(
                "crossed switches under a clock offset left the phones in different modes for good (W-122 / M3): " +
                    "A=${a.phone.mode()} B=${b.phone.mode()} after $rounds relay rounds",
                b.phone.mode(),
                a.phone.mode(),
            )
            val seamA = a.phone.seam
            val seamB = b.phone.seam
            val spentOnA = seamA.proposalRequests.get()
            val spentOnB = seamB.proposalRequests.get()
            assertEquals("a mode switch spent on A", 0, spentOnA)
            assertEquals("a mode switch spent on B", 0, spentOnB)
        }

    /** Control: with no clock offset (B's switch truly older), the same exchange already converges at e626e879d. */
    @Test
    fun `control - crossed switches with B's switch older by the clocks end in one mode`() =
        runBlocking {
            val a = side()
            val b = side()
            val sinceB = System.currentTimeMillis() - 60_000L
            b.phone.real.setConversationMode(PEER, ConversationMode.VAULT)
            b.phone.real.setConversationModeExplicit(PEER)
            b.phone.real.setOwnModeChangeSince(PEER, sinceB)
            deliver(a, ConversationMode.VAULT to sinceB)

            a.phone.relaysUp()
            a.vm.changeConversationMode(PEER, ConversationMode.OPEN)
            awaitIdle(a.vm)
            var pending = a.newZmodes()
            var toB = true
            var rounds = 0
            while (pending.isNotEmpty() && rounds < 8) {
                val target = if (toB) b else a
                pending.forEach { deliver(target, it) }
                pending = target.newZmodes()
                toB = !toB
                rounds++
            }

            assertEquals("A=${a.phone.mode()} B=${b.phone.mode()}", ConversationMode.OPEN, b.phone.mode())
            assertEquals(ConversationMode.OPEN, a.phone.mode())
        }
}
