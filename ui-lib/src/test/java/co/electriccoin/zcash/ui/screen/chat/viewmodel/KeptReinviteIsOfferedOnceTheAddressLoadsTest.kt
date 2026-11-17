package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZC-668 door 2: address load must sweep disk pending records even with an empty deferred queue. */
internal class KeptReinviteIsOfferedOnceTheAddressLoadsTest : Zc668Fixture() {
    @Test
    fun `kept active invite is offered after null-address kick and product load`() =
        runBlocking {
            val screen = screen(ready = false)
            receive(screen.vm, invite(), I2)
            assertEquals("fixture active invite must be durably kept", I2, diskState().pendingReinvite?.inviteId)
            kick(screen.vm)
            assertNull("no Join before address loads", diskJoin())
            assertEquals(I2, diskState().pendingReinvite?.inviteId)
            assertTrue("kept sweep must work with empty new-invite queue", screen.pendingQueueEmpty())
            screen.startLoad()
            screen.finishLoad()
            assertOffered(I2)
        }

    @Test
    fun `fresh screen sweeps kept invite after null-address removal`() =
        runBlocking {
            val first = screen(ready = false)
            receive(first.vm, invite(), I2)
            kick(first.vm)
            first.close()
            reopenStore()
            assertEquals("reconstruction must read the disk kept invite", I2, diskState().pendingReinvite?.inviteId)
            val fresh = screen(ready = false)
            assertTrue(fresh.pendingQueueEmpty())
            fresh.startLoad()
            fresh.finishLoad()
            assertOffered(I2)
        }

    @Test
    fun `address-load sweep captured before old-wallet state read cannot offer or clear in replacement wallet`() =
        runBlocking {
            inactive()
            keep()
            val otherPhone = AnnouncePhone()
            AnnounceDriver.memberPhone(otherPhone)
            otherPhone.nostrKeys[ADMIN] = AnnounceDriver.ADMIN_NOSTR
            otherPhone.infos[GID] =
                co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol.serializeGroupInfo(
                    otherPhone.info(GID).copy(isActive = false, removedByAdmin = true),
                )
            val otherDisks = Zc668Disks()
            val other = Zc668Store(otherPhone, otherDisks)
            val memo = AnnounceDriver.gi(ADMIN, otherPhone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), 2)
            assertTrue(
                other.real.saveGroupAnnounceState(
                    GID,
                    co.electriccoin.zcash.ui.screen.chat.model.AnnounceState(
                        inactiveSince = REMOVED,
                        seenActiveInviteIds = setOf(I3),
                        pendingReinvite =
                            co.electriccoin.zcash.ui.screen.chat.model.PendingReinvite(
                                I3,
                                INVITED,
                                memo.substringAfter("GI:$GID:"),
                                ADMIN,
                            ),
                    ),
                ),
            )
            store.switchAfterStateRead = other
            val screen = screen(ready = false)
            assertTrue(screen.pendingQueueEmpty())
            screen.startLoad()
            screen.finishLoad()
            assertEquals("address load must inspect stored kept invites despite an empty queue", 1, store.walletChanges)
            assertEquals("the sweep must not write after the old-wallet read changed ownership", 0, store.writesAfterChange)
            assertNull("replacement wallet must receive no Join offer", other.real.getGroupInviteNeedsJoin(GID))
            assertEquals(
                "replacement wallet pending copy must not be cleared or used",
                I3,
                other.real
                    .getGroupAnnounceState(GID)
                    .pendingReinvite
                    ?.inviteId,
            )
            assertFalse(I3 in other.real.getGroupAnnounceState(GID).usedInviteIds)
            assertEquals("original wallet pending copy must survive", I2, diskState().pendingReinvite?.inviteId)
        }

    @Test
    fun `control wallet generation change inside kept offer writes nothing`() =
        runBlocking {
            inactive()
            keep()
            val screen = screen()
            store.changeWalletOnCreatorRead = true
            offerKept(screen.vm)
            assertEquals("fixture wallet transition must fire", 1, store.walletChanges)
            assertEquals("no invite write may reach the new wallet", 0, store.writesAfterChange)
            assertNull(diskJoin())
            assertEquals(I2, diskState().pendingReinvite?.inviteId)
        }
}
