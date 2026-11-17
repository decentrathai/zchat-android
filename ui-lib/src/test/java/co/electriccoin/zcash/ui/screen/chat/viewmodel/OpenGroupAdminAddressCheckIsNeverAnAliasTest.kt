package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.KeptAdminChange
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroupAlias.CREATOR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroupAlias.MEMBER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroupAlias.OTHER_MEMBER
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-967, finding 2 (KI-25 R17, pair 3) — the two checks that tie a change of an Open group to its stored admin address
 * ([GroupAdminCheck.openGroupSealedByItsAdmin], and the list check behind [GroupAdminCheck.keyState]'s Open-group row)
 * compare the seal sender with the stored creator address as stored, never through an address alias.
 *
 * - (a) with the alias of [OpenGroupAlias.planted], the member's signed removal sealed by the member is not kept, the
 *   group still reads NOT_APPLICABLE, and the card does not say that the admin changed the group key;
 * - (b) a refused entry already in the list whose seal came from that member does not make the group ask;
 * - (c) control: a refused entry sealed by the stored creator address still makes the group ask.
 *
 * At the base (a) and (b) fail: the alias makes the member's seal count as the admin's. All values are synthetic;
 * nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupAdminAddressCheckIsNeverAnAliasTest {
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

    private fun refused(from: String): KeptAdminChange =
        KeptAdminChange(
            id = "synthetic-zc967-kept-$from".take(KeptAdminChange.MAX_ID_CHARS),
            kind = KeptAdminChange.KICK,
            epoch = 2,
            payload = OpenGroupAlias.sealedKick(kicked = OTHER_MEMBER),
            from = from,
            by = from,
            kept = System.currentTimeMillis(),
        )

    @Test
    fun `a - the member's signed removal sealed by the member is not kept and the group does not ask`() =
        runBlocking {
            val (p, vm, member) = OpenGroupAlias.planted()
            val removal =
                AdminCheck.kickNaming(
                    p,
                    naming = CREATOR,
                    kicked = OTHER_MEMBER,
                    newEpoch = 2,
                    newKey = null,
                    signer = member,
                    wrapFor = MEMBER,
                )

            AdminCheck.relayDeliver(vm, removal, MEMBER, "synthetic-zc967-signed-kick", OpenGroupAlias.changeTime)

            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, OTHER_MEMBER))
            val keptIds = AdminCheck.keptChanges(p).map { it.getString("id") }
            assertEquals("the member's removal was kept as the admin's", emptyList<String>(), keptIds)
            assertEquals("the member's seal made the Open group ask", "NOT_APPLICABLE", AdminCheck.keyState(vm))
            assertFalse(
                "the card says the admin changed the group key",
                GroupAdminCheck.openGroupAdminElsewhere(p.prefs, emptyList(), GID),
            )
        }

    @Test
    fun `b - a refused entry sealed by the member does not count as sealed by the admin address`() =
        runBlocking {
            val (p, vm, _) = OpenGroupAlias.planted()
            assertFalse(
                "the member's seal counts as sealed by the Open group's admin address",
                GroupAdminCheck.openGroupSealedByItsAdmin(p.prefs, p.base.info(GID), MEMBER),
            )
            p.base.states[GID] = p.base.state(GID).copy(keptAdminChanges = listOf(refused(MEMBER)))
            assertEquals("a refused entry the member sealed made the Open group ask", "NOT_APPLICABLE", AdminCheck.keyState(vm))
        }

    @Test
    fun `c - control - an entry sealed by the stored creator address still makes the group ask`() =
        runBlocking {
            val (p, vm, _) = OpenGroupAlias.planted()
            assertTrue(GroupAdminCheck.openGroupSealedByItsAdmin(p.prefs, p.base.info(GID), CREATOR))
            p.base.states[GID] = p.base.state(GID).copy(keptAdminChanges = listOf(refused(CREATOR)))
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
        }
}
