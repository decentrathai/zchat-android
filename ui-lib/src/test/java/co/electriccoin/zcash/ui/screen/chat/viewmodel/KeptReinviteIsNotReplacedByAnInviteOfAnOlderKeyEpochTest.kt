package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** ZC-668 door 5: epoch wins before source time, with creator and same-epoch controls. */
internal class KeptReinviteIsNotReplacedByAnInviteOfAnOlderKeyEpochTest : Zc668Fixture() {
    @Test
    fun `later lower epoch cannot replace chosen creator invite`() = runBlocking { choose(higherFirst = true) }

    @Test
    fun `earlier higher epoch replaces later creator invite`() = runBlocking { choose(higherFirst = false) }

    private suspend fun choose(higherFirst: Boolean) {
        val first = screen()
        val winnerKey = AnnounceDriver.newGroupKey()
        val high = invite(3, winnerKey)
        val low = invite(2)
        if (higherFirst) {
            receive(first.vm, high, I2)
            receive(first.vm, low, I3, INVITED + 5_000)
        } else {
            receive(first.vm, low, I3, INVITED + 5_000)
            receive(first.vm, high, I2)
        }
        assertEquals("epoch precedence must select the higher epoch despite source-time order", I2, diskState().pendingReinvite?.inviteId)
        assertEquals(3, org.json.JSONObject(diskState().pendingReinvite!!.inviteJson).getInt("key_epoch"))
        first.close()
        reopenStore()
        val fresh = screen()
        kick(fresh.vm)
        assertOffered(I2, epoch = 3, key = winnerKey)
    }

    @Test
    fun `control same epoch strictly later time wins in either delivery order`() =
        runBlocking {
            val screen = screen()
            receive(screen.vm, invite(), I3, INVITED + 5_000)
            receive(screen.vm, invite(), I2)
            assertEquals(I3, diskState().pendingReinvite?.inviteId)
            receive(screen.vm, invite(), "synthetic-zc668-tie", INVITED + 5_000)
            assertEquals("equal epoch and time must not replace", I3, diskState().pendingReinvite?.inviteId)
            receive(screen.vm, invite(), "synthetic-zc668-newest", INVITED + 6_000)
            assertEquals("strictly later same epoch must replace", "synthetic-zc668-newest", diskState().pendingReinvite?.inviteId)
            kick(screen.vm)
            assertOffered("synthetic-zc668-newest")
        }

    @Test
    fun `control higher-epoch other member and creator spoof cannot replace creator`() =
        runBlocking {
            val screen = screen()
            phone.link(AnnounceDriver.X)
            receive(screen.vm, invite(), I2)
            val other = AnnounceDriver.gi(AnnounceDriver.X, phone.sessionWith(AnnounceDriver.X), AnnounceDriver.newGroupKey(), 9)
            val spoof = AnnounceDriver.gi(ADMIN, phone.sessionWith(AnnounceDriver.X), AnnounceDriver.newGroupKey(), 10)
            receive(screen.vm, other, I3, INVITED + 5_000, AnnounceDriver.X)
            receive(screen.vm, spoof, "synthetic-zc668-spoof", INVITED + 6_000, AnnounceDriver.X)
            assertEquals("authentication precedes epoch ordering", I2, diskState().pendingReinvite?.inviteId)
            kick(screen.vm)
            assertOffered(I2)
        }
}
