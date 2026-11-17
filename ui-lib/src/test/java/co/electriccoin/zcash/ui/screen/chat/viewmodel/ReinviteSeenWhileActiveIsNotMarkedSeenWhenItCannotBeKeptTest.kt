package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZC-668 door 4: a failed state commit must not burn a reader-premarked relay event. */
internal class ReinviteSeenWhileActiveIsNotMarkedSeenWhenItCannotBeKeptTest : Zc668Fixture() {
    @Test
    fun `failed active state commit removes reader receipt mark`() =
        runBlocking {
            val screen = screen()
            store.members.refuseNextCommit = true
            receive(screen.vm, invite(), I2)
            assertEquals("active state commit fault must fire", 1, store.members.refusals)
            assertFailedMemoryProbe()
            assertNull("failed commit must not keep a disk copy", diskState().pendingReinvite)
            assertFalse("failed keep must remove the reader-equivalent seen mark", store.proxy.hasSeenNostrEvent(I2))
        }

    @Test
    fun `same-screen redelivery after failed commit durably keeps invite despite volatile seen id`() =
        runBlocking {
            val screen = screen()
            val memo = invite()
            store.members.refuseNextCommit = true
            receive(screen.vm, memo, I2)
            assertEquals(1, store.members.refusals)
            assertFailedMemoryProbe()
            assertFalse("fixture disk must still lack id", I2 in diskState().seenActiveInviteIds)
            // Same VM and same store: rebuilding here would conceal the poisoned-memory replay failure.
            receive(screen.vm, memo, I2)
            assertTrue("successful replay remains marked seen", store.proxy.hasSeenNostrEvent(I2))
            assertEquals("same-screen replay must durably retain invite", I2, diskState().pendingReinvite?.inviteId)
            assertTrue("same-screen replay must durably record active seen id", I2 in diskState().seenActiveInviteIds)
            assertFalse(I2 in diskState().usedInviteIds)
            screen.close()
            reopenStore()
            assertEquals(
                "restarted store must recover replay's invite",
                I2,
                store.real
                    .getGroupAnnounceState(GID)
                    .pendingReinvite
                    ?.inviteId,
            )
            assertTrue("restarted seen ledger must retain successful replay mark", store.proxy.hasSeenNostrEvent(I2))
        }

    private fun assertFailedMemoryProbe() {
        val memory =
            store.members.failedMemory
                ?.values
                ?.filterIsInstance<String>()
                ?.singleOrNull()
        assertTrue("refused commit seam must expose I2 in volatile serialized state", memory?.contains(I2) == true)
        assertTrue(
            "refused commit seam must leave I2 absent from disk",
            store.members.failedDisk
                ?.values
                ?.none { it.toString().contains(I2) } == true,
        )
    }

    @Test
    fun `control successful active keep is durable and stays seen`() =
        runBlocking {
            receive(screen().vm, invite(), I2)
            assertEquals(I2, diskState().pendingReinvite?.inviteId)
            assertTrue(I2 in diskState().seenActiveInviteIds)
            assertTrue(store.proxy.hasSeenNostrEvent(I2))
            assertNull(diskJoin())
            assertTrue(phone.info(GID).isActive)
        }
}
