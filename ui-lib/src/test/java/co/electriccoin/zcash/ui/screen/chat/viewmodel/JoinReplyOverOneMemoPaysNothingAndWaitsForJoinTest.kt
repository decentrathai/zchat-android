package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Register ZC-517 (found with ZC-506) — what Join does when the "I joined" reply (GROUP_ACCEPT) is too long for one
 * 512-byte memo. A pin of the safe half; green today.
 *
 * The signed reply is 310 bytes plus the member's address (and a few more for the key epoch it answers). With the
 * default chat identity, a 213-character unified address, that is over 512 bytes, so the reply can never go on-chain;
 * it travels only over the relays. With a 178-character address it fits.
 *
 * Pinned here, for a member with no relay path to the creator who taps Join:
 * - 213 characters: Join starts, the wallet is asked for nothing (no proposal, no submit: nothing is paid), and the
 *   reply is back in the group's needs-join record, unchanged, so nothing is lost and a later Join can send it free
 *   once the relays work;
 * - control, 178 characters: Join pays the one reply, and no needs-join record is left.
 *
 * ZC-517's relay-only reason and no-price banner are pinned separately by
 * JoinReplyThatCannotFitOneMemoIsRelayOnlyTest and GroupJoinRelayOnlyBannerNamesNoPriceTest. This safe-money pin keeps
 * both original cases: no proposal or submit, the same oversized reply kept, and the fitting reply paid once.
 *
 * Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinReplyOverOneMemoPaysNothingAndWaitsForJoinTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    private class Joined(
        val phone: AnnouncePhone,
        val seam: HandshakeSeam,
        val heldBefore: String,
    )

    /**
     * A member's phone whose own address has [length] characters gets its first invite from the known contact [ADMIN]
     * on-chain, holds no relay key for it (so the reply waits for Join), and the user taps Join.
     */
    private suspend fun inviteThenJoin(length: Int): Joined {
        val phone = AnnouncePhone("u1" + "q".repeat(length - 2))
        // [test-change-ok: W-137 ZC-922 ZC-633 review follow-up] this test is about the paid Join: it starts from the record a
        // returned first Join leaves (the price flag set), not from a fresh one.
        phone.needsJoinAfterRelayTry = true
        phone.link(ADMIN)
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        val invite = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), AnnounceDriver.newGroupKey(), keyEpoch = 1)
        AnnounceDriver.receive(vm, invite, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(71))
        HandshakeHarness.awaitIdle(vm)
        val record = phone.needsJoin[GID]
        assertNotNull("fixture: the reply that cannot go over the relays does not wait for Join", record)
        assertEquals("fixture: something was proposed before Join", 0, seam.proposalRequests.get())

        assertTrue("Join was refused", vm.joinGroupInvite(GID))
        HandshakeHarness.awaitIdle(vm)
        return Joined(phone, seam, JSONObject(record!!).getString("memo"))
    }

    private fun bytesOf(memo: String): Int = memo.toByteArray(Charsets.UTF_8).size

    @Test
    fun `with a 213-character address the reply is over one memo - Join pays nothing and the reply waits for Join again`() =
        runBlocking {
            val j = inviteThenJoin(213)

            assertTrue("the reply is ${bytesOf(j.heldBefore)} bytes: it fits one memo", bytesOf(j.heldBefore) > 512)
            assertEquals("a reply that cannot fit a memo was handed to the wallet", 0, j.seam.submits.get())
            assertEquals("a reply that cannot fit a memo was proposed for payment", 0, j.seam.proposalRequests.get())
            val record = j.phone.needsJoin[GID]
            assertNotNull("the reply that was not sent is lost: no needs-join record", record)
            assertEquals("the reply kept for the next Join is not the same reply", j.heldBefore, JSONObject(record!!).getString("memo"))
            assertFalse("a held copy stayed beside the needs-join record", j.phone.heldAccepts.containsKey(GID))
        }

    @Test
    fun `control - with a 178-character address the reply fits one memo and Join pays it once`() =
        runBlocking {
            val j = inviteThenJoin(178)

            assertTrue("the reply is ${bytesOf(j.heldBefore)} bytes: over one memo", bytesOf(j.heldBefore) <= 512)
            assertEquals("Join did not pay its one reply", 1, j.seam.submits.get())
            assertNull("a needs-join record is left after a paid Join", j.phone.needsJoin[GID])
            assertFalse(j.phone.heldAccepts.containsKey(GID))
        }
}
