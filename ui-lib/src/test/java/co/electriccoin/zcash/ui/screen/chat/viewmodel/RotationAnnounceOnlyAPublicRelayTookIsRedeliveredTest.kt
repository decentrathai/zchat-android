package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.RelayCopyMarksApi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.PEER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.PEER_RELAY_KEY
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.ourRelayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.repairAtChatOpen
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.tick
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.zboot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-420 (high; fix-all 2.13.0, train T6b; ZC-420-SPEC §2.2 S2, §3.4 items 2 and 4, §4.2): a relay-key change announced to
 * a contact with keys, that only a public relay took, is remembered and announced again over the relays, for free.
 *
 * announceRotationOverNostr counts any readable relay as delivered and writes the delivered-identity marker. A Tunnel
 * contact on 2.12.4 never reads the copy primal took, keeps writing to our old key and drops what we send from the new
 * one; the marker stops every later announce. Wanted, on the E2E-signed branch only (a pure Open contact keeps today's
 * chat-open re-announce): a mark of the new identity written before the publish; after a restart the tick publishes
 * one version-3 ZBOOT of the new key at the rotated epoch; a cut-off announce (a mark of the new key beside a marker that
 * still names the old one) is run again; a repair of the previous identity that answers late never removes or replaces
 * the new identity's mark. Every key is made per run; every address is synthetic. The Open control passes before and
 * after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotationAnnounceOnlyAPublicRelayTookIsRedeliveredTest {
    private val sendScopes = SendHarnessScopes()
    private val net = IdentityRelayNet()
    private val old = ourRelayKey(0)
    private val rotated = ourRelayKey(1)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        net.install()
    }

    @After
    fun tearDown() {
        net.uninstall()
        RelayCopyMarksApi.unregister()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun RelayIdentityChat.marksOrFail() {
        assertTrue("this build keeps no relay-copy mark (RelayCopyMarks, ZC-420): nothing can repair the announce", registerMarks())
    }

    private fun assertRotatedZboot(published: IdentityRelayNet.Published) {
        assertEquals(PEER_RELAY_KEY, published.to)
        val boot = zboot(published.memo)
        assertEquals("not a version-3 (E2E-signed) announce", 3, boot.version)
        assertEquals("the announce carries another relay key", rotated, boot.senderNostrPubkeyHex)
        assertEquals("the announce's epoch is not the rotation index", 1L, boot.epoch)
    }

    @Test
    fun `a Tunnel contact's announce only a public relay took is marked with the new key and announced again after a restart`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.prefs.sentNostrBoot[PEER] = old
            net.answer = Triple(0, "rumor-rot", 1)
            val vm = chat.viewModel()

            vm.rotateNostrIdentity()
            awaitIdle(vm)

            assertEquals("fixture: one announce", 1, net.published.size)
            assertRotatedZboot(net.published[0])
            assertEquals("today's result: the new key is marked delivered", rotated, chat.marker())
            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals("rumor-rot", chat.marks.rumorId(chat.slot()))
            assertEquals("the mark names the new key", rotated, chat.marks.extra(chat.slot()))

            net.resender = null
            net.answer = Triple(1, "rumor-again", 1)
            val restarted = chat.viewModel()
            tick(restarted, 3_000_001L)
            awaitIdle(restarted)

            assertEquals("the announce was not delivered again", 2, net.published.size)
            assertRotatedZboot(net.published[1])
            assertNull(chat.marks.map[chat.slot()])
            assertEquals(0, chat.seam.proposalRequests.get())
        }

    @Test
    fun `control - a pure Open contact (no E2E key) gets today's v4 announce and no mark`() =
        runBlocking {
            val chat = RelayIdentityChat(mode = ConversationMode.OPEN)
            chat.prefs.ourKeys.remove(PEER)
            chat.prefs.peerKeys.remove(PEER)
            chat.prefs.complete.remove(PEER)
            chat.prefs.sentNostrBoot[PEER] = old
            val marked = chat.registerMarks()
            net.answer = Triple(0, "rumor-open", 1)
            val vm = chat.viewModel()

            vm.rotateNostrIdentity()
            awaitIdle(vm)

            assertEquals(1, net.published.size)
            assertTrue("fixture: the Open announce is NOSTR-key-signed (v4)", net.published[0].memo.startsWith("ZBOOT|v4|"))
            assertEquals(rotated, chat.marker())
            if (marked) assertTrue("a pure Open contact's announce was marked: ${chat.marks.map.keys}", chat.marks.map.isEmpty())
        }

    @Test
    fun `the mark of the new key exists before the announce's publish answers`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.prefs.sentNostrBoot[PEER] = old
            val hold = CompletableDeferred<Unit>()
            net.hold = hold
            net.answer = Triple(0, "rumor-held", 1)
            val vm = chat.viewModel()

            vm.rotateNostrIdentity()
            HandshakeHarness.awaitTrue { net.published.size == 1 }

            assertEquals("no mark before the publish answered", "", chat.marks.rumorId(chat.slot()))
            assertEquals(rotated, chat.marks.extra(chat.slot()))
            assertEquals("fixture: the marker still names the old key", old, chat.marker())

            hold.complete(Unit)
            awaitIdle(vm)
            assertEquals("rumor-held", chat.marks.rumorId(chat.slot()))
            assertEquals(rotated, chat.marker())
        }

    @Test
    fun `a rotation while a repair of the previous identity waits keeps the new key's mark - a late shared answer`() =
        rotationDuringRepair(lateAnswer = Triple(1, "rumor-late", 1))

    @Test
    fun `a rotation while a repair of the previous identity waits keeps the new key's mark - a late readable-only answer`() =
        rotationDuringRepair(lateAnswer = Triple(0, "rumor-late", 1))

    private fun rotationDuringRepair(lateAnswer: Triple<Int, String, Int>) =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            chat.prefs.sentNostrBoot[PEER] = old
            chat.marks.map[chat.slot()] = "|${System.currentTimeMillis() - 5 * 60_000L}|$old"
            val vm = chat.viewModel()
            val held = CompletableDeferred<Unit>()
            net.hold = held

            repairAtChatOpen(vm)
            HandshakeHarness.awaitTrue { net.published.size == 1 }
            // The repair's publish waits for its relay answer; the user rotates meanwhile.
            net.hold = null
            net.answer = Triple(0, "rumor-rotation", 1)
            vm.rotateNostrIdentity()
            HandshakeHarness.awaitTrue { net.published.size == 2 }
            assertRotatedZboot(net.published[1])

            net.answer = lateAnswer
            held.complete(Unit)
            awaitIdle(vm)

            val why = "the late answer of the old identity's repair removed or replaced the new key's mark"
            assertEquals(why, rotated, chat.marks.extra(chat.slot()))
            assertEquals("rumor-rotation", chat.marks.rumorId(chat.slot()))
        }

    @Test
    fun `an announce cut off before its answer is run again by the tick after a restart, and the marker becomes the new key`() =
        runBlocking {
            val chat = RelayIdentityChat().apply { marksOrFail() }
            // What a kill during the announce leaves: the index is the new one, the marker the old key, a mark of the new key.
            chat.rotation.set(1)
            chat.prefs.sentNostrBoot[PEER] = old
            chat.marks.map[chat.slot()] = "|${System.currentTimeMillis() - 5 * 60_000L}|$rotated"
            net.answer = Triple(0, "rumor-cut", 1)
            val vm = chat.viewModel()

            tick(vm, 3_000_001L)
            awaitIdle(vm)

            assertEquals("the cut-off announce was not run again", 1, net.published.size)
            assertRotatedZboot(net.published[0])
            assertEquals("a readable ack makes the new key the delivered one", rotated, chat.marker())
            assertEquals("rumor-cut", chat.marks.rumorId(chat.slot()))
            assertEquals(0, chat.seam.proposalRequests.get())
        }
}
