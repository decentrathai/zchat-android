package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X2
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
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * Round 8 MA2, design test 25 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.2, review M1 / F6) — one member in two address forms is
 * paid for once.
 *
 * A member's phone learned X from X's group message (W9) in one address form (c1 = [X]); the creator then announces X
 * in the form it announces X in (c2 = [X2]). The two forms are different addresses on this phone, so both rows would
 * be recipients and every message would pay twice for X; an `R` for c2 would leave the c1 row paying on.
 *
 * Wanted: once the roster is announced, a member's phone sends only to announced members (and the creator): X counts
 * once, as its announced form, in messageRecipients and in groupMessageCost's paid memos; after `R X`, X is gone
 * completely.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateFormsPayOnceTest {
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
    fun `a W9 row in one form plus an announced other form is one recipient and one paid memo, and R removes both`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin =
                AnnounceDriver.memberPhone(
                    phone,
                    extraRows = listOf(GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE)),
                )
            // The creator is reachable free over the relays; X is on-chain only, in both forms.
            phone.nostrKeys[ADMIN] = ADMIN_NOSTR
            AnnounceDriver.relayAcks(published)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val f = GroupPaidOnceFixture().apply { scheduler = this@DuplicateFormsPayOnceTest.scheduler }
            val gvm = AnnounceDriver.groupVm(phone, f)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.A, X2, T + 1_000, 1, admin),
                Instant.ofEpochMilli(T + 1_000),
                AnnounceDriver.txId(1)
            )

            val recipients = AnnounceDriver.messageRecipients(gvm)
            assertEquals(
                "X is not a recipient exactly once, in its announced form: $recipients",
                listOf(X2),
                recipients.filter {
                    it == X ||
                        it == X2
                }
            )
            val cost = gvm.groupMessageCost(GID)
            assertEquals("the cost counts X more than once (or the creator)", 2, cost.recipients)
            assertEquals("the cost pays for X more than once", 1, cost.onChainMessages)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, X2, T + 2_000, 1, admin),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(2)
            )

            val after = AnnounceDriver.messageRecipients(gvm)
            assertFalse("X is still a recipient after R X: $after", after.any { it == X || it == X2 })
            assertEquals("X is still paid for after R X", 0, gvm.groupMessageCost(GID).onChainMessages)
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, f.seam.proposalRequests.get())
        }
}
