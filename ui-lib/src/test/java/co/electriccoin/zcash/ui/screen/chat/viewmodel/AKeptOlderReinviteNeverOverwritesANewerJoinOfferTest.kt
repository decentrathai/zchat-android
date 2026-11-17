package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-790 (Grok's finding 1 on ZC-668, confirmed 12 Oct) — a kept older re-invite from a group's creator, replayed by
 * the address-load sweep at a later screen open, overwrites a newer Join offer that arrived meanwhile.
 *
 * Re-invite A stays in the kept slot (a screen with no address yet kept it and closed before its sweep, or its Join
 * record could not be written). A newer re-invite B then arrives with the address loaded and is offered directly:
 * B's Join record is written and the slot is not touched. The next screen's sweep offers A, and
 * `writeGroupInviteNeedsJoin` replaces B's record unread. B is then in no store; if the creator changed the key
 * between the two, Join reopens the group on A's older key and current messages do not decrypt.
 *
 * Wanted: `offerKeptReinvite` reads the waiting record first. When that record is not older than the kept copy by the
 * slot's own rule (key number, then source time), the slot is cleared, the kept id is recorded as used and nothing
 * else is written: the record is still B's. A kept copy that is newer than the waiting record still replaces it, and a
 * kept copy that is the waiting record's own invite is still offered and not used up (controls). Nothing is sent or
 * paid before Join (the fixture checks it after every case). Every address, key and id is synthetic.
 */
internal class AKeptOlderReinviteNeverOverwritesANewerJoinOfferTest : Zc668Fixture() {
    /** A in the kept slot, B offered directly on an open screen, then the sweep of the next screen's address load. */
    private suspend fun keptThenOfferedDirectlyThenSwept(
        keptEpoch: Int,
        keptTime: Long,
        directEpoch: Int,
        directTime: Long,
        directKey: String,
    ) {
        inactive()
        val open = screen()
        keep(memo = invite(keptEpoch), id = I2, time = keptTime)
        receive(open.vm, invite(directEpoch, directKey), I3, directTime)
        assertEquals("fixture: B was offered directly", I3, diskJoin()?.optJSONObject("reopen")?.optString("invite_id"))
        assertEquals("fixture: the direct offer leaves A in the kept slot", I2, diskState().pendingReinvite?.inviteId)
        open.close()
        reopenStore()

        val next = screen(ready = false)
        next.startLoad()
        next.finishLoad()
    }

    private fun assertStillB(
        epoch: Int,
        key: String,
    ) {
        val reopen = diskJoin()?.optJSONObject("reopen")
        assertEquals("the sweep replaced the newer Join offer with the kept older re-invite", I3, reopen?.optString("invite_id"))
        assertEquals("the Join record must still carry B's key number", epoch, reopen?.optInt("key_epoch"))
        assertTrue("the Join record must still carry B's key", key == reopen?.optString("key"))
        assertNull("the kept slot must be cleared", diskState().pendingReinvite)
        assertTrue("the kept older re-invite must be recorded as used", I2 in diskState().usedInviteIds)
        assertFalse("the waiting invite must not be used up", I3 in diskState().usedInviteIds)
        assertFalse("nothing reopens before Join", phone.info(AnnounceDriver.GID).isActive)
    }

    @Test
    fun `a kept re-invite of an older key never overwrites a newer Join offer`() =
        runBlocking {
            val keyB = AnnounceDriver.newGroupKey()
            keptThenOfferedDirectlyThenSwept(
                keptEpoch = 2,
                keptTime = INVITED,
                directEpoch = 3,
                directTime = INVITED + 5_000,
                directKey = keyB,
            )
            assertStillB(epoch = 3, key = keyB)
        }

    @Test
    fun `a kept re-invite of the same key number and an earlier time never overwrites a later Join offer`() =
        runBlocking {
            val keyB = AnnounceDriver.newGroupKey()
            keptThenOfferedDirectlyThenSwept(
                keptEpoch = 2,
                keptTime = INVITED,
                directEpoch = 2,
                directTime = INVITED + 5_000,
                directKey = keyB,
            )
            assertStillB(epoch = 2, key = keyB)
        }

    /** Control, green before and after: the slot's rule still lets a kept copy of a newer key replace the record. */
    @Test
    fun `control - a kept re-invite of a newer key still replaces an older Join offer`() =
        runBlocking {
            inactive()
            val open = screen()
            receive(open.vm, invite(2), I3, INVITED + 5_000)
            assertEquals("fixture: the older invite waits for Join", I3, diskJoin()?.optJSONObject("reopen")?.optString("invite_id"))
            val keyA = AnnounceDriver.newGroupKey()
            keep(memo = invite(3, keyA), id = I2, time = INVITED)
            open.close()
            reopenStore()

            val next = screen(ready = false)
            next.startLoad()
            next.finishLoad()

            assertOffered(I2, epoch = 3, key = keyA)
        }

    /** Control, green before and after: a kept copy of the waiting record's own invite is offered, never used up. */
    @Test
    fun `control - a kept copy of the waiting invite itself is offered again and stays unused`() =
        runBlocking {
            inactive()
            val open = screen()
            val memo = invite()
            keep(memo = memo, id = I2)
            offerKept(open.vm)
            assertOffered(I2)
            keep(memo = memo, id = I2)

            offerKept(open.vm)

            assertOffered(I2)
        }
}
