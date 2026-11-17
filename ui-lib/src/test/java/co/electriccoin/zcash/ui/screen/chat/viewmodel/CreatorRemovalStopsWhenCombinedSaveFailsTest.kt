package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Additional ZC-690 coverage of the creator's caller after the combined save refuses or throws an ordinary exception.
 * The wrapper refuses the three-argument API before delegating it. This tests caller behavior, not SharedPreferences
 * atomicity; the separate frozen removal regression covers the real roster/announce store and its memory/disk split.
 * Key memos precede the commit, so a confirmed quote may already have paid for one. That payment must never be retried.
 * runTest also reports uncaught exceptions from launched ViewModel work; swallowing one in a test handler is avoided.
 * All identities, keys and payments are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreatorRemovalStopsWhenCombinedSaveFailsTest {
    private val scopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        scopes.close()
        MA3.resetRuntime()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a false combined removal save stops after the already quoted key without notice announce or key adoption`() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            withContext(Dispatchers.Default) { refusedRemoval(Fault.FALSE) }
        }

    @Test
    fun `an ordinary throwing combined removal save stops cleanly after the already quoted key without retry`() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            withContext(Dispatchers.Default) { refusedRemoval(Fault.THROW) }
        }

    private suspend fun refusedRemoval(fault: Fault) {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE))
        MA3.memberPhone(A, admin)
        MA3.memberPhone(X, admin)
        admin.base.nostrKeys[X] = NPUB.getValue(X)
        MA3.alreadyAnnounced(admin, listOf(A, X))
        val net = RelayNet().also { it.register() }
        val seam = AnnounceSeam()
        // The fixture polls with a real timeout on Default; runTest drives Main and reports unhandled VM failures.
        val fixture = GroupPaidOnceFixture()
        var armed = false
        var attempts = 0
        var stagedRoster: String? = null
        var stagedState: AnnounceState? = null
        var paidAtCommit: List<PaidMemo>? = null
        val proxy =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                if (armed && method.name == "saveGroupMembersDurably" && args!!.size == 3) {
                    attempts += 1
                    assertEquals("fault must target this group's combined save", GID, args[0])
                    stagedRoster = args[1] as String
                    stagedState = args[2] as AnnounceState
                    paidAtCommit = seam.submitted.toList()
                    if (fault == Fault.THROW) throw IllegalStateException("synthetic ZC-690 combined save fault")
                    false
                } else {
                    try {
                        method.invoke(admin.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as ZchatPreferences
        val vm = MA3.groupVm(admin, fixture, seam)
        try {
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(vm, proxy)
            MA3.load(vm, fixture)
            // Leave real free announcement debt after load: an erroneous automatic pass would send A to X.
            admin.ledgers[GID] = admin.ledger().filterNot { it.rAddr == X && it.sAddr == A }
            val planner = GroupAnnouncer(proxy, { _, _ -> false }, { admin.base.nostrKeys[it] })
            try {
                assertTrue(
                    "setup: an automatic pass has a real relay announcement to send",
                    planner.owedPairs(GID).any { it.recipient == X && it.subject == A && it.op == AnnounceOp.A },
                )
            } finally {
                planner.close()
            }
            net.clear()
            val rosterBefore = admin.base.rosters.getValue(GID)
            val stateBefore = admin.state()
            val ledgerBefore = admin.ledger().toList()
            val counterBefore = admin.counters[GID]
            val keysBefore = admin.base.keys.toMap()
            val floorBefore = admin.base.floors[GID]
            val heightsBefore = admin.base.kickHeights[GID]
            assertEquals("setup: initial key epoch", 1, admin.base.epochs[GID])
            armed = true
            val before = System.currentTimeMillis()
            fixture.awaitNewJobs(vm) { vm.kickMember(GID, X) }
            val quote = vm.paidActionConfirmation.value
            assertNotNull("setup: a genuine removal quote is required", quote)
            assertEquals(PaidAction.GROUP_REMOVE_MEMBER, quote!!.action)
            assertEquals("quote counts A's key and R X to A; X's notice is free", 2, quote.onChainMessages)
            assertEquals("quoting pays nothing", emptyList<PaidMemo>(), seam.proposals.toList())
            assertEquals("quoting must not call the combined save", 0, attempts)
            MA3.confirm(vm, fixture)
            val after = System.currentTimeMillis()

            assertEquals("the real confirmed kick reached exactly one combined save", 1, attempts)
            val rows = ZMSGGroupProtocol.deserializeGroupMembers(checkNotNull(stagedRoster))
            assertEquals("fault is after removal was staged", MemberStatus.LEFT, rows.single { it.address == X }.status)
            val removal = checkNotNull(stagedState).members.getValue(X)
            assertEquals("fault sees the rotated epoch", 2, removal.removedAtEpoch)
            assertEquals("fault sees an authenticated removal", ActivatedBy.AUTH, removal.leftBy)
            assertTrue("fault sees this call's removal time", removal.leftAtMs in before..after)
            val paid = checkNotNull(paidAtCommit)
            assertEquals("only the quoted key was already paid at the fault", 1, paid.size)
            assertEquals(A, paid.single().recipient)
            val memo = paid.single().memo
            assertEquals(GroupMessageType.GROUP_KICK, ZMSGGroupProtocol.parseMessageType(memo))
            val payload = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
            assertEquals(X, payload.getString("kicked"))
            assertEquals(2, payload.getInt("new_epoch"))
            assertTrue("the preceding paid memo actually carries A's rotated key", payload.has("enc_key"))
            assertTrue(
                "the preceding key memo is genuinely signed for A",
                E2EEncryption.verify(
                    MA3.keyFor(admin, A).publicKey,
                    ZMSGGroupProtocol.groupKickSignedData(GID, X, MA3.C, 2, payload.getString("enc_key")),
                    payload.getString("sig"),
                ),
            )
            assertEquals("refusal must not propose a notice, R or retry", paid, seam.proposals.toList())
            assertEquals("the prior quoted key is submitted exactly once", paid, seam.submitted.toList())
            assertTrue("refusal must not send a notice or run the automatic relay pass", net.published.isEmpty())
            assertNotNull("a refused save after the paid key memo must show its result", vm.groupSendEvent.value)
            assertEquals("refused wrapper must not delegate the roster", rosterBefore, admin.base.rosters[GID])
            assertEquals("no removal record or announce numbering after refusal", stateBefore, admin.state())
            assertEquals("no announce ledger updates after refusal", ledgerBefore, admin.ledger())
            assertEquals("no announce counter advance after refusal", counterBefore, admin.counters[GID])
            assertEquals(
                "the creator keeps the key it sent to A, under its number",
                keysBefore + ("$GID|2" to E2EEncryption.decrypt(payload.getString("enc_key"), admin.base.sessionWith(A))),
                admin.base.keys.toMap(),
            )
            assertEquals("the creator moves to the key number it sent", 2, admin.base.epochs[GID])
            assertEquals("the removal floor follows the key number the members were sent (was $floorBefore)", 2, admin.base.floors[GID])
            val heightsAfter = admin.base.kickHeights[GID] ?: emptyMap()
            assertEquals(
                "the removal frontier gains the entry of the key number the members were sent",
                (heightsBefore ?: emptyMap()).keys + 2,
                heightsAfter.keys,
            )
            assertEquals("refusal must not ask again or automatically retry", null, vm.paidActionConfirmation.value)
        } finally {
            vm.viewModelScope.cancel()
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                vm.viewModelScope.coroutineContext.job
                    .join()
            }
        }
    }

    private enum class Fault { FALSE, THROW }
}
