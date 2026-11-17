package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZC-668 door 3: exception and refused durable Join write are undecided, rule refusals remain decisions. */
internal class KeptReinviteSurvivesAnOfferThatCouldNotBeDecidedTest : Zc668Fixture() {
    @Test
    fun `inside-offer store exception keeps copy unused and later load offers it`() =
        runBlocking {
            inactive()
            keep()
            val first = screen()
            store.throwInsideOffer = true
            offerKept(first.vm)
            assertEquals("fault must fire inside offerGroupReopen exactly once", 1, store.offerReadFaults)
            assertFalse("exception must not record a rule refusal", I2 in diskState().usedInviteIds)
            assertEquals("exception must preserve durable copy", I2, diskState().pendingReinvite?.inviteId)
            first.close()
            reopenStore()
            val fresh = screen(ready = false)
            fresh.startLoad()
            fresh.finishLoad()
            assertOffered(I2)
        }

    @Test
    fun `false Join commit keeps copy unused and later load offers it`() =
        runBlocking {
            inactive()
            keep()
            val first = screen()
            store.joins.refuseNextCommit = true
            offerKept(first.vm)
            assertEquals("Join commit refusal must actually fire", 1, store.joins.refusals)
            assertTrue(
                "fault seam mutates memory before refusing disk",
                store.joins.failedMemory
                    ?.keys
                    ?.any { it.startsWith("invitejoin:") } == true,
            )
            assertTrue(
                "fault seam leaves Join absent from disk",
                store.joins.failedDisk
                    ?.keys
                    ?.none { it.startsWith("invitejoin:") } == true,
            )
            assertNull("refused Join record must not be on disk", diskJoin())
            assertFalse("unwritten Join must leave invite unused", I2 in diskState().usedInviteIds)
            assertEquals("unwritten Join must preserve durable copy", I2, diskState().pendingReinvite?.inviteId)
            first.close()
            reopenStore()
            val fresh = screen(ready = false)
            fresh.startLoad()
            fresh.finishLoad()
            assertOffered(I2)
        }

    @Test
    fun `control old source time is a rule refusal and clears used copy`() = runBlocking { refuse(time = REMOVED) }

    @Test
    fun `control below-floor epoch is a rule refusal and clears used copy`() = runBlocking { refuse(epoch = 0) }

    @Test
    fun `control another authenticated member is a rule refusal and clears used copy`() =
        runBlocking {
            phone.link(AnnounceDriver.X)
            val memo = AnnounceDriver.gi(AnnounceDriver.X, phone.sessionWith(AnnounceDriver.X), AnnounceDriver.newGroupKey(), 2)
            refuse(memo = memo, sender = AnnounceDriver.X)
        }

    @Test
    fun `control valid kept creator invite durably offers Join without payment`() =
        runBlocking {
            inactive()
            keep()
            offerKept(screen().vm)
            assertOffered(I2)
        }

    private suspend fun refuse(epoch: Int = 2, time: Long = INVITED, memo: String = invite(epoch), sender: String = ADMIN) {
        inactive()
        keep(memo = memo, time = time, sender = sender)
        offerKept(screen().vm)
        assertNull("rule refusal must not offer Join", diskJoin())
        assertNull("rule refusal clears kept copy", diskState().pendingReinvite)
        assertTrue("rule refusal records used id", I2 in diskState().usedInviteIds)
    }
}
