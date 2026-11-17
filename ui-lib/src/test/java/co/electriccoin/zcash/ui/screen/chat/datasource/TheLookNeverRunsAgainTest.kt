package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.MARK
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.record2124
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheckPhone
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AutoSpendVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D1; section 4.3 class 19) — the look never runs again. In every case a finished
 * chat sits at the creator's address at the time of the later move or arrival, and the assertion is the same: no
 * `creator_pub` is written, the later move looked at nothing (its line of counts says `looked=0`), and on a phone whose
 * store holds that record ([onPhone]: the record put into a view-model phone with that finished chat) the group reads
 * UNKNOWN and a removal signed with that chat's key changes nothing on three passes and after a restart.
 *
 * (a) the failed mark; (b) never twice (a deleted group, and an emptied store); (c) a kill between the mark and the
 * commit; (d) a change of wallet before the move and (e) a restore of the key store before it, both seen from the move as
 * a later start (`storeIsNew` false) over another key store: the wallet bundle and the kept copy are not driven through
 * `moveStores` and `KeptChatStoreCopies.restore` here; (f) records that reach the store another way; (g) nothing waits
 * after a failed move: the records are in memory as 2.12.4 wrote them and read UNKNOWN; (h) R19 in that state.
 *
 * At the base there is no move of this kind ("no such operation") and (h) fails because the snapshot takes the key at
 * the address when the removal arrives. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TheLookNeverRunsAgainTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    private val raw = record2124(GID, X_OLD)
    private val kickTime: Instant = Instant.ofEpochMilli(AdminCheck.T + 60_000)

    private fun hasKey(record: String?): Boolean = record != null && JSONObject(record).has("creator_pub")

    /** A view-model phone whose store holds [record], with a finished chat at the creator address: the chat's keys. */
    private fun onPhone(record: String): Pair<AdminCheckPhone, co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair> {
        val p = AdminCheck.driftedPhone()
        p.base.infos[GID] = record
        val chat = p.base.link(X_OLD)
        p.base.nostrKeys[X_OLD] = "1f".repeat(32)
        return p to chat
    }

    private fun onChain(
        vm: ChatViewModel,
        memo: String,
        n: Int,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(n), minedHeight = HEIGHT + n)

    /** The assertion every case ends with, on the record the store holds. */
    private suspend fun neverFilled(
        record: String?,
        laterLine: String?,
    ) {
        assertNotNull("the record is not in the store", record)
        assertFalse("a creator key was written after the first start", hasKey(record))
        if (laterLine != null) assertTrue("the later move looked at a record: $laterLine", laterLine.contains("looked=0"))
        val (p, chat) = onPhone(record!!)
        val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
        assertEquals("UNKNOWN", AdminCheck.keyState(vm))
        val removal = AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = null, signer = chat, wrapFor = X_OLD)
        repeat(3) { onChain(vm, removal, 6 + it) }
        AdminCheck.forgetConfirmedPasses()
        onChain(AdminCheck.chatVm(p, HandshakeSeam(X_OLD)), removal, 9)
        assertEquals("a removal signed with the later chat's key was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
        assertFalse("a creator key was written on the phone", hasKey(p.base.infos.getValue(GID)))
    }

    @Test
    fun `a - the failed mark - a key exchange later and the next start's move write no key`() =
        runBlocking {
            val f = LegacyGroupMoveFixture()
            val old = f.OldFile()
            old.apply {
                putRaw(GID, raw)
                refuseMark = true
            }
            val store = f.StoreFile().apply { refuseCommit = true }
            f.move(old, store, storeIsNew = true)

            f.finishedChat(X_OLD)
            val next = store.restart()
            val line = f.move(old, next, storeIsNew = false)

            neverFilled(next.diskSnapshot()[GID], line)
        }

    @Test
    fun `b - never twice - a record gone from the store comes across again as 2_12_4 wrote it`() =
        runBlocking {
            for (emptied in listOf(false, true)) {
                val f = LegacyGroupMoveFixture()
                val old = f.OldFile()
                old.apply {
                    putRaw(GID, raw)
                    putRaw(OTHER_GROUP, record2124(OTHER_GROUP, OTHER_CREATOR))
                    refuseClear = true
                }
                f.deleteAnswers = false
                val store = f.StoreFile()
                f.move(old, store, storeIsNew = true)
                assertFalse("fixture: the first look found no key", hasKey(store.diskSnapshot()[GID]))

                f.finishedChat(X_OLD)
                if (emptied) store.disk.clear() else store.disk.remove(GID)
                val next = store.restart()
                old.refuseClear = false
                val line = f.move(old, next, storeIsNew = false)

                assertEquals(raw, next.diskSnapshot()[GID])
                neverFilled(next.diskSnapshot()[GID], line)
            }
        }

    @Test
    fun `c - a kill between the mark and the commit - the records come across as they are`() =
        runBlocking {
            val f = LegacyGroupMoveFixture()
            f.finishedChat(X_OLD)
            val old = f.OldFile()
            old.apply {
                putRaw(GID, raw)
                putRaw(MARK, true)
            }
            val store = f.StoreFile()

            val line = f.move(old, store, storeIsNew = false)

            neverFilled(store.diskSnapshot()[GID], line)
        }

    @Test
    fun `d and e - another wallet's key store, or a restored key store, before the move - no key`() =
        runBlocking {
            val f = LegacyGroupMoveFixture()
            val old = f.OldFile().apply { putRaw(GID, raw) }
            val store = f.StoreFile().apply { refuseCommit = true }
            f.move(old, store, storeIsNew = true)

            // The key store now holds a finished chat at the creator address (another wallet's chats were loaded, or a
            // kept copy of the key store was put back).
            f.finishedChat(X_OLD)
            val next = store.restart()
            val line = f.move(old, next, storeIsNew = false)

            neverFilled(next.diskSnapshot()[GID], line)
        }

    @Test
    fun `f - records that reach the store another way are never filled`() =
        runBlocking {
            val f = LegacyGroupMoveFixture()
            f.finishedChat(X_OLD)
            // A 2.13 build before this batch, another recovery phrase's bundle, a kept copy, a recreated file: the
            // record is in the store and the old file is empty.
            val store = f.StoreFile(linkedMapOf(GID to raw))
            val line = f.move(f.OldFile(), store, storeIsNew = true)

            assertEquals("a move with an empty old file wrote a line", null, line)
            neverFilled(store.diskSnapshot()[GID], null)
        }

    @Test
    fun `g - nothing waits after a failed move - the records are in memory as 2_12_4 wrote them and read UNKNOWN`() =
        runBlocking {
            val f = LegacyGroupMoveFixture()
            f.finishedChat(X_OLD)
            val old = f.OldFile().apply { putRaw(GID, raw) }
            val store = f.StoreFile().apply { refuseCommit = true }

            f.move(old, store, storeIsNew = true)

            assertEquals("the older group is not in memory as 2.12.4 wrote it", raw, store.memorySnapshot()[GID])
            neverFilled(store.memorySnapshot()[GID], null)
        }

    @Test
    fun `h - R19 in the failed-commit state - a relay removal is kept, survives the restart, and applies after the confirmation`() =
        runBlocking {
            val f = LegacyGroupMoveFixture()
            f.finishedChat(X_OLD)
            val old = f.OldFile().apply { putRaw(GID, raw) }
            val store = f.StoreFile().apply { refuseCommit = true }
            f.move(old, store, storeIsNew = true)

            val (p, chat) = onPhone(store.memorySnapshot().getValue(GID))
            val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
            val newKey = AnnounceDriver.newGroupKey()
            val removal =
                AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = newKey, signer = chat, wrapFor = X_OLD)
            AdminCheck.relayDeliver(vm, removal, X_OLD, KEPT, kickTime)
            assertEquals("the relay removal was applied before any confirmation", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals("the relay removal was not kept", listOf(KEPT), AdminCheck.keptChanges(p).map { it.getString("id") })

            // The restart: the next start's move brings the record across without a look.
            val next = store.restart()
            f.move(old, next, storeIsNew = false)
            p.base.infos[GID] = next.diskSnapshot().getValue(GID)
            AdminCheck.forgetConfirmedPasses()
            val again = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
            assertFalse(hasKey(p.base.infos.getValue(GID)))
            assertEquals("UNKNOWN", AdminCheck.keyState(again))
            assertEquals("the kept change did not survive the restart", listOf(KEPT), AdminCheck.keptChanges(p).map { it.getString("id") })

            p.verified += X_OLD
            assertEquals(CONFIRMED, AdminCheck.confirm(again, X_OLD, chat.publicKey))
            AdminCheck.awaitOwnJobs(again)
            val pass = AdminCheck.beginPass(again)
            AdminCheck.endPass(again, pass, complete = true)
            assertEquals("the kept removal was not applied after the confirmation", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(newKey, p.groupKey(2))
            HandshakeHarness.awaitIdle(again)
        }

    private companion object {
        const val KEPT = "synthetic-zc598-relay-kick-after-failed-move"
        const val OTHER_GROUP = "zgrp_598fa0000000000000000007"
        const val OTHER_CREATOR = "usyntheticzc598othercreator1"
    }
}
