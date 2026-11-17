package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * F8 R5 (ZC-112) — the promise of F8-lite, swept: after the chain proved a setup message never went out, NOTHING is
 * paid for it by itself.
 *
 * 2.13.0 must never pay twice by itself. For the key exchange, the key reply and the relay-identity message, after the
 * proof, every automatic trigger under the standing consent (chat opens, a forced bootstrap, blocks, the owed-KEX
 * tick, the responder's retry, a restart) builds no proposal and writes no marker false. Only the user's quoted Retry
 * passes.
 *
 * Every address, key and transaction id is synthetic.
 */
class NothingIsPaidAutomaticallyAfterAProvenNotSentForAnyChatKindTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun LedgerWorld.noMarkerReArmed(mark: Int) {
        val rearmed =
            prefs.writesSince(mark).filter {
                (it.name == "setOwnBootSent" && it.args[1] == false) ||
                    ((it.name == "setSentKexAckPubkey" || it.name == "setSentNostrBootPubkey") && it.args[1] == null) ||
                    it.name == "restoreQuotedZbootAsOwed" ||
                    (it.name == "setKexOwed" && it.args[1] == true)
            }
        assertEquals("no marker is re-armed and no owed flag is set after a proof", emptyList<String>(), rearmed.map { it.name })
    }

    @Test
    fun `key exchange - every automatic trigger after the proof pays nothing`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexProvenNotSent(vm)
            world.agePaidRecords(minutes = 61)
            val mark = world.prefs.mark()

            world.everyAutomaticKexTrigger(vm, world.wallet.scanned)
            world.restart()
            AutoSpendVm.setHistoryRead(true)
            val again = world.openVm()
            world.everyAutomaticKexTrigger(again, world.wallet.scanned + 10)

            assertEquals("nothing is paid by itself after the proof", 1, world.seam.proposals.get())
            world.noMarkerReArmed(mark)
        }

    @Test
    fun `key reply - every automatic trigger after the proof pays nothing`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexAckProvenNotSent(vm)
            val mark = world.prefs.mark()

            repeat(3) {
                vm.retryKexAckIfResponder(world.peer)
                HandshakeHarness.awaitIdle(vm)
            }
            // A new process: the in-memory "acked" map is gone; even with the durable marker re-armed nothing is paid.
            world.restart()
            world.prefs.sentKexAck.remove(world.peer)
            val again = world.openVm()
            repeat(3) {
                again.retryKexAckIfResponder(world.peer)
                HandshakeHarness.awaitIdle(again)
            }

            assertEquals("nothing is paid by itself after the proof", 1, world.seam.proposals.get())
            world.noMarkerReArmed(mark)
        }

    @Test
    fun `relay identity - every automatic trigger after the proof pays nothing`() =
        runBlocking {
            val world = LedgerWorld(exchangeComplete = true)
            val vm = world.openVm()
            world.zbootProvenNotSent(vm)
            val mark = world.prefs.mark()

            repeat(3) { world.sendZboot(vm) }
            world.chatOpen(vm)
            repeat(3) { world.blockTick(vm, world.wallet.scanned + it) }
            // A new process in which the delivered-identity marker was re-armed (a Reconnect did that before F8).
            world.restart()
            AutoSpendVm.setHistoryRead(true)
            world.base.sentNostrBoot.remove(world.peer)
            world.base.paidRecords.clear()
            val again = world.openVm()
            repeat(3) { world.sendZboot(again) }

            assertEquals("nothing is paid by itself after the proof", 1, world.seam.proposals.get())
            world.noMarkerReArmed(mark)
        }
}
