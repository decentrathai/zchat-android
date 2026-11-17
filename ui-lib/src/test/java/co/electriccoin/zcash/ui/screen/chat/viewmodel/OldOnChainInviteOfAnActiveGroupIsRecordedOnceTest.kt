package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
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
import java.time.Instant
import java.util.Collections

/**
 * ZC-780 (review of 12 Oct 2026, finding 3) — the history scan rewrites the group store at every pass.
 *
 * The scan hands every on-chain group memo to ChatViewModel again at each pass. Since ZC-668's fix an invitation to a
 * group this phone is still in returns its announce state for a commit even when its id was already recorded as seen
 * (so that a relay copy whose first commit failed is committed again when the relays deliver it once more). For an
 * on-chain copy that means one encrypted commit, a bump of the group's store version and a reload of an open group
 * screen for every old invitation at every pass. It hits a phone upgraded in place from 2.12.4, where the invitation
 * the group was joined with was never recorded as used.
 *
 * Wanted: an on-chain invitation whose id is already recorded and which would not change the kept re-invitation
 * writes nothing. Everything else stays: the first sighting records the id, an invitation first seen unmined is kept
 * once the scan brings it back mined, a newer invitation takes the kept place, and a relay copy delivered again is
 * committed again.
 *
 * The phone is the MA2 fake ([AnnouncePhone]): it records every store call by name and bumps the real process-wide
 * store version on every group save, as ZchatPreferencesImpl does. Nothing is sent or proposed for payment in any
 * case. Stored states are compared, never printed. All addresses, keys, txids and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldOnChainInviteOfAnActiveGroupIsRecordedOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val seam = HandshakeSeam(ADMIN)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `the invitation a 2_12_4 phone joined with is recorded once and never rewritten by later passes`() =
        runBlocking {
            val phone = upgradedPhone()
            val vm = chat(phone)
            val original = invitation(phone, phone.keys.getValue("$GID|1"), keyEpoch = 1)

            scanPass(vm, original to originalTx)
            assertTrue("fixture: the first pass records the id", originalTx.txIdString() in phone.state(GID).seenActiveInviteIds)
            assertLaterPassesWriteNothing(phone, vm, original to originalTx)
        }

    @Test
    fun `an old invitation of another member, which is never kept, is recorded once and never rewritten`() =
        runBlocking {
            val phone = upgradedPhone()
            phone.link(Y)
            val vm = chat(phone)
            // Only the creator's invitation can be kept for a later removal; Y's is remembered by its id alone.
            val fromY = AnnounceDriver.gi(Y, phone.sessionWith(Y), phone.keys.getValue("$GID|1"), keyEpoch = 1)

            scanPass(vm, fromY to originalTx)
            assertTrue("fixture: the first pass records the id", originalTx.txIdString() in phone.state(GID).seenActiveInviteIds)
            assertTrue("fixture: another member's invitation is not kept", phone.state(GID).pendingReinvite == null)
            assertLaterPassesWriteNothing(phone, vm, fromY to originalTx)
        }

    @Test
    fun `two old invitations, the newer one kept for a later removal, are not rewritten by later passes`() =
        runBlocking {
            val phone = upgradedPhone()
            val vm = chat(phone)
            val original = invitation(phone, phone.keys.getValue("$GID|1"), keyEpoch = 1)
            val reinvite = invitation(phone, AnnounceDriver.newGroupKey(), keyEpoch = 2)

            scanPass(vm, original to originalTx, reinvite to reinviteTx)
            assertEquals(
                "fixture: the creator's newer invitation is the kept one",
                reinviteTx.txIdString(),
                phone.state(GID).pendingReinvite?.inviteId,
            )
            assertLaterPassesWriteNothing(phone, vm, original to originalTx, reinvite to reinviteTx)
            assertEquals(
                "the kept invitation was lost",
                reinviteTx.txIdString(),
                phone.state(GID).pendingReinvite?.inviteId,
            )
        }

    @Test
    fun `control - an invitation first seen unmined is still kept once the scan brings it back mined`() =
        runBlocking {
            val phone = upgradedPhone()
            val vm = chat(phone)
            val reinvite = invitation(phone, AnnounceDriver.newGroupKey(), keyEpoch = 2)

            // Not mined yet: no source time, so only its id is remembered.
            AnnounceDriver.receive(vm, reinvite, null, reinviteTx)
            HandshakeHarness.awaitIdle(vm)
            assertTrue("fixture: the unmined copy records the id", reinviteTx.txIdString() in phone.state(GID).seenActiveInviteIds)
            assertTrue("fixture: an unmined copy is not kept", phone.state(GID).pendingReinvite == null)

            val writesBefore = groupWrites(phone)
            scanPass(vm, reinvite to reinviteTx)
            assertEquals(
                "the mined copy of an invitation already seen unmined was not kept",
                reinviteTx.txIdString(),
                phone.state(GID).pendingReinvite?.inviteId,
            )
            assertEquals("keeping it is one write", 1, groupWrites(phone) - writesBefore)
        }

    @Test
    fun `a newer invitation seen for the first time costs one write and takes the kept place`() =
        runBlocking {
            val phone = upgradedPhone()
            val vm = chat(phone)
            val original = invitation(phone, phone.keys.getValue("$GID|1"), keyEpoch = 1)
            val reinvite = invitation(phone, AnnounceDriver.newGroupKey(), keyEpoch = 2)
            scanPass(vm, original to originalTx)
            scanPass(vm, original to originalTx)

            val writesBefore = groupWrites(phone)
            val versionBefore = storeVersion()
            scanPass(vm, original to originalTx, reinvite to reinviteTx)
            assertEquals(
                "the pass that brings one new invitation did not write exactly once (the old one must not be rewritten)",
                1,
                groupWrites(phone) - writesBefore,
            )
            assertTrue("the store version did not move for a real change", storeVersion() != versionBefore)
            assertTrue("its id was not recorded", reinviteTx.txIdString() in phone.state(GID).seenActiveInviteIds)
            assertEquals("it did not take the kept place", reinviteTx.txIdString(), phone.state(GID).pendingReinvite?.inviteId)
        }

    @Test
    fun `control - a relay copy delivered again is still committed again`() =
        runBlocking {
            val phone = upgradedPhone()
            val vm = chat(phone)
            val reinvite = invitation(phone, AnnounceDriver.newGroupKey(), keyEpoch = 2)

            relayDelivery(vm, reinvite)
            assertEquals("fixture: the relay copy is kept", RELAY_EVENT, phone.state(GID).pendingReinvite?.inviteId)

            // The relays deliver an invitation once; a second delivery means the first commit may not have reached the
            // disk (ZC-668, door 4), so it is committed again.
            val writesBefore = groupWrites(phone)
            val versionBefore = storeVersion()
            relayDelivery(vm, reinvite)
            assertEquals("the relay copy delivered again was not committed again", 1, groupWrites(phone) - writesBefore)
            assertTrue("the store version did not move for the recommit", storeVersion() != versionBefore)
            assertEquals("the kept relay copy was lost", RELAY_EVENT, phone.state(GID).pendingReinvite?.inviteId)
            assertNothingElseHappened(phone)
        }

    // ------------------------------------------------------------------ harness

    private val originalTx: TransactionId = AnnounceDriver.txId(100)
    private val reinviteTx: TransactionId = AnnounceDriver.txId(200)

    /**
     * A member's phone still in the group at key number 1, as an in-place upgrade from 2.12.4 leaves it: no announce
     * state at all, so the invitation it joined with was never recorded as used.
     */
    private fun upgradedPhone(): AnnouncePhone {
        val phone = AnnouncePhone()
        AnnounceDriver.memberPhone(phone)
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        assertNull("fixture: a 2.12.4 phone has no announce state", phone.states[GID])
        return phone
    }

    private suspend fun chat(phone: AnnouncePhone): ChatViewModel =
        AnnounceDriver.chatVm(phone, seam).also { AnnounceDriver.relayAcks(published) }

    /** The creator's invitation to this phone, carrying [key] at [keyEpoch]. */
    private fun invitation(
        phone: AnnouncePhone,
        key: String,
        keyEpoch: Int,
    ): String = AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), key, keyEpoch)

    /** One pass of the history scan: every on-chain invitation again, mined, with its transaction id and block time. */
    private suspend fun scanPass(
        vm: ChatViewModel,
        vararg invitations: Pair<String, TransactionId>,
    ) {
        invitations.forEachIndexed { i, (memo, tx) ->
            AnnounceDriver.receive(vm, memo, Instant.ofEpochMilli(T + 20_000L + i * 60_000L), tx)
        }
        HandshakeHarness.awaitIdle(vm)
    }

    private suspend fun relayDelivery(
        vm: ChatViewModel,
        memo: String,
    ) {
        AnnounceDriver.receive(vm, memo, Instant.ofEpochMilli(T + 20_000L), sender = ADMIN, eventId = RELAY_EVENT)
        HandshakeHarness.awaitIdle(vm)
    }

    /** How many writes of the group's stores the phone has made so far. */
    private fun groupWrites(phone: AnnouncePhone): Int = phone.calls.toList().count { it in GROUP_STORE_WRITES }

    /** The process-wide store version of the group (what an open group screen follows). */
    private fun storeVersion(): Long? = ZchatPreferences.groupStoreVersion.value[GID]

    private suspend fun assertLaterPassesWriteNothing(
        phone: AnnouncePhone,
        vm: ChatViewModel,
        vararg invitations: Pair<String, TransactionId>,
    ) {
        repeat(3) { n ->
            val pass = n + 2
            val writesBefore = groupWrites(phone)
            val versionBefore = storeVersion()
            val stateBefore = phone.state(GID)
            scanPass(vm, *invitations)
            assertEquals(
                "pass $pass: the scan wrote the group store again for invitations it had already recorded",
                0,
                groupWrites(phone) - writesBefore,
            )
            assertEquals(
                "pass $pass: the group's store version moved, so an open group screen reloads",
                versionBefore,
                storeVersion(),
            )
            assertTrue("pass $pass: the announce state changed", stateBefore == phone.state(GID))
        }
        assertNothingElseHappened(phone)
    }

    private fun assertNothingElseHappened(phone: AnnouncePhone) {
        assertTrue("the active group was changed", phone.info(GID).isActive)
        assertNull("a needs-join record was left on an active group", phone.needsJoin[GID])
        assertEquals("the key number of an active group changed", 1, phone.epochs[GID])
        assertTrue("something was sent over the relays", published.isEmpty())
        assertEquals("something was proposed for payment", 0, seam.proposalRequests.get())
        assertEquals("something was submitted", 0, seam.submits.get())
    }

    private companion object {
        const val RELAY_EVENT = "synthetic-zc780-relay-event"

        /** Every write of a group's stores the fake answers (the announce state is the one this defect makes). */
        val GROUP_STORE_WRITES =
            setOf(
                "saveGroupAnnounceState",
                "saveGroupMembers",
                "saveGroupMembersDurably",
                "saveGroupInfo",
                "saveGroupMessages",
                "saveGroupKey",
                "setGroupKeyEpoch",
                "setGroupRemovalFloor",
                "setGroupInviteNeedsJoin",
                "setHeldGroupAccept",
            )
    }
}
