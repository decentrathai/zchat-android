package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.groupSendBannerText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * Round 8 MA2, design test 8 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.1 step 3) — a verified `R self` tells this phone it was
 * removed.
 *
 * The creator can announce "this member is no longer in the group" to the member itself (its other device, or a
 * member who missed the kick). At the MA1 tip nothing handles GP: the group stays active, the cost line keeps
 * quoting the other members and a send still goes out.
 *
 * Wanted: an `R` naming this phone, verified against the creator's key, with `n > max(selfJoinN, self.n)` and
 * `e >= selfJoinEpoch`, saves the group as one this phone was removed from (the P6 path): inactive, 0 recipients, a
 * send pays and sends nothing, and the screen says "You were removed from this group" / "Te quitaron de este grupo".
 * An unverified `R self`, or one that is not newer than this phone's last join, changes nothing.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovedByAnnounceIsToldTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a verified R self makes the group one this phone was removed from - no recipients, nothing sent or paid`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            // Another member, reachable free over the relays, so a send at the MA1 tip visibly goes out.
            phone.nostrKeys[Y] = "5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e"
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, Y, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 1_000),
                AnnounceDriver.txId(1)
            )

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, phone.self, T + 5_000, 1, admin),
                Instant.ofEpochMilli(T + 5_000),
                AnnounceDriver.txId(5),
            )

            val info = phone.info(GID)
            assertFalse("a verified R naming this phone left its group active", info.isActive)
            assertTrue("the group is not saved as one this phone was removed from", info.removedByAdmin)
            assertEquals(0, seam.proposalRequests.get())

            val f = GroupPaidOnceFixture().apply { scheduler = this@RemovedByAnnounceIsToldTest.scheduler }
            val gvm = AnnounceDriver.groupVm(phone, f)
            assertEquals(
                "the cost line still quotes members of a group this phone was removed from",
                0,
                gvm.groupMessageCost(GID).recipients
            )
            AnnounceDriver.relayAcks(published)
            f.awaitNewJobs(gvm) { gvm.sendGroupMessage(GID, "still here?") }
            if (gvm.paidActionConfirmation.value != null) f.awaitNewJobs(gvm) { gvm.confirmPaidAction() }
            assertEquals("a removed member paid to send a group message", 0, f.seam.proposalRequests.get())
            assertTrue("a removed member still published a group message: $published", published.isEmpty())
            val event = gvm.groupSendEvent.value
            assertNotNull("the refused send reports no outcome", event)
            assertEquals(
                "You were removed from this group",
                groupSendBannerText(
                    event!!,
                    { id -> ChatResourceText.english(id) },
                    { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) },
                ),
            )
            assertEquals("Te quitaron de este grupo", ChatResourceText.strings("values-es")["group_you_were_removed"])
        }

    @Test
    fun `an R self signed by a member changes nothing`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(phone)
            val member = phone.link(X)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, phone.self, T + 5_000, 1, member),
                Instant.ofEpochMilli(T + 5_000),
                AnnounceDriver.txId(5)
            )
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, phone.self, T + 5_001, 1, member),
                Instant.ofEpochMilli(T + 5_001),
                sender = X,
                eventId = "synthetic-event-rself",
            )

            assertTrue("an unverified R self made the group inactive", phone.info(GID).isActive)
            assertFalse(phone.info(GID).removedByAdmin)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a verified R self not newer than this phone's last join, or at an older epoch, changes nothing`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, epoch = 3)
            // This phone joined (again) at announce number T+9000, key epoch 3.
            phone.states[GID] = AnnounceState(hw = T + 9_000, selfJoinN = T + 9_000, selfJoinEpoch = 3)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, phone.self, T + 5_000, 3, admin),
                Instant.ofEpochMilli(T + 5_000),
                AnnounceDriver.txId(5)
            )
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, phone.self, T + 10_000, 2, admin),
                Instant.ofEpochMilli(T + 10_000),
                AnnounceDriver.txId(6)
            )

            assertTrue("an R self older than this phone's last join removed it", phone.info(GID).isActive)
            assertEquals(0, seam.proposalRequests.get())

            // Control: a newer one at the join epoch does apply.
            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, phone.self, T + 11_000, 3, admin),
                Instant.ofEpochMilli(T + 11_000),
                AnnounceDriver.txId(7)
            )
            assertFalse("a verified, newer R self at the join epoch did not remove this phone", phone.info(GID).isActive)
        }
}
