package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.NOW_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.own
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-1069 (T7bG cross review, GPT and Grok, medium) — a rebuilt group's progress, as it happens:
 *
 * 1. The owed-key re-send never records that the new key reached a member (only an invitation, a Resend invite and the
 *    key change itself do), so a group whose key change reached nobody stays blocked after the owed key goes out, until
 *    the admin resends an invitation. Wanted: an owed key that went (or may have gone) out counts as the new key reaching
 *    a member, as an invitation does.
 * 2. A removal whose roster and key number both did not reach the disk still records the group as rekeyed (the normal
 *    path checks the stored number first). Wanted: rekeyed only when the new number is on the disk.
 * 3. Our own removal read back while the stored roster cannot be read replaces it with a one-row roster (the removed
 *    member, LEFT), losing every other row. Wanted: an unreadable roster is left as it is.
 *
 * Nothing new pays: the owed key is the existing re-send and the key change stays the quoted action. All addresses, keys,
 * ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupProgressIsRecordedAsItHappensTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        net.register()
        RebuiltGroupFixture.setClock(NOW_MS, NOW_MS - 3_600_000L)
    }

    @After
    fun tearDown() {
        RebuiltGroupFixture.setClock(null, 0L)
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private suspend fun gvm(admin: AnnouncerPhone): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@RebuiltGroupProgressIsRecordedAsItHappensTest.scheduler }
        return MA3.groupVm(admin, f, AnnounceSeam()) to f
    }

    private fun flag(
        admin: AnnouncerPhone,
        name: String,
    ): Boolean = JSONObject(admin.base.infos.getValue(GID)).optBoolean(name, false)

    private fun ownRemoval(
        admin: AnnouncerPhone,
        removed: String,
        to: String,
        epoch: Int,
    ): String {
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), admin.base.sessionWith(to))
        val signed = ZMSGGroupProtocol.groupKickSignedData(GID, removed, C, epoch, wrapped)
        val sig = E2EEncryption.sign(MA3.keyFor(admin, to).privateKey, signed)
        return ZMSGGroupProtocol.createGroupKickMessage(GID, removed, C, epoch, wrapped, sig)
    }

    /** Our own key change to number 3 sent to a member this restored phone has no secure chat with (not readable here). */
    private fun unreadableOwnKeyChange(): String {
        val signer = E2EEncryption.generateKeyPair()
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), ByteArray(32) { 7 })
        val sig = E2EEncryption.sign(signer.privateKey, ZMSGGroupProtocol.groupKeySignedData(GID, C, 3, wrapped, "rotation"))
        return ZMSGGroupProtocol.createGroupKeyMessage(GID, C, 3, wrapped, sig)
    }

    /** [base] with the roster's durable write refused and the durable key-number write throwing. */
    private fun storeRefusingRemovalCommit(base: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when {
                method.name == "saveGroupMembersDurably" -> false
                method.name == "setGroupKeyEpoch" && args?.size == 3 && args[2] == true -> throw IOException("synthetic refused commit")
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    @Test
    fun `1 an owed key that goes out after a key change that reached nobody lets the group send`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            val vm = MA3.chatVm(admin)
            // No secure chat with A yet: the key change reaches nobody (as RebuiltGroupMakesANewKeyBeforeItSendsTest (n)).
            own(vm, ownInvite(admin, A), 1, A, 100)
            own(vm, unreadableOwnKeyChange(), 2, A, 110)
            val (gvm, f) = gvm(admin)
            RebuiltGroupFixture.rotate(gvm, f)
            val n = admin.base.epochs.getValue(GID)
            assertTrue("fixture: the key change was not recorded", flag(admin, "rekeyed_after_rebuild"))
            assertFalse("fixture: the key change reached a member", flag(admin, "rebuilt_key_reached_member"))

            // A secure chat with A now exists, and A's row is owed the new key.
            RebuiltGroupFixture.member(A, admin, epoch = 0)
            admin.base.rosters[GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    admin.base.roster(GID).map { if (it.address == A) it.copy(pendingKeyEpoch = n, pendingKeyTxIds = null) else it },
                )
            f.awaitNewJobs(gvm) { gvm.retryPendingGroupKeys(GID) }

            assertEquals("fixture: the owed key did not go to A", listOf(n), RebuiltGroupFixture.keyChangesTo(net, NPUB.getValue(A)))
            assertTrue("the owed key reached A but the group still says nobody has the new key", flag(admin, "rebuilt_key_reached_member"))
        }

    @Test
    fun `2 a removal whose roster and key number did not reach the disk does not record the group as rekeyed`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 0)
            RebuiltGroupFixture.member(B, admin, epoch = 0)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), 1, A, 100)
            own(vm, ownInvite(admin, B), 2, B, 101)
            assertTrue("fixture: the group was not rebuilt", admin.base.info(GID).announcerRebuilt)
            val stored = admin.base.epochs[GID]
            val (gvm, f) = gvm(admin)
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(gvm, storeRefusingRemovalCommit(admin.proxy))

            f.awaitNewJobs(gvm) { gvm.kickMember(GID, B) }
            if (gvm.rekeyUnkeyableConfirmation.value != null) f.awaitNewJobs(gvm) { gvm.confirmRekeyUnkeyable() }
            if (gvm.paidActionConfirmation.value != null) MA3.confirm(gvm, f)

            assertEquals("fixture: the key number reached the disk", stored, admin.base.epochs[GID])
            assertFalse(
                "the group is recorded as rekeyed although its new key number is not on the disk",
                flag(admin, "rekeyed_after_rebuild"),
            )
        }

    @Test
    fun `3 our own removal read back over an unreadable roster leaves the roster as it is`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 4)
            RebuiltGroupFixture.member(B, admin, epoch = 3)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), 1, A, 100)
            own(vm, ownInvite(admin, B), 2, B, 101)
            assertTrue("fixture: the group was not rebuilt", admin.base.info(GID).announcerRebuilt)
            val unreadable = "[{\"address\":\"synthetic-not-a-roster\""
            admin.base.rosters[GID] = unreadable

            own(vm, ownRemoval(admin, B, A, 4), 3, A, 120)

            assertEquals("fixture: the removal was not read (the stored number)", 4, admin.base.epochs[GID])
            assertEquals("an unreadable roster was replaced by a roster of the removed member alone", unreadable, admin.base.rosters[GID])
        }
}
