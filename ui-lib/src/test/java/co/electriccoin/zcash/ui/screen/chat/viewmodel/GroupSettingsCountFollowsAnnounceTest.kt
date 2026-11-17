package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 9 R9-5 (REVIEW_R8 F11 = device D-F14) — Group Settings counts the whole group once the creator announced it.
 *
 * At 3bcbc11ab GroupSettingsView picks the header's plural by isCreator alone: every phone that is not the creator
 * says "N known members". Round 8 MA2 made a member's phone know the whole group once the creator has announced the
 * roster (GroupViewModel.groupMessageCost: knownOnly = !isGroupAdmin && !rosterAnnounced), and the group screen's
 * header follows that, so on the gate phones the group screen and Group Settings disagreed.
 *
 * Wanted: Group Settings takes knownOnly from the same groupMessageCost. On a member's phone:
 * - with rosterAnnounced = true the header is group_member_count ("3 members");
 * - without it, group_member_count_known ("3 known members").
 * On the creator's phone it is group_member_count.
 *
 * The new API (GroupSettingsState.Success.knownOnly, view.GroupReachTextKt.groupSettingsMemberCountLabel) is reached by
 * name, so this file compiles on the build before the fix. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSettingsCountFollowsAnnounceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

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

    /** A member's phone in G with the admin, us and X, all ACTIVE; [announced] = the creator announced the roster. */
    private fun memberPhone(announced: Boolean): AnnouncePhone {
        val phone = AnnouncePhone()
        AnnounceDriver.memberPhone(
            phone,
            extraRows = listOf(GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE)),
        )
        if (announced) phone.states[GID] = AnnounceState(rosterAnnounced = true)
        return phone
    }

    private suspend fun settings(
        phone: AnnouncePhone,
        f: GroupPaidOnceFixture = GroupPaidOnceFixture().apply { scheduler = this@GroupSettingsCountFollowsAnnounceTest.scheduler },
    ): GroupSettingsState.Success {
        val vm = AnnounceDriver.groupVm(phone, f)
        f.awaitNewJobs(vm) { vm.loadGroupSettings(GID) }
        return vm.groupSettingsState.value as? GroupSettingsState.Success
            ?: throw AssertionError("Group Settings did not load: ${vm.groupSettingsState.value}")
    }

    private fun knownOnly(state: GroupSettingsState.Success): Boolean {
        val getter =
            GroupSettingsState.Success::class.java.methods.firstOrNull { it.name == "getKnownOnly" }
                ?: throw AssertionError(
                    "Group Settings has no knownOnly: its header still picks 'known members' by isCreator alone",
                )
        return getter.invoke(state) as Boolean
    }

    /** The header under the group's name in Group Settings, in English. */
    private fun header(state: GroupSettingsState.Success): String {
        val reach =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.view.GroupReachTextKt") }.getOrNull()
                ?: throw AssertionError("no view.GroupReachTextKt")
        val method =
            reach.methods.firstOrNull { it.name == "groupSettingsMemberCountLabel" }
                ?: throw AssertionError("Group Settings' member count has no testable text (groupSettingsMemberCountLabel)")
        val plural: (Int, Int, List<Any>) -> String = { id, q, args -> ChatResourceText.englishPlural(id, q, args) }
        return method.invoke(null, state.members, knownOnly(state), plural) as String
    }

    @Test
    fun `on a member's phone whose roster the creator announced, Group Settings says members`() =
        runBlocking {
            val state = settings(memberPhone(announced = true))
            assertEquals("fixture: not the creator", false, state.isCreator)
            assertEquals(
                "a member's phone that knows the whole group still says 'known members' in Group Settings",
                false,
                knownOnly(state),
            )
            assertEquals("3 members", header(state))
        }

    @Test
    fun `on a member's phone with no announced roster, Group Settings says known members`() =
        runBlocking {
            val state = settings(memberPhone(announced = false))
            assertEquals(true, knownOnly(state))
            assertEquals("3 known members", header(state))
        }

    @Test
    fun `on the creator's phone, Group Settings says members`() =
        runBlocking {
            val phone = memberPhone(announced = false)
            phone.selfCreated += GID
            // The creator's phone: the group's creator is this phone.
            phone.infos[GID] =
                co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol.serializeGroupInfo(
                    phone.info(GID).copy(creatorAddress = phone.self),
                )
            val state = settings(phone)
            assertEquals("fixture: the creator", true, state.isCreator)
            assertEquals(false, knownOnly(state))
            assertEquals("3 members", header(state))
        }
}
